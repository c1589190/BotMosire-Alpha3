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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-LLBot-driven rule: a 1000-message page degrades to 300, then 100. */
class QqRemoteHistoryToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aFailedLargePageDegradesTo300() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("bot.db"));
             QqExtrasStore extras = new QqExtrasStore(tempDir.resolve("extras.db"))) {
            FakeClient client = new FakeClient();
            AccountRegistry registry = new AccountRegistry((config, listener) -> client, event -> { });
            registry.reconcile(List.of(new AccountConfig(
                    "bot-1", URI.create("ws://127.0.0.1:3001"), "token", true)));
            QqToolContext context = new QqToolContext(registry, board, board, tempDir, extras);

            ToolResult result = new QqRemoteHistoryTool(context).execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 1000)));
            assertThat(result.success()).as(result.message()).isTrue();
            JsonNode body = mapper.readTree(result.message());
            assertThat(client.counts).containsExactly(1000, 300);
            assertThat(body.path("requestedCount").asInt()).isEqualTo(1000);
            assertThat(body.path("fetchedCount").asInt()).isEqualTo(300);
            assertThat(body.path("degradedFrom").asInt()).isEqualTo(1000);
            assertThat(body.path("count").asInt()).isEqualTo(1);
            assertThat(body.path("attempts")).hasSize(2);
            // P1-1：每一次尝试都要能看出"用什么 count、什么结果、多久、几条"
            JsonNode attempted = body.path("attemptDetails");
            assertThat(attempted).hasSize(2);
            assertThat(attempted.get(0).path("count").asInt()).isEqualTo(1000);
            assertThat(attempted.get(0).path("outcome").asText()).isNotBlank();
            assertThat(attempted.get(1).path("count").asInt()).isEqualTo(300);
            assertThat(attempted.get(1).path("outcome").asText()).isEqualTo("ok");
            assertThat(attempted.get(1).path("messages").asInt()).isEqualTo(1);
            assertThat(attempted.get(1).path("elapsedMs").asLong()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void anEmptyLargePageAlsoDegrades() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("bot2.db"));
             QqExtrasStore extras = new QqExtrasStore(tempDir.resolve("extras2.db"))) {
            FakeClient client = new FakeClient();
            client.emptyAt = 1000;
            AccountRegistry registry = new AccountRegistry((config, listener) -> client, event -> { });
            registry.reconcile(List.of(new AccountConfig(
                    "bot-1", URI.create("ws://127.0.0.1:3001"), "token", true)));
            QqToolContext context = new QqToolContext(registry, board, board, tempDir, extras);

            ToolResult result = new QqRemoteHistoryTool(context).execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 1000)));
            assertThat(result.success()).isTrue();
            JsonNode body = mapper.readTree(result.message());
            assertThat(client.counts).containsExactly(1000, 300);
            assertThat(body.path("degradedFrom").asInt()).isEqualTo(1000);
            assertThat(body.path("count").asInt()).isEqualTo(1);
        }
    }

    @Test
    void aLargePageRetcode1200AlsoDegrades() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("bot3.db"));
             QqExtrasStore extras = new QqExtrasStore(tempDir.resolve("extras3.db"))) {
            FakeClient client = new FakeClient();
            client.retcode1200At = 1000;
            AccountRegistry registry = new AccountRegistry((config, listener) -> client, event -> { });
            registry.reconcile(List.of(new AccountConfig(
                    "bot-1", URI.create("ws://127.0.0.1:3001"), "token", true)));
            QqToolContext context = new QqToolContext(registry, board, board, tempDir, extras);

            ToolResult result = new QqRemoteHistoryTool(context).execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 1000)));
            assertThat(result.success()).isTrue();
            JsonNode body = mapper.readTree(result.message());
            assertThat(client.counts).containsExactly(1000, 300);
            assertThat(body.path("degradedFrom").asInt()).isEqualTo(1000);
            assertThat(body.path("count").asInt()).isEqualTo(1);
        }
    }

    @Test
    void aTimeoutIsClassifiedAsTimeoutNotAsAGenericFailure() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("bot3.db"));
             QqExtrasStore extras = new QqExtrasStore(tempDir.resolve("extras3.db"))) {
            FakeClient client = new FakeClient();
            client.timeoutAbove = 0;   // 所有 count 都抛 TimeoutException
            AccountRegistry registry = new AccountRegistry((config, listener) -> client, event -> { });
            registry.reconcile(List.of(new AccountConfig(
                    "bot-1", URI.create("ws://127.0.0.1:3001"), "token", true)));
            QqToolContext context = new QqToolContext(registry, board, board, tempDir, extras);

            ToolResult result = new QqRemoteHistoryTool(context).execute(call(Map.of(
                    "chatType", "GROUP", "targetId", "955432692", "count", 1000)));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("HISTORY_FAILED");
            JsonNode body = mapper.readTree(result.message());
            assertThat(body.path("lastError").asText()).isEqualTo("TIMEOUT");
            assertThat(body.path("attempts")).hasSize(3);
            for (JsonNode attempt : body.path("attempts")) {
                assertThat(attempt.path("outcome").asText()).isEqualTo("TIMEOUT");
            }
            assertThat(body.path("hint").asText()).contains("TIMEOUT");
        }
    }

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), arguments, AgentIdentity.external());
    }

    private final class FakeClient extends OneBot11Client {

        private final List<Integer> counts = new ArrayList<>();
        private int emptyAt = -1;
        private int retcode1200At = -1;
        /** >=0 时：count 大于它的请求都抛 TimeoutException（模拟 provider 不响）。 */
        private int timeoutAbove = -1;

        private FakeClient() {
            super(URI.create("ws://127.0.0.1:1"), "", event -> { });
        }

        @Override
        public void start() {
        }

        @Override
        public void awaitReady(Duration timeout) {
        }

        @Override
        public CompletableFuture<JsonNode> sendAction(String action, Map<String, Object> params) {
            int count = params.get("count") instanceof Number number
                    ? number.intValue() : 0;
            if ("get_group_msg_history".equals(action)) {
                counts.add(count);
            }
            if (timeoutAbove >= 0 && count > timeoutAbove) {
                return CompletableFuture.failedFuture(
                        new java.util.concurrent.TimeoutException("provider 不响"));
            }
            if (count == emptyAt) {
                return Completed.json(mapper, "{\"status\":\"ok\",\"retcode\":0,"
                        + "\"data\":{\"messages\":[]}}");
            }
            if (count == retcode1200At) {
                return Completed.json(mapper, "{\"status\":\"failed\",\"retcode\":1200,"
                        + "\"data\":{}}");
            }
            if (count > 300) {
                return CompletableFuture.failedFuture(new IllegalStateException("timeout"));
            }
            return Completed.json(mapper, "{\"status\":\"ok\",\"retcode\":0,\"data\":{"
                    + "\"messages\":[{\"message_id\":1,\"message_seq\":100,"
                    + "\"sender\":{\"user_id\":1,\"nickname\":\"x\"},"
                    + "\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"hi\"}}]}]}}");
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
