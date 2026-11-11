package io.mosire.bma3.app;

import io.mosire.bma3.agentlib.Bma3McpServer;
import io.mosire.bma3.app.config.AppConfig;
import io.mosire.bma3.app.config.ConfigWatcher;
import io.mosire.bma3.app.config.RuntimePolicy;
import io.mosire.bma3.app.priority.PriorityDecision;
import io.mosire.bma3.app.priority.PriorityResolver;
import io.mosire.bma3.app.tools.ConfigToolContext;
import io.mosire.bma3.app.tools.GetConfigTool;
import io.mosire.bma3.app.tools.SetConfigTool;
import io.mosire.bma3.core.config.ConfigException;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.priority.PriorityAttributes;
import io.mosire.bma3.core.priority.SourceKeyer;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.source.SourceLabels;
import io.mosire.bma3.core.source.SourceView;
import io.mosire.bma3.core.store.AppendResult;
import io.mosire.bma3.core.store.MessageBoardStore;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import io.mosire.bma3.inbox.core.AppendItem;
import io.mosire.bma3.inbox.core.DeliveryPolicy;
import io.mosire.bma3.inbox.core.InboxEntry;
import io.mosire.bma3.inbox.core.InboxService;
import io.mosire.bma3.inbox.core.SqliteInboxStore;
import io.mosire.bma3.inbox.http.InboxHttpServer;
import io.mosire.bma3.inbox.tools.InboxTools;
import io.mosire.bma3.qqconnect.account.AccountConfig;
import io.mosire.bma3.qqconnect.account.AccountEvent;
import io.mosire.bma3.qqconnect.account.AccountRegistry;
import io.mosire.bma3.qqconnect.onebot.OneBotEvent;
import io.mosire.bma3.qqconnect.tools.QqExtrasStore;
import io.mosire.bma3.qqconnect.tools.QqSendPolicy;
import io.mosire.bma3.qqconnect.tools.QqSignalExtractor;
import io.mosire.bma3.qqconnect.tools.QqSourceKeyer;
import io.mosire.bma3.qqconnect.tools.QqSourceLabeler;
import io.mosire.bma3.qqconnect.tools.QqToolContext;
import io.mosire.bma3.qqconnect.tools.QqTools;
import io.mosire.bma3.webconnect.BrowserOptions;
import io.mosire.bma3.webconnect.BrowserService;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Composition root for the one BMA3 service process. */
public final class Bma3App implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Bma3App.class);

    private final AppConfig config;
    private final SqliteMessageBoardStore board;
    private final SqliteInboxStore inboxStore;
    private final InboxService inboxService;
    private final HealthServer health;
    private final InboxHttpServer inboxHttp;
    private final AccountRegistry accounts;
    private final Bma3McpServer mcp;
    private final BrowserService browser;
    private final QqExtrasStore qqExtras;
    private final ConfigWatcher watcher;

    private Bma3App(AppConfig config, SqliteMessageBoardStore board,
                    SqliteInboxStore inboxStore, InboxService inboxService,
                    HealthServer health, InboxHttpServer inboxHttp,
                    AccountRegistry accounts, Bma3McpServer mcp,
                    BrowserService browser, QqExtrasStore qqExtras,
                    ConfigWatcher watcher) {
        this.config = config;
        this.board = board;
        this.inboxStore = inboxStore;
        this.inboxService = inboxService;
        this.health = health;
        this.inboxHttp = inboxHttp;
        this.accounts = accounts;
        this.mcp = mcp;
        this.browser = browser;
        this.qqExtras = qqExtras;
        this.watcher = watcher;
    }

    public static Bma3App start(AppConfig config) {
        return start(config, System.getenv());
    }

    public static Bma3App start(AppConfig config, Map<String, String> env) {
        return start(config, env, Path.of("."));
    }

    public static Bma3App start(AppConfig config, Map<String, String> env, Path configRoot) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(configRoot, "configRoot");
        String mcpToken = secret(env, config.server().tokenEnv(), "server.tokenEnv");
        try {
            Files.createDirectories(config.dataDir());
        } catch (Exception failure) {
            throw new IllegalStateException("创建 dataDir 失败", failure);
        }

        SqliteMessageBoardStore board = new SqliteMessageBoardStore(
                config.dataDir().resolve("bot.db"));
        SqliteInboxStore inboxStore = new SqliteInboxStore(
                config.dataDir().resolve("inbox.db"));
        AppConfig.Weighting weighting = config.weighting();
        AtomicReference<AppConfig> current = new AtomicReference<>(config);
        AtomicReference<RuntimePolicy> runtime =
                new AtomicReference<>(RuntimePolicy.from(config));
        SourceLabels sourceLabels = SourceLabels.builder()
                .register(new QqSourceLabeler()).build();
        SourceKeyer sourceKeyer = new QqSourceKeyer();
        PriorityResolver priorities = new PriorityResolver(() -> runtime.get().weighting());
        DeliveryPolicy deliveryPolicy = new DeliveryPolicy(
                weighting.immediateThreshold(),
                Duration.ofSeconds(weighting.importantDelaySeconds()),
                Duration.ofSeconds(weighting.normalDelaySeconds()));
        InboxService inboxService = new InboxService(
                inboxStore, deliveryPolicy, config.inbox().inboxId(), () -> {
                    var policy = runtime.get();
                    return new io.mosire.bma3.inbox.core.InboxRuntime(
                            policy.display().maxMessagesPerItem(),
                            policy.display().maxItemsPerPanel(),
                            policy.display().includeOmittedCount(),
                            policy.push().minPriority(),
                            policy.retention().maxAgeDays(),
                            policy.retention().maxItems());
                });
        HealthServer health = new HealthServer();
        InboxHttpServer inboxHttp = new InboxHttpServer(
                config.server().host(), config.inbox().httpPort(),
                config.inbox().inboxId(), mcpToken, inboxService);
        QqExtrasStore qqExtras = new QqExtrasStore(config.dataDir().resolve("qq-extras.db"));
        AtomicReference<AccountRegistry> registry = new AtomicReference<>();
        AccountRegistry accounts = new AccountRegistry(event -> ingest(board, inboxService,
                priorities, sourceKeyer, registry.get(), current, qqExtras, sourceLabels, event));
        registry.set(accounts);
        ConfigWatcher watcher = null;
        Bma3McpServer mcp = null;
        BrowserService browser = null;
        try {
            inboxHttp.start();
            List<AccountConfig> resolved = resolveAccounts(config, env);
            accounts.reconcile(resolved);
            applySelfOverrides(accounts, config);
            watcher = ConfigWatcher.watch(configRoot, env, next -> {
                accounts.reconcile(resolveAccounts(next, env));
                applySelfOverrides(accounts, next);
                current.set(next);
                runtime.set(RuntimePolicy.from(next));
            });

            ConfigToolContext configTools = new ConfigToolContext(configRoot, env, current::get);
            List<io.mosire.agentlib.tool.AgentTool> tools = new ArrayList<>();
            tools.addAll(QqTools.all(new QqToolContext(
                    accounts, board, board, config.dataDir(), qqExtras,
                    () -> {
                        var send = runtime.get().send();
                        return QqSendPolicy.from(send.maxTextChars(), send.overflow());
                    },
                    () -> {
                        var media = runtime.get().media();
                        Path dir = media.dir().isBlank()
                                ? config.dataDir().resolve("downloads") : Path.of(media.dir());
                        return new io.mosire.bma3.qqconnect.tools.QqMediaPolicy(dir,
                                media.maxInlineBytes(), media.maxDownloadBytes(),
                                Duration.ofSeconds(media.timeoutSeconds()));
                    })));
            tools.addAll(InboxTools.all(inboxService));
            tools.add(new GetConfigTool(configTools));
            tools.add(new SetConfigTool(configTools));
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
            mcp = new Bma3McpServer(config.server().host(), config.server().port(),
                    config.server().mcpPath(), mcpToken, "bma3", "0.0.3-hotfix4", tools)
                    .authHint(config.server().tokenEnv());
            mcp.start();
            return new Bma3App(config, board, inboxStore, inboxService, health,
                    inboxHttp, accounts, mcp, browser, qqExtras, watcher);
        } catch (RuntimeException failure) {
            if (mcp != null) {
                mcp.close();
            }
            if (browser != null) {
                browser.close();
            }
            if (watcher != null) {
                watcher.close();
            }
            accounts.close();
            qqExtras.close();
            inboxHttp.close();
            health.close();
            inboxStore.close();
            board.close();
            throw failure;
        }
    }

    /**
     * Priority first, persistence second: a message scored below {@code dropBelow} is discarded
     * outright (operator ruling) and never reaches the board, so it can never be replayed or
     * counted later.
     */
    private static void ingest(MessageBoardStore board, InboxService inboxService,
                               PriorityResolver priorities, SourceKeyer sourceKeyer,
                               AccountRegistry accounts, AtomicReference<AppConfig> current,
                               QqExtrasStore extras, SourceLabels sourceLabels,
                               AccountEvent accountEvent) {
        OneBotEvent event = accountEvent.event();
        SourceAddress source = event.source(accountEvent.accountId());
        SourceView view = sourceLabels.view(source);
        String sourceKey = sourceKeyer.keyFor(source);
        String senderId = Long.toString(event.userId());
        boolean replyToBot = isReplyToBot(board, source, event.replyToMessageId());
        PriorityAttributes attributes = QqSignalExtractor.attributes(event,
                selfName(accounts, current.get(), accountEvent.accountId()), replyToBot);
        PriorityDecision decision = priorities.resolve(sourceKey, senderId,
                event.senderName(), attributes);
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
        AppendResult appended = board.append(new InboundMessage(
                source,
                Long.toString(event.messageId()),
                event.at(),
                event.segments(),
                Direction.INBOUND,
                senderId,
                event.senderName()));
        if (!appended.created()) {
            return;
        }
        var note = extras.note(accountEvent.accountId(), senderId).orElse(null);
        InboxEntry entry = entry(event, appended.message().localSeq(), note);
        inboxService.appendOrMerge(AppendItem.of(view, entry, decision.priority(), Instant.now()));
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
                                    QqExtrasStore.UserNote note) {
        String sender = event.senderName() == null || event.senderName().isBlank()
                ? Long.toString(event.userId()) : event.senderName();
        if (note != null && !note.displayName().isBlank()) {
            sender = note.displayName();
        }
        StringBuilder body = new StringBuilder();
        List<String> markers = new ArrayList<>();
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

    @Override
    public void close() {
        if (browser != null) {
            browser.close();
        }
        if (qqExtras != null) {
            qqExtras.close();
        }
        if (watcher != null) {
            watcher.close();
        }
        accounts.close();
        if (mcp != null) {
            mcp.close();
        }
        if (inboxService != null) {
            inboxService.close();
        }
        inboxHttp.close();
        health.close();
        inboxStore.close();
        board.close();
    }
}
