package com.finoniq.docql;

/**
 * A call that ran out of time (D-06): the per-request deadline
 * ({@link DocQL.Builder#timeout(java.time.Duration)}, 660&nbsp;s by default,
 * covering the upload until the response headers arrive) or the 10&nbsp;s
 * connect timeout of the SDK-built client expired.
 */
public class DocQLTimeoutException extends DocQLConnectionException {

    private static final long serialVersionUID = 1L;

    DocQLTimeoutException(String message) {
        super(message);
    }

    DocQLTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
