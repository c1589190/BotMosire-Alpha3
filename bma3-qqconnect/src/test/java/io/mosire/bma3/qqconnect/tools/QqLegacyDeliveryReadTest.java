package io.mosire.bma3.qqconnect.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Read-surface compatibility for rows that carry no reliable delivery state: historical outbound
 * rows migrated to {@code LEGACY_UNKNOWN}, and the still-supported same-process legacy
 * {@code append} path whose outbound row has SQL NULL delivery state.
 */
class QqLegacyDeliveryReadTest {

    private static final SourceAddress SOURCE = SourceAddress.parse("qq_private/bot-1/20002");
    private static final String ACCOUNT = "bot-1";

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void migratedLegacyOutboundReadsAsLegacyUnknown() throws Exception {
        Path database = tempDir.resolve("data/bot.db");
        Files.createDirectories(database.getParent());
        createLegacyBoard(database);

        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(database)) {
            QqToolContext context = new QqToolContext(null, board, board, tempDir);

            JsonNode history = json(new QqConversationHistoryTool(context).execute(
                    call(Map.of("source", SOURCE.format(), "limit", 10))));
            assertThat(history.path("messages")).hasSize(2);
            JsonNode out = find(history.path("messages"), 2L);
            assertThat(out.path("direction").asText()).isEqualTo("OUT");
            assertThat(out.path("externalMessageId").asText()).isEqualTo("777");
            assertThat(out.path("delivery").path("status").asText()).isEqualTo("LEGACY_UNKNOWN");
            assertThat(out.path("delivery").path("operationId").asText()).isEmpty();
            assertThat(out.path("delivery").path("partIndex").asInt()).isZero();
            assertThat(out.path("delivery").path("partTotal").asInt()).isZero();
            assertThat(out.path("delivery").has("errorCode")).isFalse();

            JsonNode singleOut = json(new QqMessageGetTool(context).execute(
                    call(Map.of("source", SOURCE.format(), "seq", 2))));
            assertThat(singleOut.path("delivery").path("status").asText())
                    .isEqualTo("LEGACY_UNKNOWN");
            assertThat(singleOut.path("externalMessageId").asText()).isEqualTo("777");

            JsonNode singleIn = json(new QqMessageGetTool(context).execute(
                    call(Map.of("source", SOURCE.format(), "seq", 1))));
            assertThat(singleIn.path("direction").asText()).isEqualTo("IN");
            assertThat(singleIn.path("externalMessageId").asText()).isEqualTo("legacy-in");
            assertThat(singleIn.has("delivery")).isFalse();
        }
    }

    @Test
    void sameProcessLegacyAppendStaysWithoutDeliveryMetadata() throws Exception {
        Path database = tempDir.resolve("data/bot.db");
        Files.createDirectories(database.getParent());
        createLegacyBoard(database);

        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(database)) {
            long appended = board.append(outbound("legacy-append")).message().localSeq();
            QqToolContext context = new QqToolContext(null, board, board, tempDir);

            JsonNode history = json(new QqConversationHistoryTool(context).execute(
                    call(Map.of("source", SOURCE.format(), "limit", 10))));
            JsonNode row = find(history.path("messages"), appended);
            assertThat(row.path("direction").asText()).isEqualTo("OUT");
            assertThat(row.path("externalMessageId").asText()).isEmpty();
            assertThat(row.has("delivery"))
                    .as("兼容 append 的未定义状态不得被读取面伪造成 LEGACY_UNKNOWN 或其他状态")
                    .isFalse();

            JsonNode single = json(new QqMessageGetTool(context).execute(
                    call(Map.of("source", SOURCE.format(), "seq", appended))));
            assertThat(single.has("delivery")).isFalse();
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

    private static InboundMessage outbound(String messageId) {
        return new InboundMessage(SOURCE, messageId, Instant.parse("2026-11-15T09:00:00Z"),
                List.of(new Segment("text", "兼容出站")), Direction.OUTBOUND, ACCOUNT, "机器人");
    }

    /** Recreates the 0.0.6 {@code chat_message} schema (no delivery columns). */
    private static void createLegacyBoard(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE chat_message (
                          local_seq INTEGER PRIMARY KEY AUTOINCREMENT,
                          message_key TEXT NOT NULL UNIQUE,
                          source TEXT NOT NULL,
                          account_id TEXT NOT NULL,
                          direction TEXT NOT NULL,
                          message_id TEXT NOT NULL,
                          at TEXT NOT NULL,
                          text TEXT NOT NULL,
                          sender_id TEXT,
                          sender_name TEXT,
                          sender_card TEXT,
                          sender_role TEXT,
                          segments_json TEXT,
                          external_message_id TEXT
                        )
                        """);
                statement.execute("""
                        CREATE INDEX idx_chat_message_source_seq
                        ON chat_message(source, local_seq)
                        """);
                statement.execute("""
                        CREATE INDEX idx_chat_message_external
                        ON chat_message(source, external_message_id)
                        """);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO chat_message (
                      message_key, source, account_id, direction, message_id, at, text,
                      sender_id, sender_name, sender_card, sender_role, segments_json,
                      external_message_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, SOURCE.format() + "|legacy-in");
                statement.setString(2, SOURCE.format());
                statement.setString(3, ACCOUNT);
                statement.setString(4, "INBOUND");
                statement.setString(5, "legacy-in");
                statement.setString(6, "2026-11-14T10:00:00Z");
                statement.setString(7, "历史入站");
                statement.setString(8, "20002");
                statement.setString(9, "Alice");
                statement.setString(10, "");
                statement.setString(11, "member");
                statement.setString(12, "[{\"type\":\"text\",\"text\":\"历史入站\"}]");
                statement.setString(13, "legacy-in");
                statement.executeUpdate();

                statement.setString(1, SOURCE.format() + "|legacy-out");
                statement.setString(2, SOURCE.format());
                statement.setString(3, ACCOUNT);
                statement.setString(4, "OUTBOUND");
                statement.setString(5, "legacy-out");
                statement.setString(6, "2026-11-14T10:01:00Z");
                statement.setString(7, "历史出站");
                statement.setString(8, ACCOUNT);
                statement.setString(9, "机器人");
                statement.setString(10, "");
                statement.setString(11, "");
                statement.setString(12, "[{\"type\":\"text\",\"text\":\"历史出站\"}]");
                statement.setString(13, "777");
                statement.executeUpdate();
            }
        }
    }
}
