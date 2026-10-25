package com.finoniq.docql;

/**
 * The job statuses DocQL answers today, as {@code String} constants to
 * compare {@link Job#status()} with (D-09).
 *
 * <p>An open set, not an enum: a status a newer DocQL release adds reaches
 * the caller verbatim through {@link Job#status()}, and
 * {@link DocQL#waitFor(String)} keeps polling a status it does not know
 * until its deadline.
 */
public final class JobStatus {

    /** Accepted and waiting for a worker. */
    public static final String QUEUED = "queued";

    /** A worker is extracting the document. */
    public static final String RUNNING = "running";

    /** Finished: {@link Job#result()} carries the extraction result. */
    public static final String SUCCEEDED = "succeeded";

    /** Finished without a result: {@link Job#error()} says why. */
    public static final String FAILED = "failed";

    /** Cancelled at the caller's request before it finished. */
    public static final String CANCELLED = "cancelled";

    private JobStatus() {
    }
}
