package com.finoniq.docql;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WR-02: a {@link DocQLException} that 0.2.0 serialized, before the
 * {@code metadata} field existed, still reads back with a usable
 * {@link DocQLException#metadata()}. The stream is built from
 * {@link OldDocQLExcept}, which has the 0.2.0 field set, with its 32-byte
 * class name replaced by {@code com.finoniq.docql.DocQLException}; the
 * {@code serialVersionUID} is 1L on both sides, so Java serialization accepts
 * it and leaves the missing field at its default.
 */
class DocQLExceptionCompatTest {

    private static final byte[] OLD_NAME = "com.finoniq.docql.OldDocQLExcept".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW_NAME = "com.finoniq.docql.DocQLException".getBytes(StandardCharsets.UTF_8);

    @Test
    void aStreamWrittenBy020HasNonNullEmptyMetadata() throws Exception {
        assertEquals(OLD_NAME.length, NEW_NAME.length, "the stand-in name must keep the stream length");
        byte[] stream = serialize(new OldDocQLExcept(422, "spreadsheet_too_large", "req-020",
                Duration.ofSeconds(7), false, "too large", "{\"error_info\":\"too large\"}", "job_020", "idem-020"));
        assertEquals(1, occurrences(stream, OLD_NAME), "the class descriptor names the stand-in once");
        byte[] renamed = stream.clone();
        int at = indexOf(renamed, OLD_NAME);
        System.arraycopy(NEW_NAME, 0, renamed, at, NEW_NAME.length);

        Object read;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(renamed))) {
            read = in.readObject();
        }
        DocQLException e = assertInstanceOf(DocQLException.class, read);
        assertNotNull(e.metadata(), "metadata() is never null, also for a 0.2.0 stream");
        assertTrue(e.metadata().isObject(), "an object node");
        assertEquals(0, e.metadata().size(), "an empty object");
        assertTrue(e.metadata().path("limit").isMissingNode());
        assertEquals(422, e.status());
        assertEquals("spreadsheet_too_large", e.reason());
        assertEquals("req-020", e.requestId());
        assertEquals("job_020", e.jobId());
        assertEquals("idem-020", e.idempotencyKey());
    }

    private static byte[] serialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int occurrences(byte[] haystack, byte[] needle) {
        int count = 0;
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length && match; j++) {
                match = haystack[i + j] == needle[j];
            }
            if (match) {
                count++;
            }
        }
        return count;
    }
}
