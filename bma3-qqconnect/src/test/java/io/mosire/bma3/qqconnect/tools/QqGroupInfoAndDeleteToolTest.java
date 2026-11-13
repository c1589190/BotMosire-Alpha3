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

/**
 * P1-2 / P2-4 的新工具：{@code qq_group_info}、{@code qq_delete_msg}。
 *
 * <p>重点验证的是**失败语义**，不是 happy path：撤回的三态（DELETED / FAILED / UNKNOWN）
 * 必须分得清，UNKNOWN 绝不能伪装成成功或失败。
 */
class QqGroupInfoAndDeleteToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void groupInfoNormalisesTheProviderResponse() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("g.db"));
             QqExtrasStore extras = new QqExtrasStore(tempDir.resolve("g-extras.db"))) {
            FakeClient client = new FakeClient();
            QqToolContext context = context(client, board, extras);

            ToolResult result = new QqGroupInfoTool(context).execute(call(Map.of("groupId", 955432692)));

            assertThat(result.success()).as(result.message()).isTrue();
            JsonNode body = mapper.readTree(result.message());
            assertThat(body.path("ok").asBoolean()).isTrue();
            assertThat(body.path("groupName").asText()).isEqualTo("E2E群");
            assertThat(body.path("memberCount").asInt()).isEqualTo(42);
            assertThat(client.actions).contains("get_group_info");
        }
    }

    @Test
    void groupInfoSaysWhatAProviderErrorProbablyMeans() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("g2.db"));
             QqExtrasStore extras = new QqExtrasStore(tempDir.resolve("g2-extras.db"))) {
            FakeClient client = new FakeClient();
            client.groupInfoRetcode = 102;
            QqToolContext context = context(client, board, extras);

            ToolResult result = new QqGroupInfoTool(context).execute(call(Map.of("groupId", 123)));
            JsonNode body = mapper.readTree(result.message());

            assertThat(body.path("ok").asBoolean()).isFalse();
            assertThat(body.path("retcode").asInt()).isEqualTo(102);
            assertThat(body.path("hint").asText()).contains("不在群");
        }
    }

    @Test
    void deleteSucceeds() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("d.db"));
             QqExtrasStore extras = new QqExtrasStore(tempDir.resolve("d-extras.db"))) {
            FakeClient client = new FakeClient();
            QqToolContext context = context(client, board, extras);

            ToolResult result = new QqDeleteMsgTool(context)
                    .execute(call(Map.of("messageId", "12345")));

            JsonNode body = mapper.readTree(result.message());
            assertThat(body.path("state").asText()).isEqualTo("DELETED");
            assertThat(body.path("retcode").asInt()).isZero();
        }
    }

    @Test
    void deleteIsRejectedLoudlyWhenTheProviderRefuses() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("d2.db"));
             QqExtrasStore extras = new QqExtrasStore(tempDir.resolve("d2-extras.db"))) {
            FakeClient client = new FakeClient();
            client.deleteRetcode = 100;
            client.deleteMessage = "消息已超过撤回时限";
            QqToolContext context = context(client, board, extras);

            ToolResult result = new QqDeleteMsgTool(context)
                    .execute(call(Map.of("messageId", "12345")));

            JsonNode body = mapper.readTree(result.message());
            assertThat(body.path("state").asText()).isEqualTo("FAILED");
            assertThat(body.path("retcode").asInt()).isEqualTo(100);
            assertThat(body.path("providerMessage").asText()).contains("撤回时限");
            assertThat(body.path("hint").asText()).contains("2 分钟");
        }
    }

    @Test
    void deleteTimesOutAsUnknownAndForbidsRetry() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("d3.db"));
             QqExtrasStore extras = new QqExtrasStore(tempDir.resolve("d3-extras.db"))) {
            FakeClient client = new FakeClient();
            client.deleteTimesOut = true;
            QqToolContext context = context(client, board, extras);

            ToolResult result = new QqDeleteMsgTool(context)
                    .execute(call(Map.of("messageId", "12345")));

            // 工具本身不报错（拿到了一个明确的三态结论），但状态必须是 UNKNOWN
            assertThat(result.success()).isTrue();
            JsonNode body = mapper.readTree(result.message());
            assertThat(body.path("state").asText()).isEqualTo("UNKNOWN");
            assertThat(body.path("hint").asText()).contains("不要自动重试");
        }
    }

    @Test
    void deleteRejectsANonNumericMessageIdWithoutCallingTheProvider() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("d4.db"));
             QqExtrasStore extras = new QqExtrasStore(tempDir.resolve("d4-extras.db"))) {
            FakeClient client = new FakeClient();
            QqToolContext context = context(client, board, extras);

            ToolResult result = new QqDeleteMsgTool(context)
                    .execute(call(Map.of("messageId", "out-abc")));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("INVALID_ARGUMENT");
            assertThat(client.actions).doesNotContain("delete_msg");
        }
    }

    private QqToolContext context(FakeClient client, SqliteMessageBoardStore board,
                                  QqExtrasStore extras) {
        AccountRegistry registry = new AccountRegistry((config, listener) -> client, event -> { });
        registry.reconcile(List.of(new AccountConfig(
                "bot-1", URI.create("ws://127.0.0.1:3001"), "token", true)));
        return new QqToolContext(registry, board, board, tempDir, extras);
    }

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), arguments, AgentIdentity.external());
    }

    private final class FakeClient extends OneBot11Client {

        private final List<String> actions = new ArrayList<>();
        private int groupInfoRetcode = 0;
        private int deleteRetcode = 0;
        private String deleteMessage = "";
        private boolean deleteTimesOut = false;

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
            actions.add(action);
            if ("get_group_info".equals(action)) {
                if (groupInfoRetcode != 0) {
                    return Completed.json(mapper, "{\"status\":\"failed\",\"retcode\":"
                            + groupInfoRetcode + ",\"message\":\"group not found\",\"data\":null}");
                }
                return Completed.json(mapper,
                        "{\"status\":\"ok\",\"retcode\":0,\"data\":{\"group_id\":955432692,"
                                + "\"group_name\":\"E2E群\",\"member_count\":42,"
                                + "\"max_member_count\":200}}");
            }
            if ("delete_msg".equals(action)) {
                if (deleteTimesOut) {
                    return CompletableFuture.failedFuture(
                            new java.util.concurrent.TimeoutException("no answer"));
                }
                if (deleteRetcode != 0) {
                    return Completed.json(mapper, "{\"status\":\"failed\",\"retcode\":"
                            + deleteRetcode + ",\"message\":\"" + deleteMessage + "\",\"data\":null}");
                }
                return Completed.json(mapper, "{\"status\":\"ok\",\"retcode\":0,\"data\":null}");
            }
            return Completed.json(mapper, "{\"status\":\"ok\",\"retcode\":0,\"data\":{}}");
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
