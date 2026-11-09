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
        Weighting weighting,
        Logging logging) {

    public AppConfig {
        dataDir = dataDir == null ? Path.of("data") : dataDir;
        server = server == null ? Server.defaults() : server;
        qqconnect = qqconnect == null ? new QqConnect(List.of()) : qqconnect;
        inbox = inbox == null ? Inbox.defaults() : inbox;
        webconnect = webconnect == null ? Webconnect.defaults() : webconnect;
        weighting = weighting == null ? Weighting.defaults() : weighting;
        logging = logging == null ? Logging.defaults() : logging;
    }

    public static AppConfig defaults() {
        return new AppConfig(Path.of("data"), Server.defaults(),
                new QqConnect(List.of()), Inbox.defaults(), Webconnect.defaults(),
                Weighting.defaults(), Logging.defaults());
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
        this(dataDir, server, qqconnect, inbox, Webconnect.defaults(),
                Weighting.defaults(), logging);
    }

    /** 6-arg compatibility constructor for profiles created before weighting. */
    public AppConfig(Path dataDir, Server server, QqConnect qqconnect, Inbox inbox,
                     Webconnect webconnect, Logging logging) {
        this(dataDir, server, qqconnect, inbox, webconnect, Weighting.defaults(), logging);
    }

    /**
     * Last iibot priority model: source weight decides the priority level; signals are
     * diagnostics only. Normal messages wait; important messages use the short delay.
     */
    public record Weighting(int privateSourceWeight, int groupSourceWeight,
                            int notifyThreshold, long normalDelaySeconds,
                            long importantDelaySeconds) {

        public Weighting {
            if (privateSourceWeight < -1000 || privateSourceWeight > 1000
                    || groupSourceWeight < -1000 || groupSourceWeight > 1000
                    || notifyThreshold < -1000 || notifyThreshold > 1000) {
                throw new IllegalArgumentException("weighting 权重必须在 -1000..1000");
            }
            if (normalDelaySeconds < 0 || normalDelaySeconds > 600
                    || importantDelaySeconds < 0 || importantDelaySeconds > 600) {
                throw new IllegalArgumentException("weighting 延迟必须在 0..600 秒");
            }
        }

        public static Weighting defaults() {
            return new Weighting(300, 0, 200, 30, 1);
        }
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
