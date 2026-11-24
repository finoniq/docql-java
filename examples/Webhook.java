// The README webhook example, kept byte-identical with the README's fenced
// block by scripts/check-quickstart.sh. Set DOCQL_WEBHOOK_SECRET to your
// endpoint's whsec_ secret from the console, optionally PORT (default 8000),
// and run it with the JDK source launcher and the classpath of the quickstart:
//
//   java -cp <the quickstart's classpath> Webhook.java

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.List;

import com.finoniq.docql.WebhookEvent;
import com.finoniq.docql.WebhookVerificationException;
import com.finoniq.docql.Webhooks;
import com.sun.net.httpserver.HttpServer;

public class Webhook {

    private static final int MAX_BODY = 1 << 20; // 1 MiB

    public static void main(String[] args) throws IOException {
        String secret = System.getenv("DOCQL_WEBHOOK_SECRET"); // whsec_..., from the console
        if (secret == null) {
            System.err.println("set DOCQL_WEBHOOK_SECRET");
            System.exit(1);
        }
        String port = System.getenv("PORT");
        if (port == null || port.isEmpty()) {
            port = "8000";
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(port)), 0);
        server.createContext("/", exchange -> {
            try {
                if (!exchange.getRequestMethod().equals("POST")) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                // The raw bytes, before any JSON parsing; at most 1 MiB.
                byte[] body;
                try (InputStream in = exchange.getRequestBody()) {
                    body = in.readNBytes(MAX_BODY + 1);
                }
                if (body.length > MAX_BODY) {
                    exchange.sendResponseHeaders(413, -1);
                    return;
                }

                WebhookEvent event;
                try {
                    event = Webhooks.verify(body, exchange.getRequestHeaders(), List.of(secret));
                } catch (WebhookVerificationException e) {
                    System.err.println("webhook refused: " + e.reason());
                    exchange.sendResponseHeaders(400, -1);
                    return;
                } catch (IllegalArgumentException e) { // a malformed secret: fix the configuration
                    System.err.println(e.getMessage());
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }

                System.out.println("event " + event.type() + " " + event.id()); // store event.id(); skip one already seen
                exchange.sendResponseHeaders(204, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }
}
