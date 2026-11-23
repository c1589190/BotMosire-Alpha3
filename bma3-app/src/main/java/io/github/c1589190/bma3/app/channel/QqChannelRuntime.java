package io.github.c1589190.bma3.app.channel;

import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.bma3.app.channel.binding.QqBindingCoordinator;
import io.github.c1589190.bma3.app.channel.matching.CrossChannelMatcher;
import io.github.c1589190.bma3.app.channel.router.QqChannelBoardPort;
import io.github.c1589190.bma3.app.channel.router.QqChannelInboxPort;
import io.github.c1589190.bma3.app.channel.router.QqChannelObservationLedger;
import io.github.c1589190.bma3.app.channel.router.QqChannelRouter;
import io.github.c1589190.bma3.app.channel.router.RegistryQqChannelControl;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore;
import io.github.c1589190.bma3.app.channel.tools.ChannelEvidenceTools;
import io.github.c1589190.bma3.app.channel.tools.ChannelMutationGate;
import io.github.c1589190.bma3.app.channel.tools.ChannelSourceCatalog;
import io.github.c1589190.bma3.app.channel.tools.ChannelToolAssembly;
import io.github.c1589190.bma3.app.channel.tools.ChannelTools;
import io.github.c1589190.bma3.app.config.AppConfig;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.core.store.MessageBoardStore;
import io.github.c1589190.bma3.inbox.core.InboxService;
import io.github.c1589190.bma3.inbox.core.InboxStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Owns the local QQ channel control plane and the shared ingress gate.
 *
 * <p>Construction opens local stores only; it never connects to either QQ service. Call
 * {@link #recoverBeforeServing()} before publishing the tools, starting an account or accepting
 * Inbox HTTP traffic. The caller owns the board, inbox and application instance lock.</p>
 */
public final class QqChannelRuntime implements AutoCloseable {

    private final QqChannelRegistry registry;
    private final QqChannelObservationStore observations;
    private final QqBindingCoordinator coordinator;
    private final QqChannelRouter router;
    private final ChannelMutationGate gate;
    private final List<AgentTool> tools;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean recovered;

    public QqChannelRuntime(Path dataDir,
                            MessageBoardStore board,
                            InboxService inbox,
                            InboxStore inboxStore,
                            KnownAccountVerifier knownAccounts,
                            ChannelAvailabilityProvider availability,
                            Supplier<AppConfig.QqOfficial.Matching> matching,
                            Clock clock) {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(inbox, "inbox");
        Objects.requireNonNull(inboxStore, "inboxStore");
        Objects.requireNonNull(knownAccounts, "knownAccounts");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(matching, "matching");
        Objects.requireNonNull(clock, "clock");

        QqChannelRegistry openedRegistry = null;
        QqChannelObservationStore openedObservations = null;
        QqBindingCoordinator openedCoordinator = null;
        try {
            Path channelDb = dataDir.resolve(QqChannelObservationStore.DATABASE_FILE_NAME);
            openedRegistry = new QqChannelRegistry(channelDb, knownAccounts,
                    raw -> board.hasInbound(SourceAddress.parse(raw)), clock,
                    () -> QqChannelRegistry.MatchingMode.fromWire(
                            Objects.requireNonNull(matching.get(), "matching").mode()),
                    availability);
            openedObservations = new QqChannelObservationStore(channelDb, clock);
            ChannelMutationGate sharedGate = ChannelMutationGate.processLocal();
            QqChannelBoardPort boardPort = QqChannelBoardPort.from(board);
            openedCoordinator = new QqBindingCoordinator(openedRegistry, openedObservations,
                    boardPort, sharedGate, dataDir.resolve("qq-channel-bindings.db"), clock);
            QqChannelRegistry activeRegistry = openedRegistry;
            QqChannelObservationStore activeObservations = openedObservations;
            QqChannelObservationLedger ledger = QqChannelObservationLedger.from(activeObservations);
            QqChannelRouter activeRouter = new QqChannelRouter(boardPort, ledger,
                    QqChannelInboxPort.from(inbox, inboxStore),
                    new RegistryQqChannelControl(activeRegistry, ledger),
                    new CrossChannelMatcher(), sharedGate, clock,
                    () -> Duration.ofSeconds(Objects.requireNonNull(matching.get(), "matching")
                            .strictTimeToleranceSeconds()),
                    threadId -> activeRegistry.status(threadId)
                            .map(status -> status.thread().label())
                            .filter(label -> !label.isBlank()).orElse(threadId));
            ChannelSourceCatalog catalog = limit -> observedSources(board, limit);
            ChannelToolAssembly assembly = ChannelTools.assembleProduction(activeRegistry, catalog,
                    sharedGate, openedCoordinator, clock);
            if (!assembly.productionReady() || assembly.gate() != sharedGate) {
                throw new IllegalStateException("QQ 渠道控制面未使用共享 gate");
            }
            List<AgentTool> activeTools = new ArrayList<>(assembly.tools());
            activeTools.addAll(ChannelEvidenceTools.all(activeRegistry, activeObservations, board));

            this.registry = openedRegistry;
            this.observations = openedObservations;
            this.coordinator = openedCoordinator;
            this.router = activeRouter;
            this.gate = sharedGate;
            this.tools = List.copyOf(activeTools);
        } catch (RuntimeException failure) {
            closeSuppressing(failure, openedCoordinator);
            closeSuppressing(failure, openedObservations);
            closeSuppressing(failure, openedRegistry);
            throw failure;
        }
    }

    /** Reconcile binding epochs, then replay durable channel/Inbox work before external access. */
    public synchronized QqChannelRouter.RecoveryReport recoverBeforeServing() {
        ensureOpen();
        if (recovered) {
            throw new IllegalStateException("QQ 渠道运行时已完成启动恢复");
        }
        coordinator.repairOrThrow();
        QqChannelRouter.RecoveryReport result = router.recover();
        recovered = true;
        return result;
    }

    /** The caller must call {@link #recoverBeforeServing()} before using this ingress. */
    public synchronized QqChannelRouter router() {
        ensureReady();
        return router;
    }

    /** Tools are unavailable until startup repair and replay have completed successfully. */
    public synchronized List<AgentTool> tools() {
        ensureReady();
        return tools;
    }

    public QqChannelRegistry registry() {
        ensureOpen();
        return registry;
    }

    public QqChannelObservationStore observations() {
        ensureOpen();
        return observations;
    }

    public QqBindingCoordinator coordinator() {
        ensureOpen();
        return coordinator;
    }

    public ChannelMutationGate gate() {
        ensureOpen();
        return gate;
    }

    public boolean ping() {
        return !closed.get() && registry.ping();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        try {
            coordinator.close();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            observations.close();
        } catch (RuntimeException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        try {
            registry.close();
        } catch (RuntimeException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("QQ 渠道运行时已关闭");
        }
    }

    private void ensureReady() {
        ensureOpen();
        if (!recovered) {
            throw new IllegalStateException("QQ 渠道启动恢复尚未完成");
        }
    }

    private static List<ChannelSourceCatalog.ObservedSource> observedSources(
            MessageBoardStore board, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        int scanLimit = Math.min(1000, Math.max(100, limit * 4));
        List<ChannelSourceCatalog.ObservedSource> result = new ArrayList<>();
        for (var thread : board.threads(scanLimit)) {
            SourceAddress source;
            try {
                source = SourceAddress.parse(thread.source());
            } catch (IllegalArgumentException invalid) {
                continue;
            }
            if (!isChannelSource(source) || !board.hasInbound(source)) {
                continue;
            }
            result.add(new ChannelSourceCatalog.ObservedSource(source.format(), thread.lastAt()));
            if (result.size() == limit) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static boolean isChannelSource(SourceAddress source) {
        return switch (source.platform()) {
            case "qq_group", "qq_private", "qq_official_group", "qq_official_private" -> true;
            default -> false;
        };
    }

    private static void closeSuppressing(Throwable primary, AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }
}
