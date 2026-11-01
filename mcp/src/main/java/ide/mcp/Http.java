package ide.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ide.core.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The server over MCP's Streamable HTTP transport, so that an agent reaches an IDE that is already running (the
 * window's) instead of opening a project of its own: one endpoint, {@code POST /mcp}, on 127.0.0.1 only, with a bearer
 * token. A request is answered as JSON, or as a stream of events when there is more than one message (an answer and
 * the notification that the tools changed); a notification gets 202. It offers no stream of its own to listen to.
 * Each message is handled in the {@code turn} it is given (the window's thread), one at a time.
 */
public final class Http {

    private final HttpServer server;

    private Http(HttpServer server) {
        this.server = server;
    }

    /** Listens on a free port of 127.0.0.1; {@code turn} runs the work it is given and returns when it is done. */
    public static Http start(Server mcp, Consumer<Runnable> turn, String token) {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            var expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
            server.createContext("/mcp", exchange -> {
                try (exchange) {
                    var given = exchange.getRequestHeaders().getFirst("Authorization");
                    if (given == null || !MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
                        exchange.sendResponseHeaders(401, -1);
                    } else if (!exchange.getRequestMethod().equals("POST")) {
                        exchange.getResponseHeaders().set("Allow", "POST");
                        exchange.sendResponseHeaders(405, -1);
                    } else {
                        answer(exchange, mcp, turn);
                    }
                }
            });
            server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
                var thread = new Thread(runnable, "mcp-http");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            return new Http(server);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot listen for MCP", e);
        }
    }

    private static void answer(HttpExchange exchange, Server mcp, Consumer<Runnable> turn) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var messages = new Object() {
            List<Map<String, Object>> sent = List.of();
        };
        turn.accept(() -> messages.sent = mcp.handle(body));
        if (messages.sent.isEmpty()) {
            exchange.sendResponseHeaders(202, -1);
            return;
        }
        String text;
        if (messages.sent.size() == 1) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            text = Json.write(messages.sent.getFirst());
        } else {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            var events = new StringBuilder();
            messages.sent.forEach(message -> events.append("event: message\ndata: ").append(Json.write(message)).append("\n\n"));
            text = events.toString();
        }
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** Where an agent finds it. */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    public void stop() {
        server.stop(0);
    }
}
