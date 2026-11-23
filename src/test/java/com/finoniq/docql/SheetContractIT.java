package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every kit v5 spreadsheet case on both routes against the stub booted by
 * {@code scripts/stub-up.sh} (JAVA-08, SSDK-02). {@code contract/sheet-cases.json}
 * lists 26 cases, each with routes {@code ["sync", "jobs"]}, so the factory
 * runs 52 dynamic tests named {@code <id>@<route>}. {@code sync} calls
 * {@link DocQL#queryDocument(DocFile, Instruction, QueryOptions)};
 * {@code jobs} calls {@link DocQL#submitDocument(DocFile, Instruction, QueryOptions)}
 * and then {@link DocQL#waitFor(String)}, and a refusal must come from
 * submitDocument itself. The same strict-stub rule as {@link ContractIT}:
 * with {@code DOCQL_REQUIRE_STUB=1} a missing {@code DOCQL_STUB_URL} fails the
 * run, otherwise the stub tests skip. ContractIT's kit gate proves the served
 * sheet-cases.json equals the vendored one.
 *
 * <p>The SDK sends opaque bytes: the upload name goes out byte for byte
 * ({@code "a.csv "} keeps its trailing space, a zero-length file is sent as
 * such) and the SDK never checks a file's type, name or size; whether a file
 * is refused is the server's answer. Before any request, a fixture's vendored
 * bytes must match the sha256 the vendored manifest lists for it. For a 422
 * the error's {@link DocQLException#metadata()} must hold every
 * {@code expect.metadata} entry with the same value (the SSDK-02 proof).
 * Each passed run prints {@code SHEET_CONTRACT_CASE_OK <id> <route>}, and the
 * class prints {@code SHEET_CONTRACT_CASES_RAN <n>} once when all of them
 * passed; contract.yml requires both to equal the jq route total.
 */
class SheetContractIT {

    private static final List<String> KNOWN_ROUTES = List.of("sync", "jobs");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AtomicInteger RAN = new AtomicInteger();

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

    /** Every route of the vendored list is one this runner replays; a new one fails loudly. */
    @Test
    void everySheetRouteIsKnown() {
        List<String> unknown = new ArrayList<>();
        for (JsonNode c : Kit.sheetCases().path("cases")) {
            for (JsonNode route : c.path("routes")) {
                if (!KNOWN_ROUTES.contains(route.asText())) {
                    unknown.add(c.path("id").asText() + " (route " + route.asText() + ")");
                }
            }
        }
        assertEquals(List.of(), unknown, "sheet-cases.json carries unknown routes");
        assertTrue(runCount() > 0, "sheet-cases.json lists no case runs");
    }

    /** A fixture whose bytes drifted from the manifest fails before any request. */
    @Test
    void driftedFixtureFails(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("contract/sheets"));
        Files.write(root.resolve("contract/sheets/plain.csv"), "tampered\n".getBytes(StandardCharsets.UTF_8));
        String manifest = "{\"files\":{\"sheets/plain.csv\":\""
                + sha256Hex("original\n".getBytes(StandardCharsets.UTF_8)) + "\"}}";
        Files.write(root.resolve("contract/manifest.json"), manifest.getBytes(StandardCharsets.UTF_8));
        JsonNode spec = MAPPER.readTree("{\"name\":\"plain.csv\",\"fixture\":\"sheets/plain.csv\"}");
        AssertionError e = assertThrows(AssertionError.class, () -> fixtureBytes(root, spec));
        assertTrue(e.getMessage().startsWith("CONTRACT_KIT_MISMATCH sheets/plain.csv"), e.getMessage());
    }

    @TestFactory
    Stream<DynamicTest> sheetCasesAgainstTheStub() {
        if (base == null) {
            return Stream.empty();
        }
        // sheet-cases.json boots the stub with the job-cases.json recipe and its keys.
        String key = Kit.jobCases().path("keys").path("valid").asText();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : Kit.sheetCases().path("cases")) {
            String id = c.path("id").asText();
            for (JsonNode r : c.path("routes")) {
                String route = r.asText();
                tests.add(DynamicTest.dynamicTest(id + "@" + route, () -> {
                    runCase(key, c, route);
                    RAN.incrementAndGet();
                    System.out.println("SHEET_CONTRACT_CASE_OK " + id + " " + route);
                }));
            }
        }
        return tests.stream();
    }

    /**
     * The count gate: the runs that passed must equal the vendored route
     * total exactly, so a dropped or half-failing case never goes green.
     */
    @AfterAll
    static void sheetCountGate() {
        if (base == null) {
            return;
        }
        int want = runCount();
        assertEquals(want, RAN.get(), "ran " + RAN.get() + " of the " + want
                + " sheet case runs: a run stopped before its last assertion");
        System.out.println("SHEET_CONTRACT_CASES_RAN " + RAN.get());
    }

    private static int runCount() {
        int n = 0;
        for (JsonNode c : Kit.sheetCases().path("cases")) {
            n += c.path("routes").size();
        }
        return n;
    }

    private static void runCase(String key, JsonNode c, String route) {
        JsonNode request = c.path("request");
        JsonNode fileSpec = request.path("file");
        byte[] data = fixtureBytes(Path.of("."), fileSpec);
        String name = fileSpec.path("name").asText();
        JsonNode query = request.get("query");
        assertTrue(query != null && query.isTextual(), "the case carries no query");
        Instruction instruction = Instruction.query(query.asText());
        QueryOptions.Builder options = QueryOptions.builder();
        JsonNode mode = request.get("mode");
        if (mode != null && !mode.isNull()) {
            options.mode(Mode.of(mode.asText()));
        }
        DocQL client = DocQL.builder().apiKey(key).apiUrl(base).build();
        // The name is sent verbatim: never trimmed or normalised (SSDK-03).
        DocFile file = DocFile.bytes(data, name);

        JsonNode expect = c.path("expect");
        int wantStatus = expect.path("status").asInt();
        QueryResult result = null;
        DocQLException error = null;
        if ("sync".equals(route)) {
            try {
                result = client.queryDocument(file, instruction, options.build());
            } catch (DocQLException e) {
                error = e;
            }
        } else if ("jobs".equals(route)) {
            Job job = null;
            try {
                job = client.submitDocument(file, instruction, options.build());
            } catch (DocQLException e) {
                error = e;
            }
            if (job != null) {
                assertNotNull(job.jobId(), "submitDocument returned a job without an id");
                if (wantStatus != 200) {
                    fail("expected submitDocument to refuse with " + wantStatus + ", got job "
                            + job.jobId() + " (" + job.status() + ")");
                }
                Job done = client.waitFor(job.jobId());
                assertEquals(JobStatus.SUCCEEDED, done.status(), "job " + done.jobId() + " status");
                result = done.result();
                assertNotNull(result, "a succeeded job carries a result");
            }
        } else {
            fail("unknown route " + route);
        }

        if (wantStatus == 200) {
            if (error != null) {
                fail("expected status 200, got error: " + error.getMessage());
            }
            assertTrue(expect.has("page_count") && expect.has("pages_ocr") && expect.has("file_mimetype"),
                    "a 200 sheet case must name page_count, pages_ocr and file_mimetype");
            assertEquals(Integer.valueOf(expect.path("page_count").asInt()), result.pageCount(), "page_count");
            assertEquals(Integer.valueOf(expect.path("pages_ocr").asInt()), result.pagesOcr(), "pages_ocr");
            assertEquals(expect.path("file_mimetype").asText(),
                    result.metadata().path("file_mimetype").asText(null), "file_mimetype");
            return;
        }

        if (error == null) {
            fail("expected status " + wantStatus + ", got a successful result");
        }
        assertEquals(wantStatus, error.status(), "status");
        String wantReason = expect.path("reason").asText(null);
        assertNotNull(wantReason, "a refusal case must name its reason");
        assertEquals(wantReason, error.reason(), "reason");
        JsonNode metadata = error.metadata();
        assertNotNull(metadata, "metadata() is null");
        assertTrue(metadata.isObject(), "metadata() is not an object: " + metadata);
        assertEquals(wantReason, metadata.path("reason").asText(null), "metadata.reason");
        for (Map.Entry<String, JsonNode> entry : expect.path("metadata").properties()) {
            String k = entry.getKey();
            JsonNode want = entry.getValue();
            JsonNode got = metadata.get(k);
            assertNotNull(got, "metadata has no " + k + ": " + metadata);
            if (want.isNumber()) {
                assertTrue(got.isNumber(), "metadata." + k + " is not a number: " + got);
            }
            assertEquals(want.asText(), got.asText(), "metadata." + k);
            if (want.isIntegralNumber() && want.canConvertToLong()) {
                assertEquals(want.asLong(), got.asLong(), "metadata." + k + " as long");
            }
        }
    }

    /**
     * The upload bytes of a case file: inline base64 (zero-length allowed),
     * or a vendored fixture whose sha256 must equal its entry in
     * {@code root/contract/manifest.json}.
     */
    static byte[] fixtureBytes(Path root, JsonNode fileSpec) {
        JsonNode inline = fileSpec.get("base64");
        if (inline != null && inline.isTextual()) {
            return Base64.getDecoder().decode(inline.asText());
        }
        String fixture = fileSpec.path("fixture").asText("");
        if (fixture.isEmpty()) {
            throw new AssertionError("case file " + fileSpec.path("name").asText() + " has neither fixture nor base64");
        }
        try {
            byte[] data = Files.readAllBytes(root.resolve("contract").resolve(fixture));
            JsonNode manifest = MAPPER.readTree(Files.readAllBytes(root.resolve("contract/manifest.json")));
            String declared = manifest.path("files").path(fixture).asText("");
            String actual = sha256Hex(data);
            if (!actual.equals(declared)) {
                throw new AssertionError("CONTRACT_KIT_MISMATCH " + fixture + ": vendored sha256 " + actual
                        + ", manifest " + declared);
            }
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException("read fixture " + fixture, e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
