package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golden wire test (D-03, D-20): the request built from the golden
 * {@code ascii-query} inputs is byte-identical with
 * {@code contract/golden/ascii-query.bin} while the JVM default charset is
 * TIS-620 — the run's argLine, never another charset name, which the JDK
 * would silently ignore. Runs only in the dedicated {@code charset-tis620}
 * surefire execution.
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
    void asciiQueryIsByteExactUnderNonUtf8DefaultCharset() throws Exception {
        JsonNode fixture = goldenCase("ascii-query");
        byte[] fileBytes = Base64.getDecoder().decode(fixture.path("fileBase64").asText());
        String filename = fixture.path("filename").asText();
        String query = fixture.path("query").asText();
        byte[] golden = Kit.goldenBin(fixture.path("bin").asText());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", WireCharsetTest::handle);
        server.start();
        try {
            DocQL client = DocQL.builder()
                    .apiKey(WIRE_TEST_KEY)
                    .apiUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .boundary(() -> GOLDEN_BOUNDARY)
                    .build();
            QueryResult result = client.queryDocument(
                    DocFile.bytes(fileBytes, filename),
                    Instruction.query(query));

            Captured request = captured.get();
            assertNotNull(request, "the fake server received no request");

            // The body is byte-exact with the golden fixture.
            assertArrayEquals(golden, request.body());

            Headers headers = request.headers();
            assertEquals(Kit.golden().path("contentType").asText(),
                    headers.getFirst("Content-type"));
            assertEquals(golden.length, Integer.parseInt(headers.getFirst("Content-length")),
                    "Content-Length must be the exact body length");
            assertNull(headers.getFirst("Transfer-encoding"),
                    "a known-length body is never chunked");
            assertEquals(WIRE_TEST_KEY, headers.getFirst("X-API-Key"));
            assertTrue(headers.getFirst("User-agent")
                            .matches("^docql-java/0\\.1\\.0 \\(java [^;()]+; [^/()]+/[^()]+\\)$"),
                    "User-Agent must be docql-java/<version> (java <version>; <os>/<arch>): "
                            + headers.getFirst("User-agent"));
            assertNull(headers.getFirst("X-Request-Id"),
                    "the SDK never sends X-Request-Id");
            for (String name : headers.keySet()) {
                assertTrue(!name.toLowerCase(Locale.ROOT).startsWith("x-docql-"),
                        "the SDK never sends an x-docql-* header, saw " + name);
            }
            assertEquals("wire-test-request-id", result.requestId());
            assertEquals(1, result.pageCount());
        } finally {
            server.stop(0);
        }
        System.out.println("GOLDEN_OK ascii-query bytes " + golden.length);
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

    private static JsonNode goldenCase(String id) {
        for (JsonNode c : Kit.golden().path("cases")) {
            if (id.equals(c.path("id").asText())) {
                return c;
            }
        }
        throw new UncheckedIOException(new IOException("golden case " + id + " not found"));
    }
}
