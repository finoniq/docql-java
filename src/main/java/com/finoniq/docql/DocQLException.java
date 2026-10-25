package com.finoniq.docql;

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

    DocQLException(int status, String reason, String requestId, Duration retryAfter,
            boolean retryable, String serverMessage, String body) {
        this(status, reason, requestId, retryAfter, retryable, serverMessage, body, null, null);
    }

    DocQLException(int status, String reason, String requestId, Duration retryAfter,
            boolean retryable, String serverMessage, String body, String jobId, String idempotencyKey) {
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
    }

    /**
     * This error with the job context set: a job id, an idempotency key and
     * the retryable flag (a failed job is never retryable). Everything else
     * is kept.
     */
    DocQLException withJob(String newJobId, String newIdempotencyKey, boolean newRetryable) {
        return new DocQLException(status, reason, requestId, retryAfter, newRetryable, serverMessage, body,
                newJobId, newIdempotencyKey);
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
