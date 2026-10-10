package com.finoniq.docql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The builder's configuration surface and client policy (JAVA-02): env
 * fallbacks, validation, the injected-client redirect rule, the per-request
 * deadline surviving an injected client, the exact three request headers,
 * redirects never followed, the masked {@code toString}, and one client
 * serving concurrent calls (D-01, D-07, D-09, D-10, D-12). The process
 * environment is never read: every builder injects a map lookup.
 */
class ClientTest {

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

    /** A builder whose environment is the given map — never the real process env. */
    private static DocQL.Builder builder(Map<String, String> env) {
        return DocQL.builder().env(env::get);
    }

    private static void sendOne(DocQL client) {
        client.queryDocument(DocFile.bytes(new byte[]{1, 2, 3}, "f.pdf"), Instruction.query("q"));
    }

    @Test
    void envFallback() {
        Map<String, String> env = Map.of(
                "DOCQL_API_KEY", "env-key",
                "DOCQL_API_URL", server.url() + "/");
        DocQL client = builder(env).build();

        sendOne(client);

        FakeServer.CapturedRequest request = server.requests().get(0);
        assertEquals("env-key", request.header("X-API-Key"), "the env key must travel on the request");
        assertEquals("/v1/query-document", request.path());
    }

    @Test
    void explicitBeatsEnv() {
        Map<String, String> env = Map.of(
                "DOCQL_API_KEY", "env-key",
                "DOCQL_API_URL", "http://env.example.invalid");
        DocQL client = builder(env).apiKey("explicit-key").apiUrl(server.url() + "/").build();

        sendOne(client);

        assertEquals("explicit-key", server.requests().get(0).header("X-API-Key"));
        assertEquals(1, server.requestCount(), "the explicit URL wins: the request reached our fake");
    }

    @Test
    void blankEnvIsUnset() {
        Map<String, String> blankKey = Map.of("DOCQL_API_KEY", "   ", "DOCQL_API_URL", server.url());
        IllegalArgumentException noKey = assertThrows(IllegalArgumentException.class,
                () -> builder(blankKey).build());
        assertTrue(noKey.getMessage().contains("DOCQL_API_KEY"), noKey.getMessage());

        Map<String, String> blankUrl = Map.of("DOCQL_API_KEY", KEY, "DOCQL_API_URL", "   ");
        DocQL client = builder(blankUrl).build();
        assertTrue(client.toString().contains(DocQL.DEFAULT_API_URL),
                "a whitespace-only DOCQL_API_URL counts as unset: " + client);
    }

    @Test
    void missingKey() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> builder(Map.of()).apiUrl(server.url()).build());
        assertTrue(e.getMessage().contains("DOCQL_API_KEY"), e.getMessage());
        assertEquals(0, server.requestCount());
    }

    @Test
    void explicitBlankRejected() {
        Map<String, String> envKey = Map.of("DOCQL_API_KEY", "env-key");
        assertThrows(IllegalArgumentException.class, () -> builder(envKey).apiKey("   ").build(),
                "an explicit blank key is rejected without an env fallback");

        Map<String, String> env = Map.of("DOCQL_API_KEY", KEY);
        IllegalArgumentException blankUrl = assertThrows(IllegalArgumentException.class,
                () -> builder(env).apiUrl("").build());
        assertTrue(blankUrl.getMessage().contains("DOCQL_API_URL"), blankUrl.getMessage());
    }

    @Test
    void keyTrimmedAndControlCharsRejected() {
        DocQL client = builder(Map.of()).apiKey(" k\n").apiUrl(server.url()).build();
        sendOne(client);
        assertEquals("k", server.requests().get(0).header("X-API-Key"), "the key is sent trimmed");

        String control = "a\u0001b";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> builder(Map.of()).apiKey(control).apiUrl(server.url()).build());
        assertFalse(e.getMessage().contains(control), "the key value is never echoed: " + e.getMessage());
    }

    @Test
    void invalidUrlRejected() {
        List<String> bad = List.of(
                "ftp://h",
                "api-docql.finoniq.com",
                "https://",
                "https://user:pw@h",
                "https://h/?q=1",
                "https://h/#f");
        for (String url : bad) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> builder(Map.of()).apiKey(KEY).apiUrl(url).build(),
                    () -> "apiUrl " + url + " must be rejected");
            assertTrue(e.getMessage().contains("DOCQL_API_URL"), url + ": " + e.getMessage());
            assertFalse(e.getMessage().contains("pw"), "userinfo is never echoed: " + e.getMessage());
        }
    }

    @Test
    void urlNormalised() {
        DocQL client = builder(Map.of()).apiKey(KEY).apiUrl(server.url() + "///").build();
        sendOne(client);
        assertEquals("/v1/query-document", server.requests().get(0).path(),
                "trailing slashes are stripped");
    }

    @Test
    void defaultUrl() {
        DocQL client = builder(Map.of()).apiKey(KEY).build();
        assertTrue(client.toString().contains("https://api-docql.finoniq.com"), client.toString());
    }

    @Test
    void invalidTimeout() {
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> builder(Map.of()).apiKey(KEY).apiUrl(server.url()).timeout(timeout).build());
            assertEquals("timeout must be greater than zero", e.getMessage());
        }
    }

    @Test
    void nullSettersRejected() {
        DocQL.Builder builder = builder(Map.of());
        assertThrows(NullPointerException.class, () -> builder.apiKey(null));
        assertThrows(NullPointerException.class, () -> builder.apiUrl(null));
        assertThrows(NullPointerException.class, () -> builder.timeout(null));
        assertThrows(NullPointerException.class, () -> builder.httpClient(null));
    }

    @Test
    void injectedClientMustNotFollowRedirects() throws IOException {
        for (HttpClient.Redirect policy : List.of(HttpClient.Redirect.NORMAL, HttpClient.Redirect.ALWAYS)) {
            HttpClient redirecting = HttpClient.newBuilder().followRedirects(policy).build();
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> builder(Map.of()).apiKey(KEY).apiUrl(server.url()).httpClient(redirecting).build());
            assertTrue(e.getMessage().contains("Redirect.NEVER"), e.getMessage());
        }
        // The JDK default keeps reporting Redirect.NEVER (measured) and is accepted.
        DocQL client = builder(Map.of()).apiKey(KEY).apiUrl(server.url())
                .httpClient(java.net.http.HttpClient.newHttpClient()).build();
        assertNotNull(client);
    }

    @Test
    void injectedClientUsed() {
        AtomicInteger submitted = new AtomicInteger();
        HttpClient injected = HttpClient.newBuilder()
                .executor(task -> {
                    submitted.incrementAndGet();
                    task.run();
                })
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        DocQL client = builder(Map.of()).apiKey(KEY).apiUrl(server.url()).httpClient(injected).build();

        sendOne(client);

        assertTrue(submitted.get() > 0, "the injected client must carry the request");
        assertEquals(1, server.requestCount());
    }

    @Test
    void requestTimeoutWithInjectedClient() throws IOException {
        server.delay(Duration.ofSeconds(3));
        HttpClient injected = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        DocQL client = builder(Map.of()).apiKey(KEY).apiUrl(server.url())
                .timeout(Duration.ofSeconds(1))
                .httpClient(injected)
                .build();

        long start = System.nanoTime();
        assertThrows(DocQLTimeoutException.class,
                () -> client.queryDocument(DocFile.bytes(new byte[]{1}, "f.pdf"), Instruction.query("q")));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMillis < 2_500,
                "the SDK deadline must fire before 2.5 s, took " + elapsedMillis + " ms");
    }

    @Test
    void headers() {
        DocQL client = builder(Map.of()).apiKey(KEY).apiUrl(server.url()).build();

        sendOne(client);

        FakeServer.CapturedRequest request = server.requests().get(0);
        assertEquals(KEY, request.header("X-API-Key"));
        assertTrue(request.header("Content-Type").startsWith("multipart/form-data; boundary="),
                request.header("Content-Type"));
        assertTrue(request.header("User-Agent")
                        .matches("^docql-java/0\\.1\\.0 \\(java [^;()]+; [^/()]+/[^()]+\\)$"),
                "User-Agent must be docql-java/<version> (java <version>; <os>/<arch>): "
                        + request.header("User-Agent"));
        assertNull(request.header("X-Request-Id"), "the SDK never sends X-Request-Id");
        assertNull(request.header("Authorization"), "the SDK never sends Authorization");
        assertNull(request.header("Upgrade"), "an HTTP/1.1 request carries no Upgrade header");
        for (String name : request.headerNames()) {
            assertFalse(name.toLowerCase(Locale.ROOT).startsWith("x-docql-"),
                    "the SDK never sends an x-docql-* header, saw " + name);
        }
    }

    @Test
    void redirectNotFollowed() throws IOException {
        FakeServer second = new FakeServer();
        try {
            server.enqueue(302, Map.of("Location", second.url() + "/moved"), "");
            DocQL client = builder(Map.of()).apiKey(KEY).apiUrl(server.url()).build();

            DocQLException e = assertThrows(DocQLException.class,
                    () -> sendOne(client));

            assertEquals(302, e.status());
            assertNull(e.reason(), "a 302 without a DocQL envelope has no reason");
            assertEquals(0, second.requestCount(), "the key never follows a redirect");
        } finally {
            second.close();
        }
    }

    @Test
    void toStringMasksKey() {
        String key = "dql_secret_abcdef1234567890";
        DocQL client = builder(Map.of()).apiKey(key).apiUrl(server.url()).build();

        String text = client.toString();
        assertTrue(text.contains(server.url()), "toString contains the API URL: " + text);
        assertTrue(text.contains("***"), "toString masks the key: " + text);
        assertFalse(text.contains(key), "toString never contains the key: " + text);
        for (int i = 0; i + 6 <= key.length(); i++) {
            assertFalse(text.contains(key.substring(i, i + 6)),
                    "no 6-character substring of the key may survive: " + text);
        }
    }

    @Test
    void concurrentCalls() throws Exception {
        server.echoData();
        DocQL client = builder(Map.of()).apiKey(KEY).apiUrl(server.url()).build();

        int threads = 8;
        int callsPerThread = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<QueryResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads * callsPerThread; i++) {
            int n = i;
            tasks.add(() -> client.queryDocument(
                    DocFile.bytes(new byte[]{(byte) n}, "f-" + n + ".pdf"),
                    Instruction.query("query-" + n)));
        }
        try {
            List<Future<QueryResult>> results = pool.invokeAll(tasks);
            for (int i = 0; i < results.size(); i++) {
                QueryResult result = results.get(i).get();
                assertEquals("query-" + i, result.data().path("query").asText(),
                        "every result's data echoes its own query");
            }
        } finally {
            pool.shutdown();
        }

        assertEquals(threads * callsPerThread, server.requestCount());
        Set<String> boundaries = new java.util.HashSet<>();
        for (FakeServer.CapturedRequest request : server.requests()) {
            boundaries.add(request.boundary());
        }
        assertEquals(threads * callsPerThread, boundaries.size(),
                "every call must carry its own boundary");
    }
}
