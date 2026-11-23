package io.github.c1589190.bma3.inbox.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.c1589190.bma3.inbox.core.ControlCommand;
import io.github.c1589190.bma3.inbox.core.DismissRequest;
import io.github.c1589190.bma3.inbox.core.DismissResult;
import io.github.c1589190.bma3.inbox.core.InboxAckRejectedException;
import io.github.c1589190.bma3.inbox.core.InboxPanel;
import io.github.c1589190.bma3.inbox.core.InboxService;
import io.github.c1589190.bma3.inbox.core.PluginHeartbeat;
import io.github.c1589190.bma3.memory.ContextDigest;
import io.github.c1589190.bma3.memory.ContextDigestStore;
import io.github.c1589190.bma3.memory.ContextEntry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
    private final ContextDigestStore context;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private HttpServer server;
    private int boundPort = -1;

    public InboxHttpServer(String host, int port, String inboxId, String token,
                           InboxService service, ContextDigestStore context) {
        this.host = host;
        this.requestedPort = port;
        this.inboxId = inboxId;
        this.token = token == null ? "" : token.trim();
        this.service = service;
        this.context = context;
    }

    /** Without a context store the {@code /plugin/context} route answers 503. */
    public InboxHttpServer(String host, int port, String inboxId, String token,
                           InboxService service) {
        this(host, port, inboxId, token, service, null);
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
            if ("plugin".equals(parts[4]) && parts.length == 6
                    && "context".equals(parts[5]) && !contextMissing(exchange)) {
                if ("POST".equals(method)) {
                    handleContextPush(exchange);
                    return;
                }
                if ("GET".equals(method)) {
                    handleContextCrawl(exchange);
                    return;
                }
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
            InboxPanel snapshot = service.pushPanel();
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
     * ACK over HTTP.
     *
     * <ul>
     *   <li>{@code {"revision":N,"itemIds":[…],"inboxIdentity":"…"}} — the identity-guarded path
     *       the DSH plugin uses: every requested item must be proven and the whole batch is
     *       rejected with 409 when any part cannot be. It is the only accepted way to ACK named
     *       ids over HTTP.</li>
     *   <li>{@code {"indices":[…],"revision":N}} — manual panel-index ACK (legacy surface).</li>
     *   <li>{@code {"revision":N}} or {@code {}} — manual revision-only ACK (legacy surface).</li>
     * </ul>
     *
     * <p>A present but malformed or empty {@code itemIds} field is rejected with 400 and never
     * falls through to the revision-only path: a broken ID list must not acknowledge items that
     * were not named.</p>
     */
    private AckResponse handleAck(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        JsonNode node;
        if (body.length == 0) {
            // The only intentional "ack everything" body: an actually empty entity.
            node = null;
        } else {
            try {
                node = parseCompleteAckBody(body);
            } catch (IOException malformed) {
                // Undecodable or over-long JSON must never reach any ACK path (in particular not
                // the all-ACK). Default readTree would stop at the first root value and accept
                // "{} {}" as "{}".
                return new AckResponse(400, rejection("invalid_json",
                        Map.of("detail", "ACK body 必须是单个完整 JSON 值")));
            }
            if (node == null || !node.isObject()) {
                // A JSON scalar/array/literal null is not an ACK request. Treating it as an empty
                // object would silently become the legacy all-visible ACK.
                return new AckResponse(400, rejection("invalid_body",
                        Map.of("detail", "ACK body 必须是 JSON 对象；只有空 body 表示人工全量 ACK")));
            }
        }
        try {
            // Mentioning inboxIdentity at all is a declaration "this is an identity-guarded ACK".
            // From that point on the request must carry a usable identity, a non-empty item id list
            // and the original revision; it must never widen into revision-only / all-visible ACK,
            // even when the identity value itself is null or malformed.
            boolean guardedAttempt = node != null
                    && (node.has("itemIds") || node.has("inboxIdentity"));
            if (guardedAttempt) {
                JsonNode rawIdentity = node.get("inboxIdentity");
                if (rawIdentity == null || rawIdentity.isNull()) {
                    return new AckResponse(409, rejection("inbox_identity_required",
                            Map.of("inboxIdentity", service.inboxIdentity())));
                }
                if (!rawIdentity.isTextual()) {
                    return new AckResponse(400, rejection("inbox_identity_invalid",
                            Map.of("detail", "inboxIdentity 必须是数据库身份字符串")));
                }
                String identity = rawIdentity.asText("");
                if (identity.isBlank()) {
                    return new AckResponse(409, rejection("inbox_identity_required",
                            Map.of("inboxIdentity", service.inboxIdentity())));
                }
                if (!node.has("itemIds")) {
                    return new AckResponse(400, rejection("item_ids_required",
                            Map.of("detail",
                                    "带 inboxIdentity 的 ACK 必须给出非空 itemIds，"
                                            + "不能退化为 revision-only/全量 ACK")));
                }
                List<String> ids = parseItemIds(node.path("itemIds"));
                if (ids == null) {
                    return new AckResponse(400, rejection("invalid_item_ids",
                            Map.of("detail", "itemIds 必须是非空、无重复的非空字符串数组")));
                }
                // The original panel revision is part of the proof of "what was seen". A missing,
                // null, fractional, textual, boolean, negative or overflowing revision must be
                // rejected; treating it as "absent" would silently turn a guarded ACK into an
                // unversioned one.
                JsonNode rawRevision = node.get("revision");
                if (rawRevision == null || rawRevision.isNull()) {
                    return new AckResponse(400, rejection("revision_required",
                            Map.of("detail", "identity-guarded ACK 必须带原始 panel revision")));
                }
                Long revision = nonNegativeRevision(rawRevision);
                if (revision == null) {
                    return new AckResponse(400, rejection("revision_invalid",
                            Map.of("detail", "revision 必须是 long 范围内的非负整数")));
                }
                return new AckResponse(200,
                        service.ackItemsGuarded(new LinkedHashSet<>(ids), revision, identity));
            }
            // Legacy manual paths: an absent revision stays allowed (revision-only ACK), but a
            // present one must still be a valid non-negative long instead of being ignored.
            Long revision = null;
            JsonNode rawLegacyRevision = node == null ? null : node.get("revision");
            if (rawLegacyRevision != null) {
                revision = nonNegativeRevision(rawLegacyRevision);
                if (revision == null) {
                    return new AckResponse(400, rejection("revision_invalid",
                            Map.of("detail", "revision 必须是 long 范围内的非负整数")));
                }
            }
            if (node != null && node.has("indices") && node.path("indices").isArray()) {
                List<Integer> indices = new ArrayList<>();
                node.path("indices").forEach(value -> indices.add(value.asInt()));
                return new AckResponse(200, service.ackIndices(indices, revision));
            }
            return new AckResponse(200,
                    revision == null ? service.ackPanel() : service.ackPanel(revision));
        } catch (InboxAckRejectedException rejection) {
            int status = switch (rejection.reason()) {
                case REVISION_REQUIRED, REVISION_INVALID -> 400;
                default -> 409;
            };
            return new AckResponse(status, rejectionBody(rejection));
        } catch (IllegalArgumentException failure) {
            String message = failure.getMessage() == null ? "ack_rejected" : failure.getMessage();
            if (message.startsWith("REVISION_CONFLICT")) {
                return new AckResponse(409, rejection("revision_conflict", Map.of()));
            }
            return new AckResponse(400, Map.of("error", "ack_rejected", "detail", message));
        }
    }

    /**
     * Parses the ACK body as <b>exactly one</b> complete JSON value and rejects anything after it.
     *
     * <p>{@link ObjectMapper#readTree(byte[])} stops at the first root value by default, so
     * {@code "{} {}"} would be read as {@code "{}"} and silently become the all-visible ACK. This
     * stricter parse is local to {@code /ack}; other endpoints keep their previous behaviour.
     * Returns {@code null} when the body holds no JSON value at all (for example whitespace).</p>
     */
    private JsonNode parseCompleteAckBody(byte[] body) throws IOException {
        try (JsonParser parser = mapper.getFactory().createParser(body)) {
            JsonNode parsed = mapper.readTree(parser);
            if (parsed != null && parser.nextToken() != null) {
                throw new IOException("ACK body 在 JSON 值之后还有多余内容");
            }
            return parsed;
        }
    }

    /**
     * Accepts only an integral JSON number in {@code [0, Long.MAX_VALUE]}. Text, boolean, null,
     * fractional numbers (also {@code 7.0}), negatives and values beyond a long are all invalid.
     */
    private static Long nonNegativeRevision(JsonNode raw) {
        if (raw == null || raw.isNull() || !raw.isIntegralNumber()) {
            return null;
        }
        java.math.BigInteger value = raw.bigIntegerValue();
        if (value.signum() < 0 || value.bitLength() > 63) {
            return null;
        }
        return value.longValue();
    }

    /** Strict parser; {@code null} means malformed and the caller must reject the whole request. */
    private static List<String> parseItemIds(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode value : array) {
            if (!value.isTextual()) {
                return null;
            }
            String id = value.asText().trim();
            if (id.isEmpty() || !seen.add(id)) {
                return null;
            }
            ids.add(id);
        }
        return ids;
    }

    private static Map<String, Object> rejection(String error, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", error);
        payload.putAll(extra);
        payload.put("ackedItemIds", List.of());
        payload.put("alreadyAckedItemIds", List.of());
        if (!payload.containsKey("missingItemIds")) {
            payload.put("missingItemIds", List.of());
        }
        payload.put("complete", false);
        return payload;
    }

    private Map<String, Object> rejectionBody(InboxAckRejectedException rejection) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (rejection.reason()) {
            case INBOX_IDENTITY_MISMATCH -> {
                payload.put("error", "inbox_identity_mismatch");
                payload.put("inboxIdentity", service.inboxIdentity());
                payload.put("ackedItemIds", List.of());
                payload.put("alreadyAckedItemIds", List.of());
                payload.put("missingItemIds", List.of());
            }
            case INBOX_IDENTITY_REQUIRED -> {
                payload.put("error", "inbox_identity_required");
                payload.put("inboxIdentity", service.inboxIdentity());
                payload.put("ackedItemIds", List.of());
                payload.put("alreadyAckedItemIds", List.of());
                payload.put("missingItemIds", List.of());
            }
            case ITEM_MISSING -> {
                payload.put("error", "item_missing");
                payload.put("ackedItemIds", List.of());
                payload.put("alreadyAckedItemIds", List.of());
                payload.put("missingItemIds", rejection.itemIds());
            }
            case ITEM_NOT_PUSHED -> {
                payload.put("error", "item_not_pushed");
                payload.put("unpushedItemIds", rejection.itemIds());
                payload.put("ackedItemIds", List.of());
                payload.put("alreadyAckedItemIds", List.of());
                payload.put("missingItemIds", List.of());
            }
            case REVISION_REQUIRED -> {
                payload.put("error", "revision_required");
                payload.put("ackedItemIds", List.of());
                payload.put("alreadyAckedItemIds", List.of());
                payload.put("missingItemIds", List.of());
            }
            case REVISION_INVALID -> {
                payload.put("error", "revision_invalid");
                payload.put("ackedItemIds", List.of());
                payload.put("alreadyAckedItemIds", List.of());
                payload.put("missingItemIds", List.of());
            }
            default -> payload.put("error", "ack_rejected");
        }
        payload.put("complete", false);
        return payload;
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
        java.util.Map<String, Object> diagnostics = node.has("diagnostics")
                && node.get("diagnostics").isObject()
                ? mapper.convertValue(node.get("diagnostics"),
                        new com.fasterxml.jackson.core.type.TypeReference<>() { })
                : java.util.Map.of();
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
                Instant.now(), diagnostics);
        ControlCommand control = service.pluginHeartbeat(heartbeat);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("control", control);
        response.put("mcpStartId", service.runtimeInfo().mcpStartId());
        response.put("pushRevision", service.pushRevision());
        return response;
    }

    /**
     * The rolling context channel. Bodies never travel in the heartbeat
     * ({@code PluginHeartbeat}: "Only ids/status/counters are carried"), so they arrive here.
     */
    private void handleContextPush(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        try {
            JsonNode node = raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw);
            String sessionId = node.path("sessionId").asText("");
            long capturedThroughSeq = node.path("capturedThroughSeq").asLong(0L);
            List<ContextEntry> entries = new ArrayList<>();
            JsonNode list = node.path("entries");
            if (list.isArray()) {
                for (JsonNode entry : list) {
                    entries.add(new ContextEntry(
                            entry.path("eventSeq").asLong(0L),
                            entry.path("kind").asText(""),
                            entry.path("speaker").asText(""),
                            instant(entry.path("at").asText("")),
                            entry.path("text").asText("")));
                }
            }
            ContextDigestStore.AppendResult result =
                    context.append(sessionId, capturedThroughSeq, entries, Instant.now());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("stored", result.stored());
            body.put("truncated", result.truncated());
            body.put("evicted", result.evicted());
            body.put("maxEventSeq", result.maxEventSeq());
            send(exchange, 200, mapper.writeValueAsString(body));
        } catch (IllegalArgumentException failure) {
            send(exchange, 400, mapper.writeValueAsString(
                    Map.of("error", "invalid_request", "message", String.valueOf(
                            failure.getMessage()))));
        } catch (IOException failure) {
            send(exchange, 400, "{\"error\":\"invalid_body\"}");
        } catch (RuntimeException failure) {
            send(exchange, 500, "{\"error\":\"context_store_failed\"}");
        }
    }

    /** Catch-up after a plugin restart: everything the jar holds past the plugin's last seq. */
    private void handleContextCrawl(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> query = queryOf(exchange);
            String sessionId = query.getOrDefault("sessionId", "");
            if (sessionId.isBlank()) {
                sessionId = context.lastSession().orElse("");
            }
            if (sessionId.isBlank()) {
                send(exchange, 200, "{\"sessionId\":\"\",\"entries\":[],\"count\":0,"
                        + "\"maxEventSeq\":0}");
                return;
            }
            long after = parseLong(query.get("after"), 0L);
            int limit = (int) Math.min(parseLong(query.get("limit"), 200L), 1000L);
            List<ContextDigest> rows = context.crawl(sessionId, after, limit);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (ContextDigest row : rows) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("eventSeq", row.eventSeq());
                entry.put("kind", row.kind());
                entry.put("speaker", row.speaker());
                entry.put("at", row.at() == null ? "" : row.at().toString());
                entry.put("text", row.body());
                entry.put("truncated", row.truncated());
                entries.add(entry);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sessionId", sessionId);
            body.put("entries", entries);
            body.put("count", entries.size());
            // The newest seq the store holds, not the newest one in this page: a restarted plugin
            // resumes from here instead of replaying the whole session.
            body.put("maxEventSeq", context.maxEventSeq(sessionId).orElse(0L));
            send(exchange, 200, mapper.writeValueAsString(body));
        } catch (IllegalArgumentException failure) {
            send(exchange, 400, mapper.writeValueAsString(
                    Map.of("error", "invalid_request", "message", String.valueOf(
                            failure.getMessage()))));
        } catch (RuntimeException failure) {
            send(exchange, 500, "{\"error\":\"context_store_failed\"}");
        }
    }

    private boolean contextMissing(HttpExchange exchange) throws IOException {
        if (context != null) {
            return false;
        }
        send(exchange, 503, "{\"error\":\"context_store_unavailable\"}");
        return true;
    }

    private Map<String, String> queryOf(HttpExchange exchange) {
        Map<String, String> query = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getQuery();
        if (raw == null || raw.isBlank()) {
            return query;
        }
        for (String pair : raw.split("&")) {
            int at = pair.indexOf('=');
            if (at <= 0) {
                continue;
            }
            query.put(java.net.URLDecoder.decode(pair.substring(0, at),
                    StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8));
        }
        return query;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("分页参数必须是整数: " + value);
        }
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException failure) {
            return null;
        }
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
