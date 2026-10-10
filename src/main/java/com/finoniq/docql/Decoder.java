package com.finoniq.docql;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Pure decode of a queryDocument response (D-04, D-06, D-11): a 2xx body
 * into a {@link QueryResult}, any other status into a {@link DocQLException}.
 * Both paths scrub the API key from everything they keep, and the parser runs
 * on the SDK's private mapper with explicit stream-read constraints — never a
 * caller's or a global mapper.
 */
final class Decoder {

    /**
     * The SDK's private mapper: bounded stream reads (D-11) and a rejection
     * of trailing data after the one top-level JSON value, matching the Go
     * decoder's dec.More() rule.
     */
    private static final JsonMapper MAPPER = JsonMapper.builder(
            JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(500)
                            .maxNumberLength(1000)
                            .maxStringLength(20_000_000)
                            .maxNameLength(50_000)
                            .maxDocumentLength(DocQL.SUCCESS_BODY_LIMIT)
                            .build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private Decoder() {
    }

    /**
     * Decodes a 2xx body into a QueryResult. A body that is not one JSON
     * object, or whose {@code data} is present, non-null and not an object,
     * is unusable: the thrown DocQLException names the problem instead of
     * delivering an empty result. The key scrubs the body stored on that
     * exception, symmetric with decodeError.
     */
    static QueryResult parseResult(int status, HttpHeaders headers, byte[] body, String key) {
        JsonNode root = parseTree(body);
        if (root == null || root.isMissingNode() || !root.isObject()) {
            throw unusable(status, headers, body, key, "unexpected non-JSON response");
        }
        JsonNode dataNode = root.get("data");
        JsonNode data;
        if (dataNode == null || dataNode.isNull()) {
            data = MAPPER.createObjectNode();
        } else if (!dataNode.isObject()) {
            throw unusable(status, headers, body, key, "unexpected response: data is not a JSON object");
        } else {
            data = dataNode;
        }
        JsonNode metadataNode = root.get("metadata");
        JsonNode metadata = metadataNode != null && metadataNode.isObject()
                ? metadataNode
                : MAPPER.createObjectNode();

        String requestId = "";
        JsonNode rid = metadata.get("request_id");
        if (rid != null && rid.isTextual() && !rid.textValue().isEmpty()) {
            requestId = rid.textValue();
        } else {
            String fromHeader = headerRequestId(headers);
            if (fromHeader != null) {
                requestId = fromHeader;
            }
        }
        return new QueryResult(data, requestId,
                jsonInt(metadata, "page_count"),
                jsonInt(metadata, "pages_ocr"),
                metadata);
    }

    /**
     * Decodes a non-2xx response into a DocQLException (D-06). A DocQL
     * envelope is present only when the body is a JSON object whose metadata
     * is an object with a string reason. Retry-After is parsed on every
     * non-2xx; retryable is true only for 429 and 503. Neither the stored
     * body nor the server message ever contains the API key, even when the
     * server echoes it back.
     */
    static DocQLException decodeError(int status, HttpHeaders headers, byte[] body, String key) {
        String reason = null;
        String serverMessage = null;
        String requestId = null;

        JsonNode root = parseTree(body);
        if (root != null && root.isObject()) {
            JsonNode metadata = root.get("metadata");
            if (metadata != null && metadata.isObject()) {
                JsonNode reasonNode = metadata.get("reason");
                if (reasonNode != null && reasonNode.isTextual()) {
                    reason = reasonNode.textValue();
                    JsonNode info = root.get("error_info");
                    if (info != null && info.isTextual()) {
                        serverMessage = scrub(info.textValue(), key);
                    }
                    JsonNode rid = metadata.get("request_id");
                    if (rid != null && rid.isTextual() && !rid.textValue().isEmpty()) {
                        requestId = rid.textValue();
                    }
                }
            }
        }
        if (requestId == null) {
            requestId = headerRequestId(headers);
        }
        return new DocQLException(status, reason, requestId,
                parseRetryAfter(headerValue(headers, "retry-after"), Instant.now()),
                status == 429 || status == 503,
                serverMessage,
                scrubAndTruncate(body, key));
    }

    /**
     * Returns the {@code Retry-After} header value as whole seconds, or null
     * when absent or unparsable (D-21). Delta-seconds pass through; an
     * HTTP-date becomes the seconds from now rounded up and clamped at 0, so
     * a past date means "retry now" rather than a negative sleep. A delta
     * that overflows is treated as absent, never as an error.
     */
    static Duration parseRetryAfter(String value, Instant now) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        if (!text.isEmpty() && text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            try {
                return Duration.ofSeconds(Long.parseLong(text));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            Instant at = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            long millis = Duration.between(now, at).toMillis();
            return Duration.ofSeconds(millis <= 0 ? 0 : (millis + 999) / 1000);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Replaces every occurrence of the key with {@code ***}; an empty key is
     * a no-op (D-12).
     */
    static String scrub(String text, String key) {
        if (key == null || key.isEmpty()) {
            return text;
        }
        return text.replace(key, "***");
    }

    /**
     * Makes a response body safe to store on an error: invalid UTF-8 becomes
     * the replacement character, every occurrence of the key is replaced
     * before any truncation (so a key straddling the cut can never survive as
     * a fragment), and the text is cut to errorBodyKeepChars code points.
     */
    static String scrubAndTruncate(byte[] bodyBytes, String key) {
        String text = new String(bodyBytes, StandardCharsets.UTF_8);
        text = scrub(text, key);
        return cutAtCodePoints(text, DocQL.ERROR_BODY_KEEP_CHARS);
    }

    /** Cuts text at max code points without splitting a surrogate pair. */
    static String cutAtCodePoints(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max));
    }

    private static DocQLException unusable(int status, HttpHeaders headers, byte[] body,
            String key, String message) {
        return new DocQLException(status, null, headerRequestId(headers), null, false,
                message, scrubAndTruncate(body, key));
    }

    private static JsonNode parseTree(byte[] body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private static Integer jsonInt(JsonNode metadata, String field) {
        JsonNode value = metadata.get(field);
        if (value != null && value.isIntegralNumber() && value.canConvertToInt()) {
            return value.intValue();
        }
        return null;
    }

    private static String headerValue(HttpHeaders headers, String name) {
        return headers.firstValue(name).orElse(null);
    }

    /** The x-request-id header value, or null when absent or empty. */
    private static String headerRequestId(HttpHeaders headers) {
        String value = headerValue(headers, "x-request-id");
        return value == null || value.isEmpty() ? null : value;
    }
}
