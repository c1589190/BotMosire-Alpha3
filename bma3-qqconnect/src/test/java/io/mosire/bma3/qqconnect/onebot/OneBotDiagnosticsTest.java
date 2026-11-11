package io.mosire.bma3.qqconnect.onebot;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * "It won't connect" must come with a fix. These assertions pin the mapping that used to be a bare
 * exception type in a WARN line.
 */
class OneBotDiagnosticsTest {

    @Test
    void anUpgradeRequiredResponsePointsAtTheHttpApiPort() {
        var diagnosis = OneBotDiagnostics.of(handshake(426));
        assertThat(diagnosis.httpStatus()).isEqualTo(426);
        assertThat(diagnosis.hint()).contains("HTTP API").contains("ws://");
    }

    @Test
    void authAndPathFailuresSayWhatToCheck() {
        assertThat(OneBotDiagnostics.of(handshake(401)).hint()).contains("token");
        assertThat(OneBotDiagnostics.of(handshake(404)).hint()).contains("path");
    }

    @Test
    void transportFailuresAreNamedInPlainLanguage() {
        assertThat(OneBotDiagnostics.of(new ConnectException("refused")).hint())
                .contains("连接被拒绝");
        assertThat(OneBotDiagnostics.of(new UnknownHostException("nope.invalid")).hint())
                .contains("DNS");
    }

    @Test
    void theHttpProbeConfirmsAPortThatSpeaksPlainHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            URI endpoint = URI.create("ws://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertThat(OneBotDiagnostics.probeHttp(endpoint, Duration.ofSeconds(2)))
                    .as("探测能区分「这是 HTTP 端口」和「路径/token 问题」")
                    .isEqualTo(200);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theProbeReturnsMinusOneWhenNothingAnswers() {
        assertThat(OneBotDiagnostics.probeHttp(
                URI.create("ws://127.0.0.1:1/"), Duration.ofMillis(300))).isEqualTo(-1);
    }

    private static WebSocketHandshakeException handshake(int status) {
        return new WebSocketHandshakeException(new java.net.http.HttpResponse<String>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public java.net.http.HttpRequest request() {
                return null;
            }

            @Override
            public java.util.Optional<java.net.http.HttpResponse<String>> previousResponse() {
                return java.util.Optional.empty();
            }

            @Override
            public java.net.http.HttpHeaders headers() {
                return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
            }

            @Override
            public String body() {
                return "";
            }

            @Override
            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
                return java.util.Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("ws://127.0.0.1:3001/");
            }

            @Override
            public java.net.http.HttpClient.Version version() {
                return java.net.http.HttpClient.Version.HTTP_1_1;
            }
        });
    }
}
