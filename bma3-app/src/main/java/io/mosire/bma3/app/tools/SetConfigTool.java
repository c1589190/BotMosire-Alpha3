package io.mosire.bma3.app.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.app.config.AppConfig;
import io.mosire.bma3.app.config.ConfigEditor;
import io.mosire.bma3.app.config.ConfigLoader;
import io.mosire.bma3.core.config.ConfigException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Path-level configuration writer: the agent edits its own {@code config/app.yaml} and the running
 * process hot-reloads it.
 *
 * <p>There is deliberately no whitelist — the operator asked for unrestricted edits. Safety comes
 * from validate-then-atomic-replace plus the watcher's last-known-good behaviour: an edit that
 * would break the schema is rejected here and never reaches disk, and an edit to a non-reloadable
 * key simply has no effect until restart (reported as {@code reloaded:false}).</p>
 */
public final class SetConfigTool implements AgentTool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration RELOAD_TIMEOUT = Duration.ofSeconds(5);

    private final ConfigToolContext context;

    public SetConfigTool(ConfigToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "set_config";
    }

    @Override
    public String description() {
        return "修改 config/app.yaml 并触发热重载。patch 是 {点分路径: 值}，unset 是路径数组。"
                + "校验失败不会落盘；返回 applied/reloaded 与生效后的关键策略值。"
                + "例：patch={\"weighting.sourcePriority.qq_group\":150}、"
                + "unset=[\"weighting.attributes.qq_at_all\"]。";
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, true);
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "patch", Map.of("type", "object",
                        "description", "点分路径 → 新值（整数/布尔/字符串）",
                        "additionalProperties", true),
                "unset", Map.of("type", "array",
                        "items", Map.of("type", "string"),
                        "description", "要删除的点分路径")));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        Map<String, Object> patch = asMap(toolContext.arguments().get("patch"));
        List<String> unset = asList(toolContext.arguments().get("unset"));
        if (patch.isEmpty() && unset.isEmpty()) {
            return ToolResult.error("INVALID_ARGUMENT", "patch 与 unset 不能同时为空");
        }
        AppConfig candidate;
        try {
            candidate = ConfigEditor.patch(context.root(), context.env(), patch, unset).config();
        } catch (ConfigException failure) {
            return ToolResult.error("CONFIG_INVALID", "未落盘: " + failure.getMessage());
        } catch (IllegalArgumentException failure) {
            return ToolResult.error("CONFIG_INVALID", "未落盘: " + failure.getMessage());
        }
        boolean reloaded = awaitReload(candidate);
        ObjectNode root = JSON.createObjectNode();
        root.put("applied", true);
        root.put("reloaded", reloaded);
        root.put("configFile", ConfigLoader.configFile(context.root()).toString());
        root.set("effective", ConfigView.effective(context.effective().get()));
        if (!reloaded) {
            root.put("hint", "文件已写入，但当前进程没有采纳（可能改的是非热重载项，或热重载被拒）。"
                    + "端口/host/tokenEnv 需要重启；其余见 logs 的 config_reload_rejected。");
        }
        return ToolResult.ok(root.toString());
    }

    /** Waits briefly for {@link ConfigWatcher}-driven adoption of exactly this snapshot. */
    private boolean awaitReload(AppConfig candidate) {
        Instant deadline = Instant.now().plus(RELOAD_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (candidate.equals(context.effective().get())) {
                return true;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return candidate.equals(context.effective().get());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<String> asList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
