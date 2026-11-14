package io.mosire.bma3.inbox.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.bma3.inbox.core.ControlCommand;
import io.mosire.bma3.inbox.core.DismissRequest;
import io.mosire.bma3.inbox.core.DismissResult;
import io.mosire.bma3.inbox.core.InboxPanel;
import io.mosire.bma3.inbox.core.InboxService;
import io.mosire.bma3.inbox.core.PluginHeartbeat;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        this.token = token == null ? "" : token.trim();
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

    /** True while the HTTP listener is up; used by the watchdog. */
    public synchronized boolean running() {
        return server != null;
    }

    /** Watchdog remediation: stop and rebind the same host/port. */
    public synchronized void restart() {
        close();
        start();
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
            String method = exchange.getRequestMethod();
            if ("status".equals(parts[4]) && "GET".equals(method)) {
                send(exchange, 200, mapper.writeValueAsString(service.status()));
                return;
            }
            if ("panel".equals(parts[4]) && "GET".equals(method)) {
                send(exchange, 200, mapper.writeValueAsString(service.panel()));
                return;
            }
            if ("events".equals(parts[4]) && parts.length == 5 && "GET".equals(method)) {
                streamEvents(exchange);
                return;
            }
            if ("ack".equals(parts[4]) && parts.length == 5 && "POST".equals(method)) {
                AckResponse ack = handleAck(exchange);
                send(exchange, ack.status(), mapper.writeValueAsString(ack.body()));
                return;
            }
            if ("dismiss".equals(parts[4]) && parts.length == 5 && "POST".equals(method)) {
                send(exchange, 200, mapper.writeValueAsString(handleDismiss(exchange)));
                return;
            }
            if ("runtime".equals(parts[4]) && parts.length == 5 && "GET".equals(method)) {
                send(exchange, 200, mapper.writeValueAsString(service.runtimeInfo()));
                return;
            }
            if ("plugin".equals(parts[4]) && parts.length == 5 && "GET".equals(method)) {
                send(exchange, 200, mapper.writeValueAsString(pluginState()));
                return;
            }
            if ("plugin".equals(parts[4]) && parts.length == 6
                    && "heartbeat".equals(parts[5]) && "POST".equals(method)) {
                send(exchange, 200, mapper.writeValueAsString(handleHeartbeat(exchange)));
                return;
            }
            if ("items".equals(parts[4]) && parts.length == 6 && "GET".equals(method)) {
                try {
                    send(exchange, 200, mapper.writeValueAsString(service.item(parts[5])));
                } catch (IllegalArgumentException failure) {
                    send(exchange, 404, "{\"error\":\"item_not_found\"}");
                }
                return;
            }
            if ("items".equals(parts[4]) && parts.length == 5 && "GET".equals(method)) {
                send(exchange, 200, mapper.writeValueAsString(service.list()));
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
     * fresh bounded snapshot, so a missed event cannot hide messages. Arrival-driven pushes are
     * what the delay/batching rules in {@link InboxService#awaitPush} govern.
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
            long pushed = snapshot.pushRevision();
            while (!Thread.currentThread().isInterrupted()) {
                InboxPanel next = service.awaitPush(pushed, java.time.Duration.ofSeconds(15));
                if (next == null) {
                    writeEvent(output, "heartbeat", last,
                            "{\"revision\":" + last + "}");
                    continue;
                }
                writeEvent(output, "panel", next.revision(), mapper.writeValueAsString(next));
                last = Math.max(last, next.revision());
                pushed = Math.max(pushed, next.pushRevision());
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

    private record AckResponse(int status, Object body) {
    }

    /**
     * Per-item ack over HTTP: {@code {"itemIds":[…]} } or {@code {"indices":[…]} } ack exactly those
     * items; {@code {"revision":N}} acks everything at or below that revision (what the DSH plugin
     * sends after injecting a panel). Returns 409 when the caller's revision is stale.
     */
    private AckResponse handleAck(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        var node = body.length == 0 ? null : mapper.readTree(body);
        Long revision = node != null && node.has("revision")
                && node.path("revision").canConvertToLong()
                ? node.path("revision").asLong() : null;
        try {
            if (node != null && node.has("itemIds") && node.path("itemIds").isArray()) {
                Set<String> ids = new LinkedHashSet<>();
                node.path("itemIds").forEach(value -> ids.add(value.asText()));
                return new AckResponse(200, service.ackItems(ids, revision));
            }
            if (node != null && node.has("indices") && node.path("indices").isArray()) {
                List<Integer> indices = new ArrayList<>();
                node.path("indices").forEach(value -> indices.add(value.asInt()));
                return new AckResponse(200, service.ackIndices(indices, revision));
            }
            return new AckResponse(200,
                    revision == null ? service.ackPanel() : service.ackPanel(revision));
        } catch (IllegalArgumentException failure) {
            String message = failure.getMessage() == null ? "ack_rejected" : failure.getMessage();
            if (message.startsWith("REVISION_CONFLICT")) {
                return new AckResponse(409, Map.of("error", "revision_conflict"));
            }
            return new AckResponse(400, Map.of("error", "ack_rejected", "detail", message));
        }
    }

    /**
     * Bulk "clear without reading" (D-042). The body is a {@link DismissRequest}; an empty body
     * means "dismiss the visible panel window, but keep important items".
     */
    private DismissResult handleDismiss(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        var node = body.length == 0 ? mapper.createObjectNode() : mapper.readTree(body);
        Set<String> ids = new LinkedHashSet<>();
        if (node.path("itemIds").isArray()) {
            node.path("itemIds").forEach(value -> ids.add(value.asText()));
        }
        return service.dismiss(new DismissRequest(
                node.path("scope").asText("visible"),
                ids,
                node.path("sourcePrefix").asText(""),
                node.hasNonNull("priorityBelow") ? node.path("priorityBelow").asInt() : null,
                node.hasNonNull("priorityAtLeast") ? node.path("priorityAtLeast").asInt() : null,
                node.hasNonNull("olderThanSeconds") ? node.path("olderThanSeconds").asLong() : null,
                node.path("limit").asInt(100),
                node.path("dryRun").asBoolean(false),
                node.path("allowImportant").asBoolean(false),
                node.path("reason").asText("")));
    }

    private Map<String, Object> handleHeartbeat(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        var node = body.length == 0 ? mapper.createObjectNode() : mapper.readTree(body);
        PluginHeartbeat heartbeat = new PluginHeartbeat(
                node.path("sessionId").asText(""),
                node.path("bound").asBoolean(false),
                node.path("sse").asText("unknown"),
                node.path("lastPushRevision").asLong(0L),
                node.path("lastMcpStartId").asText(""),
                node.path("lastError").asText(""),
                node.path("appliedControlId").asText(""),
                node.path("tokenSource").asText("unknown"),
                node.path("tokenOk").asBoolean(true),
                Instant.now());
        ControlCommand control = service.pluginHeartbeat(heartbeat);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("control", control);
        response.put("mcpStartId", service.runtimeInfo().mcpStartId());
        response.put("pushRevision", service.pushRevision());
        return response;
    }

    private Map<String, Object> pluginState() {
        Map<String, Object> state = new LinkedHashMap<>();
        PluginHeartbeat heartbeat = service.lastHeartbeat();
        state.put("heartbeat", heartbeat);
        state.put("healthy", heartbeat != null && heartbeat.healthy());
        state.put("control", service.pendingControl());
        state.put("mcpStartId", service.runtimeInfo().mcpStartId());
        state.put("pluginSessionId", service.pluginSessionId());
        return state;
    }

    private boolean authorized(HttpExchange exchange) {
        if (token.isEmpty()) {
            // security.auth=none (D-064): loopback binding is still enforced above.
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] actual = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}
