package io.mosire.bma3.core.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.bma3.core.account.ConsumerId;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMessageBoardStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void appendsDeduplicatesReadsAndPersists() {
        Path database = tempDir.resolve("data/bot.db");
        SourceAddress source = SourceAddress.parse("qq_private/bot-1/123");
        InboundMessage inbound = new InboundMessage(
                source,
                "message-1",
                Instant.parse("2026-11-09T11:00:00Z"),
                List.of(new Segment("text", "hello")),
                Direction.INBOUND);
        ConsumerId consumer = new ConsumerId("agent");

        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            AppendResult first = store.append(inbound);
            assertThat(first.created()).isTrue();
            assertThat(first.message().localSeq()).isEqualTo(1L);
            assertThat(first.message().inbound().source()).isEqualTo(source);

            AppendResult duplicate = store.append(inbound);
            assertThat(duplicate.created()).isFalse();
            assertThat(duplicate.message().localSeq()).isEqualTo(1L);

            List<Message> messages = store.read(source, 0L, 10);
            assertThat(messages).hasSize(1);
            assertThat(messages.getFirst().inbound().segments())
                    .containsExactly(new Segment("text", "hello"));

            assertThat(store.readSeq(consumer, source)).isZero();
            store.ack(consumer, source, 1L);
            store.ack(consumer, source, 1L);
            assertThat(store.readSeq(consumer, source)).isEqualTo(1L);

            store.ack(consumer, source, 0L);
            assertThat(store.readSeq(consumer, source)).isEqualTo(1L);
        }

        try (SqliteMessageBoardStore reopened = new SqliteMessageBoardStore(database)) {
            assertThat(reopened.read(source, 0L, 10)).hasSize(1);
            assertThat(reopened.readSeq(consumer, source)).isEqualTo(1L);
        }
    }
}
