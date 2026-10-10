package com.finoniq.docql;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reason constants (D-04): {@link Reasons} holds exactly 14 public static
 * final String constants whose literal values are the stub REASON_TEXTS plus
 * the gateway ERROR_CLASSES — pinned here as literals, because the SDK repo
 * must not reference the private console sources.
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

        List<Field> constants = new ArrayList<>();
        for (Field field : Reasons.class.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isPublic(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods)
                    && field.getType() == String.class) {
                constants.add(field);
            }
        }
        assertEquals(expected.size(), constants.size(),
                "Reasons holds exactly 14 public static final String constants, found " + constants);

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
}
