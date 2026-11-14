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
        Logging logging,
        Deployer deployer,
        Watchdog watchdog,
        Xvfb xvfb,
        Security security) {

    public AppConfig {
        dataDir = dataDir == null ? Path.of("data") : dataDir;
        server = server == null ? Server.defaults() : server;
        qqconnect = qqconnect == null ? new QqConnect(List.of()) : qqconnect;
        inbox = inbox == null ? Inbox.defaults() : inbox;
        webconnect = webconnect == null ? Webconnect.defaults() : webconnect;
        weighting = weighting == null ? Weighting.defaults() : weighting;
        logging = logging == null ? Logging.defaults() : logging;
        deployer = deployer == null ? Deployer.defaults() : deployer;
        watchdog = watchdog == null ? Watchdog.defaults() : watchdog;
        xvfb = xvfb == null ? Xvfb.defaults() : xvfb;
        security = security == null ? Security.defaults() : security;
    }

    public static AppConfig defaults() {
        return new AppConfig(Path.of("data"), Server.defaults(),
                new QqConnect(List.of()), Inbox.defaults(), Webconnect.defaults(),
                Weighting.defaults(), Logging.defaults(), Deployer.defaults(),
                Watchdog.defaults(), Xvfb.defaults(), Security.defaults());
    }

    /**
     * Watchdog knobs. Plain values so the watchdog module never depends on this config class;
     * the app maps this record to {@code WatchdogOptions}.
     */
    public record Watchdog(boolean enabled, int intervalSeconds, int probeTimeoutMs,
                           int failureThreshold, int recoveryCooldownSeconds,
                           int alertCooldownSeconds, int pluginHeartbeatTimeoutSeconds,
                           long diskMinFreeMb, List<String> readyzCritical) {

        public Watchdog {
            if (intervalSeconds < 1 || intervalSeconds > 3600) {
                throw new IllegalArgumentException("watchdog.intervalSeconds 必须在 1..3600");
            }
            if (probeTimeoutMs < 100 || probeTimeoutMs > 60_000) {
                throw new IllegalArgumentException("watchdog.probeTimeoutMs 必须在 100..60000");
            }
            if (failureThreshold < 1 || failureThreshold > 100) {
                throw new IllegalArgumentException("watchdog.failureThreshold 必须在 1..100");
            }
            if (recoveryCooldownSeconds < 1 || recoveryCooldownSeconds > 86_400) {
                throw new IllegalArgumentException(
                        "watchdog.recoveryCooldownSeconds 必须在 1..86400");
            }
            if (alertCooldownSeconds < 1 || alertCooldownSeconds > 86_400) {
                throw new IllegalArgumentException(
                        "watchdog.alertCooldownSeconds 必须在 1..86400");
            }
            if (pluginHeartbeatTimeoutSeconds < 5 || pluginHeartbeatTimeoutSeconds > 3600) {
                throw new IllegalArgumentException(
                        "watchdog.pluginHeartbeatTimeoutSeconds 必须在 5..3600");
            }
            if (diskMinFreeMb < 0 || diskMinFreeMb > 1_000_000) {
                throw new IllegalArgumentException("watchdog.diskMinFreeMb 必须在 0..1000000");
            }
            readyzCritical = readyzCritical == null ? List.of() : List.copyOf(readyzCritical);
        }

        public static Watchdog defaults() {
            return new Watchdog(true, 10, 2000, 2, 30, 600, 45, 200,
                    List.of("mcp", "inboxHttp", "qqAccount", "db"));
        }
    }

    /**
     * The operator to alert when the watchdog detects a component going DOWN. Editable by the
     * Agent through {@code set_config}; the value is a QQ number, never a credential.
     */
    public record Deployer(String qq, boolean enabled) {

        public Deployer {
            qq = qq == null ? "" : qq.trim();
            if (!qq.isEmpty() && !qq.matches("[0-9]{5,12}")) {
                throw new IllegalArgumentException("deployer.qq 必须是 5..12 位数字的 QQ 号");
            }
        }

        public static Deployer defaults() {
            return new Deployer("", false);
        }

        public boolean configured() {
            return enabled && !qq.isEmpty();
        }
    }

    public record Server(String host, int port, String mcpPath, String tokenEnv,
                         int healthPort) {

        public Server {
            host = host == null || host.isBlank() ? "127.0.0.1" : host;
            mcpPath = mcpPath == null || mcpPath.isBlank() ? "/mcp" : mcpPath;
            tokenEnv = tokenEnv == null ? "BMA3_MCP_TOKEN" : tokenEnv;
            if (healthPort < 0 || healthPort > 65535) {
                throw new IllegalArgumentException("server.healthPort 必须在 0..65535");
            }
        }

        /** Compatibility constructor: random health port. */
        public Server(String host, int port, String mcpPath, String tokenEnv) {
            this(host, port, mcpPath, tokenEnv, 0);
        }

        public static Server defaults() {
            return new Server("127.0.0.1", 8765, "/mcp", "BMA3_MCP_TOKEN", 8766);
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

    /** Same configuration with a different security mode (used by {@code --no-auth}). */
    public AppConfig withSecurity(Security replacement) {
        return new AppConfig(dataDir, server, qqconnect, inbox, webconnect, weighting, logging,
                deployer, watchdog, xvfb, replacement);
    }

    /**
     * Startup security mode plus where the token bootstrap file lives.
     *
     * <p>{@code auth=token} (default) requires {@code MCP_TOKEN}/{@code INBOX_TOKEN} in the env file;
     * {@code auth=none} skips Bearer checks entirely and is only reachable by explicit request
     * (D-064). Loopback binding stays mandatory either way.</p>
     */
    public record Security(String auth, String envFile) {

        public static final String AUTH_TOKEN = "token";
        public static final String AUTH_NONE = "none";

        public Security {
            String normalized = auth == null || auth.isBlank() ? AUTH_TOKEN : auth.trim().toLowerCase(java.util.Locale.ROOT);
            if (!AUTH_TOKEN.equals(normalized) && !AUTH_NONE.equals(normalized)) {
                throw new IllegalArgumentException("security.auth 只支持 token 或 none");
            }
            auth = normalized;
            envFile = envFile == null ? "" : envFile.trim();
        }

        public static Security defaults() {
            return new Security(AUTH_TOKEN, "");
        }

        /** Whether Bearer tokens are mandatory for this process. */
        public boolean requiresToken() {
            return AUTH_TOKEN.equals(auth);
        }
    }

    /** 5-arg compatibility constructor for tests/profiles created before webconnect. */
    public AppConfig(Path dataDir, Server server, QqConnect qqconnect, Inbox inbox,
                     Logging logging) {
        this(dataDir, server, qqconnect, inbox, Webconnect.defaults(),
                Weighting.defaults(), logging, Deployer.defaults(), Watchdog.defaults(),
                Xvfb.defaults(), Security.defaults());
    }

    /** 6-arg compatibility constructor for profiles created before weighting. */
    public AppConfig(Path dataDir, Server server, QqConnect qqconnect, Inbox inbox,
                     Webconnect webconnect, Logging logging) {
        this(dataDir, server, qqconnect, inbox, webconnect, Weighting.defaults(), logging,
                Deployer.defaults(), Watchdog.defaults(), Xvfb.defaults(),
                Security.defaults());
    }

    /** 7-arg compatibility constructor for profiles created before the deployer alert. */
    public AppConfig(Path dataDir, Server server, QqConnect qqconnect, Inbox inbox,
                     Webconnect webconnect, Weighting weighting, Logging logging) {
        this(dataDir, server, qqconnect, inbox, webconnect, weighting, logging,
                Deployer.defaults(), Watchdog.defaults(), Xvfb.defaults(),
                Security.defaults());
    }

    /** 8-arg compatibility constructor for profiles created before the watchdog. */
    public AppConfig(Path dataDir, Server server, QqConnect qqconnect, Inbox inbox,
                     Webconnect webconnect, Weighting weighting, Logging logging,
                     Deployer deployer) {
        this(dataDir, server, qqconnect, inbox, webconnect, weighting, logging, deployer,
                Watchdog.defaults(), Xvfb.defaults(), Security.defaults());
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
    /**
     * One content rule: when the message text matches {@code pattern} the score moves by
     * {@code delta}. All matching rules apply and stack (D-041).
     *
     * @param pattern    literal text to look for
     * @param match      {@code prefix} (text starts with pattern) or {@code contains} (default)
     * @param delta      score delta; negative lowers, positive raises
     * @param ignoreCase case-insensitive compare, default true
     */
    public record ContentRule(String pattern, String match, int delta, Boolean ignoreCase) {

        public ContentRule {
            match = match == null || match.isBlank() ? "contains" : match.trim().toLowerCase(java.util.Locale.ROOT);
            if (!match.equals("prefix") && !match.equals("contains")) {
                throw new IllegalArgumentException("weighting.rules[].match 只能是 prefix 或 contains");
            }
            ignoreCase = ignoreCase == null ? Boolean.TRUE : ignoreCase;
        }

        /** Whether this rule matches {@code text}. */
        public boolean matches(String text) {
            if (text == null || text.isEmpty() || pattern == null || pattern.isEmpty()) {
                return false;
            }
            String haystack = ignoreCase ? text.toLowerCase(java.util.Locale.ROOT) : text;
            String needle = ignoreCase ? pattern.toLowerCase(java.util.Locale.ROOT) : pattern;
            return match.equals("prefix") ? haystack.startsWith(needle) : haystack.contains(needle);
        }
    }

    public record Weighting(
            int immediateThreshold,
            int dropBelow,
            int maxPriority,
            long normalDelaySeconds,
            long importantDelaySeconds,
            java.util.Map<String, Integer> sourcePriority,
            java.util.Map<String, Integer> attributes,
            /** Ordered content rules; every matching rule adds its delta (they stack). */
            java.util.List<ContentRule> rules) {

        public Weighting {
            rules = rules == null ? java.util.List.of() : java.util.List.copyOf(rules);
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
            for (ContentRule rule : rules) {
                if (rule == null || rule.pattern() == null || rule.pattern().isEmpty()) {
                    throw new IllegalArgumentException("weighting.rules[].pattern 不能为空");
                }
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
                    defaultAttributes(), java.util.List.of());
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
                        Retention retention, Dismiss dismiss) {

        public Inbox {
            inboxId = inboxId == null || inboxId.isBlank() ? "bma3" : inboxId;
            display = display == null ? Display.defaults() : display;
            push = push == null ? Push.defaults() : push;
            retention = retention == null ? Retention.defaults() : retention;
            dismiss = dismiss == null ? Dismiss.defaults() : dismiss;
        }

        public Inbox(String inboxId, int httpPort, Display display,
                     Push push, Retention retention) {
            this(inboxId, httpPort, display, push, retention, Dismiss.defaults());
        }

        public Inbox(String inboxId, int httpPort, Display display) {
            this(inboxId, httpPort, display, Push.defaults(), Retention.defaults());
        }

        public Inbox(String inboxId, int httpPort) {
            this(inboxId, httpPort, Display.defaults());
        }

        public static Inbox defaults() {
            return new Inbox("bma3", 46500, Display.defaults(), Push.defaults(),
                    Retention.defaults(), Dismiss.defaults());
        }

        /** Push filter: items below {@code minPriority} stay visible but never wake the agent. */
        public record Push(int minPriority, int maxFirstPushDelaySeconds) {

            public Push {
                if (minPriority < -1000 || minPriority > 1000) {
                    throw new IllegalArgumentException("inbox.push.minPriority 必须在 -1000..1000");
                }
                if (maxFirstPushDelaySeconds < 0 || maxFirstPushDelaySeconds > 3600) {
                    throw new IllegalArgumentException(
                            "inbox.push.maxFirstPushDelaySeconds 必须在 0..3600");
                }
            }

            /** Compatibility constructor: default hard cap. */
            public Push(int minPriority) {
                this(minPriority, 60);
            }

            public static Push defaults() {
                return new Push(-1000, 60);
            }
        }

        /**
         * Automatic backlog cleanup (D-042). Disabled by default; the watchdog maintenance task
         * calls {@code InboxService.autoDismiss()} when enabled.
         */
        public record Dismiss(boolean autoEnabled, int autoBelowPriority,
                              int autoAfterSeconds, int autoMaxPerRound) {

            public Dismiss {
                if (autoBelowPriority < -1000 || autoBelowPriority > 1000) {
                    throw new IllegalArgumentException(
                            "inbox.dismiss.autoBelowPriority 必须在 -1000..1000");
                }
                if (autoAfterSeconds < 1 || autoAfterSeconds > 86_400 * 30) {
                    throw new IllegalArgumentException(
                            "inbox.dismiss.autoAfterSeconds 必须在 1..2592000");
                }
                if (autoMaxPerRound < 1 || autoMaxPerRound > 500) {
                    throw new IllegalArgumentException(
                            "inbox.dismiss.autoMaxPerRound 必须在 1..500");
                }
            }

            public static Dismiss defaults() {
                return new Dismiss(false, 0, 1800, 200);
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
                              boolean includeOmittedCount, int maxCharsPerItem) {

            public Display {
                if (maxMessagesPerItem < 1 || maxMessagesPerItem > 50) {
                    throw new IllegalArgumentException("inbox.display.maxMessagesPerItem 必须在 1..50");
                }
                if (maxItemsPerPanel < 1 || maxItemsPerPanel > 50) {
                    throw new IllegalArgumentException("inbox.display.maxItemsPerPanel 必须在 1..50");
                }
                if (maxCharsPerItem < 0 || maxCharsPerItem > 20000) {
                    throw new IllegalArgumentException("inbox.display.maxCharsPerItem 必须在 0..20000");
                }
            }

            /** Compatibility constructor: default per-item character cap. */
            public Display(int maxMessagesPerItem, int maxItemsPerPanel,
                           boolean includeOmittedCount) {
                this(maxMessagesPerItem, maxItemsPerPanel, includeOmittedCount, 1200);
            }

            public static Display defaults() {
                return new Display(6, 6, true, 1200);
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

    /**
     * Xvfb 工作区配置（D-055）。{@code workspaceRoot} 留空 ⇒ {@code ${cwd}/.xvfb}，
     * 即「默认生成并提供一个当前目录下的 xvfb 工作区」。
     */
    public record Xvfb(boolean enabled, String workspaceRoot, int width, int height, int depth) {

        public Xvfb {
            workspaceRoot = workspaceRoot == null ? "" : workspaceRoot.trim();
            if (width < 320 || width > 7680) {
                throw new IllegalArgumentException("xvfb.width 必须在 320..7680");
            }
            if (height < 240 || height > 4320) {
                throw new IllegalArgumentException("xvfb.height 必须在 240..4320");
            }
            if (depth != 16 && depth != 24 && depth != 32) {
                throw new IllegalArgumentException("xvfb.depth 只能是 16/24/32");
            }
        }

        public static Xvfb defaults() {
            return new Xvfb(true, "", 1440, 900, 24);
        }

        /** 实际工作区根目录：配置为空时落在当前工作目录下的 {@code .xvfb}。 */
        public java.nio.file.Path root() {
            return workspaceRoot.isEmpty()
                    ? java.nio.file.Path.of(".xvfb").toAbsolutePath().normalize()
                    : java.nio.file.Path.of(workspaceRoot).toAbsolutePath().normalize();
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
