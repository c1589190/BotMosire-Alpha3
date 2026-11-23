package io.github.c1589190.bma3.app.official;

import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.bma3.app.config.AppConfig;
import io.github.c1589190.bma3.app.priority.PriorityResolver;
import io.github.c1589190.bma3.core.message.TextSendPolicy;
import io.github.c1589190.bma3.core.source.SourceLabels;
import io.github.c1589190.bma3.core.store.MessageBoardStore;
import io.github.c1589190.bma3.inbox.core.InboxService;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountClientFactory;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountConfig;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountEventSink;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountRegistry;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountRuntimeOptions;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountStatus;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqReconcileResult;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqApiException;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqApiSendTransport;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqErrorKind;
import io.github.c1589190.bma3.qqofficial.credential.OfficialQqCredentialPathPolicy;
import io.github.c1589190.bma3.qqofficial.directory.OfficialQqDirectoryStore;
import io.github.c1589190.bma3.qqofficial.eventstore.OfficialQqEventReferenceStore;
import io.github.c1589190.bma3.qqofficial.readtools.OfficialQqAccountResolver;
import io.github.c1589190.bma3.qqofficial.readtools.OfficialQqAccountView;
import io.github.c1589190.bma3.qqofficial.readtools.OfficialQqReadApi;
import io.github.c1589190.bma3.qqofficial.readtools.OfficialQqReadApiProvider;
import io.github.c1589190.bma3.qqofficial.readtools.OfficialQqReadContext;
import io.github.c1589190.bma3.qqofficial.readtools.OfficialQqReadTools;
import io.github.c1589190.bma3.qqofficial.send.OfficialQqRegistrySendAccounts;
import io.github.c1589190.bma3.qqofficial.send.OfficialQqReplySequenceStore;
import io.github.c1589190.bma3.qqofficial.send.OfficialQqSendService;
import io.github.c1589190.bma3.qqofficial.tools.QqOfficialReplyTool;
import io.github.c1589190.bma3.qqofficial.tools.QqOfficialSendTextTool;
import io.github.c1589190.bma3.qqofficial.tools.QqOfficialShareQrTools;
import io.github.c1589190.bma3.qqofficial.tools.QqOfficialSourceKeyer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * App-side composition for the official QQ raw channel.
 *
 * <p>This service owns the official desired-state registry, the board-first ingest pipeline, the
 * official directory, read/share tools and one bounded credential-reconcile timer. Construction
 * performs no credential read and no network I/O; {@link #start()} resolves only enabled accounts.
 * Disabled or absent accounts never create an {@link
 * io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountClient}.</p>
 *
 * <p>The environment is captured from the caller (normally the {@code Bma3App.start(config, env,
 * root)} argument), never from a hidden {@code System.getenv()} call. The periodic reconcile is
 * deliberately idempotent: the registry compares config and secret fingerprints and leaves
 * unchanged accounts running, so a file scan does not cause reconnect churn.</p>
 */
public final class OfficialQqService implements AutoCloseable {

    public static final Duration DEFAULT_RECONCILE_INTERVAL = Duration.ofSeconds(5);

    private static final Logger LOG = LoggerFactory.getLogger(OfficialQqService.class);

    @FunctionalInterface
    public interface EnvironmentSource {
        Map<String, String> current();
    }

    private final Path dataDir;
    private final OfficialQqDirectoryStore directory;
    private final OfficialQqEventReferenceStore eventReferenceStore;
    private final OfficialQqReplySequenceStore replySequenceStore;
    private final OfficialQqAccountRegistry registry;
    private final OfficialQqIngest ingest;
    private final OfficialQqSendService sendService;
    private final OfficialQqReadContext readContext;
    private final List<AgentTool> tools;
    private final ScheduledExecutorService reconcileScheduler;
    private final Duration reconcileInterval;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Serializes config publication, periodic ticks and close; registry reads stay lock-free. */
    private final ReentrantLock reconcileLock = new ReentrantLock(true);
    private final AtomicReference<AppConfig> textPolicyConfig = new AtomicReference<>();
    private volatile AppConfig config;
    private volatile String lastReconcileError = "";
    private volatile long reconcileCount;

    public OfficialQqService(
            AppConfig initialConfig,
            Map<String, String> env,
            MessageBoardStore board,
            OfficialQqDirectoryStore directory,
            InboxService inbox,
            PriorityResolver priorities,
            SourceLabels sourceLabels,
            OfficialQqAccountClientFactory clientFactory,
            OfficialQqCredentialPathPolicy credentialPathPolicy) {
        this(initialConfig, env == null ? Map.of() : Map.copyOf(env), board, directory, inbox,
                priorities, sourceLabels, clientFactory, credentialPathPolicy,
                DEFAULT_RECONCILE_INTERVAL);
    }

    /** Production channel router seam: the handler must be ready before this service is started. */
    public OfficialQqService(
            AppConfig initialConfig,
            Map<String, String> env,
            MessageBoardStore board,
            OfficialQqDirectoryStore directory,
            InboxService inbox,
            PriorityResolver priorities,
            SourceLabels sourceLabels,
            OfficialQqAccountClientFactory clientFactory,
            OfficialQqCredentialPathPolicy credentialPathPolicy,
            OfficialQqIngest.PreBoardIngressHandler ingressHandler) {
        this(initialConfig, env, board, directory, inbox, priorities, sourceLabels, clientFactory,
                credentialPathPolicy, DEFAULT_RECONCILE_INTERVAL, ingressHandler);
    }

    /** Test/embedded seam with an explicit bounded reconcile interval. */
    public OfficialQqService(
            AppConfig initialConfig,
            Map<String, String> env,
            MessageBoardStore board,
            OfficialQqDirectoryStore directory,
            InboxService inbox,
            PriorityResolver priorities,
            SourceLabels sourceLabels,
            OfficialQqAccountClientFactory clientFactory,
            OfficialQqCredentialPathPolicy credentialPathPolicy,
            Duration reconcileInterval) {
        this(initialConfig, env, board, directory, inbox, priorities, sourceLabels, clientFactory,
                credentialPathPolicy, reconcileInterval, null);
    }

    private OfficialQqService(
            AppConfig initialConfig,
            Map<String, String> env,
            MessageBoardStore board,
            OfficialQqDirectoryStore directory,
            InboxService inbox,
            PriorityResolver priorities,
            SourceLabels sourceLabels,
            OfficialQqAccountClientFactory clientFactory,
            OfficialQqCredentialPathPolicy credentialPathPolicy,
            Duration reconcileInterval,
            OfficialQqIngest.PreBoardIngressHandler ingressHandler) {
        this.config = Objects.requireNonNull(initialConfig, "initialConfig");
        this.textPolicyConfig.set(initialConfig);
        this.dataDir = initialConfig.dataDir();
        this.directory = Objects.requireNonNull(directory, "directory");
        this.reconcileInterval = requireInterval(reconcileInterval);
        Map<String, String> environment = env == null ? Map.of() : Map.copyOf(env);
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(inbox, "inbox");
        Objects.requireNonNull(priorities, "priorities");
        Objects.requireNonNull(sourceLabels, "sourceLabels");
        Objects.requireNonNull(clientFactory, "clientFactory");
        Objects.requireNonNull(credentialPathPolicy, "credentialPathPolicy");

        Supplier<TextSendPolicy> textPolicy = () -> {
            AppConfig current = textPolicyConfig.get();
            AppConfig.QqConnect.Send send = current.qqconnect().send();
            return TextSendPolicy.from(send.maxTextChars(), send.overflow());
        };
        Assembled assembled = assemble(initialConfig, environment, board, directory, inbox,
                priorities, sourceLabels, clientFactory, credentialPathPolicy, textPolicy,
                ingressHandler);
        this.eventReferenceStore = assembled.eventReferenceStore();
        this.replySequenceStore = assembled.replySequenceStore();
        this.registry = assembled.registry();
        this.ingest = assembled.ingest();
        this.sendService = assembled.sendService();
        this.readContext = assembled.readContext();
        this.tools = assembled.tools();
        this.reconcileScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bma3-official-reconcile");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static Assembled assemble(
            AppConfig initialConfig,
            Map<String, String> environment,
            MessageBoardStore board,
            OfficialQqDirectoryStore directory,
            InboxService inbox,
            PriorityResolver priorities,
            SourceLabels sourceLabels,
            OfficialQqAccountClientFactory clientFactory,
            OfficialQqCredentialPathPolicy credentialPathPolicy,
            Supplier<TextSendPolicy> textPolicy,
            OfficialQqIngest.PreBoardIngressHandler ingressHandler) {
        OfficialQqEventReferenceStore eventStore = null;
        OfficialQqReplySequenceStore sequenceStore = null;
        OfficialQqAccountRegistry registry = null;
        try {
            eventStore = new OfficialQqEventReferenceStore(
                    initialConfig.dataDir().resolve("official-event-reference.db"));
            sequenceStore = new OfficialQqReplySequenceStore(
                    initialConfig.dataDir().resolve("official-reply-sequence.db"));
            AtomicReference<OfficialQqIngest> ingestRef = new AtomicReference<>();
            OfficialQqAccountEventSink eventSink = event -> {
                OfficialQqIngest active = ingestRef.get();
                if (active == null) {
                    throw new IllegalStateException("official ingest 尚未就绪");
                }
                active.onEvent(event);
            };
            registry = new OfficialQqAccountRegistry(
                    clientFactory, eventSink, () -> environment, credentialPathPolicy);
            OfficialQqAccountRegistry activeRegistry = registry;
            OfficialQqIngest.Builder ingestBuilder = OfficialQqIngest.builder(
                            board, directory, priorities,
                            accountId -> activeRegistry.accountStatus(accountId)
                                    .map(OfficialQqAccountStatus::enabled)
                                    .orElse(false),
                            new OfficialInboxProjectionSink(inbox))
                    .sourceKeyer(new QqOfficialSourceKeyer())
                    .sourceLabels(sourceLabels)
                    .eventReferenceStore(eventStore);
            if (ingressHandler != null) {
                ingestBuilder.preBoardIngressHandler(ingressHandler);
            }
            OfficialQqIngest ingest = ingestBuilder.build();
            ingestRef.set(ingest);
            OfficialQqReadApiProvider readApi = accountId -> activeRegistry.apiClient(accountId)
                    .map(OfficialQqReadApi::fromClient)
                    .orElseThrow(() -> new OfficialQqApiException(
                            OfficialQqErrorKind.CONFIGURATION,
                            -1,
                            accountId,
                            "READ_API",
                            "",
                            "",
                            false,
                            "read API unavailable",
                            null));
            OfficialQqReadContext readContext = new OfficialQqReadContext(
                    board, directory,
                    new OfficialQqAccountResolver(
                            OfficialQqAccountView.fromRegistry(activeRegistry)),
                    readApi);
            OfficialQqRegistrySendAccounts sendAccounts = new OfficialQqRegistrySendAccounts(
                    activeRegistry,
                    accountId -> activeRegistry.apiClient(accountId)
                            .map(OfficialQqApiSendTransport::new)
                            .orElseThrow(() -> new IllegalStateException(
                                    "官方账号发送 API 当前不可用")));
            OfficialQqSendService sendService = new OfficialQqSendService(
                    board, sendAccounts, textPolicy, sequenceStore, eventStore);
            List<AgentTool> officialTools = new ArrayList<>();
            officialTools.addAll(OfficialQqReadTools.all(readContext));
            officialTools.addAll(QqOfficialShareQrTools.all(
                    initialConfig.dataDir(), activeRegistry::apiClient));
            officialTools.add(new QqOfficialSendTextTool(sendService));
            officialTools.add(new QqOfficialReplyTool(sendService));
            return new Assembled(eventStore, sequenceStore, registry, ingest, sendService,
                    readContext, List.copyOf(officialTools));
        } catch (RuntimeException failure) {
            closeSuppressing(failure, registry);
            closeSuppressing(failure, sequenceStore);
            closeSuppressing(failure, eventStore);
            throw failure;
        }
    }

    private static void closeSuppressing(Throwable primary, AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    /** Initial reconcile plus the bounded secret/config rotation check. */
    public synchronized void start() {
        if (closed.get()) {
            throw new IllegalStateException("official QQ service 已关闭");
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        reconcile(config);
        long intervalMillis = reconcileInterval.toMillis();
        reconcileScheduler.scheduleWithFixedDelay(this::periodicReconcile,
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** Apply the complete desired account list and update the config used by periodic checks. */
    public OfficialQqReconcileResult reconcile(AppConfig next) {
        return reconcile(next, desiredAccounts(next));
    }

    /**
     * Apply a desired list that the composition root already validated. This keeps hot-reload
     * publication atomic: the caller can validate all dependent runtimes before mutating any of
     * them, then pass the exact validated official snapshot.
     */
    public OfficialQqReconcileResult reconcile(AppConfig next,
                                               List<OfficialQqAccountConfig> validatedDesired) {
        Objects.requireNonNull(next, "next");
        List<OfficialQqAccountConfig> desired = validatedDesired == null
                ? List.of() : List.copyOf(validatedDesired);
        reconcileLock.lock();
        try {
            if (closed.get()) {
                throw new IllegalStateException("official QQ service 已关闭");
            }
            try {
                OfficialQqReconcileResult result = registry.reconcile(desired);
                // Publish both the desired config and later-call text policy under the same lock,
                // after the registry snapshot has been accepted.
                config = next;
                textPolicyConfig.set(next);
                lastReconcileError = "";
                reconcileCount++;
                LOG.info("event=qq_official_reconcile added={} removed={} restarted={} "
                                + "disabled={} unchanged={} rejected={} failed={}",
                        result.added().size(), result.removed().size(), result.restarted().size(),
                        result.disabled().size(), result.unchanged().size(),
                        result.rejected().size(), result.failed().size());
                return result;
            } catch (RuntimeException failure) {
                lastReconcileError = failure.getClass().getSimpleName();
                reconcileCount++;
                throw failure;
            }
        } finally {
            reconcileLock.unlock();
        }
    }

    /**
     * Manual periodic tick. The config snapshot must be read while holding the same lock that
     * serializes disable/remove and periodic ticks; otherwise an old desired generation can win
     * the lock after a disable has already completed.
     */
    public OfficialQqReconcileResult reconcileNow() {
        reconcileLock.lock();
        try {
            if (closed.get()) {
                throw new IllegalStateException("official QQ service 已关闭");
            }
            return reconcile(config);
        } finally {
            reconcileLock.unlock();
        }
    }

    private void periodicReconcile() {
        reconcileLock.lock();
        try {
            if (closed.get()) {
                return;
            }
            try {
                OfficialQqReconcileResult result = registry.reconcile(desiredAccounts(config));
                lastReconcileError = "";
                reconcileCount++;
                if (!result.clean() || !result.restarted().isEmpty()
                        || !result.added().isEmpty() || !result.removed().isEmpty()) {
                    LOG.info("event=qq_official_periodic_reconcile added={} removed={} "
                                    + "restarted={} rejected={} failed={}",
                            result.added().size(), result.removed().size(),
                            result.restarted().size(), result.rejected().size(),
                            result.failed().size());
                }
            } catch (RuntimeException failure) {
                lastReconcileError = failure.getClass().getSimpleName();
                LOG.warn("event=qq_official_periodic_reconcile_failed type={}",
                        failure.getClass().getSimpleName());
            }
        } finally {
            reconcileLock.unlock();
        }
    }

    /**
     * Translate app config references into the registry's credential-free desired records. Every
     * account field is carried through; secret values are resolved later by the registry.
     */
    public static List<OfficialQqAccountConfig> desiredAccounts(AppConfig config) {
        Objects.requireNonNull(config, "config");
        AppConfig.QqOfficial official = config.qqOfficial();
        OfficialQqAccountRuntimeOptions options = OfficialQqAccountRuntimeOptions.ofSeconds(
                official.http().connectTimeoutSeconds(),
                official.http().requestTimeoutSeconds(),
                official.reconnect().initialMs(),
                official.reconnect().maxMs(),
                official.reconnect().jitter());
        List<OfficialQqAccountConfig> desired = new ArrayList<>();
        for (AppConfig.QqOfficial.Account account : official.accounts()) {
            Path secretFile = account.appSecretFile().isBlank()
                    ? null : Path.of(account.appSecretFile());
            int shardIndex = account.shard().get(0);
            int shardCount = account.shard().get(1);
            desired.add(new OfficialQqAccountConfig(
                    account.accountId(),
                    account.appId(),
                    account.appSecretEnv(),
                    secretFile,
                    account.enabled(),
                    shardIndex,
                    shardCount,
                    options));
        }
        return List.copyOf(desired);
    }

    public OfficialQqAccountRegistry registry() {
        return registry;
    }

    public OfficialQqIngest ingest() {
        return ingest;
    }

    public OfficialQqReadContext readContext() {
        return readContext;
    }

    public List<AgentTool> tools() {
        return tools;
    }

    public OfficialQqDirectoryStore directory() {
        return directory;
    }

    public OfficialQqEventReferenceStore eventReferenceStore() {
        return eventReferenceStore;
    }

    public OfficialQqReplySequenceStore replySequenceStore() {
        return replySequenceStore;
    }

    public OfficialQqSendService sendService() {
        return sendService;
    }

    public String lastReconcileError() {
        return lastReconcileError;
    }

    public long reconcileCount() {
        return reconcileCount;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        reconcileLock.lock();
        try {
            reconcileScheduler.shutdownNow();
            try {
                registry.close();
            } finally {
                try {
                    replySequenceStore.close();
                } finally {
                    try {
                        eventReferenceStore.close();
                    } finally {
                        directory.close();
                    }
                }
            }
        } finally {
            reconcileLock.unlock();
        }
    }

    private record Assembled(
            OfficialQqEventReferenceStore eventReferenceStore,
            OfficialQqReplySequenceStore replySequenceStore,
            OfficialQqAccountRegistry registry,
            OfficialQqIngest ingest,
            OfficialQqSendService sendService,
            OfficialQqReadContext readContext,
            List<AgentTool> tools) {
    }

    private static Duration requireInterval(Duration value) {
        Objects.requireNonNull(value, "reconcileInterval");
        if (value.isNegative() || value.isZero()
                || value.compareTo(Duration.ofMillis(100)) < 0
                || value.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("official reconcile interval 必须在 100ms..1min");
        }
        return value;
    }
}
