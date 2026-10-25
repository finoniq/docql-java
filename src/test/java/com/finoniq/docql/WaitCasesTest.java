package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * waitFor replays the kit's {@code waitCases} (W1-W7) offline, exactly: the
 * Builder's sleeper and nanoClock seams record every sleep and advance a fake
 * clock, so a 15-minute wait runs in milliseconds with the real wait code
 * (D-03, D-04, D-05, OD-5). A recording HttpClient around the real one
 * records each request's method, fake send time and timeout, so the tests
 * pin that no poll is ever sent at or past the deadline and that waitFor
 * never sends anything but GET.
 */
class WaitCasesTest {

    private static final String KEY = "unit-test-key";
    private static final String JOB_ID = "job_0123456789ABCDEFGHIJKL";

    private static final String RUNNING = "{\"job_id\":\"" + JOB_ID + "\",\"status\":\"running\",\"metadata\":{}}";
    private static final String SUCCEEDED = "{\"job_id\":\"" + JOB_ID + "\",\"status\":\"succeeded\","
            + "\"metadata\":{},\"result\":{\"data\":{},\"metadata\":{\"request_id\":\"r-1\"}}}";
    private static final String CANCELLED = "{\"job_id\":\"" + JOB_ID + "\",\"status\":\"cancelled\",\"metadata\":{}}";
    private static final String BUSY = "{\"error_info\":\"busy\",\"metadata\":{\"reason\":\"service_overloaded\"}}";

    private FakeServer server;
    private FakeClock clock;
    private RecordingClient http;

    @BeforeEach
    void start() throws IOException {
        server = new FakeServer();
        clock = new FakeClock();
        http = new RecordingClient(clock);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** A monotonic fake clock in nanoseconds; each sleep is recorded and advances it. */
    static final class FakeClock {

        private static final long START = 1_000_000_000_000L;
        private long now = START;
        private final List<Duration> sleeps = new ArrayList<>();

        long now() {
            return now;
        }

        void sleep(Duration d) {
            sleeps.add(d);
            now += d.toNanos();
        }

        Duration elapsed() {
            return Duration.ofNanos(now - START);
        }

        List<Long> sleepSeconds() {
            List<Long> seconds = new ArrayList<>();
            for (Duration d : sleeps) {
                seconds.add(d.getSeconds());
            }
            return seconds;
        }
    }

    /** One request the recording client saw. */
    static final class Sent {

        final String method;
        final long at;
        final Duration timeout;

        Sent(String method, long at, Duration timeout) {
            this.method = method;
            this.at = at;
            this.timeout = timeout;
        }
    }

    /**
     * The real SDK-shaped client (HTTP/1.1, Redirect.NEVER) with a recorder
     * in front: each send records the method, the fake clock and the
     * request's timeout; the first {@code failures} sends throw instead of
     * sending (a connect failure or a per-poll timeout).
     */
    static final class RecordingClient extends HttpClient {

        private final HttpClient delegate = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        private final LongSupplier clock;
        final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
        final List<IOException> failures = new ArrayList<>();
        final CountDownLatch firstSend = new CountDownLatch(1);

        RecordingClient(FakeClock clock) {
            this.clock = clock::now;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            sent.add(new Sent(request.method(), clock.getAsLong(), request.timeout().orElse(null)));
            firstSend.countDown();
            if (!failures.isEmpty()) {
                throw failures.remove(0);
            }
            return delegate.send(request, handler);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler) {
            return delegate.sendAsync(request, handler);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
            return delegate.sendAsync(request, handler, push);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }

        List<String> methods() {
            List<String> methods = new ArrayList<>();
            for (Sent s : sent) {
                methods.add(s.method);
            }
            return methods;
        }
    }

    private DocQL client() {
        return DocQL.builder().apiKey(KEY).apiUrl(server.url()).env(name -> null)
                .httpClient(http).sleeper(clock::sleep).nanoClock(clock::now).build();
    }

    private static Map<String, String> retryAfter(String seconds) {
        return Map.of("retry-after", seconds);
    }

    /** No request is sent at or past the deadline, and nothing but GET. */
    private void assertPollsOnlyBeforeTheDeadline(Duration timeout) {
        long deadline = FakeClock.START + timeout.toNanos();
        for (Sent s : http.sent) {
            assertEquals("GET", s.method, "waitFor sends only GET");
            assertTrue(s.at < deadline, "a poll was sent at or past the deadline");
        }
        for (FakeServer.CapturedRequest request : server.requests()) {
            assertEquals("GET", request.method(), "no DELETE or other non-GET request");
        }
    }

    // --- the kit's waitCases, exactly (D-12) ---------------------------------------------------

    private static List<JsonNode> kitWaitCases() {
        List<JsonNode> cases = new ArrayList<>();
        Kit.jobCases().path("waitCases").forEach(cases::add);
        return cases;
    }

    @Test
    void theKitScriptsW1ToW7InOrder() {
        List<String> ids = new ArrayList<>();
        for (JsonNode c : kitWaitCases()) {
            ids.add(c.path("id").asText());
        }
        assertEquals(List.of("w1-succeeded", "w2-failed", "w3-cancelled", "w4-expired", "w5-not-found",
                "w6-poll-429", "w7-timeout"), ids);
    }

    @TestFactory
    Stream<DynamicTest> waitCases() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : kitWaitCases()) {
            String id = c.path("id").asText();
            tests.add(DynamicTest.dynamicTest(id, () -> {
                // A fresh fake per case: the factory's own fixtures serve one case only.
                stop();
                start();
                System.out.println("WAIT_CASE " + id);
                runWaitCase(c);
                System.out.println("WAIT_CASE_OK " + id);
            }));
        }
        return tests.stream();
    }

    private void runWaitCase(JsonNode c) {
        String id = c.path("id").asText();
        for (JsonNode response : c.path("responses")) {
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> header : response.path("headers").properties()) {
                headers.put(header.getKey(), header.getValue().asText());
            }
            server.enqueue(response.path("status").asInt(), headers, response.path("bodyUtf8").asText(""));
        }
        if (c.path("repeatLast").asBoolean(false)) {
            server.repeatLast();
        }
        String jobId = c.path("jobId").asText();
        JsonNode expect = c.path("expect");
        String outcome = expect.path("outcome").asText();
        DocQL client = client();
        switch (outcome) {
            case "succeeded":
            case "cancelled": {
                Job job = client.waitFor(jobId);
                assertEquals(outcome, job.status(), id + ": outcome");
                assertEquals(jobId, job.jobId(), id + ": job id");
                break;
            }
            case "failed":
            case "error": {
                DocQLException e = assertThrows(DocQLException.class, () -> client.waitFor(jobId), id);
                assertEquals(expect.path("reason").asText(), e.reason(), id + ": reason");
                assertEquals(jobId, e.jobId(), id + ": job id on the error");
                if ("failed".equals(outcome)) {
                    assertFalse(e.retryable(), id + ": a failed job is final");
                }
                break;
            }
            case "timeout": {
                DocQLWaitTimeoutException e = assertThrows(DocQLWaitTimeoutException.class,
                        () -> client.waitFor(jobId), id);
                assertEquals(jobId, e.jobId(), id + ": job id on the timeout");
                assertTrue(clock.elapsed().getSeconds() >= expect.path("minElapsedSeconds").asLong(),
                        id + ": elapsed " + clock.elapsed());
                break;
            }
            default:
                fail(id + ": unknown outcome " + outcome);
        }
        if (expect.has("sleepsSeconds")) {
            List<Long> want = new ArrayList<>();
            expect.path("sleepsSeconds").forEach(n -> want.add(n.asLong()));
            assertEquals(want, clock.sleepSeconds(), id + ": sleeps");
            assertEquals(want.size() + 1, server.requestCount(), id + ": one poll per sleep plus the first");
        }
        assertEquals(expect.path("deletes").asInt(), 0, id + ": the kit expects no DELETE");
        assertPollsOnlyBeforeTheDeadline(Jobs.DEFAULT_WAIT);
    }

    @Test
    void w7DefaultTimeoutIs900SecondsAndNeverPollsAtTheDeadline() {
        server.enqueue(200, retryAfter("5"), RUNNING).repeatLast();
        DocQLWaitTimeoutException e = assertThrows(DocQLWaitTimeoutException.class, () -> client().waitFor(JOB_ID));
        assertEquals(JOB_ID, e.jobId());
        assertEquals(Duration.ofSeconds(900), clock.elapsed());
        assertEquals(180, clock.sleeps.size());
        assertEquals(180, server.requestCount(), "polls at 0, 5, ... 895 s; none at 900 s");
        assertEquals("wait for job " + JOB_ID + " timed out after 900 s; the job was not cancelled", e.getMessage());
        assertPollsOnlyBeforeTheDeadline(Jobs.DEFAULT_WAIT);
    }

    @Test
    void w7WithACallerTimeoutOf10Seconds() {
        server.enqueue(200, retryAfter("5"), RUNNING).repeatLast();
        WaitOptions options = WaitOptions.builder().timeout(Duration.ofSeconds(10)).build();
        assertThrows(DocQLWaitTimeoutException.class, () -> client().waitFor(JOB_ID, options));
        assertEquals(List.of(5L, 5L), clock.sleepSeconds());
        assertEquals(2, server.requestCount());
        assertPollsOnlyBeforeTheDeadline(Duration.ofSeconds(10));
    }

    /** The kit runner's W1 variant: Retry-After stripped, the absent default is 1 s (D-03 as adjusted). */
    @Test
    void w1WithoutRetryAfterSleeps1Second() {
        JsonNode w1 = kitWaitCases().get(0);
        for (JsonNode response : w1.path("responses")) {
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> header : response.path("headers").properties()) {
                if (!header.getKey().equalsIgnoreCase("retry-after")) {
                    headers.put(header.getKey(), header.getValue().asText());
                }
            }
            server.enqueue(response.path("status").asInt(), headers, response.path("bodyUtf8").asText(""));
        }
        Job job = client().waitFor(w1.path("jobId").asText());
        assertEquals(JobStatus.SUCCEEDED, job.status());
        assertEquals(List.of(1L, 1L), clock.sleepSeconds());
    }

    // --- the deadline (OD-5) -------------------------------------------------------------------

    @Test
    void aSleepLandingExactlyOnTheDeadlineEndsTheWaitWithoutPollingAtIt() {
        server.enqueue(200, retryAfter("2"), RUNNING).enqueue(200, retryAfter("2"), RUNNING).enqueue(200, SUCCEEDED);
        WaitOptions options = WaitOptions.builder().timeout(Duration.ofSeconds(4)).build();
        DocQLWaitTimeoutException e = assertThrows(DocQLWaitTimeoutException.class,
                () -> client().waitFor(JOB_ID, options));
        assertEquals(JOB_ID, e.jobId());
        assertEquals(List.of(2L, 2L), clock.sleepSeconds());
        assertEquals(2, server.requestCount(), "no third poll at the 4 s deadline");
        assertPollsOnlyBeforeTheDeadline(Duration.ofSeconds(4));
    }

    @Test
    void aSleepPastTheDeadlineThrowsWithoutSleeping() {
        server.enqueue(200, retryAfter("2"), RUNNING).repeatLast();
        WaitOptions options = WaitOptions.builder().timeout(Duration.ofSeconds(3)).build();
        assertThrows(DocQLWaitTimeoutException.class, () -> client().waitFor(JOB_ID, options));
        assertEquals(List.of(2L), clock.sleepSeconds());
        assertEquals(2, server.requestCount());
    }

    @Test
    void anUnknownStatusIsPolledUntilTheDeadline() {
        server.enqueue(200, "{\"job_id\":\"" + JOB_ID + "\",\"status\":\"paused\",\"metadata\":{}}").repeatLast();
        WaitOptions options = WaitOptions.builder().timeout(Duration.ofSeconds(10)).build();
        assertThrows(DocQLWaitTimeoutException.class, () -> client().waitFor(JOB_ID, options));
        assertEquals(Collections.nCopies(10, 1L), clock.sleepSeconds());
        assertEquals(10, server.requestCount(), "polls at 0..9 s; none at 10 s");
        assertPollsOnlyBeforeTheDeadline(Duration.ofSeconds(10));
    }

    @Test
    void thePollTimeoutIsCappedByTheTimeLeftWithA1SecondFloor() {
        server.enqueue(200, retryAfter("2"), RUNNING).repeatLast();
        WaitOptions options = WaitOptions.builder().timeout(Duration.ofSeconds(5)).build();
        assertThrows(DocQLWaitTimeoutException.class, () -> client().waitFor(JOB_ID, options));
        List<Duration> timeouts = new ArrayList<>();
        for (Sent s : http.sent) {
            timeouts.add(s.timeout);
        }
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ofSeconds(1)), timeouts);
    }

    @Test
    void aTinyWaitStillGivesThePollA1SecondFloor() {
        server.enqueue(200, SUCCEEDED);
        client().waitFor(JOB_ID, WaitOptions.builder().timeout(Duration.ofMillis(500)).build());
        assertEquals(Duration.ofSeconds(1), http.sent.get(0).timeout);
    }

    @Test
    void pollsUse30SecondsNeverTheUploadTimeout() {
        server.enqueue(200, SUCCEEDED).enqueue(200, SUCCEEDED).enqueue(202, CANCELLED);
        DocQL client = client();
        client.waitFor(JOB_ID);
        client.getJob(JOB_ID);
        client.cancelJob(JOB_ID);
        for (Sent s : http.sent) {
            assertEquals(Duration.ofSeconds(30), s.timeout, s.method);
        }
    }

    // --- Retry-After and the backoff (D-03, D-04) ----------------------------------------------

    @ParameterizedTest
    @CsvSource({"0, 1", "100, 30", "7, 7", "garbage, 1", "99999999999999999999, 30", "' 3 ', 3"})
    void retryAfterIsClampedTo1And30(String header, long seconds) {
        server.enqueue(200, retryAfter(header), RUNNING).enqueue(200, SUCCEEDED);
        client().waitFor(JOB_ID);
        assertEquals(List.of(seconds), clock.sleepSeconds());
    }

    @Test
    void anHttpDateRetryAfterFarAheadIsCappedAt30() {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(600));
        server.enqueue(200, retryAfter(date), RUNNING).enqueue(200, SUCCEEDED);
        client().waitFor(JOB_ID);
        assertEquals(List.of(30L), clock.sleepSeconds());
    }

    @Test
    void anAbsentRetryAfterSleeps1Second() {
        server.enqueue(200, RUNNING).enqueue(200, SUCCEEDED);
        client().waitFor(JOB_ID);
        assertEquals(List.of(1L), clock.sleepSeconds());
    }

    @Test
    void thePollIntervalRaisesTheSleepNeverLowersIt() {
        server.enqueue(200, retryAfter("2"), RUNNING).enqueue(200, retryAfter("7"), RUNNING).enqueue(200, SUCCEEDED);
        client().waitFor(JOB_ID, WaitOptions.builder().pollInterval(Duration.ofSeconds(5)).build());
        assertEquals(List.of(5L, 7L), clock.sleepSeconds());
    }

    @Test
    void aPollIntervalOf10SecondsWithRetryAfter2Sleeps10() {
        server.enqueue(200, retryAfter("2"), RUNNING).enqueue(200, SUCCEEDED);
        client().waitFor(JOB_ID, WaitOptions.builder().pollInterval(Duration.ofSeconds(10)).build());
        assertEquals(List.of(10L), clock.sleepSeconds());
    }

    @Test
    void transient503sWithoutRetryAfterBackOff124ThenResetAfterA200() {
        server.enqueue(503, BUSY).enqueue(503, BUSY).enqueue(503, BUSY)
                .enqueue(200, RUNNING).enqueue(503, BUSY).enqueue(200, SUCCEEDED);
        client().waitFor(JOB_ID);
        assertEquals(List.of(1L, 2L, 4L, 1L, 1L), clock.sleepSeconds());
        assertEquals(6, server.requestCount());
    }

    @Test
    void theBackoffCapsAt30() {
        for (int i = 0; i < 7; i++) {
            server.enqueue(503, BUSY);
        }
        server.enqueue(200, SUCCEEDED);
        client().waitFor(JOB_ID);
        assertEquals(List.of(1L, 2L, 4L, 8L, 16L, 30L, 30L), clock.sleepSeconds());
    }

    @Test
    void a429WithRetryAfterIsHonoured() {
        server.enqueue(429, retryAfter("3"), "{\"metadata\":{\"reason\":\"rate_limit_exceeded\"}}")
                .enqueue(200, SUCCEEDED);
        client().waitFor(JOB_ID);
        assertEquals(List.of(3L), clock.sleepSeconds());
    }

    @Test
    void networkErrorsAndPollTimeoutsAreRetried() {
        http.failures.add(new ConnectException("refused"));
        http.failures.add(new HttpTimeoutException("request timed out"));
        server.enqueue(200, SUCCEEDED);
        Job job = client().waitFor(JOB_ID);
        assertEquals(JobStatus.SUCCEEDED, job.status());
        assertEquals(List.of(1L, 2L), clock.sleepSeconds());
        assertEquals(3, http.sent.size());
        assertEquals(1, server.requestCount());
    }

    // --- outcomes (D-05) -----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "401, '{\"error_info\":\"invalid API key\",\"metadata\":{\"reason\":\"invalid_api_key\"}}', invalid_api_key",
        "404, '{\"error_info\":\"no such job\",\"metadata\":{\"reason\":\"job_not_found\"}}', job_not_found",
        "404, '{\"error_info\":\"expired\",\"metadata\":{\"reason\":\"job_expired\"}}', job_expired",
        "404, '{\"message\":\"Route GET:/v1/jobs/x not found\"}', ''",
        "405, 'Method Not Allowed', ''",
        "500, '{\"error_info\":\"boom\",\"metadata\":{\"reason\":\"internal_error\"}}', internal_error",
    })
    void nonTransientErrorsThrowAfterOneGet(int status, String body, String reason) {
        server.enqueue(status, body).enqueue(200, SUCCEEDED);
        DocQLException e = assertThrows(DocQLException.class, () -> client().waitFor(JOB_ID));
        assertEquals(status, e.status());
        assertEquals(reason.isEmpty() ? null : reason, e.reason());
        assertEquals(JOB_ID, e.jobId());
        assertEquals(1, server.requestCount());
        assertEquals(List.of(), clock.sleeps);
    }

    @Test
    void aFailedJobWithAnEmbedded503ThrowsOnceNotRetryable() {
        server.enqueue(200, "{\"job_id\":\"" + JOB_ID + "\",\"status\":\"failed\",\"metadata\":{},"
                + "\"error\":{\"status_code\":503,\"error_info\":\"down\",\"metadata\":{\"reason\":\"upstream_unavailable\"}}}");
        DocQLException e = assertThrows(DocQLException.class, () -> client().waitFor(JOB_ID));
        assertEquals(503, e.status());
        assertEquals("upstream_unavailable", e.reason());
        assertFalse(e.retryable());
        assertEquals(JOB_ID, e.jobId());
        assertEquals(1, server.requestCount());
    }

    @Test
    void aFailedJobWithoutAnErrorObjectIsUnusable() {
        server.enqueue(200, "{\"job_id\":\"" + JOB_ID + "\",\"status\":\"failed\",\"metadata\":{}}");
        DocQLException e = assertThrows(DocQLException.class, () -> client().waitFor(JOB_ID));
        assertEquals(200, e.status());
        assertNull(e.reason());
        assertFalse(e.retryable());
        assertEquals(JOB_ID, e.jobId());
    }

    @Test
    void waitTwiceOnASucceededJob() {
        server.enqueue(200, SUCCEEDED).repeatLast();
        DocQL client = client();
        assertEquals(JobStatus.SUCCEEDED, client.waitFor(JOB_ID).status());
        assertEquals(JobStatus.SUCCEEDED, client.waitFor(JOB_ID).status());
        assertEquals(2, server.requestCount());
        assertEquals(List.of(), clock.sleeps);
    }

    @Test
    void waitOnACancelledJobReturnsIt() {
        server.enqueue(200, CANCELLED);
        assertEquals(JobStatus.CANCELLED, client().waitFor(JOB_ID).status());
    }

    // --- interrupts: the 26-01 rule ------------------------------------------------------------

    @Test
    void anInterruptedSleepRestoresTheFlagAndSendsNoDelete() {
        server.enqueue(200, retryAfter("2"), RUNNING).repeatLast();
        DocQL client = DocQL.builder().apiKey(KEY).apiUrl(server.url()).env(name -> null)
                .httpClient(http).nanoClock(clock::now)
                .sleeper(d -> {
                    throw new InterruptedException("test interrupt");
                })
                .build();
        try {
            DocQLConnectionException e = assertThrows(DocQLConnectionException.class, () -> client.waitFor(JOB_ID));
            assertFalse(e instanceof DocQLTimeoutException);
            assertEquals("waitFor interrupted for job " + JOB_ID + "; the job was not cancelled", e.getMessage());
            assertInstanceOf(InterruptedException.class, e.getCause());
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is set again");
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, server.requestCount());
        assertPollsOnlyBeforeTheDeadline(Jobs.DEFAULT_WAIT);
    }

    @Test
    void interruptingTheWaitingThreadEndsWaitForPromptly() throws Exception {
        server.enqueue(200, retryAfter("30"), RUNNING).repeatLast();
        // The real default sleeper and clock: a 30 s sleep the interrupt must cut short.
        DocQL client = DocQL.builder().apiKey(KEY).apiUrl(server.url()).env(name -> null).httpClient(http).build();
        assertInterruptEndsTheWait(client);
    }

    @Test
    void interruptingAPollInFlightEndsWaitForPromptly() throws Exception {
        server.delay(Duration.ofSeconds(3)).enqueue(200, RUNNING);
        DocQL client = DocQL.builder().apiKey(KEY).apiUrl(server.url()).env(name -> null).httpClient(http).build();
        assertInterruptEndsTheWait(client);
    }

    private void assertInterruptEndsTheWait(DocQL client) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean flagAfter = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            try {
                client.waitFor(JOB_ID);
            } catch (Throwable t) {
                thrown.set(t);
            }
            flagAfter.set(Thread.currentThread().isInterrupted());
        });
        waiter.start();
        assertTrue(http.firstSend.await(10, TimeUnit.SECONDS), "waitFor sent its first poll");
        waiter.interrupt();
        waiter.join(2_000);
        assertFalse(waiter.isAlive(), "waitFor ended within 2 s of the interrupt");
        DocQLConnectionException e = assertInstanceOf(DocQLConnectionException.class, thrown.get());
        assertTrue(e.getMessage().contains(JOB_ID), e.getMessage());
        assertTrue(flagAfter.get(), "the interrupt flag is set again");
        for (Sent s : http.sent) {
            assertEquals("GET", s.method, "no DELETE on an interrupt");
        }
    }

    // --- WaitOptions ---------------------------------------------------------------------------

    @Test
    void waitOptionsRejectZeroOrNegativeValues() {
        for (Duration bad : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThrows(IllegalArgumentException.class, () -> WaitOptions.builder().timeout(bad).build());
            assertThrows(IllegalArgumentException.class, () -> WaitOptions.builder().pollInterval(bad).build());
        }
        assertThrows(NullPointerException.class, () -> WaitOptions.builder().timeout(null));
        assertThrows(NullPointerException.class, () -> WaitOptions.builder().pollInterval(null));
        assertThrows(NullPointerException.class, () -> client().waitFor(JOB_ID, null));
        assertEquals(0, server.requestCount());
    }

    @Test
    void waitOptionsDefaultTo15MinutesAndNoPollInterval() {
        WaitOptions options = WaitOptions.builder().build();
        assertEquals(Duration.ofMinutes(15), options.timeout());
        assertNull(options.pollInterval());
    }

    @Test
    void aHugeTimeoutAndPollIntervalDoNotOverflow() {
        server.enqueue(200, RUNNING).enqueue(200, SUCCEEDED);
        Job job = client().waitFor(JOB_ID, WaitOptions.builder()
                .timeout(Duration.ofSeconds(Long.MAX_VALUE)).pollInterval(Duration.ofDays(3)).build());
        assertEquals(JobStatus.SUCCEEDED, job.status());
        assertEquals(List.of(Duration.ofDays(3)), clock.sleeps);
    }

    @Test
    void theWaitModuleSendsDeleteOnlyFromTheCancelPath() throws IOException {
        String source = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/java/com/finoniq/docql/Jobs.java")), StandardCharsets.UTF_8);
        int waitStart = source.indexOf("static Job waitFor(");
        int waitEnd = source.indexOf("\n    }\n", waitStart);
        assertTrue(waitStart > 0 && waitEnd > waitStart, "the wait loop was found");
        assertFalse(source.substring(waitStart, waitEnd).contains("DELETE"), "the wait loop never names DELETE");
    }
}
