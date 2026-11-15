package io.mosire.bma3.app.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import io.mosire.bma3.inbox.core.InboxService;
import io.mosire.bma3.watchdog.spi.WatchdogEvent;
import io.mosire.bma3.watchdog.spi.WatchdogSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP face of the debug surface, mounted on the loopback health listener.
 *
 * <p>Endpoints (all Bearer-protected by {@code DEBUG_TOKEN}):</p>
 *
 * <pre>
 * GET  /debug/snapshot          process + watchdog + inbox + db sizes
 * GET  /debug/logs?since=&amp;limit= watchdog events (no bodies)
 * POST /debug/ask               {question, timeoutSeconds?, wait?}
 * GET  /debug/ask/{id}          one request
 * GET  /debug/ask?status=&amp;limit= list requests
 * POST /debug/answer            {requestId, text}
 * </pre>
 *
 * <p>Deliberately absent: any way to execute a command. The ask path only appends one Inbox item;
 * the Agent decides what to do with it.</p>
 */
public final class DebugApi {

    private static final Logger LOG = LoggerFactory.getLogger(DebugApi.class);
    private static final int MAX_BODY_BYTES = 512 * 1024;

    private final DebugService service;
    private final String token;
    private final Supplier<WatchdogSnapshot> watchdog;
    private final InboxService inbox;
    private final Path dataDir;
    private final String version;
    private final Instant startedAt;
    private final String authMode;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public DebugApi(DebugService service, String token, Supplier<WatchdogSnapshot> watchdog,
                    InboxService inbox, Path dataDir, String version, Instant startedAt,
                    String authMode) {
        this.service = service;
        this.token = token == null ? "" : token;
        this.watchdog = watchdog;
        this.inbox = inbox;
        this.dataDir = dataDir;
        this.version = version;
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
        this.authMode = authMode == null ? "token" : authMode;
    }

    /** Handles one exchange; the caller only routes {@code /debug} paths here. */
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"bma3-debug\"");
                send(exchange, 401, error("unauthorized", "需要 DEBUG_TOKEN"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            Map<String, String> query = query(exchange);

            if ("GET".equals(method) && "/debug/snapshot".equals(path)) {
                send(exchange, 200, mapper.writeValueAsString(snapshot()));
                return;
            }
            if ("GET".equals(method) && "/debug/logs".equals(path)) {
                send(exchange, 200, mapper.writeValueAsString(events(query)));
                return;
            }
            if ("GET".equals(method) && "/debug/ask".equals(path)) {
                DebugRequest.Status status = parseStatus(query.get("status"));
                int limit = parseInt(query.get("limit"), 50);
                send(exchange, 200, mapper.writeValueAsString(
                        service.list(status, limit)));
                return;
            }
            if (path.startsWith("/debug/ask/") && "GET".equals(method)) {
                String id = URLDecoder.decode(path.substring("/debug/ask/".length()),
                        StandardCharsets.UTF_8);
                send(exchange, 200, mapper.writeValueAsString(service.get(id)));
                return;
            }
            if ("POST".equals(method) && "/debug/ask".equals(path)) {
                ask(exchange);
                return;
            }
            if ("POST".equals(method) && "/debug/answer".equals(path)) {
                answer(exchange);
                return;
            }
            send(exchange, 404, error("not_found", "未知的 debug 路径: " + path));
        } catch (DebugException failure) {
            send(exchange, failure.status(),
                    error(failure.code(), failure.getMessage() == null ? "" : failure.getMessage()));
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            LOG.warn("event=debug_api_failed error={}", failure.getClass().getSimpleName());
            send(exchange, 500, error("internal", "debug 面内部错误"));
        }
    }

    private void ask(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        String question = body.path("question").asText("");
        Integer timeout = body.has("timeoutSeconds") && body.get("timeoutSeconds").canConvertToInt()
                ? body.get("timeoutSeconds").asInt() : null;
        boolean wait = !body.has("wait") || body.path("wait").asBoolean(true);
        DebugRequest result = service.ask(question, timeout, wait, peer(exchange));
        int status = result.status() == DebugRequest.Status.PENDING ? 202 : 200;
        send(exchange, status, mapper.writeValueAsString(result));
    }

    private void answer(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        String requestId = body.path("requestId").asText("");
        String text = body.path("text").asText("");
        DebugRequest result = service.answer(requestId, text, peer(exchange));
        send(exchange, 200, mapper.writeValueAsString(result));
    }

    private Map<String, Object> snapshot() {
        Instant now = Instant.now();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", version);
        root.put("pid", ProcessHandle.current().pid());
        root.put("startedAt", startedAt.toString());
        root.put("now", now.toString());
        root.put("uptimeSeconds", Duration.between(startedAt, now).toSeconds());
        root.put("authMode", authMode);
        root.put("debugPending", service.pendingCount());
        WatchdogSnapshot current = watchdog == null ? null : watchdog.get();
        root.put("watchdog", current);
        Map<String, Object> inboxView = new LinkedHashMap<>();
        if (inbox != null) {
            inboxView.put("inboxId", inbox.inboxId());
            inboxView.put("status", inbox.status());
            inboxView.put("heartbeat", inbox.lastHeartbeat());
        }
        root.put("inbox", inboxView);
        Map<String, Long> files = new LinkedHashMap<>();
        for (String name : new String[] {"bot.db", "inbox.db", "schedule.db", "debug/requests.json"}) {
            Path file = dataDir == null ? null : dataDir.resolve(name);
            files.put(name, file != null && Files.isRegularFile(file) ? fileSize(file) : -1L);
        }
        root.put("files", files);
        return root;
    }

    private List<WatchdogEvent> events(Map<String, String> query) {
        WatchdogSnapshot current = watchdog == null ? null : watchdog.get();
        List<WatchdogEvent> all = current == null ? List.of() : current.recentEvents();
        Instant since = null;
        String raw = query.get("since");
        if (raw != null && !raw.isBlank()) {
            try {
                since = Instant.parse(raw);
            } catch (RuntimeException ignored) {
                since = null;
            }
        }
        int limit = Math.max(1, Math.min(200, parseInt(query.get("limit"), 50)));
        List<WatchdogEvent> selected = new ArrayList<>();
        for (WatchdogEvent event : all) {
            if (since == null || event.at().isAfter(since)) {
                selected.add(event);
            }
        }
        if (selected.size() > limit) {
            selected = selected.subList(selected.size() - limit, selected.size());
        }
        return selected;
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        byte[] raw;
        try (InputStream input = exchange.getRequestBody()) {
            raw = input.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (raw.length > MAX_BODY_BYTES) {
            throw new DebugException(413, "body_too_large", "请求体超过 512 KiB");
        }
        if (raw.length == 0) {
            throw new DebugException(400, "body_required", "需要一个 JSON 对象");
        }
        try {
            JsonNode node = mapper.reader().with(
                    com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(raw);
            if (node == null || !node.isObject()) {
                throw new DebugException(400, "body_invalid", "请求体必须是 JSON 对象");
            }
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new DebugException(400, "body_invalid", "JSON 解析失败");
        }
    }

    private boolean authorized(HttpExchange exchange) {
        if (token.isBlank()) {
            return false;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] actual = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int split = pair.indexOf('=');
            String key = split < 0 ? pair : pair.substring(0, split);
            String value = split < 0 ? "" : pair.substring(split + 1);
            values.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    private static DebugRequest.Status parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DebugRequest.Status.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException failure) {
            return -1L;
        }
    }

    private static String peer(HttpExchange exchange) {
        try {
            return exchange.getRemoteAddress().getAddress().getHostAddress();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private String error(String code, String message) throws IOException {
        return mapper.writeValueAsString(Map.of("error", code, "message", message));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
