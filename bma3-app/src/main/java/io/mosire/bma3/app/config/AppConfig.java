package io.mosire.bma3.app.config;

import java.nio.file.Path;
import java.util.List;

/** Typed application configuration. Secrets are referenced by env name, not stored here. */
public record AppConfig(
        Path dataDir,
        Server server,
        QqConnect qqconnect,
        QqOfficial qqOfficial,
        Inbox inbox,
        Webconnect webconnect,
        Weighting weighting,
        Logging logging,
        Deployer deployer,
        Watchdog watchdog,
        Xvfb xvfb,
        Security security,
        Debug debug,
        Schedule schedule) {

    public AppConfig {
        dataDir = dataDir == null ? Path.of("data") : dataDir;
        server = server == null ? Server.defaults() : server;
        qqconnect = qqconnect == null ? new QqConnect(List.of()) : qqconnect;
        qqOfficial = qqOfficial == null ? QqOfficial.defaults() : qqOfficial;
        inbox = inbox == null ? Inbox.defaults() : inbox;
        webconnect = webconnect == null ? Webconnect.defaults() : webconnect;
        weighting = weighting == null ? Weighting.defaults() : weighting;
        logging = logging == null ? Logging.defaults() : logging;
        deployer = deployer == null ? Deployer.defaults() : deployer;
        watchdog = watchdog == null ? Watchdog.defaults() : watchdog;
        xvfb = xvfb == null ? Xvfb.defaults() : xvfb;
        security = security == null ? Security.defaults() : security;
        debug = debug == null ? Debug.defaults() : debug;
        schedule = schedule == null ? Schedule.defaults() : schedule;
    }

    public static AppConfig defaults() {
        return new AppConfig(Path.of("data"), Server.defaults(),
                new QqConnect(List.of()), QqOfficial.defaults(), Inbox.defaults(),
                Webconnect.defaults(), Weighting.defaults(), Logging.defaults(),
                Deployer.defaults(), Watchdog.defaults(), Xvfb.defaults(),
                Security.defaults(), Debug.defaults(), Schedule.defaults());
    }

    /**
     * Watchdog knobs. Plain values so the watchdog module never depends on this config class;
     * the app maps this record to {@code WatchdogOptions}.
     */
    public record Watchdog(boolean enabled, int intervalSeconds, int probeTimeoutMs,
                           int failureThreshold, int recoveryCooldownSeconds,
                           int alertCooldownSeconds, int pluginHeartbeatTimeoutSeconds,
                           long diskMinFreeMb, List<String> readyzCritical,
                           boolean pluginHeartbeatEnabled, boolean pushBacklogEnabled) {

        public Watchdog(boolean enabled, int intervalSeconds, int probeTimeoutMs,
                        int failureThreshold, int recoveryCooldownSeconds,
                        int alertCooldownSeconds, int pluginHeartbeatTimeoutSeconds,
                        long diskMinFreeMb, List<String> readyzCritical) {
            this(enabled, intervalSeconds, probeTimeoutMs, failureThreshold,
                    recoveryCooldownSeconds, alertCooldownSeconds, pluginHeartbeatTimeoutSeconds,
                    diskMinFreeMb, readyzCritical, true, true);
        }

        public Watchdog(boolean enabled, int intervalSeconds, int probeTimeoutMs,
                        int failureThreshold, int recoveryCooldownSeconds,
                        int alertCooldownSeconds, int pluginHeartbeatTimeoutSeconds,
                        long diskMinFreeMb, List<String> readyzCritical,
                        boolean pluginHeartbeatEnabled) {
            this(enabled, intervalSeconds, probeTimeoutMs, failureThreshold,
                    recoveryCooldownSeconds, alertCooldownSeconds, pluginHeartbeatTimeoutSeconds,
                    diskMinFreeMb, readyzCritical, pluginHeartbeatEnabled, true);
        }

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
     * QQ 官方机器人配置（D-074..D-077）。本阶段只保存结构与凭据引用，不读取凭据文件，
     * 也不发起任何网络请求。AppSecret 永远不能内联在配置中，只能通过环境变量或受保护文件引用。
     */
    public record QqOfficial(List<Account> accounts, String transport, Reconnect reconnect,
                             Http http, Matching matching) {

        public QqOfficial {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            transport = transport == null || transport.isBlank()
                    ? "websocket" : transport.trim().toLowerCase(java.util.Locale.ROOT);
            if (!"websocket".equals(transport)) {
                throw new IllegalArgumentException("qqofficial.transport 只支持 websocket");
            }
            reconnect = reconnect == null ? Reconnect.defaults() : reconnect;
            http = http == null ? Http.defaults() : http;
            matching = matching == null ? Matching.defaults() : matching;
        }

        public static QqOfficial defaults() {
            return new QqOfficial(List.of(), "websocket", Reconnect.defaults(),
                    Http.defaults(), Matching.defaults());
        }

        /**
         * 一个官方机器人账号。{@code appSecretEnv} 与 {@code appSecretFile} 只能二选一；
         * {@code shard} 首版只允许 {@code [0,1]}。
         */
        public record Account(String accountId, String appId, String appSecretEnv,
                              String appSecretFile, boolean enabled, List<Integer> shard) {

            public Account {
                appId = appId == null ? "" : appId.trim();
                appSecretEnv = appSecretEnv == null ? "" : appSecretEnv.trim();
                appSecretFile = appSecretFile == null ? "" : appSecretFile.trim();
                shard = shard == null ? List.of(0, 1) : List.copyOf(shard);
                if (accountId == null || accountId.isBlank()) {
                    throw new IllegalArgumentException("qqofficial.accounts.accountId 不能为空");
                }
                if (!safeSingleSegment(accountId)) {
                    throw new IllegalArgumentException(
                            "qqofficial.accounts.accountId 必须是安全单段"
                                    + "（不得含 /、空白或控制字符）");
                }
                if (enabled && appId.isEmpty()) {
                    throw new IllegalArgumentException(
                            "qqofficial.accounts.appId 在 enabled=true 时不能为空");
                }
                boolean envRef = !appSecretEnv.isEmpty();
                boolean fileRef = !appSecretFile.isEmpty();
                if (envRef && fileRef) {
                    throw new IllegalArgumentException(
                            "qqofficial.accounts.appSecretEnv/appSecretFile 只能二选一");
                }
                if (enabled && !envRef && !fileRef) {
                    throw new IllegalArgumentException(
                            "qqofficial.accounts 在 enabled=true 时必须设置 appSecretEnv 或 appSecretFile");
                }
                if (envRef && !appSecretEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException(
                            "qqofficial.accounts.appSecretEnv 必须是合法环境变量名");
                }
                if (shard.size() != 2 || !Integer.valueOf(0).equals(shard.get(0))
                        || !Integer.valueOf(1).equals(shard.get(1))) {
                    throw new IllegalArgumentException(
                            "qqofficial.accounts.shard 首版只允许 [0, 1]");
                }
            }

            public Account(String accountId, String appId, String appSecretEnv,
                           String appSecretFile, boolean enabled) {
                this(accountId, appId, appSecretEnv, appSecretFile, enabled, List.of(0, 1));
            }

            /** Phase 2 credential resolver convenience aliases. */
            public String secretEnv() {
                return appSecretEnv;
            }

            /** Phase 2 credential resolver convenience aliases. */
            public String secretFile() {
                return appSecretFile;
            }

            public boolean hasSecretReference() {
                return !appSecretEnv.isEmpty() || !appSecretFile.isEmpty();
            }

            /**
             * Account ids become part of canonical raw sources and audit/log fields. Keep them to
             * one visible token so they cannot alter source routing or forge log lines.
             */
            private static boolean safeSingleSegment(String value) {
                if (value.length() > 128 || !value.equals(value.strip())) {
                    return false;
                }
                return value.codePoints().noneMatch(codePoint ->
                        codePoint == '/'
                                || codePoint == '|'
                                || Character.isWhitespace(codePoint)
                                || Character.isISOControl(codePoint)
                                || Character.getType(codePoint) == Character.FORMAT
                                || Character.getType(codePoint) == Character.LINE_SEPARATOR
                                || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR);
            }
        }

        public record Reconnect(int initialMs, int maxMs, boolean jitter) {

            public Reconnect {
                if (initialMs < 100 || initialMs > 60_000) {
                    throw new IllegalArgumentException(
                            "qqofficial.reconnect.initialMs 必须在 100..60000");
                }
                if (maxMs < initialMs || maxMs > 600_000) {
                    throw new IllegalArgumentException(
                            "qqofficial.reconnect.maxMs 必须在 initialMs..600000");
                }
            }

            public static Reconnect defaults() {
                return new Reconnect(1000, 60_000, true);
            }
        }

        public record Http(int connectTimeoutSeconds, int requestTimeoutSeconds) {

            public Http {
                if (connectTimeoutSeconds < 1 || connectTimeoutSeconds > 300) {
                    throw new IllegalArgumentException(
                            "qqofficial.http.connectTimeoutSeconds 必须在 1..300");
                }
                if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 600) {
                    throw new IllegalArgumentException(
                            "qqofficial.http.requestTimeoutSeconds 必须在 1..600");
                }
            }

            public static Http defaults() {
                return new Http(10, 20);
            }
        }

        public record Matching(String mode, int strictTimeToleranceSeconds) {

            public Matching {
                mode = mode == null || mode.isBlank()
                        ? "strict" : mode.trim().toLowerCase(java.util.Locale.ROOT);
                if (!"strict".equals(mode) && !"loose".equals(mode) && !"off".equals(mode)) {
                    throw new IllegalArgumentException(
                            "qqofficial.matching.mode 只能是 strict/loose/off");
                }
                if (strictTimeToleranceSeconds < 0 || strictTimeToleranceSeconds > 300) {
                    throw new IllegalArgumentException(
                            "qqofficial.matching.strictTimeToleranceSeconds 必须在 0..300");
                }
            }

            public static Matching defaults() {
                return new Matching("strict", 5);
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
        return new AppConfig(dataDir, server, qqconnect, qqOfficial, inbox, webconnect,
                weighting, logging, deployer, watchdog, xvfb, replacement, debug, schedule);
    }

    /**
     * Startup security mode plus where the token bootstrap file lives.
     *
     * <p>{@code auth=token} (default) requires {@code MCP_TOKEN}/{@code INBOX_TOKEN} in the env file;
     * {@code auth=none} skips Bearer checks entirely and is only reachable by explicit request
     * (D-064). Loopback binding stays mandatory either way.</p>
     */
    /**
     * Scheduler knobs (spec 2026-11-15 §4). The scheduler never executes work itself; a due
     * schedule only produces one Inbox notification.
     */
    public record Schedule(boolean enabled, int tickSeconds, int maxSchedules,
                           String defaultMissedPolicy) {

        public Schedule {
            if (tickSeconds < 1 || tickSeconds > 60) {
                throw new IllegalArgumentException("schedule.tickSeconds 必须在 1..60");
            }
            if (maxSchedules < 1 || maxSchedules > 2000) {
                throw new IllegalArgumentException("schedule.maxSchedules 必须在 1..2000");
            }
            String policy = defaultMissedPolicy == null
                    ? "skip" : defaultMissedPolicy.trim().toLowerCase(java.util.Locale.ROOT);
            if (!policy.equals("skip") && !policy.equals("once")) {
                throw new IllegalArgumentException("schedule.defaultMissedPolicy 只能是 skip 或 once");
            }
            defaultMissedPolicy = policy;
        }

        public static Schedule defaults() {
            return new Schedule(true, 1, 200, "skip");
        }
    }

    /** 13-arg compatibility constructor for profiles created before QQ official support. */
    public AppConfig(Path dataDir, Server server, QqConnect qqconnect, Inbox inbox,
                     Webconnect webconnect, Weighting weighting, Logging logging,
                     Deployer deployer, Watchdog watchdog, Xvfb xvfb, Security security,
                     Debug debug, Schedule schedule) {
        this(dataDir, server, qqconnect, QqOfficial.defaults(), inbox, webconnect, weighting,
                logging, deployer, watchdog, xvfb, security, debug, schedule);
    }

    /** 12-arg compatibility constructor for profiles created before the scheduler. */
    public AppConfig(Path dataDir, Server server, QqConnect qqconnect, Inbox inbox,
                     Webconnect webconnect, Weighting weighting, Logging logging,
                     Deployer deployer, Watchdog watchdog, Xvfb xvfb, Security security,
                     Debug debug) {
        this(dataDir, server, qqconnect, inbox, webconnect, weighting, logging, deployer,
                watchdog, xvfb, security, debug, Schedule.defaults());
    }

    /**
     * Remote ask/diagnose surface (spec 2026-11-15 §2).
     *
     * <p>Mounted on the health listener so an operator only needs one SSH tunnel; the bearer is a
     * separate {@code DEBUG_TOKEN} so rotating it never disturbs MCP or the Inbox SSE.</p>
     */
    public record Debug(boolean enabled, int maxPending, int askTimeoutSeconds,
                        int questionMaxBytes, int answerMaxBytes, int retention, int priority) {

        public Debug {
            if (maxPending < 0 || maxPending > 1024) {
                throw new IllegalArgumentException("debug.maxPending 必须在 0..1024（0 = 不限）");
            }
            if (askTimeoutSeconds < 5 || askTimeoutSeconds > 600) {
                throw new IllegalArgumentException("debug.askTimeoutSeconds 必须在 5..600");
            }
            if (questionMaxBytes < 256 || questionMaxBytes > 65_536) {
                throw new IllegalArgumentException("debug.questionMaxBytes 必须在 256..65536");
            }
            if (answerMaxBytes < 256 || answerMaxBytes > 262_144) {
                throw new IllegalArgumentException("debug.answerMaxBytes 必须在 256..262144");
            }
            if (retention < 10 || retention > 5000) {
                throw new IllegalArgumentException("debug.retention 必须在 10..5000");
            }
            if (priority < -1000 || priority > 1000) {
                throw new IllegalArgumentException("debug.priority 必须在 -1000..1000");
            }
        }

        public static Debug defaults() {
            return new Debug(true, 32, 120, 4096, 16384, 200, 350);
        }
    }

    /** 11-arg compatibility constructor for profiles created before the debug surface. */
    public AppConfig(Path dataDir, Server server, QqConnect qqconnect, Inbox inbox,
                     Webconnect webconnect, Weighting weighting, Logging logging,
                     Deployer deployer, Watchdog watchdog, Xvfb xvfb, Security security) {
        this(dataDir, server, qqconnect, inbox, webconnect, weighting, logging, deployer,
                watchdog, xvfb, security, Debug.defaults());
    }

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
     * One content rule: the ordered rules stack, each adds {@code delta}.
     *
     * <p>{@code match} is {@code prefix}, {@code contains} or {@code regex}; {@code source} scopes
     * the rule to one source (same {@code /}-boundary semantics as {@code sourcePriority}); an empty
     * {@code source} means "any source". Regex text is operator-configured, so a bad pattern is
     * rejected at load time instead of silently never matching.</p>
     */
    public record ContentRule(String pattern, String match, int delta, Boolean ignoreCase,
                              String source) {

        /** Longest accepted pattern; keeps a typo from turning into a pathological regex. */
        public static final int MAX_PATTERN_CHARS = 200;

        public ContentRule {
            match = match == null || match.isBlank()
                    ? "contains" : match.trim().toLowerCase(java.util.Locale.ROOT);
            if (!match.equals("prefix") && !match.equals("contains") && !match.equals("regex")) {
                throw new IllegalArgumentException(
                        "weighting.rules[].match 只能是 prefix、contains 或 regex");
            }
            ignoreCase = ignoreCase == null ? Boolean.TRUE : ignoreCase;
            source = source == null ? "" : source.trim();
            if (source.startsWith("/")) {
                throw new IllegalArgumentException("weighting.rules[].source 不能以 / 开头");
            }
            if (pattern == null || pattern.isEmpty()) {
                throw new IllegalArgumentException("weighting.rules[].pattern 不能为空");
            }
            if (pattern.length() > MAX_PATTERN_CHARS) {
                throw new IllegalArgumentException(
                        "weighting.rules[].pattern 不能超过 " + MAX_PATTERN_CHARS + " 字符");
            }
            if ("regex".equals(match)) {
                try {
                    java.util.regex.Pattern.compile(pattern, regexFlags(ignoreCase));
                } catch (java.util.regex.PatternSyntaxException failure) {
                    throw new IllegalArgumentException(
                            "weighting.rules[].pattern 不是合法正则: " + pattern);
                }
            }
        }

        /** Backwards-compatible 4-arg form: no source scope. */
        public ContentRule(String pattern, String match, int delta, Boolean ignoreCase) {
            this(pattern, match, delta, ignoreCase, "");
        }

        /** Whether this rule matches {@code text} on any source. */
        public boolean matches(String text) {
            return matches(text, "");
        }

        /** Whether this rule matches {@code text} observed on {@code sourceKey}. */
        public boolean matches(String text, String sourceKey) {
            if (!source.isBlank() && !sourceApplies(source, sourceKey)) {
                return false;
            }
            if (text == null || text.isEmpty()) {
                return false;
            }
            if ("regex".equals(match)) {
                return java.util.regex.Pattern.compile(pattern, regexFlags(ignoreCase))
                        .matcher(text).find();
            }
            String haystack = ignoreCase ? text.toLowerCase(java.util.Locale.ROOT) : text;
            String needle = ignoreCase ? pattern.toLowerCase(java.util.Locale.ROOT) : pattern;
            return match.equals("prefix") ? haystack.startsWith(needle) : haystack.contains(needle);
        }

        private static int regexFlags(boolean ignoreCase) {
            return ignoreCase
                    ? java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE
                    : 0;
        }

        private static boolean sourceApplies(String ruleSource, String sourceKey) {
            if (sourceKey == null || sourceKey.isBlank()) {
                return false;
            }
            return sourceKey.equals(ruleSource)
                    || (sourceKey.startsWith(ruleSource)
                        && sourceKey.charAt(ruleSource.length()) == '/');
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
            java.util.List<ContentRule> rules,
            /** Text prefixes that raise {@code qq_command} (default {@code /} and {@code !}). */
            java.util.List<String> commandPrefixes) {

        public Weighting {
            rules = rules == null ? java.util.List.of() : java.util.List.copyOf(rules);
            commandPrefixes = commandPrefixes == null || commandPrefixes.isEmpty()
                    ? java.util.List.of("/", "!") : java.util.List.copyOf(commandPrefixes);
            for (String prefix : commandPrefixes) {
                if (prefix == null || prefix.isBlank() || !prefix.equals(prefix.strip())
                        || prefix.length() > 8) {
                    throw new IllegalArgumentException(
                            "weighting.commandPrefixes 必须是不含空白、长度 1..8 的字符串");
                }
            }
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

        /** Backwards-compatible 8-arg form (before configurable command prefixes). */
        public Weighting(int immediateThreshold, int dropBelow, int maxPriority,
                         long normalDelaySeconds, long importantDelaySeconds,
                         java.util.Map<String, Integer> sourcePriority,
                         java.util.Map<String, Integer> attributes,
                         java.util.List<ContentRule> rules) {
            this(immediateThreshold, dropBelow, maxPriority, normalDelaySeconds,
                    importantDelaySeconds, sourcePriority, attributes, rules,
                    java.util.List.of("/", "!"));
        }

        public static Weighting defaults() {
            return new Weighting(300, -300, 1000, 30, 1, defaultSourcePriority(),
                    defaultAttributes(), java.util.List.of(), java.util.List.of("/", "!"));
        }

        public static java.util.Map<String, Integer> defaultSourcePriority() {
            java.util.Map<String, Integer> rules = new java.util.LinkedHashMap<>();
            rules.put("*", 0);
            rules.put("qq_private", 300);
            rules.put("qq_group", 200);
            rules.put("qq_official_private", 300);
            rules.put("qq_official_group", 200);
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
                        Retention retention, Dismiss dismiss, Wait waitPolicy) {

        public Inbox {
            inboxId = inboxId == null || inboxId.isBlank() ? "bma3" : inboxId;
            display = display == null ? Display.defaults() : display;
            push = push == null ? Push.defaults() : push;
            retention = retention == null ? Retention.defaults() : retention;
            dismiss = dismiss == null ? Dismiss.defaults() : dismiss;
            waitPolicy = waitPolicy == null ? Wait.defaults() : waitPolicy;
        }

        public Inbox(String inboxId, int httpPort, Display display,
                     Push push, Retention retention, Dismiss dismiss) {
            this(inboxId, httpPort, display, push, retention, dismiss, Wait.defaults());
        }

        public Inbox(String inboxId, int httpPort, Display display,
                     Push push, Retention retention) {
            this(inboxId, httpPort, display, push, retention, Dismiss.defaults(),
                    Wait.defaults());
        }

        public Inbox(String inboxId, int httpPort, Display display) {
            this(inboxId, httpPort, display, Push.defaults(), Retention.defaults());
        }

        public Inbox(String inboxId, int httpPort) {
            this(inboxId, httpPort, Display.defaults());
        }

        public static Inbox defaults() {
            return new Inbox("bma3", 46500, Display.defaults(), Push.defaults(),
                    Retention.defaults(), Dismiss.defaults(), Wait.defaults());
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

        /**
         * Source-prefix quiet policy for the single Inbox push scheduler. The factory
         * default is quiet=0 so existing deployments do not change until an Agent opts in.
         */
        public record Wait(boolean enabled, int defaultQuietSeconds, int defaultMaxWaitSeconds,
                           List<SourceRule> sources) {

            public Wait {
                if (defaultQuietSeconds < 0 || defaultQuietSeconds > 300) {
                    throw new IllegalArgumentException(
                            "inbox.wait.defaultQuietSeconds 必须在 0..300");
                }
                if (defaultMaxWaitSeconds < 1 || defaultMaxWaitSeconds > 600) {
                    throw new IllegalArgumentException(
                            "inbox.wait.defaultMaxWaitSeconds 必须在 1..600");
                }
                if (defaultQuietSeconds > 0 && defaultMaxWaitSeconds < defaultQuietSeconds) {
                    throw new IllegalArgumentException(
                            "inbox.wait.defaultMaxWaitSeconds 不能小于 defaultQuietSeconds");
                }
                sources = sources == null ? List.of() : List.copyOf(sources);
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (SourceRule rule : sources) {
                    if (!seen.add(rule.source())) {
                        throw new IllegalArgumentException(
                                "inbox.wait.sources 重复来源: " + rule.source());
                    }
                }
            }

            public record SourceRule(String source, int quietSeconds, int maxWaitSeconds) {

                public SourceRule {
                    source = source == null ? "" : source.trim();
                    if (source.isEmpty()) {
                        throw new IllegalArgumentException(
                                "inbox.wait.sources[].source 不能为空");
                    }
                    if (source.startsWith("/")) {
                        throw new IllegalArgumentException(
                                "inbox.wait.sources[].source 不能以 / 开头");
                    }
                    if (quietSeconds < 0 || quietSeconds > 300) {
                        throw new IllegalArgumentException(
                                "inbox.wait.sources[].quietSeconds 必须在 0..300");
                    }
                    if (maxWaitSeconds < 1 || maxWaitSeconds > 600) {
                        throw new IllegalArgumentException(
                                "inbox.wait.sources[].maxWaitSeconds 必须在 1..600");
                    }
                    if (quietSeconds > 0 && maxWaitSeconds < quietSeconds) {
                        throw new IllegalArgumentException(
                                "inbox.wait.sources[].maxWaitSeconds 不能小于 quietSeconds");
                    }
                }
            }

            public static Wait defaults() {
                return new Wait(true, 0, 20, List.of());
            }
        }

        public record Display(int maxMessagesPerItem, int maxItemsPerPanel,
                              boolean includeOmittedCount, int maxCharsPerItem,
                              String mode, int summaryMaxCharsPerItem, int summaryAboveItems) {

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
                mode = mode == null ? "auto" : mode.trim().toLowerCase(java.util.Locale.ROOT);
                if (!mode.equals("full") && !mode.equals("summary") && !mode.equals("auto")) {
                    throw new IllegalArgumentException(
                            "inbox.display.mode 只能是 full/summary/auto");
                }
                if (summaryMaxCharsPerItem < 0 || summaryMaxCharsPerItem > 20000) {
                    throw new IllegalArgumentException(
                            "inbox.display.summaryMaxCharsPerItem 必须在 0..20000");
                }
                if (summaryAboveItems < 1 || summaryAboveItems > 50) {
                    throw new IllegalArgumentException(
                            "inbox.display.summaryAboveItems 必须在 1..50");
                }
            }

            /** Compatibility constructor: default per-item character cap. */
            public Display(int maxMessagesPerItem, int maxItemsPerPanel,
                           boolean includeOmittedCount) {
                this(maxMessagesPerItem, maxItemsPerPanel, includeOmittedCount, 1200,
                        "auto", 200, 8);
            }

            /** Compatibility constructor: pre-summary config. */
            public Display(int maxMessagesPerItem, int maxItemsPerPanel,
                           boolean includeOmittedCount, int maxCharsPerItem) {
                this(maxMessagesPerItem, maxItemsPerPanel, includeOmittedCount, maxCharsPerItem,
                        "auto", 200, 8);
            }

            public static Display defaults() {
                return new Display(6, 6, true, 1200, "auto", 200, 8);
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
