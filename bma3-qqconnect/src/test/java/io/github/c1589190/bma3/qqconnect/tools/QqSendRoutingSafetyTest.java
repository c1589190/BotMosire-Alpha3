package io.github.c1589190.bma3.qqconnect.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.AgentIdentity;
import io.github.c1589190.agentmosire.agentlib.permission.AgentPermissionSet;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.core.message.DeliveryStatus;
import io.github.c1589190.bma3.core.message.Message;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.core.store.SqliteMessageBoardStore;
import io.github.c1589190.bma3.qqconnect.account.AccountConfig;
import io.github.c1589190.bma3.qqconnect.account.AccountRegistry;
import io.github.c1589190.bma3.qqconnect.onebot.OneBot11Client;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QqSendRoutingSafetyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SourceAddress GROUP =
            SourceAddress.parse("qq_group/bot-1/123");
    private static final SourceAddress PRIVATE =
            SourceAddress.parse("qq_private/bot-1/456");

    @TempDir
    Path temp;

    @Test
    void rejectsNonOneBotSourcesAndMalformedShapesBeforeBoardOrProvider() {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(temp.resolve("bad.db"))) {
            RecordingProvider provider = new RecordingProvider();
            QqToolContext context = context(board, provider);

            for (String raw : List.of(
                    "qq_official_group/bot-1/123",
                    "qq_official_private/bot-1/456",
                    "bilibili/account/123",
                    "qq_group/bot-1/123/extra",
                    "qq_group/bot-1/not-a-number",
                    "qq_private/bot-1/0")) {
                assertThatThrownBy(() -> QqSendSupport.sendParts(
                        context, SourceAddress.parse(raw), "hello", "", "send_group_msg", Map.of()))
                        .as(raw)
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> QqSendSupport.sendParts(
                    context, GROUP, "hello", "", "send_private_msg", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QqSendSupport.sendParts(
                    context, GROUP, "hello", "", "send_group_msg", Map.of("group_id", 999L)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QqSendSupport.sendParts(
                    context, PRIVATE, "hello", "", "send_private_msg",
                    Map.of("group_id", 999L, "user_id", 44L)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> QqSendSupport.route(
                    SourceAddress.parse("qq_official_group/bot-1/123"),
                    "upload_group_file", Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(provider.sendCalls).isZero();
            assertThat(board.read(GROUP, 0, 10)).isEmpty();
            assertThat(board.read(PRIVATE, 0, 10)).isEmpty();
        }
    }

    @Test
    void hostileUserIdAndPathsAreNeverEchoedByInvalidArgumentResults() {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(temp.resolve("hostile.db"))) {
            RecordingProvider provider = new RecordingProvider();
            QqToolContext context = context(board, provider);
            String secret = "HOSTILE_SECRET_VALUE";

            ToolResult privateSend = new QqPrivateSendTool(context).execute(call(Map.of(
                    "userId", secret,
                    "text", "hello")));
            assertSafeInvalid(privateSend, secret);

            ToolResult image = new QqConversationSendImageTool(context).execute(call(Map.of(
                    "source", GROUP.format(),
                    "imagePath", secret + "\u0000.png")));
            assertSafeInvalid(image, secret);

            ToolResult file = new QqConversationSendFileTool(context).execute(call(Map.of(
                    "source", GROUP.format(),
                    "filePath", secret + "\u0000.bin")));
            assertSafeInvalid(file, secret);

            ToolResult number = QqToolSupport.invalid(
                    new NumberFormatException("For input string: \"" + secret + "\""));
            assertSafeInvalid(number, secret);

            ToolResult path = QqToolSupport.invalid(
                    new InvalidPathException(secret + "/x", "Nul character not allowed"));
            assertSafeInvalid(path, secret);

            ToolResult arbitrary = QqToolSupport.invalid(
                    new IllegalArgumentException(secret + ": arbitrary provider text"));
            assertSafeInvalid(arbitrary, secret);

            assertThat(provider.sendCalls).isZero();
            assertThat(board.read(GROUP, 0, 10)).isEmpty();
            assertThat(board.read(PRIVATE, 0, 10)).isEmpty();
        }
    }

    private static void assertSafeInvalid(ToolResult result, String secret) {
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(result.message())
                .doesNotContain(secret)
                .contains("参数无效");
    }

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(
                AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(),
                arguments,
                AgentIdentity.external());
    }

    @Test
    void disconnectedClientFailsBeforeDispatchAndIsRecordedAsNotDispatched() {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(temp.resolve("offline.db"))) {
            RecordingProvider provider = new RecordingProvider();
            provider.connected = false;
            QqToolContext context = context(board, provider);

            List<QqDelivery.Outcome> outcomes =
                    QqSendSupport.sendParts(context, GROUP, "hello", "", "send_group_msg", Map.of());

            assertThat(outcomes).singleElement().satisfies(outcome -> {
                assertThat(outcome.status()).isEqualTo(DeliveryStatus.FAILED_BEFORE_SEND);
                assertThat(outcome.errorCode()).isEqualTo(QqDelivery.NOT_DISPATCHED);
                assertThat(outcome.recorded()).isTrue();
                assertThat(outcome.dispatched()).isFalse();
                assertThat(outcome.partIndex()).isEqualTo(1);
                assertThat(outcome.partTotal()).isEqualTo(1);
            });
            assertThat(provider.sendCalls).isZero();
            Message stored = board.read(GROUP, 0, 10).getFirst();
            assertThat(stored.delivery().status()).isEqualTo(DeliveryStatus.FAILED_BEFORE_SEND);
            assertThat(stored.delivery().errorCode()).isEqualTo(QqDelivery.NOT_DISPATCHED);
            assertThat(stored.externalMessageId()).isEmpty();
        }
    }

    @Test
    void routesOnlyTheTwoOneBotPlatformsWithExactTargetsAndTemporarySessionExtras() {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(temp.resolve("ok.db"))) {
            RecordingProvider provider = new RecordingProvider();
            QqToolContext context = context(board, provider);

            QqSendSupport.sendParts(context, GROUP, "group", "", "send_group_msg", Map.of());
            assertThat(provider.lastParams.path("group_id").asLong()).isEqualTo(123L);
            assertThat(provider.lastParams.has("user_id")).isFalse();

            QqSendSupport.sendParts(context, PRIVATE, "private", "", "send_private_msg", Map.of());
            assertThat(provider.lastParams.path("user_id").asLong()).isEqualTo(456L);
            assertThat(provider.lastParams.has("group_id")).isFalse();

            QqSendSupport.sendParts(context, PRIVATE, "temp", "", "send_private_msg",
                    Map.of("group_id", 789L));
            assertThat(provider.lastParams.path("user_id").asLong()).isEqualTo(456L);
            assertThat(provider.lastParams.path("group_id").asLong()).isEqualTo(789L);
            assertThat(provider.sendCalls).isEqualTo(3);

            QqSendSupport.SendRoute groupUpload =
                    QqSendSupport.route(GROUP, "upload_group_file", Map.of());
            assertThat(groupUpload.targetParams()).containsEntry("group_id", 123L);
            QqSendSupport.SendRoute privateUpload =
                    QqSendSupport.route(PRIVATE, "upload_private_file", Map.of());
            assertThat(privateUpload.targetParams()).containsEntry("user_id", 456L);
        }
    }

    private QqToolContext context(SqliteMessageBoardStore board, RecordingProvider provider) {
        AccountRegistry registry = new AccountRegistry((config, listener) -> provider, event -> { });
        registry.reconcile(List.of(new AccountConfig(
                "bot-1", URI.create("ws://127.0.0.1:1"), "token", true)));
        return new QqToolContext(registry, board, board, temp, null,
                () -> new QqSendPolicy(3000, "reject"), null);
    }

    private static final class RecordingProvider extends OneBot11Client {

        private static final JsonNode LOGIN = parse("""
                {"status":"ok","retcode":0,"data":{"user_id":"10001","nickname":"bot"}}
                """);

        private int sendCalls;
        private boolean connected = true;
        private JsonNode lastParams = JSON.createObjectNode();

        private RecordingProvider() {
            super(URI.create("ws://127.0.0.1:1"), "", event -> { });
        }

        @Override
        public void start() {
        }

        @Override
        public void awaitReady(java.time.Duration timeout) {
        }

        @Override
        public boolean connected() {
            return connected;
        }

        @Override
        public CompletableFuture<JsonNode> sendAction(String action, Map<String, Object> params) {
            if ("get_login_info".equals(action)) {
                return CompletableFuture.completedFuture(LOGIN);
            }
            sendCalls++;
            lastParams = JSON.valueToTree(params);
            return CompletableFuture.completedFuture(parse("""
                    {"status":"ok","retcode":0,"data":{"message_id":9001}}
                    """));
        }

        @Override
        public void close() {
        }
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
