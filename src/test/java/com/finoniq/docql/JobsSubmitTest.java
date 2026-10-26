package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same-key submit retry (JSDK-02, D-07, D-08), the Java port of the
 * Python SDK's {@code tests/unit/test_submit.py}: {@link FakeServer} scripts
 * the answers and the Builder's sleeper seam records every retry sleep
 * without sleeping.
 */
class JobsSubmitTest {

    private static final String KEY = "unit-test-key";
    private static final byte[] PDF = "%PDF-1.4 submit unit".getBytes(StandardCharsets.US_ASCII);
    private static final String BOUNDARY = "jobssubmittestboundary000000001";

    private FakeServer server;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = new FakeServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private DocQL.Builder builder(String url) {
        return DocQL.builder().apiKey(KEY).apiUrl(url).env(name -> null).boundary(() -> BOUNDARY)
                .sleeper(sleeps::add);
    }

    private DocQL client() {
        return builder(server.url()).build();
    }

    private Job submit(DocFile file) {
        return client().submitDocument(file, Instruction.query("q"));
    }

    private Job submit(DocFile file, String key) {
        return client().submitDocument(file, Instruction.query("q"),
                QueryOptions.builder().idempotencyKey(key).build());
    }

    private static DocFile pdf() {
        return DocFile.bytes(PDF, "f.pdf");
    }

    private List<Long> sleepSeconds() {
        List<Long> seconds = new ArrayList<>();
        for (Duration d : sleeps) {
            seconds.add(d.getSeconds());
        }
        return seconds;
    }

    private List<List<String>> keysSent() {
        List<List<String>> keys = new ArrayList<>();
        for (FakeServer.CapturedRequest request : server.requests()) {
            keys.add(request.headerValues("Idempotency-Key"));
        }
        return keys;
    }

    private static JsonNode decodeCase(String id) {
        for (JsonNode c : Kit.jobCases().path("decodeCases")) {
            if (id.equals(c.path("id").asText())) {
                return c;
            }
        }
        throw new IllegalArgumentException("decode case " + id + " not found");
    }

    /** Enqueues a kit decode case as the next answer. */
    private void answer(String caseId) {
        JsonNode c = decodeCase(caseId);
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> header : c.path("headers").properties()) {
            headers.put(header.getKey(), header.getValue().asText());
        }
        server.enqueue(c.path("status").asInt(), headers, c.path("bodyUtf8").asText(""));
    }

    private static String envelope(String reason) {
        return "{\"error_info\":\"kit\",\"metadata\":{\"reason\":\"" + reason + "\"}}";
    }

    private void queued() {
        answer("job-202-queued");
    }

    private void unavailable() {
        server.enqueue(503, Map.of("Retry-After", "5"), envelope("upstream_unavailable"));
    }

    private void inProgress() {
        server.enqueue(409, Map.of("Retry-After", "1"), envelope("idempotency_in_progress"));
    }

    @Test
    void constantsAreTheD07Numbers() {
        assertEquals(4, Jobs.SUBMIT_MAX_ATTEMPTS);
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)),
                Jobs.SUBMIT_BACKOFF);
    }

    // --- retry rule -----------------------------------------------------------------------------

    @Test
    void a503Then202ReturnsTheJobWithTheSameKeyOnBothAttempts() {
        unavailable();
        queued();
        Job job = submit(pdf(), "order-1");
        assertEquals(JobStatus.QUEUED, job.status());
        assertEquals("order-1", job.idempotencyKey());
        assertEquals(List.of(List.of("order-1"), List.of("order-1")), keysSent());
        assertEquals(List.of(5L), sleepSeconds());
    }

    @Test
    void a503FourTimesThrowsTheFourthWithTheKey() {
        unavailable();
        server.repeatLast();
        DocQLException e = assertThrows(DocQLException.class, () -> submit(pdf(), "order-2"));
        assertEquals(503, e.status());
        assertEquals("upstream_unavailable", e.reason());
        assertEquals("order-2", e.idempotencyKey());
        assertEquals(Duration.ofSeconds(5), e.retryAfter());
        assertEquals(Jobs.SUBMIT_MAX_ATTEMPTS, server.requestCount());
        assertEquals(List.of(List.of("order-2"), List.of("order-2"), List.of("order-2"), List.of("order-2")),
                keysSent());
        assertEquals(List.of(5L, 5L, 5L), sleepSeconds());
    }

    @Test
    void a503WithoutRetryAfterBacksOff124() {
        server.enqueue(503, envelope("upstream_unavailable"));
        server.repeatLast();
        DocQLException e = assertThrows(DocQLException.class, () -> submit(pdf()));
        assertEquals(503, e.status());
        assertEquals(4, server.requestCount());
        assertEquals(List.of(1L, 2L, 4L), sleepSeconds());
    }

    @Test
    void any503IsRetriedWhateverItsReason() {
        server.enqueue(503, envelope("service_overloaded"));
        queued();
        submit(pdf());
        assertEquals(2, server.requestCount());
        assertEquals(List.of(1L), sleepSeconds());
    }

    @ParameterizedTest
    @CsvSource({"0,1", "1,1", "7,7", "30,30", "3600,30", "99999999999999999999,30"})
    void retryAfterIsClampedTo1And30(String retryAfter, long slept) {
        server.enqueue(503, Map.of("Retry-After", retryAfter), envelope("upstream_unavailable"));
        queued();
        submit(pdf());
        assertEquals(List.of(slept), sleepSeconds());
    }

    @Test
    void a409InProgressThen202IsRetriedAfterRetryAfter1() {
        inProgress();
        queued();
        Job job = submit(pdf(), "order-3");
        assertEquals("order-3", job.idempotencyKey());
        assertEquals(List.of(List.of("order-3"), List.of("order-3")), keysSent());
        assertEquals(List.of(1L), sleepSeconds());
    }

    @Test
    void a422KeyConflictIsThrownAfterOneRequest() {
        server.enqueue(422, envelope("idempotency_key_conflict"));
        queued();
        DocQLException e = assertThrows(DocQLException.class, () -> submit(pdf(), "order-4"));
        assertEquals("idempotency_key_conflict", e.reason());
        assertEquals("order-4", e.idempotencyKey());
        assertEquals(1, server.requestCount());
        assertEquals(List.of(), sleeps);
    }

    @Test
    void a409WithAnotherReasonIsNotRetried() {
        server.enqueue(409, Map.of("Retry-After", "1"), envelope("idempotency_key_conflict"));
        queued();
        assertThrows(DocQLException.class, () -> submit(pdf()));
        assertEquals(1, server.requestCount());
        assertEquals(List.of(), sleeps);
    }

    @ParameterizedTest
    @CsvSource({
        "429,rate_limit_exceeded", "429,concurrency_limit_exceeded", "400,invalid_params",
        "401,invalid_api_key", "413,upload_too_large", "500,internal_error", "502,bad_gateway"})
    void otherErrorsAreThrownAfterOneRequestWithTheKey(int status, String reason) {
        server.enqueue(status, Map.of("Retry-After", "1"), envelope(reason));
        queued();
        DocQLException e = assertThrows(DocQLException.class, () -> submit(pdf(), "order-5"));
        assertEquals(status, e.status());
        assertEquals("order-5", e.idempotencyKey());
        assertEquals(1, server.requestCount());
        assertEquals(List.of(), sleeps);
    }

    @Test
    void aKit429QueueFullIsThrownAfterOneRequestWithTheKey() {
        answer("job-429-queue-full-tenant");
        queued();
        DocQLException e = assertThrows(DocQLException.class, () -> submit(pdf(), "order-5q"));
        assertEquals(429, e.status());
        assertEquals("queue_full", e.reason());
        assertEquals("order-5q", e.idempotencyKey());
        assertEquals(1, server.requestCount());
        assertEquals(List.of(), sleeps);
    }

    @Test
    void aConnectionFailureIsThrownWithTheKeyAndNeverRetried() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        DocQL client = builder("http://127.0.0.1:" + closedPort).build();
        DocQLConnectionException e = assertThrows(DocQLConnectionException.class,
                () -> client.submitDocument(pdf(), Instruction.query("q"),
                        QueryOptions.builder().idempotencyKey("order-6").build()));
        assertEquals("order-6", e.idempotencyKey());
        assertFalse(e instanceof DocQLTimeoutException);
        assertEquals(List.of(), sleeps);
    }

    @Test
    void aTimeoutIsThrownWithTheKeyAfterOneRequest() {
        server.delay(Duration.ofSeconds(2));
        DocQL client = builder(server.url()).timeout(Duration.ofMillis(300)).build();
        DocQLTimeoutException e = assertThrows(DocQLTimeoutException.class,
                () -> client.submitDocument(pdf(), Instruction.query("q"),
                        QueryOptions.builder().idempotencyKey("order-7").build()));
        assertEquals("order-7", e.idempotencyKey());
        assertEquals(1, server.requestCount());
        assertEquals(List.of(), sleeps);
    }

    @Test
    void aConnectionFailureAfterA503IsThrownWithTheSameKey() throws IOException {
        FakeServer closing = new FakeServer();
        closing.enqueue(503, Map.of("Retry-After", "5"), envelope("upstream_unavailable"));
        Jobs.Sleeper stopServer = d -> {
            sleeps.add(d);
            closing.close();
        };
        DocQL client = DocQL.builder().apiKey(KEY).apiUrl(closing.url()).env(name -> null).sleeper(stopServer).build();
        DocQLConnectionException e = assertThrows(DocQLConnectionException.class,
                () -> client.submitDocument(pdf(), Instruction.query("q"),
                        QueryOptions.builder().idempotencyKey("order-8").build()));
        assertEquals("order-8", e.idempotencyKey());
        assertEquals(1, closing.requestCount());
        assertEquals(List.of(5L), sleepSeconds());
    }

    @Test
    void anUnusable2xxCarriesTheKey() {
        server.enqueue(202, "<html>");
        DocQLException e = assertThrows(DocQLException.class, () -> submit(pdf(), "order-9"));
        assertEquals("order-9", e.idempotencyKey());
        assertEquals(1, server.requestCount());
    }

    @Test
    void anInterruptDuringTheRetrySleepRestoresTheFlagAndThrowsWithTheKey() {
        unavailable();
        queued();
        DocQL client = DocQL.builder().apiKey(KEY).apiUrl(server.url()).env(name -> null)
                .sleeper(d -> {
                    throw new InterruptedException("test");
                }).build();
        try {
            DocQLConnectionException e = assertThrows(DocQLConnectionException.class,
                    () -> client.submitDocument(pdf(), Instruction.query("q"),
                            QueryOptions.builder().idempotencyKey("order-int").build()));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is set again");
            assertEquals("order-int", e.idempotencyKey());
            assertInstanceOf(InterruptedException.class, e.getCause());
            assertFalse(e.getMessage().contains("order-int"), e.getMessage());
            assertEquals(1, server.requestCount());
        } finally {
            Thread.interrupted();
        }
    }

    // --- replayable input -----------------------------------------------------------------------

    @Test
    void bytesAreReplayedByteForByte() {
        unavailable();
        queued();
        submit(pdf());
        assertEquals(2, server.requestCount());
        byte[] first = server.requests().get(0).body();
        assertArrayEquals(first, server.requests().get(1).body());
        assertTrue(contains(first, PDF));
    }

    @Test
    void aPathIsReReadForTheRetry(@TempDir Path dir) throws IOException {
        Path doc = dir.resolve("doc.pdf");
        Files.write(doc, PDF);
        unavailable();
        queued();
        client().submitDocument(DocFile.path(doc), Instruction.query("q"));
        assertEquals(2, server.requestCount());
        byte[] first = server.requests().get(0).body();
        assertTrue(contains(first, PDF));
        assertArrayEquals(first, server.requests().get(1).body());
    }

    @Test
    void aPathThatVanishedBeforeTheRetryIsAConnectionFailureWithTheKey(@TempDir Path dir) throws IOException {
        Path doc = dir.resolve("doc.pdf");
        Files.write(doc, PDF);
        unavailable();
        queued();
        DocQL client = DocQL.builder().apiKey(KEY).apiUrl(server.url()).env(name -> null)
                .sleeper(d -> {
                    try {
                        Files.delete(doc);
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }).build();
        DocQLConnectionException e = assertThrows(DocQLConnectionException.class,
                () -> client.submitDocument(DocFile.path(doc), Instruction.query("q"),
                        QueryOptions.builder().idempotencyKey("order-gone").build()));
        assertEquals("order-gone", e.idempotencyKey());
        assertEquals(1, server.requestCount());
    }

    @Test
    void aMarkableStreamIsResetToItsMarkAndNeverClosedOrOverRead() throws IOException {
        byte[] wire = concat("0123456".getBytes(StandardCharsets.US_ASCII), PDF, "TAIL".getBytes(StandardCharsets.US_ASCII));
        TrackingStream in = new TrackingStream(new BufferedInputStream(new ByteArrayInputStream(wire)));
        assertEquals(7, in.skip(7));
        unavailable();
        queued();
        submit(DocFile.stream(in, PDF.length, "f.pdf"));
        assertEquals(2, server.requestCount());
        byte[] first = server.requests().get(0).body();
        assertArrayEquals(first, server.requests().get(1).body(), "the retry starts at the mark");
        assertTrue(contains(first, PDF));
        assertFalse(contains(first, "0123456".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(contains(first, "TAIL".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(1, in.resets, "reset only before the retry");
        assertFalse(in.closed, "the caller's stream is never closed");
        assertArrayEquals("TAIL".getBytes(StandardCharsets.US_ASCII), in.readAllBytes(),
                "never read past the declared length");
    }

    @Test
    void aSuccessfulStreamSubmitIsNeverReset() throws IOException {
        TrackingStream in = new TrackingStream(new BufferedInputStream(new ByteArrayInputStream(PDF)));
        queued();
        submit(DocFile.stream(in, PDF.length, "f.pdf"));
        assertEquals(0, in.resets);
        assertFalse(in.closed);
    }

    @Test
    void aFileInputStreamIsSentOnceAndThe503CarriesTheKey(@TempDir Path dir) throws IOException {
        Path doc = dir.resolve("doc.pdf");
        Files.write(doc, PDF);
        unavailable();
        queued();
        try (FileInputStream raw = new FileInputStream(doc.toFile())) {
            assertFalse(raw.markSupported());
            TrackingStream in = new TrackingStream(raw);
            DocQLException e = assertThrows(DocQLException.class,
                    () -> submit(DocFile.stream(in, PDF.length, "f.pdf"), "order-10"));
            assertEquals(503, e.status());
            assertEquals("order-10", e.idempotencyKey());
            assertEquals(1, server.requestCount());
            assertEquals(List.of(), sleeps);
            assertFalse(in.closed);
        }
    }

    @Test
    void aNonMarkableStreamGetsA409InProgressThrownOnce() {
        inProgress();
        queued();
        InputStream in = new NoMark(PDF);
        DocQLException e = assertThrows(DocQLException.class, () -> submit(DocFile.stream(in, PDF.length, "f.pdf")));
        assertEquals("idempotency_in_progress", e.reason());
        assertNotNull(e.idempotencyKey());
        assertEquals(1, server.requestCount());
    }

    @Test
    void aStreamWhoseResetFailsIsNeverResentAndThrowsWithTheKey() {
        unavailable();
        queued();
        InputStream broken = new BufferedInputStream(new NoMark(PDF)) {
            @Override
            public synchronized void reset() throws IOException {
                throw new IOException("Resetting to invalid mark");
            }
        };
        assertTrue(broken.markSupported());
        DocQLConnectionException e = assertThrows(DocQLConnectionException.class,
                () -> submit(DocFile.stream(broken, PDF.length, "f.pdf"), "order-reset"));
        assertEquals("order-reset", e.idempotencyKey());
        assertEquals(1, server.requestCount(), "no second document under the key");
    }

    @Test
    void isReplayableFollowsThe2601StreamRule(@TempDir Path dir) throws IOException {
        Path doc = dir.resolve("doc.pdf");
        Files.write(doc, PDF);
        assertTrue(Jobs.isReplayable(pdf()));
        assertTrue(Jobs.isReplayable(DocFile.path(doc)));
        assertTrue(Jobs.isReplayable(DocFile.stream(new ByteArrayInputStream(PDF), PDF.length, "f.pdf")));
        assertTrue(Jobs.isReplayable(DocFile.stream(new BufferedInputStream(new NoMark(PDF)), PDF.length, "f.pdf")));
        assertFalse(Jobs.isReplayable(DocFile.stream(new NoMark(PDF), PDF.length, "f.pdf")));
        try (FileInputStream raw = new FileInputStream(doc.toFile())) {
            assertFalse(Jobs.isReplayable(DocFile.stream(raw, PDF.length, "f.pdf")));
        }
        assertFalse(Jobs.isReplayable(DocFile.stream(new ByteArrayInputStream(PDF), Integer.MAX_VALUE + 1L, "f.pdf")),
                "a length over the int mark limit cannot be marked");
    }

    // --- key rules ------------------------------------------------------------------------------

    @Test
    void noKeyGeneratesOne43CharUrlSafeKeyReusedOnEveryAttempt() {
        unavailable();
        inProgress();
        queued();
        Job job = submit(pdf());
        List<List<String>> sent = keysSent();
        assertEquals(3, sent.size());
        String key = sent.get(0).get(0);
        assertEquals(List.of(List.of(key), List.of(key), List.of(key)), sent);
        assertTrue(key.matches("[A-Za-z0-9_-]{43}"), key);
        assertEquals(key, job.idempotencyKey());
    }

    @Test
    void eachCallWithoutAKeyGetsANewKey() {
        queued();
        queued();
        assertNotEquals(submit(pdf()).idempotencyKey(), submit(pdf()).idempotencyKey());
    }

    /**
     * The Idempotency-Key line values exactly as they left the SDK, read off
     * a raw socket: a JDK HttpServer (FakeServer) strips the optional
     * whitespace around a header value, as HTTP parsers do, so it cannot show
     * a key with a leading or trailing space.
     */
    private List<String> wireKeyLines(String key) throws Exception {
        List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        JsonNode queued = decodeCase("job-202-queued");
        byte[] body = queued.path("bodyUtf8").asText().getBytes(StandardCharsets.UTF_8);
        byte[] response = ("HTTP/1.1 202 Accepted\r\nContent-Type: application/json\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        RstServer raw = new RstServer();
        try {
            raw.enqueue(socket -> {
                InputStream in = socket.getInputStream();
                java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
                while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                    int b = in.read();
                    if (b < 0) {
                        break;
                    }
                    head.write(b);
                }
                long length = 0;
                for (String line : head.toString(StandardCharsets.ISO_8859_1).split("\r\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0 && "idempotency-key".equalsIgnoreCase(line.substring(0, colon))) {
                        lines.add(line.substring(colon + 1));
                    }
                    if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                        length = Long.parseLong(line.substring(colon + 1).trim());
                    }
                }
                in.readNBytes((int) length);
                socket.getOutputStream().write(RstServer.concat(response, body));
                socket.getOutputStream().flush();
            });
            Job job = builder(raw.url()).build().submitDocument(pdf(), Instruction.query("q"),
                    QueryOptions.builder().idempotencyKey(key).build());
            assertEquals(key, job.idempotencyKey());
        } finally {
            raw.close();
        }
        return lines;
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "~", " leading and trailing ", "a~b c"})
    void keysAtTheEdgesAreSentUnchanged(String key) throws Exception {
        // The JDK writes "name: value" and, like every HTTP parser on the
        // receiving side (RFC 9110 5.5: a field value never starts or ends
        // with whitespace), drops the spaces around the value. DocQL sees the
        // same trimmed key from every SDK; the Job keeps the caller's string.
        assertEquals(List.of(" " + key.strip()), wireKeyLines(key));
    }

    @Test
    void a255CharacterKeyAndEveryPrintableCharacterAreSentUnchanged() throws Exception {
        StringBuilder printable = new StringBuilder();
        for (char c = 0x20; c <= 0x7E; c++) {
            printable.append(c);
        }
        for (String key : List.of("k".repeat(255), printable.toString())) {
            assertEquals(List.of(" " + key.strip()), wireKeyLines(key));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a\u001fb", "a\u007fb", "café", "ก", "a\nb", "a\tb"})
    void badKeysAreRefusedBeforeAnyRequestWithoutEcho(String key) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> submit(pdf(), key));
        assertEquals(Jobs.IDEMPOTENCY_KEY_MESSAGE, e.getMessage());
        if (!key.isEmpty()) {
            assertFalse(e.getMessage().contains(key));
            assertFalse(e.toString().contains(key));
        }
        assertEquals(0, server.requestCount());
    }

    @Test
    void a256CharacterKeyIsRefusedBeforeAnyRequest() {
        String key = "k".repeat(256);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> submit(pdf(), key));
        assertFalse(e.getMessage().contains(key));
        assertEquals(0, server.requestCount());
    }

    @Test
    void theKeyIsNeverInAnErrorMessage() {
        unavailable();
        server.repeatLast();
        DocQLException e = assertThrows(DocQLException.class, () -> submit(pdf(), "secret-order-key"));
        assertFalse(e.getMessage().contains("secret-order-key"), e.getMessage());
        assertFalse(e.toString().contains("secret-order-key"), e.toString());
    }

    @Test
    void oneIdempotencyKeyLinePerRequest() {
        unavailable();
        queued();
        submit(pdf(), "one-line");
        assertEquals(2, server.requestCount());
        for (FakeServer.CapturedRequest request : server.requests()) {
            assertEquals(List.of("one-line"), request.headerValues("Idempotency-Key"));
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] all = Arrays.copyOf(parts[0], length);
        int at = parts[0].length;
        for (int i = 1; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, all, at, parts[i].length);
            at += parts[i].length;
        }
        return all;
    }

    /** A stream without mark/reset, like a socket or pipe stream. */
    private static final class NoMark extends InputStream {

        private final ByteArrayInputStream in;

        NoMark(byte[] data) {
            this.in = new ByteArrayInputStream(data);
        }

        @Override
        public int read() {
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return in.read(b, off, len);
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }

    /** Delegates to a stream and records reset() and close() calls. */
    private static final class TrackingStream extends InputStream {

        private final InputStream in;
        int resets;
        boolean closed;

        TrackingStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return in.read(b, off, len);
        }

        @Override
        public long skip(long n) throws IOException {
            return in.skip(n);
        }

        @Override
        public boolean markSupported() {
            return in.markSupported();
        }

        @Override
        public synchronized void mark(int readlimit) {
            in.mark(readlimit);
        }

        @Override
        public synchronized void reset() throws IOException {
            resets++;
            in.reset();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            in.close();
        }
    }
}
