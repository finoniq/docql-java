package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Async jobs (JSDK-01): the one-request primitive, the job decode and the
 * wait. Package-private; {@link DocQL} delegates its job methods here.
 *
 * <p>The wait is a copy of the contract kit's reference {@code waitForJob}
 * (docql-console {@code apps/docql-stub/test/job-kit.ts}) and of the Python
 * SDK's {@code wait_for}, so every SDK makes the same requests and reaches
 * the same outcome on the kit's {@code waitCases}:
 * <ul>
 * <li>D-03: each sleep is the answer's {@code Retry-After} clamped to
 * [1, 30] s, 1 s when a 200 carries none; a caller poll interval raises the
 * sleep to {@code max(interval, server value)}. No jitter.</li>
 * <li>D-04: a network error, a per-poll timeout, a 429 and a 503 are retried;
 * without {@code Retry-After} they back off 1, 2, 4, 8, 16, 30 s, reset after
 * any 200. A 401, any 404 and every other status throw at once.</li>
 * <li>D-05: {@code succeeded} and {@code cancelled} return the Job;
 * {@code failed} throws the job's {@link DocQLException} (job id set, not
 * retryable).</li>
 * <li>OD-5: the wait gives up when the next sleep would pass the deadline
 * (15 minutes by default) and throws {@link DocQLWaitTimeoutException}.</li>
 * </ul>
 * The wait never cancels the job, on any path, interrupts included.
 */
final class Jobs {

    /** The job collection route. */
    static final String JOBS_PATH = "/v1/jobs";
    /** The deadline of one poll or cancel request (connect stays the client's 10 s). */
    static final Duration POLL_TIMEOUT = Duration.ofSeconds(30);
    /** The default wait deadline (OD-5). */
    static final Duration DEFAULT_WAIT = Duration.ofMinutes(15);
    /** The shortest sleep between polls, whatever Retry-After says. */
    static final Duration RETRY_AFTER_FLOOR = Duration.ofSeconds(1);
    /** The longest sleep between polls, whatever Retry-After says. */
    static final Duration RETRY_AFTER_CAP = Duration.ofSeconds(30);
    /** The sleep after a 200 without Retry-After (D-03 as adjusted to the kit). */
    static final Duration RETRY_AFTER_ABSENT = Duration.ofSeconds(1);
    /** The cap of the 1, 2, 4, ... s backoff after transient failures (D-04). */
    static final Duration TRANSIENT_BACKOFF_CAP = Duration.ofSeconds(30);
    /** The key validation message; the key itself is never echoed (Pitfall 9). */
    static final String IDEMPOTENCY_KEY_MESSAGE =
            "idempotencyKey must be 1-255 printable ASCII characters (0x20-0x7E)";

    private static final HttpHeaders NO_HEADERS = HttpHeaders.of(Map.of(), (name, value) -> true);
    private static final SecureRandom RANDOM = new SecureRandom();

    private Jobs() {
    }

    /** The wait's sleep; tests replace it with a recording fake clock. */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    /** The multipart body of one submit: its content type, publisher and file length. */
    static final class Upload {

        private final String contentType;
        private final HttpRequest.BodyPublisher publisher;
        private final long fileLength;

        Upload(String contentType, HttpRequest.BodyPublisher publisher, long fileLength) {
            this.contentType = contentType;
            this.publisher = publisher;
            this.fileLength = fileLength;
        }

        String contentType() {
            return contentType;
        }

        HttpRequest.BodyPublisher publisher() {
            return publisher;
        }

        long fileLength() {
            return fileLength;
        }
    }

    /** One answer: status, headers and the bounded body. */
    static final class Answer {

        private final int status;
        private final HttpHeaders headers;
        private final byte[] body;

        Answer(int status, HttpHeaders headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        int status() {
            return status;
        }

        HttpHeaders headers() {
            return headers;
        }

        byte[] body() {
            return body;
        }

        boolean ok() {
            return status / 100 == 2;
        }
    }

    /**
     * The job URL path: the id as one percent-encoded segment, every
     * {@code .} as {@code %2E}, so {@code .} and {@code ..} ids are never
     * normalised away (the gateway's own encoder). The id is opaque: only a
     * null or empty id is refused.
     */
    static String jobPath(String jobId) {
        if (jobId == null || jobId.isEmpty()) {
            throw new IllegalArgumentException("jobId must be a non-empty string");
        }
        String segment = URLEncoder.encode(jobId, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace(".", "%2E");
        return JOBS_PATH + "/" + segment;
    }

    /** A fresh Idempotency-Key: 32 SecureRandom bytes, URL-safe base64 without padding (D-07). */
    static String newIdempotencyKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Returns the key unchanged when DocQL accepts it (1-255 characters, each
     * 0x20-0x7E), else throws IllegalArgumentException whose message never
     * echoes the value. Runs before any {@code HttpRequest.Builder.header}
     * call, whose own rejection would echo it (Pitfall 9).
     */
    static String validateIdempotencyKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 255) {
            throw new IllegalArgumentException(IDEMPOTENCY_KEY_MESSAGE);
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new IllegalArgumentException(IDEMPOTENCY_KEY_MESSAGE);
            }
        }
        return key;
    }

    /**
     * Sends exactly one job request and returns its answer. The headers are
     * the queryDocument ones — X-API-Key and User-Agent on every request,
     * Content-Type on a submit — plus one {@code Idempotency-Key} line per
     * given key, each validated first; never X-Request-Id or
     * {@code x-docql-*}. Bounded body reads, Redirect.NEVER and the transport
     * failure mapping are the v0.1 ones. It never decodes and never retries.
     */
    static Answer sendOnce(DocQL client, String method, String path, Upload upload, List<String> keys,
            Duration timeout) throws InterruptedException {
        for (String key : keys) {
            validateIdempotencyKey(key);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(client.apiUrl() + path))
                .timeout(timeout)
                .header("X-API-Key", client.apiKey());
        if (upload != null) {
            builder.header("Content-Type", upload.contentType());
        }
        builder.header("User-Agent", DocQL.userAgent());
        for (String key : keys) {
            builder.header("Idempotency-Key", key);
        }
        if (upload != null) {
            builder.POST(upload.publisher());
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.GET();
        }
        HttpResponse<byte[]> response;
        try {
            response = client.httpClient().send(builder.build(), BoundedBodySubscriber.handler());
        } catch (IOException e) {
            throw Transport.mapFailure(e, upload == null ? 0 : upload.fileLength(), client.apiKey(),
                    client.host(), timeout, client.sdkBuiltClient());
        }
        return new Answer(response.statusCode(), response.headers(), response.body());
    }

    /**
     * Decodes a 2xx job answer (submit 202, poll 200, cancel 200/202) into a
     * Job. A body that is not a JSON object with a string {@code job_id} and
     * {@code status} is unusable. A succeeded job's result decodes through
     * {@link Decoder#parseResult}; a failed job's error through
     * {@link Decoder#decodeError} with the embedded {@code status_code} (else
     * 500), then the job id set and retryable false.
     */
    static Job parseJob(int status, HttpHeaders headers, byte[] body, String apiKey, String idempotencyKey) {
        if (body.length > DocQL.SUCCESS_BODY_LIMIT) {
            throw new DocQLException(status, null, Decoder.headerRequestId(headers, apiKey), null, false,
                    "response body exceeds " + DocQL.SUCCESS_BODY_LIMIT + " bytes",
                    Decoder.scrubAndTruncate(body, apiKey));
        }
        JsonNode root = Decoder.parseTree(body);
        if (root == null || !root.isObject()) {
            throw Decoder.unusable(status, headers, body, apiKey, "unexpected non-JSON response");
        }
        JsonNode idNode = root.get("job_id");
        JsonNode statusNode = root.get("status");
        if (idNode == null || !idNode.isTextual() || statusNode == null || !statusNode.isTextual()) {
            throw Decoder.unusable(status, headers, body, apiKey, "unexpected response: not a DocQL job object");
        }
        String jobId = idNode.textValue();
        String jobStatus = statusNode.textValue();
        JsonNode metadataNode = root.get("metadata");
        JsonNode metadata = metadataNode != null && metadataNode.isObject() ? metadataNode : MissingNode.getInstance();

        QueryResult result = null;
        JsonNode rawResult = root.get("result");
        if (JobStatus.SUCCEEDED.equals(jobStatus) && rawResult != null && rawResult.isObject()) {
            result = Decoder.parseResult(200, headers, Decoder.toJsonBytes(rawResult), apiKey);
        }

        DocQLException error = null;
        JsonNode rawError = root.get("error");
        if (JobStatus.FAILED.equals(jobStatus) && rawError != null && rawError.isObject()) {
            JsonNode code = rawError.get("status_code");
            int errorStatus = code != null && code.isIntegralNumber() && code.canConvertToInt() ? code.intValue() : 500;
            error = Decoder.decodeError(errorStatus, NO_HEADERS, Decoder.toJsonBytes(rawError), apiKey)
                    .withJob(jobId, null, false);
        }

        return new Job(jobId, jobStatus,
                Decoder.jsonInt(metadata, "page_count"),
                Decoder.jsonInt(metadata, "pages_done"),
                Decoder.headerRequestId(headers, apiKey),
                timestamp(root.get("created_at")),
                timestamp(root.get("started_at")),
                timestamp(root.get("finished_at")),
                timestamp(root.get("expires_at")),
                result, error,
                Decoder.parseRetryAfter(headers.firstValue("retry-after").orElse(null), Instant.now()),
                idempotencyKey);
    }

    /** An ISO-8601 timestamp; one without an offset is read as UTC; anything else is null. */
    private static Instant timestamp(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.textValue();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            // fall through: no offset, or not a timestamp
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The error of a non-2xx job answer (or an unusable 2xx), with the job id set. */
    static DocQLException jobError(Answer answer, String apiKey, String jobId) {
        DocQLException error = Decoder.decodeError(answer.status(), answer.headers(), answer.body(), apiKey);
        return error.withJob(jobId, null, error.retryable());
    }

    /** The Job of a 2xx answer, else the answer's error; either way the job id is set on errors. */
    static Job jobOrThrow(Answer answer, String apiKey, String jobId) {
        if (!answer.ok()) {
            throw jobError(answer, apiKey, jobId);
        }
        try {
            return parseJob(answer.status(), answer.headers(), answer.body(), apiKey, null);
        } catch (DocQLException unusable) {
            throw unusable.withJob(jobId, null, unusable.retryable());
        }
    }

    /** One GET of the job: a Job for any 2xx (a failed job included), else DocQLException. */
    static Job fetch(DocQL client, String jobId) {
        String path = jobPath(jobId);
        Answer answer;
        try {
            answer = sendOnce(client, "GET", path, null, List.of(), POLL_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocQLConnectionException(Decoder.scrub("interrupted", client.apiKey()), e);
        }
        return jobOrThrow(answer, client.apiKey(), jobId);
    }

    /** Polls the job until it is succeeded or cancelled: the kit's waitForJob (class comment). */
    static Job waitFor(DocQL client, String jobId, Duration timeout, Duration pollInterval) {
        String path = jobPath(jobId);
        String apiKey = client.apiKey();
        long deadline = saturatedAdd(client.nanoClock().getAsLong(), nanos(timeout));
        Duration backoff = RETRY_AFTER_FLOOR;
        while (true) {
            long remaining = saturatedSubtract(deadline, client.nanoClock().getAsLong());
            Duration pollTimeout = Duration.ofNanos(Math.min(POLL_TIMEOUT.toNanos(),
                    Math.max(remaining, RETRY_AFTER_FLOOR.toNanos())));
            Answer answer = null;
            try {
                answer = sendOnce(client, "GET", path, null, List.of(), pollTimeout);
            } catch (DocQLConnectionException e) {
                // A network error or a per-poll timeout: transient, retried below.
            } catch (InterruptedException e) {
                throw interrupted(jobId, apiKey, e);
            }

            boolean transientFailure;
            Duration server = null;
            if (answer == null) {
                transientFailure = true;
            } else if (answer.ok()) {
                Job job = jobOrThrow(answer, apiKey, jobId);
                if (JobStatus.SUCCEEDED.equals(job.status()) || JobStatus.CANCELLED.equals(job.status())) {
                    return job;
                }
                if (JobStatus.FAILED.equals(job.status())) {
                    if (job.error() != null) {
                        throw job.error();
                    }
                    throw Decoder.unusable(answer.status(), answer.headers(), answer.body(), apiKey,
                            "unexpected response: failed job without an error object").withJob(jobId, null, false);
                }
                // queued, running or a status this SDK does not know (D-09).
                transientFailure = false;
                backoff = RETRY_AFTER_FLOOR;
                server = retryAfterHint(answer.headers());
            } else if (answer.status() == 429 || answer.status() == 503) {
                transientFailure = true;
                server = retryAfterHint(answer.headers());
            } else {
                throw jobError(answer, apiKey, jobId);
            }

            Duration sleep;
            if (server != null) {
                sleep = clamp(server, RETRY_AFTER_FLOOR, RETRY_AFTER_CAP);
            } else if (transientFailure) {
                sleep = backoff;
                backoff = min(backoff.multipliedBy(2), TRANSIENT_BACKOFF_CAP);
            } else {
                sleep = RETRY_AFTER_ABSENT;
            }
            if (pollInterval != null && pollInterval.compareTo(sleep) > 0) {
                sleep = pollInterval;
            }
            if (nanos(sleep) > saturatedSubtract(deadline, client.nanoClock().getAsLong())) {
                throw new DocQLWaitTimeoutException(jobId, timeout, apiKey);
            }
            try {
                client.sleeper().sleep(sleep);
            } catch (InterruptedException e) {
                throw interrupted(jobId, apiKey, e);
            }
        }
    }

    /**
     * The interrupt rule (26-01): restore the flag, then a connection
     * exception that names the job; the job is never cancelled.
     */
    private static DocQLConnectionException interrupted(String jobId, String apiKey, InterruptedException e) {
        Thread.currentThread().interrupt();
        return new DocQLConnectionException("waitFor interrupted for job " + Decoder.sanitise(jobId, apiKey)
                + "; the job was not cancelled", e);
    }

    /**
     * The answer's Retry-After for the wait's sleep, or null when absent or
     * unparsable. Unlike {@link DocQLException#retryAfter()}, a delta too
     * large for a Duration counts as present (clamped to the cap later), and
     * {@code 0} stays 0 so the clamp turns it into the 1 s floor.
     */
    static Duration retryAfterHint(HttpHeaders headers) {
        String value = headers.firstValue("retry-after").orElse(null);
        Duration parsed = Decoder.parseRetryAfter(value, Instant.now());
        if (parsed == null && value != null) {
            String text = value.strip();
            if (!text.isEmpty() && text.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return RETRY_AFTER_CAP;
            }
        }
        return parsed;
    }

    private static Duration clamp(Duration value, Duration floor, Duration cap) {
        return min(value.compareTo(floor) < 0 ? floor : value, cap);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** A Duration in nanoseconds, saturating at Long.MAX_VALUE instead of overflowing. */
    static long nanos(Duration d) {
        try {
            return d.toNanos();
        } catch (ArithmeticException e) {
            return d.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        if (((a ^ sum) & (b ^ sum)) < 0) {
            return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return sum;
    }

    private static long saturatedSubtract(long a, long b) {
        long difference = a - b;
        if (((a ^ b) & (a ^ difference)) < 0) {
            return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return difference;
    }
}
