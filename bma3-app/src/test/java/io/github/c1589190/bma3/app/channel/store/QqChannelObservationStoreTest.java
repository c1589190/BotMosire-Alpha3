package io.github.c1589190.bma3.app.channel.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.BindingEventKind;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.Channel;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ChannelAvailability;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ContentIntegrity;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.CorrelationAuditAction;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.EvidenceCandidateDraft;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.EvidenceOutboxState;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.CorrelationState;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.CorrelationUpdate;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.Direction;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ErrorCode;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.IngestIntentDraft;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.IdentityStatus;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.IngestIntentState;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.MatchingMode;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.Observation;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ObservedAtCursor;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.RouteReason;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ObservationCursor;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ObservationDraft;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ObservationPage;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.RouteState;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.StoreException;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.TimeReliability;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QqChannelObservationStoreTest {

    private static final Instant BASE = Instant.parse("2026-11-17T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(BASE, ZoneOffset.UTC);
    private static final String ONEBOT = "qq_group/bot-1/955432692";
    private static final String ONEBOT_OTHER = "qq_group/bot-2/955432693";
    private static final String OFFICIAL = "qq_official_group/app-1/group_openid_A";
    private static final String TEST_EPOCH = "epoch-test-thread";

    @TempDir
    Path tempDir;

    private QqChannelObservationStore store;
    private QqChannelRegistry registry;

    @AfterEach
    void closeStores() {
        if (store != null) {
            store.close();
        }
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    void observationWritesAreIdempotentAndRejectConflictingReplayOrAccount() {
        store = open("channels.db");
        ObservationDraft original = draft(ONEBOT, "m-1", 10, "thread.one", "bot-1", BASE);

        var first = store.observe(original);
        assertThat(first.changed()).isTrue();
        assertThat(first.observation().observationId())
                .isEqualTo(store.observationIdFor(ONEBOT, "m-1"));
        assertThat(first.observation().requestKey())
                .isEqualTo(store.requestKeyFor(ONEBOT, "m-1"));
        assertThat(first.observation().routeState()).isEqualTo(RouteState.PENDING_ROUTE);
        assertThat(first.observation().itemId()).isEmpty();
        assertThat(first.observation().correlationState()).isEqualTo(CorrelationState.CHANNEL_ONLY);

        var replay = store.observe(original);
        assertThat(replay.changed()).isFalse();
        assertThat(replay.observation()).isEqualTo(first.observation());

        ObservationDraft conflictingThread = new ObservationDraft(
                "thread.two", original.channel(), original.accountId(), original.rawSource(),
                original.channelMessageId(), original.boardLocalSeq(), original.senderId(),
                original.direction(), original.messageAt(), original.observedAt(),
                original.priority(), original.timeReliability(), original.contentIntegrity());
        expectCode(ErrorCode.OBSERVATION_CONFLICT, () -> store.observe(conflictingThread));
        assertThat(store.findObservation(ONEBOT, "m-1").orElseThrow().threadId())
                .isEqualTo("thread.one");

        ObservationDraft wrongAccount = new ObservationDraft(
                "thread.one", Channel.ONEBOT, "bot-2", ONEBOT, "m-2", 11,
                "sender-1", Direction.INBOUND, BASE, BASE, 10,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE);
        expectCode(ErrorCode.ACCOUNT_MISMATCH, () -> store.observe(wrongAccount));
        assertThat(store.findObservation(ONEBOT, "m-2")).isEmpty();

        // The raw source itself cannot be reused with a different account path.
        expectCode(ErrorCode.ACCOUNT_MISMATCH,
                () -> store.beginBinding(ONEBOT, "thread.one", Channel.ONEBOT, "bot-2", 0));
    }

    @Test
    void routeStatesAreAtomicAuditedAndTerminalReplayCannotOverwriteFirstDecision() {
        store = open("channels.db");
        store.observe(draft(ONEBOT, "route-1", 1, "thread.one", "bot-1", BASE));
        store.observe(draft(ONEBOT, "route-2", 2, "thread.one", "bot-1", BASE.plusSeconds(1)));

        var pending = store.decideRoute(ONEBOT, "route-1", RouteState.DISPLAY_PENDING,
                "itm-stable-1");
        assertThat(pending.changed()).isTrue();
        assertThat(pending.previousState()).isEqualTo(RouteState.PENDING_ROUTE);
        assertThat(pending.observation().itemId()).contains("itm-stable-1");
        assertThat(pending.observation().routeRevision()).isEqualTo(2);

        var repeatedPending = store.decideRoute(ONEBOT, "route-1",
                RouteState.DISPLAY_PENDING, "itm-stable-1");
        assertThat(repeatedPending.changed()).isFalse();
        expectCode(ErrorCode.ROUTE_CONFLICT, () -> store.decideRoute(ONEBOT, "route-1",
                RouteState.DISPLAY_PENDING, "itm-different"));

        var linked = store.decideRoute(ONEBOT, "route-1", RouteState.DISPLAY_LINKED, null);
        assertThat(linked.changed()).isTrue();
        assertThat(linked.observation().itemId()).contains("itm-stable-1");
        assertThat(linked.observation().routeRevision()).isEqualTo(3);
        assertThat(store.decideRoute(ONEBOT, "route-1", RouteState.DISPLAY_LINKED,
                "itm-stable-1").changed()).isFalse();
        expectCode(ErrorCode.ILLEGAL_ROUTE_TRANSITION,
                () -> store.decideRoute(ONEBOT, "route-1", RouteState.EVIDENCE_ONLY, null));

        var evidenceOnly = store.decideRoute(ONEBOT, "route-2", RouteState.EVIDENCE_ONLY, null);
        assertThat(evidenceOnly.changed()).isTrue();
        assertThat(store.decideRoute(ONEBOT, "route-2", RouteState.EVIDENCE_ONLY, null).changed())
                .isFalse();
        expectCode(ErrorCode.ILLEGAL_ROUTE_TRANSITION,
                () -> store.decideRoute(ONEBOT, "route-2", RouteState.DISPLAY_PENDING, "itm-x"));

        assertThat(store.routeHistory(ONEBOT, "route-1"))
                .extracting(entry -> entry.toState())
                .containsExactly(RouteState.PENDING_ROUTE, RouteState.DISPLAY_PENDING,
                        RouteState.DISPLAY_LINKED);
        assertThat(store.routeHistory(ONEBOT, "route-2").get(0).fromState()).isEmpty();
        assertThat(store.routeHistory(ONEBOT, "route-2").get(0).toState())
                .isEqualTo(RouteState.PENDING_ROUTE);
    }

    @Test
    void threadPaginationUsesAStableTimeWindowAndKeysetCursor() {
        store = open("channels.db");
        for (int i = 1; i <= 5; i++) {
            store.observe(draft(ONEBOT, "page-" + i, i, "thread.page", "bot-1",
                    BASE.plusSeconds(i)));
        }
        store.observe(draft(ONEBOT, "other-thread", 6, "thread.other", "bot-1",
                BASE.plusSeconds(2)));

        ObservationPage first = store.observationsForThread("thread.page",
                BASE.plusSeconds(2), BASE.plusSeconds(5), 2);
        assertThat(first.items()).extracting(Observation::channelMessageId)
                .containsExactly("page-2", "page-3");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isPresent();

        ObservationCursor cursor = first.nextCursor().orElseThrow();
        ObservationPage second = store.observationsForThread("thread.page",
                BASE.plusSeconds(2), BASE.plusSeconds(5), cursor, 2);
        assertThat(second.items()).extracting(Observation::channelMessageId)
                .containsExactly("page-4");
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isEmpty();

        ObservationPage third = store.observationsForThread("thread.page",
                BASE.plusSeconds(2), BASE.plusSeconds(5),
                new ObservationCursor(BASE.plusSeconds(4), "obs-does-not-exist"), 2);
        assertThat(third.items()).isEmpty();
    }

    @Test
    void sourceCursorAdvancesOnlyInsideTheActiveGenerationAndNeverRegresses() {
        store = open("channels.db");
        var binding = store.beginBinding(ONEBOT, "thread.scan", Channel.ONEBOT, "bot-1", 10);
        assertThat(binding.changed()).isTrue();
        assertThat(binding.binding().generation()).isEqualTo(1);
        assertThat(binding.binding().startBoardSeq()).isEqualTo(10);
        assertThat(binding.binding().scanCursor()).isEqualTo(9);
        assertThat(binding.binding().nextBoardSeq()).hasValue(10);
        assertThat(store.scanWindow(ONEBOT).fromBoardSeq()).isEqualTo(10);

        var first = store.ingestScanBatch(ONEBOT, 1, 10,
                List.of(draft(ONEBOT, "scan-10", 10, "thread.scan", "bot-1", BASE)));
        assertThat(first.advanced()).isTrue();
        assertThat(first.inserted()).isEqualTo(1);
        assertThat(first.binding().scanCursor()).isEqualTo(10);

        var replay = store.ingestScanBatch(ONEBOT, 1, 10,
                List.of(draft(ONEBOT, "scan-10", 10, "thread.scan", "bot-1", BASE)));
        assertThat(replay.advanced()).isFalse();
        assertThat(replay.unchanged()).isEqualTo(1);
        assertThat(store.scanWindow(ONEBOT).fromBoardSeq()).isEqualTo(11);

        var second = store.ingestScanBatch(ONEBOT, 1, 12,
                List.of(draft(ONEBOT, "scan-12", 12, "thread.scan", "bot-1",
                        BASE.plusSeconds(1))));
        assertThat(second.advanced()).isTrue();
        assertThat(second.binding().scanCursor()).isEqualTo(12);
        assertThat(store.scanWindow(ONEBOT).fromBoardSeq()).isEqualTo(13);

        expectCode(ErrorCode.CURSOR_REGRESSION,
                () -> store.ingestScanBatch(ONEBOT, 1, 11, List.of()));
        assertThat(store.sourceBinding(ONEBOT).orElseThrow().scanCursor()).isEqualTo(12);
    }

    @Test
    void failedScanBatchRollsBackObservationsAndCursorTogether() {
        store = open("channels.db");
        store.observe(draft(ONEBOT, "conflict-existing", 1, "thread.scan", "bot-1", BASE));
        store.beginBinding(ONEBOT, "thread.scan", Channel.ONEBOT, "bot-1", 10);

        ObservationDraft newFirst = draft(ONEBOT, "must-rollback", 11, "thread.scan",
                "bot-1", BASE.plusSeconds(1));
        ObservationDraft contradictory = new ObservationDraft(
                "thread.scan", Channel.ONEBOT, "bot-1", ONEBOT, "conflict-existing", 12,
                "different-sender", Direction.INBOUND, BASE.plusSeconds(2), BASE,
                10, TimeReliability.RELIABLE, ContentIntegrity.COMPLETE);

        expectCode(ErrorCode.OBSERVATION_CONFLICT,
                () -> store.ingestScanBatch(ONEBOT, 1, 20, List.of(newFirst, contradictory)));

        assertThat(store.findObservation(ONEBOT, "must-rollback")).isEmpty();
        assertThat(store.sourceBinding(ONEBOT).orElseThrow().scanCursor()).isEqualTo(9);
        assertThat(store.statusCounts("thread.scan").observations()).isEqualTo(1);
    }

    @Test
    void rebindCreatesANewGenerationAndRejectsStaleBoundaryOrGeneration() {
        store = open("channels.db");
        store.beginBinding(ONEBOT, "thread.old", Channel.ONEBOT, "bot-1", 10);
        store.ingestScanBatch(ONEBOT, 1, 10,
                List.of(draft(ONEBOT, "old-10", 10, "thread.old", "bot-1", BASE)));

        var unbound = store.unbindSource(ONEBOT);
        assertThat(unbound.changed()).isTrue();
        assertThat(unbound.binding().active()).isFalse();
        assertThat(store.findObservation(ONEBOT, "old-10")).isPresent();
        expectCode(ErrorCode.SOURCE_NOT_BOUND,
                () -> store.ingestScanBatch(ONEBOT, 1, 11, List.of()));

        var rebind = store.beginBinding(ONEBOT, "thread.new", Channel.ONEBOT, "bot-1", 20);
        assertThat(rebind.changed()).isTrue();
        assertThat(rebind.binding().generation()).isEqualTo(2);
        assertThat(rebind.binding().startBoardSeq()).isEqualTo(20);
        assertThat(rebind.binding().scanCursor()).isEqualTo(19);

        expectCode(ErrorCode.GENERATION_MISMATCH,
                () -> store.ingestScanBatch(ONEBOT, 1, 21,
                        List.of(draft(ONEBOT, "stale-gen", 21, "thread.new", "bot-1", BASE))));
        assertThat(store.findObservation(ONEBOT, "stale-gen")).isEmpty();
        assertThat(store.sourceBinding(ONEBOT).orElseThrow().scanCursor()).isEqualTo(19);

        expectCode(ErrorCode.BINDING_MISMATCH,
                () -> store.ingestScanBatch(ONEBOT, 2, 21,
                        List.of(draft(ONEBOT, "wrong-thread", 21, "thread.old", "bot-1", BASE))));
        expectCode(ErrorCode.BOARD_SEQ_BEFORE_BINDING_BOUNDARY,
                () -> store.ingestScanBatch(ONEBOT, 2, 21,
                        List.of(draft(ONEBOT, "before-boundary", 19, "thread.new", "bot-1", BASE))));
        assertThat(store.sourceBinding(ONEBOT).orElseThrow().scanCursor()).isEqualTo(19);

        var newBatch = store.ingestScanBatch(ONEBOT, 2, 20,
                List.of(draft(ONEBOT, "new-20", 20, "thread.new", "bot-1", BASE)));
        assertThat(newBatch.advanced()).isTrue();
        assertThat(store.findObservation(ONEBOT, "old-10").orElseThrow().threadId())
                .isEqualTo("thread.old");
        assertThat(store.findObservation(ONEBOT, "new-20").orElseThrow().threadId())
                .isEqualTo("thread.new");

        assertThat(store.bindingHistory(ONEBOT))
                .extracting(event -> event.kind())
                .containsExactly(BindingEventKind.BOUND, BindingEventKind.UNBOUND,
                        BindingEventKind.BOUND);
    }

    @Test
    void restartPreservesObservationRouteItemCursorAndBindingHistory() {
        Path database = tempDir.resolve("qq-channels.db");
        store = new QqChannelObservationStore(database, FIXED_CLOCK);
        store.beginBinding(ONEBOT, "thread.restart", Channel.ONEBOT, "bot-1", 30);
        store.ingestScanBatch(ONEBOT, 1, 30,
                List.of(draft(ONEBOT, "restart-30", 30, "thread.restart", "bot-1", BASE)));
        store.decideRoute(ONEBOT, "restart-30", RouteState.DISPLAY_PENDING, "itm-restart");
        store.decideRoute(ONEBOT, "restart-30", RouteState.DISPLAY_LINKED, null);
        store.close();

        store = new QqChannelObservationStore(database, FIXED_CLOCK);
        Observation recovered = store.findObservation(ONEBOT, "restart-30").orElseThrow();
        assertThat(recovered.routeState()).isEqualTo(RouteState.DISPLAY_LINKED);
        assertThat(recovered.itemId()).contains("itm-restart");
        assertThat(recovered.requestKey()).isEqualTo(store.requestKeyFor(ONEBOT, "restart-30"));
        assertThat(store.sourceBinding(ONEBOT).orElseThrow().generation()).isEqualTo(1);
        assertThat(store.sourceBinding(ONEBOT).orElseThrow().scanCursor()).isEqualTo(30);
        assertThat(store.bindingHistory(ONEBOT)).hasSize(1);
        assertThat(store.routeHistory(ONEBOT, "restart-30")).hasSize(3);
    }

    @Test
    void concurrentObserversConvergeOnTheUniqueSourceMessageRow() throws Exception {
        Path database = tempDir.resolve("qq-channels.db");
        QqChannelObservationStore first = new QqChannelObservationStore(database, FIXED_CLOCK);
        QqChannelObservationStore second = new QqChannelObservationStore(database, FIXED_CLOCK);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            ObservationDraft same = draft(ONEBOT, "concurrent-1", 1, "thread.concurrent",
                    "bot-1", BASE);
            Future<Boolean> one = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return first.observe(same).changed();
            });
            Future<Boolean> two = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return second.observe(same).changed();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Boolean> changed = List.of(one.get(10, TimeUnit.SECONDS),
                    two.get(10, TimeUnit.SECONDS));
            assertThat(changed).containsExactlyInAnyOrder(true, false);
            assertThat(first.findObservation(ONEBOT, "concurrent-1")).isPresent();
            assertThat(first.statusCounts("thread.concurrent").observations()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            first.close();
            second.close();
        }
    }

    @Test
    void correlationIsPassiveButAtomicallyCountedAndPeerValidated() {
        store = open("channels.db");
        store.observe(draft(ONEBOT, "corr-onebot", 1, "thread.corr", "bot-1", BASE));
        store.observe(draft(OFFICIAL, "corr-official", 2, "thread.corr", "app-1",
                BASE.plusMillis(100)));

        var onebot = store.recordCorrelation(CorrelationUpdate.withPeer(
                ONEBOT, "corr-onebot", CorrelationState.MATCHED,
                OFFICIAL, "corr-official"));
        assertThat(onebot.correlationState()).isEqualTo(CorrelationState.MATCHED);
        assertThat(onebot.peerObservationId()).contains(
                store.observationIdFor(OFFICIAL, "corr-official"));

        var official = store.recordCorrelation(CorrelationUpdate.withPeer(
                OFFICIAL, "corr-official", CorrelationState.MATCHED,
                ONEBOT, "corr-onebot"));
        assertThat(official.correlationState()).isEqualTo(CorrelationState.MATCHED);

        expectCode(ErrorCode.CORRELATION_CONFLICT,
                () -> store.recordCorrelation(CorrelationUpdate.withPeer(
                        ONEBOT, "corr-onebot", CorrelationState.CONFLICT,
                        ONEBOT, "corr-onebot")));
        expectCode(ErrorCode.CORRELATION_CONFLICT,
                () -> store.recordCorrelation(CorrelationUpdate.unverified(
                        ONEBOT, "corr-onebot")));

        var release = store.releaseCorrelationPair(ONEBOT, "corr-onebot");
        assertThat(release.changed()).isTrue();
        assertThat(release.observations()).hasSize(2)
                .allSatisfy(observation -> {
                    assertThat(observation.correlationState())
                            .isEqualTo(CorrelationState.UNVERIFIED);
                    assertThat(observation.peerObservationId()).isEmpty();
                });

        var suppressed = store.recordCorrelationPair(
                ONEBOT, "corr-onebot", OFFICIAL, "corr-official",
                CorrelationState.SUPPRESSED_UNVERIFIED);
        assertThat(suppressed.changed()).isTrue();
        assertThat(suppressed.first().correlationState())
                .isEqualTo(CorrelationState.SUPPRESSED_UNVERIFIED);
        assertThat(suppressed.second().correlationState())
                .isEqualTo(CorrelationState.SUPPRESSED_UNVERIFIED);
        assertThat(store.findCorrelationPair(ONEBOT, "corr-onebot")).isPresent();

        store.observe(draft(OFFICIAL, "corr-official-2", 3, "thread.corr", "app-1",
                BASE.plusMillis(200)));
        expectCode(ErrorCode.CORRELATION_CONFLICT, () -> store.recordCorrelationPair(
                ONEBOT, "corr-onebot", OFFICIAL, "corr-official-2",
                CorrelationState.UNVERIFIED_MATCH));

        store.decideRoute(OFFICIAL, "corr-official", RouteState.DISPLAY_PENDING, "itm-corr");
        store.decideRoute(OFFICIAL, "corr-official", RouteState.DISPLAY_LINKED, null);
        var counts = store.statusCounts("thread.corr");
        assertThat(counts.observations()).isEqualTo(3);
        assertThat(counts.routeCounts().get(RouteState.PENDING_ROUTE)).isEqualTo(2);
        assertThat(counts.routeCounts().get(RouteState.DISPLAY_LINKED)).isEqualTo(1);
        assertThat(counts.correlationCounts().get(CorrelationState.SUPPRESSED_UNVERIFIED))
                .isEqualTo(2);
        assertThat(counts.correlationCounts().get(CorrelationState.CHANNEL_ONLY))
                .isEqualTo(1);
    }

    @Test
    void preBoardIngestIntentIsBodyFreeIdempotentAndSnapshotsBindingGateAndRoute() {
        store = open("channels.db");
        store.beginBinding(ONEBOT, "thread.intent", Channel.ONEBOT, "bot-1", 50);
        IngestIntentDraft snapshot = intentDraft("evt-1", "intent-50", 50,
                "thread.intent", 1, true, RouteState.DISPLAY_PENDING, "itm-intent");

        var first = store.recordIngestIntent(snapshot);
        assertThat(first.changed()).isTrue();
        assertThat(first.intent().state()).isEqualTo(IngestIntentState.RECORDED);
        assertThat(first.intent().boardLocalSeq()).isEmpty();
        assertThat(first.intent().bindingGeneration()).isEqualTo(1);
        assertThat(first.intent().gateEnabled()).isTrue();
        assertThat(first.intent().routeDecision()).isEqualTo(RouteState.DISPLAY_PENDING);
        assertThat(first.intent().routeItemId()).contains("itm-intent");
        assertThat(first.intent().requestKey())
                .isEqualTo(store.requestKeyFor(ONEBOT, "intent-50"));
        assertThat(store.findObservation(ONEBOT, "intent-50")).isEmpty();
        assertThat(store.pendingIngestIntents(ONEBOT, 10)).hasSize(1);
        assertThat(store.ingestIntentCounts(ONEBOT).recorded()).isEqualTo(1);
        assertThat(store.ingestIntentCounts(ONEBOT).pending()).isEqualTo(1);

        assertThat(store.recordIngestIntent(snapshot).changed()).isFalse();

        IngestIntentDraft changedPriority = intentDraft("evt-1", "intent-50", 50,
                "thread.intent", 1, true, RouteState.DISPLAY_PENDING, "itm-intent");
        changedPriority = new IngestIntentDraft(
                changedPriority.intentKey(), changedPriority.threadId(),
                changedPriority.channel(), changedPriority.accountId(),
                changedPriority.rawSource(), changedPriority.channelMessageId(),
                changedPriority.senderId(), changedPriority.direction(),
                changedPriority.messageAt(), changedPriority.observedAt(), 99,
                changedPriority.timeReliability(), changedPriority.contentIntegrity(),
                changedPriority.bindingGeneration(), changedPriority.gateEnabled(),
                changedPriority.routeDecision(), changedPriority.routeItemId());
        IngestIntentDraft finalChangedPriority = changedPriority;
        expectCode(ErrorCode.INGEST_INTENT_CONFLICT,
                () -> store.recordIngestIntent(finalChangedPriority));

        IngestIntentDraft otherEventSameMessage = intentDraft("evt-2", "intent-50", 50,
                "thread.intent", 1, true, RouteState.EVIDENCE_ONLY, null);
        expectCode(ErrorCode.INGEST_INTENT_CONFLICT,
                () -> store.recordIngestIntent(otherEventSameMessage));

        IngestIntentDraft staleGeneration = intentDraft("evt-3", "intent-51", 51,
                "thread.intent", 2, true, RouteState.EVIDENCE_ONLY, null);
        expectCode(ErrorCode.GENERATION_MISMATCH,
                () -> store.recordIngestIntent(staleGeneration));
        assertThat(store.findIngestIntent("evt-3")).isEmpty();
    }

    @Test
    void promotionUsesThePersistedSnapshotAfterUnbindRebindInsteadOfTheNewGate() {
        store = open("channels.db");
        store.beginBinding(ONEBOT, "thread.old", Channel.ONEBOT, "bot-1", 10);
        store.recordIngestIntent(intentDraft("evt-recover", "recover-11", 11,
                "thread.old", 1, true, RouteState.DISPLAY_PENDING, "itm-original"));

        store.unbindSource(ONEBOT);
        var rebound = store.beginBinding(ONEBOT, "thread.new", Channel.ONEBOT, "bot-1", 100);
        assertThat(rebound.binding().generation()).isEqualTo(2);

        // The old generation's board row is discovered after restart. The intent snapshot, not
        // the new binding/gate, drives the recovered observation and route decision.
        var promotion = store.promoteIngestIntent("evt-recover", 11);
        assertThat(promotion.observationCreated()).isTrue();
        assertThat(promotion.changed()).isTrue();
        assertThat(promotion.observation().threadId()).isEqualTo("thread.old");
        assertThat(promotion.observation().requestKey())
                .isEqualTo(store.requestKeyFor(ONEBOT, "recover-11"));
        assertThat(promotion.intent().requestKey())
                .isEqualTo(promotion.observation().requestKey());
        assertThat(promotion.observation().routeState()).isEqualTo(RouteState.DISPLAY_PENDING);
        assertThat(promotion.observation().itemId()).contains("itm-original");
        assertThat(promotion.intent().state())
                .isEqualTo(IngestIntentState.OBSERVATION_COMMITTED);
        assertThat(store.sourceBinding(ONEBOT).orElseThrow().generation()).isEqualTo(2);
        assertThat(store.sourceBinding(ONEBOT).orElseThrow().scanCursor()).isEqualTo(99);

        var replay = store.promoteIngestIntent("evt-recover", 11);
        assertThat(replay.changed()).isFalse();
        assertThat(replay.observationCreated()).isFalse();
        assertThat(replay.observation().routeState()).isEqualTo(RouteState.DISPLAY_PENDING);
        assertThat(store.ingestIntentCounts(ONEBOT).observationCommitted()).isEqualTo(1);
        assertThat(store.pendingIngestIntents(ONEBOT, 10)).isEmpty();
    }

    @Test
    void boardCommitAndPromotionTransitionsAreIdempotentAndCannotMoveLocalSeq() {
        store = open("channels.db");
        store.beginBinding(ONEBOT, "thread.intent", Channel.ONEBOT, "bot-1", 20);
        store.recordIngestIntent(intentDraft("evt-20", "intent-20", 20,
                "thread.intent", 1, true, RouteState.EVIDENCE_ONLY, null));

        var committed = store.markIngestIntentBoardCommitted("evt-20", 20);
        assertThat(committed.changed()).isTrue();
        assertThat(committed.intent().state()).isEqualTo(IngestIntentState.BOARD_COMMITTED);
        assertThat(committed.intent().boardLocalSeq()).contains(20L);
        assertThat(store.markIngestIntentBoardCommitted("evt-20", 20).changed()).isFalse();
        expectCode(ErrorCode.INGEST_INTENT_STATE_CONFLICT,
                () -> store.markIngestIntentBoardCommitted("evt-20", 21));

        var promoted = store.promoteIngestIntent("evt-20", 20);
        assertThat(promoted.observationCreated()).isTrue();
        assertThat(promoted.observation().routeState()).isEqualTo(RouteState.EVIDENCE_ONLY);
        assertThat(promoted.intent().state())
                .isEqualTo(IngestIntentState.OBSERVATION_COMMITTED);
        assertThat(store.promoteIngestIntent("evt-20", 20).changed()).isFalse();
        expectCode(ErrorCode.INGEST_INTENT_STATE_CONFLICT,
                () -> store.promoteIngestIntent("evt-20", 21));
        expectCode(ErrorCode.INGEST_INTENT_STATE_CONFLICT,
                () -> store.markIngestIntentBoardCommitted("evt-20", 21));

        assertThat(store.findObservation(ONEBOT, "intent-20").orElseThrow().routeState())
                .isEqualTo(RouteState.EVIDENCE_ONLY);
        assertThat(store.routeHistory(ONEBOT, "intent-20"))
                .extracting(entry -> entry.toState())
                .containsExactly(RouteState.PENDING_ROUTE, RouteState.EVIDENCE_ONLY);
        assertThat(store.ingestIntentCounts(ONEBOT).pending()).isZero();
    }

    @Test
    void failedIntentPromotionRollsBackObservationAndLeavesIntentRecoverable() {
        store = open("channels.db");
        store.beginBinding(ONEBOT, "thread.intent", Channel.ONEBOT, "bot-1", 30);
        store.observe(new ObservationDraft("thread.intent", Channel.ONEBOT, "bot-1", ONEBOT,
                "intent-30", 1, "sender-existing", Direction.INBOUND, BASE, BASE, 10,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE));

        IngestIntentDraft conflicting = new IngestIntentDraft(
                "evt-30", "thread.intent", Channel.ONEBOT, "bot-1", ONEBOT, "intent-30",
                "sender-different", Direction.INBOUND, BASE, BASE, 10,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE,
                1, true, RouteState.EVIDENCE_ONLY, null);
        store.recordIngestIntent(conflicting);
        store.markIngestIntentBoardCommitted("evt-30", 30);

        expectCode(ErrorCode.OBSERVATION_CONFLICT,
                () -> store.promoteIngestIntent("evt-30", 30));
        assertThat(store.findIngestIntent("evt-30").orElseThrow().state())
                .isEqualTo(IngestIntentState.BOARD_COMMITTED);
        assertThat(store.findObservation(ONEBOT, "intent-30").orElseThrow().routeState())
                .isEqualTo(RouteState.PENDING_ROUTE);
        assertThat(store.routeHistory(ONEBOT, "intent-30")).hasSize(1);
        assertThat(store.ingestIntentCounts(ONEBOT).pending()).isEqualTo(1);
    }

    @Test
    void gateOffIntentStillProducesObservationButNeverADisplayDecision() {
        store = open("channels.db");
        store.beginBinding(ONEBOT, "thread.intent", Channel.ONEBOT, "bot-1", 40);
        store.recordIngestIntent(intentDraft("evt-40", "intent-40", 40,
                "thread.intent", 1, false, RouteState.GATE_OFF, null));
        var promotion = store.promoteIngestIntent("evt-40", 40);
        assertThat(promotion.observation().routeState()).isEqualTo(RouteState.GATE_OFF);
        assertThat(promotion.observation().itemId()).isEmpty();

        IngestIntentDraft inconsistentGate = intentDraft("evt-41", "intent-41", 41,
                "thread.intent", 1, false, RouteState.DISPLAY_PENDING, "itm-never");
        expectCode(ErrorCode.INVALID_ARGUMENT,
                () -> store.recordIngestIntent(inconsistentGate));
        assertThat(store.findIngestIntent("evt-41")).isEmpty();
    }

    @Test
    void observedAtKeysetPagesSameTimestampPastFiveHundredAndSupportsUnionDedupe() {
        store = open("channels.db");
        store.beginBinding(ONEBOT, "thread.observed", Channel.ONEBOT, "bot-1", 1);
        List<ObservationDraft> drafts = new ArrayList<>();
        for (int i = 1; i <= 501; i++) {
            drafts.add(draft(ONEBOT, "observed-" + i, i, "thread.observed",
                    "bot-1", BASE.plusSeconds(i)));
        }
        store.ingestScanBatch(ONEBOT, 1, 501, drafts);

        var first = store.observationsForThreadByObservedAt(
                "thread.observed", BASE, BASE.plusSeconds(1), 500);
        assertThat(first.items()).hasSize(500);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isPresent();
        ObservedAtCursor cursor = first.nextCursor().orElseThrow();
        var second = store.observationsForThreadByObservedAt(
                "thread.observed", BASE, BASE.plusSeconds(1), cursor, 500);
        assertThat(second.items()).hasSize(1);
        assertThat(second.hasMore()).isFalse();

        var messagePage = store.observationsForThread(
                "thread.observed", BASE, BASE.plusSeconds(1_000), 500);
        Set<String> union = new java.util.LinkedHashSet<>();
        first.items().forEach(observation -> union.add(observation.observationId()));
        second.items().forEach(observation -> union.add(observation.observationId()));
        messagePage.items().forEach(observation -> union.add(observation.observationId()));
        assertThat(union).hasSize(501);
    }

    @Test
    void correlationPairObservationsResolveBothCompleteSidesUntilRelease() {
        store = open("channels.db");
        store.observe(draft(ONEBOT, "pair-view-a", 1, "thread.pair.view", "bot-1", BASE));
        store.observe(draft(OFFICIAL, "pair-view-b", 2, "thread.pair.view", "app-1",
                BASE.plusMillis(100)));
        store.recordCorrelationPair(ONEBOT, "pair-view-a", OFFICIAL, "pair-view-b",
                CorrelationState.UNVERIFIED_MATCH);
        store.decideRoute(OFFICIAL, "pair-view-b", RouteState.EVIDENCE_ONLY,
                "itm-pair-peer", RouteReason.LATE_EVIDENCE_FROZEN_ITEM);

        var pairView = store.correlationPairObservations(ONEBOT, "pair-view-a")
                .orElseThrow();
        assertThat(pairView.first().rawSource()).isEqualTo(ONEBOT);
        assertThat(pairView.second().rawSource()).isEqualTo(OFFICIAL);
        assertThat(pairView.second().channelMessageId()).isEqualTo("pair-view-b");
        assertThat(pairView.second().routeState()).isEqualTo(RouteState.EVIDENCE_ONLY);
        assertThat(pairView.second().itemId()).contains("itm-pair-peer");
        assertThat(store.evidenceOutboxEntry(OFFICIAL, "pair-view-b")
                .orElseThrow().targetItemId()).isEqualTo("itm-pair-peer");

        store.releaseCorrelationPair(ONEBOT, "pair-view-a");
        assertThat(store.correlationPairObservations(ONEBOT, "pair-view-a")).isEmpty();
    }

    @Test
    void structuredMentionsTrustedIntentSnapshotDrivesObservationAcrossRestart() {
        Path database = tempDir.resolve("qq-channels.db");
        store = new QqChannelObservationStore(database, FIXED_CLOCK);
        store.beginBinding(ONEBOT, "thread.trust", Channel.ONEBOT, "bot-1", 100);
        IngestIntentDraft trusted = new IngestIntentDraft(
                "evt-trust-true", "thread.trust", Channel.ONEBOT, "bot-1", ONEBOT,
                "trust-true", "sender-1", Optional.of("canonical-1"),
                IdentityStatus.MAPPED, Direction.INBOUND, BASE, BASE, 10,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE, true, 1, true,
                TEST_EPOCH, RouteReason.NON_PREFERRED_EVIDENCE, null,
                ChannelAvailability.UNKNOWN, null, MatchingMode.STRICT, 4, 9,
                RouteState.EVIDENCE_ONLY, null);
        IngestIntentDraft untrusted = new IngestIntentDraft(
                "evt-trust-false", "thread.trust", Channel.ONEBOT, "bot-1", ONEBOT,
                "trust-false", "sender-1", Optional.of("canonical-1"),
                IdentityStatus.MAPPED, Direction.INBOUND, BASE, BASE, 10,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE, false, 1, true,
                TEST_EPOCH, RouteReason.NON_PREFERRED_EVIDENCE, null,
                ChannelAvailability.UNKNOWN, null, MatchingMode.STRICT, 4, 9,
                RouteState.EVIDENCE_ONLY, null);
        store.recordIngestIntent(trusted);
        store.recordIngestIntent(untrusted);
        store.recordIngestIntent(intentDraft("evt-trust-legacy", "trust-legacy", 102,
                "thread.trust", 1, true, RouteState.EVIDENCE_ONLY, null));
        assertThat(store.findIngestIntent("evt-trust-true").orElseThrow()
                .structuredMentionsTrusted()).isTrue();
        assertThat(store.findIngestIntent("evt-trust-false").orElseThrow()
                .structuredMentionsTrusted()).isFalse();
        assertThat(store.findIngestIntent("evt-trust-legacy").orElseThrow()
                .structuredMentionsTrusted()).isFalse();
        store.close();

        store = new QqChannelObservationStore(database, FIXED_CLOCK);
        assertThat(store.findIngestIntent("evt-trust-true").orElseThrow()
                .structuredMentionsTrusted()).isTrue();
        assertThat(store.promoteIngestIntent("evt-trust-true", 100)
                .observation().structuredMentionsTrusted()).isTrue();
        assertThat(store.promoteIngestIntent("evt-trust-false", 101)
                .observation().structuredMentionsTrusted()).isFalse();
        assertThat(store.promoteIngestIntent("evt-trust-legacy", 102)
                .observation().structuredMentionsTrusted()).isFalse();
    }

    @Test
    void sourceBindingGlobalPageIncludesInactiveAndPagesPastFiveHundred() {
        store = open("channels.db");
        for (int i = 1; i <= 501; i++) {
            String raw = "qq_group/bot-1/" + (10_000 + i);
            store.beginBinding(raw, "thread.bindings", Channel.ONEBOT, "bot-1", i);
            if (i % 10 == 0) {
                store.unbindSource(raw);
            }
        }

        var first = store.sourceBindings(null, 500);
        assertThat(first.items()).hasSize(500);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextRawSource()).isPresent();
        var second = store.sourceBindings(first.nextRawSource().orElseThrow(), 500);
        assertThat(second.items()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(first.items()).anySatisfy(binding -> assertThat(binding.active()).isFalse());
    }

    @Test
    void evidenceOutboxTracksOnlyExplicitEvidenceTargetsAndCompletesIdempotently() {
        store = open("channels.db");
        store.observe(draft(ONEBOT, "outbox-deferred", 1, "thread.outbox", "bot-1", BASE));
        var deferred = store.decideRoute(ONEBOT, "outbox-deferred",
                RouteState.EVIDENCE_ONLY, null, RouteReason.NON_PREFERRED_EVIDENCE);
        assertThat(deferred.observation().itemId()).isEmpty();
        assertThat(store.evidenceOutboxEntry(ONEBOT, "outbox-deferred")).isEmpty();
        assertThat(store.pendingEvidenceOutbox(10, null).items()).isEmpty();

        store.attachEvidenceItem(ONEBOT, "outbox-deferred", "itm-outbox-1");
        var pending = store.evidenceOutboxEntry(ONEBOT, "outbox-deferred").orElseThrow();
        assertThat(pending.targetItemId()).isEqualTo("itm-outbox-1");
        assertThat(pending.state()).isEqualTo(EvidenceOutboxState.PENDING);
        assertThat(store.pendingEvidenceOutbox(10, null).items()).hasSize(1);

        var applied = store.markEvidenceOutboxApplied(ONEBOT, "outbox-deferred");
        assertThat(applied.state()).isEqualTo(EvidenceOutboxState.APPLIED);
        assertThat(applied.revision()).isEqualTo(2);
        assertThat(store.pendingEvidenceOutbox(10, null).items()).isEmpty();
        var appliedReplay = store.markEvidenceOutboxApplied(ONEBOT, "outbox-deferred");
        assertThat(appliedReplay.revision()).isEqualTo(2);
        expectCode(ErrorCode.EVIDENCE_OUTBOX_STATE_CONFLICT,
                () -> store.markEvidenceOutboxTargetMissing(ONEBOT, "outbox-deferred"));

        store.observe(draft(ONEBOT, "outbox-missing", 2, "thread.outbox", "bot-1",
                BASE.plusSeconds(1)));
        store.decideRoute(ONEBOT, "outbox-missing", RouteState.EVIDENCE_ONLY,
                "itm-outbox-missing", RouteReason.LATE_EVIDENCE_FROZEN_ITEM);
        var targetMissing = store.markEvidenceOutboxTargetMissing(ONEBOT, "outbox-missing");
        assertThat(targetMissing.state()).isEqualTo(EvidenceOutboxState.TARGET_MISSING);
        assertThat(store.pendingEvidenceOutbox(10, null).items()).isEmpty();
        assertThat(store.markEvidenceOutboxTargetMissing(ONEBOT, "outbox-missing")
                .revision()).isEqualTo(2);
    }

    @Test
    void evidenceOutboxGlobalPaginationAndRestartRecoverTheSamePendingTargets() {
        Path database = tempDir.resolve("qq-channels.db");
        store = new QqChannelObservationStore(database, FIXED_CLOCK);
        store.observe(draft(ONEBOT, "outbox-page-1", 1, "thread.outbox.page", "bot-1", BASE));
        store.observe(draft(ONEBOT, "outbox-page-2", 2, "thread.outbox.page", "bot-1",
                BASE.plusSeconds(1)));
        store.observe(draft(ONEBOT, "outbox-page-3", 3, "thread.outbox.page", "bot-1",
                BASE.plusSeconds(2)));
        store.decideRoute(ONEBOT, "outbox-page-1", RouteState.EVIDENCE_ONLY,
                "itm-page-1", RouteReason.LATE_EVIDENCE_FROZEN_ITEM);
        store.decideRoute(ONEBOT, "outbox-page-2", RouteState.EVIDENCE_ONLY,
                "itm-page-2", RouteReason.LATE_EVIDENCE_FROZEN_ITEM);
        store.decideRoute(ONEBOT, "outbox-page-3", RouteState.EVIDENCE_ONLY,
                "itm-page-3", RouteReason.LATE_EVIDENCE_FROZEN_ITEM);
        store.close();

        store = new QqChannelObservationStore(database, FIXED_CLOCK);
        var first = store.pendingEvidenceOutbox(2, null);
        assertThat(first.items()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextObservationId()).isPresent();
        var second = store.pendingEvidenceOutbox(2, first.nextObservationId().orElseThrow());
        assertThat(second.items()).hasSize(1);
        assertThat(second.hasMore()).isFalse();

        store.markEvidenceOutboxApplied(
                first.items().get(0).observation().rawSource(),
                first.items().get(0).observation().channelMessageId());
        assertThat(store.pendingEvidenceOutbox(10, null).items()).hasSize(2);
    }

    @Test
    void globalIntentPaginationIncludesUnboundSourcesAndPagesPastFiveHundred() {
        store = open("channels.db");
        store.beginBinding(ONEBOT, "thread.intent.global", Channel.ONEBOT, "bot-1", 1);
        for (int i = 0; i < 501; i++) {
            store.recordIngestIntent(intentDraft("evt-global-" + i,
                    "global-intent-" + i, i + 1, "thread.intent.global", 1,
                    true, RouteState.EVIDENCE_ONLY, null));
        }
        var unbound = store.unbindSource(ONEBOT);
        assertThat(unbound.binding().active()).isFalse();

        var first = store.pendingIngestIntents(0, 500);
        assertThat(first.items()).hasSize(500);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextAfterId()).isGreaterThan(0);
        var second = store.pendingIngestIntents(first.nextAfterId(), 500);
        assertThat(second.items()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.items().get(0).rawSource()).isEqualTo(ONEBOT);
    }

    @Test
    void globalRoutePaginationIncludesUnboundThreadsAndPagesPastFiveHundred() {
        store = open("channels.db");
        store.observe(draft(ONEBOT, "global-route-a", 1, "thread.global.a", "bot-1", BASE));
        store.observe(draft(ONEBOT, "global-route-b", 2, "thread.global.b", "bot-1",
                BASE.plusMillis(10)));
        store.observe(draft(ONEBOT, "global-route-c", 3, "thread.global.c", "bot-1",
                BASE.plusMillis(20)));
        store.decideRoute(ONEBOT, "global-route-a", RouteState.EVIDENCE_ONLY, null,
                RouteReason.NON_PREFERRED_EVIDENCE);
        store.decideRoute(ONEBOT, "global-route-b", RouteState.DISPLAY_PENDING, "itm-global");
        store.decideRoute(ONEBOT, "global-route-c", RouteState.GATE_OFF, null,
                RouteReason.INJECTION_GATE_OFF);

        Set<String> seen = new java.util.LinkedHashSet<>();
        String cursor = null;
        boolean hasMore;
        do {
            var page = store.pendingRouteObservations(
                    Set.of(RouteState.EVIDENCE_ONLY, RouteState.DISPLAY_PENDING),
                    1, cursor);
            page.items().forEach(observation -> seen.add(observation.channelMessageId()));
            hasMore = page.hasMore();
            cursor = page.nextObservationId().orElse(null);
        } while (hasMore);
        assertThat(seen).containsExactlyInAnyOrder("global-route-a", "global-route-b");

        store.beginBinding(ONEBOT, "thread.global.bulk", Channel.ONEBOT, "bot-1", 1);
        List<ObservationDraft> bulk = new ArrayList<>();
        for (int i = 1; i <= 501; i++) {
            bulk.add(draft(ONEBOT, "bulk-route-" + i, i, "thread.global.bulk",
                    "bot-1", BASE.plusSeconds(i)));
        }
        store.ingestScanBatch(ONEBOT, 1, 501, bulk);

        var first = store.pendingRouteObservations(
                Set.of(RouteState.PENDING_ROUTE), 500, null);
        assertThat(first.items()).hasSize(500);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextObservationId()).isPresent();
        var second = store.pendingRouteObservations(
                Set.of(RouteState.PENDING_ROUTE), 500,
                first.nextObservationId().orElseThrow());
        assertThat(second.items()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.items().get(0).threadId()).isEqualTo("thread.global.bulk");
    }

    @Test
    void strictTimeToleranceSnapshotSurvivesConfigChangeAndRestart() {
        Path database = tempDir.resolve("qq-channels.db");
        store = new QqChannelObservationStore(database, FIXED_CLOCK);
        store.beginBinding(ONEBOT, "thread.tolerance", Channel.ONEBOT, "bot-1", 70);
        IngestIntentDraft oldConfigIntent = new IngestIntentDraft(
                "evt-tolerance", "thread.tolerance", Channel.ONEBOT, "bot-1", ONEBOT,
                "tolerance-70", "sender-1", Optional.of("canonical-1"),
                IdentityStatus.MAPPED, Direction.INBOUND, BASE, BASE, 10,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE, 1, true,
                TEST_EPOCH, RouteReason.NON_PREFERRED_EVIDENCE, null,
                ChannelAvailability.UNKNOWN, null, MatchingMode.STRICT,
                3, 7, RouteState.EVIDENCE_ONLY, null);
        store.recordIngestIntent(oldConfigIntent);
        // A newer config value on another observation must not affect the old intent.
        store.observe(new ObservationDraft(
                "thread.tolerance", Channel.ONEBOT, "bot-1", ONEBOT,
                "tolerance-new-config", 71, "sender-1",
                Optional.of("canonical-1"), IdentityStatus.MAPPED, 1,
                Direction.INBOUND, BASE, BASE, 10, TimeReliability.RELIABLE,
                ContentIntegrity.COMPLETE, 300, TEST_EPOCH, RouteReason.PENDING));
        store.close();

        store = new QqChannelObservationStore(database, FIXED_CLOCK);
        assertThat(store.findIngestIntent("evt-tolerance").orElseThrow()
                .strictTimeToleranceSeconds()).isEqualTo(3);
        var promotion = store.promoteIngestIntent("evt-tolerance", 70);
        assertThat(promotion.observation().strictTimeToleranceSeconds()).isEqualTo(3);
        assertThat(store.findObservation(ONEBOT, "tolerance-new-config").orElseThrow()
                .strictTimeToleranceSeconds()).isEqualTo(300);

        assertThatThrownBy(() -> new ObservationDraft(
                "thread.tolerance", Channel.ONEBOT, "bot-1", ONEBOT,
                "tolerance-below", 72, "sender-1", Optional.empty(),
                IdentityStatus.UNMAPPED, 0, Direction.INBOUND, BASE, BASE, 10,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE, -1,
                TEST_EPOCH, RouteReason.PENDING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObservationDraft(
                "thread.tolerance", Channel.ONEBOT, "bot-1", ONEBOT,
                "tolerance-above", 73, "sender-1", Optional.empty(),
                IdentityStatus.UNMAPPED, 0, Direction.INBOUND, BASE, BASE, 10,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE, 301,
                TEST_EPOCH, RouteReason.PENDING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void suppressedFallbackSupportsMultipleCandidatesWithoutCreatingAPair() {
        store = open("channels.db");
        store.observe(draft(ONEBOT, "fallback-source", 1, "thread.fallback", "bot-1", BASE));
        store.observe(draft(OFFICIAL, "fallback-candidate-1", 2, "thread.fallback",
                "app-1", BASE.plusMillis(10)));
        store.observe(draft(OFFICIAL, "fallback-candidate-2", 3, "thread.fallback",
                "app-1", BASE.plusMillis(20)));

        var first = store.recordSuppressedFallbackCandidates(ONEBOT, "fallback-source",
                List.of(
                        new EvidenceCandidateDraft(OFFICIAL, "fallback-candidate-1", "itm-1"),
                        new EvidenceCandidateDraft(OFFICIAL, "fallback-candidate-2", "itm-2")));
        assertThat(first.inserted()).isEqualTo(2);
        assertThat(first.unchanged()).isZero();
        assertThat(first.sourceObservation().routeState()).isEqualTo(RouteState.EVIDENCE_ONLY);
        assertThat(first.sourceObservation().routeReason())
                .isEqualTo(RouteReason.SUPPRESSED_FALLBACK_UNVERIFIED);
        assertThat(first.sourceObservation().correlationState())
                .isEqualTo(CorrelationState.UNVERIFIED);
        assertThat(first.sourceObservation().peerObservationId()).isEmpty();
        assertThat(first.candidates()).extracting(record -> record.candidateObservationId())
                .hasSize(2);
        assertThat(first.candidates()).extracting(record -> record.candidateItemId())
                .containsExactly(Optional.of("itm-1"), Optional.of("itm-2"));
        assertThat(store.findCorrelationPair(ONEBOT, "fallback-source")).isEmpty();
        assertThat(store.findObservation(OFFICIAL, "fallback-candidate-1").orElseThrow()
                .correlationState()).isEqualTo(CorrelationState.CHANNEL_ONLY);
        assertThat(store.findObservation(OFFICIAL, "fallback-candidate-2").orElseThrow()
                .correlationState()).isEqualTo(CorrelationState.CHANNEL_ONLY);
        var counts = store.statusCounts("thread.fallback");
        assertThat(counts.correlationCounts().get(CorrelationState.MATCHED)).isZero();
        assertThat(counts.correlationCounts().get(CorrelationState.UNVERIFIED)).isEqualTo(1);
        assertThat(counts.correlationCounts().get(CorrelationState.CHANNEL_ONLY)).isEqualTo(2);

        var replay = store.recordSuppressedFallbackCandidates(ONEBOT, "fallback-source",
                List.of(
                        new EvidenceCandidateDraft(OFFICIAL, "fallback-candidate-1", "itm-1"),
                        new EvidenceCandidateDraft(OFFICIAL, "fallback-candidate-2", "itm-2")));
        assertThat(replay.inserted()).isZero();
        assertThat(replay.unchanged()).isEqualTo(2);
        assertThat(replay.changed()).isFalse();
        assertThat(store.evidenceCandidatesForSource(ONEBOT, "fallback-source"))
                .hasSize(2);
    }

    @Test
    void suppressedFallbackValidatesThreadChannelEpochAndRollsBackWholeBatch() {
        store = open("channels.db");
        store.observe(draft(ONEBOT, "fallback-invalid-source", 1, "thread.fallback",
                "bot-1", BASE));
        store.observe(draft(OFFICIAL, "fallback-good", 2, "thread.fallback",
                "app-1", BASE.plusMillis(10)));
        ObservationDraft wrongEpoch = new ObservationDraft(
                "thread.fallback", Channel.QQ_OFFICIAL, "app-1", OFFICIAL,
                "fallback-wrong-epoch", 3, "sender-1",
                Optional.of("canonical-sender-1"), IdentityStatus.MAPPED, 1,
                Direction.INBOUND, BASE.plusMillis(20), BASE, 10,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE,
                "epoch-different", RouteReason.PENDING);
        store.observe(wrongEpoch);

        expectCode(ErrorCode.CORRELATION_CONFLICT,
                () -> store.recordSuppressedFallbackCandidates(
                        ONEBOT, "fallback-invalid-source",
                        List.of(
                                new EvidenceCandidateDraft(OFFICIAL, "fallback-good", "itm-good"),
                                new EvidenceCandidateDraft(
                                        OFFICIAL, "fallback-wrong-epoch", "itm-bad"))));
        assertThat(store.evidenceCandidatesForSource(
                ONEBOT, "fallback-invalid-source")).isEmpty();
        Observation unchanged = store.findObservation(
                ONEBOT, "fallback-invalid-source").orElseThrow();
        assertThat(unchanged.routeState()).isEqualTo(RouteState.PENDING_ROUTE);
        assertThat(unchanged.correlationState()).isEqualTo(CorrelationState.CHANNEL_ONLY);

        ObservationDraft sameChannel = draft(ONEBOT, "fallback-same-channel", 4,
                "thread.fallback", "bot-1", BASE.plusMillis(30));
        store.observe(sameChannel);
        expectCode(ErrorCode.CORRELATION_CONFLICT,
                () -> store.recordSuppressedFallbackCandidates(
                        ONEBOT, "fallback-invalid-source",
                        List.of(new EvidenceCandidateDraft(
                                ONEBOT, "fallback-same-channel", "itm-same"))));
        assertThat(store.evidenceCandidatesForSource(
                ONEBOT, "fallback-invalid-source")).isEmpty();

        store.observe(draft(OFFICIAL, "fallback-wrong-thread", 5, "thread.other",
                "app-1", BASE.plusMillis(40)));
        expectCode(ErrorCode.CORRELATION_CONFLICT,
                () -> store.recordSuppressedFallbackCandidates(
                        ONEBOT, "fallback-invalid-source",
                        List.of(new EvidenceCandidateDraft(
                                OFFICIAL, "fallback-wrong-thread", "itm-other"))));
        assertThat(store.evidenceCandidatesForSource(
                ONEBOT, "fallback-invalid-source")).isEmpty();
    }

    @Test
    void suppressedFallbackCandidatesAndRouteSnapshotSurviveRestart() {
        Path database = tempDir.resolve("qq-channels.db");
        store = new QqChannelObservationStore(database, FIXED_CLOCK);
        store.observe(draft(ONEBOT, "fallback-restart-source", 1, "thread.fallback",
                "bot-1", BASE));
        store.observe(draft(OFFICIAL, "fallback-restart-1", 2, "thread.fallback",
                "app-1", BASE.plusMillis(10)));
        store.observe(draft(OFFICIAL, "fallback-restart-2", 3, "thread.fallback",
                "app-1", BASE.plusMillis(20)));
        store.recordSuppressedFallbackCandidates(ONEBOT, "fallback-restart-source",
                List.of(
                        new EvidenceCandidateDraft(OFFICIAL, "fallback-restart-1", "itm-r1"),
                        new EvidenceCandidateDraft(OFFICIAL, "fallback-restart-2", "itm-r2")));
        store.close();

        store = new QqChannelObservationStore(database, FIXED_CLOCK);
        Observation recovered = store.findObservation(
                ONEBOT, "fallback-restart-source").orElseThrow();
        assertThat(recovered.routeState()).isEqualTo(RouteState.EVIDENCE_ONLY);
        assertThat(recovered.routeReason())
                .isEqualTo(RouteReason.SUPPRESSED_FALLBACK_UNVERIFIED);
        assertThat(recovered.correlationState()).isEqualTo(CorrelationState.UNVERIFIED);
        assertThat(store.findCorrelationPair(ONEBOT, "fallback-restart-source")).isEmpty();
        assertThat(store.evidenceCandidatesForSource(
                ONEBOT, "fallback-restart-source"))
                .extracting(record -> record.candidateItemId())
                .containsExactly(Optional.of("itm-r1"), Optional.of("itm-r2"));
    }

    @Test
    void pairMemberPrimaryKeyRejectsAThenBThenCAcrossBothColumns() throws Exception {
        Path database = tempDir.resolve("qq-channels.db");
        store = new QqChannelObservationStore(database, FIXED_CLOCK);
        List<ObservationDraft> triple = crossColumnTriple(store);
        ObservationDraft a = triple.get(0);
        ObservationDraft b = triple.get(1);
        ObservationDraft c = triple.get(2);
        store.observe(a);
        store.observe(b);
        store.observe(c);

        var ab = store.recordCorrelationPair(a.rawSource(), a.channelMessageId(),
                b.rawSource(), b.channelMessageId(), CorrelationState.UNVERIFIED_MATCH);
        assertThat(ab.changed()).isTrue();
        expectCode(ErrorCode.CORRELATION_CONFLICT, () -> store.recordCorrelationPair(
                b.rawSource(), b.channelMessageId(),
                c.rawSource(), c.channelMessageId(), CorrelationState.UNVERIFIED_MATCH));

        String obsA = store.observationIdFor(a.rawSource(), a.channelMessageId());
        String obsB = store.observationIdFor(b.rawSource(), b.channelMessageId());
        String obsC = store.observationIdFor(c.rawSource(), c.channelMessageId());
        assertThat(obsA.compareTo(obsB)).isLessThan(0);
        assertThat(obsB.compareTo(obsC)).isLessThan(0);

        // The old two independent UNIQUE columns would accept (A,B) and (B,C). The member table
        // must reject the second pair even when inserted directly.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.setAutoCommit(false);
            try (PreparedStatement pair = connection.prepareStatement("""
                    INSERT INTO channel_correlation_pair(
                      pair_key, observation_a_id, observation_b_id, state, revision,
                      created_at, updated_at)
                    VALUES ('direct-bc', ?, ?, 'UNVERIFIED_MATCH', 1, 'x', 'x')
                    """)) {
                pair.setString(1, obsB);
                pair.setString(2, obsC);
                pair.executeUpdate();
            }
            assertThatThrownBy(() -> {
                try (PreparedStatement member = connection.prepareStatement("""
                        INSERT INTO channel_correlation_pair_member(
                          observation_id, pair_key, created_at)
                        VALUES (?, 'direct-bc', 'x')
                        """)) {
                    member.setString(1, obsB);
                    member.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
            connection.rollback();
        }
    }

    @Test
    void correlationAuditSurvivesReleaseAndRecordsCreateStateChangeRelease() {
        store = open("channels.db");
        store.observe(draft(ONEBOT, "audit-onebot", 1, "thread.audit", "bot-1", BASE));
        store.observe(draft(OFFICIAL, "audit-official", 2, "thread.audit", "app-1",
                BASE.plusMillis(100)));

        store.recordCorrelationPair(ONEBOT, "audit-onebot", OFFICIAL, "audit-official",
                CorrelationState.SUPPRESSED_UNVERIFIED);
        store.recordCorrelationPair(ONEBOT, "audit-onebot", OFFICIAL, "audit-official",
                CorrelationState.UNVERIFIED_MATCH);
        String pairKey = store.findCorrelationPair(ONEBOT, "audit-onebot")
                .orElseThrow().pairKey();
        store.releaseCorrelationPair(ONEBOT, "audit-onebot");

        assertThat(store.findCorrelationPair(ONEBOT, "audit-onebot")).isEmpty();
        assertThat(store.correlationHistoryForPair(pairKey))
                .extracting(entry -> entry.action())
                .containsExactly(CorrelationAuditAction.CREATE,
                        CorrelationAuditAction.STATE_CHANGE,
                        CorrelationAuditAction.RELEASE);
        assertThat(store.correlationHistory(ONEBOT, "audit-onebot"))
                .hasSize(3)
                .allSatisfy(entry -> {
                    assertThat(entry.fromState()).isNotNull();
                    assertThat(entry.toState()).isNotNull();
                });
    }

    @Test
    void twoStoresRacingForTheSameObservationProduceOnePairOnly() throws Exception {
        Path database = tempDir.resolve("qq-channels.db");
        QqChannelObservationStore first = new QqChannelObservationStore(database, FIXED_CLOCK);
        QqChannelObservationStore second = new QqChannelObservationStore(database, FIXED_CLOCK);
        first.observe(draft(ONEBOT, "race-a", 1, "thread.race", "bot-1", BASE));
        first.observe(draft(OFFICIAL, "race-b", 2, "thread.race", "app-1", BASE));
        first.observe(draft(OFFICIAL, "race-c", 3, "thread.race", "app-1", BASE));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> one = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    first.recordCorrelationPair(ONEBOT, "race-a", OFFICIAL, "race-b",
                            CorrelationState.UNVERIFIED_MATCH);
                    return "created-b";
                } catch (StoreException failure) {
                    return failure.code().name();
                }
            });
            Future<String> two = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    second.recordCorrelationPair(ONEBOT, "race-a", OFFICIAL, "race-c",
                            CorrelationState.UNVERIFIED_MATCH);
                    return "created-c";
                } catch (StoreException failure) {
                    return failure.code().name();
                }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> outcomes = List.of(one.get(10, TimeUnit.SECONDS),
                    two.get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(value -> value.startsWith("created-")).toList())
                    .as("outcomes=%s", outcomes).hasSize(1);
            assertThat(outcomes).contains(ErrorCode.CORRELATION_CONFLICT.name());
            assertThat(first.findCorrelationPair(ONEBOT, "race-a")).isPresent();
        } finally {
            executor.shutdownNow();
            first.close();
            second.close();
        }
    }

    @Test
    void evidenceOnlyTargetItemIsOptionalThenSafelyAttachedOnlyOnce() {
        store = open("channels.db");
        store.observe(draft(ONEBOT, "evidence-item", 1, "thread.evidence", "bot-1", BASE));
        var evidence = store.decideRoute(ONEBOT, "evidence-item",
                RouteState.EVIDENCE_ONLY, null, RouteReason.NON_PREFERRED_EVIDENCE);
        assertThat(evidence.observation().itemId()).isEmpty();

        var attached = store.attachEvidenceItem(ONEBOT, "evidence-item", "itm-frozen");
        assertThat(attached.changed()).isTrue();
        assertThat(attached.observation().routeState()).isEqualTo(RouteState.EVIDENCE_ONLY);
        assertThat(attached.observation().itemId()).contains("itm-frozen");
        assertThat(attached.observation().routeReason())
                .isEqualTo(RouteReason.LATE_EVIDENCE_FROZEN_ITEM);
        assertThat(store.attachEvidenceItem(ONEBOT, "evidence-item", "itm-frozen").changed())
                .isFalse();
        expectCode(ErrorCode.ROUTE_CONFLICT,
                () -> store.attachEvidenceItem(ONEBOT, "evidence-item", "itm-other"));
    }

    @Test
    void negativePriorityAndUnknownSenderArePersistedButUnknownSenderCannotMatch() {
        store = open("channels.db");
        ObservationDraft lowPriority = new ObservationDraft(
                "thread.bounds", Channel.ONEBOT, "bot-1", ONEBOT, "priority-low", 1,
                "", Optional.empty(), IdentityStatus.UNMAPPED, 0, Direction.INBOUND,
                BASE, BASE, -1000, TimeReliability.RELIABLE,
                ContentIntegrity.COMPLETE, TEST_EPOCH, RouteReason.PENDING);
        var low = store.observe(lowPriority).observation();
        assertThat(low.priority()).isEqualTo(-1000);
        assertThat(low.senderId()).isEmpty();
        assertThat(low.identityStatus()).isEqualTo(IdentityStatus.UNMAPPED);

        ObservationDraft highPriority = new ObservationDraft(
                "thread.bounds", Channel.ONEBOT, "bot-1", ONEBOT, "priority-high", 2,
                "sender-1", Optional.of("canonical-1"), IdentityStatus.MAPPED, 1,
                Direction.INBOUND, BASE, BASE, 1000, TimeReliability.RELIABLE,
                ContentIntegrity.COMPLETE, TEST_EPOCH, RouteReason.PENDING);
        assertThat(store.observe(highPriority).observation().priority()).isEqualTo(1000);

        ObservationDraft below = new ObservationDraft(
                "thread.bounds", Channel.ONEBOT, "bot-1", ONEBOT, "priority-below", 3,
                "", Optional.empty(), IdentityStatus.UNMAPPED, 0, Direction.INBOUND,
                BASE, BASE, -1001, TimeReliability.RELIABLE,
                ContentIntegrity.COMPLETE, TEST_EPOCH, RouteReason.PENDING);
        expectCode(ErrorCode.INVALID_ARGUMENT, () -> store.observe(below));
        ObservationDraft above = new ObservationDraft(
                "thread.bounds", Channel.ONEBOT, "bot-1", ONEBOT, "priority-above", 4,
                "", Optional.empty(), IdentityStatus.UNMAPPED, 0, Direction.INBOUND,
                BASE, BASE, 1001, TimeReliability.RELIABLE,
                ContentIntegrity.COMPLETE, TEST_EPOCH, RouteReason.PENDING);
        expectCode(ErrorCode.INVALID_ARGUMENT, () -> store.observe(above));

        store.observe(new ObservationDraft(
                "thread.bounds", Channel.QQ_OFFICIAL, "app-1", OFFICIAL, "official-unknown",
                5, "", Optional.empty(), IdentityStatus.UNMAPPED, 0, Direction.INBOUND,
                BASE.plusMillis(100), BASE, -300, TimeReliability.RELIABLE,
                ContentIntegrity.COMPLETE, TEST_EPOCH, RouteReason.PENDING));
        expectCode(ErrorCode.CORRELATION_CONFLICT, () -> store.recordCorrelationPair(
                ONEBOT, "priority-low", OFFICIAL, "official-unknown",
                CorrelationState.MATCHED));
        var suppressed = store.recordCorrelationPair(
                ONEBOT, "priority-low", OFFICIAL, "official-unknown",
                CorrelationState.SUPPRESSED_UNVERIFIED);
        assertThat(suppressed.first().correlationState())
                .isEqualTo(CorrelationState.SUPPRESSED_UNVERIFIED);
    }

    @Test
    void intentSnapshotCarriesCanonicalSenderEpochGenerationAndRouteReasonIntoObservation() {
        store = open("channels.db");
        store.beginBinding(ONEBOT, "thread.meta", Channel.ONEBOT, "bot-1", 60);
        IngestIntentDraft full = new IngestIntentDraft(
                "evt-meta", "thread.meta", Channel.ONEBOT, "bot-1", ONEBOT, "meta-60",
                "sender-1", Optional.of("canonical-1"), IdentityStatus.MAPPED,
                Direction.INBOUND, BASE, BASE, -300, TimeReliability.FALLBACK,
                ContentIntegrity.INCOMPLETE, 1, true, TEST_EPOCH,
                RouteReason.NON_PREFERRED_EVIDENCE, null, ChannelAvailability.UNKNOWN,
                null, MatchingMode.STRICT, 7, RouteState.EVIDENCE_ONLY, null);
        store.recordIngestIntent(full);
        var promotion = store.promoteIngestIntent("evt-meta", 60);

        assertThat(promotion.observation().priority()).isEqualTo(-300);
        assertThat(promotion.observation().canonicalSenderId()).contains("canonical-1");
        assertThat(promotion.observation().identityStatus()).isEqualTo(IdentityStatus.MAPPED);
        assertThat(promotion.observation().identityRevision()).isEqualTo(7);
        assertThat(promotion.observation().bindingGeneration()).isEqualTo(1);
        assertThat(promotion.observation().bindingEpochId()).isEqualTo(TEST_EPOCH);
        assertThat(promotion.observation().ingestIntentKey()).contains("evt-meta");
        assertThat(promotion.observation().routeReason())
                .isEqualTo(RouteReason.NON_PREFERRED_EVIDENCE);
        assertThat(promotion.observation().timeReliability())
                .isEqualTo(TimeReliability.FALLBACK);
        assertThat(promotion.observation().contentIntegrity())
                .isEqualTo(ContentIntegrity.INCOMPLETE);
    }

    @Test
    void bindingEpochIsDerivedFromExplicitTupleNotFromCrossSourceGenerationEquality() {
        String epoch = QqChannelObservationStore.bindingEpochId("thread.epoch", List.of(
                new QqChannelObservationStore.ActiveBindingRef(
                        Channel.ONEBOT, "bot-1", ONEBOT, 2),
                new QqChannelObservationStore.ActiveBindingRef(
                        Channel.QQ_OFFICIAL, "app-1", OFFICIAL, 1)));
        String sameTuple = QqChannelObservationStore.bindingEpochId("thread.epoch", List.of(
                new QqChannelObservationStore.ActiveBindingRef(
                        Channel.QQ_OFFICIAL, "app-1", OFFICIAL, 1),
                new QqChannelObservationStore.ActiveBindingRef(
                        Channel.ONEBOT, "bot-1", ONEBOT, 2)));
        String changedTuple = QqChannelObservationStore.bindingEpochId("thread.epoch", List.of(
                new QqChannelObservationStore.ActiveBindingRef(
                        Channel.ONEBOT, "bot-1", ONEBOT, 2),
                new QqChannelObservationStore.ActiveBindingRef(
                        Channel.QQ_OFFICIAL, "app-1", OFFICIAL, 2)));
        assertThat(epoch).isEqualTo(sameTuple);
        assertThat(epoch).isNotEqualTo(changedTuple);
        assertThat(epoch).startsWith("epoch-");
    }

    @Test
    void schemaContainsNoBodyUrlTokenOrBodyFingerprintColumns() throws Exception {
        Path database = tempDir.resolve("qq-channels.db");
        store = open(database.getFileName().toString());
        Set<String> columns = new java.util.TreeSet<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "PRAGMA table_info(channel_observation)")) {
            while (result.next()) {
                columns.add(result.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        assertThat(columns).isNotEmpty();
        assertThat(columns).noneMatch(column -> column.contains("body")
                || column.contains("segment")
                || column.contains("url")
                || column.contains("token")
                || column.contains("secret")
                || column.contains("fingerprint")
                || column.equals("text"));
        assertThat(columns).contains("raw_source", "channel_message_id", "board_local_seq",
                "sender_id", "canonical_sender_id", "identity_status", "identity_revision",
                "binding_generation", "binding_epoch_id", "ingest_intent_key", "route_reason",
                "message_at", "observed_at", "time_reliability", "content_integrity",
                "structured_mentions_trusted", "strict_time_tolerance_seconds",
                "route_state", "route_request_key",
                "route_item_id", "correlation_state");

        Set<String> intentColumns = new java.util.TreeSet<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "PRAGMA table_info(channel_ingest_intent)")) {
            while (result.next()) {
                intentColumns.add(result.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        assertThat(intentColumns).isNotEmpty();
        assertThat(intentColumns).noneMatch(column -> column.contains("body")
                || column.contains("segment")
                || column.contains("url")
                || column.contains("token")
                || column.contains("secret")
                || column.contains("fingerprint")
                || column.equals("text"));
        assertThat(intentColumns).contains("intent_key", "account_id", "raw_source",
                "channel_message_id", "canonical_sender_id", "identity_status",
                "binding_generation", "binding_epoch_id", "gate_enabled", "route_reason",
                "preferred_channel", "preferred_channel_availability", "selected_channel",
                "matching_mode", "structured_mentions_trusted",
                "strict_time_tolerance_seconds", "identity_revision", "priority",
                "route_decision",
                "route_item_id", "state", "board_local_seq");
    }

    @Test
    void storeAndRegistryCanReuseTheSameDatabaseFileWithoutOwningEachOthersSchema() {
        Path database = tempDir.resolve("qq-channels.db");
        registry = new QqChannelRegistry(database, (channel, accountId) -> true,
                rawSource -> true, FIXED_CLOCK);
        registry.bind("thread.registry", "qq_group/bot-1/955432692");

        store = new QqChannelObservationStore(database, FIXED_CLOCK);
        store.beginBinding("qq_group/bot-1/955432692", "thread.store", Channel.ONEBOT,
                "bot-1", 100);
        store.ingestScanBatch("qq_group/bot-1/955432692", 1, 100,
                List.of(draft(ONEBOT, "shared-db", 100, "thread.store", "bot-1", BASE)));

        assertThat(store.findObservation(ONEBOT, "shared-db")).isPresent();
        assertThat(registry.status("thread.registry").orElseThrow().bindings()).hasSize(1);
        assertThat(registry.status("thread.store")).isEmpty();
    }

    private QqChannelObservationStore open(String file) {
        return new QqChannelObservationStore(tempDir.resolve(file), FIXED_CLOCK);
    }

    private static ObservationDraft draft(String rawSource,
                                          String messageId,
                                          long boardSeq,
                                          String threadId,
                                          String accountId,
                                          Instant messageAt) {
        Channel channel = rawSource.startsWith("qq_official")
                ? Channel.QQ_OFFICIAL : Channel.ONEBOT;
        return new ObservationDraft(threadId, channel, accountId, rawSource, messageId,
                boardSeq, "sender-1", Optional.of("canonical-sender-1"),
                IdentityStatus.MAPPED, 1, Direction.INBOUND, messageAt, BASE, 10,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE,
                TEST_EPOCH, RouteReason.PENDING);
    }

    private static List<ObservationDraft> crossColumnTriple(
            QqChannelObservationStore store) {
        record Candidate(String rawSource, String messageId, String accountId,
                         Channel channel, String observationId) { }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            String oneMessage = "triple-ob-" + i;
            candidates.add(new Candidate(ONEBOT, oneMessage, "bot-1", Channel.ONEBOT,
                    store.observationIdFor(ONEBOT, oneMessage)));
            String officialMessage = "triple-off-" + i;
            candidates.add(new Candidate(OFFICIAL, officialMessage, "app-1",
                    Channel.QQ_OFFICIAL,
                    store.observationIdFor(OFFICIAL, officialMessage)));
        }
        candidates.sort((left, right) ->
                left.observationId().compareTo(right.observationId()));
        for (int i = 1; i + 1 < candidates.size(); i++) {
            Candidate a = candidates.get(i - 1);
            Candidate b = candidates.get(i);
            Candidate c = candidates.get(i + 1);
            if (a.channel() == c.channel() && a.channel() != b.channel()) {
                return List.of(
                        draft(a.rawSource(), a.messageId(), 10, "thread.triple",
                                a.accountId(), BASE),
                        draft(b.rawSource(), b.messageId(), 11, "thread.triple",
                                b.accountId(), BASE.plusMillis(10)),
                        draft(c.rawSource(), c.messageId(), 12, "thread.triple",
                                c.accountId(), BASE.plusMillis(20)));
            }
        }
        throw new AssertionError("未找到跨列 A-B/B-C 测试三元组");
    }

    private static IngestIntentDraft intentDraft(String intentKey,
                                                 String messageId,
                                                 long boardSeq,
                                                 String threadId,
                                                 long bindingGeneration,
                                                 boolean gateEnabled,
                                                 RouteState route,
                                                 String itemId) {
        return new IngestIntentDraft(intentKey, threadId, Channel.ONEBOT, "bot-1",
                ONEBOT, messageId, "sender-1", Direction.INBOUND, BASE, BASE, 10,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE,
                bindingGeneration, gateEnabled, route, itemId);
    }

    private static void expectCode(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(StoreException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
