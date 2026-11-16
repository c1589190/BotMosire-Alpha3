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
                assertThat(get(base(server) + "/healthz", null).statusCode()).isEqualTo(200);
                assertThat(get(base(server) + "/v1/inboxes/bma3/status", null).statusCode())
                        .isEqualTo(401);

                HttpResponse<String> status = get(base(server) + "/v1/inboxes/bma3/status",
                        "token");
                assertThat(status.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(status.body()).path("available").asInt()).isEqualTo(1);

                HttpResponse<String> items = get(base(server) + "/v1/inboxes/bma3/items",
                        "token");
                assertThat(items.statusCode()).isEqualTo(200);
                assertThat(items.body()).doesNotContain("\"content\"");
                assertThat(items.body()).contains("hello");
                String itemId = mapper.readTree(items.body()).get(0).path("id").asText();

                HttpResponse<String> detail = get(base(server)
                        + "/v1/inboxes/bma3/items/" + itemId, "token");
                assertThat(detail.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(detail.body()).path("id").asText()).isEqualTo(itemId);

                // 逐条 ACK 只接受"推送过"的条目，并且必须带数据库身份（插件契约）。
                var pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
                assertThat(pushed).isNotNull();
                String identity = mapper.readTree(
                        get(base(server) + "/v1/inboxes/bma3/panel", "token").body())
                        .path("inboxIdentity").asText();
                assertThat(identity).isNotBlank();

                HttpResponse<String> ack = post(base(server) + "/v1/inboxes/bma3/ack", "token",
                        "{\"revision\":" + pushed.revision() + ",\"itemIds\":[\"" + itemId
                                + "\"],\"inboxIdentity\":\"" + identity + "\"}");
                assertThat(ack.statusCode()).as(ack.body()).isEqualTo(200);
                assertThat(mapper.readTree(ack.body()).path("remaining").asInt()).isZero();

                HttpResponse<String> second = post(base(server) + "/v1/inboxes/bma3/ack", "token",
                        "{\"revision\":" + pushed.revision() + ",\"itemIds\":[\"" + itemId
                                + "\"],\"inboxIdentity\":\"" + identity + "\"}");
                assertThat(second.statusCode())
                        .as("ack 幂等：重复 ack 已确认条目是成功 no-op")
                        .isEqualTo(200);
                assertThat(mapper.readTree(second.body()).path("remaining").asInt()).isZero();
                assertThat(mapper.readTree(second.body()).path("alreadyAckedItemIds").get(0)
                        .asText()).isEqualTo(itemId);
            } finally {
                server.close();
            }
        }
    }

    @Test
    void aNonObjectAckBodyIsRejectedInsteadOfAckingEverything() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("non-object.db"))) {
            InboxService service = new InboxService(store,
                    new DeliveryPolicy(200, Duration.ofSeconds(1), Duration.ofSeconds(30)));
            service.append(new AppendItem("qq_private/bot-1/1", "hello", 300,
                    Instant.now().minusSeconds(5)));
            InboxHttpServer server = new InboxHttpServer("127.0.0.1", 0, "bma3", "token", service);
            try {
                server.start();
                // JSON scalars / arrays / literal null are not an ACK request at all. They must not
                // collapse into an empty object (which the manual path treats as "ack everything").
                for (String body : java.util.List.of("null", "[]", "[\"x\"]", "\"hello\"",
                        "123", "true", "false", "7.5")) {
                    HttpResponse<String> rejected = post(
                            base(server) + "/v1/inboxes/bma3/ack", "token", body);
                    assertThat(rejected.statusCode()).as(body + " -> " + rejected.body())
                            .isEqualTo(400);
                    assertThat(mapper.readTree(rejected.body()).path("error").asText())
                            .as(body).isEqualTo("invalid_body");
                    assertThat(service.panel().items()).as(body).hasSize(1);
                }
                // Undecodable JSON must also fail closed instead of reaching any ACK path.
                for (String body : java.util.List.of("{", "not json", "{\"revision\":}")) {
                    HttpResponse<String> rejected = post(
                            base(server) + "/v1/inboxes/bma3/ack", "token", body);
                    assertThat(rejected.statusCode()).as(body + " -> " + rejected.body())
                            .isEqualTo(400);
                    assertThat(mapper.readTree(rejected.body()).path("error").asText())
                            .as(body).isEqualTo("invalid_json");
                    assertThat(service.panel().items()).as(body).hasSize(1);
                }
            } finally {
                server.close();
            }
        }
    }

    @Test
    void trailingTokensAfterTheJsonObjectAreRejectedInsteadOfAckingEverything() throws Exception {
        // Jackson's default readTree stops at the first root value, so "{} {}" used to be read as
        // "{}" and silently become the all-visible ACK. A complete-JSON check must reject it.
        java.util.List<String> bodies = java.util.List.of(
                "{} {}", "{} x", "{\"revision\":1}junk", "{}[]", "{} 123");
        int caseIndex = 0;
        for (String body : bodies) {
            try (SqliteInboxStore store = new SqliteInboxStore(
                    tempDir.resolve("trailing-" + (caseIndex++) + ".db"))) {
                InboxService service = new InboxService(store,
                        new DeliveryPolicy(200, Duration.ofMillis(50), Duration.ofMillis(100)));
                service.append(new AppendItem("qq_private/bot-1/1", "hello", 300,
                        Instant.now().minusSeconds(5)));
                // Freeze the item so even a legacy revision-only ACK would be observable here.
                assertThat(service.awaitPush(service.pushRevision(), Duration.ofSeconds(2)))
                        .isNotNull();
                String itemId = service.panel().items().getFirst().id();
                InboxHttpServer server = new InboxHttpServer("127.0.0.1", 0, "bma3", "token",
                        service);
                try {
                    server.start();
                    HttpResponse<String> rejected = post(
                            base(server) + "/v1/inboxes/bma3/ack", "token", body);
                    assertThat(rejected.statusCode()).as(body + " -> " + rejected.body())
                            .isEqualTo(400);
                    assertThat(mapper.readTree(rejected.body()).path("error").asText())
                            .as(body).isEqualTo("invalid_json");
                    assertThat(service.panel().items()).as(body).hasSize(1);
                    assertThat(store.item(itemId).orElseThrow().status())
                            .as(body).isEqualTo(io.mosire.bma3.inbox.core.ItemStatus.AVAILABLE);
                } finally {
                    server.close();
                }
            }
        }
    }

    @Test
    void trailingWhitespaceAfterAValidObjectIsStillAccepted() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("trailing-ws.db"))) {
            InboxService service = new InboxService(store,
                    new DeliveryPolicy(200, Duration.ofMillis(50), Duration.ofMillis(100)));
            service.append(new AppendItem("qq_private/bot-1/1", "hello", 300,
                    Instant.now().minusSeconds(5)));
            var pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
            assertThat(pushed).isNotNull();
            InboxHttpServer server = new InboxHttpServer("127.0.0.1", 0, "bma3", "token",
                    service);
            try {
                server.start();
                // A complete JSON value followed only by whitespace is still valid JSON, and this
                // legacy revision-only request keeps its meaning.
                HttpResponse<String> accepted = post(base(server) + "/v1/inboxes/bma3/ack",
                        "token", "{\"revision\":" + pushed.revision() + "}\n");
                assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
                assertThat(service.panel().items()).isEmpty();
            } finally {
                server.close();
            }
        }
    }

    @Test
    void emptyBodyAndEmptyObjectRemainTheIntentionalManualFullAck() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("manual-empty.db"))) {
            InboxService service = new InboxService(store,
                    new DeliveryPolicy(200, Duration.ofSeconds(1), Duration.ofSeconds(30)));
            InboxHttpServer server = new InboxHttpServer("127.0.0.1", 0, "bma3", "token", service);
            try {
                server.start();
                service.append(new AppendItem("qq_private/bot-1/1", "first", 300,
                        Instant.now().minusSeconds(5)));
                HttpResponse<String> emptyBody = post(base(server) + "/v1/inboxes/bma3/ack",
                        "token", "");
                assertThat(emptyBody.statusCode()).as(emptyBody.body()).isEqualTo(200);
                assertThat(service.panel().items()).as("空 body 是有意的人工全量 ACK").isEmpty();

                service.append(new AppendItem("qq_private/bot-1/2", "second", 300,
                        Instant.now().minusSeconds(5)));
                HttpResponse<String> emptyObject = post(base(server) + "/v1/inboxes/bma3/ack",
                        "token", "{}");
                assertThat(emptyObject.statusCode()).as(emptyObject.body()).isEqualTo(200);
                assertThat(service.panel().items()).as("{} 仍是有意的人工全量 ACK").isEmpty();
            } finally {
                server.close();
            }
        }
    }

    @Test
    void aPresentButMalformedRevisionNeverFallsBackToAnUnversionedAck() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("legacy-revision.db"))) {
            InboxService service = new InboxService(store,
                    new DeliveryPolicy(200, Duration.ofSeconds(1), Duration.ofSeconds(30)));
            service.append(new AppendItem("qq_private/bot-1/1", "hello", 300,
                    Instant.now().minusSeconds(5)));
            InboxHttpServer server = new InboxHttpServer("127.0.0.1", 0, "bma3", "token", service);
            try {
                server.start();
                // No itemIds: this is the legacy manual path, where an absent revision is still
                // allowed. A present-but-invalid one must not silently become "ack everything".
                for (String body : java.util.List.of("{\"revision\":\"7\"}",
                        "{\"revision\":null}", "{\"revision\":-1}",
                        "{\"revision\":7.5}")) {
                    HttpResponse<String> rejected = post(
                            base(server) + "/v1/inboxes/bma3/ack", "token", body);
                    assertThat(rejected.statusCode()).as(body + " -> " + rejected.body())
                            .isEqualTo(400);
                    assertThat(mapper.readTree(rejected.body()).path("error").asText())
                            .isEqualTo("revision_invalid");
                    assertThat(service.panel().items()).as(body).hasSize(1);
                }
            } finally {
                server.close();
            }
        }
    }

    private static String base(InboxHttpServer server) {
        return "http://127.0.0.1:" + server.boundPort();
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

    @Test
    void heartbeatDiagnosticsRoundTripAndRestoreControlIsDeliveredOnNextHeartbeat() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("heartbeat.db"))) {
            InboxService service = new InboxService(store,
                    new DeliveryPolicy(200, Duration.ofSeconds(1), Duration.ofSeconds(30)));
            InboxHttpServer server = new InboxHttpServer("127.0.0.1", 0, "bma3", "token", service);
            try {
                server.start();
                String heartbeatBody = "{\"sessionId\":\"session-1\",\"bound\":true,"
                        + "\"sse\":\"open\",\"lastPushRevision\":9,"
                        + "\"tokenSource\":\"env-file\",\"tokenOk\":true,"
                        + "\"diagnostics\":{\"version\":\"0.0.7-hotfix1\","
                        + "\"toolRegistration\":{\"registered\":false,"
                        + "\"attempts\":2,\"lastError\":\"tools_service_unavailable\"}}}";
                HttpResponse<String> heartbeat = post(
                        base(server) + "/v1/inboxes/bma3/plugin/heartbeat", "token",
                        heartbeatBody);
                assertThat(heartbeat.statusCode()).as(heartbeat.body()).isEqualTo(200);

                HttpResponse<String> plugin = get(base(server) + "/v1/inboxes/bma3/plugin", "token");
                JsonNode pluginBody = mapper.readTree(plugin.body());
                assertThat(pluginBody.path("heartbeat").path("diagnostics").path("version").asText())
                        .isEqualTo("0.0.7-hotfix1");
                assertThat(pluginBody.path("heartbeat").path("diagnostics")
                        .path("toolRegistration").path("registered").asBoolean()).isFalse();

                service.requestControl("restore", "session-9", "test restore");
                HttpResponse<String> next = post(
                        base(server) + "/v1/inboxes/bma3/plugin/heartbeat", "token",
                        heartbeatBody);
                JsonNode nextBody = mapper.readTree(next.body());
                assertThat(nextBody.path("control").path("action").asText()).isEqualTo("restore");
                assertThat(nextBody.path("control").path("sessionId").asText()).isEqualTo("session-9");
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
