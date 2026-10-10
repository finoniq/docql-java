package com.finoniq.docql;

import java.io.IOException;
import java.io.InputStream;

/**
 * A bounded, non-closing view of a caller's stream (D-03). It reads at most
 * the declared number of bytes from the caller's current position, makes
 * {@link #close()} a no-op — {@code BodyPublishers.ofInputStream} would close
 * the caller's stream — and, if the underlying stream ends before the declared
 * length, throws a {@link ShortStreamException} instead of returning an early
 * EOF, so a request can never undersend silently.
 */
final class BoundedInputStream extends InputStream {

    private final InputStream in;
    private final long declared;
    private long remaining;

    BoundedInputStream(InputStream in, long declared) {
        this.in = in;
        this.declared = declared;
        this.remaining = declared;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int value = in.read();
        if (value < 0) {
            throw shortRead();
        }
        remaining--;
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (remaining <= 0) {
            return -1;
        }
        int n = in.read(buffer, offset, (int) Math.min((long) length, remaining));
        if (n < 0) {
            throw shortRead();
        }
        remaining -= n;
        return n;
    }

    /** Never closes the caller's stream (D-03). */
    @Override
    public void close() {
        // no-op on purpose: the stream belongs to the caller
    }

    private ShortStreamException shortRead() {
        long read = declared - remaining;
        return new ShortStreamException(
                "the stream ended after " + read + " of " + declared + " declared bytes");
    }
}
