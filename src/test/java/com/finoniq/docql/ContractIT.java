package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The contract runner (JAVA-06): every {@code via: sdk} case of the vendored
 * shared list runs through the same queryDocument path the tracer fixed,
 * against the digest-pinned stub booted by {@code scripts/stub-up.sh}. The
 * stub URL arrives in {@code DOCQL_STUB_URL}; with {@code DOCQL_REQUIRE_STUB=1}
 * a missing stub fails the run, otherwise the cases skip so plain unit runs
 * stay green. Assertions stay semantic: they never read the stub's extraction
 * state or any stub-prefixed metadata key, so a cached repeat passes like a
 * fresh call.
 */
class ContractIT {

    /** The strict-stub rule: REQUIRE_STUB=1 fails when no stub runs, else skip. */
    @Test
    void stubRequirement() {
        String base = stubBase();
        if (base == null) {
            Assumptions.assumeTrue(false,
                    "DOCQL_STUB_URL is not set: run through scripts/stub-up.sh --run to check the shared cases");
        }
    }

    @TestFactory
    Stream<DynamicTest> sdkCases() {
        String base = stubBase();
        if (base == null) {
            return Stream.empty();
        }
        JsonNode kit = Kit.sdkCases();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : kit.path("cases")) {
            if (!"sdk".equals(c.path("via").asText())) {
                continue;
            }
            String id = c.path("id").asText();
            tests.add(DynamicTest.dynamicTest(id, () -> runCase(kit, c, base)));
        }
        return tests.stream();
    }

    private static void runCase(JsonNode kit, JsonNode c, String base) {
        String id = c.path("id").asText();
        String key = "invalid".equals(c.path("key").asText())
                ? kit.path("keys").path("invalid").asText()
                : kit.path("keys").path("valid").asText();

        JsonNode request = c.path("request");
        JsonNode fileSpec = request.path("file");
        byte[] data = Kit.caseFileBytes(fileSpec);
        String name = fileSpec.path("name").asText();

        JsonNode query = request.get("query");
        Instruction instruction = query != null && !query.isNull()
                ? Instruction.query(query.asText())
                : Instruction.prompt(request.path("prompt").asText());

        QueryOptions.Builder options = QueryOptions.builder().filename(name);
        JsonNode mode = request.get("mode");
        if (mode != null && !mode.isNull()) {
            options.mode(Mode.of(mode.asText()));
        }

        DocQL client = DocQL.builder()
                .apiKey(key)
                .apiUrl(base)
                .build();

        JsonNode expect = c.path("expect");
        int wantStatus = expect.path("status").asInt();

        if (wantStatus == 200) {
            QueryResult result;
            try {
                result = client.queryDocument(DocFile.bytes(data, name), instruction, options.build());
            } catch (DocQLException e) {
                fail("expected status 200, got error: " + e.getMessage());
                return;
            }
            assertNotNull(result, "expected a result");
            assertEquals(expect.path("pageCount").asInt(), result.pageCount(), "page_count");
            assertEquals(expect.path("pagesOcr").asInt(), result.pagesOcr(), "pages_ocr");
            assertNotNull(result.requestId(), "request_id must be present on a 200 envelope");
            assertFalse(result.requestId().isEmpty(), "request_id must be present on a 200 envelope");
        } else {
            DocQLException e;
            try {
                client.queryDocument(DocFile.bytes(data, name), instruction, options.build());
                fail("expected status " + wantStatus + ", got a successful result");
                return;
            } catch (DocQLException de) {
                e = de;
            }
            assertEquals(wantStatus, e.status(), "status");
            JsonNode wantReason = expect.get("reason");
            if (wantReason != null && !wantReason.isNull()) {
                assertEquals(wantReason.asText(), e.reason(), "reason");
            }
            JsonNode retryAfter = expect.get("retryAfterSeconds");
            if (retryAfter != null && !retryAfter.isNull()) {
                assertEquals(Duration.ofSeconds(retryAfter.asLong()), e.retryAfter(), "retry_after");
            }
            assertEquals(wantStatus == 429 || wantStatus == 503, e.retryable(), "retryable");
            if (expect.path("requestIdPresent").asBoolean(false)) {
                assertNotNull(e.requestId(), "request_id must be present");
            }
        }
        System.out.println("CONTRACT_CASE_OK sdk " + id);
    }

    /**
     * The stub base URL, or null when no stub runs. With
     * {@code DOCQL_REQUIRE_STUB=1} a missing stub fails, naming the variable
     * and the script that boots it.
     */
    private static String stubBase() {
        String url = System.getenv("DOCQL_STUB_URL");
        if (url == null || url.isBlank()) {
            if ("1".equals(System.getenv("DOCQL_REQUIRE_STUB"))) {
                fail("DOCQL_REQUIRE_STUB=1 but DOCQL_STUB_URL is not set: "
                        + "run the cases through scripts/stub-up.sh --run");
            }
            return null;
        }
        return url.replaceAll("/+$", "");
    }
}
