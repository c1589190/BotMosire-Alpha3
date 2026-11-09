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
    private final ConfigWatcher watcher;

    private Bma3App(AppConfig config, SqliteMessageBoardStore board,
                   SqliteInboxStore inboxStore, InboxService inboxService,
                   HealthServer health, InboxHttpServer inboxHttp,
                   AccountRegistry accounts, Bma3McpServer mcp,
                   BrowserService browser, ConfigWatcher watcher) {
        this.config = config;
        this.board = board;
        this.inboxStore = inboxStore;
        this.inboxService = inboxService;
        this.health = health;
        this.inboxHttp = inboxHttp;
        this.accounts = accounts;
        this.mcp = mcp;
        this.browser = browser;
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
        InboxService inboxService = new InboxService(inboxStore,
                new DeliveryPolicy(200, Duration.ofSeconds(1),
                        Duration.ofSeconds(30), Duration.ofMinutes(10), 3));
        HealthServer health = new HealthServer();
        InboxHttpServer inboxHttp = new InboxHttpServer(
                config.server().host(), config.inbox().httpPort(),
                config.inbox().inboxId(), mcpToken, inboxService);
        AccountRegistry accounts = new AccountRegistry(
                event -> ingest(board, inboxService, event));
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
            tools.addAll(QqTools.all(new QqToolContext(accounts, board, board)));
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
                    config.server().mcpPath(), mcpToken, "bma3", "0.1.0", tools);
            mcp.start();
            return new Bma3App(config, board, inboxStore, inboxService, health,
                    inboxHttp, accounts, mcp, browser, watcher);
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
            inboxHttp.close();
            health.close();
            inboxStore.close();
            board.close();
            throw failure;
        }
    }

    private static void ingest(SqliteMessageBoardStore board, InboxService inboxService,
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
        String summary = event.text().isBlank() ? "[非文本消息]" : event.text();
        int priority = "qq_private".equals(source.platform()) ? 300 : 0;
        inboxService.append(new AppendItem(source.format(), summary, priority, Instant.now()));
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
