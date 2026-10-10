package com.finoniq.docql;

import java.util.Objects;

/**
 * Per-call options of a queryDocument call. Every value is optional: an
 * absent mode omits {@code params.mode} from the request, and an absent
 * filename keeps the {@link DocFile}'s own name (D-02).
 */
public final class QueryOptions {

    private final Mode mode;
    private final String filename;

    private QueryOptions(Builder builder) {
        this.mode = builder.mode;
        this.filename = builder.filename;
    }

    /** Returns a builder for the options. */
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

    /** Builder of {@link QueryOptions}. */
    public static final class Builder {

        private Mode mode;
        private String filename;

        private Builder() {
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

        /** Builds the immutable options. */
        public QueryOptions build() {
            return new QueryOptions(this);
        }
    }
}
