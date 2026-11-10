package io.mosire.bma3.inbox.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.bma3.inbox.core.Delivery;
import io.mosire.bma3.inbox.core.InboxPanel;
import io.mosire.bma3.inbox.core.InboxService;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;

/** Minimal loopback HTTP surface for one inbox; MCP tools remain the Agent-facing path. */
public final class InboxHttpServer implements AutoCloseable {

    private final String host;
    private final int requestedPort;
    private final String inboxId;
    private final String token;
    private final InboxService service;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private HttpServer server;
    private int boundPort = -1;

    public InboxHttpServer(String host, int port, String inboxId, String token,
                           InboxService service) {
        this.host = host;
        this.requestedPort = port;
        this.inboxId = inboxId;
        this.token = token;
        this.service = service;
    }

    public synchronized void start() {
        try {
            if (!InetAddress.getByName(host).isLoopbackAddress()) {
                throw new IllegalStateException("Inbox HTTP 只允许回环地址");
            }
            server = HttpServer.create(new InetSocketAddress(host, requestedPort), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", this::handle);
            server.start();
            boundPort = server.getAddress().getPort();
        } catch (IOException failure) {
            throw new IllegalStateException("启动 Inbox HTTP 失败", failure);
        }
    }

    public int boundPort() {
        return boundPort;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("/healthz".equals(path)) {
                send(exchange, 200, "{\"status\":\"ok\"}");
                return;
            }
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"bma3\"");
                send(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            String[] parts = path.split("/");
            if (parts.length < 5 || !"v1".equals(parts[1]) || !"inboxes".equals(parts[2])
                    || !inboxId.equals(parts[3])) {
                send(exchange, 404, "{\"error\":\"not_found\"}");
                return;
            }
            if ("status".equals(parts[4]) && "GET".equals(exchange.getRequestMethod())) {
                send(exchange, 200, mapper.writeValueAsString(service.status()));
                return;
            }
            if ("panel".equals(parts[4]) && "GET".equals(exchange.getRequestMethod())) {
                send(exchange, 200, mapper.writeValueAsString(service.panel()));
                return;
            }
            if ("events".equals(parts[4]) && parts.length == 5
                    && "GET".equals(exchange.getRequestMethod())) {
                streamEvents(exchange);
                return;
            }
            if ("ack".equals(parts[4]) && parts.length == 5
                    && "POST".equals(exchange.getRequestMethod())) {
                long requested = requestedRevision(exchange);
                send(exchange, 200, mapper.writeValueAsString(
                        requested < 0 ? service.ackPanel() : service.ackPanel(requested)));
                return;
            }
            if ("items".equals(parts[4]) && "GET".equals(exchange.getRequestMethod())) {
                send(exchange, 200, mapper.writeValueAsString(service.list()));
                return;
            }
            if ("deliveries".equals(parts[4]) && parts.length == 6
                    && "due".equals(parts[5]) && "POST".equals(exchange.getRequestMethod())) {
                Instant now = now(exchange);
                send(exchange, 200, mapper.writeValueAsString(
                        service.due(now, minPriority(exchange))));
                return;
            }
            if ("deliveries".equals(parts[4]) && parts.length == 7
                    && "ack".equals(parts[6]) && "POST".equals(exchange.getRequestMethod())) {
                try {
                    send(exchange, 200, mapper.writeValueAsString(service.ack(parts[5])));
                } catch (IllegalArgumentException failure) {
                    send(exchange, 404, "{\"error\":\"delivery_not_found\"}");
                }
                return;
            }
            send(exchange, 404, "{\"error\":\"not_found\"}");
        } catch (RuntimeException failure) {
            send(exchange, 500, "{\"error\":\"internal\"}");
        } finally {
            exchange.close();
        }
    }

    /**
     * One SSE stream per inbox. The event id is the panel revision; a reconnect always gets a
     * fresh bounded snapshot, so a missed event cannot hide messages.
     */
    private void streamEvents(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        OutputStream output = exchange.getResponseBody();
        long last = parseRevision(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
        try {
            InboxPanel snapshot = service.panel();
            writeEvent(output, "panel", snapshot.revision(), mapper.writeValueAsString(snapshot));
            last = Math.max(last, snapshot.revision());
            while (!Thread.currentThread().isInterrupted()) {
                InboxPanel next = service.awaitPanel(last, java.time.Duration.ofSeconds(15));
                if (next == null) {
                    writeEvent(output, "heartbeat", last,
                            "{\"revision\":" + last + "}");
                    continue;
                }
                writeEvent(output, "panel", next.revision(), mapper.writeValueAsString(next));
                last = Math.max(last, next.revision());
            }
        } catch (IOException disconnected) {
            // Client closed the SSE; the panel itself is unaffected.
        } finally {
            exchange.close();
        }
    }

    private static void writeEvent(OutputStream output, String event, long id, String data)
            throws IOException {
        String frame = "id: " + id + "\n"
                + "event: " + event + "\n"
                + "data: " + data + "\n\n";
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static long parseRevision(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /** Reads the optional ack revision; -1 means "ack the current panel". */
    private long requestedRevision(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length == 0) {
            return -1L;
        }
        var node = mapper.readTree(body);
        if (!node.has("revision")) {
            return -1L;
        }
        return node.path("revision").asLong(-1L);
    }

    private Instant now(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length == 0) {
            return Instant.now();
        }
        var node = mapper.readTree(body);
        String raw = node.path("now").asText("");
        return raw.isBlank() ? Instant.now() : Instant.parse(raw);
    }

    private int minPriority(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isBlank()) {
            return Integer.MIN_VALUE;
        }
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && "minPriority".equals(pair[0])) {
                try {
                    return Integer.parseInt(pair[1]);
                } catch (NumberFormatException ignored) {
                    return Integer.MIN_VALUE;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] actual = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
        }
    }
}
