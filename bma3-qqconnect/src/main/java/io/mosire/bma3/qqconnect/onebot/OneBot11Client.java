package io.mosire.bma3.qqconnect.onebot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * OneBot 11 forward-WebSocket client. Events and action calls share one socket; no HTTP action
 * endpoint is needed. Reconnect policy is owned by the account registry.
 */
public final class OneBot11Client implements AutoCloseable {

    private final URI endpoint;
    private final String token;
    private final Consumer<OneBotEvent> eventListener;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OneBot11Codec codec = new OneBot11Codec();
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final CompletableFuture<WebSocket> ready = new CompletableFuture<>();
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending =
            new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public OneBot11Client(URI endpoint, String token, Consumer<OneBotEvent> eventListener) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.token = token == null ? "" : token;
        this.eventListener = Objects.requireNonNull(eventListener, "eventListener");
    }

    public void start() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        WebSocket.Builder builder = http.newWebSocketBuilder();
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.buildAsync(endpoint, new Listener()).whenComplete((webSocket, failure) -> {
            if (failure != null) {
                ready.completeExceptionally(failure);
                return;
            }
            socket.set(webSocket);
            ready.complete(webSocket);
        });
    }

    public void awaitReady(Duration timeout) {
        try {
            ready.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("OneBot WebSocket 未就绪", failure);
        }
    }

    public CompletableFuture<JsonNode> sendAction(String action, Map<String, Object> params) {
        WebSocket webSocket = socket.get();
        if (webSocket == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("OneBot 未连接"));
        }
        String echo = "bma3-" + sequence.incrementAndGet();
        ObjectNode request = mapper.createObjectNode();
        request.put("action", action);
        request.set("params", mapper.valueToTree(params == null ? Map.of() : params));
        request.put("echo", echo);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(echo, future);
        webSocket.sendText(request.toString(), true).whenComplete((ignored, failure) -> {
            if (failure != null) {
                pending.remove(echo);
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    @Override
    public void close() {
        WebSocket webSocket = socket.getAndSet(null);
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
        ready.completeExceptionally(new IllegalStateException("OneBot 已关闭"));
        pending.forEach((echo, future) ->
                future.completeExceptionally(new IllegalStateException("OneBot 已关闭")));
        pending.clear();
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String message = partial.toString();
                partial.setLength(0);
                handle(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            ready.completeExceptionally(new IllegalStateException("OneBot 连接关闭"));
            pending.forEach((echo, future) ->
                    future.completeExceptionally(new IllegalStateException("OneBot 连接关闭")));
            pending.clear();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ready.completeExceptionally(error);
            pending.forEach((echo, future) -> future.completeExceptionally(error));
            pending.clear();
        }

        private void handle(String message) {
            try {
                JsonNode root = mapper.readTree(message);
                JsonNode echo = root.get("echo");
                if (echo != null && echo.isTextual()) {
                    CompletableFuture<JsonNode> future = pending.remove(echo.asText());
                    if (future != null) {
                        future.complete(root);
                        return;
                    }
                }
                codec.decode(message).ifPresent(eventListener);
            } catch (Exception failure) {
                // Ignore malformed frames; a future account registry can surface a metric.
            }
        }
    }
}
