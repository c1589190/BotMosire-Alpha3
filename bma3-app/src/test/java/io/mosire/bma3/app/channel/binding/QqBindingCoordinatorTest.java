package io.mosire.bma3.app.channel.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.bma3.app.channel.QqChannelRegistry;
import io.mosire.bma3.app.channel.QqChannelRegistry.ActiveBindingPage;
import io.mosire.bma3.app.channel.QqChannelRegistry.BindResult;
import io.mosire.bma3.app.channel.QqChannelRegistry.Channel;
import io.mosire.bma3.app.channel.QqChannelRegistry.ChannelBinding;
import io.mosire.bma3.app.channel.QqChannelRegistry.LogicalThread;
import io.mosire.bma3.app.channel.QqChannelRegistry.UnbindResult;
import io.mosire.bma3.app.channel.binding.BindingCrashHook.CrashPoint;
import io.mosire.bma3.app.channel.binding.QqBindingCoordinator.BindOutcome;
import io.mosire.bma3.app.channel.router.QqChannelBoardPort;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.BindingEventKind;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.ContentIntegrity;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.Direction;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.IngestIntentDraft;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.RouteState;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.TimeReliability;
import io.mosire.bma3.app.channel.tools.ChannelMutationGate;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QqBindingCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String THREAD = "thread.one";
    private static final String OTHER_THREAD = "thread.two";
    private static final String ONEBOT = "qq_group/bot-1/955432692";
    private static final String ONEBOT_B = "qq_group/bot-2/955432693";
    private static final String OFFICIAL = "qq_official_group/app-1/group_openid_A";

    @TempDir
    Path tempDir;

    private Fixture fixture;

    @AfterEach
    void close() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void bindUsesBoardHighWatermarkAndNeverBackfillsHistory() {
        fixture = open();
        long oldSeq = fixture.append(ONEBOT, "old-1");

        BindOutcome outcome = fixture.coordinator.bind(THREAD, ONEBOT, "QQ群 测试群");

        assertThat(outcome.generation()).isEqualTo(1);
        assertThat(outcome.startBoardSeq()).isEqualTo(oldSeq + 1);
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().active()).isTrue();
        assertThat(fixture.registry.binding(ONEBOT).orElseThrow().active()).isTrue();
        assertThat(fixture.ledger.findObservation(ONEBOT, "old-1")).isEmpty();
        assertThat(fixture.ledger.bindingHistory(ONEBOT))
                .extracting(event -> event.kind())
                .containsExactly(BindingEventKind.BOUND);

        long newSeq = fixture.append(ONEBOT, "new-1");
        assertThat(newSeq).isGreaterThanOrEqualTo(outcome.startBoardSeq());
        assertThat(fixture.coordinator.repair().readyForIngress()).isTrue();
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().generation()).isEqualTo(1);
    }

    @Test
    void repeatedBindIsIdempotentAndDoesNotCreateGeneration() {
        fixture = open();
        BindOutcome first = fixture.coordinator.bind(THREAD, ONEBOT, "same");
        BindOutcome replay = fixture.coordinator.bind(THREAD, ONEBOT, "same");

        assertThat(replay.generation()).isEqualTo(first.generation());
        assertThat(replay.startBoardSeq()).isEqualTo(first.startBoardSeq());
        assertThat(replay.changed()).isFalse();
        assertThat(fixture.ledger.bindingHistory(ONEBOT)).hasSize(1);
        assertThat(fixture.registry.bindings(THREAD)).hasSize(1);
    }

    @Test
    void conflictingBindDoesNotDamageEitherStoreAndIsAudited() {
        fixture = open();
        fixture.coordinator.bind(THREAD, ONEBOT, "original");

        assertThatThrownBy(() -> fixture.coordinator.bind(OTHER_THREAD, ONEBOT, "steal"))
                .isInstanceOfSatisfying(BindingCoordinatorException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                BindingCoordinatorException.Code.SOURCE_ALREADY_BOUND));

        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().threadId()).isEqualTo(THREAD);
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().generation()).isEqualTo(1);
        assertThat(fixture.registry.binding(ONEBOT).orElseThrow().threadId()).isEqualTo(THREAD);
        assertThat(fixture.coordinator.audits(20))
                .anySatisfy(audit -> {
                    assertThat(audit.phase()).isEqualTo("FAILED");
                    assertThat(audit.errorCode()).contains("SOURCE_ALREADY_BOUND");
                    assertThat(audit.rawSource()).isEqualTo(ONEBOT);
                });
    }

    @Test
    void sameThreadChannelOccupancyRejectedBeforeLedgerMutation() {
        fixture = open();
        fixture.coordinator.bind(THREAD, ONEBOT, "first");

        assertThatThrownBy(() -> fixture.coordinator.bind(THREAD, ONEBOT_B, "second"))
                .isInstanceOfSatisfying(BindingCoordinatorException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                BindingCoordinatorException.Code.THREAD_CHANNEL_ALREADY_BOUND));

        assertThat(fixture.ledger.sourceBinding(ONEBOT_B)).isEmpty();
        assertThat(fixture.registry.binding(ONEBOT_B)).isEmpty();
        assertThat(fixture.registry.bindings(THREAD)).hasSize(1);
    }

    @Test
    void oneAndTwoAccountBindingsStayIndependentAndRepairHasNoBlocker() {
        fixture = open();
        BindOutcome onebot = fixture.coordinator.bind(THREAD, ONEBOT, "group");
        BindOutcome official = fixture.coordinator.bind(THREAD, OFFICIAL, "official");

        assertThat(onebot.generation()).isEqualTo(1);
        assertThat(official.generation()).isEqualTo(1);
        assertThat(fixture.registry.bindings(THREAD)).hasSize(2);
        assertThat(fixture.registry.binding(ONEBOT).orElseThrow().accountId()).isEqualTo("bot-1");
        assertThat(fixture.registry.binding(OFFICIAL).orElseThrow().accountId()).isEqualTo("app-1");

        BindingRepairReport report = fixture.coordinator.repair();
        assertThat(report.readyForIngress()).isTrue();
        assertThat(report.sourcesExamined()).isGreaterThanOrEqualTo(2);
        assertThat(report.blockers()).isEmpty();
    }

    @Test
    void unbindThenRebindCreatesNewGenerationAndPreservesObservationAndHistory() {
        fixture = open();
        BindOutcome first = fixture.coordinator.bind(THREAD, ONEBOT, "first");
        var observation = fixture.ledger.observe(new QqChannelObservationStore.ObservationDraft(
                THREAD, io.mosire.bma3.app.channel.store.QqChannelObservationStore.Channel.ONEBOT,
                "bot-1", ONEBOT, "m-old", 1, "sender", Direction.INBOUND,
                NOW, NOW, 10, TimeReliability.RELIABLE, ContentIntegrity.COMPLETE)).observation();

        QqBindingCoordinator.UnbindOutcome unbound = fixture.coordinator.unbind(THREAD, ONEBOT);
        assertThat(unbound.changed()).isTrue();
        assertThat(unbound.ledgerUnbound()).isTrue();
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().active()).isFalse();

        BindOutcome rebound = fixture.coordinator.bind(THREAD, ONEBOT, "second");
        assertThat(rebound.generation()).isEqualTo(first.generation() + 1);
        assertThat(rebound.startBoardSeq()).isGreaterThanOrEqualTo(first.startBoardSeq());
        assertThat(fixture.ledger.findObservation(ONEBOT, "m-old").orElseThrow().observationId())
                .isEqualTo(observation.observationId());
        assertThat(fixture.ledger.bindingHistory(ONEBOT))
                .extracting(event -> event.kind())
                .containsExactly(BindingEventKind.BOUND, BindingEventKind.UNBOUND,
                        BindingEventKind.BOUND);
    }

    @Test
    void unbindPreservesUnfinishedIngestIntent() {
        fixture = open();
        BindOutcome bound = fixture.coordinator.bind(THREAD, ONEBOT, "intent");
        fixture.ledger.recordIngestIntent(new IngestIntentDraft(
                "intent-key-1", THREAD,
                io.mosire.bma3.app.channel.store.QqChannelObservationStore.Channel.ONEBOT,
                "bot-1", ONEBOT, "m-intent", "sender", Direction.INBOUND, NOW, NOW, 10,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE, bound.generation(), true,
                RouteState.EVIDENCE_ONLY, null));

        fixture.coordinator.unbind(THREAD, ONEBOT);

        assertThat(fixture.ledger.findIngestIntent("intent-key-1")).isPresent();
        assertThat(fixture.ledger.ingestIntentCounts(ONEBOT).observationCommitted()).isZero();
        assertThat(fixture.ledger.ingestIntentCounts(ONEBOT).recorded()).isEqualTo(1);
    }

    @Test
    void crashAfterLedgerBindRestartsAndRepairRollsBackOrphanThenRebindUsesNextGeneration()
            throws Exception {
        fixture = open(new ScriptedHook(CrashPoint.AFTER_LEDGER_BIND));
        long oldSeq = fixture.append(ONEBOT, "old");
        assertThatThrownBy(() -> fixture.coordinator.bind(THREAD, ONEBOT, "crash"))
                .isInstanceOf(SimulatedCrash.class);
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().active()).isTrue();
        assertThat(fixture.registry.binding(ONEBOT).filter(
                QqChannelRegistry.ChannelBinding::active)).isEmpty();

        restart();

        BindingRepairReport report = fixture.coordinator.repair();
        assertThat(report.readyForIngress()).isTrue();
        assertThat(report.ledgerRolledBack()).isEqualTo(1);
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().active()).isFalse();
        assertThat(fixture.registry.binding(ONEBOT).filter(
                QqChannelRegistry.ChannelBinding::active)).isEmpty();

        BindOutcome rebound = fixture.coordinator.bind(THREAD, ONEBOT, "retry");
        assertThat(rebound.generation()).isEqualTo(2);
        assertThat(rebound.startBoardSeq()).isEqualTo(oldSeq + 1);
    }

    @Test
    void crashAfterRegistryBindRestartsAndRepairCreatesNoExtraGeneration() throws Exception {
        fixture = open(new ScriptedHook(CrashPoint.AFTER_REGISTRY_BIND));
        assertThatThrownBy(() -> fixture.coordinator.bind(THREAD, ONEBOT, "crash"))
                .isInstanceOf(SimulatedCrash.class);
        long generation = fixture.ledger.sourceBinding(ONEBOT).orElseThrow().generation();
        assertThat(fixture.registry.binding(ONEBOT).orElseThrow().active()).isTrue();

        restart();

        BindingRepairReport report = fixture.coordinator.repair();
        assertThat(report.readyForIngress()).isTrue();
        assertThat(report.journalFinalized()).isEqualTo(1);
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().generation())
                .isEqualTo(generation);
        assertThat(fixture.ledger.bindingHistory(ONEBOT)).hasSize(1);
    }

    @Test
    void crashAfterRegistryUnbindRestartsAndRepairCompletesUnbindWithoutLosingIntent()
            throws Exception {
        fixture = open();
        BindOutcome bound = fixture.coordinator.bind(THREAD, ONEBOT, "crash-unbind");
        fixture.ledger.recordIngestIntent(new IngestIntentDraft(
                "intent-survives-unbind", THREAD,
                io.mosire.bma3.app.channel.store.QqChannelObservationStore.Channel.ONEBOT,
                "bot-1", ONEBOT, "m-intent-2", "sender", Direction.INBOUND, NOW, NOW, 10,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE, bound.generation(), true,
                RouteState.EVIDENCE_ONLY, null));
        fixture.hook.set(CrashPoint.AFTER_REGISTRY_UNBIND);

        assertThatThrownBy(() -> fixture.coordinator.unbind(THREAD, ONEBOT))
                .isInstanceOf(SimulatedCrash.class);
        assertThat(fixture.registry.binding(ONEBOT).filter(
                QqChannelRegistry.ChannelBinding::active)).isEmpty();
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().active()).isTrue();

        restart();

        BindingRepairReport report = fixture.coordinator.repair();
        assertThat(report.readyForIngress()).isTrue();
        assertThat(report.unboundCompleted()).isEqualTo(1);
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().active()).isFalse();
        assertThat(fixture.ledger.findIngestIntent("intent-survives-unbind")).isPresent();
    }

    @Test
    void crashAfterLedgerUnbindRestartsToConsistentState() throws Exception {
        fixture = open();
        fixture.coordinator.bind(THREAD, ONEBOT, "crash-ledger-unbind");
        fixture.hook.set(CrashPoint.AFTER_LEDGER_UNBIND);

        assertThatThrownBy(() -> fixture.coordinator.unbind(THREAD, ONEBOT))
                .isInstanceOf(SimulatedCrash.class);
        assertThat(fixture.registry.binding(ONEBOT).filter(
                QqChannelRegistry.ChannelBinding::active)).isEmpty();
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().active()).isFalse();

        restart();

        BindingRepairReport report = fixture.coordinator.repair();
        assertThat(report.readyForIngress()).isTrue();
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().active()).isFalse();
        assertThat(fixture.registry.binding(ONEBOT).filter(
                QqChannelRegistry.ChannelBinding::active)).isEmpty();
    }

    @Test
    void registryActiveWithLedgerMissingFailsClosedWithoutGuessingStartSeq() {
        fixture = open();
        fixture.registry.bind(THREAD, ONEBOT, Channel.ONEBOT, "direct");

        BindingRepairReport report = fixture.coordinator.repair();

        assertThat(report.readyForIngress()).isFalse();
        assertThat(report.blockers())
                .extracting(BindingRepairReport.Blocker::code)
                .contains(BindingRepairReport.Code.REGISTRY_ACTIVE_LEDGER_MISSING);
        assertThat(fixture.ledger.sourceBinding(ONEBOT)).isEmpty();
        assertThat(fixture.registry.binding(ONEBOT).orElseThrow().active()).isTrue();
    }

    @Test
    void registryActiveWithTupleConflictFailsClosedWithoutMutation() {
        fixture = open();
        fixture.registry.bind(THREAD, ONEBOT, Channel.ONEBOT, "registry");
        fixture.ledger.beginBinding(ONEBOT, OTHER_THREAD,
                io.mosire.bma3.app.channel.store.QqChannelObservationStore.Channel.ONEBOT,
                "bot-1", 10);

        BindingRepairReport report = fixture.coordinator.repair();

        assertThat(report.readyForIngress()).isFalse();
        assertThat(report.blockers())
                .extracting(BindingRepairReport.Blocker::code)
                .contains(BindingRepairReport.Code.REGISTRY_LEDGER_TUPLE_CONFLICT);
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().threadId())
                .isEqualTo(OTHER_THREAD);
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().active()).isTrue();
    }

    @Test
    void repairPagesMoreThanFiveHundredLedgerSources() {
        fixture = open();
        List<String> sources = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            String source = "qq_group/bot-1/" + (10_000 + i);
            sources.add(source);
            fixture.ledger.beginBinding(source, "thread.orphan." + i,
                    io.mosire.bma3.app.channel.store.QqChannelObservationStore.Channel.ONEBOT,
                    "bot-1", 1);
        }

        BindingRepairReport report = fixture.coordinator.repair();

        assertThat(report.readyForIngress()).isTrue();
        assertThat(report.sourcesExamined()).isGreaterThanOrEqualTo(501);
        assertThat(report.ledgerRolledBack()).isEqualTo(501);
        assertThat(fixture.ledger.sourceBinding(sources.get(0)).orElseThrow().active()).isFalse();
        assertThat(fixture.ledger.sourceBinding(sources.get(500)).orElseThrow().active()).isFalse();
    }

    @Test
    void commitAfterThrowWithUnreadableRegistryKeepsLedgerAndRecoversOnRestart() throws Exception {
        fixture = open();
        FaultyRegistryPort faulty = new FaultyRegistryPort(fixture.registry);
        faulty.commitThenThrow = true;
        fixture.replaceCoordinator(faulty);

        assertThatThrownBy(() -> fixture.coordinator.bind(THREAD, ONEBOT, "commit-then-throw"))
                .isInstanceOfSatisfying(BindingCoordinatorException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                BindingCoordinatorException.Code.BIND_RECOVERY_REQUIRED));

        // The registry commit is real, but its post-commit read is quarantined. The coordinator
        // must preserve the ledger generation and the open journal instead of inventing a
        // registry-active/ledger-inactive mismatch.
        assertThat(fixture.registry.binding(ONEBOT).orElseThrow().active()).isTrue();
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().active()).isTrue();
        long generation = fixture.ledger.sourceBinding(ONEBOT).orElseThrow().generation();
        assertThat(fixture.coordinator.audits(20))
                .noneSatisfy(audit -> assertThat(audit.phase()).isEqualTo("COMPENSATED"));

        restart();

        BindingRepairReport report = fixture.coordinator.repair();
        assertThat(report.readyForIngress()).isTrue();
        assertThat(report.journalFinalized()).isEqualTo(1);
        assertThat(report.ledgerRolledBack()).isZero();
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().active()).isTrue();
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().generation())
                .isEqualTo(generation);
        assertThat(fixture.registry.binding(ONEBOT).orElseThrow().active()).isTrue();
    }

    @Test
    void uncommittedThrowWithUnreadableRegistryDoesNotFalselyCompensateOrReportSuccess()
            throws Exception {
        fixture = open();
        FaultyRegistryPort faulty = new FaultyRegistryPort(fixture.registry);
        faulty.throwBeforeCommit = true;
        fixture.replaceCoordinator(faulty);

        assertThatThrownBy(() -> fixture.coordinator.bind(THREAD, ONEBOT, "uncommitted"))
                .isInstanceOfSatisfying(BindingCoordinatorException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                BindingCoordinatorException.Code.BIND_RECOVERY_REQUIRED));

        assertThat(fixture.registry.binding(ONEBOT)
                .filter(ChannelBinding::active)).isEmpty();
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().active()).isTrue();
        assertThat(fixture.coordinator.audits(20))
                .noneSatisfy(audit -> assertThat(audit.phase()).isEqualTo("COMPENSATED"));

        restart();

        BindingRepairReport report = fixture.coordinator.repair();
        assertThat(report.readyForIngress()).isTrue();
        assertThat(report.ledgerRolledBack()).isEqualTo(1);
        assertThat(fixture.ledger.sourceBinding(ONEBOT).orElseThrow().active()).isFalse();
        assertThat(fixture.registry.binding(ONEBOT)
                .filter(ChannelBinding::active)).isEmpty();
    }

    @Test
    void concurrentBindThroughSameGateConvergesToOneGeneration() throws Exception {
        fixture = open();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<BindOutcome> first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return fixture.coordinator.bind(THREAD, ONEBOT, "concurrent");
            });
            Future<BindOutcome> second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return fixture.coordinator.bind(THREAD, ONEBOT, "concurrent");
            });
            start.countDown();
            BindOutcome one = first.get(10, TimeUnit.SECONDS);
            BindOutcome two = second.get(10, TimeUnit.SECONDS);

            assertThat(one.generation()).isEqualTo(1);
            assertThat(two.generation()).isEqualTo(1);
            assertThat(fixture.ledger.bindingHistory(ONEBOT)).hasSize(1);
            assertThat(fixture.registry.bindings(THREAD)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture open() {
        return open(new ScriptedHook(null));
    }

    private Fixture open(ScriptedHook hook) {
        fixture = new Fixture(tempDir, hook);
        return fixture;
    }

    private void restart() throws Exception {
        fixture.close();
        fixture = new Fixture(tempDir, new ScriptedHook(null));
    }

    private static final class ScriptedHook implements BindingCrashHook {
        private final AtomicReference<CrashPoint> crashAt;

        ScriptedHook(CrashPoint crashAt) {
            this.crashAt = new AtomicReference<>(crashAt);
        }

        void set(CrashPoint point) {
            crashAt.set(point);
        }

        @Override
        public void at(CrashPoint point) {
            if (point == crashAt.get()) {
                throw new SimulatedCrash(point);
            }
        }
    }

    private static final class FaultyRegistryPort implements RegistryPort {
        private final RegistryPort delegate;
        volatile boolean throwBeforeCommit;
        volatile boolean commitThenThrow;
        volatile boolean readsThrow;

        FaultyRegistryPort(QqChannelRegistry registry) {
            this.delegate = RegistryPort.from(registry);
        }

        @Override
        public java.util.Optional<ChannelBinding> binding(String rawSource) {
            if (readsThrow) {
                throw new IllegalStateException("registry read unavailable");
            }
            return delegate.binding(rawSource);
        }

        @Override
        public BindResult bind(String threadId, String rawSource,
                               QqChannelRegistry.Channel expectedChannel, String label) {
            if (throwBeforeCommit) {
                readsThrow = true;
                throw new IllegalStateException("uncommitted registry failure");
            }
            BindResult result = delegate.bind(threadId, rawSource, expectedChannel, label);
            if (commitThenThrow) {
                readsThrow = true;
                throw new IllegalStateException("registry committed then failed");
            }
            return result;
        }

        @Override
        public UnbindResult unbind(String threadId, String rawSource) {
            return delegate.unbind(threadId, rawSource);
        }

        @Override
        public java.util.Optional<LogicalThread> thread(String threadId) {
            return delegate.thread(threadId);
        }

        @Override
        public List<ChannelBinding> bindings(String threadId) {
            return delegate.bindings(threadId);
        }

        @Override
        public ActiveBindingPage activeBindings(String afterRawSource, int limit) {
            return delegate.activeBindings(afterRawSource, limit);
        }
    }

    private static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;

        SimulatedCrash(CrashPoint point) {
            super("simulated crash at " + point);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final SqliteMessageBoardStore board;
        final QqChannelObservationStore ledger;
        final QqChannelRegistry registry;
        final ChannelMutationGate gate;
        final ScriptedHook hook;
        final Path journalDb;
        QqBindingCoordinator coordinator;

        Fixture(Path directory, ScriptedHook hook) {
            this(directory, hook, null);
        }

        Fixture(Path directory, ScriptedHook hook, RegistryPort registryPort) {
            Path boardDb = directory.resolve("board.db");
            Path registryDb = directory.resolve("registry.db");
            Path ledgerDb = directory.resolve("ledger.db");
            journalDb = directory.resolve("binding-journal.db");
            Set<String> known = new HashSet<>();
            known.add("onebot:bot-1");
            known.add("onebot:bot-2");
            known.add("qq_official:app-1");
            board = new SqliteMessageBoardStore(boardDb);
            ledger = new QqChannelObservationStore(ledgerDb, CLOCK);
            registry = new QqChannelRegistry(registryDb,
                    (channel, accountId) -> known.contains(channel.wireName() + ":" + accountId),
                    rawSource -> false, CLOCK);
            gate = ChannelMutationGate.processLocal();
            this.hook = hook;
            coordinator = new QqBindingCoordinator(
                    registryPort == null ? RegistryPort.from(registry) : registryPort,
                    ledger, QqChannelBoardPort.from(board), gate, journalDb, CLOCK, hook);
        }

        void replaceCoordinator(RegistryPort port) {
            coordinator.close();
            coordinator = new QqBindingCoordinator(port, ledger,
                    QqChannelBoardPort.from(board), gate,
                    tempJournalPath(), CLOCK, hook);
        }

        private Path tempJournalPath() {
            return journalDb;
        }

        long append(String rawSource, String messageId) {
            return board.append(new InboundMessage(SourceAddress.parse(rawSource), messageId,
                    NOW, List.of(new Segment("text", messageId)),
                    io.mosire.bma3.core.message.Direction.INBOUND, "sender", "sender"))
                    .message().localSeq();
        }

        @Override
        public void close() {
            coordinator.close();
            board.close();
            ledger.close();
            registry.close();
        }
    }
}
