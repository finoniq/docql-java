package com.finoniq.docql;

import java.time.Duration;
import java.util.Objects;

/**
 * Options of a {@link DocQL#waitFor(String, WaitOptions)} call. Every value
 * is optional: the wait gives up after 15 minutes by default, and the sleep
 * between polls follows the answers' {@code Retry-After} unless a poll
 * interval raises it.
 */
public final class WaitOptions {

    private final Duration timeout;
    private final Duration pollInterval;

    private WaitOptions(Builder builder) {
        this.timeout = builder.timeout;
        this.pollInterval = builder.pollInterval;
    }

    /**
     * Returns a builder for the options.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns how long the wait may take in total; 15 minutes unless set. */
    Duration timeout() {
        return timeout;
    }

    /** Returns the shortest sleep between polls, or null when only Retry-After decides. */
    Duration pollInterval() {
        return pollInterval;
    }

    /** Builder of {@link WaitOptions}. */
    public static final class Builder {

        private Duration timeout = Jobs.DEFAULT_WAIT;
        private Duration pollInterval;

        private Builder() {
        }

        /**
         * Sets how long the wait may take in total. When the next sleep
         * would pass it, {@link DocQL#waitFor(String, WaitOptions)} throws
         * {@link DocQLWaitTimeoutException}; the job is not cancelled.
         * Defaults to 15 minutes.
         *
         * @param timeout the wait deadline; must not be null, and
         *        {@link #build()} requires it to be positive
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /**
         * Sets the shortest sleep between polls: each sleep becomes
         * {@code max(pollInterval, the server's Retry-After)}, so it can
         * only slow the polling down, never speed it past what DocQL asks.
         *
         * @param pollInterval the shortest sleep; must not be null, and
         *        {@link #build()} requires it to be positive
         * @return this builder
         */
        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
            return this;
        }

        /**
         * Builds the immutable options.
         *
         * @return the built options
         * @throws IllegalArgumentException if the timeout or the poll
         *         interval is zero or negative
         */
        public WaitOptions build() {
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be greater than zero");
            }
            if (pollInterval != null && (pollInterval.isZero() || pollInterval.isNegative())) {
                throw new IllegalArgumentException("pollInterval must be greater than zero");
            }
            return new WaitOptions(this);
        }
    }
}
