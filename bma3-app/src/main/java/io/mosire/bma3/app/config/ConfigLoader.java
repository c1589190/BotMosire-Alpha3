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
        }
    }

    public static Path configFile(Path root) {
        return Files.isDirectory(root) ? root.resolve("config/app.yaml") : root;
    }

    public static List<ConfigError> validate(AppConfig config) {
        List<ConfigError> errors = new ArrayList<>();
        if (config.server().port() < 1 || config.server().port() > 65535) {
            errors.add(new ConfigError("server.port", "必须在 1..65535"));
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
                text(server, "tokenEnv", "BMA3_MCP_TOKEN"));

        List<AppConfig.Account> accounts = new ArrayList<>();
        for (JsonNode account : node.path("qqconnect").path("accounts")) {
            accounts.add(new AppConfig.Account(
                    text(account, "accountId", ""),
                    text(account, "endpoint", ""),
                    text(account, "tokenEnv", ""),
                    account.path("enabled").asBoolean(true)));
        }

        JsonNode inbox = node.path("inbox");
        JsonNode logging = node.path("logging");
        return new AppConfig(
                Path.of(dataDir),
                serverConfig,
                new AppConfig.QqConnect(accounts),
                new AppConfig.Inbox(text(inbox, "inboxId", "bma3")),
                new AppConfig.Logging(text(logging, "level", "INFO"), text(logging, "dir", "logs")));
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

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText(fallback);
    }

    private static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asInt(fallback);
    }
}
