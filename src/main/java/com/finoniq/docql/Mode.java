package com.finoniq.docql;

import java.util.Objects;

/**
 * The extraction mode sent in the request's {@code params.mode}.
 *
 * <p>An open final class, not an enum: unknown values pass through and the
 * server validates them (D-04). When no mode is set, {@code mode} is omitted
 * from the request entirely.
 */
public final class Mode {

    /** Fast extraction. */
    public static final Mode FAST = new Mode("fast");

    /** Standard extraction (the engine's default). */
    public static final Mode STANDARD = new Mode("standard");

    private final String value;

    private Mode(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /**
     * Returns the mode with this wire value. Unknown values pass through and
     * the server validates them.
     *
     * @param value the wire value, e.g. {@code "fast"}; must not be null
     * @return the mode
     */
    public static Mode of(String value) {
        if (FAST.value.equals(value)) {
            return FAST;
        }
        if (STANDARD.value.equals(value)) {
            return STANDARD;
        }
        return new Mode(value);
    }

    /** Returns the wire value, e.g. {@code "fast"}. */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Mode other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "Mode[" + value + "]";
    }
}
