package io.mosire.bma3.core.store;

import static org.assertj.core.api.Assertions.assertThat;

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

class MessageBoardInboundLookupTest {

    @TempDir
    Path tempDir;

    @Test
    void exactInboundLookupIgnoresDirectionAndMaxSeqIsPerSource() {
        SourceAddress source = SourceAddress.parse("qq_group/bot-1/955432692");
        SourceAddress other = SourceAddress.parse("qq_official_private/app-1/user-openid-1");
        InboundMessage first = inbound(source, "message-1", "first");
        InboundMessage second = inbound(source, "message-2", "second");
        InboundMessage otherSource = inbound(other, "message-1", "other");

        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(
                tempDir.resolve("bot.db"))) {
            AppendResult firstWrite = store.append(first);
            AppendResult secondWrite = store.append(second);
            AppendResult otherWrite = store.append(otherSource);

            assertThat(store.findInbound(source, "message-1"))
                    .get()
                    .extracting(Message::localSeq)
                    .isEqualTo(firstWrite.message().localSeq());
            assertThat(store.findInbound(source, "message-2"))
                    .get()
                    .extracting(Message::localSeq)
                    .isEqualTo(secondWrite.message().localSeq());
            assertThat(store.findInbound(source, "missing")).isEmpty();
            assertThat(store.findInbound(source, " ")).isEmpty();
            assertThat(store.findInbound(other, "message-1"))
                    .get()
                    .extracting(Message::localSeq)
                    .isEqualTo(otherWrite.message().localSeq());
            assertThat(store.maxLocalSeq(source))
                    .isEqualTo(secondWrite.message().localSeq());
            assertThat(store.maxLocalSeq(other))
                    .isEqualTo(otherWrite.message().localSeq());
            assertThat(store.maxLocalSeq(SourceAddress.parse("qq_group/bot-1/1")))
                    .isZero();
        }
    }

    @Test
    void maxSeqAndFindInboundSurviveRestart() {
        SourceAddress source = SourceAddress.parse("qq_private/bot-1/123");
        Path database = tempDir.resolve("bot.db");
        long seq;
        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            seq = store.append(inbound(source, "m-1", "hello")).message().localSeq();
        }
        try (SqliteMessageBoardStore reopened = new SqliteMessageBoardStore(database)) {
            assertThat(reopened.maxLocalSeq(source)).isEqualTo(seq);
            assertThat(reopened.findInbound(source, "m-1"))
                    .get()
                    .satisfies(message -> {
                        assertThat(message.localSeq()).isEqualTo(seq);
                        assertThat(message.inbound().segments())
                                .containsExactly(new Segment("text", "hello"));
                    });
        }
    }

    private static InboundMessage inbound(SourceAddress source, String messageId, String text) {
        return new InboundMessage(source, messageId, Instant.parse("2026-11-17T00:00:00Z"),
                List.of(new Segment("text", text)), Direction.INBOUND, "sender", "Sender");
    }
}
