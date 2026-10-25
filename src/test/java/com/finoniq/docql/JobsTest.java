package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * getJob / cancelJob, the idempotency key, header hygiene, redirects and
 * job-id encoding (T-E2, OD-10, D-08, D-09), against {@link FakeServer}
 * answering with the kit's own job decode cases.
 */
class JobsTest {

    private static final String KEY = "unit-test-key";
    private static final String JOB_ID = "job_0123456789ABCDEFGHIJKL";
    private static final byte[] PDF = "%PDF-1.4 unit".getBytes(StandardCharsets.US_ASCII);

    private FakeServer server;

    @BeforeEach
    void start() throws IOException {
        server = new FakeServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private DocQL client() {
        return DocQL.builder().apiKey(KEY).apiUrl(server.url()).env(name -> null).build();
    }

    private static JsonNode decodeCase(String id) {
        for (JsonNode c : Kit.jobCases().path("decodeCases")) {
            if (id.equals(c.path("id").asText())) {
                return c;
            }
        }
        throw new IllegalArgumentException("decode case " + id + " not found");
    }

    /** Enqueues a kit decode case as the next answer. */
    private void answer(String caseId) {
        JsonNode c = decodeCase(caseId);
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> header : c.path("headers").properties()) {
            headers.put(header.getKey(), header.getValue().asText());
        }
        server.enqueue(c.path("status").asInt(), headers, c.path("bodyUtf8").asText(""));
    }

    private Job submit(DocQL client) {
        return client.submitDocument(DocFile.bytes(PDF, "f.pdf"), Instruction.query("q"));
    }

    private Job submit(DocQL client, QueryOptions options) {
        return client.submitDocument(DocFile.bytes(PDF, "f.pdf"), Instruction.query("q"), options);
    }

    // --- getJob (T-E2) -------------------------------------------------------------------------

    @Test
    void getJobOnAFailedJobReturnsItWithoutThrowing() {
        answer("job-200-failed-upstream");
        Job job = client().getJob(JOB_ID);
        assertEquals(JobStatus.FAILED, job.status());
        assertNotNull(job.error());
        assertEquals("upstream_unavailable", job.error().reason());
        assertEquals(JOB_ID, job.error().jobId());
        assertFalse(job.error().retryable());
        assertNull(job.result());
        assertEquals(1, server.requestCount());
        assertEquals("GET", server.requests().get(0).method());
    }

    @Test
    void getJob404RaisesWithTheJobIdAfterOneRequest() {
        answer("job-404-not-found");
        DocQLException e = assertThrows(DocQLException.class, () -> client().getJob(JOB_ID));
        assertEquals(404, e.status());
        assertEquals("job_not_found", e.reason());
        assertEquals(JOB_ID, e.jobId());
        assertTrue(e.getMessage().contains("job_id=" + JOB_ID), e.getMessage());
        assertEquals(1, server.requestCount());
    }

    @Test
    void getJob503RaisesAfterOneRequest() {
        server.enqueue(503, Map.of("retry-after", "2"),
                "{\"error_info\":\"busy\",\"metadata\":{\"reason\":\"service_overloaded\"}}");
        DocQLException e = assertThrows(DocQLException.class, () -> client().getJob(JOB_ID));
        assertEquals(503, e.status());
        assertTrue(e.retryable());
        assertEquals(JOB_ID, e.jobId());
        assertEquals(1, server.requestCount());
    }

    @Test
    void unknownStatusIsKeptVerbatim() {
        server.enqueue(200, "{\"job_id\":\"" + JOB_ID + "\",\"status\":\"paused\",\"metadata\":{}}");
        Job job = client().getJob(JOB_ID);
        assertEquals("paused", job.status());
        assertNull(job.result());
        assertNull(job.error());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[1,2]", "{\"job_id\":1,\"status\":\"queued\"}", "{\"status\":\"queued\"}"})
    void unusable2xxJobBodyThrowsWithTheJobId(String body) {
        server.enqueue(200, body);
        DocQLException e = assertThrows(DocQLException.class, () -> client().getJob(JOB_ID));
        assertEquals(200, e.status());
        assertNull(e.reason());
        assertFalse(e.retryable());
        assertEquals(JOB_ID, e.jobId());
    }

    // --- cancelJob (OD-10) ---------------------------------------------------------------------

    @Test
    void cancelJob202ReturnsTheCancelledJobAfterOneDelete() {
        answer("job-delete-202-cancelled");
        Job job = client().cancelJob(JOB_ID);
        assertEquals(JobStatus.CANCELLED, job.status());
        assertEquals(JOB_ID, job.jobId());
        assertEquals(1, server.requestCount());
        assertEquals("DELETE", server.requests().get(0).method());
        assertEquals("/v1/jobs/" + JOB_ID, server.requests().get(0).rawPath());
        assertEquals(0, server.requests().get(0).body().length);
    }

    @Test
    void cancelJob202OnARunningJobReturnsItAsAnswered() {
        answer("job-delete-202-running");
        Job job = client().cancelJob(JOB_ID);
        assertEquals(JobStatus.RUNNING, job.status());
        assertEquals(1, server.requestCount());
    }

    @Test
    void cancelJobOnATerminalJobReturnsItAndIsRepeatable() {
        answer("job-delete-200-terminal");
        answer("job-delete-200-terminal");
        DocQL client = client();
        Job first = client.cancelJob(JOB_ID);
        Job second = client.cancelJob(JOB_ID);
        assertEquals(JobStatus.SUCCEEDED, first.status());
        assertEquals(first.status(), second.status());
        assertEquals(first.jobId(), second.jobId());
        assertEquals(2, server.requestCount());
        for (FakeServer.CapturedRequest request : server.requests()) {
            assertEquals("DELETE", request.method());
        }
    }

    @Test
    void cancelJob404RaisesWithTheJobId() {
        answer("job-404-not-found");
        DocQLException e = assertThrows(DocQLException.class, () -> client().cancelJob(JOB_ID));
        assertEquals(404, e.status());
        assertEquals(JOB_ID, e.jobId());
        assertEquals(1, server.requestCount());
    }

    // --- submit and the idempotency key (D-07, D-08) -------------------------------------------

    @Test
    void submitReturnsTheJobWithTheKeyItSent() {
        answer("job-202-queued");
        Job job = submit(client());
        FakeServer.CapturedRequest request = server.requests().get(0);
        assertEquals("POST", request.method());
        assertEquals("/v1/jobs", request.rawPath());
        List<String> sent = request.headerValues("Idempotency-Key");
        assertEquals(1, sent.size());
        assertEquals(sent.get(0), job.idempotencyKey());
        assertTrue(sent.get(0).matches("[A-Za-z0-9_-]{43}"), "32 random bytes as base64url");
        assertEquals(JobStatus.QUEUED, job.status());
        assertEquals("00000000-0000-4000-8000-000000002301", job.requestId());
        assertEquals(Duration.ofSeconds(2), job.retryAfter());
    }

    @Test
    void everySubmitGeneratesAFreshKey() {
        answer("job-202-queued");
        answer("job-202-queued");
        DocQL client = client();
        assertFalse(submit(client).idempotencyKey().equals(submit(client).idempotencyKey()));
    }

    @Test
    void submitSendsACallerKeyUnchangedAndPutsItOnErrors() {
        answer("job-429-queue-full-tenant");
        DocQLException e = assertThrows(DocQLException.class,
                () -> submit(client(), QueryOptions.builder().idempotencyKey("order-42 retry").build()));
        assertEquals(List.of("order-42 retry"), server.requests().get(0).headerValues("Idempotency-Key"));
        assertEquals("order-42 retry", e.idempotencyKey());
        assertNull(e.jobId());
        assertFalse(e.getMessage().contains("order-42"), e.getMessage());
    }

    @Test
    void anUnusableSubmitAnswerCarriesTheKey() {
        server.enqueue(202, "not json");
        DocQLException e = assertThrows(DocQLException.class,
                () -> submit(client(), QueryOptions.builder().idempotencyKey("order-43").build()));
        assertEquals("order-43", e.idempotencyKey());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "kกey", "tab\there", "del\u007f", "ก"})
    void aBadKeyIsRefusedWithoutEchoAndBeforeAnyRequest(String key) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> QueryOptions.builder().idempotencyKey(key).build());
        assertEquals(Jobs.IDEMPOTENCY_KEY_MESSAGE, e.getMessage());
        if (!key.isEmpty()) {
            assertFalse(e.getMessage().contains(key));
        }
        assertEquals(0, server.requestCount());
    }

    @Test
    void aKeyLongerThan255CharactersIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryOptions.builder().idempotencyKey("x".repeat(256)).build());
        assertEquals("x".repeat(255), Jobs.validateIdempotencyKey("x".repeat(255)));
        assertEquals(" ~", Jobs.validateIdempotencyKey(" ~"));
    }

    @Test
    void sendOnceValidatesEveryKeyBeforeBuildingTheRequest() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Jobs.sendOnce(client(), "GET", "/v1/jobs/x", null, List.of("ok", "ก"),
                        Jobs.POLL_TIMEOUT));
        assertEquals(Jobs.IDEMPOTENCY_KEY_MESSAGE, e.getMessage());
        assertEquals(0, server.requestCount());
    }

    @Test
    void queryDocumentRefusesTheIdempotencyKeyOption() {
        DocQL client = client();
        QueryOptions options = QueryOptions.builder().idempotencyKey("k").build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> client.queryDocument(DocFile.bytes(PDF, "f.pdf"), Instruction.query("q"), options));
        assertEquals("idempotencyKey applies to submitDocument, not queryDocument", e.getMessage());
        Throwable async = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> client.queryDocumentAsync(DocFile.bytes(PDF, "f.pdf"), Instruction.query("q"), options).get())
                .getCause();
        assertInstanceOf(IllegalArgumentException.class, async);
        assertEquals(0, server.requestCount());
    }

    @Test
    void aConnectionFailureOnSubmitCarriesTheKey() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        DocQL client = DocQL.builder().apiKey(KEY).apiUrl("http://127.0.0.1:" + closedPort).env(name -> null).build();
        DocQLConnectionException e = assertThrows(DocQLConnectionException.class,
                () -> submit(client, QueryOptions.builder().idempotencyKey("order-44").build()));
        assertEquals("order-44", e.idempotencyKey());
        assertFalse(e.getMessage().contains("order-44"), e.getMessage());

        DocQLConnectionException generated = assertThrows(DocQLConnectionException.class, () -> submit(client));
        assertNotNull(generated.idempotencyKey());
    }

    @Test
    void aSubmitTimeoutCarriesTheKey() {
        server.delay(Duration.ofSeconds(2));
        DocQL client = DocQL.builder().apiKey(KEY).apiUrl(server.url()).env(name -> null)
                .timeout(Duration.ofMillis(300)).build();
        DocQLTimeoutException e = assertThrows(DocQLTimeoutException.class,
                () -> submit(client, QueryOptions.builder().idempotencyKey("order-45").build()));
        assertEquals("order-45", e.idempotencyKey());
    }

    @Test
    void aPollConnectionFailureCarriesNoKey() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        DocQL client = DocQL.builder().apiKey(KEY).apiUrl("http://127.0.0.1:" + closedPort).env(name -> null).build();
        DocQLConnectionException e = assertThrows(DocQLConnectionException.class, () -> client.getJob(JOB_ID));
        assertNull(e.idempotencyKey());
    }

    // --- header hygiene and redirects (T-26-33) ------------------------------------------------

    @Test
    void noForbiddenHeaderLeavesOnSubmitPollOrCancel() {
        answer("job-202-queued");
        answer("job-200-running");
        answer("job-delete-202-cancelled");
        DocQL client = client();
        submit(client);
        client.getJob(JOB_ID);
        client.cancelJob(JOB_ID);
        List<FakeServer.CapturedRequest> requests = server.requests();
        assertEquals(3, requests.size());
        for (FakeServer.CapturedRequest request : requests) {
            for (String name : request.headerNames()) {
                assertFalse(name.equals("x-request-id"), "no X-Request-Id leaves the SDK");
                assertFalse(name.startsWith("x-docql-"), "no x-docql-* header leaves the SDK: " + name);
                assertFalse(name.equals("authorization"), "the key travels only as X-API-Key");
            }
            assertEquals(List.of(KEY), request.headerValues("X-API-Key"));
            assertTrue(request.header("User-Agent").startsWith("docql-java/"));
        }
        FakeServer.CapturedRequest submit = requests.get(0);
        assertEquals(1, submit.headerValues("Idempotency-Key").size());
        assertTrue(submit.header("Content-Type").startsWith("multipart/form-data; boundary="));
        for (FakeServer.CapturedRequest request : requests.subList(1, 3)) {
            assertEquals(List.of(), request.headerValues("Idempotency-Key"));
            assertNull(request.header("Content-Type"));
        }
    }

    @Test
    void a307IsDecodedAsAnErrorAndNeverFollowed() {
        server.enqueue(307, Map.of("location", "http://elsewhere.test/v1/jobs/x"), "");
        answer("job-200-succeeded");
        DocQLException e = assertThrows(DocQLException.class, () -> client().getJob(JOB_ID));
        assertEquals(307, e.status());
        assertEquals(JOB_ID, e.jobId());
        assertEquals(1, server.requestCount());
    }

    // --- job-id encoding -----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "a.b, /v1/jobs/a%2Eb",
        "'..', /v1/jobs/%2E%2E",
        "a/b, /v1/jobs/a%2Fb",
        "'a b', /v1/jobs/a%20b",
        "a+b, /v1/jobs/a%2Bb",
        "é, /v1/jobs/%C3%A9",
        "job_0123456789ABCDEFGHIJKL, /v1/jobs/job_0123456789ABCDEFGHIJKL",
    })
    void jobPathIsOneEncodedSegment(String jobId, String path) {
        assertEquals(path, Jobs.jobPath(jobId));
    }

    @ParameterizedTest
    @CsvSource({"'..', /v1/jobs/%2E%2E", "a.b, /v1/jobs/a%2Eb", "a/b, /v1/jobs/a%2Fb"})
    void theEncodedJobIdReachesTheWire(String jobId, String rawPath) {
        answer("job-200-running");
        client().getJob(jobId);
        assertEquals(rawPath, server.requests().get(0).rawPath());
    }

    @ParameterizedTest
    @ValueSource(strings = {"getJob", "waitFor", "cancelJob"})
    void aNullOrEmptyJobIdIsRefusedBeforeAnyRequest(String method) {
        DocQL client = client();
        Map<String, Consumer<String>> calls = Map.of(
                "getJob", client::getJob,
                "waitFor", client::waitFor,
                "cancelJob", client::cancelJob);
        for (String jobId : new String[] {null, ""}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> calls.get(method).accept(jobId));
            assertEquals("jobId must be a non-empty string", e.getMessage());
        }
        assertEquals(0, server.requestCount());
    }

    // --- the model and the messages (T-26-34) --------------------------------------------------

    @Test
    void jobToStringNeverShowsTheKeyOrTheResult() {
        answer("job-200-succeeded");
        Job done = client().getJob(JOB_ID);
        assertNotNull(done.result());
        assertEquals("Job{jobId=" + JOB_ID + ", status=succeeded}", done.toString());

        answer("job-202-queued");
        Job submitted = submit(client());
        assertFalse(submitted.toString().contains(submitted.idempotencyKey()));
    }

    @Test
    void aSucceededJobCarriesTheResultAndTimestamps() {
        answer("job-200-succeeded");
        Job job = client().getJob(JOB_ID);
        assertInstanceOf(QueryResult.class, job.result());
        assertEquals(Integer.valueOf(3), job.pageCount());
        assertEquals(Integer.valueOf(3), job.pagesDone());
        assertNotNull(job.createdAt());
        assertNotNull(job.finishedAt());
        assertNotNull(job.expiresAt());
        assertNull(job.idempotencyKey());
    }

    @Test
    void getMessageAddsTheJobIdInBothFormsAndNeverTheKey() {
        DocQLException envelope = new DocQLException(404, "job_not_found", "req-1", null, false, "gone", "{}",
                "job_1", "secret-key-1");
        assertEquals("404 job_not_found: gone (request_id=req-1, job_id=job_1)", envelope.getMessage());
        DocQLException plain = new DocQLException(405, null, null, null, false, null, "Method Not Allowed",
                "job\n2", "secret-key-2");
        assertEquals("405 (non-DocQL response): Method Not Allowed (job_id=job 2)", plain.getMessage());
        assertFalse(plain.getMessage().contains("secret-key"));

        DocQLException v01 = new DocQLException(404, "job_not_found", "req-1", null, false, "gone", "{}");
        assertEquals("404 job_not_found: gone (request_id=req-1)", v01.getMessage());
        DocQLException v01Plain = new DocQLException(405, null, null, null, false, null, "Method Not Allowed");
        assertEquals("405 (non-DocQL response): Method Not Allowed", v01Plain.getMessage());
    }

    @Test
    void theWaitTimeoutNamesTheJobAndSaysItWasNotCancelled() {
        DocQLWaitTimeoutException e = new DocQLWaitTimeoutException("job_1", Duration.ofMinutes(15), KEY);
        assertEquals("wait for job job_1 timed out after 900 s; the job was not cancelled", e.getMessage());
        assertEquals("job_1", e.jobId());
        assertInstanceOf(DocQLTimeoutException.class, e);
        assertEquals("wait for job j timed out after 0.5 s; the job was not cancelled",
                new DocQLWaitTimeoutException("j", Duration.ofMillis(500), KEY).getMessage());
    }

    @Test
    void theFailedJobErrorIsTheSameInstanceAcrossAccessors() {
        answer("job-200-failed-interrupted");
        Job job = client().getJob(JOB_ID);
        assertSame(job.error(), job.error());
        assertEquals(JOB_ID, job.error().jobId());
        assertFalse(job.error().retryable());
    }
}
