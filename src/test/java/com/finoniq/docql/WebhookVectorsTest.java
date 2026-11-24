package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The shared console webhook vectors (D-16). {@code testdata/webhook-vectors.json}
 * is a byte copy of docql-console {@code packages/webhooks/webhook-vectors.json}
 * (version 1, frozen in 45.1-01): the console's real signer and payload
 * builders made it, so these cases prove the helper agrees with what the
 * console sends, as the Python and Go SDKs do. A regenerated console file
 * changes both pinned values, and the copy here must then be replaced byte
 * for byte. Every case runs through the package-private
 * {@code Webhooks.verifyAt} seam at the case's own clock.
 */
class WebhookVectorsTest {

    private static final String VECTORS_SHA256 = "63bbaeb4063ebcbd98fd4535121d29333589efddc85a6a9c6eb5419e7b186bbd";
    private static final int VECTORS_CASE_COUNT = 123;
    private static final Path VECTORS = Path.of("testdata", "webhook-vectors.json");

    private static byte[] rawFile() {
        try {
            return Files.readAllBytes(VECTORS);
        } catch (IOException e) {
            throw new UncheckedIOException("read " + VECTORS, e);
        }
    }

    /** The vector file on the SDK's own mapper, so expected data compares node for node. */
    private static JsonNode vectorFile() {
        JsonNode file = Decoder.parseTree(rawFile());
        assertNotNull(file, VECTORS + " is not JSON");
        return file;
    }

    /**
     * The vendored copy is byte-identical to the frozen console file
     * (sha256), version 1, with exactly the pinned number of unique cases.
     */
    @Test
    void vectorFilePin() throws NoSuchAlgorithmException {
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawFile()));
        assertEquals(VECTORS_SHA256, sha256, "WEBHOOK_VECTORS_MISMATCH: the vendored sha256 differs from the pin");
        JsonNode file = vectorFile();
        assertEquals("docql-webhook-vectors", file.path("format").asText());
        assertEquals(1, file.path("version").asInt());
        Set<String> ids = new HashSet<>();
        for (JsonNode c : file.path("cases")) {
            ids.add(c.path("id").asText());
        }
        assertEquals(VECTORS_CASE_COUNT, file.path("cases").size(), "case count");
        assertEquals(VECTORS_CASE_COUNT, ids.size(), "unique case ids");
    }

    static Stream<Arguments> cases() {
        List<Arguments> out = new ArrayList<>();
        for (JsonNode c : vectorFile().path("cases")) {
            out.add(Arguments.argumentSet(c.path("id").asText(), c));
        }
        return out.stream();
    }

    /**
     * One case. The display name is the case id; surefire's XML report shows
     * the index, so a failure also names the case id in its message.
     */
    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("cases")
    void vector(JsonNode c) {
        try {
            runCase(c);
        } catch (AssertionError | RuntimeException e) {
            throw new AssertionError(c.path("id").asText() + ": " + e.getMessage(), e);
        }
    }

    private static void runCase(JsonNode c) {
        byte[] body = Base64.getDecoder().decode(c.path("body_base64").asText());
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (JsonNode pair : c.path("headers")) {
            String name = pair.get(0).textValue();
            List<String> previous = headers.put(name, List.of(pair.get(1).textValue()));
            assertNull(previous, "the vector repeats header name " + name + " exactly");
        }
        List<String> secrets = new ArrayList<>();
        for (JsonNode secret : c.path("secrets")) {
            secrets.add(secret.textValue());
        }
        double toleranceSeconds = c.path("tolerance_seconds").asDouble();
        long now = c.path("now").asLong();
        JsonNode expect = c.path("expect");

        // The 300 s cases take the three-argument path, so the default is what they exercise.
        Runnable call;
        WebhookEvent[] got = new WebhookEvent[1];
        if (toleranceSeconds == 300) {
            call = () -> got[0] = Webhooks.verifyAt(body, headers, secrets, now);
        } else {
            Duration tolerance = Duration.ofNanos(Math.round(toleranceSeconds * 1e9));
            call = () -> got[0] = Webhooks.verifyAt(body, headers, secrets, tolerance, now);
        }

        switch (expect.path("result").asText()) {
            case "event":
                call.run();
                checkEvent(got[0], expect.path("event"));
                break;
            case "webhook_error": {
                WebhookVerificationException e = assertThrows(WebhookVerificationException.class, call::run);
                assertEquals(expect.path("reason").asText(), e.reason(), "reason");
                assertNoEcho(e.getMessage(), c);
                break;
            }
            case "validation_error": {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call::run);
                String message = e.getMessage();
                switch (expect.path("argument").asText()) {
                    case "tolerance":
                        assertTrue(message.contains("tolerance"), message + " names the tolerance");
                        break;
                    case "secrets":
                        assertTrue(message.contains("secret"), message + " names the secrets");
                        assertFalse(message.contains("tolerance"), message + " is not about the tolerance");
                        break;
                    default:
                        fail("unknown expect.argument " + expect.path("argument"));
                }
                if (expect.has("secret_index")) {
                    String want = "secret " + expect.path("secret_index").asInt();
                    assertTrue(message.contains(want), message + " names " + want);
                }
                assertNoEcho(message, c);
                break;
            }
            default:
                fail("unknown expect.result " + expect.path("result"));
        }
    }

    private static void checkEvent(WebhookEvent event, JsonNode want) {
        assertNotNull(event, "an event");
        assertEquals(want.path("id").textValue(), event.id(), "id");
        assertEquals(want.path("type").textValue(), event.type(), "type");
        assertEquals(want.path("timestamp_epoch_ms").asLong(), event.timestamp().toEpochMilli(), "timestamp");
        assertEquals(textOrNull(want.path("job_id")), event.jobId(), "jobId");
        assertEquals(textOrNull(want.path("status")), event.status(), "status");
        JsonNode data = event.data();
        assertNotNull(data, "data is never null");
        assertTrue(data.isObject(), "data is a JSON object");
        assertEquals(want.path("data"), data, "data");
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.textValue() : null;
    }

    /**
     * An error text never carries a secret (its text or any 6-character
     * window of its base64 part) or a 6-character piece of a received
     * signature.
     */
    private static void assertNoEcho(String message, JsonNode c) {
        int position = 0;
        for (JsonNode node : c.path("secrets")) {
            position++;
            String secret = node.textValue();
            String text = trim(secret);
            if (text.length() > "whsec_".length()) {
                assertFalse(message.contains(text), "the message carries secret " + position);
            }
            int at = secret.indexOf("whsec_");
            String base64 = at >= 0 ? secret.substring(at + "whsec_".length()) : secret;
            for (String window : windows(base64)) {
                assertFalse(message.contains(window), "the message carries a piece of secret " + position);
            }
        }
        for (JsonNode pair : c.path("headers")) {
            if (!pair.get(0).textValue().equalsIgnoreCase("webhook-signature")) {
                continue;
            }
            for (String entry : pair.get(1).textValue().split(" ", -1)) {
                String digest = entry.startsWith("v1,") ? entry.substring(3) : entry;
                for (String window : windows(digest)) {
                    assertFalse(message.contains(window), "the message carries a piece of a signature");
                }
            }
        }
    }

    private static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && " \t\r\n".indexOf(text.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && " \t\r\n".indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(start, end);
    }

    private static List<String> windows(String text) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 6 <= text.length(); i++) {
            out.add(text.substring(i, i + 6));
        }
        return out;
    }
}
