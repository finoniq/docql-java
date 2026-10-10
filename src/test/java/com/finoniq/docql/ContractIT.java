package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The contract runner (JAVA-06): every {@code via: sdk} case of the vendored
 * shared list runs through the same queryDocument path the tracer fixed, and
 * every {@code via: raw} case is replayed as a plain HTTP POST decoded by the
 * same decoder the SDK uses, against the digest-pinned stub booted by
 * {@code scripts/stub-up.sh}. The stub URL arrives in {@code DOCQL_STUB_URL};
 * with {@code DOCQL_REQUIRE_STUB=1} a missing stub fails the run, otherwise
 * the cases skip so plain unit runs stay green. Before any case runs, a
 * once-per-JVM gate proves the stub serves exactly the vendored kit: the
 * sha256 of the served bytes, the manifest entry and the vendored file must
 * all agree, and any mismatch — or a read failure — fails every test with
 * {@code CONTRACT_KIT_MISMATCH:} instead of letting a wrong kit look like a
 * broken network. Assertions stay semantic:
 * they never read the stub's extraction state or any stub-prefixed metadata
 * key, so a cached repeat passes like a fresh call. The Java-only live checks
 * prove the async surface, path and stream inputs, the deadline, the size
 * hint, interruption and concurrency against the same stub.
 */
class ContractIT {

    /** How much of /__stub/sdk-cases may be read for the kit check. */
    private static final int KIT_READ_CAP = 1 << 20;

    /** The plain client of the kit check; it never carries the API key. */
    private static final HttpClient GATE_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Cases that ran to completion, sdk and raw together (the count gate). */
    private static int ran;

    /** Whether a stub was resolved for this JVM. */
    private static boolean stubbed;

    /** The vendored sdk+raw case count the count gate demands. */
    private static int wantCases;

    /** The resolved stub base, or null when no stub runs. */
    private static String base;

    /** The kit-gate detail; null once the three-way sha256 check has passed. */
    private static String gateError;

    /**
     * Once per JVM: the strict-stub rule, then the three-way sha256 gate over
     * the served bytes, the manifest entry and the vendored file. A gate
     * failure is kept and fails every stub-bound test by name.
     */
    @BeforeAll
    static void resolveStubAndCheckKit() {
        String url = System.getenv("DOCQL_STUB_URL");
        if (url == null || url.isBlank()) {
            if ("1".equals(System.getenv("DOCQL_REQUIRE_STUB"))) {
                fail("DOCQL_REQUIRE_STUB=1 but DOCQL_STUB_URL is not set: "
                        + "run the cases through scripts/stub-up.sh --run");
            }
            return;
        }
        base = url.replaceAll("/+$", "");
        stubbed = true;
        wantCases = caseCount(Kit.sdkCases());
        gateError = verifyKit(base);
    }

    /**
     * The stub base for a stub-bound test: fails by name when the kit gate
     * has not passed, so no case ever runs against an unproven stub.
     */
    private static String gatedBase() {
        if (gateError != null) {
            fail("CONTRACT_KIT_MISMATCH: " + gateError);
        }
        return base;
    }

    /** The strict-stub rule: REQUIRE_STUB=1 fails when no stub runs, else skip. */
    @Test
    void stubRequirement() {
        String b = gatedBase();
        if (b == null) {
            Assumptions.assumeTrue(false,
                    "DOCQL_STUB_URL is not set: run through scripts/stub-up.sh --run to check the shared cases");
        }
    }

    @TestFactory
    Stream<DynamicTest> sdkCases() {
        String b = gatedBase();
        if (b == null) {
            return Stream.empty();
        }
        JsonNode kit = Kit.sdkCases();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : kit.path("cases")) {
            if (!"sdk".equals(c.path("via").asText())) {
                continue;
            }
            String id = c.path("id").asText();
            tests.add(DynamicTest.dynamicTest(id, () -> runCase(kit, c, b)));
        }
        return tests.stream();
    }

    /**
     * Every {@code via: raw} case of the vendored list, replayed as a plain
     * HTTP POST (test scaffolding, never the SDK's client) and decoded by the
     * same package-private decoder the SDK client uses, so the decoder is
     * proven against live bytes, not only fixtures.
     */
    @TestFactory
    Stream<DynamicTest> rawCases() {
        String b = gatedBase();
        if (b == null) {
            return Stream.empty();
        }
        JsonNode kit = Kit.sdkCases();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : kit.path("cases")) {
            if (!"raw".equals(c.path("via").asText())) {
                continue;
            }
            String id = c.path("id").asText();
            tests.add(DynamicTest.dynamicTest(id, () -> runRawCase(kit, c, b)));
        }
        return tests.stream();
    }

    /**
     * Fails the run when the vendored list carries a case with an unknown
     * via: a new case must be wired into a runner, never silently disappear.
     */
    @Test
    void knownVia() {
        List<String> unknown = new ArrayList<>();
        int total = 0;
        for (JsonNode c : Kit.sdkCases().path("cases")) {
            total++;
            String via = c.path("via").asText("");
            if (!"sdk".equals(via) && !"raw".equals(via)) {
                unknown.add(c.path("id").asText() + " (via " + via + ")");
            }
        }
        assertTrue(unknown.isEmpty(),
                "sdk-cases.json carries cases with an unknown via: " + unknown);
        assertTrue(total > 0, "sdk-cases.json carries no cases at all");
    }

    /**
     * The Java-only live checks (JAVA-03, JAVA-04, JAVA-05): the 2 s deadline
     * against a 5 s stub hold, an injected HTTP/2 client, async success and
     * async error shapes, path and known-length stream inputs, the 25 MiB
     * size hint, interruption and concurrency. Every check uses its own
     * distinct query so the stub's cache can never mask a difference.
     */
    @TestFactory
    Stream<DynamicTest> javaExtras() {
        String base = gatedBase();
        if (base == null) {
            return Stream.empty();
        }
        JsonNode kit = Kit.sdkCases();
        String valid = kit.path("keys").path("valid").asText();
        String invalid = kit.path("keys").path("invalid").asText();
        byte[] okPdf = Kit.goldenFileBytes("ascii-query");
        List<DynamicTest> tests = new ArrayList<>();

        tests.add(DynamicTest.dynamicTest("sdk-timeout", () -> {
            DocQL client = DocQL.builder().apiKey(valid).apiUrl(base)
                    .timeout(Duration.ofSeconds(2)).build();
            DocQLTimeoutException e = assertThrows(DocQLTimeoutException.class, () -> client
                    .queryDocument(DocFile.bytes(okPdf, "invoice.pdf"),
                            Instruction.query("java-extras: sdk timeout"),
                            QueryOptions.builder().filename("delay-5000.pdf").build()));
            assertNotNull(e.getMessage(), "the timeout error carries a message");
            System.out.println("CONTRACT_EXTRA_OK sdk-timeout");
        }));

        tests.add(DynamicTest.dynamicTest("injected-http2-client", () -> {
            HttpClient h2 = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            DocQL client = DocQL.builder().apiKey(valid).apiUrl(base).httpClient(h2).build();
            QueryResult res = client.queryDocument(DocFile.bytes(okPdf, "invoice.pdf"),
                    Instruction.query("java-extras: injected http2 client"));
            assertEquals(1, res.pageCount(), "page_count");
            assertNotNull(res.requestId(), "request_id must be present");
            assertFalse(res.requestId().isEmpty(), "request_id must be present");
            System.out.println("CONTRACT_EXTRA_OK injected-http2-client");
        }));

        tests.add(DynamicTest.dynamicTest("async-ok", () -> {
            DocQL client = DocQL.builder().apiKey(valid).apiUrl(base).build();
            QueryResult res = client
                    .queryDocumentAsync(DocFile.bytes(okPdf, "invoice.pdf"),
                            Instruction.query("java-extras: async ok"))
                    .join();
            assertEquals(1, res.pageCount(), "page_count");
            assertNotNull(res.requestId(), "request_id must be present");
            assertFalse(res.requestId().isEmpty(), "request_id must be present");
            System.out.println("CONTRACT_EXTRA_OK async-ok");
        }));

        tests.add(DynamicTest.dynamicTest("async-error", () -> {
            DocQL client = DocQL.builder().apiKey(invalid).apiUrl(base).build();
            ExecutionException e = assertThrows(ExecutionException.class, () -> client
                    .queryDocumentAsync(DocFile.bytes(okPdf, "invoice.pdf"),
                            Instruction.query("java-extras: async error"))
                    .get());
            DocQLException cause = assertInstanceOf(DocQLException.class, e.getCause(),
                    "the failure cause must be the SDK's own error type");
            assertEquals(401, cause.status(), "status");
            assertEquals(Reasons.INVALID_API_KEY, cause.reason(), "reason");
            System.out.println("CONTRACT_EXTRA_OK async-error");
        }));

        tests.add(DynamicTest.dynamicTest("path-page-counts", () -> {
            DocQL client = DocQL.builder().apiKey(valid).apiUrl(base).build();
            Path p = tempFile("pages-3_ocr-1.pdf", okPdf);
            QueryResult res = client.queryDocument(DocFile.path(p),
                    Instruction.query("java-extras: path page counts"));
            assertEquals(3, res.pageCount(), "page_count");
            assertEquals(1, res.pagesOcr(), "pages_ocr");
            System.out.println("CONTRACT_EXTRA_OK path-page-counts");
        }));

        tests.add(DynamicTest.dynamicTest("stream-at-offset", () -> {
            DocQL client = DocQL.builder().apiKey(valid).apiUrl(base).build();
            byte[] junk = new byte[10];
            java.util.Arrays.fill(junk, (byte) 'J');
            Path p = tempFile("positioned.pdf", concat(junk, okPdf));
            try (FileInputStream in = new FileInputStream(p.toFile())) {
                assertEquals(10L, in.skip(10L), "the caller seeks to its own offset");
                QueryResult res = client.queryDocument(
                        DocFile.stream(in, okPdf.length, "invoice.pdf"),
                        Instruction.query("java-extras: stream at offset"));
                assertEquals(1, res.pageCount(), "page_count");
                assertEquals(-1, in.read(),
                        "the caller's stream must stay open and be fully consumed from its offset");
            }
            System.out.println("CONTRACT_EXTRA_OK stream-at-offset");
        }));

        tests.add(DynamicTest.dynamicTest("path-too-large", () -> {
            DocQL client = DocQL.builder().apiKey(valid).apiUrl(base).build();
            Path p = tempFile("big.pdf", null);
            try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
                raf.setLength(27L * 1024 * 1024);
            }
            try {
                client.queryDocument(DocFile.path(p),
                        Instruction.query("java-extras: path too large"));
                fail("expected a typed 413 or the size-hint connection error, got a result");
            } catch (DocQLException e) {
                assertEquals(413, e.status(), "status");
                assertEquals(Reasons.UPLOAD_TOO_LARGE, e.reason(), "reason");
                System.out.println("PATH_TOO_LARGE_OUTCOME typed");
            } catch (DocQLConnectionException e) {
                assertTrue(e.getMessage().contains("25 MiB"),
                        "an early-413 transport failure must carry the size hint: " + e.getMessage());
                System.out.println("PATH_TOO_LARGE_OUTCOME hint");
            }
            System.out.println("CONTRACT_EXTRA_OK path-too-large");
        }));

        tests.add(DynamicTest.dynamicTest("interrupted", () -> {
            DocQL client = DocQL.builder().apiKey(valid).apiUrl(base).build();
            Thread caller = Thread.currentThread();
            Thread interrupter = new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                caller.interrupt();
            });
            interrupter.start();
            try {
                client.queryDocument(DocFile.bytes(okPdf, "invoice.pdf"),
                        Instruction.query("java-extras: interrupted"),
                        QueryOptions.builder().filename("delay-5000.pdf").build());
                fail("expected the interrupted call to fail");
            } catch (DocQLConnectionException e) {
                assertTrue(Thread.interrupted(),
                        "the call must restore the caller's interrupt flag");
                assertNotNull(e.getMessage(), "the interrupted error carries a message");
            } finally {
                interrupter.join(5000);
                Thread.interrupted();
            }
            System.out.println("CONTRACT_EXTRA_OK interrupted");
        }));

        tests.add(DynamicTest.dynamicTest("concurrent", () -> {
            DocQL client = DocQL.builder().apiKey(valid).apiUrl(base).build();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<QueryResult>> futures = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    final int n = i;
                    futures.add(pool.submit(() -> client.queryDocument(
                            DocFile.bytes(okPdf, "invoice.pdf"),
                            Instruction.query("java-extras: concurrent " + n))));
                }
                for (int i = 0; i < 8; i++) {
                    QueryResult res = futures.get(i).get(120, TimeUnit.SECONDS);
                    assertEquals(1, res.pageCount(), "page_count of thread " + i);
                }
            } finally {
                pool.shutdownNow();
            }
            System.out.println("CONTRACT_EXTRA_OK concurrent");
        }));

        return tests.stream();
    }

    /**
     * The count gate: the sdk and raw cases that ran to completion must equal
     * the vendored count exactly, so a renumbered or half-failing list can
     * never silently go green.
     */
    @AfterAll
    static void countGate() {
        if (!stubbed) {
            return;
        }
        assertEquals(wantCases, ran, "ran " + ran + " of the " + wantCases
                + " sdk+raw cases: a case stopped before its last assertion");
        System.out.println("CONTRACT_CASES_RAN " + ran);
    }

    private static void runCase(JsonNode kit, JsonNode c, String base) {
        String id = c.path("id").asText();
        String key = "invalid".equals(c.path("key").asText())
                ? kit.path("keys").path("invalid").asText()
                : kit.path("keys").path("valid").asText();

        JsonNode request = c.path("request");
        JsonNode fileSpec = request.path("file");
        byte[] data = Kit.caseFileBytes(fileSpec);
        String name = fileSpec.path("name").asText();

        JsonNode query = request.get("query");
        Instruction instruction = query != null && !query.isNull()
                ? Instruction.query(query.asText())
                : Instruction.prompt(request.path("prompt").asText());

        QueryOptions.Builder options = QueryOptions.builder().filename(name);
        JsonNode mode = request.get("mode");
        if (mode != null && !mode.isNull()) {
            options.mode(Mode.of(mode.asText()));
        }

        DocQL client = DocQL.builder()
                .apiKey(key)
                .apiUrl(base)
                .build();

        JsonNode expect = c.path("expect");
        int wantStatus = expect.path("status").asInt();

        if (wantStatus == 200) {
            QueryResult result;
            try {
                result = client.queryDocument(DocFile.bytes(data, name), instruction, options.build());
            } catch (DocQLException e) {
                fail("expected status 200, got error: " + e.getMessage());
                return;
            }
            assertNotNull(result, "expected a result");
            assertEquals(expect.path("pageCount").asInt(), result.pageCount(), "page_count");
            assertEquals(expect.path("pagesOcr").asInt(), result.pagesOcr(), "pages_ocr");
            assertNotNull(result.requestId(), "request_id must be present on a 200 envelope");
            assertFalse(result.requestId().isEmpty(), "request_id must be present on a 200 envelope");
        } else {
            DocQLException e;
            try {
                client.queryDocument(DocFile.bytes(data, name), instruction, options.build());
                fail("expected status " + wantStatus + ", got a successful result");
                return;
            } catch (DocQLException de) {
                e = de;
            }
            assertEquals(wantStatus, e.status(), "status");
            JsonNode wantReason = expect.get("reason");
            if (wantReason != null && !wantReason.isNull()) {
                assertEquals(wantReason.asText(), e.reason(), "reason");
            }
            JsonNode retryAfter = expect.get("retryAfterSeconds");
            if (retryAfter != null && !retryAfter.isNull()) {
                assertEquals(Duration.ofSeconds(retryAfter.asLong()), e.retryAfter(), "retry_after");
            }
            assertEquals(wantStatus == 429 || wantStatus == 503, e.retryable(), "retryable");
            if (expect.path("requestIdPresent").asBoolean(false)) {
                assertNotNull(e.requestId(), "request_id must be present");
            }
        }
        System.out.println("CONTRACT_CASE_OK sdk " + id);
        ran++;
    }

    private static void runRawCase(JsonNode kit, JsonNode c, String base) {
        String id = c.path("id").asText();
        JsonNode raw = c.path("raw");
        assertTrue(raw.isObject(), "raw case " + id + " carries no raw request");

        String key = "invalid".equals(c.path("key").asText())
                ? kit.path("keys").path("invalid").asText()
                : kit.path("keys").path("valid").asText();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1/query-document"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", raw.path("contentType").asText())
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        raw.path("bodyUtf8").asText().getBytes(StandardCharsets.UTF_8)));
        if (!"missing".equals(c.path("key").asText())) {
            builder.header("X-API-Key", key);
        }
        HttpRequest request = builder.build();

        // Test scaffolding: a plain HTTP/1.1 client that never follows a
        // redirect, built per case, never the SDK's own client.
        HttpClient replay = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = replay.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("POST " + base + "/v1/query-document", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("raw replay interrupted", e);
        }

        int status = response.statusCode();
        byte[] body = response.body();
        JsonNode expect = c.path("expect");

        if (status / 100 == 2) {
            QueryResult res = Decoder.parseResult(status, response.headers(), body, key);
            assertNotNull(res, "expected a result");
            JsonNode wantPages = expect.get("pageCount");
            if (wantPages != null && !wantPages.isNull()) {
                assertEquals(wantPages.asInt(), res.pageCount(), "page_count");
            }
            JsonNode wantOcr = expect.get("pagesOcr");
            if (wantOcr != null && !wantOcr.isNull()) {
                assertEquals(wantOcr.asInt(), res.pagesOcr(), "pages_ocr");
            }
            assertNotNull(res.requestId(), "request_id must be present on a 200 envelope");
            assertFalse(res.requestId().isEmpty(), "request_id must be present on a 200 envelope");
        } else {
            DocQLException e = Decoder.decodeError(status, response.headers(), body, key);
            assertEquals(expect.path("status").asInt(), e.status(), "status");
            JsonNode wantReason = expect.get("reason");
            if (wantReason != null && !wantReason.isNull()) {
                assertEquals(wantReason.asText(), e.reason(), "reason");
            }
            JsonNode retryAfter = expect.get("retryAfterSeconds");
            if (retryAfter != null && !retryAfter.isNull()) {
                assertEquals(Duration.ofSeconds(retryAfter.asLong()), e.retryAfter(), "retry_after");
            }
            assertEquals(expect.path("status").asInt() == 429 || expect.path("status").asInt() == 503,
                    e.retryable(), "retryable");
            if (expect.path("requestIdPresent").asBoolean(false)) {
                assertNotNull(e.requestId(), "request_id must be present");
            }
        }
        System.out.println("CONTRACT_CASE_OK raw " + id);
        ran++;
    }

    /** The vendored case count across both runners. */
    private static int caseCount(JsonNode kit) {
        int n = 0;
        for (JsonNode c : kit.path("cases")) {
            String via = c.path("via").asText();
            if ("sdk".equals(via) || "raw".equals(via)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Writes bytes to a fresh temp directory under the exact name — the
     * basename drives the stub's filename knobs and must not carry a random
     * prefix — or creates an empty one when bytes is null.
     */
    private static Path tempFile(String name, byte[] bytes) {
        try {
            Path dir = Files.createTempDirectory("docql-it-");
            dir.toFile().deleteOnExit();
            Path p = dir.resolve(name);
            if (bytes != null) {
                Files.write(p, bytes);
            }
            p.toFile().deleteOnExit();
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException("write temp file " + name, e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * The kit gate (T-13-19), run once per JVM: the sha256 of the bytes
     * {@code GET /__stub/sdk-cases} serves (read capped at 1 MiB) must equal
     * the sdk-cases.json entry of the vendored manifest and the vendored file
     * itself. Any mismatch — or a non-200, over-cap body, or an unreachable
     * stub — returns the failure detail that fails every stub-bound test
     * under the {@code CONTRACT_KIT_MISMATCH:} prefix; it can fail, never
     * silently skip.
     */
    private static String verifyKit(String stubBase) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(stubBase + "/__stub/sdk-cases"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = GATE_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            return "GET " + stubBase + "/__stub/sdk-cases: " + e.getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "GET " + stubBase + "/__stub/sdk-cases: interrupted";
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                return "GET /__stub/sdk-cases: HTTP " + response.statusCode();
            }
            byte[] served = body.readNBytes(KIT_READ_CAP + 1);
            if (served.length > KIT_READ_CAP) {
                return "/__stub/sdk-cases exceeds the " + KIT_READ_CAP + "-byte read cap";
            }
            String servedSha = sha256Hex(served);
            String declared = Kit.manifestFiles().path("sdk-cases.json").asText(null);
            if (declared == null || declared.isEmpty()) {
                return "contract/manifest.json carries no sdk-cases.json entry";
            }
            String vendoredSha = sha256Hex(Files.readAllBytes(Path.of("contract", "sdk-cases.json")));
            if (!servedSha.equals(declared) || !servedSha.equals(vendoredSha)) {
                return "sdk-cases.json sha256 mismatch: served " + servedSha
                        + ", manifest " + declared + ", vendored " + vendoredSha;
            }
            return null;
        } catch (IOException e) {
            return "read /__stub/sdk-cases: " + e.getClass().getSimpleName();
        }
    }

    private static String sha256Hex(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : digest.digest(bytes)) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
