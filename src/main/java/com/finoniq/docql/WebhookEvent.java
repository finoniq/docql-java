package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * A verified DocQL webhook event, returned by {@link Webhooks#verify} (D-08,
 * D-25).
 *
 * <p>{@link #type()} is an open string: compare it with the
 * {@link WebhookEventTypes} constants and treat any other value as an event
 * type this SDK does not know yet. {@link #status()} compares with the
 * {@link JobStatus} constants. {@link #data()} is the event's raw
 * {@code data} object, unknown keys included; a {@code webhook.test} event
 * has an empty one.
 *
 * <p>The event is immutable and is not {@code Serializable}: keep
 * {@link #id()} to dedupe retried deliveries.
 */
public final class WebhookEvent {

    private final String id;
    private final String type;
    private final Instant timestamp;
    private final String jobId;
    private final String status;
    private final JsonNode data;

    WebhookEvent(String id, String type, Instant timestamp, String jobId, String status, JsonNode data) {
        this.id = id;
        this.type = type;
        this.timestamp = timestamp;
        this.jobId = jobId;
        this.status = status;
        this.data = data.deepCopy();
    }

    /**
     * Returns the event id, the same across every retry of one delivery.
     *
     * @return the non-empty event id
     */
    public String id() {
        return id;
    }

    /**
     * Returns the event type, for example {@link WebhookEventTypes#JOB_SUCCEEDED}.
     *
     * @return the non-empty event type, an open set
     */
    public String type() {
        return type;
    }

    /**
     * Returns when DocQL created the event.
     *
     * @return the event time
     */
    public Instant timestamp() {
        return timestamp;
    }

    /**
     * Returns {@code data.job_id} when it is a JSON string.
     *
     * @return the job id, or null when absent or not a string (and for
     *         {@code webhook.test})
     */
    public String jobId() {
        return jobId;
    }

    /**
     * Returns {@code data.status} when it is a JSON string; compare it with
     * the {@link JobStatus} constants.
     *
     * @return the job status, or null when absent or not a string (and for
     *         {@code webhook.test})
     */
    public String status() {
        return status;
    }

    /**
     * Returns a copy of the event's raw {@code data} object, unknown keys
     * included; changing the copy never changes the event.
     *
     * @return a JSON object, never null
     */
    public JsonNode data() {
        return data.deepCopy();
    }
}
