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

    /** The Idempotency-Key of the submit that failed in transit; null otherwise. */
    private final String idempotencyKey;

    DocQLConnectionException(String message) {
        this(message, null, null);
    }

    DocQLConnectionException(String message, Throwable cause) {
        this(message, cause, null);
    }

    DocQLConnectionException(String message, Throwable cause, String idempotencyKey) {
        super(message, cause);
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * Returns the {@code Idempotency-Key} of the submit that failed in
     * transit: the job may exist, and retrying
     * {@link DocQL#submitDocument(DocFile, Instruction, QueryOptions)} with
     * the same key never creates a second one (D-08). Null outside a submit.
     * Never part of {@link #getMessage()}.
     *
     * @return the idempotency key, or null
     */
    public String idempotencyKey() {
        return idempotencyKey;
    }
}
