package com.finoniq.docql;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * Maps a transport failure to the SDK's connection exception family
 * (D-06, D-07, D-08). The order matters: timeout before IOException. Every
 * message is composed by the SDK — never a copy of a JDK message, which may
 * be null, echo the key or carry internal selector names — scrubbed of the
 * key, and the cause is kept. When the known upload passes the API ceiling,
 * the message carries the size hint, because an early 413 can be lost as a
 * reset.
 */
final class Transport {

    private Transport() {
    }

    static RuntimeException mapFailure(Throwable failure, long fileLength, String key,
            String host, Duration timeout) {
        Throwable t = failure;
        while ((t instanceof CompletionException || t instanceof ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof DocQLException || t instanceof DocQLConnectionException) {
            return (RuntimeException) t;
        }
        String hint = fileLength > DocQL.MAX_UPLOAD_BYTES
                ? " (the upload is " + fileLength + " bytes; the API rejects files over 25 MiB)"
                : "";
        if (t instanceof HttpConnectTimeoutException) {
            return new DocQLTimeoutException(
                    Decoder.scrub("connect timed out after " + DocQL.CONNECT_TIMEOUT.toSeconds() + " s", key), t);
        }
        if (t instanceof HttpTimeoutException) {
            return new DocQLTimeoutException(
                    Decoder.scrub("request timed out after " + timeout.toSeconds() + " s" + hint, key), t);
        }
        if (t instanceof IOException) {
            return new DocQLConnectionException(
                    Decoder.scrub("connection to " + host + " failed: " + t.getClass().getSimpleName() + hint, key), t);
        }
        return new DocQLConnectionException(
                Decoder.scrub("request failed: " + t.getClass().getSimpleName(), key), t);
    }
}
