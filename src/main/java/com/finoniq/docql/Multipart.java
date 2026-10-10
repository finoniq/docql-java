package com.finoniq.docql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Pure multipart framing of a queryDocument request (D-03): the file part
 * preamble and the body part epilogue around the raw file bytes. Every string
 * is encoded with an explicit {@link StandardCharsets#UTF_8} argument and
 * every line break is the literal two-byte CRLF — never the platform charset
 * or line separator. Byte-exact with the golden fixtures.
 */
final class Multipart {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Multipart() {
    }

    /**
     * Returns a fresh random multipart boundary: 16 random bytes as 32
     * lower-case hex characters.
     */
    static String randomBoundary() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Escapes exactly the three characters that would break the quoted
     * filename parameter of the file part (D-02): {@code "} becomes
     * {@code %22}, CR becomes {@code %0D} and LF becomes {@code %0A}. There
     * is no filename* form.
     */
    static String escapeFilename(String name) {
        return name.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
    }

    /**
     * Encodes the body part: query or prompt first, then {@code params.mode}
     * only when a mode is set; an empty query still writes {@code "query":""}.
     * Compact canonical JSON: UTF-8 bytes, no HTML escaping, no trailing
     * newline (D-03 of the wire contract).
     */
    static byte[] bodyJson(Instruction instruction, Mode mode) {
        ObjectNode body = MAPPER.createObjectNode();
        if (instruction.isPrompt()) {
            body.put("prompt", instruction.text());
        } else {
            body.put("query", instruction.text());
        }
        if (mode != null) {
            body.putObject("params").put("mode", mode.value());
        }
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalStateException("encoding an in-memory JSON tree cannot fail", e);
        }
    }

    /**
     * Returns the file part preamble: the opening boundary, the file part
     * header with the escaped filename and {@code application/octet-stream},
     * and the blank line before the raw file bytes.
     */
    static byte[] preamble(String boundary, String filename) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + escapeFilename(filename) + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns everything after the raw file bytes: the blank line, the body
     * part with the canonical JSON, and the closing boundary.
     */
    static byte[] epilogue(String boundary, byte[] bodyJson) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] header = ("\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"body\"\r\n"
                + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] closing = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        out.write(header, 0, header.length);
        out.write(bodyJson, 0, bodyJson.length);
        out.write(closing, 0, closing.length);
        return out.toByteArray();
    }

    /** Returns the request {@code Content-Type} for this boundary. */
    static String contentType(String boundary) {
        return "multipart/form-data; boundary=" + boundary;
    }
}
