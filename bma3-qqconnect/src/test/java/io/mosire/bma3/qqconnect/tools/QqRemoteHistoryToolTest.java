package io.mosire.bma3.qqconnect.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import io.mosire.bma3.qqconnect.account.AccountConfig;
import io.mosire.bma3.qqconnect.account.AccountRegistry;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 契约见 {@code docs/superpowers/specs/2026-11-13-remote-history-paging-design.md}。
 *
 * <p>核心变化：单次调用只发一次 provider 请求（A8），游标由 min/max seq 推出（A5），
 * 不再有 1000→300→100 的降级阶梯。</p>
 */
class QqRemoteHistoryToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    // ---- A1 / A2 ----

    @Test
    void countAboveMaximumIsRejectedBeforeAnyRequest() throws Exception {
        try (Harness harness = new Harness()) {
            ToolResult result = harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 1000)));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("INVALID_ARGUMENT");
            // A1：拒绝必须发生在发请求之前 —— provider 一个字节都没收到
            assertThat(harness.client.requests).isEmpty();
        }
    }

    @Test
    void countDefaultsToThirtyWhichIsExactlyOneProviderRpc() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.respondWith(seqRange(1000, 30));
            ToolResult result = harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692")));

            assertThat(result.success()).as(result.message()).isTrue();
            assertThat(harness.client.requests).hasSize(1);
            assertThat(harness.client.requests.get(0).get("count")).isEqualTo(30);
            assertThat(harness.client.requests.get(0)).doesNotContainKey("message_seq");
        }
    }

    // ---- A3 / A4 ----

    @Test
    void cursorIsForwardedAsMessageSeq() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.respondWith(seqRange(900, 30));
            harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "cursor", "999")));

            assertThat(harness.client.requests.get(0).get("message_seq")).isEqualTo("999");
        }
    }

    @Test
    void sendsCamelCaseReverseOrderAndNoNapCatParameters() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.respondWith(seqRange(1000, 5));
            harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "reverseOrder", true)));

            Map<String, Object> sent = harness.client.requests.get(0);
            assertThat(sent).containsEntry("reverseOrder", true);
            // 这三个是 NapCat 的参数，LLBot 的 schema 不认识，发了会被静默丢弃
            assertThat(sent).doesNotContainKeys("reverse_order", "parse_mult_msg", "disable_get_url");
        }
    }

    // ---- A5 / A6 ----

    @Test
    void cursorsComeFromOldestAndNewestSeq() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.respondWith(seqRange(1000, 30));
            ToolResult result = harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 30)));

            JsonNode body = mapper.readTree(result.message());
            assertThat(body.path("count").asInt()).isEqualTo(30);
            assertThat(body.path("hasMore").asBoolean()).isTrue();
            // provider 语义是"取到 seq N 为止向过去数 count 条"，所以下一页从 最旧-1 开始
            assertThat(body.path("nextCursor").asText()).isEqualTo("999");
            // 往更新方向翻页要传"下一页窗口的末尾"，即 newest + count = 1029 + 30
            assertThat(body.path("prevCursor").asText()).isEqualTo("1059");
        }
    }

    @Test
    void shortPageMeansNoMoreBackwards() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.respondWith(seqRange(5, 3));
            JsonNode body = mapper.readTree(harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 30))).message());

            assertThat(body.path("count").asInt()).isEqualTo(3);
            assertThat(body.path("hasMore").asBoolean()).isFalse();
            assertThat(body.path("nextCursor").asText()).isEqualTo("4");
        }
    }

    @Test
    void reachingSeqOneStopsBackwardsPaging() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.respondWith(seqRange(1, 3));
            JsonNode body = mapper.readTree(harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692"))).message());

            assertThat(body.path("nextCursor").asText()).isEmpty();
        }
    }

    // ---- A7 ----

    @Test
    void retcode1200IsAnEmptyPageNotAnError() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.respondRaw("{\"status\":\"failed\",\"retcode\":1200,\"data\":{}}");
            ToolResult result = harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692")));

            assertThat(result.success()).isTrue();
            JsonNode body = mapper.readTree(result.message());
            assertThat(body.path("count").asInt()).isZero();
            assertThat(body.path("hasMore").asBoolean()).isFalse();
            assertThat(body.path("nextCursor").asText()).isEmpty();
        }
    }

    // ---- A8 ----

    @Test
    void timeoutIsNotRetriedWithSmallerPage() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.failWith(new TimeoutException("provider 不响"));
            ToolResult result = harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 100)));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("HISTORY_TIMEOUT");
            // 关键：只发了一次，没有 1000→300→100 那种降级
            assertThat(harness.client.requests).hasSize(1);
            JsonNode body = mapper.readTree(result.message());
            assertThat(body.path("outcome").asText()).isEqualTo("TIMEOUT");
            assertThat(body.path("attempt").path("count").asInt()).isEqualTo(100);
        }
    }

    // ---- A9 / A10 ----

    @Test
    void threeConsecutiveTimeoutsOpenTheCircuitAndTheFourthSendsNothing() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.failWith(new TimeoutException("provider 不响"));
            for (int i = 0; i < 3; i++) {
                ToolResult failed = harness.tool().execute(call(Map.of(
                        "chatType", "GROUP", "targetId", "955432692")));
                assertThat(failed.code()).isEqualTo("HISTORY_TIMEOUT");
            }
            assertThat(harness.client.requests).hasSize(3);

            ToolResult blocked = harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692")));

            assertThat(blocked.success()).isFalse();
            assertThat(blocked.code()).isEqualTo("HISTORY_CIRCUIT_OPEN");
            assertThat(harness.client.requests).as("熔断期间不得发出请求").hasSize(3);
            JsonNode body = mapper.readTree(blocked.message());
            assertThat(body.path("retryAfterMs").asLong()).isPositive();
        }
    }

    @Test
    void circuitReopensAfterCooldownAndSuccessResetsTheStreak() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.failWith(new TimeoutException("provider 不响"));
            for (int i = 0; i < 3; i++) {
                harness.tool().execute(call(Map.of("chatType", "GROUP", "targetId", "955432692")));
            }
            assertThat(harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692"))).code())
                    .isEqualTo("HISTORY_CIRCUIT_OPEN");

            harness.clock.addAndGet(QqRemoteHistoryTool.CIRCUIT_COOLDOWN_MS + 1);
            harness.client.respondWith(seqRange(1000, 2));
            ToolResult recovered = harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692")));
            assertThat(recovered.success()).as(recovered.message()).isTrue();

            // 成功后连续计数清零：再超时 1 次不应立刻熔断
            harness.client.failWith(new TimeoutException("又超时了"));
            assertThat(harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692"))).code())
                    .isEqualTo("HISTORY_TIMEOUT");
        }
    }

    // ---- A11 ----

    @Test
    void secondConcurrentCallForSameAccountIsBusy() throws Exception {
        try (Harness harness = new Harness()) {
            CompletableFuture<JsonNode> pending = new CompletableFuture<>();
            harness.client.hold(pending);

            Thread first = new Thread(() -> harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692"))));
            first.start();
            awaitRequests(harness.client, 1);

            ToolResult second = harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 30)));

            assertThat(second.success()).isFalse();
            assertThat(second.code()).isEqualTo("HISTORY_BUSY");
            assertThat(harness.client.requests).as("被拒的那次不应发出请求").hasSize(1);

            pending.complete(mapper.readTree(okBody(seqRange(1000, 2))));
            first.join();
        }
    }

    // ---- A12 ----

    @Test
    void reverseOrderOnlyChangesDisplayOrderNotCursors() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.respondWith(seqRange(1000, 10));
            JsonNode forward = mapper.readTree(harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 10,
                    "reverseOrder", false))).message());

            Harness reversed = new Harness();
            try (reversed) {
                reversed.client.respondWith(seqRange(1000, 10));
                JsonNode backward = mapper.readTree(reversed.tool().execute(call(Map.of(
                        "chatType", "GROUP", "targetId", "955432692", "count", 10,
                        "reverseOrder", true))).message());

                assertThat(backward.path("nextCursor").asText())
                        .isEqualTo(forward.path("nextCursor").asText());
                assertThat(backward.path("prevCursor").asText())
                        .isEqualTo(forward.path("prevCursor").asText());
            }
        }
    }

    @Test
    void snowlumaUsesMessageIdAnchorsAndRemovesTheInclusiveBoundary() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.providerName = "SnowLuma";
            harness.client.respondWith(seqRange(100, 3));
            JsonNode first = mapper.readTree(harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 3))).message());
            assertThat(first.path("historyDialect").asText()).isEqualTo("snowluma");
            assertThat(first.path("nextCursor").asText()).isEqualTo("snow:older:100");
            assertThat(harness.client.requests.getFirst())
                    .doesNotContainKeys("message_seq", "reverseOrder");

            harness.client.respondWith(seqRange(97, 4));
            JsonNode older = mapper.readTree(harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 3,
                    "cursor", first.path("nextCursor").asText()))).message());
            assertThat(harness.client.requests.get(1))
                    .containsEntry("message_id", 100L)
                    .containsEntry("reverse_order", true)
                    .containsEntry("count", 4);
            assertThat(older.path("messages")).hasSize(3);
            assertThat(older.path("messages").get(0).path("messageId").asText()).isEqualTo("97");
            assertThat(older.path("messages").get(2).path("messageId").asText()).isEqualTo("99");
            assertThat(older.path("prevCursor").asText()).isEqualTo("snow:newer:99");

            harness.client.respondWith(seqRange(99, 4));
            JsonNode newer = mapper.readTree(harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 3,
                    "reverseOrder", true, "cursor", older.path("prevCursor").asText()))).message());
            assertThat(harness.client.requests.get(2)).containsEntry("reverse_order", false);
            assertThat(newer.path("messages").get(0).path("messageId").asText()).isEqualTo("102");
            assertThat(newer.path("messages").get(2).path("messageId").asText()).isEqualTo("100");
        }
    }

    @Test
    void unknownProviderCanReadFirstPageButCannotInventPagingOrTreat1200AsEmpty() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.providerName = "OtherProvider";
            harness.client.respondWith(seqRange(100, 2));
            ToolResult first = harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 2)));
            JsonNode body = mapper.readTree(first.message());
            assertThat(first.success()).isTrue();
            assertThat(body.path("pagingSupported").asBoolean()).isFalse();
            assertThat(body.path("hasMore").isNull()).isTrue();
            assertThat(harness.client.requests.getFirst())
                    .doesNotContainKeys("message_seq", "message_id", "reverseOrder", "reverse_order");

            ToolResult next = harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "cursor", "999")));
            assertThat(next.success()).isFalse();
            assertThat(next.code()).isEqualTo("HISTORY_DIALECT_UNKNOWN");
            assertThat(harness.client.requests).hasSize(1);

            harness.client.respondRaw("{\"status\":\"failed\",\"retcode\":1200,\"data\":null}");
            ToolResult failed = harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692")));
            assertThat(failed.success()).isFalse();
            assertThat(failed.code()).isEqualTo("HISTORY_FAILED");
        }
    }

    @Test
    void unknownProviderOrdersByTimestampWhenAvailable() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.providerName = "OtherProvider";
            harness.client.respondWith(seqRange(100, 3));
            JsonNode body = mapper.readTree(harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 3,
                    "reverseOrder", true))).message());
            assertThat(body.path("orderSupported").asBoolean()).isTrue();
            assertThat(body.path("messages").get(0).path("messageId").asText()).isEqualTo("102");
            assertThat(body.path("messages").get(2).path("messageId").asText()).isEqualTo("100");
        }
    }

    @Test
    void unknownProviderPreservesOrderWhenATimestampIsMissing() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.providerName = "OtherProvider";
            List<Map<String, Object>> messages = seqRange(100, 2);
            messages.getFirst().remove("time");
            harness.client.respondWith(messages);
            JsonNode body = mapper.readTree(harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 2,
                    "reverseOrder", true))).message());
            assertThat(body.path("orderSupported").asBoolean()).isFalse();
            assertThat(body.path("orderHint").asText()).isNotBlank();
            assertThat(body.path("messages").get(0).path("messageId").asText()).isEqualTo("100");
        }
    }

    @Test
    void snowlumaAcceptsNegativeMessageIdsAndRejectsForeignCursors() throws Exception {
        try (Harness harness = new Harness()) {
            harness.client.providerName = "SnowLuma";
            List<Map<String, Object>> messages = seqRange(100, 2);
            messages.getFirst().put("message_id", -123);
            harness.client.respondWith(messages);
            JsonNode first = mapper.readTree(harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 2))).message());
            assertThat(first.path("nextCursor").asText()).isEqualTo("snow:older:-123");
            ToolResult invalid = harness.tool().execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "cursor", "999")));
            assertThat(invalid.success()).isFalse();
            assertThat(invalid.code()).isEqualTo("INVALID_ARGUMENT");
        }
    }

    @Test
    void detectsHistoryDialectsSeparatelyForDifferentAccounts() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("multi.db"));
             QqExtrasStore extras = new QqExtrasStore(tempDir.resolve("multi-extras.db"))) {
            FakeClient snow = new FakeClient();
            snow.providerName = "SnowLuma";
            snow.respondWith(seqRange(100, 2));
            FakeClient llbot = new FakeClient();
            llbot.respondWith(seqRange(200, 2));
            AccountRegistry registry = new AccountRegistry(
                    (config, listener) -> "snow".equals(config.accountId()) ? snow : llbot,
                    event -> { });
            registry.reconcile(List.of(
                    new AccountConfig("snow", URI.create("ws://127.0.0.1:3001"), "", true),
                    new AccountConfig("llbot", URI.create("ws://127.0.0.1:3002"), "", true)));
            QqRemoteHistoryTool tool = new QqRemoteHistoryTool(
                    new QqToolContext(registry, board, board, tempDir, extras));
            ToolResult snowResult = tool.execute(call(Map.of(
                    "accountId", "snow", "chatType", "GROUP", "targetId", "10", "count", 2)));
            ToolResult llbotResult = tool.execute(call(Map.of(
                    "accountId", "llbot", "chatType", "GROUP", "targetId", "10", "count", 2)));
            assertThat(mapper.readTree(snowResult.message()).path("historyDialect").asText())
                    .isEqualTo("snowluma");
            assertThat(mapper.readTree(llbotResult.message()).path("historyDialect").asText())
                    .isEqualTo("llbot");
            assertThat(snow.requests.getFirst()).doesNotContainKey("reverseOrder");
            assertThat(llbot.requests.getFirst()).containsKey("reverseOrder");
            registry.close();
        }
    }

    // ---- helpers ----

    private static void awaitRequests(FakeClient client, int expected) throws InterruptedException {
        for (int i = 0; i < 200 && client.requests.size() < expected; i++) {
            Thread.sleep(10);
        }
        assertThat(client.requests).hasSize(expected);
    }

    /** {seq: s, seq: s+1, ...} as provider message objects (ascending == provider default order). */
    private static List<Map<String, Object>> seqRange(long start, int size) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            long seq = start + i;
            Map<String, Object> sender = new LinkedHashMap<>();
            sender.put("user_id", 100 + i);
            sender.put("nickname", "u" + i);
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("type", "text");
            text.put("data", Map.of("text", "msg-" + seq));
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("message_id", seq);
            message.put("message_seq", seq);
            message.put("time", 1_790_000_000L + seq);
            message.put("sender", sender);
            message.put("message", List.of(text));
            messages.add(message);
        }
        return messages;
    }

    private String okBody(List<Map<String, Object>> messages) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("status", "ok");
        root.put("retcode", 0);
        root.put("data", Map.of("messages", messages));
        return mapper.writeValueAsString(root);
    }

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), arguments, AgentIdentity.external());
    }

    /** One account + one tool instance + a scriptable fake provider. */
    private final class Harness implements AutoCloseable {

        private final SqliteMessageBoardStore board;
        private final QqExtrasStore extras;
        private final FakeClient client = new FakeClient();
        private final AtomicLong clock = new AtomicLong(1_000_000L);
        private final QqRemoteHistoryTool tool;

        private Harness() {
            try {
                board = new SqliteMessageBoardStore(tempDir.resolve("bot-" + System.nanoTime() + ".db"));
                extras = new QqExtrasStore(tempDir.resolve("extras-" + System.nanoTime() + ".db"));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            AccountRegistry registry = new AccountRegistry((config, listener) -> client, event -> { });
            registry.reconcile(List.of(new AccountConfig(
                    "bot-1", URI.create("ws://127.0.0.1:3001"), "token", true)));
            QqToolContext context = new QqToolContext(registry, board, board, tempDir, extras);
            tool = new QqRemoteHistoryTool(context, clock::get);
        }

        private QqRemoteHistoryTool tool() {
            return tool;
        }

        @Override
        public void close() {
            board.close();
            extras.close();
        }
    }

    private final class FakeClient extends OneBot11Client {

        /** History calls only (params), so assertions ignore the registry handshake. */
        private final List<Map<String, Object>> requests = new ArrayList<>();
        private final List<String> actions = new ArrayList<>();
        private List<Map<String, Object>> messages = List.of();
        private String rawBody;
        private Exception failure;
        private CompletableFuture<JsonNode> held;
        private String providerName = "LLBot";

        private FakeClient() {
            super(URI.create("ws://127.0.0.1:1"), "", event -> { });
        }

        private void respondWith(List<Map<String, Object>> page) {
            this.messages = page;
            this.rawBody = null;
            this.failure = null;
        }

        private void respondRaw(String body) {
            this.rawBody = body;
            this.failure = null;
        }

        private void failWith(Exception error) {
            this.failure = error;
        }

        private void hold(CompletableFuture<JsonNode> future) {
            this.held = future;
        }

        @Override
        public void start() {
        }

        @Override
        public void awaitReady(Duration timeout) {
        }

        @Override
        public CompletableFuture<JsonNode> sendAction(String action, Map<String, Object> params) {
            actions.add(action);
            if ("get_version_info".equals(action)) {
                return CompletableFuture.completedFuture(mapper.valueToTree(Map.of(
                        "status", "ok", "retcode", 0, "data", Map.of("app_name", providerName))));
            }
            // Only the history action is scripted; the registry handshake (get_login_info and
            // friends) must keep succeeding so the tool can actually reach sendAction.
            if (!action.endsWith("_msg_history")) {
                return Completed.json(mapper, "{\"status\":\"ok\",\"retcode\":0,\"data\":{}}");
            }
            requests.add(new LinkedHashMap<>(params));
            if (held != null) {
                return held;
            }
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            if (rawBody != null) {
                return Completed.json(mapper, rawBody);
            }
            try {
                return Completed.json(mapper, okBody(messages));
            } catch (Exception broken) {
                return CompletableFuture.failedFuture(broken);
            }
        }

        @Override
        public void close() {
        }
    }

    private static final class Completed {

        private Completed() {
        }

        static CompletableFuture<JsonNode> json(ObjectMapper mapper, String raw) {
            try {
                return CompletableFuture.completedFuture(mapper.readTree(raw));
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }
}
