package io.github.c1589190.bma3.core.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.c1589190.bma3.core.message.DeliveryStatus;
import io.github.c1589190.bma3.core.message.Direction;
import io.github.c1589190.bma3.core.message.InboundMessage;
import io.github.c1589190.bma3.core.message.Message;
import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.source.SourceAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageBoardInboundPresenceTest {

    private static final String BODY_SENTINEL = "body-secret-never-log-this";
    private static final String CREDENTIAL_SENTINEL = "credential-secret-never-log-this";
    private static final Instant AT = Instant.parse("2026-11-17T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void onlyInboundRowsInTheExactSourceCountAsObserved() {
        SourceAddress sourceWithInbound = SourceAddress.parse("qq_group/bot-1/955432692");
        SourceAddress outboundOnly = SourceAddress.parse("qq_group/bot-1/outbound-only");
        SourceAddress otherInbound = SourceAddress.parse("qq_official_private/app-1/user-1");

        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(
                tempDir.resolve("bot.db"))) {
            AppendResult pending = store.beginOutbound(
                    outbound(outboundOnly, "out-pending", BODY_SENTINEL, CREDENTIAL_SENTINEL),
                    "op-pending", 1, 1);
            assertThat(pending.created()).isTrue();

            AppendResult sent = store.beginOutbound(
                    outbound(outboundOnly, "out-sent", BODY_SENTINEL, CREDENTIAL_SENTINEL),
                    "op-sent", 1, 1);
            assertThat(store.finishOutbound(
                    sent.message().localSeq(), DeliveryStatus.SENT, "external-1", null)).isTrue();

            AppendResult failed = store.beginOutbound(
                    outbound(outboundOnly, "out-failed", BODY_SENTINEL, CREDENTIAL_SENTINEL),
                    "op-failed", 1, 1);
            assertThat(store.finishOutbound(
                    failed.message().localSeq(), DeliveryStatus.FAILED_BEFORE_SEND,
                    null, "PROVIDER_ERROR")).isTrue();

            AppendResult unknown = store.beginOutbound(
                    outbound(outboundOnly, "out-unknown", BODY_SENTINEL, CREDENTIAL_SENTINEL),
                    "op-unknown", 1, 1);
            assertThat(store.finishOutbound(
                    unknown.message().localSeq(), DeliveryStatus.UNKNOWN, null, "TIMEOUT"))
                    .isTrue();

            // Same source also has an outbound row, but only the inbound append may flip the bit.
            store.beginOutbound(
                    outbound(sourceWithInbound, "out-in-mixed-source",
                            BODY_SENTINEL, CREDENTIAL_SENTINEL),
                    "op-mixed", 1, 1);
            assertThat(store.hasInbound(outboundOnly)).isFalse();
            assertThat(store.hasInbound(sourceWithInbound)).isFalse();
            assertThat(store.hasInbound(otherInbound)).isFalse();
            assertThat(store.hasInbound(SourceAddress.parse("qq_group/bot-1/missing"))).isFalse();

            store.append(inbound(sourceWithInbound, "in-1", BODY_SENTINEL));
            store.append(inbound(otherInbound, "in-other", BODY_SENTINEL));

            assertThat(store.hasInbound(sourceWithInbound)).isTrue();
            assertThat(store.hasInbound(otherInbound)).isTrue();
            assertThat(store.hasInbound(outboundOnly)).isFalse();
        }
    }

    @Test
    void inboundPresenceAndOutboundOnlyIsolationSurviveRestart() {
        Path database = tempDir.resolve("bot.db");
        SourceAddress source = SourceAddress.parse("qq_private/bot-1/123");
        SourceAddress outboundOnly = SourceAddress.parse("qq_group/bot-1/456");

        try (SqliteMessageBoardStore store = new SqliteMessageBoardStore(database)) {
            store.append(inbound(source, "in-1", BODY_SENTINEL));
            store.beginOutbound(
                    outbound(outboundOnly, "out-1", BODY_SENTINEL, CREDENTIAL_SENTINEL),
                    "op-1", 1, 1);
            assertThat(store.hasInbound(source)).isTrue();
            assertThat(store.hasInbound(outboundOnly)).isFalse();
        }

        try (SqliteMessageBoardStore reopened = new SqliteMessageBoardStore(database)) {
            assertThat(reopened.hasInbound(source)).isTrue();
            assertThat(reopened.hasInbound(outboundOnly)).isFalse();
            assertThat(reopened.findInbound(source, "in-1")).isPresent();
            assertThat(reopened.maxLocalSeq(source)).isPositive();
        }
    }

    @Test
    void defaultPortIsFailClosedEvenWhenMaxSeqWouldBeNonZero() {
        SourceAddress source = SourceAddress.parse("qq_group/bot-1/955432692");
        MessageBoardStore unsupported = new UnsupportedStore();
        assertThat(unsupported.maxLocalSeq(source)).isEqualTo(999L);
        assertThat(unsupported.hasInbound(source)).isFalse();
    }

    private static InboundMessage inbound(SourceAddress source, String messageId, String text) {
        return new InboundMessage(
                source, messageId, AT, List.of(new Segment("text", text)),
                Direction.INBOUND, CREDENTIAL_SENTINEL, BODY_SENTINEL);
    }

    private static InboundMessage outbound(
            SourceAddress source, String messageId, String text, String sender) {
        return new InboundMessage(
                source, messageId, AT, List.of(new Segment("text", text)),
                Direction.OUTBOUND, sender, sender);
    }

    /**
     * Minimal non-SQLite port. maxLocalSeq deliberately returns a nonzero value to prove the
     * default hasInbound implementation never falls back to it.
     */
    private static final class UnsupportedStore implements MessageBoardStore {
        @Override
        public AppendResult append(InboundMessage inbound) {
            throw unsupported();
        }

        @Override
        public AppendResult beginOutbound(
                InboundMessage message, String operationId, int partIndex, int partTotal) {
            throw unsupported();
        }

        @Override
        public boolean finishOutbound(
                long localSeq, DeliveryStatus status, String externalMessageId, String errorCode) {
            throw unsupported();
        }

        @Override
        public int recoverPendingOutbound() {
            throw unsupported();
        }

        @Override
        public List<Message> read(SourceAddress source, long afterSeq, int limit) {
            throw unsupported();
        }

        @Override
        public List<ThreadSummary> threads(int limit) {
            throw unsupported();
        }

        @Override
        public void attachExternalMessageId(long localSeq, String externalMessageId) {
            throw unsupported();
        }

        @Override
        public Optional<Message> findByExternalMessageId(
                SourceAddress source, String externalMessageId) {
            throw unsupported();
        }

        @Override
        public long maxLocalSeq(SourceAddress source) {
            return 999L;
        }

        @Override
        public List<Message> search(String query, String sourcePrefix, Instant from, Instant to,
                                    long beforeSeq, int limit) {
            throw unsupported();
        }

        @Override
        public boolean ping() {
            return false;
        }

        @Override
        public void close() {
            // nothing to close
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("unsupported test port");
        }
    }
}
