package io.github.c1589190.bma3.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.c1589190.bma3.app.debug.DebugApi;
import io.github.c1589190.bma3.watchdog.spi.WatchdogSnapshot;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Loopback health surface:
 *
 * <ul>
 *   <li>{@code /healthz} — liveness, always 200 while the process is alive (systemd must not
 *       restart a process merely because a dependency is down);</li>
 *   <li>{@code /readyz} — readiness, 503 when a critical watchdog component is DOWN/UNKNOWN;</li>
 *   <li>{@code /watchdog} — full watchdog snapshot, Bearer-protected.</li>
 *   <li>{@code /watchdog/diagnostics} — content-free diagnostics bundle, Bearer-protected.</li>
 *   <li>{@code /debug/*} — remote ask/diagnose surface, guarded by its own token and only mounted
 *       when {@code security.auth=token} and {@code debug.enabled=true}.</li>
 * </ul>
 */
public final class HealthServer implements AutoCloseable {

    private final HttpServer server;
    private final String token;
    private final Supplier<WatchdogSnapshot> snapshot;
    private final Supplier<Map<String, Object>> diagnostics;
    private final DebugApi debug;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public HealthServer() {
        this("127.0.0.1", 0, "", null, null, null);
    }

    public HealthServer(String host, int port, String token,
                        Supplier<WatchdogSnapshot> snapshot) {
        this(host, port, token, snapshot, null, null);
    }

    public HealthServer(String host, int port, String token,
                        Supplier<WatchdogSnapshot> snapshot, DebugApi debug) {
        this(host, port, token, snapshot, debug, null);
    }

    public HealthServer(String host, int port, String token,
                        Supplier<WatchdogSnapshot> snapshot, DebugApi debug,
                        Supplier<Map<String, Object>> diagnostics) {
        this.token = token == null ? "" : token;
        this.snapshot = snapshot;
        this.debug = debug;
        this.diagnostics = diagnostics;
        try {
            if (!InetAddress.getByName(host).isLoopbackAddress()) {
                throw new IllegalStateException("Health 只允许回环地址");
            }
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException failure) {
            throw new IllegalStateException("启动 health server 失败", failure);
        }
    }

    public int boundPort() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("/healthz".equals(path)) {
                send(exchange, 200, "{\"status\":\"ok\"}");
                return;
            }
            if ("/readyz".equals(path)) {
                WatchdogSnapshot current = snapshot == null ? null : snapshot.get();
                boolean ready = current == null || current.ready();
                String body = "{\"status\":\"" + (ready ? "ready" : "degraded") + "\""
                        + ",\"overall\":\"" + (current == null ? "unknown" : current.overall())
                        + "\"}";
                send(exchange, ready ? 200 : 503, body);
                return;
            }
            if ("/watchdog/diagnostics".equals(path)) {
                if (!authorized(exchange)) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"bma3\"");
                    send(exchange, 401, "{\"error\":\"unauthorized\"}");
                    return;
                }
                if (diagnostics == null) {
                    send(exchange, 404, "{\"error\":\"not_found\"}");
                    return;
                }
                send(exchange, 200, mapper.writeValueAsString(diagnostics.get()));
                return;
            }
            if ("/watchdog".equals(path)) {
                if (!authorized(exchange)) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"bma3\"");
                    send(exchange, 401, "{\"error\":\"unauthorized\"}");
                    return;
                }
                WatchdogSnapshot current = snapshot == null ? null : snapshot.get();
                send(exchange, 200, mapper.writeValueAsString(
                        current == null ? Map.of("status", "unavailable") : current));
                return;
            }
            if (path != null && (path.equals("/debug") || path.startsWith("/debug/"))) {
                if (debug == null) {
                    send(exchange, 404, "{\"error\":\"not_found\"}");
                    return;
                }
                debug.handle(exchange);
                return;
            }
            send(exchange, 404, "{\"error\":\"not_found\"}");
        } catch (RuntimeException failure) {
            send(exchange, 500, "{\"error\":\"internal\"}");
        } finally {
            exchange.close();
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

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
