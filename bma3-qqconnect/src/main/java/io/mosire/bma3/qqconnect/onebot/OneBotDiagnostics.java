package io.mosire.bma3.qqconnect.onebot;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import javax.net.ssl.SSLException;

/**
 * Turns a WebSocket connect failure into something an operator (or an agent) can act on.
 *
 * <p>OneBot's HTTP API and its forward-WebSocket endpoint are different ports and different
 * paths. Pointing {@code ws://} at the HTTP one produces an opaque handshake error, so this class
 * maps the HTTP status and the exception type to a hint, and can optionally probe the same
 * {@code host:port} with a plain GET to confirm "this speaks HTTP, not WebSocket".</p>
 */
public final class OneBotDiagnostics {

    private OneBotDiagnostics() {
    }

    public record Diagnosis(int httpStatus, String hint) {

        public boolean known() {
            return hint != null && !hint.isBlank();
        }
    }

    public static Diagnosis of(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof WebSocketHandshakeException handshake) {
            int status = handshake.getResponse() == null ? -1 : handshake.getResponse().statusCode();
            return new Diagnosis(status, hintForStatus(status));
        }
        if (cause instanceof ConnectException) {
            return new Diagnosis(-1,
                    "连接被拒绝：OneBot/LLBot 没启动、端口不对，或被防火墙挡住");
        }
        if (cause instanceof UnknownHostException) {
            return new Diagnosis(-1, "DNS 解析失败：检查 endpoint 的主机名");
        }
        if (cause instanceof SSLException) {
            return new Diagnosis(-1, "TLS 握手失败：wss 的证书/端口与 endpoint 不匹配");
        }
        if (cause instanceof java.net.http.HttpTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException) {
            return new Diagnosis(-1, "连接超时：检查 host:port 是否可达");
        }
        return new Diagnosis(-1, "WebSocket 未就绪：" + cause.getClass().getSimpleName());
    }

    static String hintForStatus(int status) {
        return switch (status) {
            case 401, 403 -> "鉴权失败：检查 tokenEnv 指向的 access token 是否与 OneBot 配置一致";
            case 404 -> "路径不存在：检查 endpoint 的 path（正向 WS 通常是 /ws 或 /）";
            case 426 -> "服务器要求 WebSocket 升级：这个地址更像 OneBot 的 HTTP API 端口。"
                    + "BMA3 只支持正向 WS，请把 endpoint 换成 ws://<host>:<ws端口><路径>";
            case 400 -> "握手被拒：确认这是 OneBot 11 正向 WebSocket 地址，而不是 HTTP API";
            default -> status < 0
                    ? "WebSocket 握手失败"
                    : "WebSocket 握手失败：HTTP " + status;
        };
    }

    /**
     * Best-effort confirmation that {@code host:port} answers plain HTTP. Only used after a
     * handshake failure, never in the normal path — the operator explicitly allowed this probe.
     *
     * @return the HTTP status the port answered with, or -1 when the probe failed
     */
    public static int probeHttp(URI endpoint, Duration timeout) {
        if (endpoint == null || endpoint.getHost() == null) {
            return -1;
        }
        int port = endpoint.getPort() > 0 ? endpoint.getPort() : 80;
        String scheme = "https".equalsIgnoreCase(endpoint.getScheme()) ? "https" : "http";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(scheme + "://" + endpoint.getHost() + ":" + port + "/"))
                    .timeout(timeout)
                    .GET()
                    .build();
            return client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        } catch (Exception failure) {
            return -1;
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof WebSocketHandshakeException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof SSLException) {
                return current;
            }
            if (current.getCause() == null || current.getCause() == current) {
                return current;
            }
            current = current.getCause();
        }
        return failure;
    }
}
