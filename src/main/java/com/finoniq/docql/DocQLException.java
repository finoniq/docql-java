package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Every non-2xx API response, and a 2xx response the SDK cannot use
 * (D-06). Unchecked; thrown by the queryDocument calls.
 *
 * <p>{@link #getMessage()} renders one of two shared string forms: the
 * envelope form {@code "<status> <reason>[: <serverMessage>]
 * (request_id=…, retry_after=Ns)"} and, for responses without a DocQL
 * envelope, {@code "<status> (non-DocQL response): <snippet>"}. Neither form
 * ever contains the API key. An error from a job method adds
 * {@code job_id=<id>} to the parenthesised tail; without a job id both forms
 * are exactly the v0.1.0 ones. The idempotency key never appears.
 */
public class DocQLException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The HTTP status code of the response. */
    private final int status;
    /** The envelope reason; null without a DocQL envelope. */
    private final String reason;
    /** The request id from the metadata, else {@code x-request-id}; else null. */
    private final String requestId;
    /** The parsed {@code Retry-After}; null when absent or unparsable. */
    private final Duration retryAfter;
    /** Whether the call may be retried: true only for 429 and 503. */
    private final boolean retryable;
    /** The envelope {@code error_info}, scrubbed of the key; null when absent. */
    private final String serverMessage;
    /** The response body, valid UTF-8, scrubbed, capped at 2048 characters. */
    private final String body;
    /** The job the error belongs to; null outside the job methods. */
    private final String jobId;
    /** The Idempotency-Key of the submit that failed; null otherwise. */
    private final String idempotencyKey;
    /**
     * The raw metadata object of the error body, scrubbed of the API key;
     * an empty object when the body carried none. Declared as ObjectNode,
     * which is Serializable, so the exception stays serializable.
     */
    private final ObjectNode metadata;

    DocQLException(int status, String reason, String requestId, Duration retryAfter,
            boolean retryable, String serverMessage, String body) {
        this(status, reason, requestId, retryAfter, retryable, serverMessage, body, null, null);
    }

    DocQLException(int status, String reason, String requestId, Duration retryAfter,
            boolean retryable, String serverMessage, String body, String jobId, String idempotencyKey) {
        this(status, reason, requestId, retryAfter, retryable, serverMessage, body, jobId, idempotencyKey, null);
    }

    /**
     * The full constructor. A metadata that is null or not a JSON object is
     * stored as an empty object, so {@link #metadata()} is never null.
     */
    DocQLException(int status, String reason, String requestId, Duration retryAfter,
            boolean retryable, String serverMessage, String body, String jobId, String idempotencyKey,
            JsonNode metadata) {
        super((String) null);
        this.status = status;
        this.reason = reason;
        this.requestId = requestId;
        this.retryAfter = retryAfter;
        this.retryable = retryable;
        this.serverMessage = serverMessage;
        this.body = body;
        this.jobId = jobId;
        this.idempotencyKey = idempotencyKey;
        this.metadata = metadata instanceof ObjectNode
                ? (ObjectNode) metadata
                : JsonNodeFactory.instance.objectNode();
    }

    /**
     * This error with the job context set: a job id, an idempotency key and
     * the retryable flag (a failed job is never retryable). Everything else,
     * the metadata included, is kept.
     */
    DocQLException withJob(String newJobId, String newIdempotencyKey, boolean newRetryable) {
        return new DocQLException(status, reason, requestId, retryAfter, newRetryable, serverMessage, body,
                newJobId, newIdempotencyKey, metadata);
    }

    /**
     * Returns the HTTP status code of the response.
     *
     * @return the HTTP status code
     */
    public int status() {
        return status;
    }

    /**
     * Returns the envelope reason, e.g. {@code "invalid_api_key"}; null when
     * the response carried no DocQL envelope.
     *
     * @return the envelope reason, or null
     */
    public String reason() {
        return reason;
    }

    /**
     * Returns the request id from the envelope metadata, else the
     * {@code x-request-id} response header; null when neither is present.
     *
     * @return the request id, or null
     */
    public String requestId() {
        return requestId;
    }

    /**
     * Returns the parsed {@code Retry-After} header in whole seconds; null
     * when the header is absent or unparsable (D-21).
     *
     * @return the retry-after duration, or null
     */
    public Duration retryAfter() {
        return retryAfter;
    }

    /**
     * Returns whether the call may be retried: true only for 429 and 503.
     *
     * @return true only for 429 and 503
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * Returns {@code error_info} from the envelope, scrubbed of the API key;
     * null when absent.
     *
     * @return the server message, or null
     */
    public String serverMessage() {
        return serverMessage;
    }

    /**
     * Returns the response body, made valid UTF-8, scrubbed of the API key
     * and truncated to 2048 characters.
     *
     * @return the scrubbed, truncated response body
     */
    public String body() {
        return body;
    }

    /**
     * Returns the id of the job this error belongs to: set on a failed job's
     * error and on every error {@link DocQL#getJob(String)} and
     * {@link DocQL#waitFor(String)} throw; null otherwise.
     *
     * @return the job id, or null
     */
    public String jobId() {
        return jobId;
    }

    /**
     * Returns the {@code Idempotency-Key} of the submit this error answered:
     * retrying {@link DocQL#submitDocument(DocFile, Instruction, QueryOptions)}
     * with the same key never creates a second job (D-08). Null outside a
     * submit. Never part of {@link #getMessage()}.
     *
     * @return the idempotency key, or null
     */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    /**
     * Returns the raw {@code metadata} object of the error body, so a refusal
     * can be read without parsing {@link #body()}. For
     * {@code spreadsheet_too_large} it holds the measured value under its
     * axis key ({@code cells}, {@code runes}, {@code estimated_output_tokens}
     * or {@code bytes}) and {@code limit}; for {@code too_many_pages} it
     * holds {@code pages} and {@code limit}; {@code reason} and
     * {@code request_id} sit next to them. Read a number with
     * {@code metadata().path("limit").asLong()}: integers keep their exact
     * value, and one above the long range stays a BigInteger node, on which
     * {@code asLong()} silently wraps. When a value may be that large, check
     * {@code canConvertToLong()} first or read it with
     * {@code bigIntegerValue()}.
     *
     * <p>The node is shared, not copied: the copy of this exception that a
     * job method makes to add the job id returns the same node, so a change
     * shows through both. Treat it as read-only.
     *
     * <p>Never null: an empty object when the body was not JSON or carried no
     * metadata object, for a 2xx the SDK could not use, and for an exception
     * deserialized from a stream that an SDK before 0.3.0 wrote, which had no
     * metadata. The API key is replaced with {@code ***} in every field name
     * and text value. The metadata is never part of {@link #getMessage()}.
     *
     * @return the error body's metadata object, never null
     */
    public JsonNode metadata() {
        // A stream written before 0.3.0 has no metadata field, and a final
        // field cannot be set while deserializing (WR-02).
        return metadata != null ? metadata : JsonNodeFactory.instance.objectNode();
    }

    @Override
    public String getMessage() {
        if (reason != null) {
            StringBuilder text = new StringBuilder();
            text.append(status).append(' ').append(reason);
            if (serverMessage != null && !serverMessage.isEmpty()) {
                text.append(": ").append(serverMessage);
            }
            List<String> extras = new ArrayList<>();
            if (requestId != null && !requestId.isEmpty()) {
                extras.add("request_id=" + requestId);
            }
            if (retryAfter != null) {
                extras.add("retry_after=" + retryAfter.toSeconds() + "s");
            }
            if (jobId != null) {
                extras.add("job_id=" + printableJobId());
            }
            if (!extras.isEmpty()) {
                text.append(" (").append(String.join(", ", extras)).append(')');
            }
            return text.toString();
        }
        StringBuilder text = new StringBuilder();
        text.append(status).append(" (non-DocQL response): ").append(snippet(body));
        List<String> tail = new ArrayList<>();
        if (requestId != null && !requestId.isEmpty()) {
            tail.add("request_id=" + requestId);
        }
        if (jobId != null) {
            tail.add("job_id=" + printableJobId());
        }
        if (!tail.isEmpty()) {
            text.append(" (").append(String.join(", ", tail)).append(')');
        }
        return text.toString();
    }

    /**
     * The job id inside the message: it may come from the caller or the
     * server, so control characters collapse and a hostile id cannot forge
     * log lines.
     */
    private String printableJobId() {
        return Decoder.sanitise(jobId, null);
    }

    /**
     * Collapses whitespace runs of the stored body to single spaces, trims,
     * and cuts the result at snippetMaxChars code points, or returns
     * {@code (empty body)}.
     */
    private static String snippet(String bodyText) {
        StringBuilder collapsed = new StringBuilder();
        boolean pendingSpace = false;
        for (int i = 0; i < bodyText.length(); ) {
            int codePoint = bodyText.codePointAt(i);
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = true;
            } else {
                if (pendingSpace && collapsed.length() > 0) {
                    collapsed.append(' ');
                }
                pendingSpace = false;
                collapsed.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        if (collapsed.length() == 0) {
            return "(empty body)";
        }
        return Decoder.cutAtCodePoints(collapsed.toString(), DocQL.SNIPPET_MAX_CHARS);
    }
}
