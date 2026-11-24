package com.finoniq.docql;

/**
 * A webhook delivery failed verification: do not act on it (D-13, D-14).
 *
 * <p>{@link #reason()} is one of {@code missing_header},
 * {@code invalid_timestamp}, {@code invalid_signature_header},
 * {@code signature_mismatch}, {@code timestamp_too_old},
 * {@code timestamp_too_new} or {@code invalid_payload}. The message holds
 * only the reason and a fixed sentence: never a secret, the expected
 * signature or a received signature. The exception keeps nothing but the
 * reason, so serializing it carries no request data.
 *
 * <p>This is not a {@link DocQLException}, because no API call failed.
 */
public final class WebhookVerificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the delivery failed verification; one of the seven reasons. */
    private final String reason;

    WebhookVerificationException(String reason) {
        super(reason + ": " + sentence(reason));
        this.reason = reason;
    }

    /**
     * Returns why the delivery failed verification.
     *
     * @return one of the seven reasons, for example {@code signature_mismatch}
     */
    public String reason() {
        return reason;
    }

    /** The one fixed sentence each reason carries (the Python and Go wording). */
    private static String sentence(String reason) {
        switch (reason) {
            case "missing_header":
                return "a webhook-id, webhook-timestamp or webhook-signature header is absent, "
                        + "empty, or repeated with different values";
            case "invalid_timestamp":
                return "the webhook-timestamp header is not 1 to 12 ASCII digits";
            case "invalid_signature_header":
                return "the webhook-signature header has no well-formed v1 signature";
            case "signature_mismatch":
                return "no v1 signature matches any secret (wrong secret or a changed body)";
            case "timestamp_too_old":
                return "the webhook-timestamp is older than the tolerance allows";
            case "timestamp_too_new":
                return "the webhook-timestamp is further in the future than the tolerance allows";
            case "invalid_payload":
                return "the signed body is not a DocQL webhook event";
            default:
                return "verification failed";
        }
    }
}
