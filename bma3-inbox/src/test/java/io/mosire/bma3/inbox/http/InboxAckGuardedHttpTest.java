package io.mosire.bma3.inbox.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.bma3.core.source.SourceView;
import io.mosire.bma3.inbox.core.AppendItem;
import io.mosire.bma3.inbox.core.DeliveryPolicy;
import io.mosire.bma3.inbox.core.InboxEntry;
import io.mosire.bma3.inbox.core.InboxPanel;
import io.mosire.bma3.inbox.core.InboxRuntime;
import io.mosire.bma3.inbox.core.InboxService;
import io.mosire.bma3.inbox.core.SqliteInboxStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract for the identity-guarded ACK used by the DSH plugin.
 *
 * <p>Every ACK that names item IDs must carry the durable inbox identity it saw, must prove every
 * requested item individually, and must leave the database untouched when any part cannot be
 * proven. A malformed {@code itemIds} field must never degrade into a revision-only ACK.</p>
 *
 * <p>All of this runs against a real temporary SQLite database and the real loopback HTTP server;
 * nothing here is a fake provider or a real QQ/DSH validation.</p>
 */
class InboxAckGuardedHttpTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** A live service + HTTP server over one temporary database. */
    private final class Running implements AutoCloseable {
        private final Path database;
        private final SqliteInboxStore store;
        private final InboxService service;
        private final InboxHttpServer server;

        private Running(Path database, int maxItemsPerPanel) throws Exception {
            this.database = database;
            this.store = new SqliteInboxStore(database);
            this.service = new InboxService(store,
                    new DeliveryPolicy(300, Duration.ofMillis(80), Duration.ofMillis(120)),
                    "bma3", () -> new InboxRuntime(6, maxItemsPerPanel, true));
            this.server = new InboxHttpServer("127.0.0.1", 0, "bma3", "token", service);
            server.start();
        }

        private String base() {
            return "http://127.0.0.1:" + server.boundPort();
        }

        /** Appends one item, then pushes so it becomes frozen (i.e. a plugin could have shown it). */
        private InboxPanel appendAndPush(String source, int priority, String text) {
            service.append(AppendItem.of(SourceView.of(source, source),
                    new InboxEntry(1L, Instant.now(), "u", text, List.of()), priority,
                    Instant.now()));
            InboxPanel pushed = service.awaitPush(service.pushRevision(), Duration.ofSeconds(2));
            assertThat(pushed).as("推送必须发生").isNotNull();
            return pushed;
        }

        private InboxPanel appendWithoutPush(String source, int priority, String text) {
            service.append(AppendItem.of(SourceView.of(source, source),
                    new InboxEntry(1L, Instant.now(), "u", text, List.of()), priority,
                    Instant.now()));
            return service.panel();
        }

        @Override
        public void close() {
            server.close();
            service.close();
            store.close();
        }
    }

    private Running start(Path database, int maxItemsPerPanel) throws Exception {
        return new Running(database, maxItemsPerPanel);
    }

    private JsonNode panel(Running running) throws Exception {
        return mapper.readTree(get(running.base() + "/v1/inboxes/bma3/panel", "token").body());
    }

    // ------------------------------------------------------------------ identity

    @Test
    void panelAndRuntimeExposeADurablePerDatabaseIdentity() throws Exception {
        Running first = start(tempDir.resolve("identity-a.db"), 6);
        String identity;
        try {
            identity = panel(first).path("inboxIdentity").asText();
            assertThat(identity).as("面板必须带数据库身份").isNotBlank();
            JsonNode runtime = mapper.readTree(
                    get(first.base() + "/v1/inboxes/bma3/runtime", "token").body());
            assertThat(runtime.path("inboxIdentity").asText()).isEqualTo(identity);
        } finally {
            first.close();
        }

        Running reopened = start(tempDir.resolve("identity-a.db"), 6);
        try {
            assertThat(panel(reopened).path("inboxIdentity").asText())
                    .as("普通重启后身份不变（否则插件无法证明还是同一个 inbox）")
                    .isEqualTo(identity);
        } finally {
            reopened.close();
        }

        Running other = start(tempDir.resolve("identity-b.db"), 6);
        try {
            assertThat(panel(other).path("inboxIdentity").asText())
                    .as("另一个库必须有不同的身份")
                    .isNotEqualTo(identity);
        } finally {
            other.close();
        }
    }

    // ------------------------------------------------------------------ guarded ack

    @Test
    void guardedAckConfirmsEveryRequestedItemAndIsIdempotent() throws Exception {
        try (Running running = start(tempDir.resolve("ack-proof.db"), 6)) {
            InboxPanel pushed = running.appendAndPush("qq_private/bot-1/1", 300, "hello");
            String itemId = pushed.items().getFirst().id();
            String identity = panel(running).path("inboxIdentity").asText();
            long revision = pushed.revision();

            HttpResponse<String> first = postAck(running, revision, List.of(itemId), identity);
            assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
            JsonNode body = mapper.readTree(first.body());
            assertThat(body.path("ackedItemIds")).hasSize(1);
            assertThat(body.path("ackedItemIds").get(0).asText()).isEqualTo(itemId);
            assertThat(body.path("alreadyAckedItemIds")).isEmpty();
            assertThat(body.path("missingItemIds")).isEmpty();
            assertThat(body.path("complete").asBoolean()).isTrue();
            assertThat(body.path("remaining").asInt()).isZero();
            assertThat(panel(running).path("items")).isEmpty();

            HttpResponse<String> lostResponseRetry = postAck(running, revision, List.of(itemId),
                    identity);
            assertThat(lostResponseRetry.statusCode()).as("响应丢失后的重试必须可安全重放")
                    .isEqualTo(200);
            JsonNode retry = mapper.readTree(lostResponseRetry.body());
            assertThat(retry.path("ackedItemIds")).as("没有重复确认").isEmpty();
            assertThat(retry.path("alreadyAckedItemIds")).hasSize(1);
            assertThat(retry.path("alreadyAckedItemIds").get(0).asText()).isEqualTo(itemId);
            assertThat(retry.path("missingItemIds")).isEmpty();
            assertThat(retry.path("complete").asBoolean()).isTrue();
            assertThat(retry.path("remaining").asInt()).isZero();
        }
    }

    @Test
    void identityMismatchRejectsTheWholeAckBeforeAnyRowChanges() throws Exception {
        try (Running running = start(tempDir.resolve("identity-guard.db"), 6)) {
            InboxPanel pushed = running.appendAndPush("qq_private/bot-1/1", 300, "hello");
            String itemId = pushed.items().getFirst().id();
            String identity = panel(running).path("inboxIdentity").asText();

            HttpResponse<String> rejected = postAck(running, pushed.revision(), List.of(itemId),
                    "some-other-database");
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(409);
            assertThat(mapper.readTree(rejected.body()).path("error").asText())
                    .isEqualTo("inbox_identity_mismatch");
            assertThat(panel(running).path("items")).as("身份不符时不能改任何行").hasSize(1);
            assertThat(running.store.item(itemId).orElseThrow().status())
                    .isEqualTo(io.mosire.bma3.inbox.core.ItemStatus.AVAILABLE);

            HttpResponse<String> accepted = postAck(running, pushed.revision(), List.of(itemId),
                    identity);
            assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
            assertThat(panel(running).path("items")).isEmpty();
        }
    }

    @Test
    void aMixedKnownAndUnknownRequestIsNotFalselyComplete() throws Exception {
        try (Running running = start(tempDir.resolve("mixed.db"), 6)) {
            InboxPanel pushed = running.appendAndPush("qq_private/bot-1/1", 300, "hello");
            String itemId = pushed.items().getFirst().id();
            String identity = panel(running).path("inboxIdentity").asText();

            HttpResponse<String> rejected = postAck(running, pushed.revision(),
                    List.of(itemId, "itm-does-not-exist"), identity);
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(409);
            JsonNode body = mapper.readTree(rejected.body());
            assertThat(body.path("error").asText()).isEqualTo("item_missing");
            assertThat(body.path("missingItemIds")).hasSize(1);
            assertThat(body.path("missingItemIds").get(0).asText())
                    .isEqualTo("itm-does-not-exist");
            assertThat(body.path("ackedItemIds")).isEmpty();
            assertThat(body.path("complete").asBoolean()).isFalse();
            assertThat(panel(running).path("items"))
                    .as("部分确认不能当成整批成功").hasSize(1);
        }
    }

    @Test
    void aPurgedIdIsMissingRatherThanSilentlyConfirmed() throws Exception {
        try (Running running = start(tempDir.resolve("purged.db"), 6)) {
            InboxPanel pushed = running.appendAndPush("qq_private/bot-1/1", 300, "hello");
            String itemId = pushed.items().getFirst().id();
            String identity = panel(running).path("inboxIdentity").asText();
            assertThat(postAck(running, pushed.revision(), List.of(itemId), identity).statusCode())
                    .isEqualTo(200);
            running.store.purge(Instant.now().plusSeconds(60), 5000);
            assertThat(running.store.item(itemId)).as("条目已被清理").isEmpty();

            HttpResponse<String> rejected = postAck(running, pushed.revision(), List.of(itemId),
                    identity);
            assertThat(rejected.statusCode()).as("已清理的 ID 不能仅凭不存在就假定已确认")
                    .isEqualTo(409);
            assertThat(mapper.readTree(rejected.body()).path("error").asText())
                    .isEqualTo("item_missing");
            assertThat(mapper.readTree(rejected.body()).path("missingItemIds")).hasSize(1);
        }
    }

    @Test
    void guardedAckRejectsAnItemThatWasNeverPushed() throws Exception {
        try (Running running = start(tempDir.resolve("unpushed.db"), 6)) {
            InboxPanel live = running.appendWithoutPush("qq_private/bot-1/1", 300, "hello");
            JsonNode item = panel(running).path("items").get(0);
            assertThat(item.path("frozenRevision").asLong()).isZero();
            String itemId = item.path("id").asText();
            String identity = panel(running).path("inboxIdentity").asText();

            HttpResponse<String> rejected = postAck(running, live.revision(), List.of(itemId),
                    identity);
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(409);
            assertThat(mapper.readTree(rejected.body()).path("error").asText())
                    .isEqualTo("item_not_pushed");
            assertThat(panel(running).path("items")).as("未推送条目不能被 ACK").hasSize(1);
        }
    }

    @Test
    void malformedItemIdsNeverFallThroughToRevisionOnlyAck() throws Exception {
        try (Running running = start(tempDir.resolve("malformed.db"), 6)) {
            InboxPanel pushed = running.appendAndPush("qq_private/bot-1/1", 300, "hello");
            String identity = panel(running).path("inboxIdentity").asText();
            long revision = pushed.revision();

            List<String> malformed = List.of(
                    "{\"revision\":" + revision + ",\"itemIds\":\"c29tZS1pZA==\","
                            + "\"inboxIdentity\":\"" + identity + "\"}",
                    "{\"revision\":" + revision + ",\"itemIds\":{},"
                            + "\"inboxIdentity\":\"" + identity + "\"}",
                    "{\"revision\":" + revision + ",\"itemIds\":null,"
                            + "\"inboxIdentity\":\"" + identity + "\"}",
                    "{\"revision\":" + revision + ",\"itemIds\":[],"
                            + "\"inboxIdentity\":\"" + identity + "\"}",
                    "{\"revision\":" + revision + ",\"itemIds\":[\"\"],"
                            + "\"inboxIdentity\":\"" + identity + "\"}");
            for (String body : malformed) {
                HttpResponse<String> rejected = post(running.base() + "/v1/inboxes/bma3/ack",
                        "token", body);
                assertThat(rejected.statusCode()).as(body + " -> " + rejected.body())
                        .isEqualTo(400);
                assertThat(panel(running).path("items"))
                        .as("畸形 itemIds 不能退回 revision-only ACK: " + body)
                        .hasSize(1);
            }
        }
    }

    @Test
    void itemIdsWithoutAnIdentityFailClosed() throws Exception {
        // An old plugin (or a hand-written client) that names ids but cannot prove the database must
        // not get a silent ACK: it keeps the panel unacked instead of acknowledging blind.
        try (Running running = start(tempDir.resolve("no-identity.db"), 6)) {
            InboxPanel pushed = running.appendAndPush("qq_private/bot-1/1", 300, "hello");
            String itemId = pushed.items().getFirst().id();

            HttpResponse<String> rejected = post(running.base() + "/v1/inboxes/bma3/ack", "token",
                    "{\"revision\":" + pushed.revision() + ",\"itemIds\":[\"" + itemId + "\"]}");
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(409);
            assertThat(mapper.readTree(rejected.body()).path("error").asText())
                    .isEqualTo("inbox_identity_required");
            assertThat(panel(running).path("items")).hasSize(1);
        }
    }

    @Test
    void anyExplicitIdentityIsAGuardedAttemptEvenWithoutItemIds() throws Exception {
        // Presence of inboxIdentity is itself the statement "this is a guarded ACK". A guarded
        // client that forgot itemIds must be rejected, never widened to revision-only / all-visible
        // ACK (which could acknowledge entries it never named).
        try (Running running = start(tempDir.resolve("identity-without-ids.db"), 6)) {
            InboxPanel pushed = running.appendAndPush("qq_private/bot-1/1", 300, "hello");
            String itemId = pushed.items().getFirst().id();
            String identity = panel(running).path("inboxIdentity").asText();
            long revision = pushed.revision();

            assertGuardedRejected(running, "{\"inboxIdentity\":\"" + identity
                    + "\",\"revision\":" + revision + "}", 400, "item_ids_required");
            assertGuardedRejected(running, "{\"inboxIdentity\":\"" + identity + "\"}",
                    400, "item_ids_required");
            assertGuardedRejected(running, "{\"inboxIdentity\":\"" + identity
                    + "\",\"indices\":[1],\"revision\":" + revision + "}",
                    400, "item_ids_required");

            // An explicitly supplied but unusable identity is still an attempted guarded ACK.
            assertGuardedRejected(running, "{\"inboxIdentity\":null,\"revision\":" + revision
                    + "}", 409, "inbox_identity_required");
            assertGuardedRejected(running, "{\"inboxIdentity\":\"\",\"revision\":" + revision
                    + "}", 409, "inbox_identity_required");
            assertGuardedRejected(running, "{\"inboxIdentity\":42,\"revision\":" + revision
                    + "}", 400, "inbox_identity_invalid");
            assertGuardedRejected(running, "{\"inboxIdentity\":{},\"itemIds\":[\"" + itemId
                    + "\"],\"revision\":" + revision + "}", 400, "inbox_identity_invalid");

            assertThat(panel(running).path("items"))
                    .as("任何被拒的 guarded 尝试都不能 ACK 条目")
                    .hasSize(1);
            assertThat(running.store.item(itemId).orElseThrow().status())
                    .isEqualTo(io.mosire.bma3.inbox.core.ItemStatus.AVAILABLE);
            assertThat(running.store.ackedRevision()).isZero();

            // Only a request that never mentions inboxIdentity is legacy/manual, and keeps its
            // revision-only meaning.
            HttpResponse<String> legacy = post(running.base() + "/v1/inboxes/bma3/ack", "token",
                    "{\"revision\":" + revision + "}");
            assertThat(legacy.statusCode()).as(legacy.body()).isEqualTo(200);
            assertThat(panel(running).path("items")).isEmpty();
        }
    }

    private void assertGuardedRejected(Running running, String body, int status, String error)
            throws Exception {
        HttpResponse<String> response = post(running.base() + "/v1/inboxes/bma3/ack", "token",
                body);
        assertThat(response.statusCode()).as(body + " -> " + response.body()).isEqualTo(status);
        assertThat(mapper.readTree(response.body()).path("error").asText())
                .as(body).isEqualTo(error);
    }

    @Test
    void aGuardedAckRequiresANonNegativeIntegerRevision() throws Exception {
        // The revision is what proves "what the caller saw". It must never be silently absent:
        // a missing or malformed revision must not turn into an unversioned ACK.
        try (Running running = start(tempDir.resolve("revision-guard.db"), 6)) {
            InboxPanel pushed = running.appendAndPush("qq_private/bot-1/1", 300, "hello");
            String itemId = pushed.items().getFirst().id();
            String identity = panel(running).path("inboxIdentity").asText();
            String prefix = "{\"itemIds\":[\"" + itemId + "\"],\"inboxIdentity\":\""
                    + identity + "\"";

            assertRevisionRejected(running, prefix + "}", "revision_required");
            assertRevisionRejected(running, prefix + ",\"revision\":null}", "revision_required");

            for (String raw : List.of("\"7\"", "7.5", "7.0", "true", "-1",
                    "9223372036854775808", "[7]", "{\"value\":7}")) {
                assertRevisionRejected(running, prefix + ",\"revision\":" + raw + "}",
                        "revision_invalid");
            }

            assertThat(panel(running).path("items"))
                    .as("任何被拒的 revision 都不能 ACK 条目")
                    .hasSize(1);
            assertThat(running.store.item(itemId).orElseThrow().status())
                    .isEqualTo(io.mosire.bma3.inbox.core.ItemStatus.AVAILABLE);
        }
    }

    private void assertRevisionRejected(Running running, String body, String expectedError)
            throws Exception {
        HttpResponse<String> response = post(running.base() + "/v1/inboxes/bma3/ack", "token",
                body);
        assertThat(response.statusCode()).as(body + " -> " + response.body()).isEqualTo(400);
        assertThat(mapper.readTree(response.body()).path("error").asText())
                .as(body).isEqualTo(expectedError);
    }

    @Test
    void aStaleRevisionIsStillAConflictForAValidIdentity() throws Exception {
        try (Running running = start(tempDir.resolve("revision.db"), 6)) {
            InboxPanel pushed = running.appendAndPush("qq_private/bot-1/1", 300, "hello");
            String itemId = pushed.items().getFirst().id();
            String identity = panel(running).path("inboxIdentity").asText();

            HttpResponse<String> rejected = postAck(running,
                    panel(running).path("revision").asLong() + 100, List.of(itemId), identity);
            assertThat(rejected.statusCode()).isEqualTo(409);
            assertThat(mapper.readTree(rejected.body()).path("error").asText())
                    .isEqualTo("revision_conflict");
            assertThat(panel(running).path("items")).hasSize(1);
        }
    }

    // ------------------------------------------------------------------ push eligibility proof

    @Test
    void panelItemsCarryTheFrozenRevisionThatProvesPushedEligibility() throws Exception {
        try (Running running = start(tempDir.resolve("frozen-revision.db"), 6)) {
            InboxPanel pushed = running.appendAndPush("qq_private/bot-1/1", 300, "pushed");
            long pushRevision = pushed.pushRevision();
            assertThat(pushRevision).isPositive();

            JsonNode panel = panel(running);
            assertThat(panel.path("items")).hasSize(1);
            assertThat(panel.path("items").get(0).path("frozenRevision").asLong())
                    .isEqualTo(pushRevision);

            running.appendWithoutPush("qq_private/bot-1/2", 200, "not pushed yet");
            JsonNode mixed = panel(running).path("items");
            assertThat(mixed).hasSize(2);
            JsonNode unpushed = mixed.get(1);
            assertThat(unpushed.path("frozenRevision").asLong())
                    .as("未推送条目必须可区分")
                    .isZero();
        }
    }

    @Test
    void statusDiagnosesAFrozenWindowThatStarvesPendingItems() throws Exception {
        // Window of one slot: the pushed (frozen) item keeps the only slot, so a newer pending item
        // can never be delivered until someone acks. That condition must be observable.
        try (Running running = start(tempDir.resolve("starvation.db"), 1)) {
            running.appendAndPush("qq_private/bot-1/1", 300, "占用唯一窗口");
            running.appendWithoutPush("qq_group/bot-1/2", 200, "排在窗口外");

            JsonNode status = mapper.readTree(
                    get(running.base() + "/v1/inboxes/bma3/status", "token").body());
            assertThat(status.path("frozenWindowBlocked").asBoolean()).isTrue();
            assertThat(status.path("pendingBlockedByFrozen").asInt()).isEqualTo(1);
            assertThat(status.path("frozenInWindow").asInt()).isEqualTo(1);

            String frozenId = panel(running).path("items").get(0).path("id").asText();
            String identity = panel(running).path("inboxIdentity").asText();
            assertThat(postAck(running, panel(running).path("revision").asLong(),
                    List.of(frozenId), identity).statusCode()).isEqualTo(200);

            JsonNode unblocked = mapper.readTree(
                    get(running.base() + "/v1/inboxes/bma3/status", "token").body());
            assertThat(unblocked.path("frozenWindowBlocked").asBoolean()).isFalse();
            assertThat(unblocked.path("pendingBlockedByFrozen").asInt()).isZero();
        }
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> get(String url, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String token, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postAck(Running running, long revision, List<String> itemIds,
                                         String identity) throws Exception {
        StringBuilder body = new StringBuilder("{\"revision\":").append(revision)
                .append(",\"itemIds\":[");
        for (int index = 0; index < itemIds.size(); index++) {
            if (index > 0) {
                body.append(',');
            }
            body.append('"').append(itemIds.get(index)).append('"');
        }
        body.append("],\"inboxIdentity\":\"").append(identity).append("\"}");
        return post(running.base() + "/v1/inboxes/bma3/ack", "token", body.toString());
    }
}
