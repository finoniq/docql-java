package com.finoniq.docql;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * {@link DocQL#waitFor(String)} ran out of time before the job finished
 * (OD-5): 15 minutes by default, or the wait timeout the caller set.
 *
 * <p>The job was not cancelled: it may still finish, and
 * {@link DocQL#getJob(String)} or another {@link DocQL#waitFor(String)} with
 * {@link #jobId()} picks it up again. Part of the timeout family, so a
 * {@code catch (DocQLTimeoutException e)} also catches it.
 */
public class DocQLWaitTimeoutException extends DocQLTimeoutException {

    private static final long serialVersionUID = 1L;

    /** The job that did not finish in time. */
    private final String jobId;

    DocQLWaitTimeoutException(String jobId, Duration timeout, String apiKey) {
        super("wait for job " + Decoder.sanitise(jobId, apiKey) + " timed out after " + seconds(timeout)
                + " s; the job was not cancelled");
        this.jobId = jobId;
    }

    /**
     * Returns the id of the job that did not finish in time.
     *
     * @return the job id
     */
    public String jobId() {
        return jobId;
    }

    /** Whole seconds as an integer, a fraction without trailing zeros (900, 0.5). */
    private static String seconds(Duration timeout) {
        return BigDecimal.valueOf(timeout.getSeconds())
                .add(BigDecimal.valueOf(timeout.getNano(), 9))
                .stripTrailingZeros()
                .toPlainString();
    }
}
