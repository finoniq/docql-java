package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The golden wire test (D-03, D-20): the request built from each golden
 * fixture input is byte-identical with its {@code contract/golden/*.bin} for
 * all three input kinds — {@code bytes}, {@code path} (a temp file plus the
 * filename override, so no non-ASCII name touches the file system under
 * TIS-620) and {@code stream} — while the JVM default charset is TIS-620, the
 * run's argLine, never another charset name, which the JDK would silently
 * ignore. Runs only in the dedicated {@code charset-tis620} surefire
 * execution.
 */
class WireCharsetTest {

    private static final String GOLDEN_BOUNDARY = "docqlgoldenboundary0000000000001";
    private static final String WIRE_TEST_KEY = "wire-test-key";

    private static final AtomicReference<Captured> captured = new AtomicReference<>();

    private record Captured(byte[] body, Headers headers) {
    }

    @BeforeAll
    static void requireNonUtf8DefaultCharset() {
        Charset defaultCharset = Charset.defaultCharset();
        assertNotEquals(StandardCharsets.UTF_8, defaultCharset,
                "WireCharsetTest must not run with a UTF-8 default charset: "
                        + "the TIS-620 argLine was ignored and this proof is vacuous");
        System.out.println("DEFAULT_CHARSET=" + defaultCharset.name());
    }

    @Test
    void goldenFixturesAreByteExactUnderNonUtf8DefaultCharset() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", WireCharsetTest::handle);
        server.start();
        try {
            DocQL client = DocQL.builder()
                    .apiKey(WIRE_TEST_KEY)
                    .apiUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .boundary(() -> GOLDEN_BOUNDARY)
                    .build();
            for (JsonNode fixture : Kit.golden().path("cases")) {
                String id = fixture.path("id").asText();
                byte[] fileBytes = Base64.getDecoder().decode(fixture.path("fileBase64").asText());
                byte[] golden = Kit.goldenBin(fixture.path("bin").asText());
                String filename = fixture.path("filename").asText();
                Instruction instruction = fixture.hasNonNull("prompt")
                        ? Instruction.prompt(fixture.path("prompt").asText())
                        : Instruction.query(fixture.path("query").asText());

                for (String kind : new String[]{"bytes", "path", "stream"}) {
                    QueryOptions.Builder options = QueryOptions.builder();
                    if (fixture.path("mode").asText(null) != null) {
                        options.mode(Mode.of(fixture.path("mode").asText()));
                    }
                    DocFile file;
                    Path temp = null;
                    if (kind.equals("bytes")) {
                        file = DocFile.bytes(fileBytes, filename);
                    } else if (kind.equals("path")) {
                        temp = Files.createTempFile("doc", ".bin");
                        Files.write(temp, fileBytes);
                        // The override keeps the fixture filename off the file
                        // system: TIS-620 cannot store non-ASCII names.
                        options.filename(filename);
                        file = DocFile.path(temp);
                    } else {
                        file = DocFile.stream(new ByteArrayInputStream(fileBytes), fileBytes.length, filename);
                    }

                    captured.set(null);
                    QueryResult result = client.queryDocument(file, instruction, options.build());

                    Captured request = captured.get();
                    assertNotNull(request, "the fake server received no request for " + id + "/" + kind);
                    assertArrayEquals(golden, request.body(), id + "/" + kind + " must be byte-exact");

                    Headers headers = request.headers();
                    assertEquals(Kit.golden().path("contentType").asText(),
                            headers.getFirst("Content-type"));
                    assertEquals(golden.length, Integer.parseInt(headers.getFirst("Content-length")),
                            "Content-Length must be the exact body length");
                    assertNull(headers.getFirst("Transfer-encoding"),
                            "a known-length body is never chunked");
                    assertEquals(WIRE_TEST_KEY, headers.getFirst("X-API-Key"));
                    assertEquals("wire-test-request-id", result.requestId());
                    assertEquals(1, result.pageCount());

                    if (temp != null) {
                        Files.delete(temp);
                    }
                    System.out.println("GOLDEN_OK " + id + " " + kind + " " + golden.length);
                }
            }
        } finally {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        captured.set(new Captured(body, exchange.getRequestHeaders()));
        byte[] response = ("{\"data\":{},\"metadata\":{\"request_id\":\"wire-test-request-id\","
                + "\"page_count\":1,\"pages_ocr\":0}}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }
}
