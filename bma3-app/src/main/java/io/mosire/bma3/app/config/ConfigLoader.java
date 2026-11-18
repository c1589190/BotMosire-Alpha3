package io.mosire.bma3.app.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.mosire.bma3.core.config.ConfigError;
import io.mosire.bma3.core.config.ConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads {@code config/app.yaml}, applies defaults and validates the result. */
public final class ConfigLoader {

    private static final Pattern ENV_REF = Pattern.compile("\\$\\{ENV:([A-Za-z_][A-Za-z0-9_]*)}");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> LOG_LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
    private static final Set<String> QQOFFICIAL_FIELDS =
            Set.of("accounts", "transport", "reconnect", "http", "matching");
    private static final Set<String> QQOFFICIAL_ACCOUNT_FIELDS =
            Set.of("accountId", "appId", "appSecretEnv", "appSecretFile",
                    "secretEnv", "secretFile", "enabled", "shard");
    private static final Set<String> QQOFFICIAL_RECONNECT_FIELDS =
            Set.of("initialMs", "maxMs", "jitter");
    private static final Set<String> QQOFFICIAL_HTTP_FIELDS =
            Set.of("connectTimeoutSeconds", "requestTimeoutSeconds");
    private static final Set<String> QQOFFICIAL_MATCHING_FIELDS =
            Set.of("mode", "strictTimeToleranceSeconds");
    /** Inline credential-bearing keys are rejected recursively, even inside unknown objects. */
    private static final Set<String> INLINE_SECRET_FIELDS = Set.of(
            "appsecret", "clientsecret", "client_secret", "secret", "password",
            "token", "accesstoken", "access_token", "authorization", "auth_token");

    private ConfigLoader() {
    }

    public static AppConfig load(Path root) {
        return load(root, System.getenv());
    }

    public static AppConfig load(Path root, Map<String, String> env) {
        Path file = configFile(root);
        if (!Files.exists(file)) {
            return AppConfig.defaults();
        }
        try {
            String raw = Files.readString(file);
            rejectEnvInterpolationInQqOfficial(raw);
            String interpolated = interpolate(raw, env);
            JsonNode node = YAML.readTree(interpolated);
            AppConfig config = parse(node == null ? YAML.createObjectNode() : node);
            List<ConfigError> errors = validate(config);
            if (!errors.isEmpty()) {
                throw new ConfigException(errors);
            }
            return config;
        } catch (ConfigException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new ConfigException("config", safeIoMessage(failure));
        } catch (RuntimeException failure) {
            throw new ConfigException("config", safeRuntimeMessage(failure));
        }
    }

    public static Path configFile(Path root) {
        return Files.isDirectory(root) ? root.resolve("config/app.yaml") : root;
    }

    public static List<ConfigError> validate(AppConfig config) {
        List<ConfigError> errors = new ArrayList<>();
        if (config.server().port() < 0 || config.server().port() > 65535) {
            errors.add(new ConfigError("server.port", "必须在 0..65535"));
        }
        if (config.inbox().httpPort() < 0 || config.inbox().httpPort() > 65535) {
            errors.add(new ConfigError("inbox.httpPort", "必须在 0..65535"));
        }
        if (config.server().host() == null || config.server().host().isBlank()) {
            errors.add(new ConfigError("server.host", "不能为空"));
        }
        if (!config.server().mcpPath().startsWith("/")) {
            errors.add(new ConfigError("server.mcpPath", "必须以 / 开头"));
        }
        if (!LOG_LEVELS.contains(config.logging().level().toUpperCase())) {
            errors.add(new ConfigError("logging.level", "未知级别"));
        }
        Set<String> accountIds = new HashSet<>();
        for (AppConfig.Account account : config.qqconnect().accounts()) {
            if (account.accountId() == null || account.accountId().isBlank()) {
                errors.add(new ConfigError("qqconnect.accounts.accountId", "不能为空"));
            } else if (!accountIds.add(account.accountId())) {
                errors.add(new ConfigError("qqconnect.accounts.accountId", "重复: " + account.accountId()));
            }
            if (account.enabled()) {
                String endpoint = account.endpoint();
                if (endpoint == null || !(endpoint.startsWith("ws://") || endpoint.startsWith("wss://"))) {
                    errors.add(new ConfigError("qqconnect.accounts.endpoint", "必须是 ws:// 或 wss://"));
                }
            }
        }
        Set<String> officialAccountIds = new HashSet<>();
        for (AppConfig.QqOfficial.Account account : config.qqOfficial().accounts()) {
            if (account.accountId() == null || account.accountId().isBlank()) {
                errors.add(new ConfigError("qqofficial.accounts.accountId", "不能为空"));
            } else if (!officialAccountIds.add(account.accountId())) {
                // The id is already validated as a safe single segment, but never echo it.
                errors.add(new ConfigError("qqofficial.accounts.accountId", "重复"));
            }
        }
        return List.copyOf(errors);
    }

    private static AppConfig parse(JsonNode node) {
        String dataDir = text(node, "dataDir", "data");
        JsonNode server = node.path("server");
        AppConfig.Server serverConfig = new AppConfig.Server(
                text(server, "host", "127.0.0.1"),
                integer(server, "port", 8765),
                text(server, "mcpPath", "/mcp"),
                text(server, "tokenEnv", "BMA3_MCP_TOKEN"),
                integer(server, "healthPort", 8766));

        List<AppConfig.Account> accounts = new ArrayList<>();
        for (JsonNode account : node.path("qqconnect").path("accounts")) {
            accounts.add(new AppConfig.Account(
                    text(account, "accountId", ""),
                    text(account, "endpoint", ""),
                    text(account, "tokenEnv", ""),
                    account.path("enabled").asBoolean(true),
                    text(account, "selfQq", ""),
                    text(account, "selfName", "")));
        }

        JsonNode inbox = node.path("inbox");
        JsonNode inboxDisplay = inbox.path("display");
        JsonNode inboxPush = inbox.path("push");
        JsonNode inboxRetention = inbox.path("retention");
        JsonNode inboxDismiss = inbox.path("dismiss");
        JsonNode inboxWait = inbox.path("wait");
        JsonNode send = node.path("qqconnect").path("send");
        JsonNode media = node.path("qqconnect").path("media");
        AppConfig.QqOfficial qqOfficial = parseQqOfficial(node.path("qqofficial"));
        JsonNode webconnect = node.path("webconnect");
        List<String> allowedHosts = new ArrayList<>();
        for (JsonNode host : webconnect.path("allowedHosts")) {
            allowedHosts.add(host.asText());
        }
        JsonNode weighting = node.path("weighting");
        JsonNode logging = node.path("logging");
        JsonNode watchdog = node.path("watchdog");
        JsonNode deployer = node.path("deployer");
        JsonNode xvfb = node.path("xvfb");
        JsonNode security = node.path("security");
        JsonNode debug = node.path("debug");
        JsonNode schedule = node.path("schedule");
        JsonNode memory = node.path("memory");
        List<String> readyzCritical = new ArrayList<>();
        for (JsonNode critical : watchdog.path("readyzCritical")) {
            readyzCritical.add(critical.asText());
        }
        return new AppConfig(
                Path.of(dataDir),
                serverConfig,
                new AppConfig.QqConnect(accounts,
                        new AppConfig.QqConnect.Send(
                                integer(send, "maxTextChars", 3000),
                                text(send, "overflow", "reject")),
                        new AppConfig.QqConnect.Media(
                                text(media, "dir", ""),
                                integer(media, "maxInlineBytes", 65536),
                                integer(media, "maxDownloadBytes", 33_554_432),
                                integer(media, "timeoutSeconds", 20))),
                qqOfficial,
                new AppConfig.Inbox(
                        text(inbox, "inboxId", "bma3"),
                        integer(inbox, "httpPort", 46500),
                        new AppConfig.Inbox.Display(
                                integer(inboxDisplay, "maxMessagesPerItem", 6),
                                integer(inboxDisplay, "maxItemsPerPanel", 6),
                                inboxDisplay.path("includeOmittedCount").asBoolean(true),
                                integer(inboxDisplay, "maxCharsPerItem", 1200),
                                text(inboxDisplay, "mode", "auto"),
                                integer(inboxDisplay, "summaryMaxCharsPerItem", 200),
                                integer(inboxDisplay, "summaryAboveItems", 8)),
                        new AppConfig.Inbox.Push(
                                integer(inboxPush, "minPriority", -1000),
                                integer(inboxPush, "maxFirstPushDelaySeconds", 60)),
                        new AppConfig.Inbox.Retention(
                                integer(inboxRetention, "maxAgeDays", 30),
                                integer(inboxRetention, "maxItems", 5000)),
                        new AppConfig.Inbox.Dismiss(
                                inboxDismiss.path("autoEnabled").asBoolean(false),
                                integer(inboxDismiss, "autoBelowPriority", 0),
                                integer(inboxDismiss, "autoAfterSeconds", 1800),
                                integer(inboxDismiss, "autoMaxPerRound", 200)),
                        new AppConfig.Inbox.Wait(
                                inboxWait.path("enabled").asBoolean(true),
                                integer(inboxWait, "defaultQuietSeconds", 0),
                                integer(inboxWait, "defaultMaxWaitSeconds", 20),
                                waitSources(inboxWait.path("sources")))),
                new AppConfig.Webconnect(webconnect.path("enabled").asBoolean(false),
                        text(webconnect, "binary", "/snap/bin/chromium"),
                        webconnect.path("headless").asBoolean(true),
                        allowedHosts.isEmpty() ? AppConfig.Webconnect.defaults().allowedHosts() : allowedHosts),
                new AppConfig.Weighting(
                        integer(weighting, "immediateThreshold", 300),
                        integer(weighting, "dropBelow", -300),
                        integer(weighting, "maxPriority", 1000),
                        integer(weighting, "normalDelaySeconds", 60),
                        integer(weighting, "importantDelaySeconds", 3),
                        integerMapOr(weighting.path("sourcePriority"),
                                AppConfig.Weighting.defaultSourcePriority()),
                        integerMapOr(weighting.path("attributes"),
                                AppConfig.Weighting.defaultAttributes()),
                        contentRules(weighting.path("rules")),
                        commandPrefixes(weighting.path("commandPrefixes"))),
                new AppConfig.Logging(text(logging, "level", "INFO"), text(logging, "dir", "logs")),
                new AppConfig.Deployer(
                        text(deployer, "qq", ""),
                        deployer.path("enabled").asBoolean(false)),
                new AppConfig.Watchdog(
                        watchdog.path("enabled").asBoolean(true),
                        integer(watchdog, "intervalSeconds", 10),
                        integer(watchdog, "probeTimeoutMs", 2000),
                        integer(watchdog, "failureThreshold", 2),
                        integer(watchdog, "recoveryCooldownSeconds", 30),
                        integer(watchdog, "alertCooldownSeconds", 600),
                        integer(watchdog, "pluginHeartbeatTimeoutSeconds", 45),
                        watchdog.path("diskMinFreeMb").asLong(200),
                        readyzCritical.isEmpty()
                                ? AppConfig.Watchdog.defaults().readyzCritical()
                                : readyzCritical,
                        watchdog.path("pluginHeartbeatEnabled").asBoolean(true),
                        watchdog.path("pushBacklogEnabled").asBoolean(true)),
                new AppConfig.Xvfb(
                        xvfb.path("enabled").asBoolean(true),
                        text(xvfb, "workspaceRoot", ""),
                        integer(xvfb, "width", 1440),
                        integer(xvfb, "height", 900),
                        integer(xvfb, "depth", 24)),
                new AppConfig.Security(
                        text(security, "auth", AppConfig.Security.AUTH_TOKEN),
                        text(security, "envFile", "")),
                new AppConfig.Debug(
                        debug.path("enabled").asBoolean(true),
                        integer(debug, "maxPending", 32),
                        integer(debug, "askTimeoutSeconds", 120),
                        integer(debug, "questionMaxBytes", 4096),
                        integer(debug, "answerMaxBytes", 16384),
                        integer(debug, "retention", 200),
                        integer(debug, "priority", 350)),
                new AppConfig.Schedule(
                        schedule.path("enabled").asBoolean(true),
                        integer(schedule, "tickSeconds", 1),
                        integer(schedule, "maxSchedules", 200),
                        text(schedule, "defaultMissedPolicy", "skip")),
                memory(memory));
    }

    /**
     * The official-account section is reference-only. Interpolating it before parsing would copy a
     * real secret into the Jackson tree, where validation errors or future code could expose it.
     * Parse the untouched YAML and reject the section before the generic interpolation pass.
     */
    private static void rejectEnvInterpolationInQqOfficial(String raw) throws IOException {
        JsonNode root = YAML.readTree(raw);
        if (root == null || !root.isObject()) {
            return;
        }
        JsonNode qqOfficial = root.get("qqofficial");
        if (containsEnvInterpolation(qqOfficial)) {
            throw new ConfigException("qqofficial",
                    "禁止使用 ${ENV:...} 插值；请直接填写环境变量名或凭据文件路径");
        }
    }

    private static boolean containsEnvInterpolation(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return ENV_REF.matcher(node.asText()).find();
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (ENV_REF.matcher(entry.getKey()).find()
                        || containsEnvInterpolation(entry.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsEnvInterpolation(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** YAML parser messages can contain the offending line; return only location metadata. */
    private static String safeIoMessage(IOException failure) {
        if (failure instanceof JsonProcessingException parseFailure
                && parseFailure.getLocation() != null) {
            return "YAML 解析失败（line " + parseFailure.getLocation().getLineNr()
                    + ", column " + parseFailure.getLocation().getColumnNr() + "）";
        }
        return "读取失败";
    }

    /**
     * Runtime validation messages are authored locally and never include the offending value.
     * Keep the useful field path but never fall back to an exception class name or null text.
     */
    private static String safeRuntimeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "配置校验失败" : message;
    }

    /** Keeps arbitrary YAML keys from becoming unbounded/control-character error output. */
    private static String safeFieldPath(String path, String field) {
        if (field != null && field.matches("[A-Za-z0-9_.-]{1,64}")) {
            return path + "." + field;
        }
        return path + ".<unknown>";
    }

    private static AppConfig.QqOfficial parseQqOfficial(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return AppConfig.QqOfficial.defaults();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("qqofficial 必须是对象");
        }
        rejectInlineSecrets(node, "qqofficial");
        rejectUnknownFields(node, "qqofficial", QQOFFICIAL_FIELDS);

        List<AppConfig.QqOfficial.Account> accounts = new ArrayList<>();
        JsonNode accountRows = node.path("accounts");
        if (!accountRows.isMissingNode() && !accountRows.isNull()) {
            if (!accountRows.isArray()) {
                throw new IllegalArgumentException("qqofficial.accounts 必须是数组");
            }
            int index = 0;
            for (JsonNode row : accountRows) {
                String path = "qqofficial.accounts[" + index + "]";
                if (!row.isObject()) {
                    throw new IllegalArgumentException(path + " 必须是对象");
                }
                rejectInlineSecrets(row, path);
                rejectUnknownFields(row, path, QQOFFICIAL_ACCOUNT_FIELDS);
                String env = aliasText(row, "appSecretEnv", "secretEnv", path);
                String file = aliasText(row, "appSecretFile", "secretFile", path);
                accounts.add(new AppConfig.QqOfficial.Account(
                        text(row, "accountId", ""),
                        text(row, "appId", ""),
                        env,
                        file,
                        strictBoolean(row, "enabled", false, path),
                        shard(row, path)));
                index++;
            }
        }

        JsonNode reconnect = objectOrMissing(node, "reconnect", "qqofficial.reconnect");
        rejectInlineSecrets(reconnect, "qqofficial.reconnect");
        rejectUnknownFields(reconnect, "qqofficial.reconnect",
                QQOFFICIAL_RECONNECT_FIELDS);
        JsonNode http = objectOrMissing(node, "http", "qqofficial.http");
        rejectInlineSecrets(http, "qqofficial.http");
        rejectUnknownFields(http, "qqofficial.http", QQOFFICIAL_HTTP_FIELDS);
        JsonNode matching = objectOrMissing(node, "matching", "qqofficial.matching");
        rejectInlineSecrets(matching, "qqofficial.matching");
        rejectUnknownFields(matching, "qqofficial.matching",
                QQOFFICIAL_MATCHING_FIELDS);

        return new AppConfig.QqOfficial(
                accounts,
                text(node, "transport", "websocket"),
                new AppConfig.QqOfficial.Reconnect(
                        strictInteger(reconnect, "initialMs", 1000, "qqofficial.reconnect"),
                        strictInteger(reconnect, "maxMs", 60_000, "qqofficial.reconnect"),
                        strictBoolean(reconnect, "jitter", true, "qqofficial.reconnect")),
                new AppConfig.QqOfficial.Http(
                        strictInteger(http, "connectTimeoutSeconds", 10, "qqofficial.http"),
                        strictInteger(http, "requestTimeoutSeconds", 20, "qqofficial.http")),
                new AppConfig.QqOfficial.Matching(
                        text(matching, "mode", "strict"),
                        strictInteger(matching, "strictTimeToleranceSeconds", 5,
                                "qqofficial.matching")));
    }

    /**
     * Reads one credential reference field and its documented short alias. Supplying both is
     * ambiguous and therefore rejected; blank values count as absent so an enabled account still
     * has to provide exactly one usable reference.
     */
    private static String aliasText(JsonNode row, String canonical, String alias, String path) {
        JsonNode canonicalNode = row.path(canonical);
        JsonNode aliasNode = row.path(alias);
        boolean hasCanonical = !canonicalNode.isMissingNode() && !canonicalNode.isNull();
        boolean hasAlias = !aliasNode.isMissingNode() && !aliasNode.isNull();
        if (hasCanonical && hasAlias) {
            throw new IllegalArgumentException(
                    path + "." + canonical + "/" + alias + " 只能设置一个");
        }
        return hasCanonical ? canonicalNode.asText("") : aliasNode.asText("");
    }

    private static List<Integer> shard(JsonNode row, String path) {
        JsonNode node = row.path("shard");
        if (node.isMissingNode() || node.isNull()) {
            return List.of(0, 1);
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(path + ".shard 必须是 [0, 1]");
        }
        List<Integer> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw new IllegalArgumentException(path + ".shard 必须是整数数组 [0, 1]");
            }
            values.add(value.intValue());
        }
        if (values.size() != 2 || values.get(0) != 0 || values.get(1) != 1) {
            throw new IllegalArgumentException(path + ".shard 首版只允许 [0, 1]");
        }
        return List.copyOf(values);
    }

    private static boolean strictBoolean(JsonNode node, String field, boolean fallback,
                                         String path) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(path + "." + field + " 必须是布尔值");
        }
        return value.booleanValue();
    }

    private static int strictInteger(JsonNode node, String field, int fallback, String path) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(path + "." + field + " 必须是整数");
        }
        return value.intValue();
    }

    private static JsonNode objectOrMissing(JsonNode parent, String field, String path) {
        JsonNode node = parent.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return new ObjectMapper().createObjectNode();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(path + " 必须是对象");
        }
        return node;
    }

    /** Rejects inline credentials before schema validation can accidentally ignore an unknown key. */
    private static void rejectInlineSecrets(JsonNode node, String path) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String field = entry.getKey();
                String fieldPath = safeFieldPath(path, field);
                String normalized = field.toLowerCase(java.util.Locale.ROOT)
                        .replace("-", "").replace("_", "");
                if (INLINE_SECRET_FIELDS.contains(normalized)
                        || INLINE_SECRET_FIELDS.contains(field.toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException(
                            fieldPath + " 禁止 inline secret；请使用 appSecretEnv 或 appSecretFile");
                }
                rejectInlineSecrets(entry.getValue(), fieldPath);
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                rejectInlineSecrets(node.get(index), path + "[" + index + "]");
            }
        }
    }

    private static void rejectUnknownFields(JsonNode node, String path, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            return;
        }
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(
                        safeFieldPath(path, field) + " 不是支持的字段");
            }
        });
    }

    private static String interpolate(String raw, Map<String, String> env) {
        Matcher matcher = ENV_REF.matcher(raw);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = env.get(name);
            if (value == null) {
                throw new ConfigException("env." + name, "环境变量未设置");
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    /** Parses {@code inbox.wait.sources[]}; an absent/empty array means no overrides. */
    private static java.util.List<AppConfig.Inbox.Wait.SourceRule> waitSources(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<AppConfig.Inbox.Wait.SourceRule> rules = new java.util.ArrayList<>();
        for (JsonNode row : node) {
            rules.add(new AppConfig.Inbox.Wait.SourceRule(
                    text(row, "source", ""),
                    integer(row, "quietSeconds", 0),
                    integer(row, "maxWaitSeconds", 20)));
        }
        return java.util.List.copyOf(rules);
    }

    /** Missing map falls back to the built-in defaults; an empty map stays empty on purpose. */
    /** Parses {@code weighting.rules[]}; invalid entries fail the whole config (fail-closed). */
    private static java.util.List<String> commandPrefixes(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return AppConfig.Weighting.defaults().commandPrefixes();
        }
        java.util.List<String> prefixes = new java.util.ArrayList<>();
        for (JsonNode row : node) {
            prefixes.add(row.asText(""));
        }
        return java.util.List.copyOf(prefixes);
    }

    private static java.util.List<AppConfig.ContentRule> contentRules(JsonNode node) {
        if (node == null || !node.isArray()) {
            return java.util.List.of();
        }
        java.util.List<AppConfig.ContentRule> rules = new java.util.ArrayList<>();
        for (JsonNode row : node) {
            String pattern = text(row, "pattern", "");
            if (pattern.isBlank()) {
                throw new IllegalArgumentException("weighting.rules[].pattern 不能为空");
            }
            rules.add(new AppConfig.ContentRule(pattern, text(row, "match", "contains"),
                    integer(row, "delta", 0),
                    row.hasNonNull("ignoreCase") ? row.path("ignoreCase").asBoolean(true) : null,
                    text(row, "source", "")));
        }
        return java.util.List.copyOf(rules);
    }

    private static java.util.Map<String, Integer> integerMapOr(JsonNode node,
                                                               java.util.Map<String, Integer> fallback) {
        java.util.Map<String, Integer> parsed = integerMap(node);
        return parsed == null ? fallback : parsed;
    }

    private static java.util.Map<String, Integer> integerMap(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        java.util.Map<String, Integer> values = new java.util.LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asInt()));
        return values;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText(fallback);
    }

    /**
     * {@code memory.*} — the notebook knobs. Absent sections fall back to
     * {@link AppConfig.Memory#defaults()}, which is the only place a default number is written.
     */
    private static AppConfig.Memory memory(JsonNode memory) {
        AppConfig.Memory defaults = AppConfig.Memory.defaults();
        JsonNode search = memory.path("search");
        JsonNode rate = memory.path("rate");
        JsonNode write = memory.path("write");
        JsonNode context = memory.path("context");
        JsonNode importRules = memory.path("import");
        return new AppConfig.Memory(
                search.path("tagBoost").asDouble(defaults.tagBoost()),
                integer(search, "limit", defaults.searchLimit()),
                rate.path("usefulDelta").asDouble(defaults.usefulDelta()),
                rate.path("uselessDelta").asDouble(defaults.uselessDelta()),
                rate.path("floor").asDouble(defaults.floor()),
                integer(write, "maxTextChars", defaults.maxTextChars()),
                integer(context, "maxEntries", defaults.contextMaxEntries()),
                integer(context, "maxEntryChars", defaults.contextMaxEntryChars()),
                integer(context, "staleAfterSeconds", defaults.contextStaleAfterSeconds()),
                integer(importRules, "maxBytes", defaults.importMaxBytes()));
    }

    private static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asInt(fallback);
    }
}
