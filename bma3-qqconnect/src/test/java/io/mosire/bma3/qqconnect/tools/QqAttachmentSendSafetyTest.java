package io.mosire.bma3.qqconnect.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.message.Delivery;
import io.mosire.bma3.core.message.DeliveryStatus;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.AppendResult;
import io.mosire.bma3.core.store.MessageBoardStore;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import io.mosire.bma3.core.store.ThreadSummary;
import io.mosire.bma3.qqconnect.account.AccountConfig;
import io.mosire.bma3.qqconnect.account.AccountRegistry;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Safety behaviour of the QQ file and image outbound paths.
 *
 * <p>Uses a real temporary SQLite message board and a fake external provider. The fake snapshots
 * the board at every provider call, so "PENDING is durable before dispatch" is observed at the
 * exact moment the request reaches the provider.</p>
 *
 * <p>The fallback seam is deliberately narrow: a file upload path may only advance when the
 * previous path was proven not to have been dispatched (for example, the local client is already
 * disconnected before the request is handed over). Any timeout, connection loss after dispatch,
 * exception or non-zero provider reply is {@code UNKNOWN} and stops every other path and the
 * associated text.</p>
 */
class QqAttachmentSendSafetyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SourceAddress SOURCE = SourceAddress.parse("qq_private/bot-1/20002");

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------ files

    @Test
    void ambiguousUploadResponseIsUnknownAndStopsFallbackPathsAndAssociatedText() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path file = writeFile();
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(nonzero(1200, "raw-provider-detail"));
            provider.script.add(success(9001)); // must never be used
            provider.script.add(success(9002)); // must never be used
            QqToolContext context = context(board, board, provider);

            ToolResult result = new QqConversationSendFileTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "filePath", file.toString(),
                    "providerPath", "/remote/report.txt",
                    "text", "caption")));

            assertThat(provider.actions)
                    .as("UNKNOWN 后不得尝试绝对路径/ providerPath/关联文本")
                    .containsExactly("upload_private_file");
            assertThat(provider.rowsAtCall).hasSize(1);
            assertThat(provider.rowsAtCall.getFirst()).hasSize(1);
            Delivery pendingAtProvider = provider.rowsAtCall.getFirst().getFirst().delivery();
            assertThat(pendingAtProvider).isNotNull();
            assertThat(pendingAtProvider.status()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(pendingAtProvider.partIndex()).isEqualTo(1);
            assertThat(pendingAtProvider.partTotal())
                    .as("文件传输候选不是同一文件的文本分片，每个候选独立 1/1")
                    .isEqualTo(1);

            JsonNode json = JSON.readTree(result.message());
            assertThat(result.success()).isTrue();
            assertThat(json.path("state").asText()).isEqualTo("UNKNOWN");
            assertThat(json.path("errorCode").asText()).isEqualTo("PROVIDER_NONZERO");
            assertThat(json.path("recorded").asBoolean()).isTrue();
            assertThat(json.path("externalMessageId").asText()).isEmpty();
            assertThat(json.path("attempted").asText()).isEqualTo("base64");
            assertThat(json.path("attempts")).hasSize(1);
            assertThat(json.path("attempts").get(0).path("operationId").asText())
                    .isEqualTo(pendingAtProvider.operationId());
            assertThat(json.path("attempts").get(0).path("partIndex").asInt()).isEqualTo(1);
            assertThat(json.path("attempts").get(0).path("partTotal").asInt()).isEqualTo(1);
            assertThat(json.path("resendAllowed").asBoolean())
                    .as("附件 UNKNOWN 禁止自动重发")
                    .isFalse();
            assertThat(json.path("hint").asText()).contains("禁止自动重发");
            assertThat(json.path("textState").asText())
                    .as("附件状态不确定时，关联文本不得悄悄发出")
                    .isEqualTo("NOT_SENT");
            assertThat(json.path("textParts")).isEmpty();
            assertThat(result.message()).doesNotContain("raw-provider-detail");

            List<Message> rows = board.read(SOURCE, 0, 10);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().delivery().status()).isEqualTo(DeliveryStatus.UNKNOWN);
            assertThat(rows.getFirst().externalMessageId()).isEmpty();
        }
    }

    @Test
    void lostResponseAfterUploadIsUnknownAndStopsFallbackPaths() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path file = writeFile();
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(new IllegalStateException("raw-lost-response-detail"));
            provider.script.add(success(9101)); // must never be used
            provider.script.add(success(9102)); // must never be used
            QqToolContext context = context(board, board, provider);

            ToolResult result = new QqConversationSendFileTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "filePath", file.toString(),
                    "providerPath", "/remote/report.txt")));

            assertThat(provider.actions).containsExactly("upload_private_file");
            assertThat(result.success())
                    .as("附件 UNKNOWN 沿用 Task 2 外层成功契约，不诱发自动重发")
                    .isTrue();
            JsonNode json = JSON.readTree(result.message());
            assertThat(json.path("state").asText()).isEqualTo("UNKNOWN");
            assertThat(json.path("errorCode").asText()).isEqualTo("PROVIDER_EXCEPTION");
            assertThat(json.path("recorded").asBoolean()).isTrue();
            assertThat(json.path("attempted").asText()).isEqualTo("base64");
            assertThat(json.path("resendAllowed").asBoolean())
                    .as("附件 UNKNOWN 禁止自动重发")
                    .isFalse();
            assertThat(json.path("hint").asText()).contains("禁止自动重发");
            assertThat(result.message()).doesNotContain("raw-lost-response-detail");

            List<Message> rows = board.read(SOURCE, 0, 10);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().delivery().status()).isEqualTo(DeliveryStatus.UNKNOWN);
        }
    }

    @Test
    void providerPathOnlyStillWorksAndIsPendingBeforeDispatch() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(9201));
            QqToolContext context = context(board, board, provider);

            ToolResult result = new QqConversationSendFileTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "providerPath", "/remote/report.txt",
                    "fileName", "report.txt")));

            assertThat(provider.actions).containsExactly("upload_private_file");
            assertThat(provider.params.getFirst().path("file").asText())
                    .isEqualTo("/remote/report.txt");
            assertThat(provider.params.getFirst().path("name").asText()).isEqualTo("report.txt");
            List<Message> seen = provider.rowsAtCall.getFirst();
            assertThat(seen).hasSize(1);
            assertThat(seen.getFirst().delivery().status()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(seen.getFirst().delivery().partIndex()).isEqualTo(1);
            assertThat(seen.getFirst().delivery().partTotal()).isEqualTo(1);

            JsonNode json = JSON.readTree(result.message());
            assertThat(result.success()).isTrue();
            assertThat(json.path("state").asText()).isEqualTo("SENT");
            assertThat(json.path("externalMessageId").asText()).isEqualTo("9201");
            assertThat(json.path("recorded").asBoolean()).isTrue();
            assertThat(json.path("via").asText()).isEqualTo("providerPath");
            assertThat(json.path("attempted").asText()).isEqualTo("providerPath");
            assertThat(json.path("bytes").asInt()).isZero();

            Message stored = onlyOutbound(board);
            assertThat(stored.delivery().status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(stored.externalMessageId()).isEqualTo("9201");
        }
    }

    @Test
    void fileFallbackAdvancesOnlyAfterAProvenNoDispatchFailure() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path file = writeFile();
            FakeProvider provider = new FakeProvider(board);
            // First candidate (base64) is locally disconnected: no request was handed over.
            // Second candidate (absolute path) is connected and may dispatch.
            provider.connectedScript.add(false);
            provider.connectedScript.add(true);
            provider.script.add(success(9301));
            QqToolContext context = context(board, board, provider);

            ToolResult result = new QqConversationSendFileTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "filePath", file.toString())));

            assertThat(provider.actions)
                    .as("只有真正派发的那一次才算 provider action")
                    .containsExactly("upload_private_file");
            assertThat(provider.params.getFirst().path("file").asText())
                    .isEqualTo(file.toAbsolutePath().toString());

            JsonNode json = JSON.readTree(result.message());
            assertThat(json.path("state").asText()).isEqualTo("SENT");
            assertThat(json.path("recorded").asBoolean()).isTrue();
            assertThat(json.path("via").asText()).isEqualTo("absolutePath");
            assertThat(json.path("attempted").asText()).isEqualTo("base64,absolutePath");
            assertThat(json.path("attempts")).hasSize(2);
            assertThat(json.path("attempts").get(0).path("state").asText())
                    .isEqualTo("FAILED_BEFORE_SEND");
            assertThat(json.path("attempts").get(0).path("errorCode").asText())
                    .isEqualTo("NOT_DISPATCHED");
            assertThat(json.path("attempts").get(1).path("state").asText()).isEqualTo("SENT");
            assertThat(json.path("attempts").get(0).path("operationId").asText())
                    .isNotEqualTo(json.path("attempts").get(1).path("operationId").asText());
            assertThat(json.path("attempts").get(0).path("partIndex").asInt()).isEqualTo(1);
            assertThat(json.path("attempts").get(0).path("partTotal").asInt()).isEqualTo(1);
            assertThat(json.path("attempts").get(1).path("partIndex").asInt()).isEqualTo(1);
            assertThat(json.path("attempts").get(1).path("partTotal").asInt()).isEqualTo(1);

            List<Message> rows = board.read(SOURCE, 0, 10);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).delivery().status())
                    .isEqualTo(DeliveryStatus.FAILED_BEFORE_SEND);
            assertThat(rows.get(0).delivery().errorCode()).isEqualTo("NOT_DISPATCHED");
            assertThat(rows.get(0).delivery().partIndex()).isEqualTo(1);
            assertThat(rows.get(0).delivery().partTotal()).isEqualTo(1);
            assertThat(rows.get(1).delivery().status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(rows.get(1).delivery().operationId())
                    .as("不同文件传输候选是独立操作，不再伪装成一个文件的 1/2、2/2")
                    .isNotEqualTo(rows.get(0).delivery().operationId());
            assertThat(rows.get(1).delivery().partIndex()).isEqualTo(1);
            assertThat(rows.get(1).delivery().partTotal()).isEqualTo(1);
            assertThat(json.path("operationId").asText())
                    .isEqualTo(rows.get(1).delivery().operationId());
        }
    }

    @Test
    void associatedTextIsSentOnlyAfterTheFileIsDurablySent() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path file = writeFile();
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(9401)); // file upload
            provider.script.add(success(9402)); // text
            QqToolContext context = context(board, board, provider);

            ToolResult result = new QqConversationSendFileTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "filePath", file.toString(),
                    "text", "caption")));

            assertThat(provider.actions)
                    .containsExactly("upload_private_file", "send_private_msg");
            List<Message> seenBeforeText = provider.rowsAtCall.get(1);
            assertThat(seenBeforeText).hasSize(2);
            assertThat(seenBeforeText.get(0).delivery().status())
                    .as("关联文本派发前，文件行必须已经是耐久 SENT")
                    .isEqualTo(DeliveryStatus.SENT);
            assertThat(seenBeforeText.get(1).delivery().status())
                    .as("关联文本自己的 PENDING 行也必须先落板")
                    .isEqualTo(DeliveryStatus.PENDING);
            assertThat(provider.params.get(1).path("message").asText()).isEqualTo("caption");
            assertThat(provider.params.get(1).path("user_id").asLong()).isEqualTo(20002L);

            JsonNode json = JSON.readTree(result.message());
            assertThat(result.success()).isTrue();
            assertThat(json.path("state").asText()).isEqualTo("SENT");
            assertThat(json.path("textState").asText()).isEqualTo("SENT");
            assertThat(json.path("textParts")).hasSize(1);
            assertThat(json.path("textParts").get(0).path("state").asText()).isEqualTo("SENT");
            assertThat(json.path("textParts").get(0).path("localSeq").asLong()).isPositive();
            assertThat(json.has("resendAllowed"))
                    .as("正常 SENT 不应被误报为禁止重发")
                    .isFalse();
            assertThat(json.has("hint")).isFalse();

            List<Message> rows = board.read(SOURCE, 0, 10);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).delivery().status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(rows.get(1).delivery().status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(rows.get(1).inbound().segments()).hasSize(1);
            assertThat(rows.get(1).inbound().segments().getFirst().text()).isEqualTo("caption");
        }
    }

    @Test
    void associatedTextAccountFailureAfterFileSentKeepsFileSuccessAndForbidsReupload()
            throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path file = writeFile();
            FakeProvider provider = new FakeProvider(board);
            // The first candidate (base64) is proven not dispatched; the second succeeds and is
            // durable. That leaves two prior file attempt outcomes to preserve.
            provider.connectedScript.add(false);
            provider.connectedScript.add(true);
            provider.script.add(success(9421));
            AccountRegistry registry = new AccountRegistry(
                    (config, listener) -> provider, event -> { });
            registry.reconcile(List.of(new AccountConfig(
                    "bot-1", URI.create("ws://127.0.0.1:1"), "token", true)));
            QqToolContext context = new QqToolContext(registry, board, board, tempDir, null,
                    QqSendPolicy::defaults, null);
            // Controllable account loss exactly after the dispatched file upload response: the
            // text phase cannot resolve the account any more. No text provider request is made.
            provider.afterAction = action -> {
                if ("upload_private_file".equals(action)) {
                    registry.reconcile(List.of());
                }
            };

            ToolResult result = new QqConversationSendFileTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "filePath", file.toString(),
                    "text", "caption")));

            assertThat(provider.actions).containsExactly("upload_private_file");
            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("TEXT_SEND_FAILED");
            assertThat(result.message()).doesNotContain("ACCOUNT_NOT_FOUND");

            JsonNode json = JSON.readTree(result.message());
            assertThat(json.path("state").asText()).isEqualTo("SENT");
            assertThat(json.path("localSeq").asLong()).isPositive();
            assertThat(json.path("recorded").asBoolean()).isTrue();
            assertThat(json.path("attempts")).hasSize(2);
            assertThat(json.path("attempts").get(0).path("state").asText())
                    .isEqualTo("FAILED_BEFORE_SEND");
            assertThat(json.path("attempts").get(0).path("errorCode").asText())
                    .isEqualTo("NOT_DISPATCHED");
            assertThat(json.path("attempts").get(1).path("state").asText()).isEqualTo("SENT");
            assertThat(json.path("attempts").get(1).path("localSeq").asLong()).isPositive();
            assertThat(json.path("textState").asText()).isEqualTo("FAILED_BEFORE_SEND");
            assertThat(json.path("textErrorCode").asText()).isEqualTo("TEXT_SEND_FAILED");
            assertThat(json.path("textParts")).isEmpty();
            assertThat(json.path("resendAllowed").asBoolean()).isFalse();
            assertThat(json.path("hint").asText())
                    .contains("文件已发送")
                    .contains("请勿重新上传");

            List<Message> rows = board.read(SOURCE, 0, 10);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).delivery().status())
                    .isEqualTo(DeliveryStatus.FAILED_BEFORE_SEND);
            assertThat(rows.get(1).delivery().status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(json.path("localSeq").asLong())
                    .isEqualTo(rows.get(1).localSeq());
        }
    }

    @Test
    void associatedTextUnknownAfterFileSentFlagsNoResendButKeepsOuterTextContract()
            throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path file = writeFile();
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(9431)); // file upload
            provider.script.add(nonzero(1200, "raw-text-provider-detail")); // text UNKNOWN
            QqToolContext context = context(board, board, provider);

            ToolResult result = new QqConversationSendFileTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "filePath", file.toString(),
                    "text", "caption")));

            assertThat(provider.actions)
                    .containsExactly("upload_private_file", "send_private_msg");
            assertThat(result.success())
                    .as("文本 UNKNOWN 沿用 Task 2 外层成功契约，不诱发自动重发")
                    .isTrue();
            JsonNode json = JSON.readTree(result.message());
            assertThat(json.path("state").asText()).isEqualTo("SENT");
            assertThat(json.path("recorded").asBoolean()).isTrue();
            assertThat(json.path("textState").asText()).isEqualTo("UNKNOWN");
            assertThat(json.path("textParts")).hasSize(1);
            assertThat(json.path("textParts").get(0).path("state").asText())
                    .isEqualTo("UNKNOWN");
            assertThat(json.path("resendAllowed").asBoolean()).isFalse();
            assertThat(json.path("hint").asText())
                    .contains("关联文本")
                    .contains("禁止自动重发");
            assertThat(result.message()).doesNotContain("raw-text-provider-detail");

            List<Message> rows = board.read(SOURCE, 0, 10);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).delivery().status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(rows.get(1).delivery().status()).isEqualTo(DeliveryStatus.UNKNOWN);
        }
    }

    @Test
    void associatedTextBoardWriteFailureMakesTheWholeFileResultNonSuccess() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path file = writeFile();
            FailingBoard failing = new FailingBoard(board);
            failing.failBeginAt = 2; // file row succeeds; the dependent text row fails
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(9501)); // file upload
            provider.script.add(success(9502)); // text must never be dispatched
            QqToolContext context = context(failing, board, provider);

            ToolResult result = new QqConversationSendFileTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "filePath", file.toString(),
                    "text", "caption")));

            assertThat(result.success())
                    .as("关联文本落板失败不得被包成外层成功")
                    .isFalse();
            assertThat(result.code()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(provider.actions).containsExactly("upload_private_file");
            assertThat(result.message()).doesNotContain("simulated begin write failure");

            JsonNode json = JSON.readTree(result.message());
            assertThat(json.path("state").asText()).isEqualTo("SENT");
            assertThat(json.path("recorded").asBoolean()).isTrue();
            assertThat(json.path("textState").asText()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(json.path("textParts")).hasSize(1);
            JsonNode failedText = json.path("textParts").get(0);
            assertThat(failedText.path("state").asText()).isEqualTo("FAILED_BEFORE_SEND");
            assertThat(failedText.path("errorCode").asText()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(failedText.path("localSeq").isNull()).isTrue();
            assertThat(failedText.path("dispatched").asBoolean()).isFalse();
            assertThat(json.path("resendAllowed").asBoolean()).isFalse();
            assertThat(json.path("hint").asText()).contains("禁止重新上传");

            List<Message> rows = board.read(SOURCE, 0, 10);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().delivery().status()).isEqualTo(DeliveryStatus.SENT);
        }
    }

    @Test
    void initialBoardWriteFailureStopsTheFileAndTheAssociatedText() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path file = writeFile();
            FailingBoard failing = new FailingBoard(board);
            failing.failBegin = true;
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(9601)); // must never be used
            QqToolContext context = context(failing, board, provider);

            ToolResult result = new QqConversationSendFileTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "filePath", file.toString(),
                    "text", "caption")));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(provider.actions).isEmpty();
            assertThat(board.read(SOURCE, 0, 10)).isEmpty();
            assertThat(result.message()).doesNotContain("simulated begin write failure");

            JsonNode json = JSON.readTree(result.message());
            assertThat(json.path("state").asText()).isEqualTo("FAILED_BEFORE_SEND");
            assertThat(json.path("errorCode").asText()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(json.path("localSeq").isNull()).isTrue();
            assertThat(json.path("recorded").asBoolean()).isFalse();
            assertThat(json.path("dispatched").asBoolean()).isFalse();
            assertThat(json.path("textState").asText()).isEqualTo("NOT_SENT");
            assertThat(json.path("resendAllowed").asBoolean()).isFalse();
            assertThat(json.path("hint").asText()).contains("禁止重发");
        }
    }

    @Test
    void terminalBoardWriteFailureIsUnknownAndPreventsAssociatedText() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path file = writeFile();
            FailingBoard failing = new FailingBoard(board);
            failing.failFinish = true;
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(9701)); // file upload
            provider.script.add(success(9702)); // text must never be dispatched
            QqToolContext context = context(failing, board, provider);

            ToolResult result = new QqConversationSendFileTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "filePath", file.toString(),
                    "text", "caption")));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(provider.actions).containsExactly("upload_private_file");
            JsonNode json = JSON.readTree(result.message());
            assertThat(json.path("state").asText()).isEqualTo("UNKNOWN");
            assertThat(json.path("providerState").asText()).isEqualTo("SENT");
            assertThat(json.path("errorCode").asText()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(json.path("recorded").asBoolean()).isFalse();
            assertThat(json.path("localSeq").asLong()).isPositive();
            assertThat(json.path("textState").asText()).isEqualTo("NOT_SENT");
            assertThat(json.path("resendAllowed").asBoolean()).isFalse();
            assertThat(json.path("hint").asText()).contains("禁止重发");

            Message stored = onlyOutbound(board);
            assertThat(stored.delivery().status()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(stored.externalMessageId()).isEmpty();
        }
    }

    @Test
    void fileSuccessWithoutExternalIdIsStillSent() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(successWithoutId());
            QqToolContext context = context(board, board, provider);

            ToolResult result = new QqConversationSendFileTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "providerPath", "/remote/report.txt")));

            JsonNode json = JSON.readTree(result.message());
            assertThat(json.path("state").asText()).isEqualTo("SENT");
            assertThat(json.path("externalMessageId").asText()).isEmpty();
            assertThat(json.path("recorded").asBoolean()).isTrue();
            Message stored = onlyOutbound(board);
            assertThat(stored.delivery().status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(stored.externalMessageId()).isEmpty();
        }
    }

    @Test
    void invalidAssociatedTextIsRejectedBeforeAnyFileDispatch() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path file = writeFile();
            FakeProvider provider = new FakeProvider(board);
            QqToolContext context = context(board, board, provider);

            ToolResult result = new QqConversationSendFileTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "filePath", file.toString(),
                    "text", "x".repeat(3001))));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("TEXT_TOO_LONG");
            assertThat(provider.actions).isEmpty();
            assertThat(board.read(SOURCE, 0, 10)).isEmpty();
        }
    }

    @Test
    void fileRoutesToTheThreadAccountAndUnknownAccountsDispatchNothing() throws Exception {
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
            first.script.add(success(9801));
            second.script.add(success(9802));
            SourceAddress bot1 = SourceAddress.parse("qq_private/bot-1/20002");
            SourceAddress bot2 = SourceAddress.parse("qq_private/bot-2/20002");
            SourceAddress unknown = SourceAddress.parse("qq_private/bot-9/20002");

            ToolResult sent = new QqConversationSendFileTool(context).execute(call(Map.of(
                    "source", bot2.format(), "providerPath", "/remote/report.txt")));

            assertThat(JSON.readTree(sent.message()).path("state").asText()).isEqualTo("SENT");
            assertThat(first.actions).isEmpty();
            assertThat(second.actions).containsExactly("upload_private_file");
            assertThat(second.params.getFirst().path("user_id").asLong()).isEqualTo(20002L);
            assertThat(board.read(bot1, 0, 10)).isEmpty();
            assertThat(board.read(bot2, 0, 10)).hasSize(1);
            assertThat(board.read(bot2, 0, 10).getFirst().externalMessageId())
                    .isEqualTo("9802");

            ToolResult rejected = new QqConversationSendFileTool(context).execute(call(Map.of(
                    "source", unknown.format(), "providerPath", "/remote/report.txt")));

            assertThat(rejected.success()).isFalse();
            assertThat(rejected.code()).isEqualTo("ACCOUNT_NOT_FOUND");
            assertThat(first.actions).isEmpty();
            assertThat(second.actions).containsExactly("upload_private_file");
            assertThat(board.read(unknown, 0, 10)).isEmpty();
        }
    }

    // ----------------------------------------------------------------- images

    @Test
    @Timeout(150)
    void imageThatNeverCompletesWithinTheBoundIsUnknownAndLateSuccessCannotRewrite()
            throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path image = writeImage();
            FakeProvider provider = new FakeProvider(board);
            CompletableFuture<JsonNode> late = new CompletableFuture<>();
            provider.script.add(late);
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "late-image-response");
                thread.setDaemon(true);
                return thread;
            });
            java.util.concurrent.CountDownLatch lateAttempted =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean lateAccepted =
                    new java.util.concurrent.atomic.AtomicBoolean();
            // The timeout marks the returned future terminal; a real late frame can only fail to
            // complete it. This is exactly the property under test.
            scheduler.schedule(() -> {
                lateAccepted.set(late.complete(success(9901)));
                lateAttempted.countDown();
            }, 70, TimeUnit.SECONDS);
            try {
                QqToolContext context = context(board, board, provider);

                ToolResult result = new QqConversationSendImageTool(context).execute(call(Map.of(
                        "source", SOURCE.format(),
                        "imagePath", image.toString(),
                        "text", "caption")));

                assertThat(provider.actions).containsExactly("send_private_msg");
                assertThat(result.success())
                        .as("附件 UNKNOWN 沿用 Task 2 外层成功契约，不诱发自动重发")
                        .isTrue();
                JsonNode json = JSON.readTree(result.message());
                assertThat(json.path("state").asText()).isEqualTo("UNKNOWN");
                assertThat(json.path("errorCode").asText()).isEqualTo("TIMEOUT");
                assertThat(json.path("recorded").asBoolean()).isTrue();
                assertThat(json.path("localSeq").asLong()).isPositive();
                assertThat(json.path("messageId").asText()).isEmpty();
                assertThat(json.path("textState").asText()).isEqualTo("UNKNOWN");
                assertThat(json.path("resendAllowed").asBoolean())
                        .as("附件 UNKNOWN 禁止自动重发")
                        .isFalse();
                assertThat(json.path("hint").asText()).contains("禁止自动重发");

                Message afterTimeout = onlyOutbound(board);
                assertThat(afterTimeout.delivery().status()).isEqualTo(DeliveryStatus.UNKNOWN);
                assertThat(afterTimeout.delivery().errorCode()).isEqualTo("TIMEOUT");
                assertThat(afterTimeout.externalMessageId()).isEmpty();

                assertThat(lateAttempted.await(20, TimeUnit.SECONDS)).isTrue();
                assertThat(lateAccepted)
                        .as("超时后的迟到响应不能改写已完成的 provider future")
                        .isFalse();

                assertThat(provider.actions).containsExactly("send_private_msg");
                Message afterLateSuccess = onlyOutbound(board);
                assertThat(afterLateSuccess.delivery().status())
                        .isEqualTo(DeliveryStatus.UNKNOWN);
                assertThat(afterLateSuccess.externalMessageId()).isEmpty();
            } finally {
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    void imageWithAssociatedTextPersistsPendingBeforeDispatchAndSendsWithoutId() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path image = writeImage();
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(successWithoutId());
            QqToolContext context = context(board, board, provider);

            ToolResult result = new QqConversationSendImageTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "imagePath", image.toString(),
                    "text", "caption")));

            assertThat(provider.actions).containsExactly("send_private_msg");
            List<Message> seen = provider.rowsAtCall.getFirst();
            assertThat(seen).hasSize(1);
            assertThat(seen.getFirst().delivery().status()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(seen.getFirst().delivery().partIndex()).isEqualTo(1);
            assertThat(seen.getFirst().delivery().partTotal()).isEqualTo(1);
            assertThat(provider.params.getFirst().path("message")).hasSize(2);
            assertThat(provider.params.getFirst().path("message").toString())
                    .contains("base64://");

            JsonNode json = JSON.readTree(result.message());
            assertThat(result.success()).isTrue();
            assertThat(json.path("state").asText()).isEqualTo("SENT");
            assertThat(json.path("messageId").asText()).isEmpty();
            assertThat(json.path("recorded").asBoolean()).isTrue();
            assertThat(json.path("textState").asText()).isEqualTo("SENT");
            assertThat(json.has("resendAllowed"))
                    .as("正常 SENT 不应被误报为禁止重发")
                    .isFalse();
            assertThat(json.has("hint")).isFalse();

            Message stored = onlyOutbound(board);
            assertThat(stored.delivery().status()).isEqualTo(DeliveryStatus.SENT);
            assertThat(stored.externalMessageId()).isEmpty();
        }
    }

    @Test
    void imageBoardRowContainsTheCaptionSegmentThatWasActuallySent() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path image = writeImage();
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(9951));
            QqToolContext context = context(board, board, provider);

            ToolResult result = new QqConversationSendImageTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "imagePath", image.toString(),
                    "text", "captioned login QR")));

            assertThat(result.success()).isTrue();
            JsonNode sentMessage = provider.params.getFirst().path("message");
            assertThat(sentMessage).hasSize(2);
            assertThat(sentMessage.get(0).path("type").asText()).isEqualTo("text");
            assertThat(sentMessage.get(0).path("data").path("text").asText())
                    .isEqualTo("captioned login QR");
            assertThat(sentMessage.get(1).path("type").asText()).isEqualTo("image");

            Message stored = onlyOutbound(board);
            assertThat(stored.inbound().segments()).hasSize(2);
            assertThat(stored.inbound().segments().get(0).type()).isEqualTo("text");
            assertThat(stored.inbound().segments().get(0).text())
                    .as("消息板必须包含与实际发送一致的 caption 段")
                    .isEqualTo("captioned login QR");
            assertThat(stored.inbound().segments().get(1).type()).isEqualTo("image");
            assertThat(QqToolSupport.messageText(stored)).contains("captioned login QR");
        }
    }

    @Test
    void imageNonzeroResponseIsUnknownAndHasNoSeparateTextFollowup() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path image = writeImage();
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(nonzero(1200, "raw-image-provider-detail"));
            provider.script.add(success(10001)); // must never be used
            QqToolContext context = context(board, board, provider);

            ToolResult result = new QqConversationSendImageTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "imagePath", image.toString(),
                    "text", "caption")));

            assertThat(provider.actions).containsExactly("send_private_msg");
            assertThat(result.success())
                    .as("附件 UNKNOWN 沿用 Task 2 外层成功契约，不诱发自动重发")
                    .isTrue();
            JsonNode json = JSON.readTree(result.message());
            assertThat(json.path("state").asText()).isEqualTo("UNKNOWN");
            assertThat(json.path("errorCode").asText()).isEqualTo("PROVIDER_NONZERO");
            assertThat(json.path("recorded").asBoolean()).isTrue();
            assertThat(json.path("textState").asText()).isEqualTo("UNKNOWN");
            assertThat(json.path("resendAllowed").asBoolean())
                    .as("附件 UNKNOWN 禁止自动重发")
                    .isFalse();
            assertThat(json.path("hint").asText()).contains("禁止自动重发");
            assertThat(result.message()).doesNotContain("raw-image-provider-detail");
            assertThat(onlyOutbound(board).delivery().status())
                    .isEqualTo(DeliveryStatus.UNKNOWN);
        }
    }

    @Test
    void imageInitialBoardWriteFailureIsNonSuccessAndDispatchesNothing() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path image = writeImage();
            FailingBoard failing = new FailingBoard(board);
            failing.failBegin = true;
            FakeProvider provider = new FakeProvider(board);
            QqToolContext context = context(failing, board, provider);

            ToolResult result = new QqConversationSendImageTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "imagePath", image.toString(),
                    "text", "caption")));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(provider.actions).isEmpty();
            assertThat(board.read(SOURCE, 0, 10)).isEmpty();
            assertThat(result.message()).doesNotContain("simulated begin write failure");
            JsonNode json = JSON.readTree(result.message());
            assertThat(json.path("state").asText()).isEqualTo("FAILED_BEFORE_SEND");
            assertThat(json.path("errorCode").asText()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(json.path("localSeq").isNull()).isTrue();
            assertThat(json.path("recorded").asBoolean()).isFalse();
            assertThat(json.path("dispatched").asBoolean()).isFalse();
            assertThat(json.path("textState").asText()).isEqualTo("FAILED_BEFORE_SEND");
            assertThat(json.path("resendAllowed").asBoolean()).isFalse();
            assertThat(json.path("hint").asText()).contains("禁止重发");
        }
    }

    @Test
    void imageTerminalBoardWriteFailureIsUnknownNonSuccessAndKeepsPendingRow() throws Exception {
        try (SqliteMessageBoardStore board = board()) {
            Path image = writeImage();
            FailingBoard failing = new FailingBoard(board);
            failing.failFinish = true;
            FakeProvider provider = new FakeProvider(board);
            provider.script.add(success(10101));
            QqToolContext context = context(failing, board, provider);

            ToolResult result = new QqConversationSendImageTool(context).execute(call(Map.of(
                    "source", SOURCE.format(),
                    "imagePath", image.toString())));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(provider.actions).containsExactly("send_private_msg");
            JsonNode json = JSON.readTree(result.message());
            assertThat(json.path("state").asText()).isEqualTo("UNKNOWN");
            assertThat(json.path("providerState").asText()).isEqualTo("SENT");
            assertThat(json.path("errorCode").asText()).isEqualTo("BOARD_WRITE_FAILED");
            assertThat(json.path("recorded").asBoolean()).isFalse();
            assertThat(json.path("messageId").asText()).isEmpty();
            assertThat(json.path("resendAllowed").asBoolean()).isFalse();
            assertThat(json.path("hint").asText()).contains("禁止重发");
            assertThat(onlyOutbound(board).delivery().status())
                    .isEqualTo(DeliveryStatus.PENDING);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), arguments, AgentIdentity.external());
    }

    private SqliteMessageBoardStore board() {
        return new SqliteMessageBoardStore(tempDir.resolve("bot.db"));
    }

    private Path writeFile() throws Exception {
        Path file = tempDir.resolve("report.txt");
        Files.writeString(file, "hello");
        return file;
    }

    private Path writeImage() throws Exception {
        Path image = tempDir.resolve("qr.png");
        Files.write(image, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        return image;
    }

    private QqToolContext context(MessageBoardStore board, SqliteMessageBoardStore consumers,
                                  FakeProvider provider) {
        AccountRegistry registry = new AccountRegistry((config, listener) -> provider, event -> { });
        registry.reconcile(List.of(new AccountConfig(
                "bot-1", URI.create("ws://127.0.0.1:1"), "token", true)));
        return new QqToolContext(registry, board, consumers, tempDir, null,
                QqSendPolicy::defaults, null);
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
        private final List<Boolean> connectedScript = new ArrayList<>();
        private final List<List<Message>> rowsAtCall = new ArrayList<>();
        private final List<String> actions = new ArrayList<>();
        private final List<JsonNode> params = new ArrayList<>();
        private java.util.function.Consumer<String> afterAction;
        private int calls;

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
            if (connectedScript.isEmpty()) {
                return true;
            }
            return connectedScript.remove(0);
        }

        @Override
        public CompletableFuture<JsonNode> sendAction(String action, Map<String, Object> params) {
            if ("get_login_info".equals(action)) {
                return CompletableFuture.completedFuture(LOGIN);
            }
            calls++;
            actions.add(action);
            this.params.add(JSON.valueToTree(params));
            rowsAtCall.add(List.copyOf(board.read(source, 0, 100)));
            if (afterAction != null) {
                afterAction.accept(action);
            }
            Object next = script.isEmpty()
                    ? success(calls)
                    : script.get(Math.min(calls - 1, script.size() - 1));
            if (next instanceof CompletableFuture<?> future) {
                @SuppressWarnings("unchecked")
                CompletableFuture<JsonNode> cast = (CompletableFuture<JsonNode>) future;
                return cast;
            }
            if (next instanceof RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture((JsonNode) next);
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

        FailingBoard(SqliteMessageBoardStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public AppendResult append(InboundMessage inbound) {
            return delegate.append(inbound);
        }

        @Override
        public AppendResult beginOutbound(InboundMessage message, String operationId,
                                          int partIndex, int partTotal) {
            beginCalls++;
            if (failBegin || (failBeginAt > 0 && beginCalls == failBeginAt)) {
                throw new IllegalStateException("simulated begin write failure");
            }
            return delegate.beginOutbound(message, operationId, partIndex, partTotal);
        }

        @Override
        public boolean finishOutbound(long localSeq, DeliveryStatus status,
                                      String externalMessageId, String errorCode) {
            if (failFinish) {
                throw new IllegalStateException("simulated terminal write failure");
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
