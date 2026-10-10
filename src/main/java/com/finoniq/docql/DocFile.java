package com.finoniq.docql;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * One document input of a queryDocument call. The factories are the only way
 * to build one (D-03): an in-memory byte array, a file-system path, or a
 * caller-owned stream of known length — the known length is what keeps the
 * multipart body an exact {@code Content-Length} via
 * {@code BodyPublishers.concat}. There is no unknown-length stream factory:
 * relaxing later is compatible, removing an overload is not (D-21).
 */
public final class DocFile {

    enum Kind {
        /** The bytes were given in memory. */
        BYTES,
        /** The bytes live at a file-system path. */
        PATH,
        /** The bytes are read from a caller stream of known length. */
        STREAM
    }

    private final Kind kind;
    private final byte[] data;
    private final Path path;
    private final InputStream stream;
    private final long length;
    private final String name;

    private DocFile(Kind kind, byte[] data, Path path, InputStream stream, long length, String name) {
        this.kind = kind;
        this.data = data;
        this.path = path;
        this.stream = stream;
        this.length = length;
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
        return new DocFile(Kind.BYTES, Arrays.copyOf(data, data.length), null, null, 0, filename);
    }

    /**
     * A document from a file-system path. The basename of the path is the
     * default filename (override it verbatim through
     * {@link QueryOptions.Builder#filename(String)}); the length comes from
     * the file size and the body streams from disk via
     * {@code BodyPublishers.ofFile}. No I/O happens at the factory; the path
     * is checked to be a regular file before any byte is sent, because
     * {@code ofFile} alone would happily upload a directory's inode size.
     *
     * @param path the document file; must not be null
     * @return the document input
     */
    public static DocFile path(Path path) {
        Objects.requireNonNull(path, "path");
        return new DocFile(Kind.PATH, null, path, null, 0, null);
    }

    /**
     * A document from a caller-owned stream of known length (required by the
     * known-length body contract): the declared length gives an exact
     * {@code Content-Length} through {@code BodyPublishers.concat}. The SDK
     * reads the stream from its current position, reads at most
     * {@code length} bytes, and never closes it; a stream that ends before
     * its declared length fails the call with a
     * {@link DocQLConnectionException} instead of undersending silently.
     *
     * @param in the document bytes; must not be null
     * @param length the exact number of bytes to send; must be zero or greater
     * @param filename the name of the file part; must not be null
     * @return the document input
     */
    public static DocFile stream(InputStream in, long length, String filename) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(filename, "filename");
        if (length < 0) {
            throw new IllegalArgumentException("length must be zero or greater");
        }
        return new DocFile(Kind.STREAM, null, null, in, length, filename);
    }

    /** Returns which factory built this input. */
    Kind kind() {
        return kind;
    }

    /** Returns the file bytes (a private copy); only for {@link Kind#BYTES}. */
    byte[] data() {
        return data;
    }

    /** Returns the source path; only for {@link Kind#PATH}. */
    Path path() {
        return path;
    }

    /** Returns the caller's stream; only for {@link Kind#STREAM}. */
    InputStream stream() {
        return stream;
    }

    /** Returns the declared stream length; only for {@link Kind#STREAM}. */
    long length() {
        return length;
    }

    /** Returns the factory-given name of the file part; null for {@link Kind#PATH}. */
    String name() {
        return name;
    }
}
