package io.mosire.bma3.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthServerDiagnosticsTest {

    @Test
    void exposesContentFreeDiagnosticsOnlyWithTheHealthToken() throws Exception {
        try (HealthServer server = new HealthServer("127.0.0.1", 0, "health-token",
                () -> null, null, () -> Map.of(
                        "version", "0.0.7-hotfix2",
                        "media", Map.of("attempts", 2, "failures", 1)))) {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build();
            URI uri = URI.create("http://127.0.0.1:" + server.boundPort()
                    + "/watchdog/diagnostics");

            HttpResponse<String> unauthorized = client.send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(unauthorized.statusCode()).isEqualTo(401);

            HttpResponse<String> authorized = client.send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(2))
                    .header("Authorization", "Bearer health-token")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(authorized.statusCode()).isEqualTo(200);
            assertThat(authorized.body()).contains("0.0.7-hotfix2", "attempts", "failures");
            assertThat(authorized.body()).doesNotContain("secret", "Bearer");
        }
    }
}
