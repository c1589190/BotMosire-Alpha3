package io.github.c1589190.bma3.app.channel.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.matching.CrossChannelMatcher;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ActiveBindingRef;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.Channel;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ChannelAvailability;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.CorrelationState;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.IdentityStatus;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.IngestIntent;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.MatchingMode;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.Observation;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.RouteState;
import io.github.c1589190.bma3.app.channel.tools.ChannelMutationGate;
import io.github.c1589190.bma3.core.message.Direction;
import io.github.c1589190.bma3.core.message.InboundMessage;
import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.core.source.SourceView;
import io.github.c1589190.bma3.inbox.core.AppendItem;
import io.github.c1589190.bma3.inbox.core.DeliveryPolicy;
import io.github.c1589190.bma3.inbox.core.InboxEntry;
import io.github.c1589190.bma3.inbox.core.InboxItem;
import io.github.c1589190.bma3.inbox.core.InboxPanel;
import io.github.c1589190.bma3.inbox.core.InboxService;
import io.github.c1589190.bma3.inbox.core.SqliteInboxStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QqChannelRouterTest {

    private static final String ONEBOT = "qq_group/bot-1/955432692";
    private static final String OFFICIAL = "qq_official_group/app-1/group-openid-1";
    private static final String THREAD = "thread.1";
    private static final Instant T0 = Instant.parse("2026-11-17T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void droppedMessageWritesNoFactOrIntent() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("drop"))) {
            QqChannelRouteInput normal = input(ONEBOT, "ob-drop", "10001", T0, "drop me",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE);
            QqChannelRouteInput dropped = new QqChannelRouteInput(true, normal.accountId(),
                    normal.source(), normal.messageId(), normal.senderId(), normal.direction(),
                    normal.messageAt(), normal.observedAt(), normal.priority(),
                    normal.timeReliability(), normal.contentIntegrity(),
                    normal.structuredMentionsTrusted(), normal.segments(), normal.boardMessage(),
                    normal.inboxEntry(), normal.rawProjectionMode());
            QqChannelRouteResult result = h.router().ingest(dropped);
            assertThat(result.disposition())
                    .isEqualTo(QqChannelRouteResult.Disposition.DROPPED);
            assertThat(h.board.read(SourceAddress.parse(ONEBOT), 0L, 10)).isEmpty();
            assertThat(h.inboxStore.items()).isEmpty();
            assertThat(h.ledger.findIngestIntentByMessage(ONEBOT, "ob-drop")).isEmpty();
        }
    }

    @Test
    void twoChannelsProduceTwoBoardRowsOneCanonicalItemAndOneWake() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("two"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));

            QqChannelRouteResult first = h.router().ingest(
                    input(ONEBOT, "ob-1", "10001", T0, "hello",
                            QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE));
            assertThat(first.disposition())
                    .isEqualTo(QqChannelRouteResult.Disposition.BOUND_DISPLAY);
            InboxPanel pushed = h.inbox.awaitPush(0L, Duration.ofSeconds(2));
            assertThat(pushed).isNotNull();
            long pushBeforeEvidence = pushed.pushRevision();

            QqChannelRouteResult second = h.router().ingest(
                    input(OFFICIAL, "of-1", "openid-1", T0.plusMillis(500), "hello",
                            QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE));
            assertThat(second.disposition())
                    .isEqualTo(QqChannelRouteResult.Disposition.BOUND_EVIDENCE);

            assertThat(h.board.read(SourceAddress.parse(ONEBOT), 0L, 10)).hasSize(1);
            assertThat(h.board.read(SourceAddress.parse(OFFICIAL), 0L, 10)).hasSize(1);
            assertThat(h.inboxStore.items()).hasSize(1);
            assertThat(h.inboxStore.items().getFirst().displayChannel()).isEqualTo("onebot");
            assertThat(h.inboxStore.items().getFirst().entries())
                    .extracting(InboxEntry::text).containsExactly("hello");
            long onebotBoardSeq = h.board.findInbound(
                    SourceAddress.parse(ONEBOT), "ob-1").orElseThrow().localSeq();
            assertThat(h.inboxStore.items().getFirst().entries().getFirst().localSeq())
                    .isEqualTo(onebotBoardSeq);
            assertThat(h.inbox.pushRevision()).isEqualTo(pushBeforeEvidence);
            assertThat(h.inbox.awaitPush(pushBeforeEvidence, Duration.ofMillis(120))).isNull();

            Observation official = h.ledger.findObservation(OFFICIAL, "of-1").orElseThrow();
            Observation onebot = h.ledger.findObservation(ONEBOT, "ob-1").orElseThrow();
            assertThat(official.correlationState()).isEqualTo(CorrelationState.MATCHED);
            assertThat(onebot.correlationState()).isEqualTo(CorrelationState.MATCHED);
            assertThat(official.itemId()).contains(h.inboxStore.items().getFirst().id());
        }
    }

    @Test
    void nonPreferredFirstObservationDefersWithoutItemOrWake() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("preferred"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));

            QqChannelRouteResult first = h.router().ingest(
                    input(OFFICIAL, "of-1", "openid-1", T0, "hello",
                            QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE));
            assertThat(first.disposition())
                    .isEqualTo(QqChannelRouteResult.Disposition.BOUND_EVIDENCE);
            assertThat(h.inboxStore.items()).isEmpty();
            assertThat(h.inbox.awaitPush(0L, Duration.ofMillis(120))).isNull();
            assertThat(h.ledger.findObservation(OFFICIAL, "of-1").orElseThrow().routeState())
                    .isEqualTo(RouteState.EVIDENCE_ONLY);

            QqChannelRouteResult second = h.router().ingest(
                    input(ONEBOT, "ob-1", "10001", T0.plusMillis(500), "hello",
                            QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE));
            assertThat(second.disposition())
                    .isEqualTo(QqChannelRouteResult.Disposition.BOUND_DISPLAY);
            assertThat(h.inboxStore.items()).hasSize(1);
            assertThat(h.inboxStore.items().getFirst().displayChannel()).isEqualTo("onebot");
            assertThat(h.inboxStore.items().getFirst().entries()).hasSize(1);
            assertThat(h.ledger.findObservation(OFFICIAL, "of-1").orElseThrow().itemId())
                    .contains(h.inboxStore.items().getFirst().id());
            assertThat(h.inbox.awaitPush(0L, Duration.ofSeconds(2))).isNotNull();
            assertThat(h.inbox.awaitPush(h.inbox.pushRevision(), Duration.ofMillis(120))).isNull();
        }
    }

    @Test
    void gateOffWritesBoardAndObservationButNoInbox() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("gate"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1", false));

            QqChannelRouteResult result = h.router().ingest(
                    input(ONEBOT, "ob-gate", "10001", T0, "paused",
                            QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE));
            assertThat(result.disposition())
                    .isEqualTo(QqChannelRouteResult.Disposition.BOUND_GATE_OFF);
            assertThat(h.board.read(SourceAddress.parse(ONEBOT), 0L, 10)).hasSize(1);
            assertThat(h.ledger.findObservation(ONEBOT, "ob-gate").orElseThrow().routeState())
                    .isEqualTo(RouteState.GATE_OFF);
            assertThat(h.inboxStore.items()).isEmpty();
            assertThat(h.inbox.awaitPush(0L, Duration.ofMillis(120))).isNull();
        }
    }

    @Test
    void fallbackLateCopyIsSuppressedButLaterNoCandidateCreatesNewItem() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("fallback"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.OFF, ""));

            h.router().ingest(input(ONEBOT, "ob-old", "10001", T0, "same text",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE));
            InboxPanel firstPush = h.inbox.awaitPush(0L, Duration.ofSeconds(2));
            assertThat(firstPush).isNotNull();
            long pushAfterOld = firstPush.pushRevision();
            String oldItemId = h.inboxStore.items().getFirst().id();

            h.control.put(officialSnapshot(h, ChannelAvailability.UNAVAILABLE,
                    Channel.QQ_OFFICIAL, MatchingMode.OFF, ""));
            QqChannelRouteResult late = h.router().ingest(
                    input(OFFICIAL, "of-late", "openid-1", T0.plusSeconds(1), "same text",
                            QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE));
            assertThat(late.disposition())
                    .isEqualTo(QqChannelRouteResult.Disposition.BOUND_EVIDENCE);
            assertThat(h.inboxStore.items()).hasSize(1);
            assertThat(h.inbox.pushRevision()).isEqualTo(pushAfterOld);
            Observation suppressed = h.ledger.findObservation(OFFICIAL, "of-late").orElseThrow();
            assertThat(suppressed.routeState()).isEqualTo(RouteState.EVIDENCE_ONLY);
            assertThat(suppressed.itemId()).contains(oldItemId);
            assertThat(suppressed.correlationState())
                    .isEqualTo(CorrelationState.UNVERIFIED);
            assertThat(suppressed.routeReason())
                    .isEqualTo(io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore
                            .RouteReason.SUPPRESSED_FALLBACK_UNVERIFIED);
            assertThat(h.ledger.evidenceCandidatesForSource(OFFICIAL, "of-late"))
                    .singleElement()
                    .satisfies(candidate -> assertThat(candidate.candidateItemId())
                            .contains(oldItemId));

            QqChannelRouteResult fresh = h.router().ingest(
                    input(OFFICIAL, "of-new", "openid-1", T0.plusSeconds(20), "brand new",
                            QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE));
            assertThat(fresh.disposition())
                    .isEqualTo(QqChannelRouteResult.Disposition.BOUND_DISPLAY);
            assertThat(h.inboxStore.items()).hasSize(2);
            assertThat(h.inbox.awaitPush(pushAfterOld, Duration.ofSeconds(2))).isNotNull();
        }
    }

    @Test
    void fallbackDoesNotSuppressSameTextFromDifferentMappedSenders() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("fallback-distinct-senders"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "alice"));
            h.router().ingest(input(ONEBOT, "ob-alice", "10001", T0, "same short text",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE));
            assertThat(h.inbox.awaitPush(0L, Duration.ofSeconds(2))).isNotNull();

            h.control.put(officialSnapshot(h, ChannelAvailability.UNAVAILABLE,
                    Channel.QQ_OFFICIAL, MatchingMode.STRICT, "bob"));
            QqChannelRouteResult next = h.router().ingest(input(OFFICIAL, "of-bob", "openid-2",
                    T0.plusSeconds(1), "same short text",
                    QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE));

            assertThat(next.disposition())
                    .isEqualTo(QqChannelRouteResult.Disposition.BOUND_DISPLAY);
            assertThat(h.inboxStore.items()).hasSize(2);
            assertThat(h.ledger.evidenceCandidatesForSource(OFFICIAL, "of-bob")).isEmpty();
        }
    }

    @Test
    void preferredBodyStillWritesWhenEarlierNonPreferredOnlyAttachedEvidence() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("peer-evidence-open"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));

            // An older onebot message keeps the canonical item open.
            h.router().ingest(input(ONEBOT, "ob-old", "10001", T0.minusSeconds(20), "older",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE));
            assertThat(h.inboxStore.items()).hasSize(1);
            String itemId = h.inboxStore.items().getFirst().id();

            // The non-preferred copy arrives first and is attached as evidence to the open item.
            h.router().ingest(input(OFFICIAL, "of-new", "openid-1", T0.plusSeconds(2), "new body",
                    QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE));
            Observation official = h.ledger.findObservation(OFFICIAL, "of-new").orElseThrow();
            assertThat(official.routeState()).isEqualTo(RouteState.EVIDENCE_ONLY);
            assertThat(official.itemId()).contains(itemId);

            // The preferred copy of the same new message must still append the body. The earlier
            // evidence-only peer must not be mistaken for an already displayed body.
            h.router().ingest(input(ONEBOT, "ob-new", "10001", T0.plusSeconds(2).plusMillis(100),
                    "new body", QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE));
            InboxItem item = h.inboxStore.item(itemId).orElseThrow();
            assertThat(item.entries()).extracting(InboxEntry::text)
                    .containsExactly("older", "new body");
            Observation onebot = h.ledger.findObservation(ONEBOT, "ob-new").orElseThrow();
            assertThat(onebot.routeState()).isEqualTo(RouteState.DISPLAY_LINKED);
            assertThat(onebot.correlationState()).isEqualTo(CorrelationState.MATCHED);
        }
    }

    @Test
    void evidenceWriteFailureIsNotSwallowedWhileTargetStillExists() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("evidence-failure"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.router().ingest(input(ONEBOT, "ob-1", "10001", T0, "hello",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE));
            String itemId = h.inboxStore.items().getFirst().id();

            QqChannelInboxPort delegate = QqChannelInboxPort.from(h.inbox, h.inboxStore);
            QqChannelInboxPort failing = new QqChannelInboxPort() {
                @Override
                public InboxItem appendProjectedOnce(String requestKey, AppendItem item) {
                    return delegate.appendProjectedOnce(requestKey, item);
                }

                @Override
                public InboxItem appendOrMerge(AppendItem item) {
                    return delegate.appendOrMerge(item);
                }

                @Override
                public InboxItem appendEvidenceOnly(
                        io.github.c1589190.bma3.inbox.core.InboxEvidenceUpdate update) {
                    throw new IllegalArgumentException("SOURCE_MISMATCH");
                }

                @Override
                public Optional<InboxItem> item(String id) {
                    return delegate.item(id);
                }

                @Override
                public Optional<InboxItem> activeForSource(String source) {
                    return delegate.activeForSource(source);
                }

                @Override
                public void suppressRawOnce(String requestKey, String source, String channel) {
                    delegate.suppressRawOnce(requestKey, source, channel);
                }

                @Override
                public boolean isRawSuppressed(String requestKey, String source, String channel) {
                    return delegate.isRawSuppressed(requestKey, source, channel);
                }
            };
            assertThatThrownBy(() -> h.router(failing, QqChannelRouter.CrashHook.noop()).ingest(
                    input(OFFICIAL, "of-1", "openid-1", T0.plusMillis(100), "hello",
                            QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SOURCE_MISMATCH");
            assertThat(h.inboxStore.item(itemId)).isPresent();
        }
    }

    @Test
    void emptyTargetDeferredObservationNeverAttachesToALaterUnrelatedItem() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("empty-target"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            QqChannelRouteInput deferred = input(OFFICIAL, "of-deferred", "openid-1",
                    T0, "official only",
                    QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE);
            h.router().ingest(deferred);
            assertThat(h.ledger.findObservation(OFFICIAL, "of-deferred")
                    .orElseThrow().itemId()).isEmpty();

            h.router().ingest(input(ONEBOT, "ob-later", "10001", T0.plusSeconds(20),
                    "unrelated later", QqChannelRouteInput.RawProjectionMode
                            .LEGACY_APPEND_OR_MERGE));
            assertThat(h.inboxStore.items()).hasSize(1);
            String itemId = h.inboxStore.items().getFirst().id();

            h.router().ingest(deferred);
            Observation deferredAfter = h.ledger.findObservation(
                    OFFICIAL, "of-deferred").orElseThrow();
            assertThat(deferredAfter.routeState()).isEqualTo(RouteState.EVIDENCE_ONLY);
            assertThat(deferredAfter.itemId()).isEmpty();
            InboxItem item = h.inboxStore.item(itemId).orElseThrow();
            assertThat(item.entries()).extracting(InboxEntry::text)
                    .containsExactly("unrelated later");
        }
    }

    @Test
    void evidenceOutboxRecoversAfterCrashBeforeInboxEvidenceWrite() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("evidence-outbox"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.router().ingest(input(ONEBOT, "ob-1", "10001", T0, "hello",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE));
            String itemId = h.inboxStore.items().getFirst().id();
            long pushRevision = h.inbox.pushRevision();

            QqChannelRouter crashing = h.router(cp -> {
                if (cp == QqChannelRouter.CrashPoint.AFTER_EVIDENCE_ROUTE) {
                    throw new SimulatedCrash();
                }
            });
            assertThatThrownBy(() -> crashing.ingest(input(OFFICIAL, "of-1", "openid-1",
                    T0.plusMillis(100), "hello",
                    QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE)))
                    .isInstanceOf(SimulatedCrash.class);
            assertThat(h.ledger.evidenceOutboxEntry(OFFICIAL, "of-1"))
                    .get()
                    .extracting(io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore
                            .EvidenceOutboxEntry::state)
                    .isEqualTo(io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore
                            .EvidenceOutboxState.PENDING);

            h.router().recover();
            assertThat(h.inboxStore.items()).hasSize(1);
            assertThat(h.inbox.pushRevision()).isEqualTo(pushRevision);
            assertThat(h.ledger.evidenceOutboxEntry(OFFICIAL, "of-1"))
                    .get()
                    .extracting(io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore
                            .EvidenceOutboxEntry::state)
                    .isEqualTo(io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore
                            .EvidenceOutboxState.APPLIED);
            assertThat(h.inboxStore.item(itemId).orElseThrow().channelSummary().toString())
                    .contains(h.ledger.findObservation(OFFICIAL, "of-1")
                            .orElseThrow().observationId());
        }
    }

    @Test
    void deletedEvidenceTargetIsMarkedMissingWithoutCreatingNewItem() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("evidence-target-missing"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.router().ingest(input(ONEBOT, "ob-1", "10001", T0, "hello",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE));
            String itemId = h.inboxStore.items().getFirst().id();
            h.inboxStore.delete(List.of(itemId));

            h.router().ingest(input(OFFICIAL, "of-1", "openid-1", T0.plusMillis(100),
                    "hello", QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE));
            assertThat(h.inboxStore.items()).isEmpty();
            assertThat(h.ledger.evidenceOutboxEntry(OFFICIAL, "of-1"))
                    .get()
                    .extracting(io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore
                            .EvidenceOutboxEntry::state)
                    .isEqualTo(io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore
                            .EvidenceOutboxState.TARGET_MISSING);
        }
    }

    @Test
    void untrustedDifferentAtMentionsNeverMatchButTrustedAtBotCan() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("mentions"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.router().ingest(input(ONEBOT, "ob-at", "10001", T0, "hello",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE,
                    List.of(new Segment("text", "hello"), new Segment("at", "@Alice")), false));
            h.router().ingest(input(OFFICIAL, "of-at", "openid-1", T0.plusMillis(100),
                    "hello", QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE,
                    List.of(new Segment("text", "hello"), new Segment("at", "@Bob")), false));
            assertThat(h.ledger.findCorrelationPair(OFFICIAL, "of-at")).isEmpty();
            assertThat(h.ledger.findObservation(OFFICIAL, "of-at").orElseThrow().correlationState())
                    .isNotEqualTo(CorrelationState.MATCHED);
        }

        try (Harness h = new Harness(tempDir.resolve("mentions-trusted"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.router().ingest(input(ONEBOT, "ob-bot-at", "10001", T0, "hello",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE,
                    List.of(new Segment("at", "@Bot"), new Segment("text", "hello")), true));
            h.router().ingest(input(OFFICIAL, "of-bot-at", "openid-1", T0.plusMillis(100),
                    "hello", QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE,
                    List.of(new Segment("at", "@Bot"), new Segment("text", "hello")), true));
            assertThat(h.ledger.findObservation(OFFICIAL, "of-bot-at")
                    .orElseThrow().correlationState()).isEqualTo(CorrelationState.MATCHED);
        }
    }

    @Test
    void trustedMentionSnapshotSurvivesCrashAndRecovery() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("mentions-restart"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            QqChannelRouter crashing = h.router(cp -> {
                if (cp == QqChannelRouter.CrashPoint.AFTER_BOARD) {
                    throw new SimulatedCrash();
                }
            });
            QqChannelRouteInput trusted = input(ONEBOT, "ob-trusted", "10001", T0, "hello",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE,
                    List.of(new Segment("at", "@Bot"), new Segment("text", "hello")), true);
            assertThatThrownBy(() -> crashing.ingest(trusted)).isInstanceOf(SimulatedCrash.class);
            assertThat(h.ledger.findIngestIntentByMessage(ONEBOT, "ob-trusted")
                    .orElseThrow().structuredMentionsTrusted()).isTrue();
            h.router().recover();
            assertThat(h.ledger.findObservation(ONEBOT, "ob-trusted")
                    .orElseThrow().structuredMentionsTrusted()).isTrue();
        }
    }

    @Test
    void windowOverflowFailsClosedForCorrelationAndSuppression() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("window-overflow"))) {
            h.bindBoth();
            QqChannelControl.ControlSnapshot onebot = onebotSnapshot(h,
                    ChannelAvailability.AVAILABLE, Channel.ONEBOT, MatchingMode.STRICT,
                    "canonical-1");
            h.control.put(onebot);
            String epoch = onebot.bindingEpochId();
            for (int index = 0; index < 501; index++) {
                h.ledger.observe(new io.github.c1589190.bma3.app.channel.store
                        .QqChannelObservationStore.ObservationDraft(
                        THREAD, Channel.ONEBOT, "bot-1", ONEBOT, "fill-" + index,
                        1_000L + index, "10001", Optional.of("canonical-1"),
                        IdentityStatus.MAPPED, 1L,
                        io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.Direction
                                .INBOUND, T0, T0, 100,
                        io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore
                                .TimeReliability.RELIABLE,
                        io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore
                                .ContentIntegrity.COMPLETE,
                        false, 5, epoch,
                        io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore
                                .RouteReason.PENDING));
            }
            h.router().ingest(input(ONEBOT, "ob-overflow", "10001", T0, "new",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE));
            assertThat(h.inboxStore.items()).isEmpty();
            assertThat(h.ledger.findObservation(ONEBOT, "ob-overflow")
                    .orElseThrow().routeState()).isEqualTo(RouteState.EVIDENCE_ONLY);

            h.control.put(officialSnapshot(h, ChannelAvailability.UNAVAILABLE,
                    Channel.QQ_OFFICIAL, MatchingMode.OFF, ""));
            h.router().ingest(input(OFFICIAL, "of-overflow", "openid-1", T0, "new",
                    QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE));
            assertThat(h.inboxStore.items()).isEmpty();
            assertThat(h.ledger.findObservation(OFFICIAL, "of-overflow")
                    .orElseThrow().routeState()).isEqualTo(RouteState.EVIDENCE_ONLY);
        }
    }

    @Test
    void suppressionUsesObservedAtUnionWhenMessageTimeIsUnreliable() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("observed-at-union"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.OFF, ""));
            h.router().ingest(input(ONEBOT, "ob-history", "10001", T0, "same",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE));
            InboxPanel pushed = h.inbox.awaitPush(0L, Duration.ofSeconds(2));
            assertThat(pushed).isNotNull();
            long pushRevision = pushed.pushRevision();
            String itemId = h.inboxStore.items().getFirst().id();

            h.control.put(officialSnapshot(h, ChannelAvailability.UNAVAILABLE,
                    Channel.QQ_OFFICIAL, MatchingMode.OFF, ""));
            QqChannelRouteInput late = input(OFFICIAL, "of-late-observed", "openid-1",
                    T0.plusSeconds(3600), "same",
                    QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE);
            late = withObservedAt(late, T0.plusSeconds(1),
                    io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore
                            .TimeReliability.FALLBACK);
            h.router().ingest(late);

            assertThat(h.inboxStore.items()).hasSize(1);
            assertThat(h.inbox.pushRevision()).isEqualTo(pushRevision);
            Observation suppressed = h.ledger.findObservation(
                    OFFICIAL, "of-late-observed").orElseThrow();
            assertThat(suppressed.itemId()).contains(itemId);
            assertThat(h.ledger.evidenceCandidatesForSource(
                    OFFICIAL, "of-late-observed")).hasSize(1);
        }
    }

    @Test
    void peerEvidenceTargetSurvivesCrashAtEveryDisplayLinkPhase() throws Exception {
        for (QqChannelRouter.CrashPoint point : List.of(
                QqChannelRouter.CrashPoint.AFTER_INBOX_BODY,
                QqChannelRouter.CrashPoint.AFTER_DISPLAY_PENDING,
                QqChannelRouter.CrashPoint.AFTER_PEER_EVIDENCE_TARGET,
                QqChannelRouter.CrashPoint.AFTER_DISPLAY_LINKED)) {
            Path dir = tempDir.resolve("peer-crash-" + point.name());
            try (Harness h = new Harness(dir)) {
                h.bindBoth();
                h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                        Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
                h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                        Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
                h.router().ingest(input(OFFICIAL, "of-peer", "openid-1", T0, "hello",
                        QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE));

                QqChannelRouter crashing = h.router(cp -> {
                    if (cp == point) {
                        throw new SimulatedCrash();
                    }
                });
                assertThatThrownBy(() -> crashing.ingest(input(ONEBOT, "ob-peer", "10001",
                        T0.plusMillis(100), "hello",
                        QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE)))
                        .isInstanceOf(SimulatedCrash.class);

                h.router().recover();
                assertThat(h.inboxStore.items()).hasSize(1);
                InboxItem item = h.inboxStore.items().getFirst();
                assertThat(item.entries()).extracting(InboxEntry::text)
                        .containsExactly("hello");
                Observation official = h.ledger.findObservation(
                        OFFICIAL, "of-peer").orElseThrow();
                assertThat(official.itemId()).as(point.name()).contains(item.id());
                assertThat(h.ledger.evidenceOutboxEntry(OFFICIAL, "of-peer"))
                        .get()
                        .extracting(io.github.c1589190.bma3.app.channel.store
                                .QqChannelObservationStore.EvidenceOutboxEntry::state)
                        .isEqualTo(io.github.c1589190.bma3.app.channel.store
                                .QqChannelObservationStore.EvidenceOutboxState.APPLIED);
                assertThat(h.inboxStore.item(item.id()).orElseThrow().channelSummary()
                        .toString()).contains(official.observationId());
                assertThat(h.ledger.findObservation(ONEBOT, "ob-peer")
                        .orElseThrow().routeState()).isEqualTo(RouteState.DISPLAY_LINKED);
            }
        }
    }

    @Test
    void unknownIdentityNeverBecomesMatched() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("unknown-identity"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, ""));
            h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, ""));

            h.router().ingest(input(ONEBOT, "ob-1", "10001", T0, "hello",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE));
            h.router().ingest(input(OFFICIAL, "of-1", "openid-1", T0.plusMillis(300), "hello",
                    QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE));

            Observation official = h.ledger.findObservation(OFFICIAL, "of-1").orElseThrow();
            assertThat(official.correlationState()).isNotEqualTo(CorrelationState.MATCHED);
            assertThat(h.ledger.findCorrelationPair(OFFICIAL, "of-1")).isEmpty();
            assertThat(h.inboxStore.items()).hasSize(1);
        }
    }

    @Test
    void recoveryRechecksCorrelationBeforeProjectingPendingDuplicate() throws Exception {
        for (QqChannelRouter.CrashPoint point : List.of(
                QqChannelRouter.CrashPoint.AFTER_PROMOTION,
                QqChannelRouter.CrashPoint.AFTER_CORRELATION)) {
            try (Harness h = new Harness(tempDir.resolve("recover-duplicate-" + point))) {
                h.bindBoth();
                h.control.put(h.snapshot(OFFICIAL, Channel.QQ_OFFICIAL, "app-1",
                        Channel.QQ_OFFICIAL, ChannelAvailability.AVAILABLE,
                        Channel.QQ_OFFICIAL, MatchingMode.STRICT, "person-1"));
                h.router().ingest(input(OFFICIAL, "of-displayed", "openid-1", T0, "same body",
                        QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE));
                InboxPanel pushed = h.inbox.awaitPush(0L, Duration.ofSeconds(2));
                assertThat(pushed).isNotNull();
                long before = pushed.pushRevision();

                // The preferred channel changed after the earlier item froze. This is not a
                // fallback path, so duplicate protection must come from recovered correlation.
                h.control.put(h.snapshot(ONEBOT, Channel.ONEBOT, "bot-1",
                        Channel.ONEBOT, ChannelAvailability.AVAILABLE,
                        Channel.ONEBOT, MatchingMode.STRICT, "person-1"));
                QqChannelRouter crashing = h.router(at -> {
                    if (at == point) {
                        throw new SimulatedCrash();
                    }
                });
                QqChannelRouteInput duplicate = input(ONEBOT, "ob-duplicate", "10001",
                        T0.plusMillis(500), "same body",
                        QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE);
                assertThatThrownBy(() -> crashing.ingest(duplicate))
                        .isInstanceOf(SimulatedCrash.class);

                h.router().recover();
                assertThat(h.inboxStore.items()).hasSize(1);
                assertThat(h.inbox.pushRevision()).isEqualTo(before);
                Observation recovered = h.ledger.findObservation(ONEBOT, "ob-duplicate")
                        .orElseThrow();
                assertThat(recovered.routeState()).isEqualTo(RouteState.EVIDENCE_ONLY);
                assertThat(recovered.correlationState()).isEqualTo(CorrelationState.MATCHED);
                assertThat(recovered.itemId()).contains(h.inboxStore.items().getFirst().id());
            }
        }
    }

    @Test
    void fallbackTimestampReplayPreservesTheFirstDurableFact() throws Exception {
        for (boolean crashBeforeBoard : List.of(false, true)) {
            try (Harness h = new Harness(tempDir.resolve("fallback-replay-" + crashBeforeBoard))) {
                h.bindBoth();
                h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                        Channel.QQ_OFFICIAL, MatchingMode.STRICT, "person-1"));
                QqChannelRouteInput initial = withObservedAt(input(OFFICIAL, "of-replay",
                        "openid-1", T0, "same body",
                        QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE), T0,
                        QqChannelObservationStore.TimeReliability.FALLBACK);
                if (crashBeforeBoard) {
                    QqChannelRouter crashing = h.router(point -> {
                        if (point == QqChannelRouter.CrashPoint.AFTER_INTENT) {
                            throw new SimulatedCrash();
                        }
                    });
                    assertThatThrownBy(() -> crashing.ingest(initial))
                            .isInstanceOf(SimulatedCrash.class);
                } else {
                    h.router().ingest(initial);
                }
                QqChannelRouteInput replay = withObservedAt(input(OFFICIAL, "of-replay",
                        "openid-1", T0.plusSeconds(30), "same body",
                        QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE), T0.plusSeconds(30),
                        QqChannelObservationStore.TimeReliability.FALLBACK);
                h.router().ingest(replay);

                assertThat(h.board.findInbound(SourceAddress.parse(OFFICIAL), "of-replay"))
                        .get().satisfies(message -> assertThat(message.inbound().at()).isEqualTo(T0));
                assertThat(h.ledger.findObservation(OFFICIAL, "of-replay")).get()
                        .satisfies(observation -> assertThat(observation.messageAt()).isEqualTo(T0));
                assertThat(h.inboxStore.items()).hasSize(1);
                assertThat(h.inboxStore.items().getFirst().entries()).hasSize(1);
            }
        }
    }

    @Test
    void crashAtEveryPersistencePhaseRecoversIdempotently() throws Exception {
        for (QqChannelRouter.CrashPoint point : List.of(
                QqChannelRouter.CrashPoint.AFTER_INTENT,
                QqChannelRouter.CrashPoint.AFTER_BOARD,
                QqChannelRouter.CrashPoint.AFTER_BOARD_COMMITTED,
                QqChannelRouter.CrashPoint.AFTER_PROMOTION,
                QqChannelRouter.CrashPoint.AFTER_CORRELATION,
                QqChannelRouter.CrashPoint.AFTER_INBOX_BODY,
                QqChannelRouter.CrashPoint.AFTER_ROUTE_LINK)) {
            Path dir = tempDir.resolve("crash-" + point.name());
            try (Harness h = new Harness(dir)) {
                h.bindBoth();
                h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                        Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
                QqChannelRouteInput event = input(ONEBOT, "ob-" + point.name(), "10001",
                        T0, "crash body",
                        QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE);
                QqChannelRouter crashing = h.router(cp -> {
                    if (cp == point) {
                        throw new SimulatedCrash();
                    }
                });
                assertThatThrownBy(() -> crashing.ingest(event)).isInstanceOf(SimulatedCrash.class);

                QqChannelRouter recovering = h.router();
                recovering.recover();
                if (point == QqChannelRouter.CrashPoint.AFTER_INTENT) {
                    // A pre-board intent has no fact to recover; the source replay is the only
                    // way to supply the body. The second call must reuse the same snapshot.
                    assertThat(h.board.read(SourceAddress.parse(ONEBOT), 0L, 10)).isEmpty();
                    assertThat(h.ledger.findIngestIntentByMessage(ONEBOT, "ob-" + point.name()))
                            .isPresent();
                    recovering.ingest(event);
                } else {
                    recovering.recover();
                }
                assertThat(h.board.read(SourceAddress.parse(ONEBOT), 0L, 10)).hasSize(1);
                assertThat(h.inboxStore.items()).hasSize(1);
                Observation observation = h.ledger.findObservation(
                        ONEBOT, "ob-" + point.name()).orElseThrow();
                assertThat(observation.routeState()).isEqualTo(RouteState.DISPLAY_LINKED);
                assertThat(h.inboxStore.items().getFirst().entries()).hasSize(1);
            }
        }
    }

    @Test
    void restartAfterInboxCommitRecoversFromReceiptWithoutSecondItem() throws Exception {
        Path dir = tempDir.resolve("restart");
        String messageId = "ob-restart";
        try (Harness first = new Harness(dir)) {
            first.bindBoth();
            first.control.put(onebotSnapshot(first, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            QqChannelRouter crashing = first.router(cp -> {
                if (cp == QqChannelRouter.CrashPoint.AFTER_INBOX_BODY) {
                    throw new SimulatedCrash();
                }
            });
            assertThatThrownBy(() -> crashing.ingest(input(ONEBOT, messageId, "10001", T0,
                    "restart body", QqChannelRouteInput.RawProjectionMode
                            .LEGACY_APPEND_OR_MERGE)))
                    .isInstanceOf(SimulatedCrash.class);
            assertThat(first.inboxStore.items()).hasSize(1);
        }

        try (Harness reopened = new Harness(dir)) {
            reopened.bindBoth();
            reopened.control.put(onebotSnapshot(reopened, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            QqChannelRouter.RecoveryReport report = reopened.router().recover();
            assertThat(report.promoted() + report.routed()).isGreaterThan(0);
            assertThat(reopened.inboxStore.items()).hasSize(1);
            Observation observation = reopened.ledger.findObservation(
                    ONEBOT, messageId).orElseThrow();
            assertThat(observation.routeState()).isEqualTo(RouteState.DISPLAY_LINKED);
            assertThat(observation.itemId()).contains(reopened.inboxStore.items().getFirst().id());
            long boardSeq = reopened.board.findInbound(
                    SourceAddress.parse(ONEBOT), messageId).orElseThrow().localSeq();
            assertThat(reopened.inboxStore.items().getFirst().entries().getFirst().localSeq())
                    .isEqualTo(boardSeq);
        }
    }

    @Test
    void unbindAfterCrashStillRecoversFromPersistedSnapshot() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("recover-unbound"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            QqChannelRouter crashing = h.router(cp -> {
                if (cp == QqChannelRouter.CrashPoint.AFTER_BOARD) {
                    throw new SimulatedCrash();
                }
            });
            QqChannelRouteInput event = input(ONEBOT, "ob-unbound", "10001", T0, "survives",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE);
            assertThatThrownBy(() -> crashing.ingest(event)).isInstanceOf(SimulatedCrash.class);

            h.control.clearBindings();
            QqChannelRouter.RecoveryReport report = h.router().recover();
            assertThat(report.promoted()).isPositive();
            assertThat(h.ledger.findObservation(ONEBOT, "ob-unbound").orElseThrow().threadId())
                    .isEqualTo(THREAD);
            assertThat(h.inboxStore.items()).hasSize(1);
            assertThat(h.inboxStore.items().getFirst().source())
                    .isEqualTo("qq_thread/" + THREAD);
        }
    }

    @Test
    void globalIntentRecoveryPagesPastFiveHundredRows() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("recover-pages"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            QqChannelRouter crashing = h.router(cp -> {
                if (cp == QqChannelRouter.CrashPoint.AFTER_INTENT) {
                    throw new SimulatedCrash();
                }
            });
            for (int index = 0; index < 501; index++) {
                QqChannelRouteInput event = input(ONEBOT, "ob-page-" + index, "10001",
                        T0.plusMillis(index), "page",
                        QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE);
                assertThatThrownBy(() -> crashing.ingest(event))
                        .isInstanceOf(SimulatedCrash.class);
            }
            var firstPage = h.ledger.pendingIngestIntents(0L, 500);
            assertThat(firstPage.items()).hasSize(500);
            assertThat(firstPage.hasMore()).isTrue();
            var secondPage = h.ledger.pendingIngestIntents(
                    firstPage.nextAfterId(), 500);
            assertThat(secondPage.items()).hasSize(1);
            assertThat(secondPage.hasMore()).isFalse();

            QqChannelRouter.RecoveryReport report = h.router().recover();
            assertThat(report.skipped()).isEqualTo(501);
            assertThat(h.inboxStore.items()).isEmpty();
        }
    }

    @Test
    void recoveryUsesPersistedToleranceNotCurrentRouterConfiguration() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("tolerance"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            QqChannelRouter crashing = h.router(Duration.ofSeconds(3), cp -> {
                if (cp == QqChannelRouter.CrashPoint.AFTER_BOARD) {
                    throw new SimulatedCrash();
                }
            });
            assertThatThrownBy(() -> crashing.ingest(input(ONEBOT, "ob-tolerance", "10001",
                    T0, "tolerance", QqChannelRouteInput.RawProjectionMode
                            .LEGACY_APPEND_OR_MERGE)))
                    .isInstanceOf(SimulatedCrash.class);
            assertThat(h.ledger.findIngestIntentByMessage(ONEBOT, "ob-tolerance")
                    .orElseThrow().strictTimeToleranceSeconds()).isEqualTo(3);

            // The restarted router is configured with a different live tolerance. Recovery must
            // use the intent snapshot, not the constructor value.
            QqChannelRouter recovered = h.router(Duration.ofSeconds(300),
                    QqChannelRouter.CrashHook.noop());
            recovered.recover();
            Observation observation = h.ledger.findObservation(
                    ONEBOT, "ob-tolerance").orElseThrow();
            assertThat(observation.strictTimeToleranceSeconds()).isEqualTo(3);
            assertThat(observation.routeState()).isEqualTo(RouteState.DISPLAY_LINKED);
        }
    }

    @Test
    void liveToleranceChangesOnlyAffectNewIntentsNotRecovery() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("tolerance-live"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            AtomicReference<Duration> tolerance = new AtomicReference<>(Duration.ofSeconds(3));
            QqChannelRouter crashing = h.router(tolerance::get, cp -> {
                if (cp == QqChannelRouter.CrashPoint.AFTER_BOARD) {
                    throw new SimulatedCrash();
                }
            });
            assertThatThrownBy(() -> crashing.ingest(input(ONEBOT, "ob-tol-old", "10001",
                    T0, "old", QqChannelRouteInput.RawProjectionMode
                            .LEGACY_APPEND_OR_MERGE)))
                    .isInstanceOf(SimulatedCrash.class);
            tolerance.set(Duration.ofSeconds(7));
            assertThatThrownBy(() -> crashing.ingest(input(ONEBOT, "ob-tol-new", "10001",
                    T0.plusSeconds(20), "new", QqChannelRouteInput.RawProjectionMode
                            .LEGACY_APPEND_OR_MERGE)))
                    .isInstanceOf(SimulatedCrash.class);
            assertThat(h.ledger.findIngestIntentByMessage(ONEBOT, "ob-tol-old")
                    .orElseThrow().strictTimeToleranceSeconds()).isEqualTo(3);
            assertThat(h.ledger.findIngestIntentByMessage(ONEBOT, "ob-tol-new")
                    .orElseThrow().strictTimeToleranceSeconds()).isEqualTo(7);

            tolerance.set(Duration.ofSeconds(9));
            h.router(tolerance::get, QqChannelRouter.CrashHook.noop()).recover();
            assertThat(h.ledger.findObservation(ONEBOT, "ob-tol-old")
                    .orElseThrow().strictTimeToleranceSeconds()).isEqualTo(3);
            assertThat(h.ledger.findObservation(ONEBOT, "ob-tol-new")
                    .orElseThrow().strictTimeToleranceSeconds()).isEqualTo(7);
        }
    }

    @Test
    void crashAfterEvidenceDoesNotDuplicateItemOrWake() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("crash-evidence"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.router().ingest(input(ONEBOT, "ob-1", "10001", T0, "hello",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE));
            InboxPanel pushed = h.inbox.awaitPush(0L, Duration.ofSeconds(2));
            assertThat(pushed).isNotNull();
            long pushRevision = pushed.pushRevision();

            QqChannelRouter crashing = h.router(cp -> {
                if (cp == QqChannelRouter.CrashPoint.AFTER_EVIDENCE) {
                    throw new SimulatedCrash();
                }
            });
            QqChannelRouteInput evidence = input(OFFICIAL, "of-1", "openid-1",
                    T0.plusMillis(100), "hello",
                    QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE);
            assertThatThrownBy(() -> crashing.ingest(evidence))
                    .isInstanceOf(SimulatedCrash.class);
            h.router().recover();

            assertThat(h.inboxStore.items()).hasSize(1);
            assertThat(h.inbox.pushRevision()).isEqualTo(pushRevision);
            assertThat(h.inbox.awaitPush(pushRevision, Duration.ofMillis(120))).isNull();
        }
    }

    @Test
    void intentSnapshotSurvivesBindingEpochSwitch() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("epoch"))) {
            h.bindBoth();
            QqChannelRouteInput event = input(ONEBOT, "ob-epoch", "10001", T0, "old epoch",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE);
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            QqChannelRouter crashing = h.router(cp -> {
                if (cp == QqChannelRouter.CrashPoint.AFTER_INTENT) {
                    throw new SimulatedCrash();
                }
            });
            assertThatThrownBy(() -> crashing.ingest(event)).isInstanceOf(SimulatedCrash.class);

            // Simulate a control switch after intent/while ingress was interrupted.
            h.control.put(new QqChannelControl.ControlSnapshot(
                    "thread.new", "new", ONEBOT, Channel.ONEBOT, "bot-1", 1L, 99L,
                    QqChannelObservationStore.bindingEpochId("thread.new",
                            List.of(new ActiveBindingRef(Channel.ONEBOT, "bot-1", ONEBOT, 99L))),
                    false, null, ChannelAvailability.UNKNOWN, null, MatchingMode.OFF,
                    99L, "", IdentityStatus.UNMAPPED,
                    List.of(new ActiveBindingRef(Channel.ONEBOT, "bot-1", ONEBOT, 99L))));
            h.router().ingest(event);

            Observation observation = h.ledger.findObservation(ONEBOT, "ob-epoch").orElseThrow();
            assertThat(observation.threadId()).isEqualTo(THREAD);
            assertThat(observation.bindingGeneration()).isEqualTo(1L);
            assertThat(h.inboxStore.items()).singleElement()
                    .extracting(InboxItem::source)
                    .isEqualTo("qq_thread/" + THREAD);
        }
    }

    @Test
    void concurrentTwoChannelIngressStillProducesOneCanonicalItem() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("concurrent"))) {
            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            QqChannelRouter router = h.router();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> onebot = pool.submit(() -> {
                    await(start);
                    router.ingest(input(ONEBOT, "ob-concurrent", "10001", T0, "same",
                            QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE));
                });
                Future<?> official = pool.submit(() -> {
                    await(start);
                    router.ingest(input(OFFICIAL, "of-concurrent", "openid-1",
                            T0.plusMillis(100), "same",
                            QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE));
                });
                start.countDown();
                onebot.get(5, TimeUnit.SECONDS);
                official.get(5, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            assertThat(h.board.read(SourceAddress.parse(ONEBOT), 0L, 10)).hasSize(1);
            assertThat(h.board.read(SourceAddress.parse(OFFICIAL), 0L, 10)).hasSize(1);
            assertThat(h.inboxStore.items()).hasSize(1);
            assertThat(h.inbox.awaitPush(0L, Duration.ofSeconds(2))).isNotNull();
        }
    }

    @Test
    void unboundOfficialGateOffTombstoneSurvivesReenableReplay() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("raw-tombstone"))) {
            QqChannelRouteInput event = input(OFFICIAL, "of-paused", "openid-1", T0, "paused",
                    QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE);
            h.control.sourceGate(OFFICIAL, false);
            QqChannelRouteResult paused = h.router().ingest(event);
            assertThat(paused.disposition())
                    .isEqualTo(QqChannelRouteResult.Disposition.BOUND_GATE_OFF);
            assertThat(h.board.read(SourceAddress.parse(OFFICIAL), 0L, 10)).hasSize(1);
            assertThat(h.inboxStore.items()).isEmpty();

            // Re-enable and redeliver the same paused event: the durable suppression receipt must
            // prevent a back-filled wake.
            h.control.sourceGate(OFFICIAL, true);
            h.router().ingest(event);
            assertThat(h.board.read(SourceAddress.parse(OFFICIAL), 0L, 10)).hasSize(1);
            assertThat(h.inboxStore.items()).isEmpty();
            assertThat(h.inbox.awaitPush(0L, Duration.ofMillis(120))).isNull();
        }
    }

    @Test
    void preboundSourceIsActivatedByItsFirstRealIngress() throws Exception {
        Path dir = tempDir.resolve("prebind-control");
        try (Harness h = new Harness(dir);
             QqChannelRegistry registry = new QqChannelRegistry(
                     dir.resolve("registry.db"),
                     (channel, accountId) -> true,
                     rawSource -> false,
                     Clock.fixed(T0, ZoneOffset.UTC),
                     QqChannelRegistry.MatchingMode.STRICT,
                     (channel, accountId) -> true)) {
            h.ledger.beginBinding(ONEBOT, THREAD, Channel.ONEBOT, "bot-1", 1L);
            registry.bind(THREAD, ONEBOT, QqChannelRegistry.Channel.ONEBOT);
            assertThat(registry.binding(ONEBOT).orElseThrow().state())
                    .isEqualTo(QqChannelRegistry.BindingState.PENDING_OBSERVATION);

            RegistryQqChannelControl control = new RegistryQqChannelControl(
                    registry, QqChannelObservationLedger.from(h.ledger));
            QqChannelControl.SourceState state = control.sourceState(
                    ONEBOT, "10001", Channel.ONEBOT);
            assertThat(state.binding()).isPresent();
            assertThat(state.binding().orElseThrow().gateEnabled()).isTrue();
            assertThat(state.binding().orElseThrow().selectedChannel())
                    .isEqualTo(Channel.ONEBOT);
            assertThat(registry.binding(ONEBOT).orElseThrow().state())
                    .isEqualTo(QqChannelRegistry.BindingState.ACTIVE);
        }
    }

    @Test
    void rawTombstoneBeforeBoardSurvivesLaterBindingWithoutCanonicalWake() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("tombstone-bind"))) {
            QqChannelRouteInput event = input(OFFICIAL, "of-tombstone", "openid-1", T0,
                    "paused before bind",
                    QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE);
            h.control.sourceGate(OFFICIAL, false);
            QqChannelRouter crashing = h.router(cp -> {
                if (cp == QqChannelRouter.CrashPoint.AFTER_RAW_SUPPRESSION) {
                    throw new SimulatedCrash();
                }
            });
            assertThatThrownBy(() -> crashing.ingest(event)).isInstanceOf(SimulatedCrash.class);
            assertThat(h.board.read(SourceAddress.parse(OFFICIAL), 0L, 10)).isEmpty();
            assertThat(h.inboxStore.items()).isEmpty();
            assertThat(h.ledger.findIngestIntentByMessage(OFFICIAL, "of-tombstone")).isEmpty();

            // The source is now bound before the interrupted event is redelivered.
            h.bindBoth();
            h.control.sourceGate(OFFICIAL, true);
            h.control.put(officialSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            h.router().ingest(event);
            h.router().ingest(event);

            assertThat(h.board.read(SourceAddress.parse(OFFICIAL), 0L, 10)).hasSize(1);
            assertThat(h.inboxStore.items()).isEmpty();
            assertThat(h.inbox.awaitPush(0L, Duration.ofMillis(120))).isNull();
            assertThat(h.ledger.findIngestIntentByMessage(OFFICIAL, "of-tombstone")).isEmpty();
            assertThat(h.ledger.findObservation(OFFICIAL, "of-tombstone")).isEmpty();
        }
    }

    @Test
    void inboxPreviewUsesRealBoardSeqAndAdapterSourceLabel() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("labels-seq"))) {
            QqChannelRouteInput raw = input(ONEBOT, "ob-label", "10001", T0, "labeled",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE);
            SourceView custom = SourceView.of(raw.source(), "自定义群 Alice");
            QqChannelRouteInput labeled = new QqChannelRouteInput(raw.dropped(),
                    raw.accountId(), raw.source(), raw.messageId(), raw.senderId(),
                    raw.direction(), raw.messageAt(), raw.observedAt(), raw.priority(),
                    raw.timeReliability(), raw.contentIntegrity(),
                    raw.structuredMentionsTrusted(), raw.segments(), raw.boardMessage(),
                    raw.inboxEntry(), raw.rawProjectionMode(), custom);
            h.router().ingest(labeled);
            long boardSeq = h.board.findInbound(
                    SourceAddress.parse(ONEBOT), "ob-label").orElseThrow().localSeq();
            InboxItem item = h.inboxStore.items().getFirst();
            assertThat(item.sourceLabel()).isEqualTo("自定义群 Alice");
            assertThat(item.entries()).singleElement().satisfies(entry ->
                    assertThat(entry.localSeq()).isEqualTo(boardSeq));

            h.bindBoth();
            h.control.put(onebotSnapshot(h, ChannelAvailability.AVAILABLE,
                    Channel.ONEBOT, MatchingMode.STRICT, "canonical-1"));
            QqChannelRouteInput bound = input(ONEBOT, "ob-bound-label", "10001",
                    T0.plusSeconds(10), "bound",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE);
            QqChannelRouteInput boundLabeled = new QqChannelRouteInput(bound.dropped(),
                    bound.accountId(), bound.source(), bound.messageId(), bound.senderId(),
                    bound.direction(), bound.messageAt(), bound.observedAt(), bound.priority(),
                    bound.timeReliability(), bound.contentIntegrity(),
                    bound.structuredMentionsTrusted(), bound.segments(), bound.boardMessage(),
                    bound.inboxEntry(), bound.rawProjectionMode(), custom);
            h.router(threadId -> "测试逻辑会话").ingest(boundLabeled);
            InboxItem canonical = h.inboxStore.items().stream()
                    .filter(candidate -> candidate.source().equals("qq_thread/" + THREAD))
                    .findFirst().orElseThrow();
            assertThat(canonical.sourceLabel()).isNotEqualTo("自定义群 Alice");
            assertThat(canonical.sourceLabel()).isEqualTo("测试逻辑会话");
        }
    }

    @Test
    void unboundOneBotKeepsLegacyBoardAndInboxBehavior() throws Exception {
        try (Harness h = new Harness(tempDir.resolve("unbound"))) {
            QqChannelRouteInput event = input(ONEBOT, "ob-raw", "10001", T0, "legacy",
                    QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE);
            QqChannelRouteResult first = h.router().ingest(event);
            assertThat(first.disposition())
                    .isEqualTo(QqChannelRouteResult.Disposition.UNBOUND_RAW);
            assertThat(h.inboxStore.items()).hasSize(1);
            assertThat(h.inboxStore.items().getFirst().source()).isEqualTo(ONEBOT);
            assertThat(h.ledger.findObservation(ONEBOT, "ob-raw")).isEmpty();
            assertThat(h.ledger.findIngestIntentByMessage(ONEBOT, "ob-raw")).isEmpty();

            QqChannelRouteResult duplicate = h.router().ingest(event);
            assertThat(duplicate.disposition())
                    .isEqualTo(QqChannelRouteResult.Disposition.DUPLICATE);
            assertThat(h.inboxStore.items()).hasSize(1);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static QqChannelRouteInput withObservedAt(
            QqChannelRouteInput input, Instant observedAt,
            io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.TimeReliability
                    timeReliability) {
        return new QqChannelRouteInput(input.dropped(), input.accountId(), input.source(),
                input.messageId(), input.senderId(), input.direction(), input.messageAt(),
                observedAt, input.priority(), timeReliability, input.contentIntegrity(),
                input.structuredMentionsTrusted(), input.segments(), input.boardMessage(),
                input.inboxEntry(), input.rawProjectionMode(), input.sourceView());
    }

    private static QqChannelRouteInput input(String rawSource, String messageId, String senderId,
                                             Instant at, String text,
                                             QqChannelRouteInput.RawProjectionMode mode) {
        return input(rawSource, messageId, senderId, at, text, mode,
                List.of(new Segment("text", text)), false);
    }

    private static QqChannelRouteInput input(String rawSource, String messageId, String senderId,
                                             Instant at, String text,
                                             QqChannelRouteInput.RawProjectionMode mode,
                                             List<Segment> segments,
                                             boolean structuredMentionsTrusted) {
        SourceAddress source = SourceAddress.parse(rawSource);
        Channel channel = source.platform().startsWith("qq_official")
                ? Channel.QQ_OFFICIAL : Channel.ONEBOT;
        InboundMessage board = new InboundMessage(source, messageId, at,
                segments, Direction.INBOUND, senderId, "Sender");
        InboxEntry entry = new InboxEntry(0L, at, "Sender", text, List.of(),
                channel.wireName(), Map.of("messageId", messageId));
        return new QqChannelRouteInput(false, source.accountId(), source, messageId,
                senderId, Direction.INBOUND, at, at, 100,
                io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.TimeReliability
                        .RELIABLE,
                io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ContentIntegrity
                        .COMPLETE,
                structuredMentionsTrusted, board.segments(), board, entry, mode);
    }

    private static QqChannelControl.ControlSnapshot onebotSnapshot(
            Harness h, ChannelAvailability availability, Channel selected, MatchingMode mode,
            String canonical) {
        return onebotSnapshot(h, availability, selected, mode, canonical, true);
    }

    private static QqChannelControl.ControlSnapshot onebotSnapshot(
            Harness h, ChannelAvailability availability, Channel selected, MatchingMode mode,
            String canonical, boolean gateEnabled) {
        return h.snapshot(ONEBOT, Channel.ONEBOT, "bot-1", Channel.ONEBOT,
                availability, selected, mode, canonical, gateEnabled);
    }

    private static QqChannelControl.ControlSnapshot officialSnapshot(
            Harness h, ChannelAvailability availability, Channel selected, MatchingMode mode,
            String canonical) {
        return h.snapshot(OFFICIAL, Channel.QQ_OFFICIAL, "app-1", Channel.ONEBOT,
                availability, selected, mode, canonical);
    }

    private static final class FakeControl implements QqChannelControl {
        private final Map<String, ControlSnapshot> snapshots = new LinkedHashMap<>();
        private final Map<String, Boolean> sourceGates = new LinkedHashMap<>();

        void put(ControlSnapshot snapshot) {
            snapshots.put(snapshot.rawSource(), snapshot);
        }

        void sourceGate(String rawSource, boolean enabled) {
            sourceGates.put(rawSource, enabled);
        }

        void clearBindings() {
            snapshots.clear();
        }

        @Override
        public SourceState sourceState(String rawSource, String senderId, Channel incomingChannel) {
            ControlSnapshot snapshot = snapshots.get(rawSource);
            return snapshot == null
                    ? SourceState.unbound(sourceGates.getOrDefault(rawSource, true))
                    : new SourceState(sourceGates.getOrDefault(rawSource, true),
                            Optional.of(snapshot));
        }

        @Override
        public List<String> activeRawSources() {
            return List.copyOf(snapshots.keySet());
        }

        @Override
        public List<String> activeThreadIds() {
            return snapshots.values().stream()
                    .map(ControlSnapshot::threadId).distinct().toList();
        }
    }

    private static final class Harness implements AutoCloseable {
        private final Path dir;
        private final io.github.c1589190.bma3.core.store.SqliteMessageBoardStore board;
        private final SqliteInboxStore inboxStore;
        private final InboxService inbox;
        private final QqChannelObservationStore ledger;
        private final FakeControl control = new FakeControl();
        private final ChannelMutationGate gate = ChannelMutationGate.processLocal();
        private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

        Harness(Path dir) throws java.io.IOException {
            this.dir = dir;
            try {
                Files.createDirectories(dir);
                board = new io.github.c1589190.bma3.core.store.SqliteMessageBoardStore(
                        dir.resolve("bot.db"));
                inboxStore = new SqliteInboxStore(dir.resolve("inbox.db"));
                inbox = new InboxService(inboxStore,
                        new DeliveryPolicy(0, Duration.ofMillis(1), Duration.ofMillis(1)));
                ledger = new QqChannelObservationStore(dir.resolve("qq-channels.db"), clock);
            } catch (RuntimeException failure) {
                throw failure;
            }
        }

        void bindBoth() {
            ledger.beginBinding(ONEBOT, THREAD, Channel.ONEBOT, "bot-1", 1L);
            ledger.beginBinding(OFFICIAL, THREAD, Channel.QQ_OFFICIAL, "app-1", 1L);
        }

        QqChannelRouter router() {
            return router(Duration.ofSeconds(5), QqChannelRouter.CrashHook.noop());
        }

        QqChannelRouter router(QqChannelRouter.CrashHook hook) {
            return router(Duration.ofSeconds(5), hook);
        }

        QqChannelRouter router(Duration tolerance, QqChannelRouter.CrashHook hook) {
            return router(QqChannelInboxPort.from(inbox, inboxStore), tolerance, hook);
        }

        QqChannelRouter router(QqChannelInboxPort inboxPort, QqChannelRouter.CrashHook hook) {
            return router(inboxPort, Duration.ofSeconds(5), hook);
        }

        QqChannelRouter router(QqChannelInboxPort inboxPort, Duration tolerance,
                               QqChannelRouter.CrashHook hook) {
            return new QqChannelRouter(QqChannelBoardPort.from(board),
                    QqChannelObservationLedger.from(ledger),
                    inboxPort, control, new CrossChannelMatcher(), gate, clock,
                    tolerance, QqChannelPreviewFactory.generic(), hook);
        }

        QqChannelRouter router(Supplier<Duration> tolerance,
                               QqChannelRouter.CrashHook hook) {
            return new QqChannelRouter(QqChannelBoardPort.from(board),
                    QqChannelObservationLedger.from(ledger),
                    QqChannelInboxPort.from(inbox, inboxStore), control,
                    new CrossChannelMatcher(), gate, clock, tolerance,
                    QqChannelPreviewFactory.generic(), hook, Function.identity());
        }

        QqChannelRouter router(Function<String, String> threadLabelProvider) {
            return new QqChannelRouter(QqChannelBoardPort.from(board),
                    QqChannelObservationLedger.from(ledger),
                    QqChannelInboxPort.from(inbox, inboxStore), control,
                    new CrossChannelMatcher(), gate, clock, Duration.ofSeconds(5),
                    threadLabelProvider);
        }

        QqChannelControl.ControlSnapshot snapshot(String rawSource, Channel channel,
                                 String accountId, Channel preferred,
                                 ChannelAvailability availability, Channel selected,
                                 MatchingMode mode, String canonical) {
            return snapshot(rawSource, channel, accountId, preferred, availability, selected,
                    mode, canonical, true);
        }

        QqChannelControl.ControlSnapshot snapshot(String rawSource, Channel channel,
                                 String accountId, Channel preferred,
                                 ChannelAvailability availability, Channel selected,
                                 MatchingMode mode, String canonical, boolean gateEnabled) {
            io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.SourceBinding binding =
                    ledger.sourceBinding(rawSource).orElseThrow();
            List<ActiveBindingRef> refs = new ArrayList<>();
            refs.add(new ActiveBindingRef(channel, accountId, rawSource, binding.generation()));
            String peerRaw = channel == Channel.ONEBOT ? OFFICIAL : ONEBOT;
            String peerAccount = channel == Channel.ONEBOT ? "app-1" : "bot-1";
            Channel peerChannel = channel == Channel.ONEBOT
                    ? Channel.QQ_OFFICIAL : Channel.ONEBOT;
            ledger.sourceBinding(peerRaw).ifPresent(peer ->
                    refs.add(new ActiveBindingRef(peerChannel, peerAccount, peerRaw,
                            peer.generation())));
            String epoch = QqChannelObservationStore.bindingEpochId(THREAD, refs);
            return new QqChannelControl.ControlSnapshot(THREAD, "thread", rawSource, channel,
                    accountId, binding.startBoardSeq(), binding.generation(), epoch, gateEnabled,
                    preferred, availability, selected, mode, 1L,
                    canonical == null ? "" : canonical,
                    canonical == null || canonical.isBlank()
                            ? IdentityStatus.UNMAPPED : IdentityStatus.MAPPED, refs);
        }

        @Override
        public void close() {
            ledger.close();
            inboxStore.close();
            board.close();
        }
    }

    private static final class SimulatedCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
