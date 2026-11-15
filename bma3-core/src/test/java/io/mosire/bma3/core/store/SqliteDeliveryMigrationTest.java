package io.mosire.bma3.core.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.bma3.core.message.DeliveryStatus;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Upgrade path for a board written by 0.0.6, which has no delivery columns.
 *
 * <p>Opening the old database must add the columns, mark historical outbound rows
 * {@code LEGACY_UNKNOWN} without touching {@code local_seq} or {@code external_message_id}, leave
 * inbound rows without any delivery state, and stay stable when the store is opened again. The
 * assertions read through the real history API, not through the migration SQL.</p>
 */
class SqliteDeliveryMigrationTest {

    private static final String SOURCE = "qq_private/bot-1/123";
    private static final String ACCOUNT = "bot-1";

    @TempDir
    Path tempDir;

    @Test
    void openingLegacyBoardMarksHistoricalOutboundUncertainAndIsRepeatable() throws Exception {
        Path database = tempDir.resolve("data/bot.db");
        Files.createDirectories(database.getParent());
        createLegacyBoard(database);

        SourceAddress source = SourceAddress.parse(SOURCE);

        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            List<Message> history = store.read(source, 0L, 10);

            assertThat(history).hasSize(2);
            Message inbound = history.get(0);
            Message outbound = history.get(1);

            assertThat(inbound.localSeq()).isEqualTo(1L);
            assertThat(inbound.externalMessageId()).isEqualTo("legacy-in");
            assertThat(inbound.delivery()).isNull();

            assertThat(outbound.localSeq()).isEqualTo(2L);
            assertThat(outbound.externalMessageId()).isEqualTo("777");
            assertThat(outbound.delivery()).isNotNull();
            assertThat(outbound.delivery().status()).isEqualTo(DeliveryStatus.LEGACY_UNKNOWN);
            assertThat(outbound.delivery().operationId()).isBlank();
            assertThat(outbound.delivery().partIndex()).isZero();
            assertThat(outbound.delivery().partTotal()).isZero();
            assertThat(outbound.delivery().errorCode()).isNull();
        }

        try (SqliteMessageBoardStore reopened = new SqliteMessageBoardStore(database)) {
            List<Message> history = reopened.read(source, 0L, 10);

            assertThat(history).hasSize(2);
            assertThat(history.get(0).localSeq()).isEqualTo(1L);
            assertThat(history.get(0).externalMessageId()).isEqualTo("legacy-in");
            assertThat(history.get(0).delivery()).isNull();

            assertThat(history.get(1).localSeq()).isEqualTo(2L);
            assertThat(history.get(1).externalMessageId()).isEqualTo("777");
            assertThat(history.get(1).delivery()).isNotNull();
            assertThat(history.get(1).delivery().status()).isEqualTo(DeliveryStatus.LEGACY_UNKNOWN);
            assertThat(history.get(1).delivery().operationId()).isBlank();
            assertThat(history.get(1).delivery().partIndex()).isZero();
            assertThat(history.get(1).delivery().partTotal()).isZero();
            assertThat(history.get(1).delivery().errorCode()).isNull();
        }
    }

    @Test
    void migrationPreservesLegacyIndexesAndAutoincrementSequence() throws Exception {
        Path database = tempDir.resolve("data/bot.db");
        Files.createDirectories(database.getParent());
        createLegacyBoard(database);

        // First open applies the migration only.
        try (SqliteMessageBoardStore ignored = new SqliteMessageBoardStore(database)) {
            // migration only
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            Set<String> indexes = new HashSet<>();
            try (ResultSet result = statement.executeQuery("PRAGMA index_list(chat_message)")) {
                while (result.next()) {
                    indexes.add(result.getString("name"));
                }
            }
            assertThat(indexes).contains("idx_chat_message_source_seq",
                    "idx_chat_message_external");

            // AUTOINCREMENT bookkeeping must survive the migration: the legacy rows used 1 and 2.
            try (ResultSet result = statement.executeQuery(
                    "SELECT seq FROM sqlite_sequence WHERE name = 'chat_message'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(2L);
            }
        }

        long nextSeq;
        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            nextSeq = store.beginOutbound(outbound("new-out"), "op-new", 1, 1)
                    .message().localSeq();
            assertThat(nextSeq).isEqualTo(3L);
        }

        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            List<Message> history = store.read(SourceAddress.parse(SOURCE), 0L, 10);
            assertThat(history).hasSize(3);
            assertThat(history.get(0).localSeq()).isEqualTo(1L);
            assertThat(history.get(0).delivery()).isNull();
            assertThat(history.get(1).localSeq()).isEqualTo(2L);
            assertThat(history.get(1).externalMessageId()).isEqualTo("777");
            assertThat(history.get(1).delivery().status()).isEqualTo(DeliveryStatus.LEGACY_UNKNOWN);
            assertThat(history.get(2).localSeq()).isEqualTo(3L);
            assertThat(history.get(2).delivery().operationId()).isEqualTo("op-new");
            assertThat(history.get(2).delivery().partIndex()).isEqualTo(1);
            assertThat(history.get(2).delivery().partTotal()).isEqualTo(1);
            assertThat(history.get(2).delivery().status()).isEqualTo(DeliveryStatus.PENDING);
        }
    }

    /** A new outbound row created after the migration. */
    private static InboundMessage outbound(String messageId) {
        return new InboundMessage(SourceAddress.parse(SOURCE), messageId,
                Instant.parse("2026-11-15T09:00:00Z"),
                List.of(new Segment("text", "新出站")), Direction.OUTBOUND, ACCOUNT, "机器人");
    }

    /** Recreates the exact 0.0.6 {@code chat_message} schema, then inserts one inbound/outbound row. */
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
                statement.setString(1, SOURCE + "|legacy-in");
                statement.setString(2, SOURCE);
                statement.setString(3, ACCOUNT);
                statement.setString(4, "INBOUND");
                statement.setString(5, "legacy-in");
                statement.setString(6, "2026-11-14T10:00:00Z");
                statement.setString(7, "历史入站");
                statement.setString(8, "123");
                statement.setString(9, "张三");
                statement.setString(10, "");
                statement.setString(11, "member");
                statement.setString(12, "[{\"type\":\"text\",\"text\":\"历史入站\"}]");
                statement.setString(13, "legacy-in");
                statement.executeUpdate();

                statement.setString(1, SOURCE + "|legacy-out");
                statement.setString(2, SOURCE);
                statement.setString(3, ACCOUNT);
                statement.setString(4, "OUTBOUND");
                statement.setString(5, "legacy-out");
                statement.setString(6, "2026-11-14T10:01:00Z");
                statement.setString(7, "历史出站");
                statement.setString(8, "bot-1");
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
