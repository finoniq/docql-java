package com.finoniq.docql;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test fake for the queryDocument HTTP surface: a JDK {@link HttpServer} on
 * {@code 127.0.0.1:0} with a canned-response queue. Every request is recorded
 * — method, path, all request headers under case-insensitive names, and the
 * raw body bytes — and counted. An optional hold delays every response; the
 * echo mode answers {@code data} with the request's own body-part JSON so a
 * result can be tied to its call. Not API; test scope only.
 */
final class FakeServer {

    /** One captured request. */
    static final class CapturedRequest {

        private final String method;
        private final String path;
        private final String rawPath;
        private final Map<String, String> headers;
        private final Map<String, List<String>> headerLines;
        private final byte[] body;

        CapturedRequest(String method, String path, String rawPath, Map<String, String> headers,
                Map<String, List<String>> headerLines, byte[] body) {
            this.method = method;
            this.path = path;
            this.rawPath = rawPath;
            this.headers = headers;
            this.headerLines = headerLines;
            this.body = body;
        }

        /** The request path exactly as it arrived on the request line, percent-encoding kept. */
        String rawPath() {
            return rawPath;
        }

        /** Every value of a request header under a case-insensitive name, one per line; empty when absent. */
        List<String> headerValues(String name) {
            List<String> values = headerLines.get(name.toLowerCase(Locale.ROOT));
            return values == null ? List.of() : values;
        }

        /** The request method, e.g. {@code POST}. */
        String method() {
            return method;
        }

        /** The request path, e.g. {@code /v1/query-document}. */
        String path() {
            return path;
        }

        /** The first value of a request header under a case-insensitive name, or null. */
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** Every request header name, lower-cased. */
        Iterable<String> headerNames() {
            return headers.keySet();
        }

        /** The raw request body bytes. */
        byte[] body() {
            return body;
        }

        /** The multipart boundary from the {@code Content-Type} header. */
        String boundary() {
            String contentType = header("Content-Type");
            if (contentType == null) {
                throw new IllegalStateException("no Content-Type header was captured");
            }
            int at = contentType.indexOf("boundary=");
            if (at < 0) {
                throw new IllegalStateException("no boundary in Content-Type: " + contentType);
            }
            return contentType.substring(at + "boundary=".length());
        }

        /**
         * The request's body part as text: everything after the body-part
         * header up to the closing boundary.
         */
        String bodyJsonPart() {
            String wire = new String(body, StandardCharsets.UTF_8);
            String marker = "Content-Disposition: form-data; name=\"body\"\r\n\r\n";
            int at = wire.indexOf(marker);
            if (at < 0) {
                throw new IllegalStateException("no body part in the captured request");
            }
            int end = wire.lastIndexOf("\r\n--" + boundary() + "--");
            return wire.substring(at + marker.length(), end);
        }
    }

    /** One canned response: status, extra headers, body bytes. */
    private static final class Canned {

        private final int status;
        private final Map<String, String> headers;
        private final byte[] body;

        Canned(int status, byte[] body) {
            this(status, Map.of(), body);
        }

        Canned(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        int status() {
            return status;
        }

        Map<String, String> headers() {
            return headers;
        }

        byte[] body() {
            return body;
        }
    }

    private final HttpServer server;
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Canned> canned = new LinkedBlockingQueue<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile Duration hold = Duration.ZERO;
    private volatile boolean echoData;
    private volatile boolean repeatLast;
    private volatile Canned last;

    FakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** The base URL of the fake, {@code http://127.0.0.1:<port>}. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Every captured request, in arrival order. */
    List<CapturedRequest> requests() {
        return requests;
    }

    /** How many requests arrived so far. */
    int requestCount() {
        return requests.size();
    }

    /** The request {@code path} of the n-th captured request. */
    String path(int index) {
        return requests.get(index).path();
    }

    /** Enqueues a canned response with a JSON body and no extra headers. */
    FakeServer enqueue(int status, String jsonBody) {
        canned.add(new Canned(status, jsonBody.getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    /** Enqueues a canned response with extra response headers and a JSON body. */
    FakeServer enqueue(int status, Map<String, String> headers, String jsonBody) {
        canned.add(new Canned(status, headers, jsonBody.getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    /** Delays every response by this hold, before the headers are sent. */
    FakeServer delay(Duration hold) {
        this.hold = hold;
        return this;
    }

    /** Once the queue is empty, answers every request with the last canned response again. */
    FakeServer repeatLast() {
        this.repeatLast = true;
        return this;
    }

    /** Answers every request with the request's own body part as {@code data}. */
    FakeServer echoData() {
        this.echoData = true;
        return this;
    }

    /** Stops the server. */
    void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawPath(), lowerCased(exchange.getRequestHeaders()),
                allLines(exchange.getRequestHeaders()), body));

        int status;
        byte[] payload;
        Canned response = canned.poll();
        if (response == null && repeatLast) {
            response = last;
        }
        if (response != null) {
            last = response;
        }
        if (echoData) {
            status = 200;
            payload = ("{\"data\":" + requests.get(requests.size() - 1).bodyJsonPart()
                    + ",\"metadata\":{\"request_id\":\"fake-" + sequence.incrementAndGet() + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
        } else if (response != null) {
            status = response.status();
            payload = response.body();
            for (Map.Entry<String, String> header : response.headers().entrySet()) {
                exchange.getResponseHeaders().set(header.getKey(), header.getValue());
            }
        } else {
            status = 200;
            payload = ("{\"data\":{},\"metadata\":{\"request_id\":\"fake-" + sequence.incrementAndGet() + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        sleepQuietly(hold.toMillis());
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
        exchange.close();
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted while holding the response", e));
        }
    }

    private static Map<String, List<String>> allLines(com.sun.net.httpserver.Headers headers) {
        Map<String, List<String>> lines = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            lines.put(entry.getKey().toLowerCase(Locale.ROOT),
                    entry.getValue() == null ? List.of() : List.copyOf(entry.getValue()));
        }
        return lines;
    }

    private static Map<String, String> lowerCased(com.sun.net.httpserver.Headers headers) {
        Map<String, String> lowered = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            lowered.put(entry.getKey().toLowerCase(Locale.ROOT),
                    entry.getValue() == null || entry.getValue().isEmpty() ? null : entry.getValue().get(0));
        }
        return lowered;
    }
}
