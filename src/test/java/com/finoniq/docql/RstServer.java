package com.finoniq.docql;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A raw {@link ServerSocket} fake producing the transport behaviors neither
 * the JDK HttpServer nor the stub ever produce (RESEARCH Pitfall 5): a reset
 * in the middle of an upload — the early-413-as-reset path with its size hint
 * (D-08) — and a canned early response followed by a clean FIN (the typed-413
 * path). Every accepted connection is served from a script queue; connection
 * and write counts are recorded for the no-retry and bounded-read assertions.
 * Test scope only, never API.
 */
final class RstServer {

    /** One scripted behavior, served to the next accepted connection. */
    interface Script {
        void serve(Socket socket) throws IOException, InterruptedException;
    }

    private final ServerSocket serverSocket;
    private final Thread acceptLoop;
    private final BlockingQueue<Script> scripts = new LinkedBlockingQueue<>();
    private final AtomicLong connections = new AtomicLong();
    private static volatile long measuredBodyBytes = -1;

    RstServer() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        acceptLoop = new Thread(this::acceptLoop, "rst-server-accept");
        acceptLoop.setDaemon(true);
        acceptLoop.start();
    }

    /** The base URL of the fake, {@code http://127.0.0.1:<port>}. */
    String url() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort();
    }

    /** Adds the next script to the per-connection queue. */
    RstServer enqueue(Script script) {
        scripts.add(script);
        return this;
    }

    /** How many connections were accepted so far. */
    long connections() {
        return connections.get();
    }

    /**
     * Body bytes the measuring script wrote before the client stopped
     * reading; -1 until a measuring script ran.
     */
    static long measuredBodyBytes() {
        return measuredBodyBytes;
    }

    /** Stops the fake. */
    void close() throws InterruptedException {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // the fake is shutting down anyway
        }
        acceptLoop.join(2_000);
    }

    /**
     * Reads the request headers and then {@code bodyBytes} of the body, then
     * resets the connection: {@code setSoLinger(true, 0)} makes the next close
     * send an RST, so the client never sees a response (the D-08 reset path).
     */
    static Script resetAfter(long bodyBytes) {
        return socket -> {
            long total = contentLength(readHeaders(socket));
            drain(socket, Math.min(total, bodyBytes));
            try {
                socket.setSoLinger(true, 0);
            } catch (SocketException ignored) {
                // already gone
            }
            socket.close();
        };
    }

    /**
     * Reads the whole request, writes the raw {@code response} bytes, shuts
     * the output down (a clean FIN) and closes after {@code linger}, so the
     * client reliably reads the response — the typed-413 path (D-08).
     */
    static Script respondThenFin(byte[] response, Duration linger) {
        return socket -> {
            drain(socket, contentLength(readHeaders(socket)));
            OutputStream out = socket.getOutputStream();
            out.write(response);
            out.flush();
            socket.shutdownOutput();
            Thread.sleep(linger.toMillis());
            socket.close();
        };
    }

    /** Reads the whole request, writes only response headers, then closes: the body never arrives. */
    static Script headersThenDrop(byte[] headerBytes) {
        return socket -> {
            drain(socket, contentLength(readHeaders(socket)));
            OutputStream out = socket.getOutputStream();
            out.write(headerBytes);
            out.flush();
            socket.close();
        };
    }

    /**
     * Reads the whole request, writes the headers and then as much of the body
     * as the client will take, recording how many body bytes left before the
     * write broke — the bounded-error-read measurement.
     */
    static Script respondAndMeasure(byte[] headerBytes, byte[] body) {
        return socket -> {
            drain(socket, contentLength(readHeaders(socket)));
            OutputStream out = socket.getOutputStream();
            out.write(headerBytes);
            out.flush();
            long written = 0;
            try {
                int chunk = 65_536;
                for (int offset = 0; offset < body.length; offset += chunk) {
                    int n = Math.min(chunk, body.length - offset);
                    out.write(body, offset, n);
                    written = offset + n;
                }
                out.flush();
                written = body.length;
            } catch (IOException brokenPipe) {
                // the client cancelled and closed: this is the measured outcome
            }
            measuredBodyBytes = written;
            try {
                socket.close();
            } catch (IOException ignored) {
                // already gone
            }
        };
    }

    /** Concatenates byte arrays. */
    static byte[] concat(byte[]... parts) {
        int total = Arrays.stream(parts).mapToInt(p -> p.length).sum();
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                connections.incrementAndGet();
                Script script = scripts.poll();
                Thread worker = new Thread(() -> {
                    try (Socket s = socket) {
                        s.setSoTimeout(20_000);
                        if (script != null) {
                            script.serve(s);
                        }
                    } catch (IOException | InterruptedException ignored) {
                        // the client side of these scripts is expected to break
                    }
                }, "rst-server-worker");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    /** Reads bytes up to the empty line that ends the request headers. */
    private static byte[] readHeaders(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        String terminator = "\r\n\r\n";
        int state = 0;
        while (state < terminator.length()) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("client closed before the request headers completed");
            }
            buffer.write(b);
            state = b == terminator.charAt(state) ? state + 1 : (b == '\r' ? 1 : 0);
        }
        return buffer.toByteArray();
    }

    /** The Content-Length of raw request headers, 0 when absent. */
    private static long contentLength(byte[] headers) {
        String text = new String(headers, StandardCharsets.ISO_8859_1);
        for (String line : text.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                return Long.parseLong(line.substring(colon + 1).trim());
            }
        }
        return 0;
    }

    /** Reads and discards exactly {@code bytes} body bytes (or until the client stops). */
    private static void drain(Socket socket, long bytes) throws IOException {
        InputStream in = socket.getInputStream();
        byte[] chunk = new byte[8_192];
        long left = bytes;
        while (left > 0) {
            int n = in.read(chunk, 0, (int) Math.min(chunk.length, left));
            if (n < 0) {
                return;
            }
            left -= n;
        }
    }
}
