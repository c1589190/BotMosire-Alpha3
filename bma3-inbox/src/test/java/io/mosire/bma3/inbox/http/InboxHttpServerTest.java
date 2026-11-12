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
                    new DeliveryPolicy(200, Duration.ofSeconds(1), Duration.ofSeconds(30)));
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
                String itemId = mapper.readTree(items.body()).get(0).path("id").asText();

                HttpResponse<String> detail = get(base + "/v1/inboxes/bma3/items/" + itemId,
                        "token");
                assertThat(detail.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(detail.body()).path("id").asText()).isEqualTo(itemId);

                HttpResponse<String> ack = post(base + "/v1/inboxes/bma3/ack", "token",
                        "{\"itemIds\":[\"" + itemId + "\"]}");
                assertThat(ack.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(ack.body()).path("remaining").asInt()).isZero();

                HttpResponse<String> second = post(base + "/v1/inboxes/bma3/ack", "token",
                        "{\"itemIds\":[\"" + itemId + "\"]}");
                assertThat(second.statusCode())
                        .as("ack 幂等：重复 ack 已确认条目是成功 no-op")
                        .isEqualTo(200);
                assertThat(mapper.readTree(second.body()).path("remaining").asInt()).isZero();
            } finally {
                server.close();
            }
        }
    }

    @Test
    void servesPanelAndAdvancesPanelCursor() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("panel.db"))) {
            InboxService service = new InboxService(store,
                    new DeliveryPolicy(200, Duration.ofSeconds(1), Duration.ofSeconds(30)));
            service.append(new AppendItem("qq_private/bot-1/1", "hello-panel", 300,
                    Instant.now().minusSeconds(5)));
            InboxHttpServer server = new InboxHttpServer("127.0.0.1", 0, "bma3", "token", service);
            try {
                server.start();
                String base = "http://127.0.0.1:" + server.boundPort();
                HttpResponse<String> panel = get(base + "/v1/inboxes/bma3/panel", "token");
                assertThat(panel.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(panel.body()).path("items")).hasSize(1);

                HttpResponse<String> ack = post(base + "/v1/inboxes/bma3/ack", "token", "{}");
                assertThat(ack.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(ack.body()).path("ackedRevision").asLong())
                        .isPositive();
                assertThat(mapper.readTree(get(base + "/v1/inboxes/bma3/panel", "token").body())
                        .path("items")).isEmpty();
            } finally {
                server.close();
            }
        }
    }

    @Test
    void streamsPanelSnapshotOverSse() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("sse.db"))) {
            InboxService service = new InboxService(store,
                    new DeliveryPolicy(200, Duration.ofSeconds(1), Duration.ofSeconds(30)));
            service.append(new AppendItem("qq_private/bot-1/1", "hello-sse", 300,
                    Instant.now().minusSeconds(5)));
            InboxHttpServer server = new InboxHttpServer("127.0.0.1", 0, "bma3", "token", service);
            try {
                server.start();
                String url = "http://127.0.0.1:" + server.boundPort()
                        + "/v1/inboxes/bma3/events";
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer token")
                        .header("Accept", "text/event-stream")
                        .GET().build();
                HttpResponse<java.io.InputStream> response = client.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Content-Type").orElse(""))
                        .contains("text/event-stream");
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(response.body(),
                                java.nio.charset.StandardCharsets.UTF_8))) {
                    StringBuilder frame = new StringBuilder();
                    for (int i = 0; i < 10; i++) {
                        String line = reader.readLine();
                        if (line == null) break;
                        frame.append(line).append('\n');
                        if (line.isEmpty()) break;
                    }
                    assertThat(frame.toString()).contains("event: panel");
                    assertThat(frame.toString()).contains("hello-sse");
                }
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
