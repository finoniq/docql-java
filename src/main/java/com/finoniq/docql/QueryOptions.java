package com.finoniq.docql;

import java.util.Objects;

/**
 * Per-call options of a queryDocument or submitDocument call. Every value is
 * optional: an absent mode omits {@code params.mode} from the request, an
 * absent filename keeps the {@link DocFile}'s own name (D-02), and an absent
 * idempotency key lets submitDocument generate one.
 */
public final class QueryOptions {

    private final Mode mode;
    private final String filename;
    private final String idempotencyKey;

    private QueryOptions(Builder builder) {
        this.mode = builder.mode;
        this.filename = builder.filename;
        this.idempotencyKey = builder.idempotencyKey;
    }

    /**
     * Returns a builder for the options.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the extraction mode, or null when {@code params.mode} stays omitted. */
    Mode mode() {
        return mode;
    }

    /** Returns the filename override, or null when the DocFile name is used verbatim. */
    String filename() {
        return filename;
    }

    /** Returns the submit's Idempotency-Key, or null when submitDocument generates one. */
    String idempotencyKey() {
        return idempotencyKey;
    }

    /** Builder of {@link QueryOptions}. */
    public static final class Builder {

        private Mode mode;
        private String filename;
        private String idempotencyKey;

        private Builder() {
        }

        /**
         * Sets the {@code Idempotency-Key} of a
         * {@link DocQL#submitDocument(DocFile, Instruction, QueryOptions)}
         * call, e.g. to resubmit with the key a failed submit carried
         * ({@link DocQLException#idempotencyKey()}). When not called, the
         * submit generates a fresh key. DocQL accepts 1-255 printable ASCII
         * characters (0x20-0x7E); {@link #build()} checks that without ever
         * echoing the key. Only submitDocument takes it: queryDocument throws
         * {@link IllegalArgumentException} when it is set.
         *
         * @param idempotencyKey the key; must not be null
         * @return this builder
         */
        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            return this;
        }

        /**
         * Sets the extraction mode. When not called, {@code params.mode} is
         * omitted from the request.
         *
         * @param mode the mode; must not be null
         * @return this builder
         */
        public Builder mode(Mode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Overrides the name of the file part verbatim.
         *
         * @param filename the file-part name; must not be null
         * @return this builder
         */
        public Builder filename(String filename) {
            this.filename = Objects.requireNonNull(filename, "filename");
            return this;
        }

        /**
         * Builds the immutable options.
         *
         * @return the built options
         * @throws IllegalArgumentException if the idempotency key is not 1-255
         *         printable ASCII characters (the message never echoes it)
         */
        public QueryOptions build() {
            if (idempotencyKey != null) {
                Jobs.validateIdempotencyKey(idempotencyKey);
            }
            return new QueryOptions(this);
        }
    }
}
