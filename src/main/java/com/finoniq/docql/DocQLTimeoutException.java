package com.finoniq.docql;

/**
 * A call that ran out of time (D-06): the per-request deadline
 * ({@link DocQL.Builder#timeout(java.time.Duration)}, 660&nbsp;s by default,
 * covering the upload until the response headers arrive), the 30&nbsp;s
 * deadline of a job poll or cancel, or the 10&nbsp;s connect timeout of the
 * SDK-built client expired. A submit timeout carries the idempotency key.
 */
public class DocQLTimeoutException extends DocQLConnectionException {

    private static final long serialVersionUID = 1L;

    DocQLTimeoutException(String message) {
        super(message);
    }

    DocQLTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    DocQLTimeoutException(String message, Throwable cause, String idempotencyKey) {
        super(message, cause, idempotencyKey);
    }
}
