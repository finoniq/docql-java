package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies DocQL webhook deliveries (D-06..D-16). DocQL signs every delivery
 * with the open Standard Webhooks scheme: the {@code webhook-id},
 * {@code webhook-timestamp} and {@code webhook-signature} headers carry an
 * HMAC-SHA256 over the id, the timestamp and the raw body.
 *
 * <p>A receiver has no API key, so this is a holder of static methods and
 * needs no {@link DocQL} client. Each call checks, in this fixed order: the
 * tolerance, every secret, the presence of the three headers, the timestamp
 * syntax, the {@code v1} signature entries, the HMAC, the tolerance window
 * and finally the event body. Request data (the body and the header names
 * and values) can only produce a {@link WebhookEvent} or a
 * {@link WebhookVerificationException}.
 *
 * <p>The helper does not detect replays: dedupe on {@link WebhookEvent#id()},
 * which stays the same across retries.
 */
public final class Webhooks {

    /** The default allowed clock skew, either way, inclusive. */
    static final Duration DEFAULT_TOLERANCE = Duration.ofSeconds(300);

    private static final String SECRET_PREFIX = "whsec_";
    private static final int SECRET_MIN_BYTES = 24;
    private static final int SECRET_MAX_BYTES = 64;
    private static final int SIGNATURE_BYTES = 32;
    private static final int TIMESTAMP_MAX_DIGITS = 12;

    private static final String HEADER_ID = "webhook-id";
    private static final String HEADER_TIMESTAMP = "webhook-timestamp";
    private static final String HEADER_SIGNATURE = "webhook-signature";

    /** {@code \d} is ASCII-only without UNICODE_CHARACTER_CLASS. */
    private static final Pattern EVENT_TIMESTAMP =
            Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?Z");

    private Webhooks() {
    }

    /**
     * Verifies one DocQL webhook delivery with the default tolerance of 300
     * seconds and returns its event.
     *
     * @param body the raw request body exactly as received, before any JSON
     *        parsing
     * @param headers the request headers; names match without regard to
     *        ASCII case, and null keys, lists and values are skipped
     * @param secrets the endpoint's webhook secret, or both secrets while a
     *        rotation overlaps; any secret may match
     * @return the verified event
     * @throws IllegalArgumentException if {@code body}, {@code headers},
     *         {@code secrets} or a secret is null, the list is empty, or a
     *         secret is not {@code whsec_} plus canonical padded base64 of 24
     *         to 64 bytes; the message names the position, never the secret
     * @throws WebhookVerificationException if the delivery is not an
     *         authentic, timely DocQL webhook event; {@code reason()} says why
     */
    public static WebhookEvent verify(byte[] body, Map<String, List<String>> headers, List<String> secrets) {
        return verifyAt(body, headers, secrets, Instant.now().getEpochSecond());
    }

    /**
     * Verifies one DocQL webhook delivery and returns its event.
     *
     * @param body the raw request body exactly as received, before any JSON
     *        parsing
     * @param headers the request headers; names match without regard to
     *        ASCII case, and null keys, lists and values are skipped
     * @param secrets the endpoint's webhook secret, or both secrets while a
     *        rotation overlaps; any secret may match
     * @param tolerance the allowed clock skew either way, inclusive, compared
     *        in whole seconds
     * @return the verified event
     * @throws IllegalArgumentException if {@code tolerance} is null, zero or
     *         negative, if {@code body}, {@code headers}, {@code secrets} or a
     *         secret is null, the list is empty, or a secret is not
     *         {@code whsec_} plus canonical padded base64 of 24 to 64 bytes;
     *         the message names the position, never the secret
     * @throws WebhookVerificationException if the delivery is not an
     *         authentic, timely DocQL webhook event; {@code reason()} says why
     */
    public static WebhookEvent verify(byte[] body, Map<String, List<String>> headers, List<String> secrets,
            Duration tolerance) {
        return verifyAt(body, headers, secrets, tolerance, Instant.now().getEpochSecond());
    }

    /** The three-argument {@link #verify} against a given clock: the default tolerance of 300 seconds. */
    static WebhookEvent verifyAt(byte[] body, Map<String, List<String>> headers, List<String> secrets,
            long nowEpochSecond) {
        return verifyAt(body, headers, secrets, DEFAULT_TOLERANCE, nowEpochSecond);
    }

    /** {@link #verify} against a given clock: {@code nowEpochSecond} in whole unix seconds (D-12). */
    static WebhookEvent verifyAt(byte[] body, Map<String, List<String>> headers, List<String> secrets,
            Duration tolerance, long nowEpochSecond) {
        checkTolerance(tolerance);
        List<byte[]> keys = decodeSecrets(secrets);
        if (body == null) {
            throw new IllegalArgumentException("webhook body must not be null");
        }
        if (headers == null) {
            throw new IllegalArgumentException("webhook headers must not be null");
        }

        List<String> ids = new ArrayList<>();
        List<String> timestamps = new ArrayList<>();
        List<String> signatures = new ArrayList<>();
        collectHeaders(headers, ids, timestamps, signatures);
        if (ids.isEmpty() || timestamps.isEmpty() || signatures.isEmpty()) {
            throw new WebhookVerificationException("missing_header");
        }
        String msgId = single(ids);
        String timestamp = single(timestamps);
        if (msgId == null || timestamp == null) {
            throw new WebhookVerificationException("missing_header");
        }

        if (!isTimestampText(timestamp)) {
            throw new WebhookVerificationException("invalid_timestamp");
        }
        long ts = Long.parseLong(timestamp);

        List<byte[]> entries = signatureEntries(String.join(" ", signatures));
        if (entries.isEmpty()) {
            throw new WebhookVerificationException("invalid_signature_header");
        }
        byte[] idBytes = strictUtf8(msgId);
        if (idBytes == null || !anyMatch(keys, idBytes, timestamp, body, entries)) {
            // An id that is not valid UTF-16 (a lone surrogate) cannot have been signed.
            throw new WebhookVerificationException("signature_mismatch");
        }

        // Whole seconds are exact: skew is an integer, so skew > tolerance
        // holds exactly when skew > the tolerance's whole seconds.
        long allowed = tolerance.getSeconds();
        long skew = nowEpochSecond - ts;
        if (skew > allowed) {
            throw new WebhookVerificationException("timestamp_too_old");
        }
        if (-skew > allowed) {
            throw new WebhookVerificationException("timestamp_too_new");
        }
        return parseEvent(body);
    }

    private static void checkTolerance(Duration tolerance) {
        if (tolerance == null) {
            throw new IllegalArgumentException("webhook tolerance must not be null");
        }
        if (tolerance.isZero() || tolerance.isNegative()) {
            throw new IllegalArgumentException("webhook tolerance must be greater than zero");
        }
    }

    private static List<byte[]> decodeSecrets(List<String> secrets) {
        if (secrets == null) {
            throw new IllegalArgumentException("webhook secrets must not be null");
        }
        if (secrets.isEmpty()) {
            throw new IllegalArgumentException("no webhook secrets: pass at least one webhook secret");
        }
        List<byte[]> keys = new ArrayList<>(secrets.size());
        int position = 0;
        for (String secret : secrets) {
            position++;
            if (secret == null) {
                throw new IllegalArgumentException("webhook secret " + position + ": must not be null");
            }
            keys.add(decodeSecret(position, secret));
        }
        return keys;
    }

    private static byte[] decodeSecret(int position, String secret) {
        String text = trimSecret(secret);
        if (!text.startsWith(SECRET_PREFIX)) {
            throw new IllegalArgumentException("webhook secret " + position + ": does not start with "
                    + SECRET_PREFIX);
        }
        byte[] raw = canonicalBase64(text.substring(SECRET_PREFIX.length()));
        if (raw == null) {
            throw new IllegalArgumentException("webhook secret " + position + ": is not canonical padded base64");
        }
        if (raw.length < SECRET_MIN_BYTES || raw.length > SECRET_MAX_BYTES) {
            throw new IllegalArgumentException("webhook secret " + position + ": must decode to "
                    + SECRET_MIN_BYTES + " to " + SECRET_MAX_BYTES + " bytes");
        }
        return raw;
    }

    /** D-29: exactly space, tab, LF and CR at both ends; never trim() or strip(). */
    private static String trimSecret(String secret) {
        int start = 0;
        int end = secret.length();
        while (start < end && isSecretSpace(secret.charAt(start))) {
            start++;
        }
        while (end > start && isSecretSpace(secret.charAt(end - 1))) {
            end--;
        }
        return secret.substring(start, end);
    }

    private static boolean isSecretSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /**
     * The decoded bytes when {@code text} is canonical padded standard
     * base64, else null. The basic decoder accepts missing padding and
     * non-zero trailing bits; the re-encode check refuses both.
     */
    private static byte[] canonicalBase64(String text) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return Base64.getEncoder().encodeToString(raw).equals(text) ? raw : null;
    }

    /**
     * Collects the non-empty values of the three headers. A key matches only
     * when every char is ASCII and it equals the name ignoring case, so a
     * look-alike such as the Kelvin sign never matches (D-29). Null keys,
     * lists and values, and anything that is not a String, are skipped.
     */
    private static void collectHeaders(Map<?, ?> headers, List<String> ids, List<String> timestamps,
            List<String> signatures) {
        for (Map.Entry<?, ?> entry : headers.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof List)) {
                continue;
            }
            String key = (String) entry.getKey();
            if (!isAscii(key)) {
                continue;
            }
            List<String> target;
            if (key.equalsIgnoreCase(HEADER_ID)) {
                target = ids;
            } else if (key.equalsIgnoreCase(HEADER_TIMESTAMP)) {
                target = timestamps;
            } else if (key.equalsIgnoreCase(HEADER_SIGNATURE)) {
                target = signatures;
            } else {
                continue;
            }
            for (Object value : (List<?>) entry.getValue()) {
                if (value instanceof String && !((String) value).isEmpty()) {
                    target.add((String) value);
                }
            }
        }
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    /** The one distinct value, or null when two or more different values remain. */
    private static String single(List<String> values) {
        Set<String> distinct = new LinkedHashSet<>(values);
        return distinct.size() == 1 ? distinct.iterator().next() : null;
    }

    /** 1 to 12 chars, each '0'..'9': no sign, no space, no other digit script. */
    private static boolean isTimestampText(String text) {
        if (text.isEmpty() || text.length() > TIMESTAMP_MAX_DIGITS) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * The usable entries: split on U+0020 only (a tab or newline stays inside
     * a piece and spoils it), the exact {@code v1,} prefix, then canonical
     * padded base64 of exactly 32 bytes. Everything else is skipped.
     */
    private static List<byte[]> signatureEntries(String header) {
        List<byte[]> entries = new ArrayList<>();
        for (String piece : header.split(" ", -1)) {
            if (!piece.startsWith("v1,")) {
                continue;
            }
            byte[] raw = canonicalBase64(piece.substring(3));
            if (raw != null && raw.length == SIGNATURE_BYTES) {
                entries.add(raw);
            }
        }
        return entries;
    }

    /** The UTF-8 bytes of {@code text}, or null when it holds a lone surrogate. */
    private static byte[] strictUtf8(String text) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] out = new byte[encoded.remaining()];
            encoded.get(out);
            return out;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * HMAC-SHA256 once per secret over {@code id.timestamp.body}, with the
     * timestamp text exactly as received, compared in constant time against
     * every usable entry (D-14).
     */
    private static boolean anyMatch(List<byte[]> keys, byte[] idBytes, String timestamp, byte[] body,
            List<byte[]> entries) {
        byte[] stamp = timestamp.getBytes(StandardCharsets.US_ASCII);
        for (byte[] key : keys) {
            byte[] expected;
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
                mac.update(idBytes);
                mac.update((byte) '.');
                mac.update(stamp);
                mac.update((byte) '.');
                mac.update(body);
                expected = mac.doFinal();
            } catch (GeneralSecurityException e) {
                // HmacSHA256 is a required algorithm on every Java platform.
                throw new IllegalStateException("HmacSHA256 is unavailable", e);
            }
            for (byte[] entry : entries) {
                if (MessageDigest.isEqual(expected, entry)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The event of a signed body (D-08, D-15): one JSON object with a
     * non-empty string {@code id} and {@code type}, a {@code timestamp} of the
     * form {@code YYYY-MM-DDTHH:MM:SS[.fraction]Z} and an object {@code data}.
     * Unknown keys are kept in {@code data} and ignored at the top level.
     * Anything else is invalid_payload.
     */
    private static WebhookEvent parseEvent(byte[] body) {
        JsonNode root = Decoder.parseWebhookBody(body);
        if (root == null || !root.isObject()) {
            throw new WebhookVerificationException("invalid_payload");
        }
        JsonNode id = root.get("id");
        JsonNode type = root.get("type");
        JsonNode data = root.get("data");
        if (id == null || !id.isTextual() || id.textValue().isEmpty()
                || type == null || !type.isTextual() || type.textValue().isEmpty()
                || data == null || !data.isObject()) {
            throw new WebhookVerificationException("invalid_payload");
        }
        Instant timestamp = parseTimestamp(root.get("timestamp"));
        JsonNode jobId = data.get("job_id");
        JsonNode status = data.get("status");
        return new WebhookEvent(id.textValue(), type.textValue(), timestamp,
                jobId != null && jobId.isTextual() ? jobId.textValue() : null,
                status != null && status.isTextual() ? status.textValue() : null,
                data);
    }

    /**
     * {@code YYYY-MM-DDTHH:MM:SS[.1-9 digits]Z} in ASCII. Instant.parse alone
     * would take a leap second (23:59:60) and 24:00:00, so the hour, minute
     * and second are range-checked first; year 0000 is refused too, as in the
     * Python and Go SDKs (OD-45.1-01). Instant.parse then refuses a day the
     * month does not have, such as February 30.
     */
    private static Instant parseTimestamp(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw new WebhookVerificationException("invalid_payload");
        }
        String text = node.textValue();
        Matcher m = EVENT_TIMESTAMP.matcher(text);
        if (!m.matches()
                || Integer.parseInt(m.group(1)) == 0
                || Integer.parseInt(m.group(4)) > 23
                || Integer.parseInt(m.group(5)) > 59
                || Integer.parseInt(m.group(6)) > 59) {
            throw new WebhookVerificationException("invalid_payload");
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new WebhookVerificationException("invalid_payload");
        }
    }
}
