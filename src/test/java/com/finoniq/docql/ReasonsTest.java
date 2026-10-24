package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reason constants (D-04, D-09): {@link Reasons} holds exactly 22 public
 * static final String constants whose literal values are the stub
 * REASON_TEXTS, the gateway ERROR_CLASSES and the job reasons — pinned here
 * as literals, because the SDK repo must not reference the private console
 * sources, and proven equal to the reasons of the vendored kit files.
 */
class ReasonsTest {

    @Test
    void reasonsLiteral() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("INVALID_API_KEY", "invalid_api_key");
        expected.put("UPLOAD_TOO_LARGE", "upload_too_large");
        expected.put("UNSUPPORTED_MEDIA_TYPE", "unsupported_media_type");
        expected.put("MISSING_FILE_PART", "missing_file_part");
        expected.put("MISSING_BODY_FIELD", "missing_body_field");
        expected.put("BODY_NOT_JSON", "body_not_json");
        expected.put("MISSING_QUERY_OR_PROMPT", "missing_query_or_prompt");
        expected.put("INVALID_PARAMS", "invalid_params");
        expected.put("UNSUPPORTED_FILE_TYPE", "unsupported_file_type");
        expected.put("CORRUPTED_FILE", "corrupted_file");
        expected.put("DOCUMENT_EXCEEDS_TIME_BUDGET", "document_exceeds_time_budget");
        expected.put("SERVICE_OVERLOADED", "service_overloaded");
        expected.put("UPSTREAM_UNAVAILABLE", "upstream_unavailable");
        expected.put("UPSTREAM_AUTH_FAILED", "upstream_auth_failed");
        expected.put("CONCURRENCY_LIMIT_EXCEEDED", "concurrency_limit_exceeded");
        expected.put("RATE_LIMIT_EXCEEDED", "rate_limit_exceeded");
        expected.put("JOB_NOT_FOUND", "job_not_found");
        expected.put("JOB_EXPIRED", "job_expired");
        expected.put("QUEUE_FULL", "queue_full");
        expected.put("IDEMPOTENCY_KEY_CONFLICT", "idempotency_key_conflict");
        expected.put("IDEMPOTENCY_IN_PROGRESS", "idempotency_in_progress");
        expected.put("JOB_INTERRUPTED", "job_interrupted");

        List<Field> constants = constants();
        assertEquals(expected.size(), constants.size(),
                "Reasons holds exactly 22 public static final String constants, found " + constants);

        Set<String> values = new HashSet<>();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            Field field = Reasons.class.getField(entry.getKey());
            int mods = field.getModifiers();
            assertTrue(Modifier.isPublic(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods),
                    entry.getKey() + " must be a public static final String");
            assertEquals(String.class, field.getType(), entry.getKey());
            assertEquals(entry.getValue(), field.get(null), entry.getKey());
            values.add((String) field.get(null));
        }
        assertEquals(expected.size(), values.size(), "the constants must carry distinct values");
    }

    /**
     * The constant values are exactly the non-null reasons the two vendored
     * kit files name: sdk-cases.json cases and decodeCases, and job-cases.json
     * reasons.entries, decodeCases, every case step's reason and
     * embeddedReason, and waitCases.
     */
    @Test
    void reasonValuesEqualTheVendoredKitReasons() throws Exception {
        Set<String> kit = new TreeSet<>();
        JsonNode sdk = Kit.sdkCases();
        for (String section : List.of("cases", "decodeCases")) {
            for (JsonNode c : sdk.path(section)) {
                add(kit, c.path("expect").path("reason"));
            }
        }
        JsonNode job = Kit.jobCases();
        for (JsonNode entry : job.path("reasons").path("entries")) {
            add(kit, entry.path("reason"));
        }
        for (JsonNode c : job.path("decodeCases")) {
            add(kit, c.path("expect").path("reason"));
        }
        for (JsonNode c : job.path("cases")) {
            for (JsonNode step : c.path("steps")) {
                add(kit, step.path("expect").path("reason"));
                add(kit, step.path("expect").path("embeddedReason"));
            }
        }
        for (JsonNode c : job.path("waitCases")) {
            add(kit, c.path("expect").path("reason"));
        }

        Set<String> values = new TreeSet<>();
        for (Field field : constants()) {
            values.add((String) field.get(null));
        }
        assertEquals(kit, values);
    }

    private static void add(Set<String> into, JsonNode reason) {
        if (reason.isTextual()) {
            into.add(reason.asText());
        }
    }

    private static List<Field> constants() {
        List<Field> constants = new ArrayList<>();
        for (Field field : Reasons.class.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isPublic(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods)
                    && field.getType() == String.class) {
                constants.add(field);
            }
        }
        return constants;
    }
}
