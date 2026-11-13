package io.mosire.bma3.app.config;

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
    private static final Set<String> LOG_LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

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
            String raw = interpolate(Files.readString(file), env);
            JsonNode node = new ObjectMapper(new YAMLFactory()).readTree(raw);
            AppConfig config = parse(node == null ? new ObjectMapper().createObjectNode() : node);
            List<ConfigError> errors = validate(config);
            if (!errors.isEmpty()) {
                throw new ConfigException(errors);
            }
            return config;
        } catch (IOException failure) {
            throw new ConfigException("config", "读取失败: " + failure.getMessage());
        } catch (ConfigException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ConfigException("config", failure.getMessage());
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
        JsonNode send = node.path("qqconnect").path("send");
        JsonNode media = node.path("qqconnect").path("media");
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
                new AppConfig.Inbox(
                        text(inbox, "inboxId", "bma3"),
                        integer(inbox, "httpPort", 46500),
                        new AppConfig.Inbox.Display(
                                integer(inboxDisplay, "maxMessagesPerItem", 6),
                                integer(inboxDisplay, "maxItemsPerPanel", 6),
                                inboxDisplay.path("includeOmittedCount").asBoolean(true),
                                integer(inboxDisplay, "maxCharsPerItem", 1200)),
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
                                integer(inboxDismiss, "autoMaxPerRound", 200))),
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
                        contentRules(weighting.path("rules"))),
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
                                : readyzCritical),
                new AppConfig.Xvfb(
                        xvfb.path("enabled").asBoolean(true),
                        text(xvfb, "workspaceRoot", ""),
                        integer(xvfb, "width", 1440),
                        integer(xvfb, "height", 900),
                        integer(xvfb, "depth", 24)));
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

    /** Missing map falls back to the built-in defaults; an empty map stays empty on purpose. */
    /** Parses {@code weighting.rules[]}; invalid entries fail the whole config (fail-closed). */
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
                    row.hasNonNull("ignoreCase") ? row.path("ignoreCase").asBoolean(true) : null));
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

    private static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asInt(fallback);
    }
}
