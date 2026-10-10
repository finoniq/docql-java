package com.finoniq.docql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file-input surface of queryDocument (JAVA-03): path, bytes and
 * known-length stream inputs with the strict filename rule, empty inputs,
 * short streams, the upload-counts-against-deadline behaviour, and every
 * client-side check firing before any byte is sent (D-02, D-03, D-07, D-21).
 */
class InputTest {

    private static final String KEY = "unit-test-key";

    private FakeServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private static DocQL client(FakeServer server) {
        return DocQL.builder().env(name -> null).apiKey(KEY).apiUrl(server.url()).build();
    }

    /** The raw file-part bytes of a captured request: between the part headers and the epilogue. */
    private static byte[] filePart(FakeServer.CapturedRequest request) {
        String wire = new String(request.body(), StandardCharsets.UTF_8);
        int start = wire.indexOf("\r\n\r\n") + 4;
        int end = wire.indexOf("\r\n--" + request.boundary(), start);
        return Arrays.copyOfRange(request.body(), start, end);
    }

    private static byte[] pattern(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) i;
        }
        return data;
    }

    /** A caller stream that counts close() calls (close is a no-op on ByteArrayInputStream). */
    static final class CloseCountingStream extends ByteArrayInputStream {

        int closeCount;

        CloseCountingStream(byte[] data) {
            super(data);
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    /** ~1 MiB/s: at most chunkBytes per read, one hold per chunk. */
    static final class ThrottledInputStream extends FilterInputStream {

        private final int chunkBytes;
        private final long millisPerChunk;

        ThrottledInputStream(InputStream in, int chunkBytes, long millisPerChunk) {
            super(in);
            this.chunkBytes = chunkBytes;
            this.millisPerChunk = millisPerChunk;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                Thread.sleep(millisPerChunk);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted during throttled read", e);
            }
            return super.read(buffer, offset, Math.min(length, chunkBytes));
        }
    }

    @Test
    void pathBasename() throws IOException {
        Path dir = Files.createTempDirectory("p13-02");
        Path document = dir.resolve("report.pdf");
        byte[] data = pattern(128);
        Files.write(document, data);

        DocQL client = client(server);
        client.queryDocument(DocFile.path(document), Instruction.query("q"));

        FakeServer.CapturedRequest request = server.requests().get(0);
        assertTrue(new String(request.body(), StandardCharsets.UTF_8).contains("filename=\"report.pdf\""),
                "a path input must name the file part after the basename");
        String boundary = request.boundary();
        int framing = Multipart.preamble(boundary, "report.pdf").length
                + Multipart.epilogue(boundary, Multipart.bodyJson(Instruction.query("q"), null)).length;
        assertEquals(framing + data.length,
                Integer.parseInt(request.header("Content-Length")),
                "Content-Length must be framing + file size");
        assertArrayEquals(data, filePart(request));
    }

    @Test
    void pathMissingFails() {
        Path missing = Path.of(System.getProperty("java.io.tmpdir"), "p13-02-not-there.pdf");

        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> client(server).queryDocument(DocFile.path(missing), Instruction.query("q")));
        assertTrue(e.getCause() instanceof java.nio.file.NoSuchFileException,
                "a missing path fails as NoSuchFileException: " + e.getCause());
        assertEquals(0, server.requestCount(), "no byte may be sent");
    }

    @Test
    void pathDirectoryFails() throws IOException {
        Path directory = Files.createTempDirectory("p13-02-dir");

        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> client(server).queryDocument(DocFile.path(directory), Instruction.query("q")));
        assertTrue(e.getCause() instanceof IOException
                        && e.getCause().getMessage().startsWith("not a regular file: "),
                "a directory fails as not-a-regular-file: " + e.getCause());
        assertEquals(0, server.requestCount(), "ofFile alone would upload the inode size");
    }

    @Test
    void bytesDefensiveCopy() {
        byte[] data = pattern(16);

        DocQL client = client(server);
        client.queryDocument(DocFile.bytes(data, "f.pdf"), Instruction.query("q"));
        data[0] = (byte) 0x7F;

        assertArrayEquals(pattern(16), filePart(server.requests().get(0)),
                "mutating the array after DocFile.bytes must not change the sent bytes");
    }

    @Test
    void bytesZeroLength() {
        client(server).queryDocument(DocFile.bytes(new byte[0], "empty.pdf"), Instruction.query("q"));

        FakeServer.CapturedRequest request = server.requests().get(0);
        String boundary = request.boundary();
        int framing = Multipart.preamble(boundary, "empty.pdf").length
                + Multipart.epilogue(boundary, Multipart.bodyJson(Instruction.query("q"), null)).length;
        assertEquals(framing, Integer.parseInt(request.header("Content-Length")),
                "an empty file is a valid request whose Content-Length is preamble + epilogue");
        assertEquals(0, filePart(request).length);
    }

    @Test
    void streamZeroLength() {
        CloseCountingStream in = new CloseCountingStream(new byte[0]);

        client(server).queryDocument(DocFile.stream(in, 0, "empty.pdf"), Instruction.query("q"));

        FakeServer.CapturedRequest request = server.requests().get(0);
        String boundary = request.boundary();
        int framing = Multipart.preamble(boundary, "empty.pdf").length
                + Multipart.epilogue(boundary, Multipart.bodyJson(Instruction.query("q"), null)).length;
        assertEquals(framing, Integer.parseInt(request.header("Content-Length")),
                "a zero-length stream sends noBody with Content-Length = preamble + epilogue");
        assertEquals(0, filePart(request).length);
        assertEquals(0, in.closeCount, "an unread zero-length stream is never closed");
    }

    @Test
    void streamExactLengthNotClosed() {
        byte[] data = pattern(100);
        CloseCountingStream in = new CloseCountingStream(data);

        client(server).queryDocument(DocFile.stream(in, 60, "part.pdf"), Instruction.query("q"));

        FakeServer.CapturedRequest request = server.requests().get(0);
        byte[] part = filePart(request);
        assertEquals(60, part.length, "exactly the declared length is sent");
        assertArrayEquals(Arrays.copyOfRange(data, 0, 60), part);
        assertEquals(0, in.closeCount, "the SDK must never close the caller's stream");
        assertEquals(data[60], in.read(), "the caller reads on from byte 61");
    }

    @Test
    void streamReadsFromCurrentPosition() {
        byte[] data = pattern(100);
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        in.read(new byte[10], 0, 10); // advance the stream by 10 bytes first

        client(server).queryDocument(DocFile.stream(in, data.length - 10, "doc.pdf"), Instruction.query("q"));

        byte[] part = filePart(server.requests().get(0));
        assertEquals(90, part.length, "bytes 10..10+len are sent");
        assertArrayEquals(Arrays.copyOfRange(data, 10, 100), part);
    }

    @Test
    void streamShortFails() {
        byte[] data = pattern(40);

        DocQLConnectionException e = assertThrows(DocQLConnectionException.class,
                () -> client(server).queryDocument(
                        DocFile.stream(new ByteArrayInputStream(data), 100, "short.pdf"),
                        Instruction.query("q")));

        assertEquals("the stream ended after 40 of 100 declared bytes", e.getMessage(),
                "a short stream never undersends silently");
        assertEquals(0, server.requestCount(), "the request fails before a response");
    }

    @Test
    void negativeLengthRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DocFile.stream(new ByteArrayInputStream(new byte[0]), -1, "f.pdf"));
        assertTrue(e.getMessage().contains("length"), e.getMessage());
    }

    @Test
    void filenameOverrideVerbatim() throws IOException {
        Path dir = Files.createTempDirectory("p13-02");
        Path document = dir.resolve("report.pdf");
        Files.write(document, pattern(16));

        client(server).queryDocument(DocFile.path(document), Instruction.query("q"),
                QueryOptions.builder().filename("x\"y.pdf").build());

        assertTrue(new String(server.requests().get(0).body(), StandardCharsets.UTF_8)
                        .contains("filename=\"x%22y.pdf\""),
                "the override replaces the path basename verbatim");
    }

    @Test
    void missingFilenameBeforeIo() {
        DocQL client = client(server);

        IllegalArgumentException bytesNoName = assertThrows(IllegalArgumentException.class,
                () -> client.queryDocument(DocFile.bytes(pattern(8), ""), Instruction.query("q")));
        assertTrue(bytesNoName.getMessage().startsWith("missing filename"), bytesNoName.getMessage());

        IllegalArgumentException rootPath = assertThrows(IllegalArgumentException.class,
                () -> client.queryDocument(DocFile.path(Path.of("/")), Instruction.query("q")));
        assertTrue(rootPath.getMessage().startsWith("missing filename"), rootPath.getMessage());

        assertEquals(0, server.requestCount(), "the filename rule fires before any I/O");
    }

    @Test
    void emptyQuerySent() {
        client(server).queryDocument(DocFile.bytes(pattern(8), "f.pdf"), Instruction.query(""));

        FakeServer.CapturedRequest request = server.requests().get(0);
        assertEquals("{\"query\":\"\"}", request.bodyJsonPart(),
                "Instruction.query(\"\") is sent to the server unchanged");
    }

    @Test
    void nullArgumentsRejected() {
        DocQL client = client(server);
        DocFile file = DocFile.bytes(new byte[]{1}, "f.pdf");
        Instruction instruction = Instruction.query("q");
        QueryOptions options = QueryOptions.builder().build();

        assertThrows(NullPointerException.class, () -> client.queryDocument(null, instruction));
        assertThrows(NullPointerException.class, () -> client.queryDocument(file, null));
        assertThrows(NullPointerException.class, () -> client.queryDocument(file, instruction, null));
        assertThrows(NullPointerException.class, () -> Instruction.query(null));
        assertThrows(NullPointerException.class, () -> Mode.of(null));
        assertEquals(0, server.requestCount());
    }

    @Test
    void uploadCountsAgainstDeadline() {
        byte[] payload = pattern(10 * 1024 * 1024);
        InputStream throttled = new ThrottledInputStream(new ByteArrayInputStream(payload), 8192, 7);

        DocQL client = DocQL.builder().env(name -> null).apiKey(KEY).apiUrl(server.url())
                .timeout(Duration.ofSeconds(1))
                .build();

        long start = System.nanoTime();
        assertThrows(DocQLTimeoutException.class,
                () -> client.queryDocument(DocFile.stream(throttled, payload.length, "big.pdf"),
                        Instruction.query("q")));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMillis < 3_000,
                "the deadline must include the upload and fire before 3 s, took " + elapsedMillis + " ms");
    }
}
