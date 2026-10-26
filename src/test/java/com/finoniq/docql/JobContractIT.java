package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every stub-target kit v3 job case through the SDK's own wire layer, and the
 * public submit's same-key retry, against the stub booted by
 * {@code scripts/stub-up.sh} (JSDK-02, JSDK-04). The same strict-stub rule as
 * {@link ContractIT}: with {@code DOCQL_REQUIRE_STUB=1} a missing
 * {@code DOCQL_STUB_URL} fails the run, otherwise the stub tests skip.
 * ContractIT's three-way sha256 gate runs in the same {@code verify} and
 * fails the build on a kit mismatch.
 *
 * <p>The job-case runner is a port of the kit's reference runners
 * (docql-console {@code deploy/scripts/job-cases-edge.mjs} {@code runCase}
 * and {@code apps/docql-stub/test/job-kit.ts} {@code runJobCase}) and of the
 * Python SDK's {@code tests/contract/test_job_cases.py}, op by op. Two
 * layers:
 * <ul>
 * <li>{@code submit}, {@code join}, {@code poll} and {@code cancel} steps send
 * exactly one request through the package-private single-attempt
 * {@link Jobs#sendOnce}, never the retrying public submitDocument (j16, j28
 * and j28-unknown assert the raw first answer). A kit key the SDK refuses
 * (j25) is first proven refused by sendOnce, then sent through
 * {@link Jobs#sendUnchecked} for that one request. Each answer is checked raw
 * (every wire field the kit names) and through the SDK's decoders
 * ({@link Jobs#parseJob} for a 2xx, {@link Decoder#decodeError} otherwise).</li>
 * <li>{@code wait} steps call the public {@link DocQL#waitFor(String, WaitOptions)};
 * {@code sync} steps call the public
 * {@link DocQL#queryDocument(DocFile, Instruction, QueryOptions)}.</li>
 * </ul>
 * Step semantics, as in the reference runners: {@code {run}} is replaced in
 * query and idempotency-key strings with one 8-character base62 token per
 * JVM run; {@code as} binds the job id of a 2xx submit and {@code job} names
 * it; {@code key} picks the kit's {@code valid}, {@code validOther} or
 * {@code invalid} API key; an {@code async} submit runs on its own thread
 * until its {@code join}; {@code delayBeforeMs} and {@code sleep} use real
 * time (the kit's stubEnv keeps a job for 5 s); a step's {@code boundary}
 * (else the kit's default) goes through the Builder's boundary seam; an
 * {@code idempotencyKey} is one string, a {@code lines} list sent as several
 * header lines, or a {@code repeat} x {@code length} string. Every answer
 * from a job route, each GET inside waitFor included, must meet the kit's
 * job-route invariants. Each passed case prints
 * {@code JOB_CONTRACT_CASE_OK <id>}, and the class prints
 * {@code JOB_CONTRACT_CASES_RAN <n>} once when all of them passed; CI's
 * contract.yml requires both to equal the jq count.
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

    // --- every stub-target kit v3 job case (JSDK-04) ---------------------------------------------

    /** The vias this class runs; sync is sdk-cases.json (ContractIT) and infra is deployment. */
    private static final List<String> RUN_VIAS = List.of("submit", "poll", "cancel", "wait");
    /** job-kit.ts DEFAULT_BOUNDARY: the multipart boundary of a step that names none. */
    private static final String DEFAULT_BOUNDARY = "docqlsdktestboundary0000000001";
    /** A poll-until gives up after this many seconds of real sleeping (job-cases-edge.mjs). */
    private static final int POLL_UNTIL_SECONDS = 120;
    private static final Set<String> TERMINAL = Set.of("succeeded", "failed", "cancelled");
    /** The expect fields this runner checks: every field job-kit.ts checks. */
    private static final Set<String> IMPLEMENTED_EXPECT = Set.of("status", "reason", "retryAfterSeconds",
            "location", "replayed", "jobStatus", "scope", "header", "pageCount", "resultPageCount",
            "embeddedReason", "embeddedStatus", "sameJobAs", "differentJobFrom", "bodyEquals",
            "bodyEqualsExceptRequestId", "requestIdDiffersFrom", "resultEqualsSync", "errorEqualsSync", "states");
    private static final Set<String> WAIT_EXPECT = Set.of("outcome", "reason");
    private static final List<String> JOB_KEYS =
            List.of("job_id", "status", "metadata", "created_at", "started_at", "finished_at", "expires_at");
    private static final List<String> JOB_METADATA_KEYS = List.of("page_count", "pages_done", "request_id");
    private static final Pattern TIMESTAMP = Pattern.compile("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ$");
    private static final Pattern WHOLE_SECONDS = Pattern.compile("^[1-9][0-9]*$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Every other case is excluded by exactly one named rule (the first that matches). */
    private static final List<Map.Entry<String, Predicate<JsonNode>>> EXCLUSIONS = List.of(
            Map.entry("target real: runs only against the real DocQL (kit invariant 7)",
                    c -> "real".equals(c.path("target").asText())),
            Map.entry("target console: needs the trusted key and tenant header",
                    c -> "console".equals(c.path("target").asText())),
            Map.entry("via sync: sdk-cases.json, run by ContractIT", c -> "sync".equals(c.path("via").asText())),
            Map.entry("via infra: deployment checks, no SDK call", c -> "infra".equals(c.path("via").asText())));

    /** How many job cases ran to their last check (the count gate). */
    private static final AtomicInteger RAN = new AtomicInteger();

    /** Every case this class runs: a stub case replayed through the SDK (44 in kit v3). */
    private static List<JsonNode> jobCases() {
        List<JsonNode> cases = new ArrayList<>();
        for (JsonNode c : Kit.jobCases().path("cases")) {
            if ("stub".equals(c.path("target").asText()) && RUN_VIAS.contains(c.path("via").asText())) {
                cases.add(c);
            }
        }
        return cases;
    }

    private static String classify(JsonNode c) {
        if ("stub".equals(c.path("target").asText()) && RUN_VIAS.contains(c.path("via").asText())) {
            return "run";
        }
        for (Map.Entry<String, Predicate<JsonNode>> rule : EXCLUSIONS) {
            if (rule.getValue().test(c)) {
                return rule.getKey();
            }
        }
        return null;
    }

    /** Each kit case is run here or excluded by one named rule; a new kind fails loudly. */
    @Test
    void everyJobCaseIsClassified() {
        List<String> unclassified = new ArrayList<>();
        for (JsonNode c : Kit.jobCases().path("cases")) {
            if (classify(c) == null) {
                unclassified.add(c.path("id").asText());
            }
        }
        assertEquals(List.of(), unclassified, "job cases neither run nor excluded by a named rule");
        List<JsonNode> cases = jobCases();
        assertFalse(cases.isEmpty(), "no stub job case selected");
        for (JsonNode c : cases) {
            String id = c.path("id").asText();
            assertTrue(c.path("ref").isMissingNode() || c.path("ref").isNull(), id + ": a ref case");
            assertTrue(c.path("steps").size() > 0, id + ": no steps");
        }
    }

    @TestFactory
    Stream<DynamicTest> jobCasesAgainstTheStub() {
        if (base == null) {
            return Stream.empty();
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : jobCases()) {
            String id = c.path("id").asText();
            tests.add(DynamicTest.dynamicTest(id, () -> {
                new CaseRunner(c).run();
                RAN.incrementAndGet();
                System.out.println("JOB_CONTRACT_CASE_OK " + id);
            }));
        }
        return tests.stream();
    }

    /**
     * The count gate: the job cases that ran to completion must equal the
     * selected count exactly, so a dropped or half-failing case can never
     * silently go green.
     */
    @AfterAll
    static void jobCountGate() {
        if (base == null) {
            return;
        }
        int want = jobCases().size();
        assertEquals(want, RAN.get(), "ran " + RAN.get() + " of the " + want
                + " stub job cases: a case stopped before its last check");
        System.out.println("JOB_CONTRACT_CASES_RAN " + RAN.get());
    }

    /** A case check that failed; the message names the case, the step and the field. */
    static final class CaseError extends AssertionError {

        private static final long serialVersionUID = 1L;

        CaseError(String message) {
            super(message);
        }
    }

    private static JsonNode bodyJson(Jobs.Answer wire) {
        try {
            JsonNode node = MAPPER.readTree(wire.body());
            return node == null || node.isMissingNode() ? null : node;
        } catch (IOException e) {
            return null;
        }
    }

    /** A nested field, or NullNode when any step is missing or not an object. */
    private static JsonNode field(JsonNode value, String... path) {
        JsonNode node = value;
        for (String key : path) {
            if (node == null || !node.isObject()) {
                return NullNode.getInstance();
            }
            node = node.get(key);
        }
        return node == null ? NullNode.getInstance() : node;
    }

    private static String header(Jobs.Answer wire, String name) {
        return wire.headers().firstValue(name).orElse(null);
    }

    private static JsonNode text(String value) {
        return value == null ? NullNode.getInstance() : TextNode.valueOf(value);
    }

    private static JsonNode number(Integer value) {
        return value == null ? NullNode.getInstance() : IntNode.valueOf(value);
    }

    private static JsonNode seconds(Duration value) {
        return value == null ? NullNode.getInstance() : IntNode.valueOf(Math.toIntExact(value.getSeconds()));
    }

    /** A deep copy without metadata.request_id (and, when asked, metadata.extraction_status). */
    private static JsonNode withoutRequestId(JsonNode value, boolean dropExtractionStatus) {
        if (value == null) {
            return NullNode.getInstance();
        }
        JsonNode copy = value.deepCopy();
        JsonNode metadata = copy.get("metadata");
        if (metadata instanceof ObjectNode) {
            ((ObjectNode) metadata).remove("request_id");
            if (dropExtractionStatus) {
                ((ObjectNode) metadata).remove("extraction_status");
            }
        }
        return copy;
    }

    /** Kit invariants 1-5 on one job-route answer (job-kit.ts checkJobInvariants). */
    private static void checkJobInvariants(String where, Jobs.Answer wire) {
        if (!"no-store".equals(header(wire, "cache-control"))) {
            throw new CaseError(where + ": invariant: Cache-Control is " + header(wire, "cache-control")
                    + ", not no-store");
        }
        String requestId = header(wire, "x-request-id");
        if (requestId == null || requestId.isEmpty()) {
            throw new CaseError(where + ": invariant: no X-Request-Id");
        }
        JsonNode body = bodyJson(wire);
        if (body != null && body.isObject() && body.has("error_info")
                && !text(requestId).equals(field(body, "metadata", "request_id"))) {
            throw new CaseError(where + ": invariant: envelope metadata.request_id differs from X-Request-Id");
        }
        if (body != null && body.isObject() && body.has("job_id")) {
            List<String> expected = new ArrayList<>(JOB_KEYS);
            if ("succeeded".equals(body.path("status").asText(null))) {
                expected.add("result");
            }
            if ("failed".equals(body.path("status").asText(null))) {
                expected.add("error");
            }
            List<String> keys = new ArrayList<>();
            body.fieldNames().forEachRemaining(keys::add);
            if (!keys.equals(expected)) {
                throw new CaseError(where + ": invariant: job keys " + String.join(",", keys));
            }
            JsonNode metadata = body.get("metadata");
            List<String> metadataKeys = new ArrayList<>();
            if (metadata != null && metadata.isObject()) {
                metadata.fieldNames().forEachRemaining(metadataKeys::add);
            }
            if (!metadataKeys.equals(JOB_METADATA_KEYS)) {
                throw new CaseError(where + ": invariant: job metadata keys out of order");
            }
            for (String key : List.of("created_at", "started_at", "finished_at", "expires_at")) {
                JsonNode value = body.get(key);
                if (value != null && !value.isNull()
                        && (!value.isTextual() || !TIMESTAMP.matcher(value.textValue()).matches())) {
                    throw new CaseError(where + ": invariant: " + key + " " + value
                            + " is not second-precision RFC 3339 UTC");
                }
            }
            if (!body.path("created_at").isTextual()) {
                throw new CaseError(where + ": invariant: created_at is null");
            }
        }
        String retryAfter = header(wire, "retry-after");
        if (retryAfter != null && !WHOLE_SECONDS.matcher(retryAfter).matches()) {
            throw new CaseError(where + ": invariant: Retry-After " + retryAfter + " is not a whole number >= 1");
        }
        String location = header(wire, "location");
        if (location != null) {
            JsonNode jobId = field(body, "job_id");
            if (!jobId.isTextual() || !location.equals(Jobs.jobPath(jobId.textValue()))) {
                throw new CaseError(where + ": invariant: Location " + location + " is not /v1/jobs/{job_id}");
            }
        }
    }

    private static List<String> dedupe(List<String> states) {
        List<String> out = new ArrayList<>();
        for (String state : states) {
            if (out.isEmpty() || !out.get(out.size() - 1).equals(state)) {
                out.add(state);
            }
        }
        return out;
    }

    private static boolean isSubsequence(List<String> observed, List<String> allowed) {
        int j = 0;
        for (String state : observed) {
            while (j < allowed.size() && !allowed.get(j).equals(state)) {
                j++;
            }
            if (j == allowed.size()) {
                return false;
            }
            j++;
        }
        return true;
    }

    /** An async submit running on its own thread until its join. */
    static final class Pending {

        final String name;
        final AtomicReference<Jobs.Answer> result = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        Thread thread;

        Pending(String name) {
            this.name = name;
        }
    }

    /**
     * One case, op by op: a port of the kit's reference runners
     * (job-cases-edge.mjs runCase, job-kit.ts runJobCase) and of the Python
     * SDK's CaseRunner. Each case builds its own transport, clients,
     * bindings and captures, and every fingerprint and key embeds the case id
     * plus the run token, so the order the cases run in never changes an
     * outcome.
     */
    static final class CaseRunner {

        private final JsonNode c;
        private final String caseId;
        private final CapturingClient http = new CapturingClient();
        private final Map<String, String> jobs = new ConcurrentHashMap<>();
        private final Map<String, Pending> pending = new HashMap<>();
        private final Map<String, Jobs.Answer> captured = new HashMap<>();
        private int checked;

        CaseRunner(JsonNode c) {
            this.c = c;
            this.caseId = c.path("id").asText();
        }

        // step inputs

        private String apiKey(JsonNode step, String where) {
            String name = step.path("key").asText("valid");
            if (!List.of("valid", "validOther", "invalid").contains(name)) {
                throw new CaseError(where + ": key " + name + " is not one the SDK can send");
            }
            return Kit.jobCases().path("keys").path(name).asText();
        }

        /** A client on this case's transport with the step's boundary (else the kit default). */
        private DocQL client(JsonNode step, String where) {
            String boundary = step.path("boundary").asText(DEFAULT_BOUNDARY);
            return DocQL.builder().apiKey(apiKey(step, where)).apiUrl(base).httpClient(http)
                    .boundary(() -> boundary).build();
        }

        private List<String> idempotencyKeys(JsonNode step) {
            JsonNode idem = step.get("idempotencyKey");
            if (idem == null || idem.isNull()) {
                return List.of();
            }
            if (idem.isTextual()) {
                return List.of(sub(idem.textValue()));
            }
            if (idem.has("lines")) {
                List<String> lines = new ArrayList<>();
                for (JsonNode line : idem.path("lines")) {
                    lines.add(sub(line.asText()));
                }
                return lines;
            }
            return List.of(idem.path("repeat").asText().repeat(idem.path("length").asInt()));
        }

        private String pathOf(JsonNode step, String where) {
            if (step.has("id")) {
                return Jobs.jobPath(step.path("id").asText());
            }
            String jobId = jobs.get(step.path("job").asText(""));
            if (jobId == null) {
                throw new CaseError(where + ": job " + step.path("job").asText() + " was never bound by a 2xx submit");
            }
            return Jobs.jobPath(jobId);
        }

        private static DocFile file(JsonNode request) {
            JsonNode spec = request.path("file");
            return DocFile.bytes(Kit.caseFileBytes(spec), spec.path("name").asText());
        }

        private Instruction instruction(JsonNode request) {
            return request.has("prompt")
                    ? Instruction.prompt(sub(request.path("prompt").asText()))
                    : Instruction.query(sub(request.path("query").asText()));
        }

        private static QueryOptions options(JsonNode request) {
            QueryOptions.Builder options = QueryOptions.builder().filename(request.path("file").path("name").asText());
            if (request.hasNonNull("mode")) {
                options.mode(Mode.of(request.path("mode").asText()));
            }
            return options.build();
        }

        // one request through the single-attempt primitive

        private Jobs.Answer sendSubmit(JsonNode step, String where) {
            DocQL client = client(step, where);
            JsonNode request = step.path("request");
            Jobs.Upload upload = client.upload(file(request), instruction(request), options(request));
            List<String> keys = idempotencyKeys(step);
            boolean refused = false;
            for (String key : keys) {
                try {
                    Jobs.validateIdempotencyKey(key);
                } catch (IllegalArgumentException e) {
                    refused = true;
                }
            }
            try {
                if (refused) {
                    // The SDK refuses this key before any request; the case asserts
                    // DocQL's own answer to it, so this one request bypasses the check.
                    int before = http.seen().size();
                    assertThrows(IllegalArgumentException.class,
                            () -> Jobs.sendOnce(client, "POST", Jobs.JOBS_PATH, upload, keys, DocQL.DEFAULT_TIMEOUT),
                            where + ": the SDK refuses the kit's malformed key");
                    assertEquals(before, http.seen().size(), where + ": a refused key sent a request");
                    return Jobs.sendUnchecked(client, "POST", Jobs.JOBS_PATH, upload, keys, DocQL.DEFAULT_TIMEOUT);
                }
                return Jobs.sendOnce(client, "POST", Jobs.JOBS_PATH, upload, keys, DocQL.DEFAULT_TIMEOUT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CaseError(where + ": interrupted");
            }
        }

        private Jobs.Answer sendJob(JsonNode step, String where, String method, String path) {
            try {
                return Jobs.sendOnce(client(step, where), method, path, null, List.of(), Jobs.POLL_TIMEOUT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CaseError(where + ": interrupted");
            }
        }

        // checks

        /** Invariants on every job-route answer the transport saw since the last check. */
        private void checkNewAnswers(String where) {
            List<Seen> seen = http.seen();
            int size = seen.size();
            for (int i = checked; i < size; i++) {
                Seen s = seen.get(i);
                if (s.rawPath.equals(Jobs.JOBS_PATH) || s.rawPath.startsWith(Jobs.JOBS_PATH + "/")) {
                    String path = s.rawPath.length() > 60 ? s.rawPath.substring(0, 60) : s.rawPath;
                    checkJobInvariants(where + " " + s.method + " " + path, s.answer());
                }
            }
            checked = size;
        }

        private Jobs.Answer capture(String name, String where) {
            Jobs.Answer wire = captured.get(name);
            if (wire == null) {
                throw new CaseError(where + ": capture " + name + " is missing");
            }
            return wire;
        }

        private static void eq(String where, String what, JsonNode actual, JsonNode expected) {
            JsonNode a = actual == null || actual.isMissingNode() ? NullNode.getInstance() : actual;
            JsonNode e = expected == null || expected.isMissingNode() ? NullNode.getInstance() : expected;
            if (!a.equals(e)) {
                throw new CaseError(where + ": " + what + ": expected " + e + ", got " + a);
            }
        }

        private void checkExpect(String where, JsonNode expect, Jobs.Answer wire, String apiKey, boolean jobRoute) {
            List<String> unknown = new ArrayList<>();
            expect.fieldNames().forEachRemaining(name -> {
                if (!IMPLEMENTED_EXPECT.contains(name)) {
                    unknown.add(name);
                }
            });
            if (!unknown.isEmpty()) {
                throw new CaseError(where + ": expect fields not implemented: " + unknown);
            }
            JsonNode body = bodyJson(wire);
            // The SDK's own decode of the same answer: a Job for a 2xx job answer, else DocQLException.
            Job job = null;
            DocQLException error = null;
            if (jobRoute && wire.ok()) {
                job = Jobs.parseJob(wire.status(), wire.headers(), wire.body(), apiKey, null);
            } else if (!wire.ok()) {
                error = Decoder.decodeError(wire.status(), wire.headers(), wire.body(), apiKey);
            }

            if (expect.has("status")) {
                eq(where, "status", IntNode.valueOf(wire.status()), expect.get("status"));
                if (error != null) {
                    eq(where, "decoded status", IntNode.valueOf(error.status()), expect.get("status"));
                }
            }
            if (expect.has("reason")) {
                JsonNode reason = field(body, "metadata", "reason");
                eq(where, "reason", reason.isTextual() ? reason : NullNode.getInstance(), expect.get("reason"));
                if (error != null) {
                    eq(where, "decoded reason", text(error.reason()), expect.get("reason"));
                }
            }
            if (expect.has("retryAfterSeconds")) {
                String raw = header(wire, "retry-after");
                eq(where, "Retry-After", raw == null ? NullNode.getInstance() : IntNode.valueOf(Integer.parseInt(raw)),
                        expect.get("retryAfterSeconds"));
                Duration decoded = job != null ? job.retryAfter() : error != null ? error.retryAfter() : null;
                eq(where, "decoded retry_after", seconds(decoded), expect.get("retryAfterSeconds"));
            }
            if (expect.has("location")) {
                String location = header(wire, "location");
                if (expect.get("location").asBoolean()) {
                    JsonNode jobId = field(body, "job_id");
                    eq(where, "Location", text(location),
                            jobId.isTextual() ? text(Jobs.jobPath(jobId.textValue())) : NullNode.getInstance());
                } else {
                    eq(where, "Location", text(location), NullNode.getInstance());
                }
            }
            if (expect.has("replayed")) {
                eq(where, "Idempotent-Replayed", text(header(wire, "idempotent-replayed")),
                        expect.get("replayed").asBoolean() ? TextNode.valueOf("true") : NullNode.getInstance());
            }
            if (expect.has("jobStatus")) {
                eq(where, "job status", field(body, "status"), expect.get("jobStatus"));
                eq(where, "decoded job status", text(job == null ? null : job.status()), expect.get("jobStatus"));
            }
            if (expect.has("scope")) {
                eq(where, "scope", field(body, "metadata", "scope"), expect.get("scope"));
            }
            if (expect.has("header")) {
                eq(where, "metadata.header", field(body, "metadata", "header"), expect.get("header"));
            }
            if (expect.has("pageCount")) {
                eq(where, "page_count", field(body, "metadata", "page_count"), expect.get("pageCount"));
                eq(where, "decoded page_count", number(job == null ? null : job.pageCount()), expect.get("pageCount"));
            }
            if (expect.has("resultPageCount")) {
                eq(where, "result page_count", field(body, "result", "metadata", "page_count"),
                        expect.get("resultPageCount"));
                QueryResult result = job == null ? null : job.result();
                eq(where, "decoded result page_count", number(result == null ? null : result.pageCount()),
                        expect.get("resultPageCount"));
            }
            if (expect.has("embeddedReason")) {
                eq(where, "embedded reason", field(body, "error", "metadata", "reason"), expect.get("embeddedReason"));
                DocQLException embedded = job == null ? null : job.error();
                eq(where, "decoded embedded reason", text(embedded == null ? null : embedded.reason()),
                        expect.get("embeddedReason"));
                if (embedded == null || embedded.retryable()) {
                    throw new CaseError(where + ": embedded retryable");
                }
            }
            if (expect.has("embeddedStatus")) {
                eq(where, "embedded status", field(body, "error", "status_code"), expect.get("embeddedStatus"));
                DocQLException embedded = job == null ? null : job.error();
                eq(where, "decoded embedded status", number(embedded == null ? null : embedded.status()),
                        expect.get("embeddedStatus"));
            }
            if (expect.has("sameJobAs")) {
                String other = jobs.get(expect.get("sameJobAs").asText());
                eq(where, "job_id", field(body, "job_id"), text(other));
                eq(where, "decoded job_id", text(job == null ? null : job.jobId()), text(other));
            }
            if (expect.has("differentJobFrom")) {
                String other = jobs.get(expect.get("differentJobFrom").asText());
                if (other == null || text(other).equals(field(body, "job_id"))) {
                    throw new CaseError(where + ": job_id should differ from job " + expect.get("differentJobFrom"));
                }
            }
            if (expect.has("bodyEquals")
                    && !Arrays.equals(wire.body(), capture(expect.get("bodyEquals").asText(), where).body())) {
                throw new CaseError(where + ": body differs from " + expect.get("bodyEquals") + " byte for byte");
            }
            if (expect.has("bodyEqualsExceptRequestId")) {
                String name = expect.get("bodyEqualsExceptRequestId").asText();
                eq(where, "body (except request_id) vs " + name, withoutRequestId(body, false),
                        withoutRequestId(bodyJson(capture(name, where)), false));
            }
            if (expect.has("requestIdDiffersFrom")) {
                String name = expect.get("requestIdDiffersFrom").asText();
                if (Objects.equals(header(wire, "x-request-id"), header(capture(name, where), "x-request-id"))) {
                    throw new CaseError(where + ": X-Request-Id equals " + name + "'s");
                }
            }
            // Handoff §7: a job's result (or error) equals the sync body for the same input,
            // except metadata.request_id and, on success, metadata.extraction_status.
            if (expect.has("resultEqualsSync")) {
                String name = expect.get("resultEqualsSync").asText();
                eq(where, "result vs sync " + name + " (§7)", withoutRequestId(field(body, "result"), true),
                        withoutRequestId(bodyJson(capture(name, where)), true));
            }
            if (expect.has("errorEqualsSync")) {
                String name = expect.get("errorEqualsSync").asText();
                eq(where, "error vs sync " + name + " (§7)", withoutRequestId(field(body, "error"), false),
                        withoutRequestId(bodyJson(capture(name, where)), false));
            }
        }

        private void finish(JsonNode step, String where, Jobs.Answer wire, boolean jobRoute, String bind) {
            JsonNode bound = field(bodyJson(wire), "job_id");
            if (bind != null && bound.isTextual() && wire.status() < 300) {
                jobs.put(bind, bound.textValue());
            }
            if (step.has("expect")) {
                ObjectNode expect = step.get("expect").deepCopy();
                expect.remove("states");
                checkExpect(where, expect, wire, apiKey(step, where), jobRoute);
            }
        }

        // ops

        void run() throws InterruptedException {
            JsonNode steps = c.path("steps");
            for (int index = 0; index < steps.size(); index++) {
                JsonNode step = steps.get(index);
                String op = step.path("op").asText();
                String where = caseId + " step " + index + " (" + op + ")";
                if (step.has("delayBeforeMs")) {
                    Thread.sleep(step.path("delayBeforeMs").asLong());
                }
                Jobs.Answer wire = null;
                boolean async = step.path("async").asBoolean(false);
                switch (op) {
                    case "sleep":
                        Thread.sleep(step.path("seconds").asLong() * 1000);
                        break;
                    case "submit":
                        if (async) {
                            startAsync(step, where);
                        } else {
                            wire = sendSubmit(step, where);
                            finish(step, where, wire, true, step.path("as").asText(null));
                        }
                        break;
                    case "join":
                        wire = join(step, where);
                        break;
                    case "sync":
                        wire = sync(step, where);
                        break;
                    case "cancel":
                        wire = sendJob(step, where, "DELETE", pathOf(step, where));
                        finish(step, where, wire, true, null);
                        break;
                    case "poll":
                        wire = poll(step, where);
                        break;
                    case "wait":
                        waitStep(step, where);
                        break;
                    default:
                        throw new CaseError(where + ": unsupported op " + op);
                }
                checkNewAnswers(where);
                if (wire != null && step.has("name") && !("submit".equals(op) && async)) {
                    captured.put(step.path("name").asText(), wire);
                }
            }
            if (!pending.isEmpty()) {
                for (Pending p : pending.values()) {
                    p.thread.join();
                }
                throw new CaseError(caseId + ": async submit never joined: " + pending.keySet());
            }
        }

        private void startAsync(JsonNode step, String where) {
            if (!step.has("as")) {
                throw new CaseError(where + ": async without as");
            }
            Pending p = new Pending(step.path("name").asText(null));
            p.thread = new Thread(() -> {
                try {
                    p.result.set(sendSubmit(step, where));
                } catch (Throwable t) {
                    p.error.set(t);
                }
            }, caseId + "-async");
            p.thread.setDaemon(true);
            pending.put(step.path("as").asText(), p);
            p.thread.start();
        }

        private Jobs.Answer join(JsonNode step, String where) throws InterruptedException {
            Pending p = pending.remove(step.path("job").asText(""));
            if (p == null) {
                throw new CaseError(where + ": nothing to join");
            }
            p.thread.join(60_000);
            if (p.error.get() != null) {
                Throwable t = p.error.get();
                if (t instanceof RuntimeException) {
                    throw (RuntimeException) t;
                }
                if (t instanceof Error) {
                    throw (Error) t;
                }
                throw new CaseError(where + ": the async submit failed: " + t);
            }
            Jobs.Answer wire = p.result.get();
            if (wire == null) {
                throw new CaseError(where + ": the async submit did not finish within 60 s");
            }
            finish(step, where, wire, true, step.path("job").asText());
            if (p.name != null) {
                captured.put(p.name, wire);
            }
            return wire;
        }

        /** The public queryDocument; the transport supplies the raw answer for §7. */
        private Jobs.Answer sync(JsonNode step, String where) {
            DocQL client = client(step, where);
            JsonNode request = step.path("request");
            int before = http.seen().size();
            QueryResult result = null;
            DocQLException error = null;
            try {
                result = client.queryDocument(file(request), instruction(request), options(request));
            } catch (DocQLException e) {
                error = e;
            }
            List<Seen> answers = http.seen().subList(before, http.seen().size());
            if (answers.size() != 1) {
                throw new CaseError(where + ": queryDocument made " + answers.size() + " requests, not 1");
            }
            Jobs.Answer wire = answers.get(0).answer();
            JsonNode expect = step.path("expect");
            if (expect.has("status") && expect.path("status").asInt() == 200) {
                assertNotNull(result, where + ": queryDocument threw " + error);
            } else if (expect.has("status")) {
                assertNotNull(error, where + ": queryDocument returned a result");
                assertEquals(expect.path("status").asInt(), error.status(), where + ": decoded sync status");
            }
            finish(step, where, wire, false, null);
            return wire;
        }

        private Jobs.Answer poll(JsonNode step, String where) throws InterruptedException {
            String path = pathOf(step, where);
            String until = step.path("until").asText(null);
            if (until == null) {
                Jobs.Answer wire = sendJob(step, where, "GET", path);
                finish(step, where, wire, true, null);
                return wire;
            }
            // Poll until the named status, sleeping each Retry-After in real time.
            List<String> observed = new ArrayList<>();
            int slept = 0;
            int index = 0;
            Jobs.Answer wire;
            while (true) {
                wire = sendJob(step, where, "GET", path);
                checkJobInvariants(where + " poll " + index, wire);
                JsonNode status = field(bodyJson(wire), "status");
                if (wire.status() != 200 || !status.isTextual()) {
                    break;
                }
                observed.add(status.textValue());
                if (until.equals(status.textValue())) {
                    break;
                }
                if (TERMINAL.contains(status.textValue())) {
                    throw new CaseError(where + ": reached " + status.textValue() + " before " + until);
                }
                String retryAfter = header(wire, "retry-after");
                if (retryAfter == null) {
                    throw new CaseError(where + ": a " + status.textValue() + " poll without Retry-After");
                }
                int seconds = Integer.parseInt(retryAfter);
                if (slept + seconds > POLL_UNTIL_SECONDS) {
                    throw new CaseError(where + ": " + until + " not reached within " + POLL_UNTIL_SECONDS + " s");
                }
                Thread.sleep(seconds * 1000L);
                slept += seconds;
                index++;
            }
            finish(step, where, wire, true, null);
            List<String> states = dedupe(observed);
            JsonNode allowed = step.path("expect").path("states");
            if (allowed.isArray()) {
                List<String> allowedStates = new ArrayList<>();
                allowed.forEach(s -> allowedStates.add(s.asText()));
                if (!isSubsequence(states, allowedStates)) {
                    throw new CaseError(where + ": observed states " + states + " are not a subsequence of "
                            + allowedStates);
                }
            }
            return wire;
        }

        /** The public waitFor; the outcome is read the way the kit's waitForJob reports it. */
        private void waitStep(JsonNode step, String where) {
            JsonNode expect = step.path("expect");
            List<String> unknown = new ArrayList<>();
            expect.fieldNames().forEachRemaining(name -> {
                if (!WAIT_EXPECT.contains(name)) {
                    unknown.add(name);
                }
            });
            if (!unknown.isEmpty()) {
                throw new CaseError(where + ": a wait step checks only outcome and reason, not " + unknown);
            }
            DocQL client = client(step, where);
            String jobId = jobs.get(step.path("job").asText(""));
            if (jobId == null) {
                throw new CaseError(where + ": job " + step.path("job").asText() + " was never bound by a 2xx submit");
            }
            WaitOptions.Builder options = WaitOptions.builder();
            if (step.has("timeoutSeconds")) {
                options.timeout(Duration.ofSeconds(step.path("timeoutSeconds").asLong()));
            }
            int before = http.seen().size();
            String outcome;
            String reason = null;
            try {
                Job done = client.waitFor(jobId, options.build());
                assertEquals(jobId, done.jobId(), where + ": job id");
                if (JobStatus.SUCCEEDED.equals(done.status())) {
                    assertInstanceOf(QueryResult.class, done.result(), where + ": succeeded without a result");
                } else if (JobStatus.CANCELLED.equals(done.status())) {
                    assertNull(done.result(), where + ": a cancelled job has no result");
                }
                outcome = done.status();
            } catch (DocQLWaitTimeoutException e) {
                outcome = "timeout";
            } catch (DocQLException e) {
                assertEquals(jobId, e.jobId(), where + ": wait error without the job id");
                List<Seen> seen = http.seen();
                Jobs.Answer last = seen.get(seen.size() - 1).answer();
                boolean failedJob = last.status() == 200 && "failed".equals(field(bodyJson(last), "status").asText(null));
                outcome = failedJob ? "failed" : "error";
                if (failedJob) {
                    assertFalse(e.retryable(), where + ": a failed job's error is retryable");
                }
                reason = e.reason();
            }
            Set<String> methods = new java.util.TreeSet<>();
            for (Seen s : http.seen().subList(before, http.seen().size())) {
                methods.add(s.method);
            }
            assertEquals(Set.of("GET"), methods, where + ": waitFor sent " + methods + ", never a DELETE");
            if (expect.has("outcome") && !expect.path("outcome").asText().equals(outcome)) {
                throw new CaseError(where + ": outcome " + outcome + ", expected " + expect.path("outcome").asText());
            }
            if (expect.has("reason") && !Objects.equals(expect.path("reason").asText(null), reason)) {
                throw new CaseError(where + ": reason " + reason + ", expected " + expect.path("reason"));
            }
        }
    }

    // --- the public submit's same-key retry (JSDK-02, D-07) -----------------------------------
    //
    // These go through the public, retrying submitDocument, unlike the kit's wire steps,
    // which use the single-attempt Jobs.sendOnce. A capturing HttpClient counts what
    // reached the stub and which key each request carried, and the sleeper records each
    // retry sleep and really sleeps it (the hold knob runs on real time). The stub knobs
    // are filename-based (stubEnv STUB_TEST_KNOBS=true): persist-unknown creates the job
    // and still answers 503 Retry-After 5, persist-503 answers 503 Retry-After 5 without a
    // job, hold-1500 keeps the key reserved for 1.5 s (a concurrent same-key submit gets
    // 409 idempotency_in_progress Retry-After 1). Each passed test prints
    // JOB_SUBMIT_OK <name>.

    /** The kit's own PDF bytes (j28-unknown's document). */
    private static byte[] kitPdf() {
        for (JsonNode c : Kit.jobCases().path("cases")) {
            if ("j28-unknown".equals(c.path("id").asText())) {
                return Base64.getDecoder().decode(c.path("steps").get(0).path("request").path("file")
                        .path("base64").asText());
            }
        }
        throw new IllegalStateException("j28-unknown is missing from job-cases.json");
    }

    private static String submitQuery(String name) {
        return "What is the total amount? (p26-10-" + name + "-" + RUN + ")";
    }

    private static String submitKey(String name) {
        return "p26-10-" + name + "-" + RUN;
    }

    /** A client on the capturing transport whose sleeper records and really sleeps. */
    private static DocQL submitClient(CapturingClient http, List<Duration> sleeps) {
        return DocQL.builder()
                .apiKey(Kit.jobCases().path("keys").path("valid").asText())
                .apiUrl(base)
                .httpClient(http)
                .sleeper(d -> {
                    sleeps.add(d);
                    Thread.sleep(d.toMillis());
                })
                .build();
    }

    private static Job submitPdf(DocQL client, DocFile file, String query, String key) {
        return client.submitDocument(file, Instruction.query(query),
                QueryOptions.builder().idempotencyKey(key).build());
    }

    private static List<Long> seconds(List<Duration> sleeps) {
        List<Long> out = new ArrayList<>();
        for (Duration d : sleeps) {
            out.add(d.getSeconds());
        }
        return out;
    }

    @Test
    void persistUnknownIsRetriedOnceWithTheSameKeyAndReturnsTheReplayedJob() {
        if (base == null) {
            return;
        }
        CapturingClient http = new CapturingClient();
        List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
        DocQL client = submitClient(http, sleeps);
        String key = submitKey("persist-unknown");
        Job job = submitPdf(client, DocFile.bytes(kitPdf(), "persist-unknown.pdf"), submitQuery("persist-unknown"),
                key);
        assertEquals(JobStatus.QUEUED, job.status());
        assertEquals(key, job.idempotencyKey());
        assertNotNull(job.jobId());
        assertEquals(List.of(503, 202), http.statuses());
        assertEquals(List.of(List.of(key), List.of(key)), http.keys());
        assertEquals("5", http.seen().get(0).header("retry-after"));
        assertEquals("true", http.seen().get(1).header("idempotent-replayed"));
        assertEquals(List.of(5L), seconds(sleeps));
        // The replayed job is the one DocQL created on the first (503) attempt, so it
        // exists. The kit's stubEnv keeps a job for 5 s and the retry slept 5 s, so the
        // owner may already see job_expired; job_not_found would mean the replay pointed
        // at a job that never existed.
        try {
            assertEquals(job.jobId(), client.getJob(job.jobId()).jobId());
        } catch (DocQLException e) {
            assertEquals(Reasons.JOB_EXPIRED, e.reason(), e.getMessage());
        }
        System.out.println("JOB_SUBMIT_OK persist-unknown");
    }

    @Test
    void persist503ThrowsThe503WithTheKeyAfterFourRequests() {
        if (base == null) {
            return;
        }
        CapturingClient http = new CapturingClient();
        List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
        DocQL client = submitClient(http, sleeps);
        String key = submitKey("persist-503");
        DocQLException e = assertThrows(DocQLException.class,
                () -> submitPdf(client, DocFile.bytes(kitPdf(), "persist-503.pdf"), submitQuery("persist-503"), key));
        assertEquals(503, e.status());
        assertEquals("upstream_unavailable", e.reason());
        assertEquals(key, e.idempotencyKey());
        assertEquals(Duration.ofSeconds(5), e.retryAfter());
        assertEquals(List.of(503, 503, 503, 503), http.statuses());
        assertEquals(List.of(List.of(key), List.of(key), List.of(key), List.of(key)), http.keys());
        assertEquals(List.of(5L, 5L, 5L), seconds(sleeps));
        System.out.println("JOB_SUBMIT_OK persist-503");
    }

    @Test
    void sameKeyTwiceReturnsTheSameJobIdAndTheSecondIsReplayed() {
        if (base == null) {
            return;
        }
        CapturingClient http = new CapturingClient();
        List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
        DocQL client = submitClient(http, sleeps);
        String key = submitKey("same-key-twice");
        Job first = submitPdf(client, DocFile.bytes(kitPdf(), "twice.pdf"), submitQuery("same-key-twice"), key);
        Job second = submitPdf(client, DocFile.bytes(kitPdf(), "twice.pdf"), submitQuery("same-key-twice"), key);
        assertEquals(first.jobId(), second.jobId());
        assertEquals(List.of(202, 202), http.statuses());
        assertNull(http.seen().get(0).header("idempotent-replayed"));
        assertEquals("true", http.seen().get(1).header("idempotent-replayed"));
        assertEquals(List.of(), sleeps);
        System.out.println("JOB_SUBMIT_OK same-key-twice");
    }

    @Test
    void holdConcurrentSameKeySubmitsReturnTheSameJobOneAfterA409() throws InterruptedException {
        if (base == null) {
            return;
        }
        CapturingClient http = new CapturingClient();
        List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
        DocQL client = submitClient(http, sleeps);
        String key = submitKey("hold-concurrent");
        Map<String, Job> results = new ConcurrentHashMap<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        // The kit's j16 starts the second submit 300 ms after the first.
        List<Thread> threads = new ArrayList<>();
        for (String name : List.of("first", "second")) {
            long delay = "first".equals(name) ? 0 : 300;
            threads.add(new Thread(() -> {
                try {
                    Thread.sleep(delay);
                    results.put(name, submitPdf(client, DocFile.bytes(kitPdf(), "hold-1500.pdf"),
                            submitQuery("hold-concurrent"), key));
                } catch (Throwable t) {
                    errors.add(t);
                }
            }, "hold-" + name));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(60_000);
        }
        assertEquals(List.of(), errors);
        assertEquals(results.get("first").jobId(), results.get("second").jobId());
        assertEquals(key, results.get("first").idempotencyKey());
        assertEquals(key, results.get("second").idempotencyKey());
        List<Integer> statuses = http.statuses();
        assertTrue(statuses.contains(409), statuses.toString());
        List<String> replayed = new ArrayList<>();
        for (Seen s : http.seen()) {
            assertEquals(List.of(key), s.keys);
            if (s.status == 409) {
                assertEquals("1", s.header("retry-after"));
            }
            if (s.status == 202) {
                replayed.add(String.valueOf(s.header("idempotent-replayed")));
            }
        }
        Collections.sort(replayed);
        // One fresh 202 (the first submit) and one replay (the second, after its 409s).
        assertEquals(List.of("null", "true"), replayed);
        assertFalse(sleeps.isEmpty());
        for (Duration d : sleeps) {
            assertEquals(Duration.ofSeconds(1), d);
        }
        System.out.println("JOB_SUBMIT_COUNTS hold-concurrent " + statuses);
        System.out.println("JOB_SUBMIT_OK hold-concurrent");
    }

    @Test
    void aNonReplayableStreamOnPersistUnknownThrowsThe503WithTheKeyAfterOneRequest() throws IOException {
        if (base == null) {
            return;
        }
        CapturingClient http = new CapturingClient();
        List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
        DocQL client = submitClient(http, sleeps);
        String key = submitKey("non-replayable");
        byte[] pdf = kitPdf();
        Path file = Files.createTempFile("p26-10-non-replayable", ".pdf");
        try {
            Files.write(file, pdf);
            try (FileInputStream in = new FileInputStream(file.toFile())) {
                assertFalse(in.markSupported(), "a FileInputStream cannot be reset to its start");
                DocQLException e = assertThrows(DocQLException.class,
                        () -> submitPdf(client, DocFile.stream(in, pdf.length, "persist-unknown.pdf"),
                                submitQuery("non-replayable"), key));
                assertEquals(503, e.status());
                assertEquals(key, e.idempotencyKey());
                assertTrue(in.getChannel().isOpen(), "the caller's stream is never closed");
            }
        } finally {
            Files.deleteIfExists(file);
        }
        assertEquals(List.of(503), http.statuses());
        assertEquals(List.of(), sleeps);
        System.out.println("JOB_SUBMIT_OK non-replayable");
    }

    // --- the capturing transport ----------------------------------------------------------------

    /** One request the capturing client sent and the answer it got. */
    static final class Seen {

        final String method;
        final String rawPath;
        final List<String> keys;
        final int status;
        final HttpHeaders headers;
        final byte[] body;

        Seen(String method, String rawPath, List<String> keys, int status, HttpHeaders headers, byte[] body) {
            this.method = method;
            this.rawPath = rawPath;
            this.keys = keys;
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        Jobs.Answer answer() {
            return new Jobs.Answer(status, headers, body);
        }
    }

    /**
     * The SDK's own transport settings (HTTP/1.1, no redirects, 10 s
     * connect), plus a record of every request and its answer. The SDK reads
     * every body as bytes, so the record keeps the exact bytes it decoded.
     */
    static final class CapturingClient extends HttpClient {

        private final HttpClient delegate = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        private final List<Seen> seen = new CopyOnWriteArrayList<>();

        List<Seen> seen() {
            return seen;
        }

        List<Integer> statuses() {
            List<Integer> statuses = new ArrayList<>();
            for (Seen s : seen) {
                statuses.add(s.status);
            }
            return statuses;
        }

        List<List<String>> keys() {
            List<List<String>> keys = new ArrayList<>();
            for (Seen s : seen) {
                keys.add(s.keys);
            }
            return keys;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            HttpResponse<T> response = delegate.send(request, handler);
            Object body = response.body();
            seen.add(new Seen(request.method(), request.uri().getRawPath(),
                    request.headers().allValues("Idempotency-Key"), response.statusCode(), response.headers(),
                    body instanceof byte[] ? (byte[]) body : new byte[0]));
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException("the job methods are synchronous");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
            throw new UnsupportedOperationException("the job methods are synchronous");
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
    }

    private static String sub(String text) {
        return text.replace("{run}", RUN);
    }
}
