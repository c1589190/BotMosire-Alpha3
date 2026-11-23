package io.github.c1589190.bma3.inbox.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.bma3.inbox.core.DeliveryPolicy;
import io.github.c1589190.bma3.inbox.core.InboxService;
import io.github.c1589190.bma3.inbox.core.SqliteInboxStore;
import io.github.c1589190.bma3.memory.ContextOptions;
import io.github.c1589190.bma3.memory.SqliteContextDigestStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code /plugin/context} route: Bearer-gated push, idempotent replay and catch-up reads. */
class InboxContextRouteTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void pushes_idempotently_and_allows_the_plugin_to_catch_up() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("inbox.db"));
                SqliteContextDigestStore digest = new SqliteContextDigestStore(
                        tempDir.resolve("memory.db"), ContextOptions::defaults,
                        UnaryOperator.identity())) {
            InboxService service = new InboxService(store,
                    new DeliveryPolicy(200, Duration.ofSeconds(1), Duration.ofSeconds(30)));
            InboxHttpServer server = new InboxHttpServer("127.0.0.1", 0, "bma3", "token",
                    service, digest);
            try {
                server.start();
                String url = base(server) + "/v1/inboxes/bma3/plugin/context";
                String body = """
                        {"sessionId":"session-abc","capturedThroughSeq":2,"entries":[
                          {"eventSeq":1,"kind":"user","speaker":"owner",
                           "at":"2026-11-18T10:00:00Z","text":"第一句"},
                          {"eventSeq":2,"kind":"assistant","speaker":"bma3",
                           "at":"2026-11-18T10:00:30Z","text":"第二句"}]}
                        """;

                assertThat(post(url, null, body).statusCode()).isEqualTo(401);

                HttpResponse<String> first = post(url, "token", body);
                assertThat(first.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(first.body()).path("stored").asInt()).isEqualTo(2);

                HttpResponse<String> replay = post(url, "token", body);
                assertThat(replay.statusCode()).isEqualTo(200);
                assertThat(digest.crawl("session-abc", 0, 50)).hasSize(2);

                HttpResponse<String> caught = get(url + "?after=1&limit=10", "token");
                assertThat(caught.statusCode()).isEqualTo(200);
                JsonNode payload = mapper.readTree(caught.body());
                assertThat(payload.path("sessionId").asText()).isEqualTo("session-abc");
                assertThat(payload.path("count").asInt()).isEqualTo(1);
                assertThat(payload.path("entries").get(0).path("eventSeq").asLong()).isEqualTo(2);
                assertThat(payload.path("entries").get(0).path("text").asText()).isEqualTo("第二句");
            } finally {
                server.close();
            }
        }
    }

    @Test
    void rejects_an_unknown_kind_instead_of_storing_it() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("inbox.db"));
                SqliteContextDigestStore digest = new SqliteContextDigestStore(
                        tempDir.resolve("memory.db"), ContextOptions::defaults,
                        UnaryOperator.identity())) {
            InboxService service = new InboxService(store,
                    new DeliveryPolicy(200, Duration.ofSeconds(1), Duration.ofSeconds(30)));
            InboxHttpServer server = new InboxHttpServer("127.0.0.1", 0, "bma3", "token",
                    service, digest);
            try {
                server.start();
                String url = base(server) + "/v1/inboxes/bma3/plugin/context";
                HttpResponse<String> rejected = post(url, "token", """
                        {"sessionId":"session-abc","capturedThroughSeq":1,"entries":[
                          {"eventSeq":1,"kind":"system","text":"不该进来"}]}
                        """);
                assertThat(rejected.statusCode()).isEqualTo(400);
                assertThat(digest.crawl("session-abc", 0, 50)).isEmpty();
            } finally {
                server.close();
            }
        }
    }

    @Test
    void answers_503_when_no_context_store_is_wired() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("inbox.db"))) {
            InboxService service = new InboxService(store,
                    new DeliveryPolicy(200, Duration.ofSeconds(1), Duration.ofSeconds(30)));
            InboxHttpServer server = new InboxHttpServer("127.0.0.1", 0, "bma3", "token", service);
            try {
                server.start();
                HttpResponse<String> response = post(base(server)
                        + "/v1/inboxes/bma3/plugin/context", "token", "{}");
                assertThat(response.statusCode()).isEqualTo(503);
            } finally {
                server.close();
            }
        }
    }

    private static String base(InboxHttpServer server) {
        return "http://127.0.0.1:" + server.boundPort();
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
