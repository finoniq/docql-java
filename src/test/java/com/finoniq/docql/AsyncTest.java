package com.finoniq.docql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The async surface (JAVA-05, D-05): {@code queryDocumentAsync} returns a
 * {@code CompletableFuture<QueryResult>} that completes with exactly the
 * synchronous call's results and exception classes — the same
 * {@code DocQLException}/{@code DocQLConnectionException}/{@code DocQLTimeoutException}
 * instances, surfacing as the cause of {@code ExecutionException} from
 * {@code get()} and of {@code CompletionException} from {@code join()}.
 * {@code null} arguments throw synchronously; every other pre-send failure
 * fails the future without sending anything. Unit tests never read the
 * process environment.
 */
class AsyncTest {

    private static final String KEY = "unit-test-key";

    private static DocQL client(String apiUrl) {
        return DocQL.builder().apiKey(KEY).apiUrl(apiUrl).build();
    }

    private static DocFile file(int seed) {
        return DocFile.bytes(new byte[] {1, 2, (byte) seed}, "f.pdf");
    }

    /** A local port that is closed again: connecting to it is refused. */
    private static String refusedUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    /** Waits until the fake has captured its first request (in-flight proof). */
    private static void awaitRequest(FakeServer server) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.requestCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void asyncSuccessSameAsSync() throws Exception {
        String body = "{\"data\":{\"echo\":\"payload\"},"
                + "\"metadata\":{\"request_id\":\"req-async\",\"page_count\":3}}";
        FakeServer server = new FakeServer();
        try {
            server.enqueue(200, body);
            server.enqueue(200, body);
            server.enqueue(200, body);
            server.enqueue(200, body);
            DocQL c = client(server.url());
            DocFile f = file(0);
            Instruction q = Instruction.query("same question");

            QueryResult sync = c.queryDocument(f, q);
            QueryResult async = c.queryDocumentAsync(f, q).get(5, TimeUnit.SECONDS);
            assertEquals(sync, async, "the async result must equal the sync result (record equality)");
            assertEquals("req-async", async.requestId());
            assertEquals(Integer.valueOf(3), async.pageCount());

            QueryResult syncOpts = c.queryDocument(f, q, QueryOptions.builder().build());
            QueryResult asyncOpts = c.queryDocumentAsync(f, q, QueryOptions.builder().build())
                    .get(5, TimeUnit.SECONDS);
            assertEquals(syncOpts, asyncOpts, "the options overload must behave identically");
        } finally {
            server.close();
        }
    }

    @Test
    void asyncErrorSameClassAsSync() throws Exception {
        String body = "{\"error_info\":\"bad key\",\"metadata\":{\"reason\":\"invalid_api_key\","
                + "\"request_id\":\"req-401\"}}";
        FakeServer server = new FakeServer();
        try {
            server.enqueue(401, body);
            server.enqueue(401, body);
            server.enqueue(401, body);
            DocQL c = client(server.url());

            DocQLException syncE = assertThrows(DocQLException.class,
                    () -> c.queryDocument(file(1), Instruction.query("q")));

            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> c.queryDocumentAsync(file(1), Instruction.query("q")).get(5, TimeUnit.SECONDS));
            assertTrue(ee.getCause() instanceof DocQLException,
                    "want DocQLException, got " + ee.getCause());
            DocQLException asyncE = (DocQLException) ee.getCause();
            assertEquals(syncE.getClass(), asyncE.getClass(), "the async cause must be the sync exception class");
            assertEquals(401, asyncE.status());
            assertEquals("invalid_api_key", asyncE.reason());
            assertEquals("req-401", asyncE.requestId());
            assertFalse(asyncE.retryable());

            CompletionException ce = assertThrows(CompletionException.class,
                    () -> c.queryDocumentAsync(file(1), Instruction.query("q")).join());
            assertTrue(ce.getCause() instanceof DocQLException,
                    "want DocQLException, got " + ce.getCause());
            assertEquals(401, ((DocQLException) ce.getCause()).status());
        } finally {
            server.close();
        }
    }

    @Test
    void asyncConnectionError() throws Exception {
        DocQL c = client(refusedUrl());
        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> c.queryDocumentAsync(file(2), Instruction.query("q")).get(10, TimeUnit.SECONDS));
        assertTrue(ee.getCause() instanceof DocQLConnectionException,
                "want DocQLConnectionException, got " + ee.getCause());
        assertFalse(ee.getCause() instanceof DocQLTimeoutException, "a refused connect is not a timeout");
        assertNotNull(ee.getCause().getMessage(), "the mapped message must not be null");
        assertNotNull(ee.getCause().getCause(), "the cause must be kept");
    }

    @Test
    void asyncTimeout() throws Exception {
        FakeServer server = new FakeServer().delay(Duration.ofSeconds(3));
        try {
            DocQL c = DocQL.builder().apiKey(KEY).apiUrl(server.url())
                    .timeout(Duration.ofSeconds(1)).build();
            long started = System.nanoTime();
            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> c.queryDocumentAsync(file(3), Instruction.query("q")).get(10, TimeUnit.SECONDS));
            assertTrue(ee.getCause() instanceof DocQLTimeoutException,
                    "want DocQLTimeoutException, got " + ee.getCause());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2_500,
                    "the deadline must fire before the server's 3 s hold ends");
        } finally {
            server.close();
        }
    }

    @Test
    void asyncNullThrowsSynchronously() throws Exception {
        DocQL c = client(refusedUrl());
        Instruction q = Instruction.query("q");
        // The exception comes from the call itself: no future is ever returned.
        assertThrows(NullPointerException.class, () -> c.queryDocumentAsync(null, q));
        assertThrows(NullPointerException.class, () -> c.queryDocumentAsync(file(4), null));
        assertThrows(NullPointerException.class, () -> c.queryDocumentAsync(file(4), q, null));
    }

    @Test
    void asyncValidationFailsFuture(@TempDir Path tempDir) throws Exception {
        FakeServer server = new FakeServer();
        try {
            DocQL c = client(server.url());

            CompletableFuture<QueryResult> noName = c.queryDocumentAsync(
                    DocFile.bytes(new byte[] {1}, ""), Instruction.query("q"));
            ExecutionException nameFailure = assertThrows(ExecutionException.class,
                    () -> noName.get(5, TimeUnit.SECONDS));
            assertTrue(nameFailure.getCause() instanceof IllegalArgumentException,
                    "want IllegalArgumentException, got " + nameFailure.getCause());

            CompletableFuture<QueryResult> missing = c.queryDocumentAsync(
                    DocFile.path(tempDir.resolve("nope.pdf")), Instruction.query("q"));
            ExecutionException missingFailure = assertThrows(ExecutionException.class,
                    () -> missing.get(5, TimeUnit.SECONDS));
            assertTrue(missingFailure.getCause() instanceof UncheckedIOException,
                    "want UncheckedIOException, got " + missingFailure.getCause());

            assertEquals(0, server.requestCount(), "a failed future must send no request");
        } finally {
            server.close();
        }
    }

    @Test
    void asyncCancel() throws Exception {
        FakeServer server = new FakeServer().delay(Duration.ofSeconds(3));
        try {
            DocQL c = DocQL.builder().apiKey(KEY).apiUrl(server.url())
                    .timeout(Duration.ofSeconds(30)).build();
            CompletableFuture<QueryResult> future = c.queryDocumentAsync(
                    file(6), Instruction.query("cancel-me"));
            awaitRequest(server);
            future.cancel(true);
            assertTrue(future.isCancelled(), "a cancelled future must stay cancelled");
            assertTrue(future.isCompletedExceptionally());

            // The same client serves the next call.
            server.delay(Duration.ZERO);
            QueryResult next = c.queryDocumentAsync(file(7), Instruction.query("after-cancel"))
                    .get(15, TimeUnit.SECONDS);
            assertNotNull(next);
        } finally {
            server.close();
        }
    }

    @Test
    void asyncSizeHint(@TempDir Path tempDir) throws Exception {
        Path big = tempDir.resolve("big.pdf");
        try (RandomAccessFile raf = new RandomAccessFile(big.toFile(), "rw")) {
            raf.setLength(27L * 1024 * 1024);
        }
        RstServer server = new RstServer();
        try {
            server.enqueue(RstServer.resetAfter(64 * 1024));
            DocQL c = DocQL.builder().apiKey(KEY).apiUrl(server.url())
                    .timeout(Duration.ofSeconds(120)).build();
            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> c.queryDocumentAsync(DocFile.path(big), Instruction.query("q"))
                            .get(30, TimeUnit.SECONDS));
            assertTrue(ee.getCause() instanceof DocQLConnectionException,
                    "want DocQLConnectionException, got " + ee.getCause());
            assertFalse(ee.getCause() instanceof DocQLTimeoutException);
            assertTrue(ee.getCause().getMessage().contains("28311552 bytes"), ee.getCause().getMessage());
            assertTrue(ee.getCause().getMessage().contains("25 MiB"), ee.getCause().getMessage());
        } finally {
            server.close();
        }
    }

    @Test
    void asyncBoundedErrorBody() throws Exception {
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
            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> c.queryDocumentAsync(file(8), Instruction.query("q")).get(30, TimeUnit.SECONDS));
            assertTrue(ee.getCause() instanceof DocQLException,
                    "want DocQLException, got " + ee.getCause());
            DocQLException e = (DocQLException) ee.getCause();
            assertEquals(502, e.status());
            assertTrue(e.body().length() <= 2048, "body() kept " + e.body().length() + " characters");
            long written = RstServer.measuredBodyBytes();
            assertTrue(written >= 0 && written < big.length,
                    "the async client must cancel the read early; the server wrote " + written + " of " + big.length);
        } finally {
            server.close();
        }
    }

    @Test
    void asyncConcurrent() throws Exception {
        FakeServer server = new FakeServer().echoData();
        try {
            DocQL c = client(server.url());
            List<CompletableFuture<QueryResult>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(c.queryDocumentAsync(file(100 + i), Instruction.query("async-q-" + i)));
            }
            for (int i = 0; i < 16; i++) {
                QueryResult result = futures.get(i).get(20, TimeUnit.SECONDS);
                assertEquals("async-q-" + i, result.data().path("query").asText(),
                        "future " + i + " must complete with its own echoed query");
            }
            assertEquals(16, server.requestCount());
        } finally {
            server.close();
        }
    }
}
