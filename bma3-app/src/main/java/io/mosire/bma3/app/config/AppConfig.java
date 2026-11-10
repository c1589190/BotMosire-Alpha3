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

    public record QqConnect(List<Account> accounts, Send send) {

        public QqConnect {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            send = send == null ? Send.defaults() : send;
        }

        public QqConnect(List<Account> accounts) {
            this(accounts, Send.defaults());
        }

        public record Send(int maxTextChars, String overflow) {

            public Send {
                overflow = overflow == null || overflow.isBlank() ? "reject" : overflow;
                if (maxTextChars < 1 || maxTextChars > 3000) {
                    throw new IllegalArgumentException("qqconnect.send.maxTextChars 必须在 1..3000");
                }
                if (!"reject".equals(overflow) && !"split".equals(overflow)) {
                    throw new IllegalArgumentException("qqconnect.send.overflow 必须是 reject/split");
                }
            }

            public static Send defaults() {
                return new Send(3000, "reject");
            }
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
     * Generic priority model: platform defaults plus per-source weight/threshold, user note
     * and adapter signals. Signals are no longer diagnostics-only; they can break the
     * default group silence (for example an @ mention).
     */
    public record Weighting(int privateSourceWeight, int groupSourceWeight,
                            int notifyThreshold, long normalDelaySeconds,
                            long importantDelaySeconds,
                            java.util.Map<String, Integer> platformWeights,
                            SignalWeights signals) {

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
            java.util.Map<String, Integer> weights = new java.util.LinkedHashMap<>();
            if (platformWeights != null) {
                platformWeights.forEach((platform, weight) -> {
                    if (platform == null || platform.isBlank()
                            || weight == null || weight < -1000 || weight > 1000) {
                        throw new IllegalArgumentException("weighting.platformWeights 非法: " + platform);
                    }
                    weights.put(platform, weight);
                });
            }
            weights.putIfAbsent("qq_private", privateSourceWeight);
            weights.putIfAbsent("qq_group", groupSourceWeight);
            platformWeights = java.util.Map.copyOf(weights);
            signals = signals == null ? SignalWeights.defaults() : signals;
        }

        public Weighting(int privateSourceWeight, int groupSourceWeight,
                         int notifyThreshold, long normalDelaySeconds,
                         long importantDelaySeconds) {
            this(privateSourceWeight, groupSourceWeight, notifyThreshold,
                    normalDelaySeconds, importantDelaySeconds, null, null);
        }

        public static Weighting defaults() {
            return new Weighting(300, 0, 200, 30, 1);
        }

        public int platformWeight(String platform) {
            return platformWeights.getOrDefault(platform, 0);
        }

        public record SignalWeights(int mention, int reply, int name, int command) {

            public SignalWeights {
                if (mention < 0 || reply < 0 || name < 0 || command < 0) {
                    throw new IllegalArgumentException("weighting.signals 不能为负");
                }
            }

            public static SignalWeights defaults() {
                return new SignalWeights(300, 300, 200, 300);
            }
        }
    }

    public record Inbox(String inboxId, int httpPort, Display display) {

        public Inbox {
            inboxId = inboxId == null || inboxId.isBlank() ? "bma3" : inboxId;
            display = display == null ? Display.defaults() : display;
        }

        public Inbox(String inboxId, int httpPort) {
            this(inboxId, httpPort, Display.defaults());
        }

        public static Inbox defaults() {
            return new Inbox("bma3", 46500, Display.defaults());
        }

        public record Display(int maxMessagesPerItem, int maxItemsPerPanel,
                              boolean includeOmittedCount) {

            public Display {
                if (maxMessagesPerItem < 4 || maxMessagesPerItem > 8) {
                    throw new IllegalArgumentException("inbox.display.maxMessagesPerItem 必须在 4..8");
                }
                if (maxItemsPerPanel < 4 || maxItemsPerPanel > 8) {
                    throw new IllegalArgumentException("inbox.display.maxItemsPerPanel 必须在 4..8");
                }
            }

            public static Display defaults() {
                return new Display(6, 6, true);
            }
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
