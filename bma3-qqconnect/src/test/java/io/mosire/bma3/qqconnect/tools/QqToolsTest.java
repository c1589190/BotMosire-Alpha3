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
import java.nio.file.Files;
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

            QqExtrasStore extras = new QqExtrasStore(tempDir.resolve("extras.db"));
            QqToolContext context = new QqToolContext(registry, board, board, tempDir, extras);
            List<AgentTool> tools = QqTools.all(context);
            assertThat(tools).extracting(AgentTool::name)
                    .contains("qq_account_list", "qq_account_status", "qq_account_login_info",
                            "qq_group_list", "qq_friend_list",
                            "qq_thread_list", "qq_conversation_history",
                            "qq_recent_messages", "qq_message_get",
                            "qq_conversation_read", "qq_conversation_send",
                            "qq_conversation_send_image", "qq_reply", "qq_private_send",
                            "qq_user_note_get", "qq_user_note_list", "qq_user_note_upsert",
                            "qq_conversation_ack");

            ToolResult threads = tool(tools, "qq_thread_list").execute(call(Map.of()));
            assertThat(threads.success()).isTrue();
            assertThat(mapper.readTree(threads.message()).path("threads")).hasSize(1);

            ToolResult note = tool(tools, "qq_user_note_upsert").execute(call(Map.of(
                    "userId", "20002", "note", "Alice", "tags", List.of("vip"))));
            assertThat(note.success()).isTrue();
            assertThat(mapper.readTree(note.message()).has("priority")).isFalse();
            ToolResult noteGet = tool(tools, "qq_user_note_get").execute(
                    call(Map.of("userId", "20002")));
            assertThat(mapper.readTree(noteGet.message()).path("note").asText()).isEqualTo("Alice");

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
            assertThat(mapper.readTree(sent.message()).get(0).path("state").asText()).isEqualTo("SENT");
            assertThat(client.sentAction).isEqualTo("send_private_msg");
            assertThat(client.sentParams.path("user_id").asLong()).isEqualTo(20002L);

            ToolResult afterSend = tool(tools, "qq_conversation_read").execute(
                    call(Map.of("source", source.format(), "limit", 10)));
            assertThat(mapper.readTree(afterSend.message()).path("messages")).isEmpty();

            board.append(new InboundMessage(source, "m2", Instant.now(),
                    List.of(new Segment("image", "[图片]",
                            Map.of("file", "a.image", "url", "https://cdn/a?rkey=1"))),
                    Direction.INBOUND, "20002", "Alice"));
            JsonNode mediaMessage = mapper.readTree(tool(tools, "qq_message_get").execute(
                    call(Map.of("source", source.format(), "seq", 3,
                            "includeSegments", true))).message());
            assertThat(mediaMessage.path("sender").path("id").asText()).isEqualTo("20002");
            assertThat(mediaMessage.path("sender").path("name").asText()).isEqualTo("Alice");
            assertThat(mediaMessage.path("self").asBoolean()).isFalse();
            assertThat(mediaMessage.path("segments").get(0).path("data").path("file").asText())
                    .as("媒体引用只在显式 includeSegments 时出现")
                    .isEqualTo("a.image");

            // The outbound row learned its platform id after a successful send.
            var history = mapper.readTree(tool(tools, "qq_conversation_history").execute(
                    call(Map.of("source", source.format(), "limit", 10))).message());
            var outbound = history.path("messages").get(1);
            assertThat(outbound.path("direction").asText()).isEqualTo("OUT");
            assertThat(outbound.path("self").asBoolean()).isTrue();
            assertThat(outbound.path("externalMessageId").asText()).isEqualTo("777");

            Path qr = tempDir.resolve("qr.png");
            Files.write(qr, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            ToolResult image = tool(tools, "qq_conversation_send_image").execute(
                    call(Map.of("source", source.format(), "imagePath", qr.toString(),
                            "text", "扫码登录")));
            assertThat(image.success()).isTrue();
            assertThat(mapper.readTree(image.message()).path("state").asText()).isEqualTo("SENT");
            assertThat(client.sentParams.path("message").isArray()).isTrue();
            assertThat(client.sentParams.path("message").toString()).contains("base64://");

            ToolResult outside = tool(tools, "qq_conversation_send_image").execute(
                    call(Map.of("source", source.format(), "imagePath", "/etc/hosts")));
            assertThat(outside.success()).isFalse();
            assertThat(outside.code()).isEqualTo("INVALID_ARGUMENT");

            client.failure = new IllegalStateException("timeout");
            ToolResult unknown = tool(tools, "qq_conversation_send").execute(
                    call(Map.of("source", source.format(), "text", "reply-2")));
            assertThat(unknown.success()).isTrue();
            assertThat(mapper.readTree(unknown.message()).get(0).path("state").asText())
                    .isEqualTo("UNKNOWN");

            client.failure = null;
            client.response = mapper.readTree("""
                    {"status":"ok","retcode":0,"data":{"message_id":778}}
                    """);
            ToolResult exactLimit = tool(tools, "qq_conversation_send").execute(
                    call(Map.of("source", source.format(), "text", "😀".repeat(3000))));
            assertThat(exactLimit.success()).isTrue();
            assertThat(mapper.readTree(exactLimit.message()).get(0).path("state").asText())
                    .isEqualTo("SENT");

            ToolResult tooLong = tool(tools, "qq_conversation_send").execute(
                    call(Map.of("source", source.format(), "text", "😀".repeat(3001))));
            assertThat(tooLong.success()).isFalse();
            assertThat(tooLong.code()).isEqualTo("TEXT_TOO_LONG");

            Path qrText = tempDir.resolve("qr-text.png");
            Files.write(qrText, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            ToolResult imageTooLong = tool(tools, "qq_conversation_send_image").execute(
                    call(Map.of("source", source.format(), "imagePath", qrText.toString(),
                            "text", "x".repeat(3001))));
            assertThat(imageTooLong.success()).isFalse();
            assertThat(imageTooLong.code()).isEqualTo("TEXT_TOO_LONG");

            // Reading the message board no longer needs a live connection: an unknown account
            // simply has nothing to read.
            ToolResult offline = tool(tools, "qq_conversation_read").execute(
                    call(Map.of("source", "qq_private/bot-2/20002")));
            assertThat(offline.success()).isTrue();
            assertThat(mapper.readTree(offline.message()).path("messages")).isEmpty();
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
