package io.github.c1589190.bma3.app.debug;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.bma3.app.HealthServer;
import io.github.c1589190.bma3.app.config.AppConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Acceptance A1 (token isolation) plus the HTTP shape of the ask/reply round trip. */
class DebugApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @TempDir
    Path tempDir;

    private DebugApi api(String token) {
        DebugService service = new DebugService(new DebugStore(tempDir.resolve("debug")),
                AppConfig.Debug::defaults, request -> true);
        return new DebugApi(service, token, () -> null, null, tempDir, "0.0.8-test",
                Instant.now(), "token");
    }

    private static HttpResponse<String> send(HealthServer server, String method, String path,
                                             String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.boundPort() + path))
                .timeout(Duration.ofSeconds(10));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        } else {
            builder.GET();
        }
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void debugEndpointsRejectTheOtherTokens() throws Exception {
        try (HealthServer server = new HealthServer("127.0.0.1", 0, "mcp-token", () -> null,
                api("debug-token"))) {
            assertThat(send(server, "GET", "/debug/snapshot", "mcp-token", null).statusCode())
                    .isEqualTo(401);
            assertThat(send(server, "GET", "/debug/snapshot", "inbox-token", null).statusCode())
                    .isEqualTo(401);
            assertThat(send(server, "GET", "/debug/snapshot", null, null).statusCode())
                    .isEqualTo(401);

            HttpResponse<String> ok = send(server, "GET", "/debug/snapshot", "debug-token", null);
            assertThat(ok.statusCode()).isEqualTo(200);
            JsonNode body = MAPPER.readTree(ok.body());
            assertThat(body.path("version").asText()).isEqualTo("0.0.8-test");
            assertThat(body.path("authMode").asText()).isEqualTo("token");
        }
    }

    @Test
    void askThenAnswerRoundTripsOverHttp() throws Exception {
        try (HealthServer server = new HealthServer("127.0.0.1", 0, "", () -> null,
                api("debug-token"))) {
            HttpResponse<String> asked = send(server, "POST", "/debug/ask", "debug-token",
                    "{\"question\":\"watchdog 现在什么状态\",\"wait\":false}");
            assertThat(asked.statusCode()).isEqualTo(202);
            String id = MAPPER.readTree(asked.body()).path("id").asText();
            assertThat(id).startsWith("rq-");

            HttpResponse<String> answered = send(server, "POST", "/debug/answer", "debug-token",
                    "{\"requestId\":\"" + id + "\",\"text\":\"全部 UP\"}");
            assertThat(answered.statusCode()).isEqualTo(200);
            assertThat(MAPPER.readTree(answered.body()).path("status").asText())
                    .isEqualTo("ANSWERED");

            HttpResponse<String> fetched = send(server, "GET", "/debug/ask/" + id, "debug-token",
                    null);
            assertThat(fetched.statusCode()).isEqualTo(200);
            assertThat(MAPPER.readTree(fetched.body()).path("answer").asText()).isEqualTo("全部 UP");
        }
    }

    @Test
    void unmountedDebugPathIsNotFound() throws Exception {
        try (HealthServer server = new HealthServer("127.0.0.1", 0, "mcp-token", () -> null)) {
            assertThat(send(server, "GET", "/debug/snapshot", "mcp-token", null).statusCode())
                    .isEqualTo(404);
        }
    }

    @Test
    void malformedBodiesAreRejectedWithoutInjecting() throws Exception {
        try (HealthServer server = new HealthServer("127.0.0.1", 0, "", () -> null,
                api("debug-token"))) {
            assertThat(send(server, "POST", "/debug/ask", "debug-token", "{}").statusCode())
                    .isEqualTo(400);
            assertThat(send(server, "POST", "/debug/ask", "debug-token",
                    "{\"question\":\"a\"} trailing").statusCode()).isEqualTo(400);
        }
    }
}
