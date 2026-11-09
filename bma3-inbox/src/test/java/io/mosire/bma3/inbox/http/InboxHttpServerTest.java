package io.mosire.bma3.inbox.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.bma3.inbox.core.AppendItem;
import io.mosire.bma3.inbox.core.DeliveryPolicy;
import io.mosire.bma3.inbox.core.InboxService;
import io.mosire.bma3.inbox.core.SqliteInboxStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboxHttpServerTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void exposesHealthStatusItemsAndIdempotentAck() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("inbox.db"))) {
            InboxService service = new InboxService(store,
                    new DeliveryPolicy(200, Duration.ofSeconds(1), Duration.ofSeconds(30),
                            Duration.ofMinutes(10), 3));
            service.append(new AppendItem("qq_private/bot-1/1", "hello", 300,
                    Instant.now().minusSeconds(5)));
            InboxHttpServer server = new InboxHttpServer("127.0.0.1", 0, "bma3", "token", service);
            try {
                server.start();
                String base = "http://127.0.0.1:" + server.boundPort();
                assertThat(get(base + "/healthz", null).statusCode()).isEqualTo(200);
                assertThat(get(base + "/v1/inboxes/bma3/status", null).statusCode()).isEqualTo(401);

                HttpResponse<String> status = get(base + "/v1/inboxes/bma3/status", "token");
                assertThat(status.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(status.body()).path("available").asInt()).isEqualTo(1);

                HttpResponse<String> items = get(base + "/v1/inboxes/bma3/items", "token");
                assertThat(items.statusCode()).isEqualTo(200);
                assertThat(items.body()).doesNotContain("\"content\"");
                assertThat(items.body()).contains("hello");

                HttpResponse<String> due = post(base + "/v1/inboxes/bma3/deliveries/due", "token",
                        "{\"now\":\"" + Instant.now() + "\"}");
                assertThat(due.statusCode()).isEqualTo(200);
                JsonNode deliveries = mapper.readTree(due.body());
                assertThat(deliveries).hasSize(1);
                String deliveryId = deliveries.get(0).path("id").asText();

                HttpResponse<String> ack = post(base + "/v1/inboxes/bma3/deliveries/"
                        + deliveryId + "/ack", "token", "");
                assertThat(ack.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(ack.body()).path("acked").asBoolean()).isTrue();

                HttpResponse<String> second = post(base + "/v1/inboxes/bma3/deliveries/"
                        + deliveryId + "/ack", "token", "");
                assertThat(second.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(second.body()).path("acked").asBoolean()).isFalse();
            } finally {
                server.close();
            }
        }
    }

    private HttpResponse<String> get(String url, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
