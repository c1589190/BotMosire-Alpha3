package io.github.c1589190.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.ToolSpec;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.qqconnect.onebot.OneBot11Client;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Generic, provider-specific OneBot action escape hatch.
 *
 * <p>Read-style actions run directly. Mutating actions require {@code confirm=true}. Message/file
 * send actions are permanently blocked here: sends must use the typed tools so the message board,
 * account routing, and UNKNOWN/no-retry semantics stay authoritative.</p>
 */
public final class QqProviderActionTool implements AgentTool {

    private static final Set<String> BLOCKED_SEND_ACTIONS = Set.of(
            "send_private_msg", "send_group_msg", "send_msg",
            "send_forward_msg", "send_group_forward_msg",
            "upload_group_file", "upload_private_file");

    private final QqToolContext context;

    public QqProviderActionTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_provider_action";
    }

    @Override
    public String description() {
        return "调用 provider 的 OneBot action（用于尚未 typed 的好友/群/资料能力）。"
                + "get_*/can_*/check_*/ocr_*/download_* 视为只读；其他 action 必须 confirm=true。"
                + "send_*_msg/send_forward_msg/upload_*_file 永久禁止：发送必须走消息板 typed 工具。"
                + "params 是 provider 原始参数对象；返回原始 response/status/retcode。";
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("accountId", Map.of("type", "string"));
        props.put("action", Map.of("type", "string",
                "description", "provider action，例如 get_friend_list / set_friend_remark / delete_friend"));
        props.put("params", Map.of("type", "object",
                "description", "provider 原始参数对象，默认空", "additionalProperties", true));
        props.put("confirm", Map.of("type", "boolean",
                "description", "写动作必须 true；只读动作可省略"));
        return Map.of("type", "object", "properties", props, "required", java.util.List.of("action"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        String action = text(toolContext, "action");
        if (action.isBlank()) {
            return ToolResult.error("INVALID_ARGUMENT", "action 不能为空");
        }
        String normalized = action.toLowerCase(Locale.ROOT);
        if (BLOCKED_SEND_ACTIONS.contains(normalized)) {
            return ToolResult.error("SEND_BYPASS_FORBIDDEN",
                    "发送/上传 action 必须走消息板 typed 工具，禁止从通用 action 直通: " + action);
        }
        boolean readOnly = normalized.startsWith("get_")
                || normalized.startsWith("can_")
                || normalized.startsWith("check_")
                || normalized.startsWith("ocr_")
                || normalized.startsWith("download_");
        boolean confirm = Boolean.TRUE.equals(toolContext.arguments().get("confirm"));
        if (!readOnly && !confirm) {
            return ToolResult.error("CONFIRM_REQUIRED",
                    "写 action 必须显式 confirm=true: " + action);
        }
        Map<String, Object> params = params(toolContext);
        try {
            OneBot11Client client = QqToolSupport.client(context,
                    toolContext.arguments().get("accountId") instanceof String value ? value : "");
            JsonNode response = client.sendAction(action, params)
                    .orTimeout(15, java.util.concurrent.TimeUnit.SECONDS).join();
            var body = QqToolSupport.object();
            body.put("action", action);
            body.put("readOnly", readOnly);
            body.set("response", response);
            return ToolResult.ok(body.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        } catch (RuntimeException failure) {
            return ToolResult.error("PROVIDER_ACTION_FAILED",
                    "provider action 失败: " + failure.getClass().getSimpleName());
        }
    }

    private static Map<String, Object> params(ToolContext context) {
        Object raw = context.arguments().get("params");
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) {
                values.put(String.valueOf(key), value);
            }
        });
        return values;
    }

    private static String text(ToolContext context, String name) {
        Object raw = context.arguments().get(name);
        return raw instanceof String value ? value.trim() : "";
    }
}
