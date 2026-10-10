package com.finoniq.docql;

import java.io.IOException;

/**
 * A caller-supplied stream delivered fewer bytes than its declared length
 * (D-03). {@link Transport} walks the cause chain for this type and maps it
 * to a {@link DocQLConnectionException} carrying this message — the JDK's own
 * "Too few bytes returned by the publisher" text is never surfaced.
 */
final class ShortStreamException extends IOException {

    private static final long serialVersionUID = 1L;

    ShortStreamException(String message) {
        super(message);
    }
}
