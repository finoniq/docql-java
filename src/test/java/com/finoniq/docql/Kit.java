package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

/**
 * Loads the vendored contract kit ({@code contract/sdk-cases.json},
 * {@code contract/golden.json} and the golden .bin files) relative to the
 * project base directory, and builds the file bytes of a case. Test-scope
 * helper, never API. Files are always read as bytes — never in the default
 * charset, which the TIS-620 execution deliberately is not UTF-8.
 */
final class Kit {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Kit() {
    }

    static JsonNode sdkCases() {
        return load("contract/sdk-cases.json");
    }

    static JsonNode golden() {
        return load("contract/golden.json");
    }

    /** The golden request bytes, e.g. {@code golden/ascii-query.bin}. */
    static byte[] goldenBin(String relative) {
        return read(Path.of("contract", relative));
    }

    /** The file bytes of a golden case, e.g. {@code ascii-query}. */
    static byte[] goldenFileBytes(String id) {
        for (JsonNode c : golden().path("cases")) {
            if (id.equals(c.path("id").asText())) {
                return Base64.getDecoder().decode(c.path("fileBase64").asText());
            }
        }
        throw new IllegalArgumentException("golden case " + id + " not found");
    }

    /**
     * The file bytes of a case: base64 contents, or one byte repeated length
     * times.
     */
    static byte[] caseFileBytes(JsonNode fileSpec) {
        String base64 = fileSpec.path("base64").asText(null);
        if (base64 != null && !base64.isEmpty()) {
            return Base64.getDecoder().decode(base64);
        }
        byte[] pattern = fileSpec.path("repeatByte").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int length = fileSpec.path("length").asInt();
        byte[] data = new byte[length];
        Arrays.fill(data, pattern[0]);
        return data;
    }

    private static JsonNode load(String relative) {
        try {
            return MAPPER.readTree(read(Path.of(relative)));
        } catch (IOException e) {
            throw new UncheckedIOException("read " + relative, e);
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("read " + path, e);
        }
    }
}
