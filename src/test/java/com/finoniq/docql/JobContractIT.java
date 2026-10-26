package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The kit's {@code via: wait} job cases through the public job methods,
 * against the stub booted by {@code scripts/stub-up.sh} (JSDK-01). The same
 * strict-stub rule as {@link ContractIT}: with {@code DOCQL_REQUIRE_STUB=1}
 * a missing {@code DOCQL_STUB_URL} fails the run, otherwise the cases skip.
 * ContractIT's three-way sha256 gate runs in the same {@code verify} and
 * fails the build on a kit mismatch.
 *
 * <p>Step semantics follow the kit's reference runners (docql-console
 * {@code deploy/scripts/job-cases-edge.mjs}): a per-run {@code {run}} token
 * keeps each case's fingerprint unique, {@code submit} posts the document as
 * a job and binds the answered job id, {@code cancel} calls the public
 * {@link DocQL#cancelJob(String)}, and {@code wait} calls the public
 * {@link DocQL#waitFor(String)}. Each case prints
 * {@code JOB_CONTRACT_WAIT_OK <id>} when it passes.
 */
class JobContractIT {

    private static final List<String> KNOWN_VIAS = List.of("submit", "poll", "cancel", "wait", "sync", "infra");

    /** One token per JVM run: 8 base62 characters (kit invariant 8). */
    private static final String RUN = runToken();

    private static String base;

    @BeforeAll
    static void resolveStub() {
        String url = System.getenv("DOCQL_STUB_URL");
        if (url == null || url.isBlank()) {
            if ("1".equals(System.getenv("DOCQL_REQUIRE_STUB"))) {
                fail("DOCQL_REQUIRE_STUB=1 but DOCQL_STUB_URL is not set: "
                        + "run the cases through scripts/stub-up.sh --run");
            }
            return;
        }
        base = url.replaceAll("/+$", "");
    }

    private static String runToken() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            token.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return token.toString();
    }

    /** A case with an unknown via must fail the run, never silently disappear. */
    @Test
    void everyJobCaseUsesAKnownVia() {
        List<String> unknown = new ArrayList<>();
        for (JsonNode c : Kit.jobCases().path("cases")) {
            if (!KNOWN_VIAS.contains(c.path("via").asText())) {
                unknown.add(c.path("id").asText());
            }
        }
        assertEquals(List.of(), unknown, "job-cases.json carries cases with an unknown via");
    }

    private static List<JsonNode> waitCases() {
        List<JsonNode> cases = new ArrayList<>();
        for (JsonNode c : Kit.jobCases().path("cases")) {
            if ("stub".equals(c.path("target").asText()) && "wait".equals(c.path("via").asText())) {
                cases.add(c);
            }
        }
        return cases;
    }

    @Test
    void waitCasesAreSelected() {
        List<String> ids = new ArrayList<>();
        for (JsonNode c : waitCases()) {
            ids.add(c.path("id").asText());
        }
        assertEquals(List.of("wait-succeeded", "wait-failed", "wait-cancelled"), ids);
    }

    @TestFactory
    Stream<DynamicTest> waitCasesAgainstTheStub() {
        if (base == null) {
            return Stream.empty();
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : waitCases()) {
            String id = c.path("id").asText();
            tests.add(DynamicTest.dynamicTest(id, () -> {
                runCase(c);
                System.out.println("JOB_CONTRACT_WAIT_OK " + id);
            }));
        }
        return tests.stream();
    }

    private static void runCase(JsonNode c) {
        String id = c.path("id").asText();
        DocQL client = DocQL.builder()
                .apiKey(Kit.jobCases().path("keys").path("valid").asText())
                .apiUrl(base)
                .build();
        Map<String, Job> jobs = new HashMap<>();
        for (JsonNode step : c.path("steps")) {
            String op = step.path("op").asText();
            switch (op) {
                case "submit": {
                    assertEquals("valid", step.path("key").asText("valid"), id + ": submit key");
                    assertEquals(202, step.path("expect").path("status").asInt(), id + ": expected status");
                    JsonNode request = step.path("request");
                    JsonNode file = request.path("file");
                    String name = file.path("name").asText();
                    QueryOptions.Builder options = QueryOptions.builder().filename(name);
                    if (request.hasNonNull("mode")) {
                        options.mode(Mode.of(request.path("mode").asText()));
                    }
                    Instruction instruction = request.hasNonNull("prompt")
                            ? Instruction.prompt(sub(request.path("prompt").asText()))
                            : Instruction.query(sub(request.path("query").asText()));
                    Job job = client.submitDocument(DocFile.bytes(Kit.caseFileBytes(file), name), instruction,
                            options.build());
                    assertEquals(JobStatus.QUEUED, job.status(), id + ": submitted status");
                    assertNotNull(job.jobId(), id + ": job id");
                    assertFalse(job.jobId().isEmpty(), id + ": job id");
                    assertNotNull(job.idempotencyKey(), id + ": generated idempotency key");
                    jobs.put(step.path("as").asText(), job);
                    break;
                }
                case "cancel": {
                    assertEquals(202, step.path("expect").path("status").asInt(), id + ": expected status");
                    String jobId = jobs.get(step.path("job").asText()).jobId();
                    Job cancelled = client.cancelJob(jobId);
                    assertEquals(jobId, cancelled.jobId(), id + ": cancelled job id");
                    break;
                }
                case "wait": {
                    String jobId = jobs.get(step.path("job").asText()).jobId();
                    JsonNode expect = step.path("expect");
                    String outcome = expect.path("outcome").asText();
                    if ("failed".equals(outcome)) {
                        DocQLException error = assertThrows(DocQLException.class, () -> client.waitFor(jobId),
                                id + ": a failed job throws its error");
                        assertEquals(expect.path("reason").asText(), error.reason(), id + ": reason");
                        assertEquals(jobId, error.jobId(), id + ": jobId on the error");
                        assertFalse(error.retryable(), id + ": a failed job is not retryable");
                    } else {
                        Job done = client.waitFor(jobId);
                        assertEquals(jobId, done.jobId(), id + ": job id");
                        assertEquals(outcome, done.status(), id + ": outcome");
                        if (JobStatus.SUCCEEDED.equals(outcome)) {
                            assertInstanceOf(QueryResult.class, done.result(), id + ": result");
                        }
                    }
                    break;
                }
                default:
                    fail(id + ": unsupported op " + op);
            }
        }
        assertTrue(!jobs.isEmpty(), id + ": the case submitted a job");
    }

    // --- the public submit's same-key retry (JSDK-02, D-07) -----------------------------------
    //
    // These go through the public, retrying submitDocument, unlike the kit's wire steps,
    // which use the single-attempt Jobs.sendOnce. A capturing HttpClient counts what
    // reached the stub and which key each request carried, and the sleeper records each
    // retry sleep and really sleeps it (the hold knob runs on real time). The stub knobs
    // are filename-based (stubEnv STUB_TEST_KNOBS=true): persist-unknown creates the job
    // and still answers 503 Retry-After 5, persist-503 answers 503 Retry-After 5 without a
    // job, hold-1500 keeps the key reserved for 1.5 s (a concurrent same-key submit gets
    // 409 idempotency_in_progress Retry-After 1). Each passed test prints
    // JOB_SUBMIT_OK <name>.

    /** The kit's own PDF bytes (j28-unknown's document). */
    private static byte[] kitPdf() {
        for (JsonNode c : Kit.jobCases().path("cases")) {
            if ("j28-unknown".equals(c.path("id").asText())) {
                return Base64.getDecoder().decode(c.path("steps").get(0).path("request").path("file")
                        .path("base64").asText());
            }
        }
        throw new IllegalStateException("j28-unknown is missing from job-cases.json");
    }

    private static String submitQuery(String name) {
        return "What is the total amount? (p26-10-" + name + "-" + RUN + ")";
    }

    private static String submitKey(String name) {
        return "p26-10-" + name + "-" + RUN;
    }

    /** A client on the capturing transport whose sleeper records and really sleeps. */
    private static DocQL submitClient(CapturingClient http, List<Duration> sleeps) {
        return DocQL.builder()
                .apiKey(Kit.jobCases().path("keys").path("valid").asText())
                .apiUrl(base)
                .httpClient(http)
                .sleeper(d -> {
                    sleeps.add(d);
                    Thread.sleep(d.toMillis());
                })
                .build();
    }

    private static Job submitPdf(DocQL client, DocFile file, String query, String key) {
        return client.submitDocument(file, Instruction.query(query),
                QueryOptions.builder().idempotencyKey(key).build());
    }

    private static List<Long> seconds(List<Duration> sleeps) {
        List<Long> out = new ArrayList<>();
        for (Duration d : sleeps) {
            out.add(d.getSeconds());
        }
        return out;
    }

    @Test
    void persistUnknownIsRetriedOnceWithTheSameKeyAndReturnsTheReplayedJob() {
        if (base == null) {
            return;
        }
        CapturingClient http = new CapturingClient();
        List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
        DocQL client = submitClient(http, sleeps);
        String key = submitKey("persist-unknown");
        Job job = submitPdf(client, DocFile.bytes(kitPdf(), "persist-unknown.pdf"), submitQuery("persist-unknown"),
                key);
        assertEquals(JobStatus.QUEUED, job.status());
        assertEquals(key, job.idempotencyKey());
        assertNotNull(job.jobId());
        assertEquals(List.of(503, 202), http.statuses());
        assertEquals(List.of(List.of(key), List.of(key)), http.keys());
        assertEquals("5", http.seen().get(0).header("retry-after"));
        assertEquals("true", http.seen().get(1).header("idempotent-replayed"));
        assertEquals(List.of(5L), seconds(sleeps));
        // The replayed job is the one DocQL created on the first (503) attempt, so it
        // exists. The kit's stubEnv keeps a job for 5 s and the retry slept 5 s, so the
        // owner may already see job_expired; job_not_found would mean the replay pointed
        // at a job that never existed.
        try {
            assertEquals(job.jobId(), client.getJob(job.jobId()).jobId());
        } catch (DocQLException e) {
            assertEquals(Reasons.JOB_EXPIRED, e.reason(), e.getMessage());
        }
        System.out.println("JOB_SUBMIT_OK persist-unknown");
    }

    @Test
    void persist503ThrowsThe503WithTheKeyAfterFourRequests() {
        if (base == null) {
            return;
        }
        CapturingClient http = new CapturingClient();
        List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
        DocQL client = submitClient(http, sleeps);
        String key = submitKey("persist-503");
        DocQLException e = assertThrows(DocQLException.class,
                () -> submitPdf(client, DocFile.bytes(kitPdf(), "persist-503.pdf"), submitQuery("persist-503"), key));
        assertEquals(503, e.status());
        assertEquals("upstream_unavailable", e.reason());
        assertEquals(key, e.idempotencyKey());
        assertEquals(Duration.ofSeconds(5), e.retryAfter());
        assertEquals(List.of(503, 503, 503, 503), http.statuses());
        assertEquals(List.of(List.of(key), List.of(key), List.of(key), List.of(key)), http.keys());
        assertEquals(List.of(5L, 5L, 5L), seconds(sleeps));
        System.out.println("JOB_SUBMIT_OK persist-503");
    }

    @Test
    void sameKeyTwiceReturnsTheSameJobIdAndTheSecondIsReplayed() {
        if (base == null) {
            return;
        }
        CapturingClient http = new CapturingClient();
        List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
        DocQL client = submitClient(http, sleeps);
        String key = submitKey("same-key-twice");
        Job first = submitPdf(client, DocFile.bytes(kitPdf(), "twice.pdf"), submitQuery("same-key-twice"), key);
        Job second = submitPdf(client, DocFile.bytes(kitPdf(), "twice.pdf"), submitQuery("same-key-twice"), key);
        assertEquals(first.jobId(), second.jobId());
        assertEquals(List.of(202, 202), http.statuses());
        assertNull(http.seen().get(0).header("idempotent-replayed"));
        assertEquals("true", http.seen().get(1).header("idempotent-replayed"));
        assertEquals(List.of(), sleeps);
        System.out.println("JOB_SUBMIT_OK same-key-twice");
    }

    @Test
    void holdConcurrentSameKeySubmitsReturnTheSameJobOneAfterA409() throws InterruptedException {
        if (base == null) {
            return;
        }
        CapturingClient http = new CapturingClient();
        List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
        DocQL client = submitClient(http, sleeps);
        String key = submitKey("hold-concurrent");
        Map<String, Job> results = new ConcurrentHashMap<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        // The kit's j16 starts the second submit 300 ms after the first.
        List<Thread> threads = new ArrayList<>();
        for (String name : List.of("first", "second")) {
            long delay = "first".equals(name) ? 0 : 300;
            threads.add(new Thread(() -> {
                try {
                    Thread.sleep(delay);
                    results.put(name, submitPdf(client, DocFile.bytes(kitPdf(), "hold-1500.pdf"),
                            submitQuery("hold-concurrent"), key));
                } catch (Throwable t) {
                    errors.add(t);
                }
            }, "hold-" + name));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(60_000);
        }
        assertEquals(List.of(), errors);
        assertEquals(results.get("first").jobId(), results.get("second").jobId());
        assertEquals(key, results.get("first").idempotencyKey());
        assertEquals(key, results.get("second").idempotencyKey());
        List<Integer> statuses = http.statuses();
        assertTrue(statuses.contains(409), statuses.toString());
        List<String> replayed = new ArrayList<>();
        for (Seen s : http.seen()) {
            assertEquals(List.of(key), s.keys);
            if (s.status == 409) {
                assertEquals("1", s.header("retry-after"));
            }
            if (s.status == 202) {
                replayed.add(String.valueOf(s.header("idempotent-replayed")));
            }
        }
        Collections.sort(replayed);
        // One fresh 202 (the first submit) and one replay (the second, after its 409s).
        assertEquals(List.of("null", "true"), replayed);
        assertFalse(sleeps.isEmpty());
        for (Duration d : sleeps) {
            assertEquals(Duration.ofSeconds(1), d);
        }
        System.out.println("JOB_SUBMIT_COUNTS hold-concurrent " + statuses);
        System.out.println("JOB_SUBMIT_OK hold-concurrent");
    }

    @Test
    void aNonReplayableStreamOnPersistUnknownThrowsThe503WithTheKeyAfterOneRequest() throws IOException {
        if (base == null) {
            return;
        }
        CapturingClient http = new CapturingClient();
        List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
        DocQL client = submitClient(http, sleeps);
        String key = submitKey("non-replayable");
        byte[] pdf = kitPdf();
        Path file = Files.createTempFile("p26-10-non-replayable", ".pdf");
        try {
            Files.write(file, pdf);
            try (FileInputStream in = new FileInputStream(file.toFile())) {
                assertFalse(in.markSupported(), "a FileInputStream cannot be reset to its start");
                DocQLException e = assertThrows(DocQLException.class,
                        () -> submitPdf(client, DocFile.stream(in, pdf.length, "persist-unknown.pdf"),
                                submitQuery("non-replayable"), key));
                assertEquals(503, e.status());
                assertEquals(key, e.idempotencyKey());
                assertTrue(in.getChannel().isOpen(), "the caller's stream is never closed");
            }
        } finally {
            Files.deleteIfExists(file);
        }
        assertEquals(List.of(503), http.statuses());
        assertEquals(List.of(), sleeps);
        System.out.println("JOB_SUBMIT_OK non-replayable");
    }

    // --- the capturing transport ----------------------------------------------------------------

    /** One request the capturing client sent and the answer it got. */
    static final class Seen {

        final String method;
        final String rawPath;
        final List<String> keys;
        final int status;
        final HttpHeaders headers;
        final byte[] body;

        Seen(String method, String rawPath, List<String> keys, int status, HttpHeaders headers, byte[] body) {
            this.method = method;
            this.rawPath = rawPath;
            this.keys = keys;
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        Jobs.Answer answer() {
            return new Jobs.Answer(status, headers, body);
        }
    }

    /**
     * The SDK's own transport settings (HTTP/1.1, no redirects, 10 s
     * connect), plus a record of every request and its answer. The SDK reads
     * every body as bytes, so the record keeps the exact bytes it decoded.
     */
    static final class CapturingClient extends HttpClient {

        private final HttpClient delegate = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        private final List<Seen> seen = new CopyOnWriteArrayList<>();

        List<Seen> seen() {
            return seen;
        }

        List<Integer> statuses() {
            List<Integer> statuses = new ArrayList<>();
            for (Seen s : seen) {
                statuses.add(s.status);
            }
            return statuses;
        }

        List<List<String>> keys() {
            List<List<String>> keys = new ArrayList<>();
            for (Seen s : seen) {
                keys.add(s.keys);
            }
            return keys;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            HttpResponse<T> response = delegate.send(request, handler);
            Object body = response.body();
            seen.add(new Seen(request.method(), request.uri().getRawPath(),
                    request.headers().allValues("Idempotency-Key"), response.statusCode(), response.headers(),
                    body instanceof byte[] ? (byte[]) body : new byte[0]));
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException("the job methods are synchronous");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
            throw new UnsupportedOperationException("the job methods are synchronous");
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }
    }

    private static String sub(String text) {
        return text.replace("{run}", RUN);
    }
}
