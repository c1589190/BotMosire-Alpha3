package io.mosire.bma3.app.config;

import java.nio.file.Path;
import java.util.List;

/** Typed application configuration. Secrets are referenced by env name, not stored here. */
public record AppConfig(
        Path dataDir,
        Server server,
        QqConnect qqconnect,
        Inbox inbox,
        Webconnect webconnect,
        Logging logging) {

    public AppConfig {
        dataDir = dataDir == null ? Path.of("data") : dataDir;
        server = server == null ? Server.defaults() : server;
        qqconnect = qqconnect == null ? new QqConnect(List.of()) : qqconnect;
        inbox = inbox == null ? Inbox.defaults() : inbox;
        webconnect = webconnect == null ? Webconnect.defaults() : webconnect;
        logging = logging == null ? Logging.defaults() : logging;
    }

    public static AppConfig defaults() {
        return new AppConfig(Path.of("data"), Server.defaults(),
                new QqConnect(List.of()), Inbox.defaults(), Webconnect.defaults(),
                Logging.defaults());
    }

    public record Server(String host, int port, String mcpPath, String tokenEnv) {

        public Server {
            host = host == null || host.isBlank() ? "127.0.0.1" : host;
            mcpPath = mcpPath == null || mcpPath.isBlank() ? "/mcp" : mcpPath;
            tokenEnv = tokenEnv == null ? "BMA3_MCP_TOKEN" : tokenEnv;
        }

        public static Server defaults() {
            return new Server("127.0.0.1", 8765, "/mcp", "BMA3_MCP_TOKEN");
        }
    }

    public record QqConnect(List<Account> accounts) {

        public QqConnect {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
        }
    }

    public record Account(String accountId, String endpoint, String tokenEnv, boolean enabled) {
    }

    /** 5-arg compatibility constructor for tests/profiles created before webconnect. */
    public AppConfig(Path dataDir, Server server, QqConnect qqconnect, Inbox inbox,
                     Logging logging) {
        this(dataDir, server, qqconnect, inbox, Webconnect.defaults(), logging);
    }

    public record Inbox(String inboxId, int httpPort) {

        public Inbox {
            inboxId = inboxId == null || inboxId.isBlank() ? "bma3" : inboxId;
        }

        public static Inbox defaults() {
            return new Inbox("bma3", 46500);
        }
    }

    public record Webconnect(boolean enabled, String binary, boolean headless,
                             List<String> allowedHosts) {

        public Webconnect {
            binary = binary == null || binary.isBlank() ? "/snap/bin/chromium" : binary;
            allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        }

        public static Webconnect defaults() {
            return new Webconnect(false, "/snap/bin/chromium", true,
                    List.of("bilibili.com", "www.bilibili.com", "passport.bilibili.com",
                            "search.bilibili.com", "space.bilibili.com"));
        }
    }

    public record Logging(String level, String dir) {

        public Logging {
            level = level == null || level.isBlank() ? "INFO" : level;
            dir = dir == null || dir.isBlank() ? "logs" : dir;
        }

        public static Logging defaults() {
            return new Logging("INFO", "logs");
        }
    }
}
