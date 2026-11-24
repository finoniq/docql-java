package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The webhook helper's API contract beyond the shared vectors (D-06..D-16,
 * D-25..D-29, OD-45.1-01): argument validation and its order, the event's
 * immutability, the exception's serialized form, the header map shapes a
 * Java receiver passes, the body timestamp rules, and a seeded mutation run
 * proving that request data only ever gives a WebhookEvent or a
 * WebhookVerificationException. The HMAC here is computed with javax.crypto;
 * the shared vectors stay the cross-language truth.
 */
class WebhooksTest {

    private static final long NOW = 1_795_000_000L;
    private static final byte[] KEY = key(7);
    private static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString(KEY);
    private static final String ID = "5f0c3a1e-45a1-4d1e-9b00-000000000451";
    private static final String BODY = "{\"id\":\"" + ID + "\",\"type\":\"job.succeeded\","
            + "\"timestamp\":\"2026-11-17T03:04:05.678Z\",\"data\":{\"job_id\":\"job_1\",\"status\":\"succeeded\"}}";

    private static final List<String> REASONS = List.of("missing_header", "invalid_timestamp",
            "invalid_signature_header", "signature_mismatch", "timestamp_too_old", "timestamp_too_new",
            "invalid_payload");

    private static byte[] key(int seed) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (seed * 31 + i * 7 + 0x3d);
        }
        return key;
    }

    /** {@code v1,<base64 HMAC-SHA256 of id.timestamp.body>}, the console signer's form. */
    private static String sign(byte[] key, String id, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, List<String>> headers(String id, String timestamp, String signature) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("webhook-id", List.of(id));
        headers.put("webhook-timestamp", List.of(timestamp));
        headers.put("webhook-signature", List.of(signature));
        return headers;
    }

    /** A delivery of {@code body} signed now with the test key. */
    private static Map<String, List<String>> signed(byte[] body) {
        return headers(ID, Long.toString(NOW), sign(KEY, ID, Long.toString(NOW), body));
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static WebhookEvent verify(byte[] body, Map<String, List<String>> headers) {
        return Webhooks.verifyAt(body, headers, List.of(SECRET), NOW);
    }

    private static String reason(byte[] body, Map<String, List<String>> headers) {
        return assertThrows(WebhookVerificationException.class, () -> verify(body, headers)).reason();
    }

    /** A body like BODY whose timestamp is {@code timestamp}, signed now. */
    private static WebhookVerificationException payloadTimestamp(String timestamp) {
        byte[] body = utf8(BODY.replace("2026-11-17T03:04:05.678Z", timestamp));
        return assertThrows(WebhookVerificationException.class, () -> verify(body, signed(body)), timestamp);
    }

    @Test
    void eventTypeConstants() {
        assertEquals("job.succeeded", WebhookEventTypes.JOB_SUCCEEDED);
        assertEquals("job.failed", WebhookEventTypes.JOB_FAILED);
        assertEquals("job.cancelled", WebhookEventTypes.JOB_CANCELLED);
        assertEquals("webhook.test", WebhookEventTypes.WEBHOOK_TEST);
        assertEquals(0, WebhookEventTypes.class.getConstructors().length, "no public constructor");
        assertTrue(Modifier.isFinal(WebhookEventTypes.class.getModifiers()));
    }

    @Test
    void validDeliveryGivesTheEvent() {
        byte[] body = utf8(BODY);
        WebhookEvent event = verify(body, signed(body));
        assertEquals(ID, event.id());
        assertEquals(WebhookEventTypes.JOB_SUCCEEDED, event.type());
        assertEquals(1_794_884_645_678L, event.timestamp().toEpochMilli());
        assertEquals("job_1", event.jobId());
        assertEquals(JobStatus.SUCCEEDED, event.status());
        assertEquals("succeeded", event.data().path("status").textValue());
    }

    @Test
    void publicOverloadsUseTheWallClock() {
        String now = Long.toString(System.currentTimeMillis() / 1000);
        byte[] body = utf8(BODY);
        Map<String, List<String>> headers = headers(ID, now, sign(KEY, ID, now, body));
        assertEquals(ID, Webhooks.verify(body, headers, List.of(SECRET)).id());
        assertEquals(ID, Webhooks.verify(body, headers, List.of(SECRET), Duration.ofSeconds(60)).id());
        Map<String, List<String>> stale = headers(ID, "1000", sign(KEY, ID, "1000", body));
        assertEquals("timestamp_too_old", assertThrows(WebhookVerificationException.class,
                () -> Webhooks.verify(body, stale, List.of(SECRET))).reason());
    }

    @Test
    void nullAndEmptyArgumentsAreIllegal() {
        byte[] body = utf8(BODY);
        Map<String, List<String>> headers = signed(body);
        List<String> secrets = List.of(SECRET);
        Duration tolerance = Duration.ofSeconds(300);
        assertIllegal("body", () -> Webhooks.verifyAt(null, headers, secrets, tolerance, NOW));
        assertIllegal("headers", () -> Webhooks.verifyAt(body, null, secrets, tolerance, NOW));
        assertIllegal("secrets", () -> Webhooks.verifyAt(body, headers, null, tolerance, NOW));
        assertIllegal("no webhook secrets", () -> Webhooks.verifyAt(body, headers, List.of(), tolerance, NOW));
        assertIllegal("secret 2", () -> Webhooks.verifyAt(body, headers, Arrays.asList(SECRET, null), tolerance,
                NOW));
        assertIllegal("tolerance", () -> Webhooks.verifyAt(body, headers, secrets, null, NOW));
        assertIllegal("tolerance", () -> Webhooks.verifyAt(body, headers, secrets, Duration.ZERO, NOW));
        assertIllegal("tolerance", () -> Webhooks.verifyAt(body, headers, secrets, Duration.ofNanos(-1), NOW));
        assertIllegal("tolerance", () -> Webhooks.verifyAt(body, headers, secrets, Duration.ofSeconds(-300), NOW));
        assertIllegal("body", () -> Webhooks.verify(null, headers, secrets));
        assertIllegal("tolerance", () -> Webhooks.verify(body, headers, secrets, null));
    }

    private static void assertIllegal(String named, Runnable call) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call::run, named);
        assertTrue(e.getMessage().contains(named), e.getMessage() + " names " + named);
    }

    @Test
    void halfSecondToleranceIsAcceptedAndComparesWholeSeconds() {
        byte[] body = utf8(BODY);
        Duration half = Duration.ofMillis(500);
        assertEquals(ID, Webhooks.verifyAt(body, signed(body), List.of(SECRET), half, NOW).id());
        assertEquals("timestamp_too_old", assertThrows(WebhookVerificationException.class,
                () -> Webhooks.verifyAt(body, signed(body), List.of(SECRET), half, NOW + 1)).reason());
        assertEquals("timestamp_too_new", assertThrows(WebhookVerificationException.class,
                () -> Webhooks.verifyAt(body, signed(body), List.of(SECRET), half, NOW - 1)).reason());
        Duration huge = Duration.ofSeconds(Long.MAX_VALUE, 999_999_999);
        assertEquals(ID, Webhooks.verifyAt(body, signed(body), List.of(SECRET), huge, 0).id());
    }

    /** D-14, D-26: tolerance first, then secrets, then the request, which is never read. */
    @Test
    void validationOrderIsToleranceSecretsThenRequest() {
        IllegalArgumentException tolerance = assertThrows(IllegalArgumentException.class,
                () -> Webhooks.verifyAt(null, null, Arrays.asList("nope", null), Duration.ZERO, NOW));
        assertTrue(tolerance.getMessage().contains("tolerance"), tolerance.getMessage());
        IllegalArgumentException secret = assertThrows(IllegalArgumentException.class,
                () -> Webhooks.verifyAt(null, null, List.of(SECRET, "nope"), Duration.ofSeconds(1), NOW));
        assertTrue(secret.getMessage().startsWith("webhook secret 2: "), secret.getMessage());
        assertFalse(secret.getMessage().contains("nope"), secret.getMessage());
        IllegalArgumentException secretBeforeHeaders = assertThrows(IllegalArgumentException.class,
                () -> Webhooks.verifyAt(utf8(BODY), new HashMap<>(), List.of("whsec_"), NOW));
        assertTrue(secretBeforeHeaders.getMessage().contains("secret 1"), secretBeforeHeaders.getMessage());
    }

    @Test
    void secretTrimIsExactlySpaceTabLfCr() {
        byte[] body = utf8(BODY);
        assertEquals(ID, Webhooks.verifyAt(body, signed(body), List.of("\t \r\n" + SECRET + "\r\n \t"), NOW).id());
        for (String pad : List.of("\u00a0", "\u2003", "\u000b", "\f", "\u0000", "\u3000", "\u2028", "\u0085")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Webhooks.verifyAt(body, signed(body), List.of(pad + SECRET), NOW), "leading U+"
                            + Integer.toHexString(pad.charAt(0)));
            assertTrue(e.getMessage().startsWith("webhook secret 1: "), e.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> Webhooks.verifyAt(body, signed(body), List.of(SECRET + pad), NOW), "trailing U+"
                            + Integer.toHexString(pad.charAt(0)));
        }
    }

    @Test
    void secretMessagesNameThePositionAndNeverTheSecret() {
        String base64 = SECRET.substring("whsec_".length());
        List<String> bad = List.of(
                base64,
                "WHSEC_" + base64,
                SECRET.substring(0, SECRET.length() - 1),
                "whsec_-" + base64.substring(1),
                SECRET.substring(0, 20) + " " + SECRET.substring(20),
                "whsec_" + Base64.getEncoder().encodeToString(new byte[23]),
                "whsec_" + Base64.getEncoder().encodeToString(new byte[65]));
        for (String secret : bad) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Webhooks.verifyAt(utf8(BODY), signed(utf8(BODY)), List.of(SECRET, secret), NOW));
            String message = e.getMessage();
            assertTrue(message.startsWith("webhook secret 2: "), message);
            String badBase64 = secret.startsWith("whsec_") ? secret.substring(6) : secret;
            for (String text : List.of(base64, badBase64)) {
                for (int i = 0; i + 6 <= text.length(); i++) {
                    assertFalse(message.contains(text.substring(i, i + 6)), message + " carries a secret");
                }
            }
        }
    }

    @Test
    void eventIsNotSerializableAndDataIsACopy() {
        assertFalse(Serializable.class.isAssignableFrom(WebhookEvent.class));
        byte[] body = utf8(BODY);
        WebhookEvent event = verify(body, signed(body));
        JsonNode first = event.data();
        ((ObjectNode) first).put("status", "tampered").put("extra", 1);
        JsonNode second = event.data();
        assertEquals("succeeded", second.path("status").textValue());
        assertFalse(second.has("extra"));
        assertEquals("succeeded", event.status());
    }

    @Test
    void webhookTestEventHasEmptyDataAndNoJobFields() {
        byte[] body = utf8("{\"id\":\"evt_t\",\"type\":\"webhook.test\",\"timestamp\":\"2026-11-17T03:04:05Z\","
                + "\"data\":{}}");
        WebhookEvent event = verify(body, signed(body));
        assertEquals(WebhookEventTypes.WEBHOOK_TEST, event.type());
        assertNull(event.jobId());
        assertNull(event.status());
        assertTrue(event.data().isObject() && event.data().isEmpty());
    }

    @Test
    void exceptionSerializesWithOnlyTheReason() throws Exception {
        Set<String> fields = new TreeSet<>();
        for (Field field : WebhookVerificationException.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                fields.add(field.getName() + ":" + field.getType().getSimpleName());
            }
        }
        assertEquals(Set.of("reason:String"), fields, "WebhookVerificationException keeps only the reason");
        assertEquals(RuntimeException.class, WebhookVerificationException.class.getSuperclass());

        byte[] body = utf8(BODY);
        Map<String, List<String>> headers = signed(body);
        WebhookVerificationException e = assertThrows(WebhookVerificationException.class,
                () -> Webhooks.verifyAt(utf8(BODY + " "), headers, List.of(SECRET), NOW));
        assertEquals("signature_mismatch", e.reason());
        assertNull(e.getCause());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(e);
        }
        String serialized = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
        assertFalse(serialized.contains(SECRET.substring(6, 20)), "the serialized form carries the secret");
        assertFalse(serialized.contains(headers.get("webhook-signature").get(0).substring(3, 20)),
                "the serialized form carries the signature");
        WebhookVerificationException copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (WebhookVerificationException) in.readObject();
        }
        assertEquals("signature_mismatch", copy.reason());
        assertEquals(e.getMessage(), copy.getMessage());
    }

    @Test
    void messagesAreTheReasonAndAFixedSentence() {
        Map<String, String> sentences = new TreeMap<>();
        for (String reason : REASONS) {
            String message = new WebhookVerificationException(reason).getMessage();
            assertTrue(message.startsWith(reason + ": "), message);
            sentences.put(reason, message);
        }
        assertEquals(REASONS.size(), Set.copyOf(sentences.values()).size(), "one sentence per reason");
        assertEquals("missing_header: a webhook-id, webhook-timestamp or webhook-signature header is absent, "
                + "empty, or repeated with different values", sentences.get("missing_header"));
        assertEquals("signature_mismatch: no v1 signature matches any secret (wrong secret or a changed body)",
                sentences.get("signature_mismatch"));
    }

    /** com.sun.net.httpserver.Headers normalizes names to "Webhook-id"; it still verifies. */
    @Test
    void httpServerHeadersWork() {
        byte[] body = utf8(BODY);
        Headers headers = new Headers();
        signed(body).forEach((name, values) -> headers.add(name, values.get(0)));
        headers.add("Content-Type", "application/json");
        assertFalse(headers.keySet().contains("webhook-id"), "Headers capitalizes the first letter of each name");
        assertEquals(ID, verify(body, headers).id());
    }

    /** HttpURLConnection.getHeaderFields() has a null key for the status line. */
    @Test
    void nullKeysListsAndValuesAreSkipped() {
        byte[] body = utf8(BODY);
        Map<String, List<String>> headers = new HashMap<>(signed(body));
        headers.put(null, List.of("HTTP/1.1 200 OK"));
        headers.put("x-null-list", null);
        headers.put("WEBHOOK-ID", Arrays.asList(null, "", ID));
        assertEquals(ID, verify(body, headers).id());

        Map<String, List<String>> nullIdList = new HashMap<>(signed(body));
        nullIdList.put("webhook-id", null);
        assertEquals("missing_header", reason(body, nullIdList));
    }

    @Test
    void headerNamesMatchOnlyInAscii() {
        byte[] body = utf8(BODY);
        Map<String, List<String>> kelvin = signed(body);
        kelvin.put("webhoo\u212a-id", kelvin.remove("webhook-id"));
        assertEquals("missing_header", reason(body, kelvin));
        Map<String, List<String>> longS = signed(body);
        longS.put("webhook-\u017fignature", longS.remove("webhook-signature"));
        assertEquals("missing_header", reason(body, longS));
        Map<String, List<String>> dotted = signed(body);
        dotted.put("webhook-\u0130d", dotted.remove("webhook-id"));
        assertEquals("missing_header", reason(body, dotted));
        Map<String, List<String>> mixed = new LinkedHashMap<>();
        signed(body).forEach((name, values) -> mixed.put(name.toUpperCase(java.util.Locale.ROOT), values));
        assertEquals(ID, verify(body, mixed).id());
    }

    @Test
    void repeatedHeaderValues() {
        byte[] body = utf8(BODY);
        String signature = signed(body).get("webhook-signature").get(0);
        Map<String, List<String>> sameId = signed(body);
        sameId.put("Webhook-Id", List.of(ID, ID));
        assertEquals(ID, verify(body, sameId).id());
        Map<String, List<String>> twoIds = signed(body);
        twoIds.put("Webhook-Id", List.of("other"));
        assertEquals("missing_header", reason(body, twoIds));
        Map<String, List<String>> twoStamps = signed(body);
        twoStamps.put("webhook-timestamp", List.of(Long.toString(NOW), Long.toString(NOW - 1)));
        assertEquals("missing_header", reason(body, twoStamps));
        Map<String, List<String>> split = signed(body);
        split.put("webhook-signature", List.of("v1,AAAA", signature));
        assertEquals(ID, verify(body, split).id());
        Map<String, List<String>> splitNames = signed(body);
        splitNames.put("webhook-signature", List.of("v2,x"));
        splitNames.put("Webhook-Signature", List.of(signature));
        assertEquals(ID, verify(body, splitNames).id());
    }

    @Test
    void timestampHeaderSyntax() {
        byte[] body = utf8(BODY);
        for (String stamp : List.of(" " + NOW, NOW + " ", "+" + NOW, "-1", "1795000000.5", "1.795e9",
                "\u0661\u0667\u0669\u0665", "\uff11\uff17", "1234567890123", "0x6AFC")) {
            Map<String, List<String>> headers = headers(ID, stamp, sign(KEY, ID, stamp, body));
            assertEquals("invalid_timestamp", reason(body, headers), stamp);
        }
        String twelve = "00" + NOW;
        assertEquals(12, twelve.length());
        assertEquals(ID, verify(body, headers(ID, twelve, sign(KEY, ID, twelve, body))).id(),
                "leading zeros are allowed and the HMAC covers the text as received");
    }

    /** An id the console cannot have signed (a lone surrogate) is signature_mismatch, never an encoding error. */
    @Test
    void loneSurrogateIdIsSignatureMismatch() {
        byte[] body = utf8(BODY);
        String id = "evt_\ud800";
        Map<String, List<String>> headers = headers(id, Long.toString(NOW), sign(KEY, "evt_?",
                Long.toString(NOW), body));
        assertEquals("signature_mismatch", reason(body, headers));
    }

    /** OD-45.1-01: year 0000 is invalid_payload in all three SDKs; year 0001 still verifies. */
    @Test
    void yearZeroBodyTimestampIsInvalidPayload() {
        assertEquals("invalid_payload", payloadTimestamp("0000-01-01T00:00:00Z").reason());
        assertEquals("invalid_payload", payloadTimestamp("0000-12-31T23:59:59.999Z").reason());
        byte[] body = utf8(BODY.replace("2026-11-17T03:04:05.678Z", "0001-01-01T00:00:00Z"));
        assertEquals(-62_135_596_800_000L, verify(body, signed(body)).timestamp().toEpochMilli());
    }

    /** Instant.parse alone takes 23:59:60 and 24:00:00; Python and Go refuse them. */
    @Test
    void bodyTimestampRanges() {
        for (String stamp : List.of("2026-11-17T23:59:60Z", "2026-11-17T24:00:00Z", "2026-11-17T23:60:00Z",
                "2026-02-30T00:00:00Z", "2026-02-29T00:00:00Z", "2026-13-01T00:00:00Z", "2026-00-10T00:00:00Z",
                "2026-11-00T00:00:00Z", "2026-11-17t03:04:05Z", "2026-11-17T03:04:05z", "2026-11-17T03:04:05.Z",
                "2026-11-17T03:04:05.1234567890Z", "2026-11-17T03:04:05+00:00", "2026-11-17 03:04:05Z",
                "+2026-11-17T03:04:05Z", "\u0662026-11-17T03:04:05Z", "2026-11-17T03:04:05Z\\n")) {
            assertEquals("invalid_payload", payloadTimestamp(stamp).reason(), stamp);
        }
        for (String stamp : List.of("2028-02-29T00:00:00Z", "9999-12-31T23:59:59.999999999Z",
                "2026-11-17T03:04:05.1Z")) {
            byte[] body = utf8(BODY.replace("2026-11-17T03:04:05.678Z", stamp));
            assertDoesNotThrow(() -> verify(body, signed(body)), stamp);
        }
    }

    @Test
    void bodyShapes() {
        for (String body : List.of("", " ", "null", "[]", "\"x\"", "{}", BODY + "x", BODY + "{}", "\ufeff" + BODY,
                BODY.replace("\"data\":{", "\"data\":NaN,\"d\":{"), BODY.replace("\"job.succeeded\"", "1"),
                "{\"id\":\"a\",\"type\":\"t\",\"timestamp\":\"2026-11-17T03:04:05Z\",\"data\":null}",
                "{\"id\":\"a\",\"type\":\"t\",\"timestamp\":\"2026-11-17T03:04:05Z\",\"data\":[]}",
                "{\"id\":\"a\",\"type\":\"t\",\"timestamp\":\"2026-11-17T03:04:05Z\"}",
                "{\"id\":\"a\",\"type\":\"\",\"timestamp\":\"2026-11-17T03:04:05Z\",\"data\":{}}",
                "{\"id\":\"\",\"type\":\"t\",\"timestamp\":\"2026-11-17T03:04:05Z\",\"data\":{}}",
                "{\"id\":\"a\",\"type\":\"t\",\"timestamp\":1794884645,\"data\":{}}",
                "{'id':'a','type':'t','timestamp':'2026-11-17T03:04:05Z','data':{}}",
                "{\"id\":\"a\",/*c*/\"type\":\"t\",\"timestamp\":\"2026-11-17T03:04:05Z\",\"data\":{}}")) {
            byte[] bytes = utf8(body);
            assertEquals("invalid_payload", reason(bytes, signed(bytes)), body);
        }
        byte[] utf16 = BODY.getBytes(StandardCharsets.UTF_16LE);
        assertEquals("invalid_payload", reason(utf16, signed(utf16)), "UTF-16 is never auto-detected");
        byte[] invalidUtf8 = utf8(BODY.replace("job_1", "job_\u00e9"));
        invalidUtf8[BODY.indexOf("job_1") + 4] = (byte) 0xff;
        assertEquals("invalid_payload", reason(invalidUtf8, signed(invalidUtf8)), "invalid UTF-8");
        String deep = "{\"id\":\"a\",\"type\":\"t\",\"timestamp\":\"2026-11-17T03:04:05Z\",\"data\":{\"x\":"
                + "[".repeat(600) + "]".repeat(600) + "}}";
        assertEquals("invalid_payload", reason(utf8(deep), signed(utf8(deep))), "nesting over 500");
        String longNumber = BODY.replace("\"job_1\"", "1" + "0".repeat(1500));
        assertEquals("invalid_payload", reason(utf8(longNumber), signed(utf8(longNumber))), "number over 1000");
    }

    @Test
    void unknownKeysAndTypesNeverFail() {
        byte[] body = utf8("{\"type\":\"job.expired\",\"extra\":{\"k\":[1]},\"data\":{\"status\":7,\"new\":true,"
                + "\"job_id\":null},\"timestamp\":\"2026-11-17T03:04:05.123456789Z\",\"id\":\"evt_x\"}");
        WebhookEvent event = verify(body, signed(body));
        assertEquals("job.expired", event.type());
        assertNull(event.jobId());
        assertNull(event.status());
        assertTrue(event.data().path("new").booleanValue());
        assertEquals(123_456_789, event.timestamp().getNano());
    }

    /**
     * Request data (body bytes, header names and values) can only give an
     * event or a WebhookVerificationException: 2,000 seeded mutations of a
     * valid delivery, half of the body and timestamp mutations re-signed so
     * the later checks see hostile input too, reach every reason and never
     * any other exception or a message carrying the secret or a signature.
     */
    @Test
    void mutationsOnlyGiveAnEventOrAVerificationException() {
        Random random = new Random(451_09L);
        Map<String, Integer> outcomes = new TreeMap<>();
        String secretPiece = SECRET.substring(6, 18);
        for (int round = 0; round < 2_000; round++) {
            byte[] body = utf8(BODY);
            String id = ID;
            String stamp = Long.toString(NOW);
            boolean resign = random.nextBoolean();
            List<String[]> pairs = new ArrayList<>();
            int kind = random.nextInt(12);
            switch (kind) {
                case 0 -> body = flipBytes(random, body);
                case 1 -> body = Arrays.copyOf(body, random.nextInt(body.length + 1));
                case 2 -> body = utf8(BODY + junk(random));
                case 3 -> body = utf8(BODY.replace(randomPiece(random, BODY), junk(random)));
                case 4 -> stamp = Long.toString(NOW + random.nextInt(1_201) - 600);
                case 5 -> stamp = junk(random);
                case 6 -> id = junk(random);
                default -> { }
            }
            String signature = resign || kind > 6 ? sign(KEY, strictish(id), stamp, body) : sign(KEY, ID,
                    Long.toString(NOW), utf8(BODY));
            pairs.add(new String[] {"webhook-id", id});
            pairs.add(new String[] {"webhook-timestamp", stamp});
            pairs.add(new String[] {"webhook-signature", signature});
            switch (kind) {
                case 7 -> pairs.remove(random.nextInt(pairs.size()));
                case 8 -> {
                    String[] pair = pairs.get(random.nextInt(pairs.size()));
                    pairs.add(new String[] {caseVariant(random, pair[0]), random.nextBoolean() ? pair[1]
                            : junk(random)});
                }
                case 9 -> {
                    String[] pair = pairs.get(random.nextInt(pairs.size()));
                    pair[0] = lookAlike(random, pair[0]);
                }
                case 10 -> {
                    String[] pair = pairs.get(2);
                    pair[1] = mutateSignature(random, pair[1]);
                }
                case 11 -> {
                    String[] pair = pairs.get(random.nextInt(pairs.size()));
                    pair[1] = random.nextBoolean() ? "" : pair[1] + junk(random);
                }
                default -> { }
            }
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (String[] pair : pairs) {
                headers.computeIfAbsent(pair[0], k -> new ArrayList<>()).add(pair[1]);
            }
            String outcome;
            try {
                WebhookEvent event = Webhooks.verifyAt(body, headers, List.of(SECRET), NOW);
                assertNotNull(event);
                assertNotNull(event.data());
                outcome = "event";
            } catch (WebhookVerificationException e) {
                assertTrue(REASONS.contains(e.reason()), e.reason());
                assertFalse(e.getMessage().contains(secretPiece), "the message carries the secret");
                assertFalse(e.getMessage().contains(signature.substring(3, 15)), "the message carries the signature");
                outcome = e.reason();
            } catch (RuntimeException | Error e) {
                fail("round " + round + " (kind " + kind + ") escaped with " + e, e);
                return;
            }
            outcomes.merge(outcome, 1, Integer::sum);
        }
        for (String reason : REASONS) {
            assertTrue(outcomes.containsKey(reason), "the mutation run reaches " + reason + ": " + outcomes);
        }
        assertTrue(outcomes.containsKey("event"), "some mutations still verify: " + outcomes);
    }

    /** The id the signer would see: Java's lenient encoder turns a lone surrogate into '?'. */
    private static String strictish(String id) {
        return new String(id.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static byte[] flipBytes(Random random, byte[] body) {
        byte[] out = body.clone();
        for (int i = 0, n = 1 + random.nextInt(3); i < n; i++) {
            out[random.nextInt(out.length)] = (byte) random.nextInt(256);
        }
        return out;
    }

    private static final String[] JUNK = {" ", "\t", "\n", "\u0000", "\u00a0", "\ud800", "\udfff", "\ufeff",
        "\u212a", "\u0661", "x", "{", "}", "[", "]", "\"", ",", ":", "NaN", "-1", "1e999", "0", "v1,", "v2,",
        "=", "+", "/", "\\u0000", "\\ud800", "\u0e01", "\uff10", "whsec_", "null", "true"};

    private static String junk(Random random) {
        StringBuilder out = new StringBuilder();
        for (int i = 0, n = 1 + random.nextInt(4); i < n; i++) {
            out.append(JUNK[random.nextInt(JUNK.length)]);
        }
        return out.toString();
    }

    private static String randomPiece(Random random, String text) {
        int start = random.nextInt(text.length());
        return text.substring(start, Math.min(text.length(), start + 1 + random.nextInt(8)));
    }

    private static String caseVariant(Random random, String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            out.append(random.nextBoolean() ? Character.toUpperCase(c) : c);
        }
        return out.toString();
    }

    private static String lookAlike(Random random, String name) {
        String[][] swaps = {{"k", "\u212a"}, {"s", "\u017f"}, {"i", "\u0130"}, {"-", "\u2010"}, {"e", "\u0435"}};
        String[] swap = swaps[random.nextInt(swaps.length)];
        return name.contains(swap[0]) ? name.replaceFirst(swap[0], swap[1]) : name + "\u00a0";
    }

    private static String mutateSignature(Random random, String signature) {
        switch (random.nextInt(6)) {
            case 0:
                return signature.substring(0, signature.length() - 1 - random.nextInt(4));
            case 1:
                return signature.replace("v1,", "V1,");
            case 2:
                return signature.replace('+', '-').replace('/', '_');
            case 3:
                return signature + "\n";
            case 4:
                return "v1," + Base64.getEncoder().encodeToString(key(random.nextInt(100)));
            default:
                return signature.replace("=", "");
        }
    }
}
