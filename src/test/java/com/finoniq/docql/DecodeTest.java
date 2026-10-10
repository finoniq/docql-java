package com.finoniq.docql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The response side (JAVA-04): every vendored decode case, the Retry-After
 * forms, the retryable set, the unusable 2xx bodies and the 64&nbsp;MiB
 * success cap, the data/metadata defaults, the integer-only page counts, the
 * request-id fallbacks, the two message forms, and the scrub-before-truncate
 * and sanitising rules (D-04, D-06, D-09, D-11, D-12, D-21). The decode cases
 * are read from the vendored kit, never copied into the test.
 */
class DecodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A key long enough that no reason literal collides with it when scrubbed. */
    private static final String TEST_KEY = "unit-test-key";

    /** The HttpHeaders of a header map; lookups are case-insensitive. */
    private static HttpHeaders httpHeaders(Map<String, String> headers) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        headers.forEach((name, value) -> map.put(name, List.of(value)));
        return HttpHeaders.of(map, (a, b) -> true);
    }

    private static DocQLException decode(int status, Map<String, String> headers, String body, String key) {
        return Decoder.decodeError(status, httpHeaders(headers), body.getBytes(StandardCharsets.UTF_8), key);
    }

    private static DocQLException decode(int status, Map<String, String> headers, byte[] body, String key) {
        return Decoder.decodeError(status, httpHeaders(headers), body, key);
    }

    private static QueryResult parse(String body) {
        return Decoder.parseResult(200, httpHeaders(Map.of()), body.getBytes(StandardCharsets.UTF_8), TEST_KEY);
    }

    /**
     * A DocQL error envelope like the stub's sendEnvelopeError output; the
     * fields are written by Jackson so control characters arrive escaped and
     * the wire body is valid JSON.
     */
    private static String envelope(String reason, String errorInfo, String requestId) {
        ObjectNode metadata = MAPPER.createObjectNode();
        if (reason != null) {
            metadata.put("reason", reason);
        }
        if (requestId != null) {
            metadata.put("request_id", requestId);
        }
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("status_code", 500);
        if (errorInfo != null) {
            doc.put("error_info", errorInfo);
        }
        doc.set("metadata", metadata);
        try {
            return MAPPER.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An RFC 1123 rendering of an instant, in UTC. */
    private static String rfc1123(Instant at) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(at.atZone(ZoneOffset.UTC));
    }

    private static Integer pageCountOf(String value) {
        return parse("{\"data\":{},\"metadata\":{\"page_count\":" + value + "}}").pageCount();
    }

    /** A {@code {"data":{"p":"xx…"},"metadata":{}}} JSON document of exactly total bytes. */
    private static byte[] jsonOfExactly(int total) {
        String head = "{\"data\":{\"p\":\"";
        String tail = "\"},\"metadata\":{}}";
        int fill = total - head.length() - tail.length();
        assertTrue(fill >= 0, "total too small to hold the envelope");
        return (head + "x".repeat(fill) + tail).getBytes(StandardCharsets.UTF_8);
    }

    /** A Flow.Subscription recording what the subscriber asked of it. */
    private static final class RecordingSubscription implements Flow.Subscription {

        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicInteger requests = new AtomicInteger();

        @Override
        public void request(long n) {
            requests.incrementAndGet();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }

    @Test
    void decodeCases() {
        JsonNode cases = Kit.sdkCases().path("decodeCases");
        assertTrue(cases.isArray() && !cases.isEmpty(), "the vendored kit has no decodeCases");
        for (JsonNode c : cases) {
            String id = c.path("id").asText();
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> header : c.path("headers").properties()) {
                headers.put(header.getKey(), header.getValue().asText());
            }
            byte[] body = c.path("bodyUtf8").asText("").getBytes(StandardCharsets.UTF_8);
            DocQLException e = decode(c.path("status").asInt(), headers, body, TEST_KEY);

            JsonNode expect = c.path("expect");
            assertEquals(expect.path("status").asInt(), e.status(), id + ": status");
            JsonNode wantReason = expect.path("reason");
            assertEquals(wantReason.isMissingNode() || wantReason.isNull() ? null : wantReason.asText(),
                    e.reason(), id + ": reason");
            JsonNode wantId = expect.path("requestId");
            assertEquals(wantId.isMissingNode() || wantId.isNull() ? null : wantId.asText(),
                    e.requestId(), id + ": requestId");
            System.out.println("DECODE_CASE_OK " + id);
        }
    }

    @Test
    void retryableOnlyFor429And503() {
        Map<Integer, Boolean> expected = Map.of(
                429, true,
                503, true,
                400, false,
                401, false,
                413, false,
                422, false,
                500, false,
                502, false,
                524, false,
                302, false);
        for (Map.Entry<Integer, Boolean> entry : expected.entrySet()) {
            DocQLException e = decode(entry.getKey(), Map.of(),
                    envelope("service_overloaded", "busy", "req-1"), TEST_KEY);
            assertEquals(entry.getValue(), e.retryable(), "status " + entry.getKey());
        }
    }

    @Test
    void retryAfterForms() {
        Instant now = Instant.parse("2026-10-08T12:00:00.800Z");
        Instant whole = Instant.parse("2026-10-08T12:00:01Z");

        // Delta-seconds, trimmed.
        assertEquals(Duration.ofSeconds(5), Decoder.parseRetryAfter("5", whole));
        assertEquals(Duration.ofSeconds(7), Decoder.parseRetryAfter(" 7 ", whole));

        // An HTTP-date 10.2 s after the injected now rounds up to 11 s.
        assertEquals(Duration.ofSeconds(11), Decoder.parseRetryAfter(rfc1123(now.plusMillis(10_200)), now));
        // A date in the past clamps at 0 s — "retry now", never a negative sleep.
        assertEquals(Duration.ZERO, Decoder.parseRetryAfter(rfc1123(now.minusSeconds(30)), now));

        // Garbage and overflow are absent (null, D-21).
        assertNull(Decoder.parseRetryAfter("abc", now));
        assertNull(Decoder.parseRetryAfter("99999999999999999999", now));
        assertNull(Decoder.parseRetryAfter(null, now));

        // Parsed on every non-2xx: 503, 302 and 404 alike.
        for (int status : new int[] {503, 302, 404}) {
            DocQLException e = decode(status, Map.of("Retry-After", "9"),
                    envelope("service_overloaded", "busy", "req-2"), TEST_KEY);
            assertEquals(Duration.ofSeconds(9), e.retryAfter(), "status " + status);
        }
    }

    @Test
    void unusableSuccessBodies() {
        String[] notUsable = {"", "not json", "[1]", "{}{}"};
        for (String body : notUsable) {
            DocQLException e = assertThrows(DocQLException.class,
                    () -> Decoder.parseResult(200, httpHeaders(Map.of()), body.getBytes(StandardCharsets.UTF_8), TEST_KEY),
                    "body " + body + " must not decode to a result");
            assertEquals(200, e.status(), "body " + body + ": the 2xx status is kept");
            assertNull(e.reason(), "body " + body + ": an unusable body has no reason");
            assertFalse(e.retryable(), "body " + body + ": an unusable 2xx is never retryable");
            assertEquals("unexpected non-JSON response", e.serverMessage(), "body " + body);
            assertTrue(e.getMessage().startsWith("200 (non-DocQL response): "), "body " + body);
        }
        String[] dataNotAnObject = {"\"x\"", "[1]", "3"};
        for (String data : dataNotAnObject) {
            DocQLException e = assertThrows(DocQLException.class,
                    () -> Decoder.parseResult(200, httpHeaders(Map.of()),
                            ("{\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8), TEST_KEY),
                    "data " + data + " must not decode to a result");
            assertEquals("unexpected response: data is not a JSON object", e.serverMessage(), "data " + data);
        }
    }

    @Test
    void successBodyCap() throws Exception {
        // Exactly the cap is accepted by the subscriber limit check: the
        // subscription completes normally, it is not cancelled.
        BoundedBodySubscriber subscriber = new BoundedBodySubscriber(DocQL.SUCCESS_BODY_LIMIT + 1);
        RecordingSubscription subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);
        byte[] exact = jsonOfExactly(DocQL.SUCCESS_BODY_LIMIT);
        subscriber.onNext(List.of(ByteBuffer.wrap(exact)));
        subscriber.onComplete();
        byte[] collected = subscriber.getBody().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(DocQL.SUCCESS_BODY_LIMIT, collected.length);
        assertFalse(subscription.cancelled.get(), "exactly the cap must be accepted, not cancelled");

        // A 2xx body one byte over the cap is unusable, naming the limit.
        FakeServer server = new FakeServer();
        try {
            String over = "{\"data\":{\"p\":\"" + "x".repeat(67_108_834) + "\"},\"metadata\":{}}";
            server.enqueue(200, over);
            DocQL client = DocQL.builder().apiKey("k").apiUrl(server.url()).build();
            DocQLException e = assertThrows(DocQLException.class, () -> client.queryDocument(
                    DocFile.bytes(new byte[] {1}, "f.pdf"), Instruction.query("q")));
            assertEquals(200, e.status());
            assertNull(e.reason());
            assertFalse(e.retryable());
            assertEquals("response body exceeds 67108864 bytes", e.serverMessage());
        } finally {
            server.close();
        }
    }

    @Test
    void dataAndMetadataDefaults() {
        // Absent or null data becomes {}.
        for (String body : new String[] {"{\"metadata\":{\"request_id\":\"r1\"}}", "{\"data\":null}"}) {
            QueryResult r = parse(body);
            assertTrue(r.data().isObject() && r.data().isEmpty(), "data of " + body + " must be {}");
        }
        // Absent metadata becomes {} and the request id stays "".
        QueryResult noMetadata = parse("{\"data\":{}}");
        assertTrue(noMetadata.metadata().isObject() && noMetadata.metadata().isEmpty());
        assertEquals("", noMetadata.requestId());
        // Unknown metadata fields are kept; an unknown top-level field is tolerated.
        QueryResult kept = parse(
                "{\"data\":{\"a\":1},\"unknown_top\":true,\"metadata\":{\"request_id\":\"r4\",\"future_field\":\"kept\",\"ratio\":1.5}}");
        assertEquals("kept", kept.metadata().path("future_field").asText());
        assertEquals("r4", kept.metadata().path("request_id").asText());
        assertEquals(1.5, kept.metadata().path("ratio").asDouble());
        assertEquals("r4", kept.requestId());
        assertEquals(1, kept.data().path("a").asInt());
    }

    @Test
    void pageCountRules() {
        assertEquals(1, pageCountOf("1"));
        assertEquals(-2, pageCountOf("-2"));
        for (String value : new String[] {"1.0", "1e2", "\"3\"", "2147483648", "null"}) {
            assertNull(pageCountOf(value), "page_count " + value + " must be null");
        }
    }

    @Test
    void requestIdFallback() {
        // Success: an empty metadata request_id falls back to the header.
        QueryResult fromHeader = Decoder.parseResult(200, httpHeaders(Map.of("x-request-id", "hdr-1")),
                "{\"data\":{},\"metadata\":{\"request_id\":\"\"}}".getBytes(StandardCharsets.UTF_8), TEST_KEY);
        assertEquals("hdr-1", fromHeader.requestId());
        // Success: neither present → "".
        assertEquals("", parse("{\"data\":{}}").requestId());
        // Error: neither present → null (D-21).
        assertNull(decode(500, Map.of(), envelope("service_overloaded", "busy", null), TEST_KEY).requestId());
        // Error: no metadata at all → the header.
        assertEquals("hdr-2",
                decode(500, Map.of("x-request-id", "hdr-2"), "{\"error_info\":\"boom\"}", TEST_KEY).requestId());
    }

    @Test
    void scrubBeforeTruncate() {
        String key = "dql_scrub_me_secret_value";
        String body = "x".repeat(2040) + key + "y".repeat(100);
        DocQLException e = decode(500, Map.of(), body, key);
        assertTrue(e.body().length() <= 2048, "body() kept " + e.body().length() + " characters");
        assertFalse(e.body().contains(key), "the key survived in body()");
        assertTrue(e.body().startsWith("x".repeat(2040) + "***"),
                "the key must be scrubbed before the cut, got: ..." + e.body().substring(2030));
    }

    @Test
    void invalidUtf8Replaced() {
        DocQLException e = decode(500, Map.of(), new byte[] {'a', (byte) 0xff, (byte) 0xfe, 'b'}, TEST_KEY);
        assertTrue(e.body().contains("\uFFFD"), "invalid UTF-8 must become U+FFFD: " + e.body());
    }

    @Test
    void errorStrings() {
        DocQLException full = decode(422, Map.of("Retry-After", "0"),
                envelope("missing_query_or_prompt", "need a query", "r1"), TEST_KEY);
        assertEquals("422 missing_query_or_prompt: need a query (request_id=r1, retry_after=0s)",
                full.getMessage());

        DocQLException noMessage = decode(401, Map.of(), envelope("invalid_api_key", null, "r1"), TEST_KEY);
        assertEquals("401 invalid_api_key (request_id=r1)", noMessage.getMessage());

        DocQLException nonDocql = decode(502, Map.of(), "<html>  big \r\n body </html>", TEST_KEY);
        assertEquals("502 (non-DocQL response): <html> big body </html>", nonDocql.getMessage());

        // The snippet collapses whitespace and is cut at 200 characters.
        DocQLException longBody = decode(502, Map.of(), "a".repeat(500), TEST_KEY);
        assertEquals("502 (non-DocQL response): " + "a".repeat(200), longBody.getMessage());

        DocQLException empty = decode(502, Map.of(), "", TEST_KEY);
        assertEquals("502 (non-DocQL response): (empty body)", empty.getMessage());

        // The request id rides the non-DocQL form too, when present.
        DocQLException withId = decode(502, Map.of("x-request-id", "x"), "", TEST_KEY);
        assertEquals("502 (non-DocQL response): (empty body) (request_id=x)", withId.getMessage());
    }

    @Test
    void sanitisedFields() {
        String key = "sekret-key";
        String body = envelope(key + "\r\nbad\treason", "info\r\nwith\ttabs and " + key, "r\r\n1");
        DocQLException e = decode(500, Map.of(), body, key);

        String message = e.getMessage();
        assertFalse(message.contains("\r"), "CR must not survive: " + message);
        assertFalse(message.contains("\n"), "LF must not survive: " + message);
        assertFalse(message.contains("\t"), "TAB must not survive: " + message);
        assertFalse(message.contains(key), "the key must not survive: " + message);
        assertTrue(message.contains("***"));
        assertEquals("500 *** bad reason: info with tabs and *** (request_id=r 1)", message);

        assertEquals("*** bad reason", e.reason());
        assertEquals("info with tabs and ***", e.serverMessage());
        assertEquals("r 1", e.requestId());
    }

    @Test
    void redirectDecodes() {
        DocQLException e = decode(302, Map.of("content-type", "text/html", "location", "https://elsewhere.example/"),
                "<html>moved</html>", "k");
        assertNotNull(e);
        assertEquals(302, e.status());
        assertNull(e.reason());
        assertFalse(e.retryable());
        assertTrue(e.getMessage().startsWith("302 (non-DocQL response): "), e.getMessage());
    }
}
