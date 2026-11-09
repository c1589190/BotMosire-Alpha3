package io.mosire.bma3.qqconnect.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.account.ConsumerId;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import io.mosire.bma3.qqconnect.account.AccountConfig;
import io.mosire.bma3.qqconnect.account.AccountRegistry;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QqToolsTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsAcksAndSendsThroughTheAccountRegistry() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("bot.db"))) {
            SourceAddress source = SourceAddress.parse("qq_private/bot-1/20002");
            board.append(new InboundMessage(source, "m1", Instant.now(),
                    List.of(new Segment("text", "hello")), Direction.INBOUND));

            FakeClient client = new FakeClient();
            AccountRegistry registry = new AccountRegistry((config, listener) -> client, event -> { });
            registry.reconcile(List.of(new AccountConfig(
                    "bot-1", URI.create("ws://127.0.0.1:3001"), "token", true)));

            QqToolContext context = new QqToolContext(registry, board, board);
            List<AgentTool> tools = QqTools.all(context);
            assertThat(tools).extracting(AgentTool::name)
                    .containsExactly("qq_account_list", "qq_conversation_read",
                            "qq_conversation_send", "qq_conversation_ack");

            ToolResult read = tool(tools, "qq_conversation_read").execute(
                    call(Map.of("source", source.format(), "limit", 10)));
            assertThat(read.success()).isTrue();
            JsonNode readJson = mapper.readTree(read.message());
            assertThat(readJson.path("messages")).hasSize(1);
            assertThat(readJson.path("messages").get(0).path("text").asText()).isEqualTo("hello");

            ToolResult acked = tool(tools, "qq_conversation_ack").execute(
                    call(Map.of("source", source.format(), "seq", 1)));
            assertThat(acked.success()).isTrue();
            assertThat(board.readSeq(new ConsumerId("external-mcp"), source)).isEqualTo(1L);

            client.response = mapper.readTree("""
                    {"status":"ok","retcode":0,"data":{"message_id":777}}
                    """);
            ToolResult sent = tool(tools, "qq_conversation_send").execute(
                    call(Map.of("source", source.format(), "text", "reply")));
            assertThat(sent.success()).isTrue();
            assertThat(mapper.readTree(sent.message()).path("state").asText()).isEqualTo("SENT");
            assertThat(client.sentAction).isEqualTo("send_private_msg");
            assertThat(client.sentParams.path("user_id").asLong()).isEqualTo(20002L);

            client.failure = new IllegalStateException("timeout");
            ToolResult unknown = tool(tools, "qq_conversation_send").execute(
                    call(Map.of("source", source.format(), "text", "reply-2")));
            assertThat(unknown.success()).isTrue();
            assertThat(mapper.readTree(unknown.message()).path("state").asText())
                    .isEqualTo("UNKNOWN");

            ToolResult mismatch = tool(tools, "qq_conversation_read").execute(
                    call(Map.of("source", "qq_private/bot-2/20002")));
            assertThat(mismatch.success()).isFalse();
            assertThat(mismatch.code()).isEqualTo("ACCOUNT_NOT_FOUND");
        }
    }

    private static AgentTool tool(List<AgentTool> tools, String name) {
        return tools.stream().filter(tool -> name.equals(tool.name())).findFirst().orElseThrow();
    }

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), arguments, AgentIdentity.external());
    }

    private static final class FakeClient extends OneBot11Client {

        private JsonNode response;
        private RuntimeException failure;
        private String sentAction;
        private JsonNode sentParams;
        private final AtomicBoolean closed = new AtomicBoolean();

        FakeClient() {
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
            sentAction = action;
            sentParams = new ObjectMapper().valueToTree(params);
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
