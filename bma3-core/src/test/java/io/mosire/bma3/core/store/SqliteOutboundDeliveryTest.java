package io.mosire.bma3.core.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.bma3.core.message.Delivery;
import io.mosire.bma3.core.message.DeliveryStatus;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Outbound delivery transitions on the message board: PENDING is persisted before dispatch,
 * terminal updates are atomic and single-shot, recovery is explicit, and inbound rows never gain
 * delivery state.
 */
class SqliteOutboundDeliveryTest {

    private static final SourceAddress SOURCE = SourceAddress.parse("qq_group/bot-1/955432692");

    @TempDir
    Path tempDir;

    @Test
    void beginOutboundPersistsPendingWithPartMetadata() {
        Path database = tempDir.resolve("bot.db");

        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            AppendResult begun = store.beginOutbound(outbound("out-1"), "op-1", 2, 3);

            assertThat(begun.created()).isTrue();
            assertThat(begun.message().localSeq()).isEqualTo(1L);
            assertThat(begun.message().externalMessageId()).isEmpty();
            assertThat(begun.message().delivery())
                    .isEqualTo(new Delivery("op-1", 2, 3, DeliveryStatus.PENDING, null));

            Message persisted = singleRow(store.read(SOURCE, 0L, 10), 1L);
            assertThat(persisted.delivery())
                    .isEqualTo(new Delivery("op-1", 2, 3, DeliveryStatus.PENDING, null));
        }
    }

    @Test
    void finishOutboundRecordsSentWithOrWithoutExternalId() {
        Path database = tempDir.resolve("bot.db");

        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            long withId = store.beginOutbound(outbound("out-a"), "op-a", 1, 1)
                    .message().localSeq();
            long withoutId = store.beginOutbound(outbound("out-b"), "op-b", 1, 2)
                    .message().localSeq();

            assertThat(store.finishOutbound(withId, DeliveryStatus.SENT, "m-9", null)).isTrue();
            assertThat(store.finishOutbound(withoutId, DeliveryStatus.SENT, "", null)).isTrue();

            List<Message> history = store.read(SOURCE, 0L, 10);
            Message sentWithId = singleRow(history, withId);
            Message sentWithoutId = singleRow(history, withoutId);

            assertThat(sentWithId.externalMessageId()).isEqualTo("m-9");
            assertThat(sentWithId.delivery())
                    .isEqualTo(new Delivery("op-a", 1, 1, DeliveryStatus.SENT, null));

            assertThat(sentWithoutId.externalMessageId()).isEmpty();
            assertThat(sentWithoutId.delivery())
                    .isEqualTo(new Delivery("op-b", 1, 2, DeliveryStatus.SENT, null));

            // Every SELECT path must map the delivery columns, not just read().
            Message byExternalId = store.findByExternalMessageId(SOURCE, "m-9").orElseThrow();
            assertThat(byExternalId.localSeq()).isEqualTo(withId);
            assertThat(byExternalId.delivery())
                    .isEqualTo(new Delivery("op-a", 1, 1, DeliveryStatus.SENT, null));

            List<Message> searched = store.search("出站", null, null, null, 0L, 10);
            assertThat(singleRow(searched, withId).delivery().status())
                    .isEqualTo(DeliveryStatus.SENT);
        }
    }

    @Test
    void finishOutboundIsAtomicAndOnlyClosesOnePendingRow() {
        Path database = tempDir.resolve("bot.db");

        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            long seq = store.beginOutbound(outbound("out-a"), "op-a", 1, 1)
                    .message().localSeq();

            assertThat(store.finishOutbound(seq, DeliveryStatus.SENT, "m-1", null)).isTrue();
            // Duplicate terminal update: rejected, and the id written first must survive.
            assertThat(store.finishOutbound(seq, DeliveryStatus.SENT, "m-2", null)).isFalse();
            assertThat(store.finishOutbound(9_999L, DeliveryStatus.SENT, "m-3", null)).isFalse();

            long inboundSeq = store.append(inbound("in-1")).message().localSeq();
            assertThat(store.finishOutbound(inboundSeq, DeliveryStatus.SENT, "m-4", null)).isFalse();

            List<Message> history = store.read(SOURCE, 0L, 10);
            Message outbound = singleRow(history, seq);
            assertThat(outbound.externalMessageId()).isEqualTo("m-1");
            assertThat(outbound.delivery())
                    .isEqualTo(new Delivery("op-a", 1, 1, DeliveryStatus.SENT, null));
            assertThat(singleRow(history, inboundSeq).delivery()).isNull();
        }
    }

    @Test
    void finishOutboundDoesNotRewriteLegacyRow() {
        Path database = tempDir.resolve("bot.db");
        long seq;
        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            seq = store.append(outbound("legacy-out")).message().localSeq();
            store.attachExternalMessageId(seq, "777");
        }

        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            assertThat(singleRow(store.read(SOURCE, 0L, 10), seq).delivery().status())
                    .isEqualTo(DeliveryStatus.LEGACY_UNKNOWN);

            assertThat(store.finishOutbound(seq, DeliveryStatus.SENT, "999", null)).isFalse();

            Message history = singleRow(store.read(SOURCE, 0L, 10), seq);
            assertThat(history.externalMessageId()).isEqualTo("777");
            assertThat(history.delivery().status()).isEqualTo(DeliveryStatus.LEGACY_UNKNOWN);
        }
    }

    @Test
    void finishOutboundRejectsInvalidTransitionsAndMetadataWithoutSideEffects() {
        Path database = tempDir.resolve("bot.db");

        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            long seq = store.beginOutbound(outbound("out-a"), "op-a", 1, 1)
                    .message().localSeq();

            assertThatThrownBy(() -> store.finishOutbound(seq, DeliveryStatus.PENDING, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.finishOutbound(seq, DeliveryStatus.LEGACY_UNKNOWN,
                    null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.finishOutbound(seq, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.finishOutbound(seq, DeliveryStatus.UNKNOWN, "m-1", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.finishOutbound(seq, DeliveryStatus.SENT, "m-1",
                    "TIMEOUT")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.finishOutbound(seq, DeliveryStatus.UNKNOWN, null,
                    "java.net.SocketTimeoutException: connect timed out"))
                    .isInstanceOf(IllegalArgumentException.class);

            // None of the rejected calls may have touched the row.
            assertThat(singleRow(store.read(SOURCE, 0L, 10), seq).delivery())
                    .isEqualTo(new Delivery("op-a", 1, 1, DeliveryStatus.PENDING, null));

            assertThat(store.finishOutbound(seq, DeliveryStatus.UNKNOWN, null, "TIMEOUT")).isTrue();
            Message failedAmbiguously = singleRow(store.read(SOURCE, 0L, 10), seq);
            assertThat(failedAmbiguously.delivery())
                    .isEqualTo(new Delivery("op-a", 1, 1, DeliveryStatus.UNKNOWN, "TIMEOUT"));
        }
    }

    @Test
    void beginOutboundRejectsInvalidMetadata() {
        Path database = tempDir.resolve("bot.db");

        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            assertThatThrownBy(() -> store.beginOutbound(outbound("out-a"), " ", 1, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.beginOutbound(outbound("out-a"), null, 1, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.beginOutbound(outbound("out-a"), "op", 0, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.beginOutbound(outbound("out-a"), "op", 2, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.beginOutbound(outbound("out-a"), "op", 1, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.beginOutbound(inbound("in-a"), "op", 1, 1))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(store.read(SOURCE, 0L, 10)).isEmpty();
        }
    }

    @Test
    void recoverPendingOutboundConvergesOnlyPendingRows() {
        Path database = tempDir.resolve("bot.db");

        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            long pending = store.beginOutbound(outbound("out-p"), "op-p", 1, 2)
                    .message().localSeq();
            long sent = store.beginOutbound(outbound("out-s"), "op-s", 1, 1)
                    .message().localSeq();
            store.finishOutbound(sent, DeliveryStatus.SENT, "m-9", null);
            long unknown = store.beginOutbound(outbound("out-u"), "op-u", 2, 2)
                    .message().localSeq();
            store.finishOutbound(unknown, DeliveryStatus.UNKNOWN, null, "TIMEOUT");
            long inboundSeq = store.append(inbound("in-1")).message().localSeq();

            assertThat(store.recoverPendingOutbound()).isEqualTo(1);
            assertThat(store.recoverPendingOutbound()).isZero();

            List<Message> history = store.read(SOURCE, 0L, 10);
            assertThat(singleRow(history, pending).delivery()).isEqualTo(new Delivery("op-p", 1, 2,
                    DeliveryStatus.UNKNOWN, "PROCESS_INTERRUPTED"));
            assertThat(singleRow(history, sent).delivery())
                    .isEqualTo(new Delivery("op-s", 1, 1, DeliveryStatus.SENT, null));
            assertThat(singleRow(history, unknown).delivery())
                    .isEqualTo(new Delivery("op-u", 2, 2, DeliveryStatus.UNKNOWN, "TIMEOUT"));
            assertThat(singleRow(history, inboundSeq).delivery()).isNull();
        }
    }

    @Test
    void openingTheStoreDoesNotRecoverPendingRows() {
        Path database = tempDir.resolve("bot.db");
        long seq;
        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            seq = store.beginOutbound(outbound("out-a"), "op-a", 2, 3).message().localSeq();
        }

        try (SqliteMessageBoardStore reopened = new SqliteMessageBoardStore(database)) {
            // Reopening must keep the whole PENDING record, including operation and part metadata.
            assertThat(singleRow(reopened.read(SOURCE, 0L, 10), seq).delivery())
                    .isEqualTo(new Delivery("op-a", 2, 3, DeliveryStatus.PENDING, null));
            assertThat(reopened.recoverPendingOutbound()).isEqualTo(1);
            assertThat(singleRow(reopened.read(SOURCE, 0L, 10), seq).delivery())
                    .isEqualTo(new Delivery("op-a", 2, 3, DeliveryStatus.UNKNOWN,
                            "PROCESS_INTERRUPTED"));
        }
    }

    @Test
    void legacyAppendStillWorksButCarriesNoDeliveryState() {
        Path database = tempDir.resolve("bot.db");

        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            AppendResult appended = store.append(outbound("out-legacy"));

            assertThat(appended.created()).isTrue();
            assertThat(appended.message().delivery()).isNull();
            assertThat(singleRow(store.read(SOURCE, 0L, 10), appended.message().localSeq())
                    .delivery()).isNull();
        }
    }

    @Test
    void blankStoredDeliveryStatusFailsClosedInsteadOfHidingCorruption() throws Exception {
        Path database = tempDir.resolve("bot.db");
        long seq;
        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            seq = store.beginOutbound(outbound("out-a"), "op-a", 1, 1).message().localSeq();
        }
        // Real corruption in the database: an outbound row whose status is an empty string. A SQL
        // NULL is the supported legacy-append intermediate state and must keep reading as null;
        // an empty/invalid value is corrupt and must not be silently treated as "no delivery".
        executeSql(database, "UPDATE chat_message SET delivery_status = '' WHERE local_seq = " + seq);

        try (SqliteMessageBoardStore reopened = new SqliteMessageBoardStore(database)) {
            assertThatThrownBy(() -> reopened.read(SOURCE, 0L, 10))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("seq=" + seq);
        }
    }

    @Test
    void failedSqliteUpdateLeavesStatusAndExternalIdUnchanged() throws Exception {
        Path database = tempDir.resolve("bot.db");
        long seq;
        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            seq = store.beginOutbound(outbound("out-a"), "op-a", 1, 1).message().localSeq();
            // A pre-existing platform id that a partial/failed terminal write must not lose.
            store.attachExternalMessageId(seq, "700");
        }
        // Real SQLite write failure: a trigger aborts the terminal UPDATE, not a closed store.
        executeSql(database, """
                CREATE TRIGGER fail_terminal_update
                BEFORE UPDATE OF delivery_status ON chat_message
                WHEN NEW.delivery_status = 'SENT'
                BEGIN
                  SELECT RAISE(ABORT, 'simulated terminal write failure');
                END
                """);

        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            assertThatThrownBy(() -> store.finishOutbound(seq, DeliveryStatus.SENT, "999", null))
                    .isInstanceOf(IllegalStateException.class);

            // Status and external id are written by one statement: neither may change on failure.
            Message row = singleRow(store.read(SOURCE, 0L, 10), seq);
            assertThat(row.delivery())
                    .isEqualTo(new Delivery("op-a", 1, 1, DeliveryStatus.PENDING, null));
            assertThat(row.externalMessageId()).isEqualTo("700");
        }
    }

    private static void executeSql(Path database, String sql) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static InboundMessage outbound(String messageId) {
        return new InboundMessage(SOURCE, messageId, Instant.parse("2026-11-15T09:00:00Z"),
                List.of(new Segment("text", "出站正文")), Direction.OUTBOUND, "bot-1", "机器人");
    }

    private static InboundMessage inbound(String messageId) {
        return new InboundMessage(SOURCE, messageId, Instant.parse("2026-11-15T09:00:00Z"),
                List.of(new Segment("text", "入站正文")), Direction.INBOUND, "123", "张三");
    }

    private static Message singleRow(List<Message> history, long localSeq) {
        return history.stream()
                .filter(message -> message.localSeq() == localSeq)
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 localSeq=" + localSeq + " 的记录"));
    }
}
