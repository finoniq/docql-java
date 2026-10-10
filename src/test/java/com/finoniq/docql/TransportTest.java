package com.finoniq.docql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transport side (JAVA-04, D-06, D-07, D-08, D-12): every transport
 * failure maps to the SDK's connection family with an SDK-composed message,
 * both early-413 paths are exercised against a raw server, nothing retries,
 * error reads are bounded, and the API key never reaches any caller-visible
 * surface. Unit tests never read the process environment.
 */
class TransportTest {

    private static final String KEY = "unit-test-key";
    /** A key a hostile server echoes back everywhere it can. */
    private static final String SECRET = "dql_SECRETSECRETSECRETSECRETSECRET_abcdef";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DocQL client(String apiUrl) {
        return DocQL.builder().apiKey(KEY).apiUrl(apiUrl).build();
    }

    private static void send(DocQL client) {
        client.queryDocument(DocFile.bytes(new byte[] {1, 2, 3}, "f.pdf"), Instruction.query("q"));
    }

    /** A DocQL error envelope like the stub's sendEnvelopeError output. */
    private static String envelope(String reason, String errorInfo, String requestId) {
        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("reason", reason);
        metadata.put("request_id", requestId);
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("error_info", errorInfo);
        doc.put("status_code", 500);
        doc.set("metadata", metadata);
        try {
            return MAPPER.writeValueAsString(doc);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A local port that is closed again: connecting to it is refused. */
    private static String refusedUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    @Test
    void ioFailuresMapped() throws Exception {
        // A closed localhost port: connection refused.
        DocQL refused = client(refusedUrl());
        DocQLConnectionException e = assertThrows(DocQLConnectionException.class, () -> send(refused));
        assertFalse(e instanceof DocQLTimeoutException, "a refused connect is not a timeout");
        assertEquals("connection to 127.0.0.1 failed: ConnectException", e.getMessage());
        assertNotNull(e.getCause(), "the cause must be kept");

        // A server that answers headers and then drops the connection: the
        // body never arrives, and the failure is still a connection failure.
        RstServer server = new RstServer();
        try {
            byte[] headers = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                    + "Content-Length: 500\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
            server.enqueue(RstServer.headersThenDrop(headers));
            DocQL c = client(server.url());
            DocQLConnectionException dropped = assertThrows(DocQLConnectionException.class, () -> send(c));
            assertFalse(dropped instanceof DocQLTimeoutException);
            assertNotNull(dropped.getCause());
        } finally {
            server.close();
        }
    }

    @Test
    void timeoutMapped() throws Exception {
        FakeServer server = new FakeServer().delay(Duration.ofSeconds(3));
        try {
            DocQL c = DocQL.builder().apiKey(KEY).apiUrl(server.url())
                    .timeout(Duration.ofSeconds(1)).build();
            long started = System.nanoTime();
            DocQLTimeoutException e = assertThrows(DocQLTimeoutException.class, () -> send(c));
            assertEquals("request timed out after 1 s", e.getMessage());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2_500,
                    "the deadline must fire before the server's 3 s hold ends");
        } finally {
            server.close();
        }
    }

    @Test
    void connectTimeoutNamed() {
        RuntimeException mapped = Transport.mapFailure(
                new HttpConnectTimeoutException("connect timed out"), 0, KEY, "127.0.0.1", Duration.ofSeconds(5));
        assertTrue(mapped instanceof DocQLTimeoutException);
        assertEquals("connect timed out after 10 s", mapped.getMessage());

        // An injected client keeps its own connect timeout: the SDK must not
        // claim a duration it does not control (Go review IN-01).
        RuntimeException injected = Transport.mapFailure(
                new HttpConnectTimeoutException("connect timed out"), 0, KEY, "127.0.0.1", Duration.ofSeconds(5),
                false);
        assertTrue(injected instanceof DocQLTimeoutException);
        assertEquals("connect timed out", injected.getMessage());
    }

    @Test
    void interruptRestoresFlag() throws Exception {
        FakeServer server = new FakeServer().delay(Duration.ofSeconds(5));
        try {
            DocQL c = DocQL.builder().apiKey(KEY).apiUrl(server.url())
                    .timeout(Duration.ofSeconds(30)).build();
            Thread caller = Thread.currentThread();
            Thread interrupter = new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                caller.interrupt();
            });
            interrupter.start();

            DocQLConnectionException e = assertThrows(DocQLConnectionException.class, () -> send(c));
            assertEquals("interrupted", e.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must be restored");
            Thread.interrupted(); // clear the flag so later tests start clean
            interrupter.join(2_000);

            // An interrupted call never retries, and the client stays usable.
            assertEquals(1, server.requestCount(), "the interrupted call must reach the server exactly once");
            server.delay(Duration.ZERO);
            send(c);
            assertEquals(2, server.requestCount(), "the next call on the same client must succeed");
        } finally {
            server.close();
        }
    }

    @Test
    void sizeHintBoundary() {
        IOException boom = new IOException("boom");
        RuntimeException atLimit = Transport.mapFailure(boom, 26_214_400L, KEY, "127.0.0.1", Duration.ofSeconds(5));
        assertFalse(atLimit.getMessage().contains("25 MiB"), atLimit.getMessage());
        RuntimeException over = Transport.mapFailure(boom, 26_214_401L, KEY, "127.0.0.1", Duration.ofSeconds(5));
        assertTrue(over.getMessage().contains("26214401 bytes"), over.getMessage());
        assertTrue(over.getMessage().contains("25 MiB"), over.getMessage());
    }

    @Test
    void sizeHintOnReset(@TempDir Path tempDir) throws Exception {
        Path big = tempDir.resolve("big.pdf");
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(big.toFile(), "rw")) {
            raf.setLength(27L * 1024 * 1024);
        }
        RstServer server = new RstServer();
        try {
            server.enqueue(RstServer.resetAfter(64 * 1024));
            DocQL c = DocQL.builder().apiKey(KEY).apiUrl(server.url())
                    .timeout(Duration.ofSeconds(120)).build();
            DocQLConnectionException e = assertThrows(DocQLConnectionException.class,
                    () -> c.queryDocument(DocFile.path(big), Instruction.query("q")));
            assertFalse(e instanceof DocQLTimeoutException);
            assertTrue(e.getMessage().contains("28311552 bytes"), e.getMessage());
            assertTrue(e.getMessage().contains("25 MiB"), e.getMessage());
        } finally {
            server.close();
        }
    }

    @Test
    void typed413Decodes() throws Exception {
        byte[] body = envelope("upload_too_large", "the upload is too large", "req-413")
                .getBytes(StandardCharsets.UTF_8);
        byte[] head = ("HTTP/1.1 413 Content Too Large\r\nContent-Type: application/json\r\n"
                + "x-request-id: req-413\r\nContent-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        RstServer server = new RstServer();
        try {
            server.enqueue(RstServer.respondThenFin(RstServer.concat(head, body), Duration.ofMillis(500)));
            DocQL c = DocQL.builder().apiKey(KEY).apiUrl(server.url())
                    .timeout(Duration.ofSeconds(60)).build();
            // assertThrows with DocQLException already proves the 413 was not
            // surfaced as a connection failure (the two types are siblings).
            DocQLException e = assertThrows(DocQLException.class, () -> send(c));
            assertEquals(413, e.status());
            assertEquals("upload_too_large", e.reason());
            assertEquals("req-413", e.requestId());
        } finally {
            server.close();
        }
    }

    @Test
    void noRetry() throws Exception {
        // 503 with Retry-After: 1 — one request, no retry.
        FakeServer unavailable = new FakeServer();
        try {
            unavailable.enqueue(503, Map.of("Retry-After", "1"),
                    envelope("upstream_unavailable", "down", "req-503"));
            DocQLException e = assertThrows(DocQLException.class, () -> send(client(unavailable.url())));
            assertEquals(503, e.status());
            assertEquals(Duration.ofSeconds(1), e.retryAfter());
            assertEquals(1, unavailable.requestCount(), "a 503 must reach the server exactly once");
        } finally {
            unavailable.close();
        }

        // 429 — one request, no retry.
        FakeServer rateLimited = new FakeServer();
        try {
            rateLimited.enqueue(429, envelope("service_overloaded", "busy", "req-429"));
            DocQLException e = assertThrows(DocQLException.class, () -> send(client(rateLimited.url())));
            assertEquals(429, e.status());
            assertEquals(1, rateLimited.requestCount(), "a 429 must reach the server exactly once");
        } finally {
            rateLimited.close();
        }

        // A refused connect fails exactly once: the immediate failure proves
        // no backoff-and-retry loop is hiding behind the call.
        DocQL refused = client(refusedUrl());
        assertThrows(DocQLConnectionException.class, () -> send(refused));

        // A reset — one accepted connection, no retry.
        RstServer server = new RstServer();
        try {
            server.enqueue(RstServer.resetAfter(1_024));
            assertThrows(DocQLConnectionException.class, () -> send(client(server.url())));
            assertEquals(1, server.connections(), "a reset must be reached exactly once");
        } finally {
            server.close();
        }
    }

    @Test
    void boundedErrorRead() throws Exception {
        // Subscriber level: the error read stops at exactly 65,536 bytes and
        // cancels the subscription.
        BoundedBodySubscriber subscriber = new BoundedBodySubscriber(DocQL.ERROR_BODY_READ_LIMIT);
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        subscriber.onSubscribe(new FlowSubscription(cancelled));
        byte[] chunk = new byte[16_384];
        Arrays.fill(chunk, (byte) 'x');
        int fed = 0;
        while (!subscriber.getBody().toCompletableFuture().isDone() && fed < 4 * DocQL.ERROR_BODY_READ_LIMIT) {
            subscriber.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(chunk)));
            fed += chunk.length;
        }
        byte[] collected = subscriber.getBody().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(DocQL.ERROR_BODY_READ_LIMIT, collected.length);
        assertTrue(cancelled.get(), "the error read must cancel at the 64 KiB limit");

        // Wire level: an 8 MiB 502 body — the client stops reading long
        // before the body ends, keeps at most 2048 characters, and the next
        // call on the same client succeeds.
        byte[] big = new byte[8 * 1024 * 1024];
        Arrays.fill(big, (byte) 'x');
        byte[] head = ("HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\n"
                + "x-request-id: req-502\r\nContent-Length: " + big.length + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        RstServer server = new RstServer();
        try {
            server.enqueue(RstServer.respondAndMeasure(head, big));
            DocQL c = DocQL.builder().apiKey(KEY).apiUrl(server.url())
                    .timeout(Duration.ofSeconds(60)).build();
            DocQLException e = assertThrows(DocQLException.class, () -> send(c));
            assertEquals(502, e.status());
            assertTrue(e.body().length() <= 2048, "body() kept " + e.body().length() + " characters");
            long written = RstServer.measuredBodyBytes();
            assertTrue(written >= 0 && written < big.length,
                    "the client must cancel early; the server wrote " + written + " of " + big.length);

            byte[] okBody = envelope("service_overloaded", "unused", "req-ok").getBytes(StandardCharsets.UTF_8);
            byte[] okHead = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + okBody.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
            server.enqueue(RstServer.respondThenFin(RstServer.concat(okHead, okBody), Duration.ofMillis(50)));
            send(c);
        } finally {
            server.close();
        }
    }

    /** A Flow.Subscription that only records cancellation. */
    private static final class FlowSubscription implements java.util.concurrent.Flow.Subscription {

        private final java.util.concurrent.atomic.AtomicBoolean cancelled;

        FlowSubscription(java.util.concurrent.atomic.AtomicBoolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public void request(long n) {
            // unbounded pull; the test feeds buffers directly
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }

    @Test
    void keyRedaction() throws Exception {
        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("reason", "invalid_api_key " + SECRET);
        metadata.put("request_id", "req-" + SECRET);
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("error_info", "key " + SECRET + " rejected");
        doc.put("echo", SECRET);
        doc.set("metadata", metadata);
        String body = MAPPER.writeValueAsString(doc);

        FakeServer server = new FakeServer();
        try {
            server.enqueue(401, Map.of("x-request-id", "hdr-" + SECRET), body);
            DocQL c = DocQL.builder().apiKey(SECRET).apiUrl(server.url()).build();
            DocQLException e = assertThrows(DocQLException.class, () -> send(c));
            String[] surfaces = {e.getMessage(), e.body(), e.serverMessage(), e.reason(), e.requestId(),
                    c.toString()};
            for (String surface : surfaces) {
                assertFalse(surface.contains(SECRET), "the key leaked: " + surface);
            }
            assertTrue(e.getMessage().contains("***"), e.getMessage());
            assertTrue(e.reason().contains("***"), e.reason());
            assertTrue(e.serverMessage().contains("***"), e.serverMessage());
        } finally {
            server.close();
        }
    }
}
