package io.github.c1589190.bma3.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.bma3.app.config.AppConfig;
import io.github.c1589190.bma3.app.config.ConfigLoader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(25)
class OptionalCapabilitiesTest {

    @TempDir
    Path root;

    @Test
    void disabledCapabilitiesAreNotReportedAsBrokenAndDshCanBeReenabled() throws Exception {
        Path configFile = root.resolve("config/app.yaml");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, config(false, false));
        AppConfig config = ConfigLoader.load(root);
        assertThat(AppConfig.Watchdog.defaults().pluginHeartbeatEnabled()).isTrue();
        assertThat(AppConfig.Watchdog.defaults().pushBacklogEnabled()).isTrue();
        assertThat(config.watchdog().pluginHeartbeatEnabled()).isFalse();
        assertThat(config.watchdog().pushBacklogEnabled()).isFalse();

        try (Bma3App app = Bma3App.start(config, Map.of(), root)) {
            String token = Bma3Env.read(root.resolve(".bma3env")).get(Bma3Env.MCP_TOKEN);
            JsonNode disabled = awaitProbe(app.healthPort(), token, "dshPlugin", "UP");
            assertThat(disabled.path("components").path("dshPlugin").path("detail").asText())
                    .isEqualTo("disabled");
            assertThat(disabled.path("components").path("xvfb").path("health").asText())
                    .isEqualTo("UP");
            assertThat(disabled.path("components").path("xvfb").path("detail").asText())
                    .isEqualTo("disabled");
            assertThat(disabled.path("components").path("inboxBacklog").path("detail").asText())
                    .isEqualTo("disabled");

            Files.writeString(configFile, config(true, true));
            JsonNode enabled = awaitProbe(app.healthPort(), token, "dshPlugin", "DEGRADED");
            assertThat(enabled.path("components").path("dshPlugin").path("code").asText())
                    .isEqualTo("NO_HEARTBEAT");
            assertThat(enabled.path("components").path("inboxBacklog").path("detail").asText())
                    .isEqualTo("ok");
        }
    }

    private String config(boolean heartbeatEnabled, boolean backlogEnabled) {
        String dataDir = root.resolve("data").toAbsolutePath().toString()
                .replace('\\', '/').replace("'", "''");
        return """
                dataDir: '%s'
                server:
                  host: 127.0.0.1
                  port: 0
                  healthPort: 0
                inbox:
                  httpPort: 0
                xvfb:
                  enabled: false
                watchdog:
                  intervalSeconds: 1
                  failureThreshold: 1
                  pluginHeartbeatEnabled: %s
                  pushBacklogEnabled: %s
                debug:
                  enabled: false
                schedule:
                  enabled: false
                """.formatted(dataDir, heartbeatEnabled, backlogEnabled);
    }

    private static JsonNode awaitProbe(int port, String token, String component, String health)
            throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/watchdog"))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode latest = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                latest = mapper.readTree(response.body());
                if (health.equals(latest.path("components").path(component).path("health").asText())
                        && latest.path("components").has("xvfb")) {
                    return latest;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("watchdog component did not reach " + health + ": " + latest);
    }
}
