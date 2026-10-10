package com.finoniq.docql;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A client for the DocQL document-extraction API ({@code POST
 * /v1/query-document}) (D-01). Send one document with an instruction and get
 * its extraction result.
 *
 * <p>Build one with {@link #builder()}; every value is explicit, an
 * {@code DOCQL_API_KEY} / {@code DOCQL_API_URL} fallback, or the production
 * default URL. A {@code DocQL} client is immutable and thread-safe. It needs
 * no closing. Calls are paid: nothing here retries.
 */
public final class DocQL {

    /** The SDK version, reported in the User-Agent of every request. */
    public static final String VERSION = "0.1.0";

    static final String DEFAULT_API_URL = "https://api-docql.finoniq.com";
    static final String ENV_API_KEY = "DOCQL_API_KEY";
    static final String ENV_API_URL = "DOCQL_API_URL";
    static final String QUERY_PATH = "/v1/query-document";
    /** The per-request deadline: it covers the upload until the response headers arrive (D-07). */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(660);
    /** Bounds dial and TLS handshake on the client the SDK builds itself (D-09). */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** The API's upload ceiling; over it, connection errors carry a size hint (D-08). */
    static final long MAX_UPLOAD_BYTES = 26_214_400L;
    /** How much of a non-2xx body is read from the wire. */
    static final int ERROR_BODY_READ_LIMIT = 65_536;
    /** How much of a response body is kept on DocQLException.body(). */
    static final int ERROR_BODY_KEEP_CHARS = 2048;
    /** The body-snippet cap of the non-DocQL message form. */
    static final int SNIPPET_MAX_CHARS = 200;
    /** The success-body cap; a larger 2xx body is unusable (D-21). */
    static final int SUCCESS_BODY_LIMIT = 67_108_864;

    private final String apiKey;
    private final String apiUrl;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final Supplier<String> boundarySupplier;

    private DocQL(String apiKey, String apiUrl, Duration timeout, HttpClient httpClient,
            Supplier<String> boundarySupplier) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.timeout = timeout;
        this.httpClient = httpClient;
        this.boundarySupplier = boundarySupplier;
    }

    /** Returns a builder for a client. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Uploads one document with an instruction and returns its extraction
     * result, with default options ({@code params.mode} omitted, the
     * DocFile's own name).
     *
     * @throws NullPointerException if file, instruction or (in the overload)
     *         options is null
     * @throws IllegalArgumentException if the effective file-part name would
     *         be empty
     * @throws DocQLException on every non-2xx response and an unusable 2xx
     * @throws DocQLConnectionException on a transport failure
     * @throws DocQLTimeoutException when the request deadline expires
     */
    public QueryResult queryDocument(DocFile file, Instruction instruction) {
        return queryDocument(file, instruction, QueryOptions.builder().build());
    }

    /**
     * Uploads one document with an instruction and options and returns its
     * extraction result. Nothing here retries: calls are paid. Every
     * client-side check happens before a byte is sent.
     *
     * @throws NullPointerException if file, instruction or options is null
     * @throws IllegalArgumentException if the effective file-part name would
     *         be empty
     * @throws DocQLException on every non-2xx response and an unusable 2xx
     * @throws DocQLConnectionException on a transport failure
     * @throws DocQLTimeoutException when the request deadline expires
     */
    public QueryResult queryDocument(DocFile file, Instruction instruction, QueryOptions options) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(options, "options");

        String partName = options.filename() != null ? options.filename() : file.name();
        if (partName.isEmpty()) {
            throw new IllegalArgumentException("missing filename: this file input needs a filename");
        }

        byte[] bodyJson = Multipart.bodyJson(instruction, options.mode());
        String boundary = boundarySupplier.get();
        byte[] preamble = Multipart.preamble(boundary, partName);
        byte[] epilogue = Multipart.epilogue(boundary, bodyJson);

        // Exactly three per-request headers: the key travels on the request
        // only, never as a client default, and no X-Request-Id or x-docql-*
        // header is ever set (D-10, D-13).
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + QUERY_PATH))
                .timeout(timeout)
                .header("X-API-Key", apiKey)
                .header("Content-Type", Multipart.contentType(boundary))
                .header("User-Agent", userAgent())
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArray(preamble),
                        HttpRequest.BodyPublishers.ofByteArray(file.data()),
                        HttpRequest.BodyPublishers.ofByteArray(epilogue)))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, BoundedBodySubscriber.handler());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocQLConnectionException(Decoder.scrub("interrupted", apiKey), e);
        } catch (IOException e) {
            throw Transport.mapFailure(e, file.data().length, apiKey, apiUrl, timeout);
        }

        int status = response.statusCode();
        byte[] body = response.body();
        if (status / 100 == 2) {
            if (body.length > SUCCESS_BODY_LIMIT) {
                throw new DocQLException(status, null, headerRequestId(response.headers()),
                        null, false, "response body exceeds " + SUCCESS_BODY_LIMIT + " bytes",
                        Decoder.scrubAndTruncate(body, apiKey));
            }
            return Decoder.parseResult(status, response.headers(), body, apiKey);
        }
        // Every other status decodes as an error, 3xx included (D-09): the
        // key never leaves the configured host on a redirect.
        throw Decoder.decodeError(status, response.headers(), body, apiKey);
    }

    /** The User-Agent of every request (D-13). */
    private static String userAgent() {
        return "docql-java/" + VERSION
                + " (java " + System.getProperty("java.version")
                + "; " + System.getProperty("os.name") + "/" + System.getProperty("os.arch") + ")";
    }

    private static String headerRequestId(HttpHeaders headers) {
        String value = headers.firstValue("x-request-id").orElse(null);
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * The client the SDK builds for itself: HTTP/1.1 (no h2c upgrade over
     * plain http://, predictable early-413 behaviour), never following a
     * redirect (the key never leaves the configured host), 10 s connect
     * timeout (D-09). Built once per JVM through a lazy holder.
     */
    static HttpClient sdkClient() {
        return SdkClientHolder.CLIENT;
    }

    private static final class SdkClientHolder {

        private SdkClientHolder() {
        }

        static final HttpClient CLIENT = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /** Builder of an immutable {@link DocQL} client (D-01). */
    public static final class Builder {

        private Function<String, String> envLookup = System::getenv;
        private Supplier<String> boundarySupplier = Multipart::randomBoundary;
        private String apiKey;
        private String apiUrl;
        private Duration timeout;
        private HttpClient httpClient;

        private Builder() {
        }

        /**
         * Sets the API key used for every call.
         *
         * @param apiKey the key; must not be null at build time
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the API base URL, e.g. {@code "https://api-docql.finoniq.com"};
         * a trailing slash is stripped and {@code http://} is allowed for the
         * stub.
         *
         * @param apiUrl the base URL
         * @return this builder
         */
        public Builder apiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
            return this;
        }

        /**
         * Sets the per-request deadline: it covers the upload until the
         * response headers arrive, so a large file on a slow uplink uses part
         * of it (D-07). Defaults to 660 seconds.
         *
         * @param timeout the deadline; must be positive
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Injects a caller-owned {@link HttpClient} to send the requests
         * with. Recorded verbatim; build() applies the redirect policy (D-10).
         *
         * @param httpClient the client to inject; must not be null
         * @return this builder
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /** Overrides the environment lookup (tests inject a map; never read the real env). */
        Builder env(Function<String, String> lookup) {
            this.envLookup = Objects.requireNonNull(lookup, "lookup");
            return this;
        }

        /** Overrides the random boundary source (tests pin the golden boundary). */
        Builder boundary(Supplier<String> boundary) {
            this.boundarySupplier = Objects.requireNonNull(boundary, "boundary");
            return this;
        }

        /** Builds the immutable client. */
        public DocQL build() {
            String key = apiKey;
            String url = apiUrl != null ? apiUrl : DEFAULT_API_URL;
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            Duration deadline = timeout != null ? timeout : DEFAULT_TIMEOUT;
            return new DocQL(key, url, deadline, sdkClient(), boundarySupplier);
        }
    }
}
