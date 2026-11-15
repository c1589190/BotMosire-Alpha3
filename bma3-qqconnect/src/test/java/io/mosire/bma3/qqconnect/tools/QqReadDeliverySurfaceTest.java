package io.mosire.bma3.qqconnect.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.message.DeliveryStatus;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The existing QQ read surfaces (history and single-message get) expose the persisted outbound
 * delivery state, its operation id and its part association, while inbound rows keep no delivery
 * data at all.
 */
class QqReadDeliverySurfaceTest {

    private static final SourceAddress SOURCE = SourceAddress.parse("qq_private/bot-1/20002");

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void historyAndGetExposeDeliveryMetadataForOutboundOnly() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("bot.db"))) {
            long inboundSeq = board.append(inbound("in-1")).message().localSeq();
            long sentSeq = board.beginOutbound(outbound("out-sent"), "op-surface-1", 2, 3)
                    .message().localSeq();
            board.finishOutbound(sentSeq, DeliveryStatus.SENT, "777", null);
            long unknownSeq = board.beginOutbound(outbound("out-unknown"), "op-surface-2", 1, 2)
                    .message().localSeq();
            board.finishOutbound(unknownSeq, DeliveryStatus.UNKNOWN, null, "TIMEOUT");

            QqToolContext context = new QqToolContext(null, board, board, tempDir);
            QqConversationHistoryTool historyTool = new QqConversationHistoryTool(context);
            QqMessageGetTool getTool = new QqMessageGetTool(context);
            QqConversationReadTool readTool = new QqConversationReadTool(context);

            JsonNode history = json(historyTool.execute(
                    call(Map.of("source", SOURCE.format(), "limit", 10))));
            JsonNode sent = find(history.path("messages"), sentSeq);
            assertThat(sent.path("direction").asText()).isEqualTo("OUT");
            assertThat(sent.path("delivery").path("status").asText()).isEqualTo("SENT");
            assertThat(sent.path("delivery").path("operationId").asText())
                    .isEqualTo("op-surface-1");
            assertThat(sent.path("delivery").path("partIndex").asInt()).isEqualTo(2);
            assertThat(sent.path("delivery").path("partTotal").asInt()).isEqualTo(3);
            assertThat(sent.path("delivery").has("errorCode")).isFalse();

            JsonNode unknown = find(history.path("messages"), unknownSeq);
            assertThat(unknown.path("delivery").path("status").asText()).isEqualTo("UNKNOWN");
            assertThat(unknown.path("delivery").path("errorCode").asText()).isEqualTo("TIMEOUT");

            JsonNode inboundNode = json(getTool.execute(
                    call(Map.of("source", SOURCE.format(), "seq", inboundSeq))));
            assertThat(inboundNode.path("direction").asText()).isEqualTo("IN");
            assertThat(inboundNode.has("delivery"))
                    .as("入站记录没有发送状态，不得伪造")
                    .isFalse();

            JsonNode sentNode = json(getTool.execute(
                    call(Map.of("source", SOURCE.format(), "seq", sentSeq))));
            assertThat(sentNode.path("delivery").path("operationId").asText())
                    .isEqualTo("op-surface-1");
            assertThat(sentNode.path("delivery").path("partIndex").asInt()).isEqualTo(2);
            assertThat(sentNode.path("delivery").path("partTotal").asInt()).isEqualTo(3);

            // The unread-read surface stays inbound-only, so it must not invent delivery data.
            JsonNode read = json(readTool.execute(call(Map.of("source", SOURCE.format()))));
            assertThat(read.path("messages")).hasSize(1);
            assertThat(read.path("messages").get(0).path("seq").asLong()).isEqualTo(inboundSeq);
            assertThat(read.path("messages").get(0).has("delivery")).isFalse();
            assertThat(read.path("readSeq").asLong()).isZero();
            assertThat(read.path("lastSeq").asLong()).isEqualTo(inboundSeq);
        }
    }

    @Test
    void aStoppedMultiPartOperationKeepsItsOperationAndPartAssociation() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("bot.db"))) {
            long first = board.beginOutbound(outbound("multi-1"), "op-multi", 1, 3)
                    .message().localSeq();
            board.finishOutbound(first, DeliveryStatus.SENT, "9001", null);
            long second = board.beginOutbound(outbound("multi-2"), "op-multi", 2, 3)
                    .message().localSeq();
            board.finishOutbound(second, DeliveryStatus.UNKNOWN, null, "TIMEOUT");
            // Part 3 was never attempted: no row may be invented for it.

            QqToolContext context = new QqToolContext(null, board, board, tempDir);
            JsonNode history = json(new QqConversationHistoryTool(context).execute(
                    call(Map.of("source", SOURCE.format(), "limit", 10))));
            assertThat(history.path("messages")).hasSize(2);

            JsonNode one = find(history.path("messages"), first);
            assertThat(one.path("delivery").path("status").asText()).isEqualTo("SENT");
            assertThat(one.path("delivery").path("operationId").asText()).isEqualTo("op-multi");
            assertThat(one.path("delivery").path("partIndex").asInt()).isEqualTo(1);
            assertThat(one.path("delivery").path("partTotal").asInt()).isEqualTo(3);
            assertThat(one.path("externalMessageId").asText()).isEqualTo("9001");

            JsonNode two = find(history.path("messages"), second);
            assertThat(two.path("delivery").path("status").asText()).isEqualTo("UNKNOWN");
            assertThat(two.path("delivery").path("operationId").asText()).isEqualTo("op-multi");
            assertThat(two.path("delivery").path("partIndex").asInt()).isEqualTo(2);
            assertThat(two.path("delivery").path("partTotal").asInt()).isEqualTo(3);
            assertThat(two.path("delivery").path("errorCode").asText()).isEqualTo("TIMEOUT");
            assertThat(two.path("externalMessageId").asText()).isEmpty();

            for (JsonNode message : history.path("messages")) {
                assertThat(message.path("delivery").path("partIndex").asInt())
                        .as("第 3 片没有实际发送记录，不得伪造")
                        .isNotEqualTo(3);
            }
        }
    }

    private JsonNode json(ToolResult result) throws Exception {
        assertThat(result.success()).isTrue();
        return mapper.readTree(result.message());
    }

    private static JsonNode find(JsonNode messages, long localSeq) {
        for (JsonNode message : messages) {
            if (message.path("seq").asLong() == localSeq) {
                return message;
            }
        }
        throw new AssertionError("history 缺少 seq=" + localSeq);
    }

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), arguments, AgentIdentity.external());
    }

    private static InboundMessage inbound(String messageId) {
        return new InboundMessage(SOURCE, messageId, Instant.parse("2026-11-15T09:00:00Z"),
                List.of(new Segment("text", "入站正文")), Direction.INBOUND, "20002", "Alice");
    }

    private static InboundMessage outbound(String messageId) {
        return new InboundMessage(SOURCE, messageId, Instant.parse("2026-11-15T09:00:00Z"),
                List.of(new Segment("text", "出站正文")), Direction.OUTBOUND, "bot-1", "机器人");
    }
}
