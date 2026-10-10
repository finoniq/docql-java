package com.finoniq.docql;

/**
 * The DocQL reasons (D-04): the SDK spelling of the stub REASON_TEXTS plus
 * the gateway ERROR_CLASSES. {@link DocQLException#reason()} carries these
 * values; classification is by status, never by body shape. Constants can be
 * added in a later release, never removed or renamed.
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
}
