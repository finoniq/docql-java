package com.finoniq.docql;

/**
 * The webhook event types DocQL sends today, as {@code String} constants to
 * compare {@link WebhookEvent#type()} with (D-15, D-27).
 *
 * <p>An open set, not an enum: a type a newer DocQL release adds reaches the
 * receiver verbatim through {@link WebhookEvent#type()} and never fails
 * verification, so a receiver must accept, and may ignore, a type it does not
 * know. Compare {@link WebhookEvent#status()} with the {@link JobStatus}
 * constants.
 */
public final class WebhookEventTypes {

    /** A job finished with a result: {@link WebhookEvent#status()} is {@link JobStatus#SUCCEEDED}. */
    public static final String JOB_SUCCEEDED = "job.succeeded";

    /** A job finished without a result: {@link WebhookEvent#status()} is {@link JobStatus#FAILED}. */
    public static final String JOB_FAILED = "job.failed";

    /** A job was cancelled: {@link WebhookEvent#status()} is {@link JobStatus#CANCELLED}. */
    public static final String JOB_CANCELLED = "job.cancelled";

    /**
     * A test delivery sent from the console: {@link WebhookEvent#data()} is
     * empty, and {@link WebhookEvent#jobId()} and {@link WebhookEvent#status()}
     * are null.
     */
    public static final String WEBHOOK_TEST = "webhook.test";

    private WebhookEventTypes() {
    }
}
