package com.finoniq.docql;

/**
 * The DocQL reasons (D-04): the SDK spelling of the stub REASON_TEXTS plus
 * the gateway ERROR_CLASSES and the job reasons: the non-null reasons of the
 * contract kit files sdk-cases.json and job-cases.json.
 * {@link DocQLException#reason()} carries these values; classification is by
 * status, never by body shape. Constants can be added in a later release,
 * never removed or renamed.
 */
public final class Reasons {

    private Reasons() {
    }

    /** The 401 reason for a missing or unknown key. */
    public static final String INVALID_API_KEY = "invalid_api_key";

    /** The 413 reason for an upload over the limit. */
    public static final String UPLOAD_TOO_LARGE = "upload_too_large";

    /** The 415 reason for a non-multipart request. */
    public static final String UNSUPPORTED_MEDIA_TYPE = "unsupported_media_type";

    /** The 422 reason for a request without its file part. */
    public static final String MISSING_FILE_PART = "missing_file_part";

    /** The 422 reason for a request without its body field. */
    public static final String MISSING_BODY_FIELD = "missing_body_field";

    /** The 422 reason for a body field that is not JSON. */
    public static final String BODY_NOT_JSON = "body_not_json";

    /** The 422 reason for neither query nor prompt. */
    public static final String MISSING_QUERY_OR_PROMPT = "missing_query_or_prompt";

    /** The 422 reason for invalid params. */
    public static final String INVALID_PARAMS = "invalid_params";

    /** The 422 reason for an unsupported file type. */
    public static final String UNSUPPORTED_FILE_TYPE = "unsupported_file_type";

    /** The 422 reason for a file that cannot be parsed. */
    public static final String CORRUPTED_FILE = "corrupted_file";

    /** The 422 reason for a document over the time budget. */
    public static final String DOCUMENT_EXCEEDS_TIME_BUDGET = "document_exceeds_time_budget";

    /** The 429 reason telling the caller to retry later. */
    public static final String SERVICE_OVERLOADED = "service_overloaded";

    /** The 503 reason for an unavailable upstream. */
    public static final String UPSTREAM_UNAVAILABLE = "upstream_unavailable";

    /** The 502 reason for a gateway-to-upstream auth failure. */
    public static final String UPSTREAM_AUTH_FAILED = "upstream_auth_failed";

    /** The 429 reason for a key over its concurrent-request limit. */
    public static final String CONCURRENCY_LIMIT_EXCEEDED = "concurrency_limit_exceeded";

    /** The 429 reason for a key over its request-rate limit. */
    public static final String RATE_LIMIT_EXCEEDED = "rate_limit_exceeded";

    /** The 404 reason for a job id that is unknown to this account. */
    public static final String JOB_NOT_FOUND = "job_not_found";

    /** The 404 reason for a job whose result has expired. */
    public static final String JOB_EXPIRED = "job_expired";

    /** The 429 reason for a full job queue; retry after the Retry-After delay. */
    public static final String QUEUE_FULL = "queue_full";

    /** The 422 reason for an Idempotency-Key reused with a different request. */
    public static final String IDEMPOTENCY_KEY_CONFLICT = "idempotency_key_conflict";

    /** The 409 reason for an Idempotency-Key whose first request is still in progress. */
    public static final String IDEMPOTENCY_IN_PROGRESS = "idempotency_in_progress";

    /** The 503 reason for a job interrupted before it could complete. */
    public static final String JOB_INTERRUPTED = "job_interrupted";
}
