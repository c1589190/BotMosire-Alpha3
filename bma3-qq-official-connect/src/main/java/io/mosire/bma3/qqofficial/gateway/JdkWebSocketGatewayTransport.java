package io.mosire.bma3.qqofficial.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production transport using the Java 21 {@link java.net.http.WebSocket} API.
 *
 * <p>It only moves text frames; protocol parsing and lifecycle live in
 * {@link OfficialQqGatewayClient}. No frame contents are logged here.</p>
 *
 * <p>Security: the production path accepts only {@code wss://} URLs on official
 * {@code qq.com} hosts (including {@code *.qq.com}), rejects user-info, fragments, explicit
 * non-443 ports and IP/loopback/localhost literals. Fake transport injection bypasses this check
 * so tests remain fully in-memory. Fragmented text messages are bounded to prevent OOM.</p>
 */
public final class JdkWebSocketGatewayTransport implements OfficialQqGatewayTransport {

    public static final int DEFAULT_MAX_TEXT_FRAME_CHARS = 1_048_576;

    private final AtomicBoolean terminated = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Listener listener;
    private final CompletableFuture<WebSocket> socketFuture;
    private volatile WebSocket socket;

    private JdkWebSocketGatewayTransport(URI uri, Listener listener, Duration connectTimeout,
                                         int maxTextFrameChars) {
        validateGatewayUri(uri);
        this.listener = Objects.requireNonNull(listener, "listener");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .build();
        this.socketFuture = client.newWebSocketBuilder()
                .connectTimeout(connectTimeout)
                .buildAsync(uri, new SocketListener(maxTextFrameChars));
        this.socketFuture.whenComplete((opened, failure) -> {
            if (failure != null) {
                notifyError(failure);
            } else {
                socket = opened;
                if (closing.get()) {
                    opened.sendClose(WebSocket.NORMAL_CLOSURE, "closed");
                }
            }
        });
    }

    /** Production default: Java's standard WebSocket implementation, 10s connect timeout. */
    public static Factory factory() {
        return factory(Duration.ofSeconds(10), DEFAULT_MAX_TEXT_FRAME_CHARS);
    }

    public static Factory factory(Duration connectTimeout) {
        return factory(connectTimeout, DEFAULT_MAX_TEXT_FRAME_CHARS);
    }

    public static Factory factory(Duration connectTimeout, int maxTextFrameChars) {
        Duration timeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (maxTextFrameChars < 1) {
            throw new IllegalArgumentException("maxTextFrameChars must be positive");
        }
        return (uri, listener) -> new JdkWebSocketGatewayTransport(
                uri, listener, timeout, maxTextFrameChars);
    }

    /**
     * Validates the production Gateway URL before any socket is opened.
     *
     * @throws GatewayPermanentException for a non-official, insecure or ambiguous endpoint
     */
    public static void validateGatewayUri(URI uri) {
        if (uri == null) {
            throw new GatewayPermanentException("GATEWAY_URI_MISSING");
        }
        if (!"wss".equalsIgnoreCase(uri.getScheme())) {
            throw new GatewayPermanentException("GATEWAY_URI_NOT_WSS");
        }
        if (uri.isOpaque() || uri.getRawUserInfo() != null) {
            throw new GatewayPermanentException("GATEWAY_URI_USERINFO_OR_OPAQUE");
        }
        if (uri.getRawFragment() != null) {
            throw new GatewayPermanentException("GATEWAY_URI_FRAGMENT");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new GatewayPermanentException("GATEWAY_URI_NON_DEFAULT_PORT");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new GatewayPermanentException("GATEWAY_URI_HOST_MISSING");
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.equals("localhost") || normalized.indexOf(':') >= 0
                || normalized.startsWith("[") || isIpv4Literal(normalized)) {
            throw new GatewayPermanentException("GATEWAY_URI_PRIVATE_OR_IP_LITERAL");
        }
        if (!normalized.equals("qq.com") && !normalized.endsWith(".qq.com")) {
            throw new GatewayPermanentException("GATEWAY_URI_NON_OFFICIAL_HOST");
        }
    }

    private static boolean isIpv4Literal(String host) {
        boolean sawDigit = false;
        for (int index = 0; index < host.length(); index++) {
            char ch = host.charAt(index);
            if (ch >= '0' && ch <= '9') {
                sawDigit = true;
            } else if (ch != '.') {
                return false;
            }
        }
        return sawDigit;
    }

    @Override
    public CompletionStage<Void> sendText(String text) {
        WebSocket current = socket;
        if (current == null || terminated.get() || closing.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("gateway socket not open"));
        }
        return current.sendText(Objects.requireNonNull(text, "text"), true)
                .thenApply(ignored -> (Void) null);
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        WebSocket current = socket;
        if (current != null) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "closed");
        } else {
            socketFuture.whenComplete((opened, failure) -> {
                if (opened != null) {
                    opened.sendClose(WebSocket.NORMAL_CLOSURE, "closed");
                }
            });
        }
    }

    private void notifyError(Throwable failure) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        try {
            listener.onError(failure);
        } catch (RuntimeException ignored) {
            // Callback failure must not escape a JDK WebSocket completion thread.
        }
    }

    private final class SocketListener implements WebSocket.Listener {

        private final BoundedTextFrame frame;

        private SocketListener(int maxTextFrameChars) {
            this.frame = new BoundedTextFrame(maxTextFrameChars);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            try {
                listener.onOpen();
            } finally {
                webSocket.request(1);
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String complete;
            try {
                complete = frame.append(data, last);
            } catch (GatewayFrameTooLargeException failure) {
                notifyError(failure);
                webSocket.abort();
                return null;
            }
            if (complete != null) {
                try {
                    listener.onText(complete);
                } catch (RuntimeException failure) {
                    notifyError(failure);
                    webSocket.abort();
                    return null;
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (terminated.compareAndSet(false, true)) {
                try {
                    listener.onClose(statusCode, reason);
                } catch (RuntimeException ignored) {
                    // The state machine is already past transport ownership here.
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            notifyError(error);
        }
    }
}
