package com.finoniq.docql;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * One document input of a queryDocument call. The factories are the only way
 * to build one; each carries the name of the file part. This release carries
 * the bytes in memory; path and stream inputs arrive in a later version.
 */
public final class DocFile {

    enum Kind {
        /** The bytes were given in memory. */
        BYTES
    }

    private final Kind kind;
    private final byte[] data;
    private final String name;

    private DocFile(Kind kind, byte[] data, String name) {
        this.kind = kind;
        this.data = data;
        this.name = name;
    }

    /**
     * A document from an in-memory byte array. The array is copied, so later
     * changes to it never change a request already built.
     *
     * @param data the file bytes; must not be null
     * @param filename the name of the file part; must not be null
     * @return the document input
     */
    public static DocFile bytes(byte[] data, String filename) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(filename, "filename");
        return new DocFile(Kind.BYTES, Arrays.copyOf(data, data.length), filename);
    }

    /**
     * A document from a file-system path. Placeholder: the real factory
     * (basename default name, size length, {@code BodyPublishers.ofFile}
     * body) is implemented in the feat commit.
     *
     * @param path the document file; must not be null
     * @return the document input
     */
    public static DocFile path(Path path) {
        Objects.requireNonNull(path, "path");
        throw new UnsupportedOperationException("not implemented in this build");
    }

    /**
     * A document from a caller-owned stream of known length. Placeholder:
     * the real factory (bounded non-closing view, exact-length concat body)
     * is implemented in the feat commit.
     *
     * @param in the document bytes; must not be null
     * @param length the exact number of bytes to send; must not be negative
     * @param filename the name of the file part; must not be null
     * @return the document input
     */
    public static DocFile stream(InputStream in, long length, String filename) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(filename, "filename");
        throw new UnsupportedOperationException("not implemented in this build");
    }

    /** Returns which factory built this input. */
    Kind kind() {
        return kind;
    }

    /** Returns the file bytes (a private copy). */
    byte[] data() {
        return data;
    }

    /** Returns the name of the file part. */
    String name() {
        return name;
    }
}
