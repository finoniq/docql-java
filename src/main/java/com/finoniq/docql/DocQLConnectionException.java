package com.finoniq.docql;

/**
 * A transport failure: no usable HTTP response arrived — a connect failure,
 * TLS, reset, broken pipe, a short body or a timeout (D-06). Sibling of
 * {@link DocQLException}; callers catch both in a multi-catch. The message is
 * composed by the SDK and never copies a JDK exception message; the cause is
 * kept.
 */
public class DocQLConnectionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    DocQLConnectionException(String message) {
        super(message);
    }

    DocQLConnectionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Not implemented yet (26-08 Task 2 RED).
     *
     * @return null
     */
    public String idempotencyKey() {
        return null;
    }
}
