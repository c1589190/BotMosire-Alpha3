package io.mosire.bma3.app.channel.binding;

import io.mosire.bma3.app.channel.QqChannelRegistry;
import io.mosire.bma3.app.channel.QqChannelRegistry.ActiveBindingPage;
import io.mosire.bma3.app.channel.QqChannelRegistry.ChannelBinding;
import io.mosire.bma3.app.channel.QqChannelRegistry.RegistryException;
import io.mosire.bma3.app.channel.binding.BindingJournal.Entry;
import io.mosire.bma3.app.channel.binding.BindingJournal.Operation;
import io.mosire.bma3.app.channel.binding.BindingJournal.Phase;
import io.mosire.bma3.app.channel.binding.BindingRepairReport.Blocker;
import io.mosire.bma3.app.channel.binding.BindingRepairReport.Code;
import io.mosire.bma3.app.channel.router.QqChannelBoardPort;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.BindingEvent;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.BindingEventKind;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.BindingUpdate;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.Channel;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.SourceBinding;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.SourceBindingPage;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.StoreException;
import io.mosire.bma3.app.channel.tools.ChannelMutationGate;
import io.mosire.bma3.core.source.SourceAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Crash-consistent binding coordinator for the registry and observation ledger.
 *
 * <p>The two owner stores keep independent SQLite connections and cannot share a transaction.
 * This class therefore serializes every control-plane mutation and every ingress-side binding
 * decision through the caller-provided single {@link ChannelMutationGate}, and uses a durable
 * operation journal plus replayable startup repair. It never claims the two writes are atomic.</p>
 *
 * <p><b>Bind order:</b> preflight registry conflicts and provable ledger state, read
 * {@code board.maxLocalSeq(rawSource)} under the gate, persist bound-free intent in the journal,
 * call {@code ledger.beginBinding(startSeq=max+1)}, then {@code registry.bind}. A failed registry
 * bind is compensated by unbinding the newly-created ledger generation when no registry binding
 * committed. Binding therefore affects only messages with a board sequence at or after its
 * boundary; pre-existing history is never rescanned or injected by this class.</p>
 *
 * <p><b>Unbind order:</b> {@code registry.unbind} first, then {@code ledger.unbindSource}. Existing
 * observations, Inbox items, binding history and unfinished ingest intents are not deleted.</p>
 *
 * <p><b>Unknown compensation:</b> if a failed registry bind cannot be followed by an authoritative
 * registry read (for example the SQLite connection quarantines after a commit), the coordinator
 * preserves the ledger generation and leaves the journal open with
 * {@code BIND_RECOVERY_REQUIRED}. It never treats an unreadable registry as "not bound" and never
 * guesses success.</p>
 *
 * <p><b>Repair policy:</b> ledger active + registry unbound is rolled back only after the ledger
 * history proves that the current generation's last event is its BOUND tuple. Registry active +
 * ledger missing/inactive/tuple-conflicting is a hard blocker: no start sequence is guessed.</p>
 *
 * <p>All journal data is body/credential free. This class does not ACK, dismiss or rewrite Inbox
 * items and does not call the message board append path.</p>
 */
public final class QqBindingCoordinator implements AutoCloseable {

    private static final int PAGE_SIZE = 500;
    private static final Pattern THREAD_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final RegistryPort registry;
    private final QqChannelObservationStore ledger;
    private final QqChannelBoardPort board;
    private final ChannelMutationGate mutationGate;
    private final BindingJournal journal;
    private final BindingCrashHook crashHook;
    private boolean closed;

    public QqBindingCoordinator(QqChannelRegistry registry,
                                QqChannelObservationStore ledger,
                                QqChannelBoardPort board,
                                ChannelMutationGate mutationGate,
                                Path journalDatabase,
                                Clock clock) {
        this(registry, ledger, board, mutationGate, journalDatabase, clock,
                BindingCrashHook.noop());
    }

    public QqBindingCoordinator(QqChannelRegistry registry,
                                QqChannelObservationStore ledger,
                                QqChannelBoardPort board,
                                ChannelMutationGate mutationGate,
                                Path journalDatabase,
                                Clock clock,
                                BindingCrashHook crashHook) {
        this(RegistryPort.from(registry), ledger, board, mutationGate, journalDatabase, clock,
                crashHook);
    }

    /** Test seam: production uses {@link #QqBindingCoordinator(QqChannelRegistry, ...)}. */
    QqBindingCoordinator(RegistryPort registry,
                         QqChannelObservationStore ledger,
                         QqChannelBoardPort board,
                         ChannelMutationGate mutationGate,
                         Path journalDatabase,
                         Clock clock,
                         BindingCrashHook crashHook) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.board = Objects.requireNonNull(board, "board");
        this.mutationGate = Objects.requireNonNull(mutationGate, "mutationGate");
        this.crashHook = Objects.requireNonNull(crashHook, "crashHook");
        this.journal = new BindingJournal(
                Objects.requireNonNull(journalDatabase, "journalDatabase"), clock);
    }

    /**
     * Bind a raw source to a logical thread, or return the current binding on exact replay.
     *
     * @return immutable outcome containing the ledger generation and inclusive start board seq
     */
    public BindOutcome bind(String threadId, String rawSource, String label) {
        ensureOpen();
        return mutationGate.underLock(() -> {
            try {
                return bindLocked(threadId, rawSource, label);
            } catch (BindingCoordinatorException failure) {
                auditFailure(Operation.BIND, threadId, rawSource, failure);
                throw failure;
            }
        });
    }

    public BindOutcome bind(String threadId, String rawSource) {
        return bind(threadId, rawSource, "");
    }

    /** Unbind a source from the supplied thread. Historical board/ledger facts are preserved. */
    public UnbindOutcome unbind(String threadId, String rawSource) {
        ensureOpen();
        return mutationGate.underLock(() -> {
            try {
                return unbindLocked(threadId, rawSource);
            } catch (BindingCoordinatorException failure) {
                auditFailure(Operation.UNBIND, threadId, rawSource, failure);
                throw failure;
            }
        });
    }

    /**
     * Enumerate both owner stores using their global keyset-paginated APIs, then repair every
     * discovered source under the same shared gate. Must complete before ingress/tools are opened.
     *
     * <p>Any unprovable mismatch is reported as a blocker and makes
     * {@link BindingRepairReport#readyForIngress()} false. The caller must not open the control
     * tools or message ingress until a later repair returns true.</p>
     */
    public BindingRepairReport repair() {
        ensureOpen();
        SortedSet<String> sources = new TreeSet<>();
        Map<String, Entry> openBySource = new HashMap<>();
        try {
            collectJournalSources(sources, openBySource);
            collectRegistrySources(sources);
            collectLedgerSources(sources);
        } catch (RuntimeException failure) {
            return new BindingRepairReport(0, 0, 0, 0, 0, false,
                    List.of(new Blocker("", Code.SOURCE_ENUMERATION_FAILED,
                            safeCode(failure))));
        }

        int examined = 0;
        int consistent = 0;
        int rolledBack = 0;
        int journalFinalized = 0;
        int unboundCompleted = 0;
        List<Blocker> blockers = new ArrayList<>();
        for (String rawSource : sources) {
            examined++;
            try {
                SourceRepair repair = mutationGate.underLock(
                        () -> repairSourceLocked(rawSource, openBySource.get(rawSource)));
                switch (repair.action()) {
                    case CONSISTENT -> consistent++;
                    case ROLLED_BACK_LEDGER -> rolledBack++;
                    case COMPLETED_UNBIND -> unboundCompleted++;
                    case JOURNAL_FINALIZED -> journalFinalized++;
                }
            } catch (RuntimeException failure) {
                Blocker blocker = blockerOf(rawSource, failure);
                blockers.add(blocker);
                Entry open = openBySource.get(rawSource);
                if (open != null) {
                    try {
                        journal.recordError(open.id(), blocker.code().name());
                    } catch (RuntimeException ignored) {
                        // The journal may be the failing component; the blocker is still authoritative.
                    }
                }
            }
        }
        return new BindingRepairReport(examined, consistent, rolledBack, journalFinalized,
                unboundCompleted, true, blockers);
    }

    /**
     * Repair one source under the shared gate. Intended for a tool retry/diagnostic path, not a
     * substitute for the whole {@link #repair()} before opening ingress.
     */
    public BindingRepairReport repairSource(String rawSource) {
        ensureOpen();
        Objects.requireNonNull(rawSource, "rawSource");
        return mutationGate.underLock(() -> {
            try {
                Entry open = journal.latestOpen(rawSource).orElse(null);
                SourceRepair repair = repairSourceLocked(rawSource, open);
                int rolledBack = repair.action() == RepairAction.ROLLED_BACK_LEDGER ? 1 : 0;
                int finalized = repair.action() == RepairAction.JOURNAL_FINALIZED ? 1 : 0;
                int unbound = repair.action() == RepairAction.COMPLETED_UNBIND ? 1 : 0;
                int consistent = repair.action() == RepairAction.CONSISTENT ? 1 : 0;
                return new BindingRepairReport(1, consistent, rolledBack, finalized, unbound,
                        true, List.of());
            } catch (RuntimeException failure) {
                return new BindingRepairReport(1, 0, 0, 0, 0, true,
                        List.of(blockerOf(rawSource, failure)));
            }
        });
    }

    /**
     * Run {@link #repair()} and fail closed when any source remains blocked or enumeration was
     * incomplete. This is the explicit gate the composition owner should use before enabling
     * ingress or control tools.
     */
    public BindingRepairReport repairOrThrow() {
        BindingRepairReport report = repair();
        if (!report.readyForIngress()) {
            throw new BindingCoordinatorException(BindingCoordinatorException.Code.REPAIR_BLOCKED,
                    "binding repair 未就绪: examined=" + report.sourcesExamined()
                            + ", blockers=" + report.blockerCount()
                            + ", enumerationComplete=" + report.enumerationComplete());
        }
        return report;
    }

    /** Body-free coordinator audit, newest first. */
    public List<AuditEntry> audits(int limit) {
        ensureOpen();
        return journal.audits(limit).stream()
                .map(entry -> new AuditEntry(entry.id(), entry.operation().name(),
                        entry.phase().name(), entry.rawSource(), entry.threadId(),
                        entry.channel(), entry.accountId(), entry.startBoardSeq(),
                        entry.errorCode(), entry.createdAt(), entry.updatedAt()))
                .toList();
    }

    private BindOutcome bindLocked(String threadId, String rawSource, String label) {
        validateThreadId(threadId);
        RawSource source = parseRawSource(rawSource);
        String normalizedLabel = label == null ? "" : label.strip();

        finishOpenJournalLocked(rawSource);

        Optional<ChannelBinding> registryBinding = activeRegistryBinding(rawSource);
        Optional<SourceBinding> ledgerBinding = ledger.sourceBinding(rawSource);

        if (registryBinding.isPresent()) {
            ChannelBinding current = registryBinding.orElseThrow();
            assertRegistryHasUsableLedger(current, ledgerBinding.orElse(null), rawSource);
            if (!current.threadId().equals(threadId)) {
                throw new BindingCoordinatorException(
                        BindingCoordinatorException.Code.SOURCE_ALREADY_BOUND,
                        "raw source 已绑定到另一个 logical thread");
            }
            // Exact replay: never move the ledger boundary or create a new generation. Registry
            // bind remains the authority for label validation/update and idempotent audit.
            QqChannelRegistry.BindResult repeated;
            try {
                repeated = registry.bind(threadId, rawSource, source.channel(), normalizedLabel);
            } catch (RegistryException failure) {
                throw new BindingCoordinatorException(
                        BindingCoordinatorException.Code.REGISTRY_BIND_FAILED,
                        "registry.bind 拒绝重复绑定: " + failure.code(), failure);
            }
            assertLiveConsistent(rawSource, threadId);
            SourceBinding persisted = ledger.sourceBinding(rawSource).orElseThrow();
            return new BindOutcome(rawSource, threadId, persisted.generation(),
                    persisted.startBoardSeq(), repeated.changed());
        }

        if (ledgerBinding.filter(SourceBinding::active).isPresent()) {
            // Registry is unbound while ledger is active. This can only be an interrupted bind or
            // an interrupted unbind. History proves the exact tuple; roll the ledger back rather
            // than guessing a registry start sequence.
            SourceBinding orphan = ledgerBinding.orElseThrow();
            assertLedgerHistoryProvesBinding(orphan);
            rollbackLedgerOrphanLocked(orphan);
        }

        assertNoThreadChannelOccupant(threadId, source.storeChannel(), rawSource);

        long priorLedgerGeneration = ledger.sourceBinding(rawSource)
                .map(SourceBinding::generation).orElse(0L);
        long boardMax;
        try {
            boardMax = board.maxLocalSeq(SourceAddress.parse(rawSource));
        } catch (RuntimeException failure) {
            throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.BOARD_WATERMARK_UNAVAILABLE,
                    "无法读取 source 的 board maxLocalSeq，未写入任何绑定", failure);
        }
        if (boardMax < 0 || boardMax == Long.MAX_VALUE) {
            throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.BOARD_WATERMARK_UNAVAILABLE,
                    "board maxLocalSeq 非法或已耗尽，未写入任何绑定");
        }
        long startBoardSeq = boardMax + 1;

        long journalId;
        try {
            journalId = journal.create(Operation.BIND, rawSource, threadId, source.channel(),
                    source.accountId(), startBoardSeq);
        } catch (RuntimeException failure) {
            throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.JOURNAL_FAILED,
                    "写入 binding journal 失败，未写入任何绑定", failure);
        }

        try {
            BindingUpdate ledgerWrite = ledger.beginBinding(rawSource, threadId,
                    source.storeChannel(), source.accountId(), startBoardSeq);
            journal.update(journalId, Phase.LEDGER_BOUND, null);
            crashHook.at(BindingCrashHook.CrashPoint.AFTER_LEDGER_BIND);

            QqChannelRegistry.BindResult registryWrite;
            try {
                registryWrite = registry.bind(threadId, rawSource, source.channel(),
                        normalizedLabel);
            } catch (RegistryException failure) {
                throw new BindingCoordinatorException(
                        BindingCoordinatorException.Code.REGISTRY_BIND_FAILED,
                        "registry.bind 失败: " + failure.code(), failure);
            }
            assertLiveConsistent(rawSource, threadId);

            journal.update(journalId, Phase.REGISTRY_BOUND, null);
            crashHook.at(BindingCrashHook.CrashPoint.AFTER_REGISTRY_BIND);
            journal.update(journalId, Phase.COMPLETE, null);

            SourceBinding persisted = ledger.sourceBinding(rawSource).orElseThrow();
            return new BindOutcome(rawSource, threadId, persisted.generation(),
                    persisted.startBoardSeq(), ledgerWrite.changed() || registryWrite.changed());
        } catch (RuntimeException failure) {
            return compensateFailedBind(journalId, rawSource, threadId, source,
                    priorLedgerGeneration, failure);
        }
    }

    private BindOutcome compensateFailedBind(long journalId,
                                             String rawSource,
                                             String threadId,
                                             RawSource source,
                                             long priorLedgerGeneration,
                                             RuntimeException failure) {
        Lookup<ChannelBinding> registryNow = lookupActiveRegistry(rawSource);
        if (registryNow.state() == LookupState.UNKNOWN) {
            // The bind may have committed and the registry connection may now be quarantined.
            // Treating this as "not bound" and unbinding the ledger would manufacture a permanent
            // registry-active/ledger-inactive blocker. Preserve both live stores and the open
            // journal for a later, readable repair pass.
            throw preserveForRecovery(journalId,
                    "registry 提交结果未知（读取失败）；保留 ledger 与 open journal", failure);
        }
        if (registryNow.state() == LookupState.FOUND) {
            // Registry bind committed (or another writer owns the active tuple). Never roll back
            // the ledger on this path; startup repair can verify the pair when reads are healthy.
            throw preserveForRecovery(journalId,
                    "registry binding 已存在但协调完成未确认；请先运行 repair", failure);
        }

        Lookup<SourceBinding> ledgerNow = lookupActiveLedger(rawSource);
        if (ledgerNow.state() == LookupState.UNKNOWN) {
            throw preserveForRecovery(journalId,
                    "ledger 状态读取失败；不能把未知当成功补偿", failure);
        }
        if (ledgerNow.state() == LookupState.FOUND) {
            SourceBinding active = ledgerNow.value();
            boolean ours = active.threadId().equals(threadId)
                    && active.channel() == source.storeChannel()
                    && active.accountId().equals(source.accountId())
                    && active.generation() > priorLedgerGeneration;
            if (!ours) {
                throw preserveForRecovery(journalId,
                        "ledger active generation 不属于本次 bind，禁止补偿解绑", failure);
            }
            try {
                ledger.unbindSource(rawSource);
            } catch (RuntimeException compensationFailure) {
                throw preserveForRecovery(journalId,
                        "ledger 补偿失败；请先运行 repair", compensationFailure);
            }
            Lookup<SourceBinding> afterCompensation = lookupActiveLedger(rawSource);
            if (afterCompensation.state() != LookupState.ABSENT) {
                throw preserveForRecovery(journalId,
                        "ledger 补偿后无法确认 inactive；请先运行 repair", compensationFailureOr(
                                afterCompensation.failure(), failure));
            }
        }

        try {
            journal.update(journalId, Phase.COMPENSATED,
                    failure instanceof BindingCoordinatorException coordinator
                            ? coordinator.code().name() : "BIND_FAILED");
        } catch (RuntimeException journalFailure) {
            throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.BIND_RECOVERY_REQUIRED,
                    "live stores 已补偿但 journal 完成未确认；请先运行 repair", journalFailure);
        }
        if (failure instanceof BindingCoordinatorException coordinator) {
            throw coordinator;
        }
        if (failure instanceof RegistryException registryFailure) {
            throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.REGISTRY_BIND_FAILED,
                    "registry.bind 失败: " + registryFailure.code(), failure);
        }
        if (failure instanceof StoreException storeFailure) {
            throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.LEDGER_BIND_FAILED,
                    "ledger.beginBinding 失败: " + storeFailure.code(), failure);
        }
        throw new BindingCoordinatorException(
                BindingCoordinatorException.Code.BIND_RECOVERY_REQUIRED,
                "bind 未完成，live stores 已补偿但调用结果不确定；请先运行 repair", failure);
    }

    private BindingCoordinatorException preserveForRecovery(long journalId,
                                                            String detail,
                                                            Throwable failure) {
        try {
            journal.recordError(journalId,
                    BindingCoordinatorException.Code.BIND_RECOVERY_REQUIRED.name());
        } catch (RuntimeException ignored) {
            // Leave the already-open journal row unchanged; it still identifies the source.
        }
        return new BindingCoordinatorException(
                BindingCoordinatorException.Code.BIND_RECOVERY_REQUIRED, detail, failure);
    }

    private static RuntimeException compensationFailureOr(RuntimeException first,
                                                          RuntimeException fallback) {
        return first == null ? fallback : first;
    }

    private UnbindOutcome unbindLocked(String threadId, String rawSource) {
        validateThreadId(threadId);
        RawSource source = parseRawSource(rawSource);
        finishOpenJournalLocked(rawSource);

        Optional<ChannelBinding> registryBinding = activeRegistryBinding(rawSource);
        Optional<SourceBinding> ledgerBinding = ledger.sourceBinding(rawSource);

        if (registryBinding.isPresent()) {
            ChannelBinding current = registryBinding.orElseThrow();
            if (!current.threadId().equals(threadId)) {
                throw new BindingCoordinatorException(
                        BindingCoordinatorException.Code.SOURCE_ALREADY_BOUND,
                        "raw source 已绑定到另一个 logical thread");
            }
            assertRegistryHasUsableLedger(current, ledgerBinding.orElse(null), rawSource);
        } else if (ledgerBinding.filter(SourceBinding::active).isEmpty()) {
            return new UnbindOutcome(rawSource, threadId, false, false, false);
        } else if (!ledgerBinding.orElseThrow().threadId().equals(threadId)) {
            throw blocker(Code.REGISTRY_LEDGER_TUPLE_CONFLICT,
                    "registry 未绑定时 ledger orphan 属于另一 thread，禁止猜测性解绑");
        }

        SourceBinding ledgerActive = ledgerBinding.filter(SourceBinding::active).orElse(null);
        ChannelBinding registryActive = registryBinding.orElse(null);
        Channel storeChannel = registryActive != null
                ? storeChannel(registryActive.channel()) : ledgerActive.channel();
        String accountId = registryActive != null ? registryActive.accountId() : ledgerActive.accountId();
        String journalThread = registryActive != null ? registryActive.threadId() : threadId;

        long journalId;
        try {
            journalId = journal.create(Operation.UNBIND, rawSource, journalThread,
                    registryChannelOf(storeChannel), accountId, null);
        } catch (RuntimeException failure) {
            throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.JOURNAL_FAILED,
                    "写入 unbind journal 失败，未写入任何解绑", failure);
        }

        try {
            boolean registryChanged = false;
            if (registryActive != null) {
                QqChannelRegistry.UnbindResult write = registry.unbind(journalThread, rawSource);
                registryChanged = write.changed();
                Optional<ChannelBinding> after = activeRegistryBinding(rawSource);
                if (after.isPresent()) {
                    throw new BindingCoordinatorException(
                            BindingCoordinatorException.Code.UNBIND_RECOVERY_REQUIRED,
                            "registry.unbind 返回后 source 仍为 active");
                }
            }
            journal.update(journalId, Phase.REGISTRY_UNBOUND, null);
            crashHook.at(BindingCrashHook.CrashPoint.AFTER_REGISTRY_UNBIND);

            boolean ledgerChanged = false;
            Optional<SourceBinding> currentLedger = ledger.sourceBinding(rawSource);
            if (currentLedger.filter(SourceBinding::active).isPresent()) {
                SourceBinding current = currentLedger.orElseThrow();
                if (ledgerActive != null && !sameTuple(current, ledgerActive)) {
                    throw new BindingCoordinatorException(
                            BindingCoordinatorException.Code.UNBIND_RECOVERY_REQUIRED,
                            "registry 已解绑但 ledger active tuple 非预期；禁止猜测性解绑");
                }
                BindingUpdate write = ledger.unbindSource(rawSource);
                ledgerChanged = write.changed();
            }
            journal.update(journalId, Phase.LEDGER_UNBOUND, null);
            crashHook.at(BindingCrashHook.CrashPoint.AFTER_LEDGER_UNBIND);

            if (ledger.sourceBinding(rawSource).filter(SourceBinding::active).isPresent()) {
                throw new BindingCoordinatorException(
                        BindingCoordinatorException.Code.UNBIND_RECOVERY_REQUIRED,
                        "ledger.unbind 返回后 source 仍为 active");
            }
            journal.update(journalId, Phase.COMPLETE, null);
            return new UnbindOutcome(rawSource, journalThread,
                    registryChanged || ledgerChanged, ledgerChanged, true);
        } catch (RuntimeException failure) {
            try {
                journal.update(journalId, Phase.REGISTRY_UNBOUND,
                        BindingCoordinatorException.Code.UNBIND_RECOVERY_REQUIRED.name());
            } catch (RuntimeException ignored) {
                // The open journal row remains; startup repair can replay both steps idempotently.
            }
            if (failure instanceof BindingCoordinatorException coordinator) {
                throw coordinator;
            }
            if (failure instanceof RegistryException registryFailure) {
                throw new BindingCoordinatorException(
                        BindingCoordinatorException.Code.UNBIND_RECOVERY_REQUIRED,
                        "registry.unbind 失败: " + registryFailure.code(), failure);
            }
            if (failure instanceof StoreException storeFailure) {
                throw new BindingCoordinatorException(
                        BindingCoordinatorException.Code.UNBIND_RECOVERY_REQUIRED,
                        "ledger.unbind 失败: " + storeFailure.code(), failure);
            }
            throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.UNBIND_RECOVERY_REQUIRED,
                    "unbind 未确认；请先运行 repair", failure);
        }
    }

    /**
     * Process one source. The caller must hold the shared mutation gate.
     */
    private SourceRepair repairSourceLocked(String rawSource, Entry openJournal) {
        Optional<ChannelBinding> registryBinding;
        Optional<SourceBinding> ledgerBinding;
        try {
            registryBinding = activeRegistryBinding(rawSource);
            ledgerBinding = ledger.sourceBinding(rawSource);
        } catch (RuntimeException failure) {
            throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.STORAGE_ERROR,
                    "读取 registry/ledger 失败: " + safeCode(failure), failure);
        }

        if (registryBinding.isPresent()) {
            ChannelBinding current = registryBinding.orElseThrow();
            SourceBinding persisted = ledgerBinding.orElse(null);
            if (persisted == null) {
                throw blocker(Code.REGISTRY_ACTIVE_LEDGER_MISSING,
                        "registry active 但 ledger missing，不能猜 startBoardSeq");
            }
            if (!persisted.active()) {
                throw blocker(Code.REGISTRY_ACTIVE_LEDGER_INACTIVE,
                        "registry active 但 ledger generation inactive，不能猜 startBoardSeq");
            }
            if (!sameTuple(current, persisted)) {
                throw blocker(Code.REGISTRY_LEDGER_TUPLE_CONFLICT,
                        "registry/ledger tuple 冲突，不能猜 startBoardSeq");
            }
            if (openJournal != null && openJournal.operation() == Operation.UNBIND) {
                if (!openJournal.threadId().equals(current.threadId())) {
                    throw blocker(Code.REGISTRY_LEDGER_TUPLE_CONFLICT,
                            "未完成 unbind journal 与 registry thread 不一致");
                }
                // The durable intent is unbind. Replaying registry -> ledger is safe and idempotent.
                registry.unbind(current.threadId(), rawSource);
                if (activeRegistryBinding(rawSource).isPresent()) {
                    throw blocker(Code.REPAIR_WRITE_FAILED,
                            "repair registry.unbind 后仍 active");
                }
                ledger.unbindSource(rawSource);
                if (ledger.sourceBinding(rawSource).filter(SourceBinding::active).isPresent()) {
                    throw blocker(Code.REPAIR_WRITE_FAILED,
                            "repair ledger.unbind 后仍 active");
                }
                journal.closeOpenForSource(rawSource, Phase.COMPLETE, null);
                return new SourceRepair(RepairAction.COMPLETED_UNBIND);
            }
            if (openJournal != null) {
                journal.closeOpenForSource(rawSource, Phase.COMPLETE, null);
                return new SourceRepair(RepairAction.JOURNAL_FINALIZED);
            }
            return new SourceRepair(RepairAction.CONSISTENT);
        }

        // Registry has no active binding. Ledger active is a provable orphan from a crash between
        // ledger.beginBinding and registry.bind, or between registry.unbind and ledger.unbind.
        if (ledgerBinding.filter(SourceBinding::active).isPresent()) {
            SourceBinding orphan = ledgerBinding.orElseThrow();
            assertLedgerHistoryProvesBinding(orphan);
            rollbackLedgerOrphanLocked(orphan);
            if (openJournal != null) {
                if (openJournal.operation() == Operation.UNBIND) {
                    journal.closeOpenForSource(rawSource, Phase.COMPLETE, null);
                    return new SourceRepair(RepairAction.COMPLETED_UNBIND);
                }
                journal.closeOpenForSource(rawSource, Phase.COMPENSATED, null);
                return new SourceRepair(RepairAction.ROLLED_BACK_LEDGER);
            }
            return new SourceRepair(RepairAction.ROLLED_BACK_LEDGER);
        }

        if (openJournal != null) {
            journal.closeOpenForSource(rawSource, Phase.COMPLETE, null);
            return new SourceRepair(RepairAction.JOURNAL_FINALIZED);
        }
        return new SourceRepair(RepairAction.CONSISTENT);
    }

    private void finishOpenJournalLocked(String rawSource) {
        Optional<Entry> open;
        try {
            open = journal.latestOpen(rawSource);
        } catch (RuntimeException failure) {
            throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.JOURNAL_FAILED,
                    "读取未完成 binding journal 失败", failure);
        }
        if (open.isEmpty()) {
            return;
        }
        try {
            repairSourceLocked(rawSource, open.orElseThrow());
        } catch (RuntimeException failure) {
            if (failure instanceof BindingCoordinatorException coordinator) {
                throw coordinator;
            }
            throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.REPAIR_BLOCKED,
                    "未完成 binding 需先 repair", failure);
        }
    }

    private void assertRegistryHasUsableLedger(ChannelBinding registryBinding,
                                               SourceBinding ledgerBinding,
                                               String rawSource) {
        if (ledgerBinding == null) {
            throw blocker(Code.REGISTRY_ACTIVE_LEDGER_MISSING,
                    "registry active 但 ledger missing，不能猜 startBoardSeq");
        }
        if (!ledgerBinding.active()) {
            throw blocker(Code.REGISTRY_ACTIVE_LEDGER_INACTIVE,
                    "registry active 但 ledger generation inactive，不能猜 startBoardSeq");
        }
        if (!sameTuple(registryBinding, ledgerBinding)) {
            throw blocker(Code.REGISTRY_LEDGER_TUPLE_CONFLICT,
                    "registry/ledger tuple 冲突，不能猜 startBoardSeq");
        }
    }

    private void assertLiveConsistent(String rawSource, String expectedThread) {
        ChannelBinding registryBinding = activeRegistryBinding(rawSource).orElseThrow(() ->
                new BindingCoordinatorException(
                        BindingCoordinatorException.Code.BIND_RECOVERY_REQUIRED,
                        "registry.bind 后缺少 active binding"));
        SourceBinding ledgerBinding = ledger.sourceBinding(rawSource).orElseThrow(() ->
                new BindingCoordinatorException(
                        BindingCoordinatorException.Code.BIND_RECOVERY_REQUIRED,
                        "ledger.beginBinding 后缺少 binding"));
        if (!registryBinding.threadId().equals(expectedThread)
                || !ledgerBinding.active() || !sameTuple(registryBinding, ledgerBinding)) {
            throw blocker(Code.REGISTRY_LEDGER_TUPLE_CONFLICT,
                    "bind 后 registry/ledger 不一致");
        }
    }

    private void assertLedgerHistoryProvesBinding(SourceBinding binding) {
        List<BindingEvent> history;
        try {
            history = ledger.bindingHistory(binding.rawSource());
        } catch (RuntimeException failure) {
            throw blocker(Code.SOURCE_READ_FAILED,
                    "读取 ledger binding history 失败: " + safeCode(failure));
        }
        if (history.isEmpty()) {
            throw blocker(Code.LEDGER_HISTORY_UNPROVABLE,
                    "ledger active 但无 binding history，不能猜测性回滚");
        }
        BindingEvent last = history.getLast();
        if (last.kind() != BindingEventKind.BOUND
                || last.generation() != binding.generation()
                || !last.threadId().equals(binding.threadId())
                || last.channel() != binding.channel()
                || !last.accountId().equals(binding.accountId())
                || last.startBoardSeq() != binding.startBoardSeq()) {
            throw blocker(Code.LEDGER_HISTORY_UNPROVABLE,
                    "ledger active 与最后一条 BOUND 历史不一致，不能猜测性回滚");
        }
    }

    private void rollbackLedgerOrphanLocked(SourceBinding orphan) {
        try {
            ledger.unbindSource(orphan.rawSource());
        } catch (RuntimeException failure) {
            throw blocker(Code.REPAIR_WRITE_FAILED,
                    "ledger orphan 回滚失败: " + safeCode(failure));
        }
        Optional<SourceBinding> after = ledger.sourceBinding(orphan.rawSource());
        if (after.filter(SourceBinding::active).isPresent()) {
            throw blocker(Code.REPAIR_WRITE_FAILED,
                    "ledger orphan 回滚后仍 active");
        }
    }

    private void assertNoThreadChannelOccupant(String threadId,
                                               Channel channel,
                                               String rawSource) {
        Optional<QqChannelRegistry.LogicalThread> thread = registry.thread(threadId);
        if (thread.isEmpty()) {
            return;
        }
        for (ChannelBinding existing : registry.bindings(threadId)) {
            if (existing.active()
                    && storeChannel(existing.channel()) == channel
                    && !existing.rawSource().equals(rawSource)) {
                throw new BindingCoordinatorException(
                        BindingCoordinatorException.Code.THREAD_CHANNEL_ALREADY_BOUND,
                        "该 logical thread 的该 channel 已有 active binding");
            }
        }
    }

    private Optional<ChannelBinding> activeRegistryBinding(String rawSource) {
        return registry.binding(rawSource).filter(ChannelBinding::active);
    }

    private Lookup<ChannelBinding> lookupActiveRegistry(String rawSource) {
        try {
            Optional<ChannelBinding> value = registry.binding(rawSource)
                    .filter(ChannelBinding::active);
            return value.map(binding -> new Lookup<>(LookupState.FOUND, binding, null))
                    .orElseGet(() -> new Lookup<>(LookupState.ABSENT, null, null));
        } catch (RuntimeException failure) {
            return new Lookup<>(LookupState.UNKNOWN, null, failure);
        }
    }

    private Lookup<SourceBinding> lookupActiveLedger(String rawSource) {
        try {
            Optional<SourceBinding> value = ledger.sourceBinding(rawSource)
                    .filter(SourceBinding::active);
            return value.map(binding -> new Lookup<>(LookupState.FOUND, binding, null))
                    .orElseGet(() -> new Lookup<>(LookupState.ABSENT, null, null));
        } catch (RuntimeException failure) {
            return new Lookup<>(LookupState.UNKNOWN, null, failure);
        }
    }

    private void collectJournalSources(SortedSet<String> sources,
                                       Map<String, Entry> openBySource) {
        long cursor = 0L;
        while (true) {
            BindingJournal.Page page = journal.openPage(cursor, PAGE_SIZE);
            for (Entry entry : page.items()) {
                sources.add(entry.rawSource());
                Entry existing = openBySource.get(entry.rawSource());
                if (existing == null || entry.id() > existing.id()) {
                    openBySource.put(entry.rawSource(), entry);
                }
            }
            if (!page.hasMore()) {
                break;
            }
            long next = page.nextAfterId().orElseThrow(() ->
                    new IllegalStateException("journal page 缺 next cursor"));
            if (next <= cursor) {
                throw new IllegalStateException("journal cursor 未推进");
            }
            cursor = next;
        }
    }

    private void collectRegistrySources(SortedSet<String> sources) {
        String cursor = null;
        while (true) {
            ActiveBindingPage page = registry.activeBindings(cursor, PAGE_SIZE);
            for (ChannelBinding binding : page.items()) {
                sources.add(binding.rawSource());
            }
            if (!page.hasMore()) {
                break;
            }
            String next = page.nextRawSource().orElseThrow(() ->
                    new IllegalStateException("registry active binding page 缺 next cursor"));
            if (cursor != null && next.compareTo(cursor) <= 0) {
                throw new IllegalStateException("registry active binding cursor 未推进");
            }
            cursor = next;
        }
    }

    private void collectLedgerSources(SortedSet<String> sources) {
        String cursor = null;
        while (true) {
            SourceBindingPage page = ledger.sourceBindings(cursor, PAGE_SIZE);
            for (SourceBinding binding : page.items()) {
                sources.add(binding.rawSource());
            }
            if (!page.hasMore()) {
                break;
            }
            String next = page.nextRawSource().orElseThrow(() ->
                    new IllegalStateException("ledger source binding page 缺 next cursor"));
            if (cursor != null && next.compareTo(cursor) <= 0) {
                throw new IllegalStateException("ledger source binding cursor 未推进");
            }
            cursor = next;
        }
    }

    private static boolean sameTuple(ChannelBinding registryBinding,
                                     SourceBinding ledgerBinding) {
        return registryBinding.threadId().equals(ledgerBinding.threadId())
                && registryBinding.accountId().equals(ledgerBinding.accountId())
                && storeChannel(registryBinding.channel()) == ledgerBinding.channel()
                && registryBinding.active() == ledgerBinding.active();
    }

    private static boolean sameTuple(SourceBinding first, SourceBinding second) {
        return first.rawSource().equals(second.rawSource())
                && first.threadId().equals(second.threadId())
                && first.channel() == second.channel()
                && first.accountId().equals(second.accountId())
                && first.generation() == second.generation();
    }

    private static Channel storeChannel(QqChannelRegistry.Channel channel) {
        return channel == QqChannelRegistry.Channel.ONEBOT
                ? Channel.ONEBOT : Channel.QQ_OFFICIAL;
    }

    private static QqChannelRegistry.Channel registryChannelOf(Channel channel) {
        return channel == Channel.ONEBOT
                ? QqChannelRegistry.Channel.ONEBOT : QqChannelRegistry.Channel.QQ_OFFICIAL;
    }

    private static void validateThreadId(String threadId) {
        if (threadId == null || !THREAD_ID.matcher(threadId).matches()) {
            throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.INVALID_ARGUMENT,
                    "threadId 只允许 [A-Za-z0-9._-]，长度 1..128");
        }
    }

    private static RawSource parseRawSource(String rawSource) {
        if (rawSource == null) {
            throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.INVALID_ARGUMENT, "rawSource 不能为空");
        }
        String[] parts = rawSource.split("/", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank()
                || parts[2].isBlank()) {
            throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.INVALID_ARGUMENT,
                    "rawSource 必须为 platform/account/target");
        }
        Channel channel = switch (parts[0]) {
            case "qq_group", "qq_private", "qq_system" -> Channel.ONEBOT;
            case "qq_official_group", "qq_official_private", "qq_official_system" ->
                    Channel.QQ_OFFICIAL;
            default -> throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.INVALID_ARGUMENT,
                    "未知 rawSource platform");
        };
        QqChannelRegistry.Channel registryChannel = channel == Channel.ONEBOT
                ? QqChannelRegistry.Channel.ONEBOT : QqChannelRegistry.Channel.QQ_OFFICIAL;
        return new RawSource(rawSource, registryChannel, channel, parts[1]);
    }

    private static BindingCoordinatorException blocker(Code code, String detail) {
        return new BindingCoordinatorException(exceptionCode(code), detail);
    }

    private static BindingCoordinatorException.Code exceptionCode(Code code) {
        return switch (code) {
            case REGISTRY_ACTIVE_LEDGER_MISSING ->
                    BindingCoordinatorException.Code.REGISTRY_ACTIVE_LEDGER_MISSING;
            case REGISTRY_ACTIVE_LEDGER_INACTIVE ->
                    BindingCoordinatorException.Code.REGISTRY_ACTIVE_LEDGER_INACTIVE;
            case REGISTRY_LEDGER_TUPLE_CONFLICT ->
                    BindingCoordinatorException.Code.REGISTRY_LEDGER_TUPLE_CONFLICT;
            case LEDGER_HISTORY_UNPROVABLE ->
                    BindingCoordinatorException.Code.LEDGER_HISTORY_UNPROVABLE;
            case SOURCE_ENUMERATION_FAILED ->
                    BindingCoordinatorException.Code.SOURCE_ENUMERATION_FAILED;
            case SOURCE_READ_FAILED, INVALID_SOURCE, REPAIR_WRITE_FAILED ->
                    BindingCoordinatorException.Code.STORAGE_ERROR;
        };
    }

    private static Blocker blockerOf(String rawSource, RuntimeException failure) {
        if (failure instanceof BindingCoordinatorException coordinator) {
            Code code = switch (coordinator.code()) {
                case REGISTRY_ACTIVE_LEDGER_MISSING -> Code.REGISTRY_ACTIVE_LEDGER_MISSING;
                case REGISTRY_ACTIVE_LEDGER_INACTIVE -> Code.REGISTRY_ACTIVE_LEDGER_INACTIVE;
                case REGISTRY_LEDGER_TUPLE_CONFLICT -> Code.REGISTRY_LEDGER_TUPLE_CONFLICT;
                case LEDGER_HISTORY_UNPROVABLE -> Code.LEDGER_HISTORY_UNPROVABLE;
                case SOURCE_ENUMERATION_FAILED -> Code.SOURCE_ENUMERATION_FAILED;
                case INVALID_ARGUMENT -> Code.INVALID_SOURCE;
                default -> Code.REPAIR_WRITE_FAILED;
            };
            String detail = coordinator.getMessage() == null ? "" : coordinator.getMessage();
            return new Blocker(rawSource, code, detail);
        }
        return new Blocker(rawSource, Code.REPAIR_WRITE_FAILED, safeCode(failure));
    }

    private void auditFailure(Operation operation,
                              String threadId,
                              String rawSource,
                              BindingCoordinatorException failure) {
        try {
            RawSource parsed = parseRawSource(rawSource);
            long id = journal.create(operation, rawSource,
                    threadId == null ? "" : threadId, parsed.channel(), parsed.accountId(), null);
            journal.update(id, Phase.FAILED, failure.code().name());
        } catch (RuntimeException ignored) {
            // If the journal itself is unavailable there is no safe place to add this audit row;
            // the original typed failure remains authoritative and fail-closed.
        }
    }

    private static String safeCode(Throwable failure) {
        if (failure instanceof RegistryException registryFailure) {
            return "registry:" + registryFailure.code().name();
        }
        if (failure instanceof StoreException storeFailure) {
            return "ledger:" + storeFailure.code().name();
        }
        return failure.getClass().getSimpleName();
    }

    private void ensureOpen() {
        if (closed) {
            throw new BindingCoordinatorException(
                    BindingCoordinatorException.Code.STORAGE_ERROR,
                    "binding coordinator 已关闭");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        journal.close();
    }

    private record RawSource(String value,
                            QqChannelRegistry.Channel channel,
                            Channel storeChannel,
                            String accountId) {
        RawSource {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(storeChannel, "storeChannel");
            Objects.requireNonNull(accountId, "accountId");
        }
    }

    private enum RepairAction {
        CONSISTENT,
        ROLLED_BACK_LEDGER,
        JOURNAL_FINALIZED,
        COMPLETED_UNBIND
    }

    private record SourceRepair(RepairAction action) {
        SourceRepair {
            Objects.requireNonNull(action, "action");
        }
    }

    public record BindOutcome(
            String rawSource,
            String threadId,
            long generation,
            long startBoardSeq,
            boolean changed) {
        public BindOutcome {
            Objects.requireNonNull(rawSource, "rawSource");
            Objects.requireNonNull(threadId, "threadId");
            if (generation < 1 || startBoardSeq < 0) {
                throw new IllegalArgumentException("binding boundary 非法");
            }
        }
    }

    public record UnbindOutcome(
            String rawSource,
            String threadId,
            boolean changed,
            boolean ledgerUnbound,
            boolean complete) {
        public UnbindOutcome {
            Objects.requireNonNull(rawSource, "rawSource");
            Objects.requireNonNull(threadId, "threadId");
        }
    }

    /** Body-free coordinator audit projection. */
    public record AuditEntry(
            long id,
            String operation,
            String phase,
            String rawSource,
            String threadId,
            String channel,
            String accountId,
            Optional<Long> startBoardSeq,
            Optional<String> errorCode,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {
        public AuditEntry {
            startBoardSeq = startBoardSeq == null ? Optional.empty() : startBoardSeq;
            errorCode = errorCode == null ? Optional.empty() : errorCode;
        }
    }

    private enum LookupState {
        FOUND,
        ABSENT,
        UNKNOWN
    }

    private record Lookup<T>(LookupState state, T value, RuntimeException failure) {
        Lookup {
            Objects.requireNonNull(state, "state");
        }
    }

}
