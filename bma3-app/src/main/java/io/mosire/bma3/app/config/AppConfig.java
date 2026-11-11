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

    public record QqConnect(List<Account> accounts, Send send, Media media) {

        public QqConnect {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            send = send == null ? Send.defaults() : send;
            media = media == null ? Media.defaults() : media;
        }

        public QqConnect(List<Account> accounts) {
            this(accounts, Send.defaults(), Media.defaults());
        }

        public QqConnect(List<Account> accounts, Send send) {
            this(accounts, send, Media.defaults());
        }

        /**
         * On-demand media limits. {@code dir} blank means {@code <dataDir>/downloads}; the file id
         * itself is never a path, so nothing here can be influenced by a remote peer.
         */
        public record Media(String dir, long maxInlineBytes, long maxDownloadBytes,
                            int timeoutSeconds) {

            public Media {
                dir = dir == null ? "" : dir;
                if (maxInlineBytes <= 0) {
                    throw new IllegalArgumentException("qqconnect.media.maxInlineBytes 必须为正");
                }
                if (maxDownloadBytes <= 0) {
                    throw new IllegalArgumentException("qqconnect.media.maxDownloadBytes 必须为正");
                }
                if (timeoutSeconds < 1 || timeoutSeconds > 300) {
                    throw new IllegalArgumentException("qqconnect.media.timeoutSeconds 必须在 1..300");
                }
            }

            public static Media defaults() {
                return new Media("", 64L * 1024, 32L * 1024 * 1024, 20);
            }
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

    /**
     * One OneBot account. {@code selfQq}/{@code selfName} are optional overrides for the bot's own
     * identity; when blank, the identity is learned from {@code get_login_info} on connect.
     */
    public record Account(String accountId, String endpoint, String tokenEnv, boolean enabled,
                          String selfQq, String selfName) {

        public Account {
            selfQq = selfQq == null ? "" : selfQq;
            selfName = selfName == null ? "" : selfName;
        }

        /** Compatibility constructor: identity is learned at runtime. */
        public Account(String accountId, String endpoint, String tokenEnv, boolean enabled) {
            this(accountId, endpoint, tokenEnv, enabled, "", "");
        }
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
     * Priority model: a most-specific-wins {@code sourcePriority} rule table plus additive
     * {@code attributes}. A message's score decides three things only — whether it is enqueued at
     * all ({@code dropBelow}), how fast the panel is pushed ({@code immediateThreshold} and the two
     * delays) and how items are ordered.
     *
     * <p>Every value here is editable through {@code set_config}; this record is the single source
     * of truth for scores (per-source weights and user-note priorities no longer exist).</p>
     */
    public record Weighting(
            int immediateThreshold,
            int dropBelow,
            int maxPriority,
            long normalDelaySeconds,
            long importantDelaySeconds,
            java.util.Map<String, Integer> sourcePriority,
            java.util.Map<String, Integer> attributes) {

        public Weighting {
            if (maxPriority < -1000 || maxPriority > 1000) {
                throw new IllegalArgumentException("weighting.maxPriority 必须在 -1000..1000");
            }
            if (dropBelow < -1000 || dropBelow > 1000) {
                throw new IllegalArgumentException("weighting.dropBelow 必须在 -1000..1000");
            }
            if (immediateThreshold < -1000 || immediateThreshold > 1000) {
                throw new IllegalArgumentException("weighting.immediateThreshold 必须在 -1000..1000");
            }
            if (dropBelow > immediateThreshold) {
                throw new IllegalArgumentException("weighting.dropBelow 不能大于 immediateThreshold");
            }
            if (normalDelaySeconds < 0 || normalDelaySeconds > 600
                    || importantDelaySeconds < 0 || importantDelaySeconds > 600) {
                throw new IllegalArgumentException("weighting 延迟必须在 0..600 秒");
            }
            sourcePriority = copyWeights(sourcePriority, "weighting.sourcePriority");
            attributes = copyWeights(attributes, "weighting.attributes");
        }

        public static Weighting defaults() {
            return new Weighting(300, -300, 1000, 30, 1, defaultSourcePriority(),
                    defaultAttributes());
        }

        public static java.util.Map<String, Integer> defaultSourcePriority() {
            java.util.Map<String, Integer> rules = new java.util.LinkedHashMap<>();
            rules.put("*", 0);
            rules.put("qq_private", 300);
            rules.put("qq_group", 200);
            return java.util.Map.copyOf(rules);
        }

        public static java.util.Map<String, Integer> defaultAttributes() {
            java.util.Map<String, Integer> weights = new java.util.LinkedHashMap<>();
            weights.put("qq_at_bot", 100);
            weights.put("qq_at_all", 20);
            weights.put("qq_at_other", 0);
            weights.put("qq_reply_to_bot", 100);
            weights.put("qq_self_name", 60);
            weights.put("qq_command", 100);
            return java.util.Map.copyOf(weights);
        }

        public int attribute(String key) {
            Integer value = attributes.get(key);
            return value == null ? 0 : value;
        }

        private static java.util.Map<String, Integer> copyWeights(java.util.Map<String, Integer> raw,
                                                                 String field) {
            java.util.Map<String, Integer> values = new java.util.LinkedHashMap<>();
            if (raw != null) {
                raw.forEach((key, weight) -> {
                    if (key == null || key.isBlank() || weight == null
                            || weight < -1000 || weight > 1000) {
                        throw new IllegalArgumentException(field + " 非法: " + key);
                    }
                    values.put(key, weight);
                });
            }
            return java.util.Map.copyOf(values);
        }
    }

    public record Inbox(String inboxId, int httpPort, Display display, Push push,
                        Retention retention) {

        public Inbox {
            inboxId = inboxId == null || inboxId.isBlank() ? "bma3" : inboxId;
            display = display == null ? Display.defaults() : display;
            push = push == null ? Push.defaults() : push;
            retention = retention == null ? Retention.defaults() : retention;
        }

        public Inbox(String inboxId, int httpPort, Display display) {
            this(inboxId, httpPort, display, Push.defaults(), Retention.defaults());
        }

        public Inbox(String inboxId, int httpPort) {
            this(inboxId, httpPort, Display.defaults());
        }

        public static Inbox defaults() {
            return new Inbox("bma3", 46500, Display.defaults(), Push.defaults(),
                    Retention.defaults());
        }

        /** Push filter: items below {@code minPriority} stay visible but never wake the agent. */
        public record Push(int minPriority) {

            public Push {
                if (minPriority < -1000 || minPriority > 1000) {
                    throw new IllegalArgumentException("inbox.push.minPriority 必须在 -1000..1000");
                }
            }

            public static Push defaults() {
                return new Push(-1000);
            }
        }

        /** Retention: ACKED items are purged by age, then by count. Unacked rows are never purged. */
        public record Retention(int maxAgeDays, int maxItems) {

            public Retention {
                if (maxAgeDays < 1 || maxAgeDays > 3650) {
                    throw new IllegalArgumentException("inbox.retention.maxAgeDays 必须在 1..3650");
                }
                if (maxItems < 1 || maxItems > 1_000_000) {
                    throw new IllegalArgumentException("inbox.retention.maxItems 必须在 1..1000000");
                }
            }

            public static Retention defaults() {
                return new Retention(30, 5000);
            }
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
