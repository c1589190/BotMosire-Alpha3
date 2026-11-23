package io.github.c1589190.bma3.app;

import io.github.c1589190.bma3.agentlib.Bma3McpServer;
import io.github.c1589190.bma3.app.config.AppConfig;
import io.github.c1589190.bma3.app.config.ConfigWatcher;
import io.github.c1589190.bma3.app.logging.Redactor;
import io.github.c1589190.bma3.memory.SqliteContextDigestStore;
import io.github.c1589190.bma3.memory.SqliteMemoryStore;
import io.github.c1589190.bma3.memory.tools.MemoryTools;
import io.github.c1589190.bma3.app.config.RuntimePolicy;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.QqChannelRuntime;
import io.github.c1589190.bma3.app.channel.router.QqChannelRouteInput;
import io.github.c1589190.bma3.app.channel.router.QqChannelRouter;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore;
import io.github.c1589190.bma3.app.debug.DebugApi;
import io.github.c1589190.bma3.app.debug.DebugRequest;
import io.github.c1589190.bma3.app.debug.DebugService;
import io.github.c1589190.bma3.app.debug.DebugStore;
import io.github.c1589190.bma3.app.debug.DebugTools;
import io.github.c1589190.bma3.app.official.OfficialQqService;
import io.github.c1589190.bma3.app.priority.PriorityDecision;
import io.github.c1589190.bma3.app.priority.PriorityResolver;
import io.github.c1589190.bma3.app.priority.PriorityTools;
import io.github.c1589190.bma3.app.tools.ConfigToolContext;
import io.github.c1589190.bma3.app.tools.GetConfigTool;
import io.github.c1589190.bma3.app.security.RepositoryBoundaries;
import io.github.c1589190.bma3.app.tools.SetConfigTool;
import io.github.c1589190.bma3.core.config.ConfigException;
import io.github.c1589190.bma3.core.message.Direction;
import io.github.c1589190.bma3.core.message.InboundMessage;
import io.github.c1589190.bma3.core.message.Message;
import io.github.c1589190.bma3.core.priority.PriorityAttributes;
import io.github.c1589190.bma3.core.priority.SourceKeyer;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.core.source.SourceLabels;
import io.github.c1589190.bma3.core.source.SourceView;
import io.github.c1589190.bma3.core.store.MessageBoardStore;
import io.github.c1589190.bma3.core.store.SqliteMessageBoardStore;
import io.github.c1589190.bma3.inbox.core.AppendItem;
import io.github.c1589190.bma3.inbox.core.DeliveryPolicy;
import io.github.c1589190.bma3.inbox.core.InboxEntry;
import io.github.c1589190.bma3.inbox.core.InboxService;
import io.github.c1589190.bma3.inbox.core.SqliteInboxStore;
import io.github.c1589190.bma3.inbox.http.InboxHttpServer;
import io.github.c1589190.bma3.inbox.tools.InboxTools;
import io.github.c1589190.bma3.qqconnect.account.AccountConfig;
import io.github.c1589190.bma3.scheduler.ScheduleFired;
import io.github.c1589190.bma3.scheduler.ScheduleOptions;
import io.github.c1589190.bma3.scheduler.ScheduleService;
import io.github.c1589190.bma3.scheduler.ScheduleStore;
import io.github.c1589190.bma3.scheduler.ScheduleTools;
import io.github.c1589190.bma3.qqconnect.account.AccountEvent;
import io.github.c1589190.bma3.qqconnect.account.AccountRegistry;
import io.github.c1589190.bma3.qqconnect.directory.QqDirectoryStore;
import io.github.c1589190.bma3.qqconnect.onebot.OneBotEvent;
import io.github.c1589190.bma3.qqconnect.tools.QqDeployerNotifier;
import io.github.c1589190.bma3.qqconnect.tools.QqExtrasStore;
import io.github.c1589190.bma3.qqconnect.tools.QqMediaDiagnostics;
import io.github.c1589190.bma3.qqconnect.tools.QqSendPolicy;
import io.github.c1589190.bma3.qqconnect.tools.QqSignalExtractor;
import io.github.c1589190.bma3.qqconnect.tools.QqSourceKeyer;
import io.github.c1589190.bma3.qqconnect.tools.QqSourceLabeler;
import io.github.c1589190.bma3.qqconnect.tools.QqToolContext;
import io.github.c1589190.bma3.qqconnect.tools.QqTools;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountClientFactory;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountStatus;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqProductionAccountRuntimeFactory;
import io.github.c1589190.bma3.qqofficial.credential.OfficialQqCredentialPathPolicy;
import io.github.c1589190.bma3.qqofficial.directory.OfficialQqDirectoryStore;
import io.github.c1589190.bma3.qqofficial.tools.QqOfficialSourceLabeler;
import io.github.c1589190.bma3.webconnect.BrowserOptions;
import io.github.c1589190.bma3.webconnect.BrowserService;
import io.github.c1589190.bma3.xvfb.XvfbService;
import io.github.c1589190.bma3.xvfb.XvfbTools;
import io.github.c1589190.bma3.watchdog.JsonWatchdogStore;
import io.github.c1589190.bma3.watchdog.WatchdogEngine;
import io.github.c1589190.bma3.watchdog.probe.Probes;
import io.github.c1589190.bma3.watchdog.remediation.DeployerAlertListener;
import io.github.c1589190.bma3.watchdog.remediation.Remediations;
import io.github.c1589190.bma3.watchdog.spi.Health;
import io.github.c1589190.bma3.watchdog.spi.ProbeResult;
import io.github.c1589190.bma3.watchdog.spi.WatchdogOptions;
import io.github.c1589190.bma3.watchdog.task.Tasks;
import io.github.c1589190.bma3.watchdog.tool.WatchdogTools;
import java.net.URI;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Composition root for the one BMA3 service process. */
public final class Bma3App implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Bma3App.class);

    private final AppConfig config;
    private final SqliteMessageBoardStore board;
    private final SqliteMemoryStore memoryStore;
    private final SqliteContextDigestStore contextStore;
    private final SqliteInboxStore inboxStore;
    private final InboxService inboxService;
    private final HealthServer health;
    private final InboxHttpServer inboxHttp;
    private final AccountRegistry accounts;
    private final Bma3McpServer mcp;
    private final BrowserService browser;
    private final XvfbService xvfb;
    private final QqExtrasStore qqExtras;
    private final ConfigWatcher watcher;
    private final WatchdogEngine watchdog;
    private final QqDirectoryStore directory;
    private final ScheduleService schedule;
    private final OfficialQqService official;
    private final QqChannelRuntime channels;
    private final SingleInstanceLock instanceLock;

    private Bma3App(AppConfig config, SqliteMessageBoardStore board,
                    SqliteMemoryStore memoryStore, SqliteContextDigestStore contextStore,
                    SqliteInboxStore inboxStore, InboxService inboxService,
                    HealthServer health, InboxHttpServer inboxHttp,
                    AccountRegistry accounts, Bma3McpServer mcp,
                    BrowserService browser, XvfbService xvfb, QqExtrasStore qqExtras,
                    ConfigWatcher watcher, WatchdogEngine watchdog,
                    QqDirectoryStore directory, ScheduleService schedule,
                    OfficialQqService official, QqChannelRuntime channels,
                    SingleInstanceLock instanceLock) {
        this.config = config;
        this.board = board;
        this.memoryStore = memoryStore;
        this.contextStore = contextStore;
        this.inboxStore = inboxStore;
        this.inboxService = inboxService;
        this.health = health;
        this.inboxHttp = inboxHttp;
        this.accounts = accounts;
        this.mcp = mcp;
        this.browser = browser;
        this.xvfb = xvfb;
        this.qqExtras = qqExtras;
        this.watcher = watcher;
        this.watchdog = watchdog;
        this.directory = directory;
        this.schedule = schedule;
        this.official = official;
        this.channels = channels;
        this.instanceLock = instanceLock;
    }

    public static Bma3App start(AppConfig config) {
        return start(config, System.getenv());
    }

    public static Bma3App start(AppConfig config, Map<String, String> env) {
        return start(config, env, Path.of("."));
    }

    public static Bma3App start(AppConfig config, Map<String, String> env, Path configRoot) {
        return start(config, env, configRoot, new OfficialQqProductionAccountRuntimeFactory());
    }

    /**
     * Composition-root seam for deterministic app integration tests and embedders. Production
     * callers use the overload above; no fake factory is ever discovered implicitly.
     */
    public static Bma3App start(AppConfig config, Map<String, String> env, Path configRoot,
                                OfficialQqAccountClientFactory officialClientFactory) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(configRoot, "configRoot");
        Objects.requireNonNull(officialClientFactory, "officialClientFactory");
        SingleInstanceLock instanceLock;
        try {
            instanceLock = SingleInstanceLock.acquire(config.dataDir());
        } catch (IOException failure) {
            throw new IllegalStateException("获取单实例锁失败: " + failure.getMessage(), failure);
        }
        try {
            Files.createDirectories(config.dataDir());
        } catch (Exception failure) {
            instanceLock.close();
            throw new IllegalStateException("创建 dataDir 失败", failure);
        }
        boolean authRequired = config.security().requiresToken();
        Bma3Env tokenFile = Bma3Env.ensure(Bma3Env.resolve(config, configRoot), false);
        if (tokenFile.degraded()) {
            if (authRequired) {
                instanceLock.close();
                throw new ConfigException("security.auth", "无法安全创建或读取 token 文件: "
                        + tokenFile.file());
            }
            LOG.warn("event=token_file_write_failed file={}", tokenFile.file());
        }
        String mcpToken = authRequired ? tokenFile.mcpToken() : "";
        String inboxToken = authRequired ? tokenFile.inboxToken() : "";
        if (authRequired && (mcpToken.isBlank() || inboxToken.isBlank())) {
            instanceLock.close();
            throw new ConfigException("security.auth",
                    "token 模式需要 MCP_TOKEN/INBOX_TOKEN: " + tokenFile.file());
        }
        if (!authRequired) {
            LOG.warn("event=auth_disabled reason=security.auth=none file={}", tokenFile.file());
        }

        SqliteMessageBoardStore board = new SqliteMessageBoardStore(
                config.dataDir().resolve("bot.db"));
        // Conservative crash recovery belongs to the locked process start, not to opening the
        // board: only here is it certain no live send can still own a PENDING row. It runs before
        // any service (Inbox HTTP, MCP, QQ) accepts a request. Log the count only, never bodies.
        try {
            int recovered = board.recoverPendingOutbound();
            if (recovered > 0) {
                LOG.info("event=outbound_pending_recovered count={}", recovered);
            }
        } catch (RuntimeException failure) {
            try {
                board.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            instanceLock.close();
            throw failure;
        }
        SqliteInboxStore inboxStore = new SqliteInboxStore(
                config.dataDir().resolve("inbox.db"));
        QqDirectoryStore directory = new QqDirectoryStore(
                config.dataDir().resolve("qq-directory.db"));
        AtomicReference<OfficialQqDirectoryStore> officialDirectoryRef =
                new AtomicReference<>();
        AtomicReference<OfficialQqService> officialServiceRef = new AtomicReference<>();
        AtomicReference<AppConfig> current = new AtomicReference<>(config);
        // Notebook storage is its own file: the fact source (bot.db) must stay untouched and a
        // lost or replayed memory database may never cost a message. Live options keep set_config
        // effective without restarting anything.
        SqliteMemoryStore memoryStore = new SqliteMemoryStore(
                config.dataDir().resolve("memory.db"),
                () -> current.get().memory().toOptions(),
                Redactor::redact);
        // Same file, different semantics: the rolling context copy is a discardable cache of what
        // the plugin saw. Only its watermark must survive, because that is what lets the watchdog
        // tell "quiet" from "dead" instead of just reading the process table.
        SqliteContextDigestStore contextStore = new SqliteContextDigestStore(
                config.dataDir().resolve("memory.db"),
                () -> current.get().memory().toContextOptions(),
                Redactor::redact);
        AtomicReference<RuntimePolicy> runtime =
                new AtomicReference<>(RuntimePolicy.from(config));
        SourceLabels sourceLabels = SourceLabels.builder()
                .register(new QqSourceLabeler(() -> directory))
                .register(new QqOfficialSourceLabeler(officialDirectoryRef::get))
                .build();
        SourceKeyer sourceKeyer = new QqSourceKeyer();
        PriorityResolver priorities = new PriorityResolver(() -> runtime.get().weighting());
        Supplier<DeliveryPolicy> deliveryPolicy = () -> {
            var weight = runtime.get().weighting();
            return new DeliveryPolicy(weight.immediateThreshold(),
                    Duration.ofSeconds(weight.importantDelaySeconds()),
                    Duration.ofSeconds(weight.normalDelaySeconds()));
        };
        InboxService inboxService = new InboxService(
                inboxStore, deliveryPolicy, config.inbox().inboxId(), () -> {
                    var policy = runtime.get();
                    var dismiss = policy.dismiss();
                    return new io.github.c1589190.bma3.inbox.core.InboxRuntime(
                            policy.display().maxMessagesPerItem(),
                            policy.display().maxItemsPerPanel(),
                            policy.display().includeOmittedCount(),
                            policy.push().minPriority(),
                            policy.retention().maxAgeDays(),
                            policy.retention().maxItems(),
                            policy.display().maxCharsPerItem(),
                            policy.push().maxFirstPushDelaySeconds(),
                            dismiss.autoEnabled() ? dismiss.autoBelowPriority()
                                    : Integer.MIN_VALUE,
                            dismiss.autoAfterSeconds(),
                            dismiss.autoMaxPerRound(),
                            policy.display().mode(),
                            policy.display().summaryMaxCharsPerItem(),
                            policy.display().summaryAboveItems(),
                            new io.github.c1589190.bma3.inbox.core.InboxWaitPolicy(
                                    policy.waitPolicy().enabled(),
                                    policy.waitPolicy().defaultQuietSeconds(),
                                    policy.waitPolicy().defaultMaxWaitSeconds(),
                                    policy.waitPolicy().sources().stream()
                                            .map(rule -> new io.github.c1589190.bma3.inbox.core
                                                    .InboxWaitPolicy.Rule(
                                                            rule.source(),
                                                            rule.quietSeconds(),
                                                            rule.maxWaitSeconds()))
                                            .toList()));
                });
        inboxService.setMcpStartId("mcp-" + UUID.randomUUID().toString().substring(0, 8));
        if (config.inbox().push().minPriority() > -1000) {
            LOG.warn("event=inbox_push_min_priority_ignored value={} hint={}",
                    config.inbox().push().minPriority(),
                    "minPriority 不再硬静音；所有未被 weighting.dropBelow 丢弃的条目都会推送");
        }

        // Durable schedules (spec 2026-11-15 §4): the engine never runs user work, it only hands one
        // Inbox notification to the composition root when a slot is due.
        AtomicReference<ScheduleService> scheduleRef = new AtomicReference<>();
        if (config.schedule().enabled()) {
            ScheduleService created = new ScheduleService(
                    new ScheduleStore(config.dataDir().resolve("schedule.db")),
                    fire -> injectSchedule(inboxService, fire),
                    () -> {
                        AppConfig.Schedule limits = current.get().schedule();
                        return new ScheduleOptions(limits.enabled(), limits.tickSeconds(),
                                limits.maxSchedules(), limits.defaultMissedPolicy());
                    });
            created.start();
            scheduleRef.set(created);
        } else {
            LOG.warn("event=schedule_disabled reason=schedule.enabled=false");
        }

        QqExtrasStore qqExtras = new QqExtrasStore(config.dataDir().resolve("qq-extras.db"));
        QqMediaDiagnostics mediaDiagnostics = new QqMediaDiagnostics();
        AtomicReference<AccountRegistry> registry = new AtomicReference<>();
        AtomicReference<QqChannelRuntime> channelsRef = new AtomicReference<>();
        AccountRegistry accounts = new AccountRegistry(event -> {
            QqChannelRuntime channels = channelsRef.get();
            if (channels == null) {
                throw new IllegalStateException("QQ 渠道恢复尚未完成");
            }
            ingest(board, priorities, sourceKeyer, registry.get(), current, qqExtras,
                    sourceLabels, directory, channels.router(), event);
        });
        registry.set(accounts);
        QqToolContext qqContext = new QqToolContext(
                accounts, board, board, config.dataDir(), qqExtras,
                () -> {
                    var send = runtime.get().send();
                    return QqSendPolicy.from(send.maxTextChars(), send.overflow());
                },
                () -> {
                    var media = runtime.get().media();
                    Path dir = media.dir().isBlank()
                            ? config.dataDir().resolve("downloads") : Path.of(media.dir());
                    return new io.github.c1589190.bma3.qqconnect.tools.QqMediaPolicy(dir,
                            media.maxInlineBytes(), media.maxDownloadBytes(),
                            Duration.ofSeconds(media.timeoutSeconds()));
                }, directory, mediaDiagnostics);
        AtomicReference<WatchdogEngine> watchdogRef = new AtomicReference<>();
        // Remote ask surface (spec 2026-11-15 §2). It is deliberately absent when auth is disabled:
        // debug.maxPending caps concurrency (0 = unlimited, per user ruling: no single-flight).
        String debugToken = authRequired ? tokenFile.debugToken() : "";
        DebugService debugService = null;
        DebugApi debugApi = null;
        if (config.debug().enabled() && authRequired && !debugToken.isBlank()) {
            debugService = new DebugService(
                    new DebugStore(config.dataDir().resolve("debug")),
                    () -> current.get().debug(),
                    request -> injectDebugQuestion(inboxService, request, current.get().debug()));
            debugApi = new DebugApi(debugService, debugToken, () -> {
                WatchdogEngine engine = watchdogRef.get();
                return engine == null ? null : engine.snapshot();
            }, inboxService, config.dataDir(), Main.VERSION, Instant.now(), "token");
        } else if (config.debug().enabled()) {
            LOG.warn("event=debug_surface_disabled reason={}",
                    authRequired ? "debug_token_missing" : "security.auth=none");
        }
        Supplier<Map<String, Object>> watchdogDiagnostics = () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("version", Main.VERSION);
            body.put("now", Instant.now().toString());
            WatchdogEngine engine = watchdogRef.get();
            body.put("watchdog", engine == null ? Map.of("status", "unavailable")
                    : engine.snapshot());
            Map<String, Object> inboxView = new LinkedHashMap<>();
            inboxView.put("status", inboxService.status());
            inboxView.put("heartbeat", inboxService.lastHeartbeat());
            body.put("inbox", inboxView);
            body.put("media", mediaDiagnostics.snapshot(qqContext.media().dir()));
            Map<String, Long> fileSizes = new LinkedHashMap<>();
            for (String name : new String[]{"bot.db", "inbox.db", "schedule.db",
                    "qq-directory.db", "qq-extras.db", "debug/requests.json"}) {
                Path file = config.dataDir().resolve(name);
                try {
                    fileSizes.put(name, Files.isRegularFile(file) ? Files.size(file) : -1L);
                } catch (IOException failure) {
                    fileSizes.put(name, -1L);
                }
            }
            body.put("files", fileSizes);
            body.put("security", Map.of("auth", authRequired ? "token" : "none"));
            return body;
        };
        HealthServer health = new HealthServer(config.server().host(),
                config.server().healthPort(), mcpToken, () -> {
                    WatchdogEngine engine = watchdogRef.get();
                    return engine == null ? null : engine.snapshot();
                }, debugApi, watchdogDiagnostics);
        InboxHttpServer inboxHttp = new InboxHttpServer(
                config.server().host(), config.inbox().httpPort(),
                config.inbox().inboxId(), inboxToken, inboxService, contextStore);
        ConfigWatcher watcher = null;
        Bma3McpServer mcp = null;
        BrowserService browser = null;
        XvfbService xvfb = null;
        WatchdogEngine watchdog = null;
        try {
            // Open local control/observation stores first. Repair binding epochs and replay every
            // durable channel/Inbox operation before an account or an HTTP endpoint is started.
            QqChannelRuntime channels = new QqChannelRuntime(config.dataDir(), board, inboxService,
                    inboxStore,
                    (channel, accountId) -> knownChannelAccount(current.get(), channel, accountId),
                    (channel, accountId) -> channelAvailable(
                            channel, accountId, accounts, officialServiceRef.get()),
                    () -> current.get().qqOfficial().matching(), Clock.systemUTC());
            channelsRef.set(channels);
            var recovery = channels.recoverBeforeServing();
            LOG.info("event=qq_channel_recovered promoted={} routed={} skipped={}",
                    recovery.promoted(), recovery.routed(), recovery.skipped());
            inboxHttp.start();
            // New official resources are created inside the unified cleanup scope. If the
            // directory, credential policy or service constructor fails, the catch below closes
            // the already-created board/inbox/directory/instance-lock resources.
            OfficialQqDirectoryStore officialDirectory = new OfficialQqDirectoryStore(
                    config.dataDir().resolve("official-directory.db"));
            officialDirectoryRef.set(officialDirectory);
            OfficialQqCredentialPathPolicy officialCredentialPolicy =
                    RepositoryBoundaries.credentialPolicy(
                            officialCredentialStartingPoints(config, configRoot));
            OfficialQqService officialService = new OfficialQqService(
                    config, env, board, officialDirectory, inboxService, priorities,
                    sourceLabels, officialClientFactory, officialCredentialPolicy,
                    channels.router()::ingest);
            officialServiceRef.set(officialService);

            List<AccountConfig> resolved = resolveAccounts(config, env);
            accounts.reconcile(resolved);
            applySelfOverrides(accounts, config);
            officialService.start();
            watcher = ConfigWatcher.watch(configRoot, env, next -> {
                // Validate every dependent runtime before mutating either one. In particular a
                // bad OneBot tokenEnv must leave the previous global policy and official snapshot
                // untouched, matching the pre-official hot-reload contract.
                List<AccountConfig> nextOneBot = resolveAccounts(next, env);
                List<io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountConfig> nextOfficial =
                        OfficialQqService.desiredAccounts(next);
                accounts.reconcile(nextOneBot);
                applySelfOverrides(accounts, next);
                officialService.reconcile(next, nextOfficial);
                current.set(next);
                runtime.set(RuntimePolicy.from(next));
            });
            ConfigWatcher activeWatcher = watcher;

            ConfigToolContext configTools = new ConfigToolContext(configRoot, env, current::get);
            watchdog = new WatchdogEngine(() -> watchdogOptions(current.get()),
                    new JsonWatchdogStore(config.dataDir().resolve("watchdog")));
            List<io.github.c1589190.agentmosire.agentlib.tool.AgentTool> tools = new ArrayList<>();
            tools.addAll(QqTools.all(qqContext));
            tools.addAll(officialService.tools());
            tools.addAll(channels.tools());
            tools.addAll(InboxTools.all(inboxService, Main.VERSION));
            tools.addAll(MemoryTools.all(memoryStore,
                    () -> current.get().memory().toOptions(), board::hasLocalSeq,
                    contextStore, () -> current.get().memory().toContextOptions(),
                    inboxService::pluginSessionId,
                    config.dataDir().resolve("exports"),
                    // File imports read only what QQ delivered. The media directory is reloadable,
                    // so resolve it per call instead of freezing it at registration time.
                    () -> {
                        var media = runtime.get().media();
                        return media.dir().isBlank()
                                ? config.dataDir().resolve("downloads") : Path.of(media.dir());
                    }));
            tools.add(new GetConfigTool(configTools));
            tools.add(new SetConfigTool(configTools));
            tools.addAll(WatchdogTools.all(watchdog, watchdogDiagnostics));
            tools.addAll(PriorityTools.all(priorities));
            if (scheduleRef.get() != null) {
                tools.addAll(ScheduleTools.all(scheduleRef.get()));
            }
            if (debugService != null) {
                tools.addAll(DebugTools.all(debugService));
            }
            if (config.webconnect().enabled()) {
                browser = new BrowserService(new BrowserOptions(
                        config.webconnect().binary(),
                        config.webconnect().headless(),
                        config.dataDir().resolve("browser"),
                        config.dataDir().resolve("screenshots"),
                        config.webconnect().allowedHosts(),
                        Duration.ofSeconds(30)));
                tools.addAll(browser.tools());
            }
            if (config.xvfb().enabled()) {
                xvfb = new XvfbService(config.xvfb().root(),
                        config.dataDir().resolve("downloads"), config.webconnect().binary());
                tools.addAll(XvfbTools.all(xvfb));
            }
            mcp = new Bma3McpServer(config.server().host(), config.server().port(),
                    config.server().mcpPath(), mcpToken, "bma3", Main.VERSION, tools)
                    .authHint(authRequired ? tokenFile.file().toString() : "security.auth=none");
            mcp.start();

            watchdog.addProbe(Probes.process())
                    .addProbe(Probes.disk(config.dataDir()))
                    .addProbe(Probes.db(board::ping, inboxStore::ping))
                    .addProbe(Probes.named("qqChannels", () -> channels.ping()
                            ? ProbeResult.up("qqChannels", "SELECT 1")
                            : ProbeResult.down("qqChannels", "DB_UNAVAILABLE", "SELECT 1 failed")))
                    .addProbe(Probes.named("directory", () -> directory.ping()
                            ? ProbeResult.up("directory", "SELECT 1")
                            : ProbeResult.down("directory", "DB_UNAVAILABLE", "SELECT 1 failed")))
                    .addProbe(Probes.inboxHttp(config.server().host(), inboxHttp.boundPort(),
                            config.inbox().inboxId(), inboxToken, inboxHttp::running))
                    .addProbe(Probes.named("inboxBacklog", () ->
                            current.get().watchdog().pushBacklogEnabled()
                                    ? Probes.inboxBacklog(inboxService, 120_000L).check()
                                    : ProbeResult.up("inboxBacklog", "disabled")))
                    .addProbe(Probes.mcp(mcp))
                    .addProbe(Probes.qqAccount(accounts))
                    .addProbe(Probes.qqZombie(accounts))
                    .addProbe(Probes.named("qqOfficial",
                            () -> qqOfficialProbe(officialService)))
                    .addProbe(Probes.named("dshPlugin", () -> {
                        AppConfig.Watchdog settings = current.get().watchdog();
                        if (!settings.pluginHeartbeatEnabled()) {
                            return ProbeResult.up("dshPlugin", "disabled");
                        }
                        return Probes.dshPlugin(inboxService,
                                Duration.ofSeconds(settings.pluginHeartbeatTimeoutSeconds())).check();
                    }))
                    // D-079: the copy the Agent reads has its own liveness. Sharing the
                    // `pluginHeartbeatEnabled` switch keeps one story for "watch the plugin".
                    .addProbe(Probes.named("pluginContext", () -> {
                        if (!current.get().watchdog().pluginHeartbeatEnabled()) {
                            return ProbeResult.up("pluginContext", "disabled");
                        }
                        return Probes.pluginContext(contextStore, inboxService::pluginSessionId,
                                () -> current.get().memory().toContextOptions()).check();
                    }))
                    .addProbe(Probes.webconnect(config.webconnect().enabled(),
                            config.webconnect().binary()))
                    .addProbe(Probes.named("xvfb", () -> {
                        if (!config.xvfb().enabled()) {
                            return ProbeResult.up("xvfb", "disabled");
                        }
                        var caps = io.github.c1589190.bma3.xvfb.PlatformProbe.probe();
                        if (!caps.supported()) {
                            return ProbeResult.degraded("xvfb", caps.reason(), caps.hint());
                        }
                        return ProbeResult.up("xvfb", caps.xvfb());
                    }))
                    .addProbe(Probes.media(mediaDiagnostics, qqContext.media().dir()))
                    .addProbe(Probes.named("schedule", () -> {
                        ScheduleService currentSchedule = scheduleRef.get();
                        if (currentSchedule == null) {
                            return ProbeResult.up("schedule", "disabled");
                        }
                        return currentSchedule.ping()
                                ? ProbeResult.up("schedule", "SELECT 1")
                                : ProbeResult.down("schedule", "DB_UNAVAILABLE", "SELECT 1 failed");
                    }))
                    .addProbe(Probes.named("config", () -> {
                        if (activeWatcher.lastReloadError().isBlank()) {
                            return ProbeResult.up("config", "reload ok",
                                    Map.of("reloads",
                                            Long.toString(activeWatcher.reloadCount())));
                        }
                        return ProbeResult.degraded("config", "RELOAD_REJECTED",
                                activeWatcher.lastReloadError());
                    }));
            if (authRequired) {
                watchdog.addRemediation(Remediations.reconnectQq(accounts))
                        .addRemediation(Remediations.restartInboxHttp(inboxHttp))
                        .addRemediation(Remediations.restartMcp(mcp))
                        .addRemediation(Remediations.nudgePlugin(inboxService));
            } else {
                // D-064/Q4: with no auth, recovery actions stay observe-only.
                LOG.warn("event=watchdog_remediation_disabled reason=security.auth=none");
            }
            watchdog.addTask(Tasks.autoDismiss(inboxService, Duration.ofSeconds(60)));
            Consumer<String> deployerAlert = text -> {
                AppConfig latest = current.get();
                if (!latest.deployer().configured()) {
                    return;
                }
                new QqDeployerNotifier(qqContext, latest.deployer().qq(), true).send(text);
            };
            watchdog.onEvent(new DeployerAlertListener(deployerAlert,
                    Duration.ofSeconds(config.watchdog().alertCooldownSeconds()),
                    config.watchdog().enabled()));
            watchdog.start();
            watchdogRef.set(watchdog);

            return new Bma3App(config, board, memoryStore, contextStore, inboxStore,
                    inboxService, health,
                    inboxHttp, accounts, mcp, browser, xvfb, qqExtras, watcher, watchdog,
                    directory, scheduleRef.get(), officialService, channels, instanceLock);
        } catch (RuntimeException failure) {
            // Stop external entry points and account producers before their shared stores. A
            // cleanup failure must not hide the original failure or leak the instance lock.
            closeSuppressing(failure, watcher);
            closeSuppressing(failure, watchdog);
            closeSuppressing(failure, mcp);
            closeSuppressing(failure, browser);
            closeSuppressing(failure, xvfb);
            closeSuppressing(failure, scheduleRef.get());
            OfficialQqService officialToClose = officialServiceRef.get();
            if (officialToClose != null) {
                closeSuppressing(failure, officialToClose);
            } else {
                closeSuppressing(failure, officialDirectoryRef.get());
            }
            closeSuppressing(failure, accounts);
            closeSuppressing(failure, channelsRef.get());
            closeSuppressing(failure, directory);
            closeSuppressing(failure, qqExtras);
            closeSuppressing(failure, inboxHttp);
            closeSuppressing(failure, health);
            closeSuppressing(failure, inboxStore);
            closeSuppressing(failure, board);
            closeSuppressing(failure, instanceLock);
            throw failure;
        }
    }

    private static void closeSuppressing(Throwable primary, AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    private static List<Path> officialCredentialStartingPoints(AppConfig config,
                                                               Path configRoot) {
        List<Path> points = new ArrayList<>();
        points.add(configRoot.toAbsolutePath().normalize());
        points.add(config.dataDir().toAbsolutePath().normalize());
        points.add(Path.of("").toAbsolutePath().normalize());
        for (AppConfig.QqOfficial.Account account : config.qqOfficial().accounts()) {
            if (!account.appSecretFile().isBlank()) {
                points.add(Path.of(account.appSecretFile()).toAbsolutePath().normalize());
            }
        }
        return List.copyOf(points);
    }

    private static boolean knownChannelAccount(AppConfig config, QqChannelRegistry.Channel channel,
                                               String accountId) {
        if (channel == QqChannelRegistry.Channel.ONEBOT) {
            return config.qqconnect().accounts().stream()
                    .anyMatch(account -> account.accountId().equals(accountId));
        }
        return config.qqOfficial().accounts().stream()
                .anyMatch(account -> account.accountId().equals(accountId));
    }

    private static boolean channelAvailable(QqChannelRegistry.Channel channel, String accountId,
                                            AccountRegistry oneBot,
                                            OfficialQqService official) {
        if (channel == QqChannelRegistry.Channel.ONEBOT) {
            return oneBot.connected(accountId);
        }
        return official != null && official.registry().accountStatus(accountId)
                .map(OfficialQqAccountStatus::healthy).orElse(false);
    }

    private static ProbeResult qqOfficialProbe(OfficialQqService official) {
        List<OfficialQqAccountStatus> statuses = official.registry().accountStatuses();
        int enabled = 0;
        int disabled = 0;
        int connected = 0;
        int down = 0;
        int pending = 0;
        for (OfficialQqAccountStatus status : statuses) {
            if (status == null || !status.enabled()) {
                disabled++;
                continue;
            }
            enabled++;
            if (status.terminal() || status.state()
                    == io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountState.DOWN) {
                down++;
            } else if (!status.connected()) {
                pending++;
            } else {
                connected++;
            }
        }
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("accounts", Integer.toString(enabled + disabled));
        facts.put("enabled", Integer.toString(enabled));
        facts.put("disabled", Integer.toString(disabled));
        facts.put("connected", Integer.toString(connected));
        facts.put("pending", Integer.toString(pending));
        facts.put("down", Integer.toString(down));
        if (enabled == 0) {
            return ProbeResult.up("qqOfficial", "disabled", facts);
        }
        if (down > 0) {
            return ProbeResult.down("qqOfficial", "DOWN", "enabled account unavailable", facts);
        }
        if (pending > 0) {
            return ProbeResult.degraded("qqOfficial", "CONNECTING",
                    "enabled account not connected", facts);
        }
        return ProbeResult.up("qqOfficial", "all enabled accounts connected", facts);
    }

    private static WatchdogOptions watchdogOptions(AppConfig config) {
        var watchdog = config.watchdog();
        return new WatchdogOptions(
                watchdog.enabled(),
                Duration.ofSeconds(watchdog.intervalSeconds()),
                Duration.ofMillis(watchdog.probeTimeoutMs()),
                watchdog.failureThreshold(),
                Duration.ofSeconds(watchdog.recoveryCooldownSeconds()),
                Duration.ofSeconds(watchdog.alertCooldownSeconds()),
                config.dataDir().resolve("watchdog"),
                watchdog.readyzCritical(),
                200,
                Duration.ofSeconds(watchdog.pluginHeartbeatTimeoutSeconds()),
                watchdog.diskMinFreeMb() * 1024L * 1024L);
    }

    /**
     * Priority first, persistence second: a message scored below {@code dropBelow} is discarded
     * outright (operator ruling) and never reaches the board, so it can never be replayed or
     * counted later.
     */
    /** Turn one fired schedule into an Inbox notification; the Agent decides what to do next. */
    private static void injectSchedule(InboxService inboxService, ScheduleFired fire) {
        SourceView source = SourceView.of("bma3_task/" + fire.scheduleId(), "定时任务");
        inboxService.append(new AppendItem(source, null, fire.text(),
                fire.priority(), Instant.now()));
    }

    /**
     * Turn one remote question into an Inbox item. The item is the only delivery path: the bound
     * session wakes on it exactly like any other source, and the Agent answers with
     * {@code bma3_debug_reply} so the caller's HTTP request can return.
     */
    private static boolean injectDebugQuestion(InboxService inboxService, DebugRequest request,
                                               AppConfig.Debug limits) {
        SourceView source = SourceView.of(request.injectedSource(), "调试提问");
        inboxService.append(new AppendItem(source, null, DebugService.inboxSummary(request),
                limits.priority(), Instant.now()));
        return true;
    }

    private static void ingest(MessageBoardStore board,
                               PriorityResolver priorities, SourceKeyer sourceKeyer,
                               AccountRegistry accounts, AtomicReference<AppConfig> current,
                               QqExtrasStore extras, SourceLabels sourceLabels,
                               QqDirectoryStore directory, QqChannelRouter channelRouter,
                               AccountEvent accountEvent) {
        OneBotEvent event = accountEvent.event();
        SourceAddress source = event.source(accountEvent.accountId());
        SourceView view = sourceLabels.view(source);
        String sourceKey = sourceKeyer.keyFor(source);
        // P3-8：没有 user_id 的合成事件（撤回/系统消息等）不要写成 "0" —— 那会被当成一个真实 QQ 号
        // 参与画像与检索。空字符串表示"未知发送者"，下游按未知处理。
        String senderId = event.userId() > 0 ? Long.toString(event.userId()) : "";
        boolean replyToBot = isReplyToBot(board, source, event.replyToMessageId());
        PriorityAttributes attributes = QqSignalExtractor.attributes(event,
                selfName(accounts, current.get(), accountEvent.accountId()), replyToBot,
                current.get().weighting().commandPrefixes());
        PriorityDecision decision = priorities.resolve(sourceKey, senderId,
                event.senderName(), attributes, event.text());
        if (LOG.isDebugEnabled()) {
            // Same arithmetic as the decision, so "why did this wake me?" is answerable from logs.
            LOG.debug("event=qq_message_scored sourceKey={} senderId={} priority={} immediate={} "
                            + "dropped={} attributes={} reasons={}",
                    sourceKey, senderId, decision.priority(), decision.immediate(),
                    decision.dropped(), attributes.keys(), decision.reasons());
        }
        if (decision.dropped()) {
            LOG.info("event=qq_message_dropped accountId={} sourceKey={} priority={}",
                    accountEvent.accountId(), sourceKey, decision.priority());
            return;
        }
        InboundMessage fact = new InboundMessage(
                source,
                Long.toString(event.messageId()),
                event.at(),
                event.segments(),
                Direction.INBOUND,
                senderId,
                event.senderName(),
                event.senderCard(),
                event.senderRole());
        var note = extras.note(accountEvent.accountId(), senderId).orElse(null);
        InboxEntry preview = entry(event, 0L, note, accountEvent.accountId(), directory);
        channelRouter.ingest(new QqChannelRouteInput(false, accountEvent.accountId(), source,
                fact.messageId(), senderId, Direction.INBOUND, event.at(), Instant.now(),
                decision.priority(),
                event.timestampFallback() ? QqChannelObservationStore.TimeReliability.FALLBACK
                        : QqChannelObservationStore.TimeReliability.RELIABLE,
                event.contentComplete() ? QqChannelObservationStore.ContentIntegrity.COMPLETE
                        : QqChannelObservationStore.ContentIntegrity.INCOMPLETE,
                botOnlyMentions(event), event.segments(), fact, preview,
                QqChannelRouteInput.RawProjectionMode.LEGACY_APPEND_OR_MERGE, view));
    }

    /** Only structured mentions whose every target is this account may be ignored by matching. */
    private static boolean botOnlyMentions(OneBotEvent event) {
        boolean found = false;
        for (var segment : event.segments()) {
            if (!"at".equalsIgnoreCase(segment.type())
                    && !"mention".equalsIgnoreCase(segment.type())) {
                continue;
            }
            found = true;
            if (event.selfId() <= 0 || !Long.toString(event.selfId()).equals(segment.data("qq"))) {
                return false;
            }
        }
        return found;
    }

    /** True when the quoted platform id belongs to a row this account sent. */
    private static boolean isReplyToBot(MessageBoardStore board, SourceAddress source,
                                        String replyToMessageId) {
        if (replyToMessageId == null || replyToMessageId.isBlank()) {
            return false;
        }
        return board.findByExternalMessageId(source, replyToMessageId)
                .map(Message::self)
                .orElse(false);
    }

    /** The bot's own nickname: an explicit config override wins over the learned login info. */
    private static String selfName(AccountRegistry accounts, AppConfig config, String accountId) {
        for (AppConfig.Account account : config.qqconnect().accounts()) {
            if (account.accountId().equals(accountId) && !account.selfName().isBlank()) {
                return account.selfName();
            }
        }
        return accounts.self(accountId)
                .map(AccountRegistry.SelfIdentity::nickname)
                .orElse("");
    }

    private static void applySelfOverrides(AccountRegistry accounts, AppConfig config) {
        for (AppConfig.Account account : config.qqconnect().accounts()) {
            if (account.selfQq().isBlank() && account.selfName().isBlank()) {
                continue;
            }
            accounts.self(account.accountId(),
                    new AccountRegistry.SelfIdentity(account.selfQq(), account.selfName()));
        }
    }

    private static InboxEntry entry(OneBotEvent event, long localSeq,
                                    QqExtrasStore.UserNote note, String accountId,
                                    QqDirectoryStore directory) {
        String sender = event.displayName().isBlank()
                ? Long.toString(event.userId()) : event.displayName();
        if (note != null && !note.displayName().isBlank()) {
            sender = note.displayName();
        }
        StringBuilder body = new StringBuilder();
        List<String> markers = new ArrayList<>();
        if (!event.senderCard().isBlank() && !event.senderName().isBlank()
                && !event.senderCard().equals(event.senderName())) {
            markers.add("[昵称:" + event.senderName() + "]");
        }
        if (event.groupId() > 0L && directory != null) {
            directory.member(accountId, Long.toString(event.groupId()),
                            Long.toString(event.userId()))
                    .map(QqDirectoryStore.Member::title)
                    .filter(title -> !title.isBlank())
                    .ifPresent(title -> markers.add("[头衔:" + title + "]"));
        }
        for (var segment : event.segments()) {
            String type = segment.type();
            if ("text".equals(type)) {
                if (segment.text() != null && !segment.text().isBlank()) {
                    if (body.length() > 0) {
                        body.append('\n');
                    }
                    body.append(segment.text());
                }
                continue;
            }
            switch (type) {
                case "at" -> markers.add(segment.text() == null || segment.text().isBlank()
                        ? "[@]" : "[@" + segment.text() + "]");
                case "reply" -> markers.add(segment.text() == null || segment.text().isBlank()
                        ? "[引用]" : "[引用 #" + segment.text() + "]");
                case "image" -> markers.add("[图片]");
                case "file" -> markers.add("[文件]");
                case "record" -> markers.add("[语音]");
                case "video" -> markers.add("[视频]");
                case "forward" -> markers.add("[合并转发]");
                case "json", "xml" -> {
                    String title = segment.data("title");
                    markers.add(title == null || title.isBlank()
                            ? "[卡片]" : "[卡片] " + title);
                }
                default -> markers.add("[" + type + "]");
            }
        }
        return new InboxEntry(localSeq, event.at(), sender, body.toString(), markers);
    }

    private static List<AccountConfig> resolveAccounts(AppConfig config,
                                                       Map<String, String> env) {
        List<AccountConfig> resolved = new ArrayList<>();
        for (AppConfig.Account account : config.qqconnect().accounts()) {
            if (!account.enabled()) {
                continue;
            }
            String token = account.tokenEnv() == null || account.tokenEnv().isBlank()
                    ? ""
                    : secret(env, account.tokenEnv(), "qqconnect.accounts.tokenEnv");
            resolved.add(new AccountConfig(account.accountId(),
                    URI.create(account.endpoint()), token, true));
        }
        return resolved;
    }

    private static String secret(Map<String, String> env, String name, String field) {
        String value = name == null ? null : env.get(name);
        if (value == null || value.isBlank()) {
            throw new ConfigException(field, "环境变量未设置: " + name);
        }
        return value;
    }

    public int mcpPort() {
        return mcp.boundPort();
    }

    public int healthPort() {
        return health.boundPort();
    }

    public int inboxHttpPort() {
        return inboxHttp.boundPort();
    }

    public InboxService inboxService() {
        return inboxService;
    }

    /** App-owned official-channel composition, exposed for focused integration diagnostics/tests. */
    public OfficialQqService officialService() {
        return official;
    }

    /** App-owned channel coordinator; exposed for local integration diagnostics/tests. */
    public QqChannelRuntime channelRuntime() {
        return channels;
    }

    @Override
    public void close() {
        if (watchdog != null) {
            watchdog.close();
        }
        if (browser != null) {
            browser.close();
        }
        if (xvfb != null) {
            xvfb.close();
        }
        if (schedule != null) {
            schedule.close();
        }
        if (watcher != null) {
            watcher.close();
        }
        if (mcp != null) {
            mcp.close();
        }
        if (official != null) {
            official.close();
        }
        accounts.close();
        if (channels != null) {
            channels.close();
        }
        if (qqExtras != null) {
            qqExtras.close();
        }
        if (directory != null) {
            directory.close();
        }
        if (inboxService != null) {
            inboxService.close();
        }
        inboxHttp.close();
        health.close();
        inboxStore.close();
        contextStore.close();
        memoryStore.close();
        board.close();
        if (instanceLock != null) {
            instanceLock.close();
        }
    }
}
