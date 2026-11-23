package io.github.c1589190.bma3.qqconnect.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.AgentIdentity;
import io.github.c1589190.agentmosire.agentlib.permission.AgentPermissionSet;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.agentlib.Bma3McpServer;
import io.github.c1589190.bma3.core.message.Delivery;
import io.github.c1589190.bma3.core.message.DeliveryStatus;
import io.github.c1589190.bma3.core.message.Direction;
import io.github.c1589190.bma3.core.message.InboundMessage;
import io.github.c1589190.bma3.core.message.Message;
import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.core.store.AppendResult;
import io.github.c1589190.bma3.core.store.MessageBoardStore;
import io.github.c1589190.bma3.core.store.SqliteMessageBoardStore;
import io.github.c1589190.bma3.core.store.ThreadSummary;
import io.github.c1589190.bma3.qqconnect.account.AccountConfig;
import io.github.c1589190.bma3.qqconnect.account.AccountRegistry;
import io.github.c1589190.bma3.qqconnect.onebot.OneBot11Client;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Safety behaviour of the shared QQ text outbound path.
 *
 * <p>Uses a real SQLite board and a fake external provider: the fake snapshots the board at every
 * provider call, so "PENDING before dispatch" is observed at the exact moment the request reaches
 * the provider, not after the call returns.</p>
 */
class QqSendSafetyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SourceAddress SOURCE = SourceAddress.parse("qq_private/bot-1/20002");

    @TempDir
    Path tempDir;

    @Test
    void persistsPendingBeforeTheProviderSeesTheRequestAndFinalizesSentWithId() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(501));
            QqToolContext context = context(board, board, provider, QqSendPolicy.defaults());

            String result = QqSendSupport.sendText(context, SOURCE, "hello", "", "send_private_msg");

            assertThat(provider.calls).isEqualTo(1);
            List<Message> seenByProvider = provider.rowsAtCall.getFirst();
            assertThat(seenByProvider).hasSize(1);
            Delivery pending = seenByProvider.getFirst().delivery();
            assertThat(pending).isNotNull();
            assertThat(pending.status()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(pending.operationId()).isNotBlank();
            assertThat(pending.partIndex()).isEqualTo(1);
            assertThat(pending.partTotal()).isEqualTo(1);

            JsonNode entry = JSON.readTree(result).get(0);
            assertThat(entry.path("state").asText()).isEqualTo("SENT");
            assertThat(entry.path("messageId").asText()).isEqualTo("501");
            assertThat(entry.path("recorded").asBoolean()).isTrue();

            Message stored = onlyOutbound(board);
            assertThat(stored.delivery().status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(stored.externalMessageId()).isEqualTo("501");
            assertThat(stored.delivery().operationId()).isEqualTo(pending.operationId());
            assertThat(stored.delivery().errorCode()).isNull();
        }
    }

    @Test
    void stopsAfterAnAmbiguousSecondChunkAndKeepsOneOperationAcrossParts() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(601));
            provider.script.add(nonzero(1200, "raw-provider-detail"));
            provider.script.add(success(603));
            QqToolContext context = context(board, board, provider, new QqSendPolicy(3, "split"));

            String result = QqSendSupport.sendText(context, SOURCE, "abcdefgh", "",
                    "send_private_msg");

            assertThat(provider.calls).isEqualTo(2);
            List<Message> seenAtSecondCall = provider.rowsAtCall.get(1);
            assertThat(seenAtSecondCall).hasSize(2);
            assertThat(seenAtSecondCall.get(0).delivery().status())
                    .isEqualTo(DeliveryStatus.SENT);
            Delivery secondPending = seenAtSecondCall.get(1).delivery();
            assertThat(secondPending.status()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(secondPending.partIndex()).isEqualTo(2);
            assertThat(secondPending.partTotal()).isEqualTo(3);

            List<Message> rows = board.read(SOURCE, 0, 10);
            assertThat(rows).hasSize(2);
            Delivery first = rows.get(0).delivery();
            Delivery second = rows.get(1).delivery();
            assertThat(first.operationId()).isNotBlank();
            assertThat(second.operationId()).isEqualTo(first.operationId());
            assertThat(first.partIndex()).isEqualTo(1);
            assertThat(first.partTotal()).isEqualTo(3);
            assertThat(second.partIndex()).isEqualTo(2);
            assertThat(second.partTotal()).isEqualTo(3);
            assertThat(rows.get(0).externalMessageId()).isEqualTo("601");
            assertThat(first.status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(second.status()).isEqualTo(DeliveryStatus.UNKNOWN);
            assertThat(second.errorCode()).isEqualTo("PROVIDER_NONZERO");
            assertThat(rows.get(1).externalMessageId()).isEmpty();

            JsonNode entries = JSON.readTree(result);
            assertThat(entries).hasSize(2);
            assertThat(entries.get(1).path("state").asText()).isEqualTo("UNKNOWN");
            assertThat(entries.get(1).path("errorCode").asText()).isEqualTo("PROVIDER_NONZERO");
            assertThat(entries.get(1).path("recorded").asBoolean()).isTrue();
            assertThat(result).doesNotContain("raw-provider-detail");
        }
    }

    @Test
    void nonzeroProviderResponseIsUnknownAndNeverLeaksTheProviderMessage() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(nonzero(1200, "raw-provider-detail"));
            QqToolContext context = context(board, board, provider, QqSendPolicy.defaults());

            String result = QqSendSupport.sendText(context, SOURCE, "hello", "", "send_private_msg");

            JsonNode entry = JSON.readTree(result).get(0);
            assertThat(entry.path("state").asText()).isEqualTo("UNKNOWN");
            assertThat(entry.path("errorCode").asText()).isEqualTo("PROVIDER_NONZERO");
            assertThat(entry.path("retcode").asInt()).isEqualTo(1200);
            assertThat(entry.path("recorded").asBoolean()).isTrue();
            Message stored = onlyOutbound(board);
            assertThat(stored.delivery().status()).isEqualTo(DeliveryStatus.UNKNOWN);
            assertThat(stored.delivery().errorCode()).isEqualTo("PROVIDER_NONZERO");
            assertThat(stored.externalMessageId()).isEmpty();
            assertThat(result).doesNotContain("raw-provider-detail");
        }
    }

    @Test
    void providerExceptionAfterDispatchIsUnknownAndNeverLeaksTheExceptionText() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(new IllegalStateException("raw-socket-detail"));
            QqToolContext context = context(board, board, provider, QqSendPolicy.defaults());

            String result = QqSendSupport.sendText(context, SOURCE, "hello", "", "send_private_msg");

            JsonNode entry = JSON.readTree(result).get(0);
            assertThat(entry.path("state").asText()).isEqualTo("UNKNOWN");
            assertThat(entry.path("errorCode").asText()).isEqualTo("PROVIDER_EXCEPTION");
            assertThat(entry.path("recorded").asBoolean()).isTrue();
            Message stored = onlyOutbound(board);
            assertThat(stored.delivery().status()).isEqualTo(DeliveryStatus.UNKNOWN);
            assertThat(stored.delivery().errorCode()).isEqualTo("PROVIDER_EXCEPTION");
            assertThat(result).doesNotContain("raw-socket-detail");
        }
    }

    @Test
    void successWithoutMessageIdIsSent() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(successWithoutId());
            QqToolContext context = context(board, board, provider, QqSendPolicy.defaults());

            String result = QqSendSupport.sendText(context, SOURCE, "hello", "", "send_private_msg");

            JsonNode entry = JSON.readTree(result).get(0);
            assertThat(entry.path("state").asText()).isEqualTo("SENT");
            assertThat(entry.path("messageId").asText()).isEmpty();
            assertThat(entry.path("recorded").asBoolean()).isTrue();
            Message stored = onlyOutbound(board);
            assertThat(stored.delivery().status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(stored.externalMessageId()).isEmpty();
        }
    }

    @Test
    void timeoutIsUnknownAndALateSuccessCannotRewriteTheBoardOrDispatchAgain() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(null); // completes only when the test says so
            QqToolContext context = context(board, board, provider, QqSendPolicy.defaults());

            String result = QqSendSupport.sendText(context, SOURCE, "hello", "", "send_private_msg");

            JsonNode entry = JSON.readTree(result).get(0);
            assertThat(entry.path("state").asText()).isEqualTo("UNKNOWN");
            assertThat(entry.path("errorCode").asText()).isEqualTo("TIMEOUT");
            assertThat(entry.path("recorded").asBoolean()).isTrue();
            Message afterTimeout = onlyOutbound(board);
            assertThat(afterTimeout.delivery().status()).isEqualTo(DeliveryStatus.UNKNOWN);
            assertThat(afterTimeout.delivery().errorCode()).isEqualTo("TIMEOUT");
            assertThat(afterTimeout.externalMessageId()).isEmpty();

            provider.completePending(0, success(999));

            assertThat(provider.calls).isEqualTo(1);
            Message afterLateSuccess = onlyOutbound(board);
            assertThat(afterLateSuccess.delivery().status()).isEqualTo(DeliveryStatus.UNKNOWN);
            assertThat(afterLateSuccess.delivery().errorCode()).isEqualTo("TIMEOUT");
            assertThat(afterLateSuccess.externalMessageId()).isEmpty();
        }
    }

    @Test
    void terminalWriteFailureAfterProviderSuccessIsNotRecordedAndStopsFurtherParts() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FailingBoard failing = new FailingBoard(board);
            failing.failFinish = true;
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(701));
            provider.script.add(success(702));
            provider.script.add(success(703));
            QqToolContext context = context(failing, board, provider, new QqSendPolicy(3, "split"));

            String result = QqSendSupport.sendText(context, SOURCE, "abcdefgh", "",
                    "send_private_msg");

            assertThat(provider.calls).isEqualTo(1);
            assertThat(failing.finishCalls).isEqualTo(1);
            JsonNode entries = JSON.readTree(result);
            assertThat(entries).hasSize(1);
            JsonNode entry = entries.get(0);
            assertThat(entry.path("state").asText())
                    .as("provider 成功但终止写失败，对外必须是保守 UNKNOWN")
                    .isEqualTo("UNKNOWN");
            assertThat(entry.path("providerState").asText()).isEqualTo("SENT");
            assertThat(entry.path("errorCode").asText()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(entry.path("recorded").asBoolean()).isFalse();
            assertThat(entry.path("dispatched").asBoolean()).isTrue();
            assertThat(entry.path("messageId").asText()).isEmpty();
            assertThat(result).doesNotContain("simulated terminal write failure");

            Message stored = onlyOutbound(board);
            assertThat(stored.delivery().status()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(stored.externalMessageId()).isEmpty();
        }
    }

    @Test
    void realSqliteTerminalWriteFailureLeavesTheRowPendingAndStopsFurtherParts() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            try (var connection = java.sql.DriverManager.getConnection(
                    "jdbc:sqlite:" + tempDir.resolve("bot.db").toAbsolutePath());
                 var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER fail_terminal_update
                        BEFORE UPDATE OF delivery_status ON chat_message
                        WHEN NEW.delivery_status = 'SENT'
                        BEGIN SELECT RAISE(ABORT, 'simulated terminal write failure'); END
                        """);
            }
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(1101));
            provider.script.add(success(1102));
            provider.script.add(success(1103));
            QqToolContext context = context(board, board, provider, new QqSendPolicy(3, "split"));

            String result = QqSendSupport.sendText(context, SOURCE, "abcdefgh", "",
                    "send_private_msg");

            assertThat(provider.calls).isEqualTo(1);
            JsonNode entries = JSON.readTree(result);
            assertThat(entries).hasSize(1);
            JsonNode entry = entries.get(0);
            assertThat(entry.path("state").asText()).isEqualTo("UNKNOWN");
            assertThat(entry.path("providerState").asText()).isEqualTo("SENT");
            assertThat(entry.path("errorCode").asText()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(entry.path("recorded").asBoolean()).isFalse();
            assertThat(entry.path("dispatched").asBoolean()).isTrue();
            Message stored = onlyOutbound(board);
            assertThat(stored.delivery().status()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(stored.externalMessageId()).isEmpty();
        }
    }

    @Test
    void initialBoardWriteFailureReturnsADiagnosticWithoutProviderCallOrBoardRow()
            throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FailingBoard failing = new FailingBoard(board);
            failing.failBegin = true;
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(801));
            QqToolContext context = context(failing, board, provider, QqSendPolicy.defaults());

            String result = QqSendSupport.sendText(context, SOURCE, "hello", "",
                    "send_private_msg");

            JsonNode entries = JSON.readTree(result);
            assertThat(entries).hasSize(1);
            JsonNode entry = entries.get(0);
            assertThat(entry.path("state").asText()).isEqualTo("FAILED_BEFORE_SEND");
            assertThat(entry.path("errorCode").asText()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(entry.path("recorded").asBoolean()).isFalse();
            assertThat(entry.path("dispatched").asBoolean()).isFalse();
            assertThat(entry.path("localSeq").isNull()).isTrue();
            assertThat(entry.path("partIndex").asInt()).isEqualTo(1);
            assertThat(entry.path("partTotal").asInt()).isEqualTo(1);
            assertThat(result).doesNotContain("simulated begin write failure");
            assertThat(provider.calls).isZero();
            assertThat(board.read(SOURCE, 0, 10)).isEmpty();
        }
    }

    @Test
    void multipartBeginFailureKeepsEarlierPartsAndDoesNotDispatchTheFailedPart() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FailingBoard failing = new FailingBoard(board);
            failing.failBeginAt = 2;
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(811));
            provider.script.add(success(812)); // must never be used
            provider.script.add(success(813));
            QqToolContext context = context(failing, board, provider, new QqSendPolicy(3, "split"));

            String result = QqSendSupport.sendText(context, SOURCE, "abcdefgh", "",
                    "send_private_msg");

            assertThat(provider.calls).isEqualTo(1);
            JsonNode entries = JSON.readTree(result);
            assertThat(entries).hasSize(2);
            JsonNode first = entries.get(0);
            assertThat(first.path("state").asText()).isEqualTo("SENT");
            assertThat(first.path("recorded").asBoolean()).isTrue();
            assertThat(first.path("partIndex").asInt()).isEqualTo(1);
            assertThat(first.path("partTotal").asInt()).isEqualTo(3);
            JsonNode failed = entries.get(1);
            assertThat(failed.path("state").asText()).isEqualTo("FAILED_BEFORE_SEND");
            assertThat(failed.path("errorCode").asText()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(failed.path("recorded").asBoolean()).isFalse();
            assertThat(failed.path("dispatched").asBoolean()).isFalse();
            assertThat(failed.path("localSeq").isNull()).isTrue();
            assertThat(failed.path("partIndex").asInt()).isEqualTo(2);
            assertThat(failed.path("partTotal").asInt()).isEqualTo(3);
            assertThat(result).doesNotContain("simulated begin write failure");

            List<Message> rows = board.read(SOURCE, 0, 10);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().delivery().status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(rows.getFirst().delivery().partIndex()).isEqualTo(1);
        }
    }

    @Test
    void finishOutboundReturningFalseIsReportedAsUnknownBoardWriteFailure() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FailingBoard failing = new FailingBoard(board);
            failing.finishReturnsFalse = true;
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(821));
            QqToolContext context = context(failing, board, provider, QqSendPolicy.defaults());

            String result = QqSendSupport.sendText(context, SOURCE, "hello", "",
                    "send_private_msg");

            assertThat(provider.calls).isEqualTo(1);
            assertThat(failing.finishCalls).isEqualTo(1);
            JsonNode entry = JSON.readTree(result).get(0);
            assertThat(entry.path("state").asText()).isEqualTo("UNKNOWN");
            assertThat(entry.path("providerState").asText()).isEqualTo("SENT");
            assertThat(entry.path("errorCode").asText()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(entry.path("recorded").asBoolean()).isFalse();
            assertThat(entry.path("dispatched").asBoolean()).isTrue();
            assertThat(entry.path("messageId").asText()).isEmpty();
            Message stored = onlyOutbound(board);
            assertThat(stored.delivery().status()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(stored.externalMessageId()).isEmpty();
        }
    }

    @Test
    void identicalTextProducesDistinctOperationsAndBoardRows() {
        try (SqliteMessageBoardStore board = board()) {
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(901));
            provider.script.add(success(902));
            QqToolContext context = context(board, board, provider, QqSendPolicy.defaults());

            QqSendSupport.sendText(context, SOURCE, "same text", "", "send_private_msg");
            QqSendSupport.sendText(context, SOURCE, "same text", "", "send_private_msg");

            assertThat(provider.calls).isEqualTo(2);
            List<Message> rows = board.read(SOURCE, 0, 10);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).delivery()).isNotNull();
            assertThat(rows.get(1).delivery()).isNotNull();
            assertThat(rows.get(0).delivery().operationId())
                    .isNotBlank()
                    .isNotEqualTo(rows.get(1).delivery().operationId());
            assertThat(rows.get(0).inbound().messageId())
                    .isNotEqualTo(rows.get(1).inbound().messageId());
            assertThat(rows.get(0).delivery().status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(rows.get(1).delivery().status()).isEqualTo(DeliveryStatus.SENT);
        }
    }

    @Test
    void deployerAlertUsesTheDurableOutboundPath() {
        try (SqliteMessageBoardStore board = board()) {
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(1001));
            QqToolContext context = context(board, board, provider, QqSendPolicy.defaults());
            QqDeployerNotifier notifier = new QqDeployerNotifier(context, "20002", true);

            assertThat(notifier.send("watchdog alert")).isTrue();
            Message stored = onlyOutbound(board);
            assertThat(stored.delivery().status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(stored.externalMessageId()).isEqualTo("1001");
            assertThat(provider.sentAction).isEqualTo("send_private_msg");
            assertThat(provider.sentParams.path("user_id").asLong()).isEqualTo(20002L);
            assertThat(provider.sentParams.has("group_id")).isFalse();
        }
    }

    @Test
    void deployerAlertReportsANonDurableTerminalWriteAsFailure() {
        try (SqliteMessageBoardStore board = board()) {
            FailingBoard failing = new FailingBoard(board);
            failing.failFinish = true;
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(1002));
            QqToolContext context = context(failing, board, provider, QqSendPolicy.defaults());
            QqDeployerNotifier notifier = new QqDeployerNotifier(context, "20002", true);

            assertThat(notifier.send("watchdog alert 2")).isFalse();
            assertThat(provider.calls).isEqualTo(1);
            Message stored = onlyOutbound(board);
            assertThat(stored.delivery().status()).isEqualTo(DeliveryStatus.PENDING);
        }
    }

    @Test
    void routesBySourceAccountAndUnknownAccountDispatchesNothing() {
        try (SqliteMessageBoardStore board = board()) {
            FakeProvider first = new FakeProvider(board, "bot-1");
            FakeProvider second = new FakeProvider(board, "bot-2");
            AccountRegistry registry = new AccountRegistry((config, listener) ->
                    "bot-1".equals(config.accountId()) ? first : second, event -> { });
            registry.reconcile(List.of(
                    new AccountConfig("bot-1", URI.create("ws://127.0.0.1:1"), "token", true),
                    new AccountConfig("bot-2", URI.create("ws://127.0.0.1:2"), "token", true)));
            QqToolContext context = new QqToolContext(registry, board, board, tempDir, null,
                    QqSendPolicy::defaults, null);
            first.script.add(success(1601));
            second.script.add(success(1602));
            SourceAddress bot1 = SourceAddress.parse("qq_private/bot-1/20002");
            SourceAddress bot2 = SourceAddress.parse("qq_private/bot-2/20002");
            SourceAddress unknown = SourceAddress.parse("qq_private/bot-9/20002");

            QqSendSupport.sendText(context, bot1, "one", "", "send_private_msg");

            assertThat(first.calls).isEqualTo(1);
            assertThat(second.calls).isZero();
            assertThat(first.sentAction).isEqualTo("send_private_msg");
            assertThat(first.sentParams.path("user_id").asLong()).isEqualTo(20002L);
            assertThat(first.sentParams.has("group_id")).isFalse();
            List<Message> bot1Rows = board.read(bot1, 0, 10);
            assertThat(bot1Rows).hasSize(1);
            assertThat(bot1Rows.getFirst().externalMessageId()).isEqualTo("1601");

            QqSendSupport.sendText(context, bot2, "two", "", "send_private_msg");

            assertThat(first.calls).isEqualTo(1);
            assertThat(second.calls).isEqualTo(1);
            assertThat(second.sentParams.path("user_id").asLong()).isEqualTo(20002L);
            List<Message> bot2Rows = board.read(bot2, 0, 10);
            assertThat(bot2Rows).hasSize(1);
            assertThat(bot2Rows.getFirst().externalMessageId()).isEqualTo("1602");

            assertThatThrownBy(() -> QqSendSupport.sendText(context, unknown, "three", "",
                    "send_private_msg"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ACCOUNT_NOT_FOUND");

            assertThat(first.calls).isEqualTo(1);
            assertThat(second.calls).isEqualTo(1);
            assertThat(board.read(unknown, 0, 10)).isEmpty();
            assertThat(board.read(bot1, 0, 10)).hasSize(1);
            assertThat(board.read(bot2, 0, 10)).hasSize(1);
        }
    }

    @Test
    void conversationSendReturnsNonSuccessWithAllPartsWhenAPartBoardWriteFails()
            throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FailingBoard failing = new FailingBoard(board);
            failing.failBeginAt = 2;
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(1701));
            provider.script.add(success(1702)); // must never be used
            QqToolContext context = context(failing, board, provider, new QqSendPolicy(3, "split"));

            ToolResult result = new QqConversationSendTool(context).execute(toolCall(Map.of(
                    "source", SOURCE.format(), "text", "abcdefgh")));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("BOARD_WRITE_FAILED");
            JsonNode parts = JSON.readTree(result.message());
            assertThat(parts).hasSize(2);
            assertThat(parts.get(0).path("state").asText()).isEqualTo("SENT");
            assertThat(parts.get(0).path("localSeq").isNumber()).isTrue();
            assertThat(parts.get(0).has("resendAllowed")).isTrue();
            assertThat(parts.get(0).path("resendAllowed").asBoolean()).isFalse();
            assertThat(parts.get(1).path("state").asText()).isEqualTo("FAILED_BEFORE_SEND");
            assertThat(parts.get(1).path("errorCode").asText())
                    .isEqualTo("BOARD_WRITE_FAILED");
            assertThat(parts.get(1).path("localSeq").isNull()).isTrue();
            assertThat(parts.get(1).path("dispatched").asBoolean()).isFalse();
            assertThat(parts.get(1).has("resendAllowed")).isTrue();
            assertThat(parts.get(1).path("resendAllowed").asBoolean()).isFalse();
            assertThat(provider.calls).isEqualTo(1);
        }
    }

    @Test
    void conversationSendReturnsNonSuccessOnInitialBoardWriteFailure() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FailingBoard failing = new FailingBoard(board);
            failing.failBegin = true;
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(1711));
            QqToolContext context = context(failing, board, provider, QqSendPolicy.defaults());

            ToolResult result = new QqConversationSendTool(context).execute(toolCall(Map.of(
                    "source", SOURCE.format(), "text", "hello")));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("BOARD_WRITE_FAILED");
            JsonNode parts = JSON.readTree(result.message());
            assertThat(parts).hasSize(1);
            assertThat(parts.get(0).path("state").asText()).isEqualTo("FAILED_BEFORE_SEND");
            assertThat(parts.get(0).path("errorCode").asText())
                    .isEqualTo("BOARD_WRITE_FAILED");
            assertThat(parts.get(0).path("localSeq").isNull()).isTrue();
            assertThat(parts.get(0).path("resendAllowed").asBoolean()).isFalse();
            assertThat(provider.calls).isZero();
        }
    }

    @Test
    void privateSendWithTemporaryGroupReturnsNonSuccessOnBoardWriteFailure() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FailingBoard failing = new FailingBoard(board);
            failing.failBegin = true;
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(1721));
            QqToolContext context = context(failing, board, provider, QqSendPolicy.defaults());

            ToolResult result = new QqPrivateSendTool(context).execute(toolCall(Map.of(
                    "userId", 20002, "text", "hello", "groupId", 955432692)));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("BOARD_WRITE_FAILED");
            JsonNode parts = JSON.readTree(result.message());
            assertThat(parts.get(0).path("errorCode").asText())
                    .isEqualTo("BOARD_WRITE_FAILED");
            assertThat(parts.get(0).path("resendAllowed").asBoolean()).isFalse();
            assertThat(provider.calls).isZero();
        }
    }

    @Test
    void privateSendWithoutTemporaryGroupReturnsNonSuccessOnBoardWriteFailure() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FailingBoard failing = new FailingBoard(board);
            failing.failBegin = true;
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(1725));
            QqToolContext context = context(failing, board, provider, QqSendPolicy.defaults());

            ToolResult result = new QqPrivateSendTool(context).execute(toolCall(Map.of(
                    "userId", 20002, "text", "hello", "groupId", 0)));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("BOARD_WRITE_FAILED");
            JsonNode parts = JSON.readTree(result.message());
            assertThat(parts.get(0).path("errorCode").asText())
                    .isEqualTo("BOARD_WRITE_FAILED");
            assertThat(parts.get(0).path("resendAllowed").asBoolean()).isFalse();
            assertThat(provider.calls).isZero();
        }
    }

    @Test
    void replyReturnsNonSuccessOnBoardWriteFailure() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FailingBoard failing = new FailingBoard(board);
            failing.failBegin = true;
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(1731));
            QqToolContext context = context(failing, board, provider, QqSendPolicy.defaults());

            ToolResult result = new QqReplyTool(context).execute(toolCall(Map.of(
                    "source", SOURCE.format(), "text", "hello", "replyToMessageId", "m1")));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("BOARD_WRITE_FAILED");
            JsonNode parts = JSON.readTree(result.message());
            assertThat(parts.get(0).path("errorCode").asText())
                    .isEqualTo("BOARD_WRITE_FAILED");
            assertThat(parts.get(0).path("resendAllowed").asBoolean()).isFalse();
            assertThat(provider.calls).isZero();
        }
    }

    @Test
    void terminalWriteFailureIsAnOuterToolErrorWithUnknownState() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FailingBoard failing = new FailingBoard(board);
            failing.failFinish = true;
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(1741));
            QqToolContext context = context(failing, board, provider, QqSendPolicy.defaults());

            ToolResult result = new QqConversationSendTool(context).execute(toolCall(Map.of(
                    "source", SOURCE.format(), "text", "hello")));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("BOARD_WRITE_FAILED");
            JsonNode entry = JSON.readTree(result.message()).get(0);
            assertThat(entry.path("state").asText()).isEqualTo("UNKNOWN");
            assertThat(entry.path("providerState").asText()).isEqualTo("SENT");
            assertThat(entry.path("errorCode").asText()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(entry.path("recorded").asBoolean()).isFalse();
            assertThat(entry.path("resendAllowed").asBoolean()).isFalse();
            assertThat(provider.calls).isEqualTo(1);
        }
    }

    @Test
    void providerUnknownStaysAnOuterSuccessAndIsNotMadeRetryable() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(nonzero(1200, "raw-provider-detail"));
            QqToolContext context = context(board, board, provider, QqSendPolicy.defaults());

            ToolResult result = new QqConversationSendTool(context).execute(toolCall(Map.of(
                    "source", SOURCE.format(), "text", "hello")));

            assertThat(result.success()).isTrue();
            JsonNode entry = JSON.readTree(result.message()).get(0);
            assertThat(entry.path("state").asText()).isEqualTo("UNKNOWN");
            assertThat(entry.path("errorCode").asText()).isEqualTo("PROVIDER_NONZERO");
            assertThat(entry.path("recorded").asBoolean()).isTrue();
            assertThat(entry.has("resendAllowed")).isFalse();
        }
    }

    @Test
    @Timeout(30)
    void mcpErrorCarriesTheCompleteStructuredPartsArray() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FailingBoard failing = new FailingBoard(board);
            failing.failBeginAt = 2;
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(1801));
            provider.script.add(success(1802)); // must never be used
            QqToolContext context = context(failing, board, provider, new QqSendPolicy(3, "split"));
            String token = "bot-test-token-9e2c";
            Bma3McpServer server = new Bma3McpServer("127.0.0.1", 0, "/bot/mcp", token,
                    "bma3-test", "0.0.6",
                    List.of(new QqConversationSendTool(context)));
            McpSyncClient client = null;
            try {
                server.start();
                var transport = HttpClientStreamableHttpTransport
                        .builder("http://127.0.0.1:" + server.boundPort())
                        .endpoint("/bot/mcp")
                        .resumableStreams(false)
                        .connectTimeout(java.time.Duration.ofSeconds(5))
                        .httpRequestCustomizer((request, method, uri, body, requestContext) ->
                                request.header("Authorization", "Bearer " + token))
                        .build();
                client = McpClient.sync(transport).build();
                client.initialize();

                McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                        "qq_conversation_send",
                        Map.of("source", SOURCE.format(), "text", "abcdefgh")));

                assertThat(result.isError()).isTrue();
                String wire = result.content().stream()
                        .filter(McpSchema.TextContent.class::isInstance)
                        .map(McpSchema.TextContent.class::cast)
                        .map(McpSchema.TextContent::text)
                        .findFirst()
                        .orElse("");
                assertThat(wire).contains("[mosire:code=BOARD_WRITE_FAILED]");
                int prefixEnd = wire.indexOf(']');
                JsonNode parts = JSON.readTree(wire.substring(prefixEnd + 1).stripLeading());
                assertThat(parts).hasSize(2);
                assertThat(parts.get(0).path("state").asText()).isEqualTo("SENT");
                assertThat(parts.get(0).path("localSeq").isNumber()).isTrue();
                assertThat(parts.get(0).has("resendAllowed")).isTrue();
                assertThat(parts.get(0).path("resendAllowed").asBoolean()).isFalse();
                assertThat(parts.get(1).path("state").asText())
                        .isEqualTo("FAILED_BEFORE_SEND");
                assertThat(parts.get(1).path("errorCode").asText())
                        .isEqualTo("BOARD_WRITE_FAILED");
                assertThat(parts.get(1).path("localSeq").isNull()).isTrue();
                assertThat(parts.get(1).path("resendAllowed").asBoolean()).isFalse();
            } finally {
                if (client != null) {
                    client.closeGracefully();
                }
                server.close();
            }
        }
    }

    /** Task 3 seam: the bounded waits are part of the shared contract. */
    @Test
    void waitsAreBoundedAsPlanned() {
        assertThat(QqDelivery.TEXT_WAIT).isEqualTo(java.time.Duration.ofSeconds(5));
        assertThat(QqDelivery.ATTACHMENT_WAIT).isEqualTo(java.time.Duration.ofSeconds(60));
    }

    /**
     * Task 3 seam: only an explicit, caller-proven no-dispatch result becomes FAILED_BEFORE_SEND,
     * and it is persisted with its bounded category and no external id.
     */
    @Test
    void explicitNotDispatchedClassificationIsBoundedAndPersisted() {
        try (SqliteMessageBoardStore board = board()) {
            QqToolContext context = context(board, board, new FakeProvider(board),
                    QqSendPolicy.defaults());
            InboundMessage outbound = new InboundMessage(SOURCE, "out-probe", Instant.now(),
                    List.of(new Segment("text", "x")), Direction.OUTBOUND);
            QqDelivery.Pending pending = QqDelivery.begin(context, outbound, "op-probe", 1, 1);

            QqDelivery.Outcome outcome = QqDelivery.finish(context, pending,
                    QqDelivery.failedBeforeSend("FILE_NOT_FOUND"));

            assertThat(outcome.status()).isEqualTo(DeliveryStatus.FAILED_BEFORE_SEND);
            assertThat(outcome.recorded()).isTrue();
            Message stored = onlyOutbound(board);
            assertThat(stored.delivery().status()).isEqualTo(DeliveryStatus.FAILED_BEFORE_SEND);
            assertThat(stored.delivery().errorCode()).isEqualTo("FILE_NOT_FOUND");
            assertThat(stored.externalMessageId()).isEmpty();
        }
    }

    private static ToolContext toolCall(Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), arguments, AgentIdentity.external());
    }

    private SqliteMessageBoardStore board() {
        return new SqliteMessageBoardStore(tempDir.resolve("bot.db"));
    }

    private QqToolContext context(MessageBoardStore board,
                                  io.github.c1589190.bma3.core.store.ConsumerConversationStore consumers,
                                  FakeProvider provider, QqSendPolicy policy) {
        AccountRegistry registry = new AccountRegistry((config, listener) -> provider, event -> { });
        registry.reconcile(List.of(new AccountConfig(
                "bot-1", URI.create("ws://127.0.0.1:1"), "token", true)));
        return new QqToolContext(registry, board, consumers, tempDir, null,
                () -> policy, null);
    }

    private static Message onlyOutbound(SqliteMessageBoardStore board) {
        List<Message> rows = board.read(SOURCE, 0, 10);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private static JsonNode success(long messageId) {
        return parse("""
                {"status":"ok","retcode":0,"data":{"message_id":%d}}
                """.formatted(messageId));
    }

    private static JsonNode successWithoutId() {
        return parse("""
                {"status":"ok","retcode":0,"data":{}}
                """);
    }

    private static JsonNode nonzero(int retcode, String providerMessage) {
        return parse("""
                {"status":"failed","retcode":%d,"message":%s}
                """.formatted(retcode, JSON.valueToTree(providerMessage)));
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static final class FakeProvider extends OneBot11Client {

        private static final JsonNode LOGIN = parse("""
                {"status":"ok","retcode":0,"data":{"user_id":"10001","nickname":"bot"}}
                """);

        private final SqliteMessageBoardStore board;
        private final SourceAddress source;
        private final List<Object> script = new ArrayList<>();
        private final List<List<Message>> rowsAtCall = new ArrayList<>();
        private final List<CompletableFuture<JsonNode>> pending = new ArrayList<>();
        private int calls;
        private String sentAction;
        private JsonNode sentParams;

        FakeProvider(SqliteMessageBoardStore board) {
            this(board, "bot-1");
        }

        FakeProvider(SqliteMessageBoardStore board, String accountId) {
            super(URI.create("ws://127.0.0.1:1"), "", event -> { });
            this.board = board;
            this.source = SourceAddress.parse("qq_private/" + accountId + "/20002");
        }

        @Override
        public void start() {
        }

        @Override
        public void awaitReady(java.time.Duration timeout) {
        }

        @Override
        public boolean connected() {
            return true;
        }

        @Override
        public CompletableFuture<JsonNode> sendAction(String action, Map<String, Object> params) {
            if ("get_login_info".equals(action)) {
                return CompletableFuture.completedFuture(LOGIN);
            }
            calls++;
            sentAction = action;
            sentParams = JSON.valueToTree(params);
            rowsAtCall.add(List.copyOf(board.read(source, 0, 100)));
            Object next = script.isEmpty()
                    ? success(calls)
                    : script.get(Math.min(calls - 1, script.size() - 1));
            if (next == null) {
                CompletableFuture<JsonNode> future = new CompletableFuture<>();
                pending.add(future);
                return future;
            }
            if (next instanceof RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture((JsonNode) next);
        }

        void completePending(int index, JsonNode response) {
            pending.get(index).complete(response);
        }

        @Override
        public void close() {
        }
    }

    /** A real board with an injectable begin/finish failure; every other call delegates. */
    private static final class FailingBoard implements MessageBoardStore {

        private final SqliteMessageBoardStore delegate;
        private boolean failBegin;
        private int failBeginAt;
        private int beginCalls;
        private boolean failFinish;
        private boolean finishReturnsFalse;
        private int finishCalls;

        FailingBoard(SqliteMessageBoardStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public AppendResult append(io.github.c1589190.bma3.core.message.InboundMessage inbound) {
            return delegate.append(inbound);
        }

        @Override
        public AppendResult beginOutbound(io.github.c1589190.bma3.core.message.InboundMessage message,
                                          String operationId, int partIndex, int partTotal) {
            beginCalls++;
            if (failBegin || (failBeginAt > 0 && beginCalls == failBeginAt)) {
                throw new IllegalStateException("simulated begin write failure");
            }
            return delegate.beginOutbound(message, operationId, partIndex, partTotal);
        }

        @Override
        public boolean finishOutbound(long localSeq, DeliveryStatus status,
                                      String externalMessageId, String errorCode) {
            finishCalls++;
            if (failFinish) {
                throw new IllegalStateException("simulated terminal write failure");
            }
            if (finishReturnsFalse) {
                return false;
            }
            return delegate.finishOutbound(localSeq, status, externalMessageId, errorCode);
        }

        @Override
        public int recoverPendingOutbound() {
            return delegate.recoverPendingOutbound();
        }

        @Override
        public List<Message> read(SourceAddress source, long afterSeq, int limit) {
            return delegate.read(source, afterSeq, limit);
        }

        @Override
        public List<ThreadSummary> threads(int limit) {
            return delegate.threads(limit);
        }

        @Override
        public void attachExternalMessageId(long localSeq, String externalMessageId) {
            delegate.attachExternalMessageId(localSeq, externalMessageId);
        }

        @Override
        public Optional<Message> findByExternalMessageId(SourceAddress source,
                                                         String externalMessageId) {
            return delegate.findByExternalMessageId(source, externalMessageId);
        }

        @Override
        public List<Message> search(String query, String sourcePrefix, Instant from,
                                    Instant to, long beforeSeq, int limit) {
            return delegate.search(query, sourcePrefix, from, to, beforeSeq, limit);
        }

        @Override
        public boolean ping() {
            return delegate.ping();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
