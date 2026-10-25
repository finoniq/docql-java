package com.finoniq.docql;

import java.time.Duration;
import java.time.Instant;

/**
 * One DocQL job as the API answered it: returned by
 * {@link DocQL#submitDocument(DocFile, Instruction)}, {@link DocQL#getJob(String)}
 * and {@link DocQL#waitFor(String)}.
 *
 * <p>{@link #status()} is an open string (D-09): compare it with the
 * {@link JobStatus} constants; a status from a newer DocQL release is kept
 * verbatim. A final class, not a record, so fields can be added later without
 * breaking callers (JSDK-03). {@link #toString()} shows the job id and status
 * only, never the idempotency key or result data.
 */
public final class Job {

    private final String jobId;
    private final String status;
    private final Integer pageCount;
    private final Integer pagesDone;
    private final String requestId;
    private final Instant createdAt;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final Instant expiresAt;
    private final QueryResult result;
    private final DocQLException error;
    private final Duration retryAfter;
    private final String idempotencyKey;

    Job(String jobId, String status, Integer pageCount, Integer pagesDone, String requestId,
            Instant createdAt, Instant startedAt, Instant finishedAt, Instant expiresAt,
            QueryResult result, DocQLException error, Duration retryAfter, String idempotencyKey) {
        this.jobId = jobId;
        this.status = status;
        this.pageCount = pageCount;
        this.pagesDone = pagesDone;
        this.requestId = requestId;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.expiresAt = expiresAt;
        this.result = result;
        this.error = error;
        this.retryAfter = retryAfter;
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * Returns the job id DocQL assigned; pass it to {@link DocQL#getJob(String)}
     * or {@link DocQL#waitFor(String)}.
     *
     * @return the job id
     */
    public String jobId() {
        return jobId;
    }

    /**
     * Returns the job status, e.g. {@link JobStatus#QUEUED}; an unknown status
     * is kept verbatim (D-09).
     *
     * @return the job status
     */
    public String status() {
        return status;
    }

    /**
     * Returns the document's page count from the job metadata; null when
     * absent or not a JSON integer.
     *
     * @return the page count, or null
     */
    public Integer pageCount() {
        return pageCount;
    }

    /**
     * Returns how many pages are done so far; null when absent or not a JSON
     * integer.
     *
     * @return the pages done, or null
     */
    public Integer pagesDone() {
        return pagesDone;
    }

    /**
     * Returns the {@code X-Request-Id} of the answer this Job was read from;
     * null when absent.
     *
     * @return the answer's request id, or null
     */
    public String requestId() {
        return requestId;
    }

    /**
     * Returns when the job was created; null when absent or unparsable.
     *
     * @return the creation time, or null
     */
    public Instant createdAt() {
        return createdAt;
    }

    /**
     * Returns when a worker started the job; null until then.
     *
     * @return the start time, or null
     */
    public Instant startedAt() {
        return startedAt;
    }

    /**
     * Returns when the job finished; null until then.
     *
     * @return the finish time, or null
     */
    public Instant finishedAt() {
        return finishedAt;
    }

    /**
     * Returns when DocQL forgets the finished job; null until it finished.
     *
     * @return the expiry time, or null
     */
    public Instant expiresAt() {
        return expiresAt;
    }

    /**
     * Returns the extraction result of a succeeded job, typed like
     * {@link DocQL#queryDocument(DocFile, Instruction)}'s answer; null
     * otherwise.
     *
     * @return the result, or null
     */
    public QueryResult result() {
        return result;
    }

    /**
     * Returns why a failed job failed, with {@link DocQLException#jobId()}
     * set and {@link DocQLException#retryable()} false (a failed job is
     * final); null otherwise.
     *
     * @return the job's error, or null
     */
    public DocQLException error() {
        return error;
    }

    /**
     * Returns the answer's {@code Retry-After} in whole seconds, for callers
     * who poll {@link DocQL#getJob(String)} themselves; null when absent.
     *
     * @return the retry-after duration, or null
     */
    public Duration retryAfter() {
        return retryAfter;
    }

    /**
     * Returns the {@code Idempotency-Key} the submit sent; set only on the Job
     * {@link DocQL#submitDocument(DocFile, Instruction)} returns, null
     * otherwise.
     *
     * @return the idempotency key, or null
     */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    /**
     * The job id and status only: never the idempotency key or result data.
     */
    @Override
    public String toString() {
        return "Job{jobId=" + jobId + ", status=" + status + "}";
    }
}
