package io.mosire.bma3.app;

import io.mosire.bma3.agentlib.Bma3McpServer;
import io.mosire.bma3.app.config.AppConfig;
import io.mosire.bma3.app.config.ConfigWatcher;
import io.mosire.bma3.core.config.ConfigException;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.AppendResult;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import io.mosire.bma3.inbox.core.AppendItem;
import io.mosire.bma3.inbox.core.DeliveryPolicy;
import io.mosire.bma3.inbox.core.InboxService;
import io.mosire.bma3.inbox.core.SqliteInboxStore;
import io.mosire.bma3.inbox.http.InboxHttpServer;
import io.mosire.bma3.inbox.tools.InboxTools;
import io.mosire.bma3.qqconnect.account.AccountConfig;
import io.mosire.bma3.qqconnect.account.AccountEvent;
import io.mosire.bma3.qqconnect.account.AccountRegistry;
import io.mosire.bma3.qqconnect.onebot.OneBotEvent;
import io.mosire.bma3.qqconnect.tools.QqToolContext;
import io.mosire.bma3.qqconnect.tools.QqExtrasStore;
import io.mosire.bma3.qqconnect.tools.QqTools;
import io.mosire.bma3.webconnect.BrowserOptions;
import io.mosire.bma3.webconnect.BrowserService;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Composition root for the one BMA3 service process. */
public final class Bma3App implements AutoCloseable {

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
        DeliveryPolicy deliveryPolicy = new DeliveryPolicy(
                weighting.notifyThreshold(),
                Duration.ofSeconds(weighting.importantDelaySeconds()),
                Duration.ofSeconds(weighting.normalDelaySeconds()),
                Duration.ofMinutes(10), 3);
        InboxService inboxService = new InboxService(inboxStore, deliveryPolicy);
        HealthServer health = new HealthServer();
        InboxHttpServer inboxHttp = new InboxHttpServer(
                config.server().host(), config.inbox().httpPort(),
                config.inbox().inboxId(), mcpToken, inboxService);
        QqExtrasStore qqExtras = new QqExtrasStore(config.dataDir().resolve("qq-extras.db"));
        AccountRegistry accounts = new AccountRegistry(
                event -> ingest(board, inboxService, weighting, qqExtras, event));
        ConfigWatcher watcher = null;
        Bma3McpServer mcp = null;
        BrowserService browser = null;
        try {
            inboxHttp.start();
            List<AccountConfig> resolved = resolveAccounts(config, env);
            accounts.reconcile(resolved);
            watcher = ConfigWatcher.watch(configRoot, env,
                    next -> accounts.reconcile(resolveAccounts(next, env)));

            List<io.mosire.agentlib.tool.AgentTool> tools = new ArrayList<>();
            tools.addAll(QqTools.all(new QqToolContext(
                    accounts, board, board, config.dataDir(), qqExtras)));
            tools.addAll(InboxTools.all(inboxService));
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
                    config.server().mcpPath(), mcpToken, "bma3", "0.0.1-hotfix1", tools);
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

    private static void ingest(SqliteMessageBoardStore board, InboxService inboxService,
                               AppConfig.Weighting weighting, QqExtrasStore extras,
                               AccountEvent accountEvent) {
        OneBotEvent event = accountEvent.event();
        SourceAddress source = event.source(accountEvent.accountId());
        AppendResult appended = board.append(new InboundMessage(
                source,
                Long.toString(event.messageId()),
                event.at(),
                event.segments(),
                Direction.INBOUND));
        if (!appended.created()) {
            return;
        }
        String summary = renderLine(event, appended.message().localSeq(), extras,
                source.accountId());
        boolean privateChat = "qq_private".equals(source.platform());
        int priority = privateChat
                ? weighting.privateSourceWeight() : weighting.groupSourceWeight();
        var sourcePolicy = extras.sourceWeight(source.format()).orElse(null);
        if (sourcePolicy != null) {
            priority = sourcePolicy.weight();
        }
        var note = extras.note(source.accountId(), Long.toString(event.userId())).orElse(null);
        if (note != null) {
            priority = Math.max(priority, note.priority());
        }
        if (!privateChat && priority < weighting.notifyThreshold()) {
            // Keep ordinary group traffic on the message board; it must not wake a session.
            // Group wake needs an explicit source policy/user-note priority above the threshold.
            return;
        }
        // The Inbox delivery policy applies the important/normal delay from priority;
        // availableAt stays the raw arrival time so the delay is not applied twice.
        inboxService.appendOrMerge(new AppendItem(source.format(), summary, priority, Instant.now()));
    }

    private static String renderLine(OneBotEvent event, long localSeq,
                                    QqExtrasStore extras, String accountId) {
        var when = event.at().atZone(ZoneId.systemDefault());
        String stamp = when.toLocalDate().equals(LocalDate.now(ZoneId.systemDefault()))
                ? when.format(DateTimeFormatter.ofPattern("HH:mm"))
                : when.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        String sender = event.senderName() == null || event.senderName().isBlank()
                ? Long.toString(event.userId()) : event.senderName();
        var note = extras.note(accountId, Long.toString(event.userId())).orElse(null);
        if (note != null && !note.displayName().isBlank()) {
            sender = note.displayName();
        }
        String qualifier = "";
        if (note != null && !note.tags().isEmpty()) {
            qualifier = "（" + note.tags().stream().map(tag -> "#" + tag)
                    .reduce((a, b) -> a + " " + b).orElse("") + "）";
        }
        String body = event.text().isBlank() ? segmentsText(event) : event.text();
        return "[" + stamp + "] #" + localSeq + " " + sender + qualifier + "：" + body;
    }

    private static String segmentsText(OneBotEvent event) {
        StringBuilder text = new StringBuilder();
        for (var segment : event.segments()) {
            String rendered = switch (segment.type()) {
                case "image" -> "[图片]";
                case "file" -> "[文件]";
                case "record" -> "[语音]";
                case "video" -> "[视频]";
                case "at" -> segment.text() == null || segment.text().isBlank()
                        ? "[@]" : "[@" + segment.text() + "]";
                default -> segment.text() == null ? "" : segment.text();
            };
            if (!rendered.isBlank()) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(rendered);
            }
        }
        return text.isEmpty() ? "[非文本消息]" : text.toString();
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
        inboxHttp.close();
        health.close();
        inboxStore.close();
        board.close();
    }
}
