package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The kit's {@code via: wait} job cases through the public job methods,
 * against the stub booted by {@code scripts/stub-up.sh} (JSDK-01). The same
 * strict-stub rule as {@link ContractIT}: with {@code DOCQL_REQUIRE_STUB=1}
 * a missing {@code DOCQL_STUB_URL} fails the run, otherwise the cases skip.
 * ContractIT's three-way sha256 gate runs in the same {@code verify} and
 * fails the build on a kit mismatch.
 *
 * <p>Step semantics follow the kit's reference runners (docql-console
 * {@code deploy/scripts/job-cases-edge.mjs}): a per-run {@code {run}} token
 * keeps each case's fingerprint unique, {@code submit} posts the document as
 * a job and binds the answered job id, {@code cancel} calls the public
 * {@link DocQL#cancelJob(String)}, and {@code wait} calls the public
 * {@link DocQL#waitFor(String)}. Each case prints
 * {@code JOB_CONTRACT_WAIT_OK <id>} when it passes.
 */
class JobContractIT {

    private static final List<String> KNOWN_VIAS = List.of("submit", "poll", "cancel", "wait", "sync", "infra");

    /** One token per JVM run: 8 base62 characters (kit invariant 8). */
    private static final String RUN = runToken();

    private static String base;

    @BeforeAll
    static void resolveStub() {
        String url = System.getenv("DOCQL_STUB_URL");
        if (url == null || url.isBlank()) {
            if ("1".equals(System.getenv("DOCQL_REQUIRE_STUB"))) {
                fail("DOCQL_REQUIRE_STUB=1 but DOCQL_STUB_URL is not set: "
                        + "run the cases through scripts/stub-up.sh --run");
            }
            return;
        }
        base = url.replaceAll("/+$", "");
    }

    private static String runToken() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            token.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return token.toString();
    }

    /** A case with an unknown via must fail the run, never silently disappear. */
    @Test
    void everyJobCaseUsesAKnownVia() {
        List<String> unknown = new ArrayList<>();
        for (JsonNode c : Kit.jobCases().path("cases")) {
            if (!KNOWN_VIAS.contains(c.path("via").asText())) {
                unknown.add(c.path("id").asText());
            }
        }
        assertEquals(List.of(), unknown, "job-cases.json carries cases with an unknown via");
    }

    private static List<JsonNode> waitCases() {
        List<JsonNode> cases = new ArrayList<>();
        for (JsonNode c : Kit.jobCases().path("cases")) {
            if ("stub".equals(c.path("target").asText()) && "wait".equals(c.path("via").asText())) {
                cases.add(c);
            }
        }
        return cases;
    }

    @Test
    void waitCasesAreSelected() {
        List<String> ids = new ArrayList<>();
        for (JsonNode c : waitCases()) {
            ids.add(c.path("id").asText());
        }
        assertEquals(List.of("wait-succeeded", "wait-failed", "wait-cancelled"), ids);
    }

    @TestFactory
    Stream<DynamicTest> waitCasesAgainstTheStub() {
        if (base == null) {
            return Stream.empty();
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : waitCases()) {
            String id = c.path("id").asText();
            tests.add(DynamicTest.dynamicTest(id, () -> {
                runCase(c);
                System.out.println("JOB_CONTRACT_WAIT_OK " + id);
            }));
        }
        return tests.stream();
    }

    private static void runCase(JsonNode c) {
        String id = c.path("id").asText();
        DocQL client = DocQL.builder()
                .apiKey(Kit.jobCases().path("keys").path("valid").asText())
                .apiUrl(base)
                .build();
        Map<String, Job> jobs = new HashMap<>();
        for (JsonNode step : c.path("steps")) {
            String op = step.path("op").asText();
            switch (op) {
                case "submit": {
                    assertEquals("valid", step.path("key").asText("valid"), id + ": submit key");
                    assertEquals(202, step.path("expect").path("status").asInt(), id + ": expected status");
                    JsonNode request = step.path("request");
                    JsonNode file = request.path("file");
                    String name = file.path("name").asText();
                    QueryOptions.Builder options = QueryOptions.builder().filename(name);
                    if (request.hasNonNull("mode")) {
                        options.mode(Mode.of(request.path("mode").asText()));
                    }
                    Instruction instruction = request.hasNonNull("prompt")
                            ? Instruction.prompt(sub(request.path("prompt").asText()))
                            : Instruction.query(sub(request.path("query").asText()));
                    Job job = client.submitDocument(DocFile.bytes(Kit.caseFileBytes(file), name), instruction,
                            options.build());
                    assertEquals(JobStatus.QUEUED, job.status(), id + ": submitted status");
                    assertNotNull(job.jobId(), id + ": job id");
                    assertFalse(job.jobId().isEmpty(), id + ": job id");
                    assertNotNull(job.idempotencyKey(), id + ": generated idempotency key");
                    jobs.put(step.path("as").asText(), job);
                    break;
                }
                case "cancel": {
                    assertEquals(202, step.path("expect").path("status").asInt(), id + ": expected status");
                    String jobId = jobs.get(step.path("job").asText()).jobId();
                    Job cancelled = client.cancelJob(jobId);
                    assertEquals(jobId, cancelled.jobId(), id + ": cancelled job id");
                    break;
                }
                case "wait": {
                    String jobId = jobs.get(step.path("job").asText()).jobId();
                    JsonNode expect = step.path("expect");
                    String outcome = expect.path("outcome").asText();
                    if ("failed".equals(outcome)) {
                        DocQLException error = assertThrows(DocQLException.class, () -> client.waitFor(jobId),
                                id + ": a failed job throws its error");
                        assertEquals(expect.path("reason").asText(), error.reason(), id + ": reason");
                        assertEquals(jobId, error.jobId(), id + ": jobId on the error");
                        assertFalse(error.retryable(), id + ": a failed job is not retryable");
                    } else {
                        Job done = client.waitFor(jobId);
                        assertEquals(jobId, done.jobId(), id + ": job id");
                        assertEquals(outcome, done.status(), id + ": outcome");
                        if (JobStatus.SUCCEEDED.equals(outcome)) {
                            assertInstanceOf(QueryResult.class, done.result(), id + ": result");
                        }
                    }
                    break;
                }
                default:
                    fail(id + ": unsupported op " + op);
            }
        }
        assertTrue(!jobs.isEmpty(), id + ": the case submitted a job");
    }

    private static String sub(String text) {
        return text.replace("{run}", RUN);
    }
}
