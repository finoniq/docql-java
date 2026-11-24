package com.finoniq.docql;

import java.time.Duration;

/**
 * A test-only stand-in for the 0.2.0 {@code DocQLException}: the same
 * superclass, {@code serialVersionUID} and nine fields with the same names
 * and types, and no {@code metadata} field (WR-02). Its binary name
 * {@code com.finoniq.docql.OldDocQLExcept} has the same 32 bytes as
 * {@code com.finoniq.docql.DocQLException}, so renaming it in a serialized
 * stream keeps the stream valid and yields what 0.2.0 wrote.
 */
final class OldDocQLExcept extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String reason;
    private final String requestId;
    private final Duration retryAfter;
    private final boolean retryable;
    private final String serverMessage;
    private final String body;
    private final String jobId;
    private final String idempotencyKey;

    OldDocQLExcept(int status, String reason, String requestId, Duration retryAfter, boolean retryable,
            String serverMessage, String body, String jobId, String idempotencyKey) {
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
}
