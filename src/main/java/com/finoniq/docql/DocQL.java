package com.finoniq.docql;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /** The URL validation message; the URL itself is never echoed (D-01). */
    static final String INVALID_URL_MESSAGE =
            "apiUrl must be an http:// or https:// URL without credentials, query or fragment: "
                    + "pass apiUrl(...) or set DOCQL_API_URL";
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
    private final String host;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final boolean sdkBuiltClient;
    private final Supplier<String> boundarySupplier;

    private DocQL(String apiKey, String apiUrl, Duration timeout, HttpClient httpClient,
            boolean sdkBuiltClient, Supplier<String> boundarySupplier) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        // Only the URI host ever names a failure: never user info, scheme or
        // port noise, and never the request URL (D-12).
        this.host = URI.create(apiUrl).getHost();
        this.timeout = timeout;
        this.httpClient = httpClient;
        this.sdkBuiltClient = sdkBuiltClient;
        this.boundarySupplier = boundarySupplier;
    }

    /**
     * Returns a builder for a client.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Uploads one document with an instruction and returns its extraction
     * result, with default options ({@code params.mode} omitted, the
     * DocFile's own name).
     *
     * @param file the document to extract from
     * @param instruction what to extract
     * @return the extraction result
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
     * @param file the document to extract from
     * @param instruction what to extract
     * @param options per-call options
     * @return the extraction result
     * @throws NullPointerException if file, instruction or options is null
     * @throws IllegalArgumentException if the effective file-part name would
     *         be empty
     * @throws DocQLException on every non-2xx response and an unusable 2xx
     * @throws DocQLConnectionException on a transport failure
     * @throws DocQLTimeoutException when the request deadline expires
     */
    public QueryResult queryDocument(DocFile file, Instruction instruction, QueryOptions options) {
        PreparedRequest prepared = prepare(file, instruction, options);

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(prepared.request(), BoundedBodySubscriber.handler());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocQLConnectionException(Decoder.scrub("interrupted", apiKey), e);
        } catch (IOException e) {
            throw Transport.mapFailure(e, prepared.fileLength(), apiKey, host, timeout, sdkBuiltClient);
        }

        return decode(response);
    }

    /**
     * Uploads one document with an instruction and returns its extraction
     * result as a {@link CompletableFuture}, with default options. Built
     * directly on {@link HttpClient#sendAsync}: the SDK owns no thread pool
     * and has no executor option (D-05).
     *
     * <p>A {@code null} file or instruction throws
     * {@link NullPointerException} immediately — no future is returned.
     * Every other failure completes the returned future exceptionally with
     * the same exception the synchronous call would throw — a
     * {@link DocQLException}, a {@link DocQLConnectionException} or a
     * {@link DocQLTimeoutException} — surfacing as the cause of
     * {@link java.util.concurrent.ExecutionException} from
     * {@link CompletableFuture#get() get()} and of
     * {@link java.util.concurrent.CompletionException} from
     * {@link CompletableFuture#join() join()}.
     *
     * @param file the document to extract from
     * @param instruction what to extract
     * @return a future completing with the extraction result, or
     *         exceptionally with the same exceptions the synchronous call
     *         throws
     * @throws NullPointerException if file or instruction is null
     */
    public CompletableFuture<QueryResult> queryDocumentAsync(DocFile file, Instruction instruction) {
        return queryDocumentAsync(file, instruction, QueryOptions.builder().build());
    }

    /**
     * Uploads one document with an instruction and options and returns its
     * extraction result as a {@link CompletableFuture}. The same request, the
     * same decode and the same exceptions as the synchronous call; nothing
     * retries: calls are paid (D-05).
     *
     * <p>The {@code null} checks throw {@link NullPointerException}
     * synchronously; every other failure — pre-send validation included —
     * completes the future exceptionally instead, with the same exception
     * class the synchronous call would throw. Cancelling the returned future
     * cancels the underlying exchange; the client stays usable.
     *
     * @param file the document to extract from
     * @param instruction what to extract
     * @param options per-call options
     * @return a future completing with the extraction result, or
     *         exceptionally with the same exceptions the synchronous call
     *         throws
     * @throws NullPointerException if file, instruction or options is null
     */
    public CompletableFuture<QueryResult> queryDocumentAsync(DocFile file, Instruction instruction,
            QueryOptions options) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(options, "options");

        PreparedRequest prepared;
        try {
            prepared = prepare(file, instruction, options);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }

        // A fresh future completed by hand (never thenApply on the JDK
        // future, whose CompletionException would wrap the SDK exception a
        // second time, T-13-15): transport failures map to the SDK's
        // exception family, decode results and decode failures complete the
        // future with the very instances the sync call returns or throws.
        CompletableFuture<QueryResult> result = new CompletableFuture<>();
        CompletableFuture<HttpResponse<byte[]>> http =
                httpClient.sendAsync(prepared.request(), BoundedBodySubscriber.handler());
        http.whenComplete((response, failure) -> {
            if (failure != null) {
                result.completeExceptionally(Transport.mapFailure(failure, prepared.fileLength(),
                        apiKey, host, timeout, sdkBuiltClient));
                return;
            }
            try {
                result.complete(decode(response));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        result.whenComplete((unused, failure) -> {
            if (result.isCancelled()) {
                http.cancel(true);
            }
        });
        return result;
    }

    /** One prepared request: the framed HttpRequest and the file length (the size-hint input). */
    private record PreparedRequest(HttpRequest request, long fileLength) {
    }

    /**
     * Every client-side check and the whole framing of one call (D-02, D-03):
     * the strict filename rule, the regular-file check, the file length, and
     * the byte-exact multipart request. Shared by the synchronous and the
     * asynchronous call so both send identical requests.
     */
    private PreparedRequest prepare(DocFile file, Instruction instruction, QueryOptions options) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(options, "options");

        // Strict filename rule (D-02): override verbatim, else the path
        // basename (null for a root path), else the factory name; empty is
        // rejected before any I/O.
        String partName;
        if (options.filename() != null) {
            partName = options.filename();
        } else if (file.kind() == DocFile.Kind.PATH) {
            Path fileName = file.path().getFileName();
            partName = fileName == null ? "" : fileName.toString();
        } else {
            partName = file.name();
        }
        if (partName.isEmpty()) {
            throw new IllegalArgumentException("missing filename: this file input needs a filename");
        }

        // A path input must be a regular file, checked before any byte is
        // sent: ofFile alone would happily upload a directory's inode size.
        if (file.kind() == DocFile.Kind.PATH && !Files.isRegularFile(file.path())) {
            if (!Files.exists(file.path())) {
                throw new UncheckedIOException(new NoSuchFileException(file.path().toString()));
            }
            throw new UncheckedIOException(new IOException("not a regular file: " + file.path()));
        }

        long fileLength;
        switch (file.kind()) {
            case BYTES:
                fileLength = file.data().length;
                break;
            case PATH:
                try {
                    fileLength = Files.size(file.path());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                break;
            default:
                fileLength = file.length();
                break;
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
                        filePublisher(file),
                        HttpRequest.BodyPublishers.ofByteArray(epilogue)))
                .build();

        return new PreparedRequest(request, fileLength);
    }

    /**
     * The response decode shared by both paths (D-04, D-06, D-21): a usable
     * 2xx body becomes a {@link QueryResult}; an over-cap 2xx body or any
     * other status throws the {@link DocQLException} the sync call throws and
     * the async future completes with.
     */
    private QueryResult decode(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        byte[] body = response.body();
        if (status / 100 == 2) {
            if (body.length > SUCCESS_BODY_LIMIT) {
                throw new DocQLException(status, null, Decoder.sanitise(headerRequestId(response.headers()), apiKey),
                        null, false, "response body exceeds " + SUCCESS_BODY_LIMIT + " bytes",
                        Decoder.scrubAndTruncate(body, apiKey));
            }
            return Decoder.parseResult(status, response.headers(), body, apiKey);
        }
        // Every other status decodes as an error, 3xx included (D-09): the
        // key never leaves the configured host on a redirect.
        throw Decoder.decodeError(status, response.headers(), body, apiKey);
    }

    /**
     * Picks the file publisher per input kind (D-03): the path streams from
     * disk via {@code ofFile}, the bytes from memory, and a known-length
     * stream through a bounded non-closing view served once — the zero-length
     * stream becomes {@code noBody()}, because {@code fromPublisher(p, 0)}
     * throws. Known-length parts inside {@code BodyPublishers.concat} keep
     * the body an exact {@code Content-Length}, never chunked.
     */
    private static HttpRequest.BodyPublisher filePublisher(DocFile file) {
        switch (file.kind()) {
            case BYTES:
                return HttpRequest.BodyPublishers.ofByteArray(file.data());
            case PATH:
                try {
                    return HttpRequest.BodyPublishers.ofFile(file.path());
                } catch (FileNotFoundException e) {
                    throw new UncheckedIOException(e);
                }
            default:
                if (file.length() == 0) {
                    return HttpRequest.BodyPublishers.noBody();
                }
                AtomicBoolean served = new AtomicBoolean();
                return HttpRequest.BodyPublishers.fromPublisher(
                        HttpRequest.BodyPublishers.ofInputStream(() -> served.compareAndSet(false, true)
                                ? new BoundedInputStream(file.stream(), file.length())
                                : null),
                        file.length());
        }
    }

    /** The User-Agent of every request (D-13). */
    private static String userAgent() {
        return "docql-java/" + VERSION
                + " (java " + System.getProperty("java.version")
                + "; " + System.getProperty("os.name") + "/" + System.getProperty("os.arch") + ")";
    }

    /**
     * The masked identity of this client (D-12): the API URL and timeout
     * only — the API key never appears in any string form.
     */
    @Override
    public String toString() {
        return "DocQL{apiUrl=" + apiUrl + ", apiKey=***, timeout=" + timeout + "}";
    }

    /** The raw {@code x-request-id} header value, or null when absent or empty. */
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
         * @param apiKey the key; must not be null (the value is trimmed and
         *        validated at build time)
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
            return this;
        }

        /**
         * Sets the API base URL, e.g. {@code "https://api-docql.finoniq.com"};
         * trailing slashes are stripped and {@code http://} is allowed for
         * the stub.
         *
         * @param apiUrl the base URL; must not be null
         * @return this builder
         */
        public Builder apiUrl(String apiUrl) {
            this.apiUrl = Objects.requireNonNull(apiUrl, "apiUrl");
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
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /**
         * Injects a caller-owned {@link HttpClient} to send the requests
         * with. The client must use {@link HttpClient.Redirect#NEVER} — the
         * JDK strips only {@code Authorization} on cross-host redirects, not
         * {@code X-API-Key}, so {@code build()} fails fast otherwise (D-10).
         *
         * @param httpClient the client to inject; must not be null
         * @return this builder
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
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

        /**
         * Builds the immutable client. Resolution order per value: explicit
         * builder value, then the environment ({@code DOCQL_API_KEY} /
         * {@code DOCQL_API_URL}; a whitespace-only value counts as unset),
         * then the production default for the URL. Fails fast before any
         * network contact when no usable key or URL can be resolved (D-01).
         *
         * @return the immutable client
         * @throws IllegalArgumentException when no usable key or URL can be
         *         resolved, or an injected client follows redirects
         */
        public DocQL build() {
            return new DocQL(resolveKey(), resolveUrl(), resolveTimeout(),
                    httpClient != null ? validateClient(httpClient) : sdkClient(),
                    httpClient == null,
                    boundarySupplier);
        }

        private String resolveKey() {
            if (apiKey != null) {
                String trimmed = apiKey.trim();
                if (trimmed.isEmpty()) {
                    throw new IllegalArgumentException(
                            "API key missing: the explicitly given apiKey(...) value is empty; "
                                    + "pass apiKey(...) or set DOCQL_API_KEY");
                }
                return requireWireSafe(trimmed);
            }
            String fromEnv = envLookup.apply(ENV_API_KEY);
            String trimmed = fromEnv == null ? "" : fromEnv.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException(
                        "API key missing: pass apiKey(...) or set DOCQL_API_KEY");
            }
            return requireWireSafe(trimmed);
        }

        /**
         * Rejects characters the wire cannot carry — control characters and
         * anything above U+00FF, which the JDK header validator would reject
         * with a message that echoes the value (WR-01) — without ever echoing
         * the key value.
         */
        private static String requireWireSafe(String key) {
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                if (c < 0x20 || c == 0x7F) {
                    throw new IllegalArgumentException(
                            "API key contains control characters (DOCQL_API_KEY)");
                }
                if (c > 0xFF) {
                    throw new IllegalArgumentException(
                            "API key contains non-Latin-1 characters the HTTP header cannot carry (DOCQL_API_KEY)");
                }
            }
            return key;
        }

        private String resolveUrl() {
            String raw;
            if (apiUrl != null) {
                if (apiUrl.trim().isEmpty()) {
                    throw new IllegalArgumentException(INVALID_URL_MESSAGE);
                }
                raw = apiUrl;
            } else {
                raw = envLookup.apply(ENV_API_URL);
                if (raw == null || raw.trim().isEmpty()) {
                    raw = DEFAULT_API_URL;
                }
            }
            URI uri;
            try {
                uri = URI.create(raw);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(INVALID_URL_MESSAGE);
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https"))
                    || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException(INVALID_URL_MESSAGE);
            }
            String url = raw;
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            return url;
        }

        private Duration resolveTimeout() {
            if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
                throw new IllegalArgumentException("timeout must be greater than zero");
            }
            return timeout != null ? timeout : DEFAULT_TIMEOUT;
        }

        private static HttpClient validateClient(HttpClient injected) {
            if (injected.followRedirects() != HttpClient.Redirect.NEVER) {
                throw new IllegalArgumentException(
                        "the injected HttpClient must use Redirect.NEVER"
                                + " (the SDK never forwards X-API-Key on a redirect)");
            }
            return injected;
        }
    }
}
