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
 * ever contains the API key.
 */
public class DocQLException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String reason;
    private final String requestId;
    private final Duration retryAfter;
    private final boolean retryable;
    private final String serverMessage;
    private final String body;

    DocQLException(int status, String reason, String requestId, Duration retryAfter,
            boolean retryable, String serverMessage, String body) {
        super((String) null);
        this.status = status;
        this.reason = reason;
        this.requestId = requestId;
        this.retryAfter = retryAfter;
        this.retryable = retryable;
        this.serverMessage = serverMessage;
        this.body = body;
    }

    /** Returns the HTTP status code of the response. */
    public int status() {
        return status;
    }

    /**
     * Returns the envelope reason, e.g. {@code "invalid_api_key"}; null when
     * the response carried no DocQL envelope.
     */
    public String reason() {
        return reason;
    }

    /**
     * Returns the request id from the envelope metadata, else the
     * {@code x-request-id} response header; null when neither is present.
     */
    public String requestId() {
        return requestId;
    }

    /**
     * Returns the parsed {@code Retry-After} header in whole seconds; null
     * when the header is absent or unparsable (D-21).
     */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** Returns whether the call may be retried: true only for 429 and 503. */
    public boolean retryable() {
        return retryable;
    }

    /**
     * Returns {@code error_info} from the envelope, scrubbed of the API key;
     * null when absent.
     */
    public String serverMessage() {
        return serverMessage;
    }

    /**
     * Returns the response body, made valid UTF-8, scrubbed of the API key
     * and truncated to 2048 characters.
     */
    public String body() {
        return body;
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
            if (!extras.isEmpty()) {
                text.append(" (").append(String.join(", ", extras)).append(')');
            }
            return text.toString();
        }
        StringBuilder text = new StringBuilder();
        text.append(status).append(" (non-DocQL response): ").append(snippet(body));
        if (requestId != null && !requestId.isEmpty()) {
            text.append(" (request_id=").append(requestId).append(')');
        }
        return text.toString();
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
