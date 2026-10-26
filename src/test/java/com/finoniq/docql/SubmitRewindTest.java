package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same-key submit retry must not reset a caller's mark/reset stream while
 * the previous attempt's HTTP client may still read it (the Java twin of
 * docql-go's 26-13 fix). A real JDK HttpServer and the SDK-built HttpClient
 * over loopback: the server answers the first submit 503 with
 * {@code Retry-After: 1} while the client is inside the caller's
 * {@code read}, before it has read the upload; the caller's stream stalls in
 * that read, the way a network-backed stream can.
 *
 * <p>This is a guard, not a reproduction: the JDK HttpClient composes the
 * response future only after the request body has been sent or has failed
 * ({@code jdk.internal.net.http.Exchange#startSendingBody}, HTTP/1.1 and
 * HTTP/2 alike), so {@code send} does not return the early 503 until the
 * stalled read has ended, and the retry's {@code reset} always comes after
 * it. Go's net/http returns the early answer while its write loop is still
 * reading, which is why docql-go waits for the previous body's Close.
 */
class SubmitRewindTest {

    private static final String BOUNDARY = "submitrewindtestboundary0000001";
    private static final byte[] DATA = data();

    private HttpServer server;
    private final AtomicInteger submits = new AtomicInteger();
    private final List<byte[]> retryFiles = new CopyOnWriteArrayList<>();
    private StallStream stream;

    private static byte[] data() {
        byte[] data = new byte[1 << 20];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ('a' + i % 26);
        }
        return data;
    }

    /**
     * A caller's mark/reset stream whose first read in the first attempt
     * stalls for up to {@code stallMillis}. It records a {@code reset} that
     * arrives while a read is still in flight: the SDK rewinding the stream
     * under the previous attempt's HTTP client. Such a reset also ends the
     * stall, so the stalled read then reads from the reset position, as a
     * real stream would. {@code stalling} opens when the stall begins, so the
     * server can hold its early answer until the client is inside the read.
     */
    static final class StallStream extends InputStream {

        private final ByteArrayInputStream in;
        private final long stallMillis;
        private final Object lock = new Object();
        private int resets;
        private boolean stalled;
        final AtomicInteger inRead = new AtomicInteger();
        final AtomicBoolean overlap = new AtomicBoolean();
        final CountDownLatch stalling = new CountDownLatch(1);
        private final CountDownLatch resetInFlight = new CountDownLatch(1);

        StallStream(byte[] data, long stallMillis) {
            this.in = new ByteArrayInputStream(data);
            this.stallMillis = stallMillis;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            inRead.incrementAndGet();
            try {
                boolean stall;
                synchronized (lock) {
                    stall = !stalled && resets == 0;
                    stalled |= stall;
                }
                if (stall) {
                    stalling.countDown();
                    try {
                        resetInFlight.await(stallMillis, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("stall interrupted");
                    }
                }
                synchronized (lock) {
                    return in.read(buffer, offset, length);
                }
            } finally {
                inRead.decrementAndGet();
            }
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public void mark(int readLimit) {
            synchronized (lock) {
                in.mark(readLimit);
            }
        }

        @Override
        public void reset() {
            if (inRead.get() > 0) {
                overlap.set(true);
                resetInFlight.countDown();
            }
            synchronized (lock) {
                resets++;
                in.reset();
            }
        }
    }

    @BeforeEach
    void start() throws IOException {
        stream = new StallStream(DATA, 3000);
        JsonNode queued = null;
        for (JsonNode c : Kit.jobCases().path("decodeCases")) {
            if ("job-202-queued".equals(c.path("id").asText())) {
                queued = c;
            }
        }
        JsonNode answer = queued;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Only the submit route counts: something on the host can probe new
        // loopback listeners with GET / (seen on WSL2); HttpServer answers
        // those 404 outside this context.
        server.createContext(Jobs.JOBS_PATH, exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if (submits.incrementAndGet() == 1) {
                earlyAnswer(exchange);
            } else {
                retryAnswer(exchange, answer);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** 503 upstream_unavailable with Retry-After 1, before reading the upload. */
    private void earlyAnswer(HttpExchange exchange) throws IOException {
        try {
            if (!stream.stalling.await(5, TimeUnit.SECONDS)) {
                throw new IOException("the first attempt never read the caller's stream");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        byte[] body = ("{\"error_info\":\"e\",\"status_code\":503,\"metadata\":{\"reason\":\""
                + Reasons.UPSTREAM_UNAVAILABLE + "\",\"request_id\":\"r\"}}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Retry-After", "1");
        exchange.sendResponseHeaders(503, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** The kit's 202 queued job, after reading the whole upload. */
    private void retryAnswer(HttpExchange exchange, JsonNode answer) throws IOException {
        byte[] request;
        try (InputStream in = exchange.getRequestBody()) {
            request = in.readAllBytes();
        }
        retryFiles.add(filePart(request));
        for (Map.Entry<String, JsonNode> header : answer.path("headers").properties()) {
            exchange.getResponseHeaders().add(header.getKey(), header.getValue().asText());
        }
        byte[] body = answer.path("bodyUtf8").asText().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(answer.path("status").asInt(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** The raw bytes of the file part, between its header and the next boundary. */
    private static byte[] filePart(byte[] request) {
        String text = new String(request, StandardCharsets.ISO_8859_1);
        int start = text.indexOf("\r\n\r\n");
        int end = text.indexOf("\r\n--" + BOUNDARY, start);
        if (start < 0 || end < 0) {
            return new byte[0];
        }
        return Arrays.copyOfRange(request, start + 4, end);
    }

    private DocQL client() {
        return DocQL.builder().apiKey("rewind-test-key").apiUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .env(name -> null).boundary(() -> BOUNDARY).build();
    }

    @Test
    void theRetryWaitsForThePreviousAttemptBeforeResettingTheStream() {
        long start = System.nanoTime();
        Job job = null;
        RuntimeException failure = null;
        try {
            job = client().submitDocument(DocFile.stream(stream, DATA.length, "big.pdf"), Instruction.query("q"),
                    QueryOptions.builder().idempotencyKey("rewind-key").build());
        } catch (RuntimeException e) {
            failure = e;
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertFalse(stream.overlap.get(), "the retry reset the stream while the previous attempt's HTTP client"
                + " was still reading it (after " + elapsedMs + " ms)");
        if (failure != null) {
            throw failure;
        }
        assertEquals("rewind-key", job.idempotencyKey());
        assertEquals(2, submits.get(), "503, then the same-key retry");
        assertEquals(1, retryFiles.size());
        assertArrayEquals(DATA, retryFiles.get(0), "the retry's file part is the caller's bytes from the mark");
    }

    @Test
    void anInterruptWhileTheFirstAttemptStillReadsTheStreamStopsWithTheKey() throws Exception {
        Thread caller = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(2000);
                caller.interrupt();
            } catch (InterruptedException ignored) {
                // the test ended first
            }
        });
        interrupter.start();
        long start = System.nanoTime();
        try {
            DocQLConnectionException e = org.junit.jupiter.api.Assertions.assertThrows(
                    DocQLConnectionException.class,
                    () -> client().submitDocument(DocFile.stream(stream, DATA.length, "big.pdf"),
                            Instruction.query("q"), QueryOptions.builder().idempotencyKey("rewind-key").build()));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertFalse(stream.overlap.get(), "the retry reset the stream while the previous attempt's HTTP"
                    + " client was still reading it (after " + elapsedMs + " ms)");
            assertEquals("rewind-key", e.idempotencyKey());
            assertTrue(Thread.interrupted(), "the interrupt flag is restored");
            assertEquals(1, submits.get(), "no retry once interrupted");
            assertTrue(elapsedMs < 2900, "returned after " + elapsedMs + " ms, want about 2 s (the interrupt)");
        } finally {
            interrupter.interrupt();
            interrupter.join();
            Thread.interrupted();
        }
    }

    /**
     * The early 503 must leave the connection the way DocQL's servers do:
     * the gateway reads and drops the rest of an upload it has already
     * answered ({@code dropUnreadUpload}, {@code lingeringClose}) and Go's
     * server lingers; neither resets a connection under an upload. A JDK
     * HttpServer handler that never reads the body gets 64 KiB drained and
     * the connection closed on the rest, and that reset can cost the SDK the
     * 503 it already received: the JDK 17/21 CI failure of the test above,
     * whose HttpServer sends the 503 head and body in separate segments. A
     * raw client reads the 503, sends the rest of the upload, then the
     * same-key retry on the same connection, and must get the 202.
     */
    @Test
    void theEarlyAnswerReadsTheRestOfTheUploadSoItsConnectionCarriesTheRetry() throws IOException {
        stream.stalling.countDown(); // earlyAnswer's precondition: a client inside its stalled read
        byte[] head = ("POST " + Jobs.JOBS_PATH + " HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: " + DATA.length
                + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(head);
            out.flush();
            assertEquals(503, answerStatus(in), "the early answer, before any of the upload");
            out.write(DATA);
            out.write(head);
            out.write(DATA);
            out.flush();
            assertEquals(202, answerStatus(in), "the same-key retry on the connection that carried the upload");
        }
        assertEquals(2, submits.get());
    }

    /** Reads one answer off a raw HTTP/1.1 connection, its body by Content-Length, and returns its status. */
    private static int answerStatus(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("the connection ended before an answer");
            }
            head.write(b);
            matched = b == "\r\n\r\n".charAt(matched) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        String[] lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        int length = 0;
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                length = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        in.readNBytes(length);
        return Integer.parseInt(lines[0].split(" ")[1]);
    }
}
