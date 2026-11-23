package io.github.c1589190.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.ToolSpec;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.qqconnect.onebot.OneBot11Client;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * High-frequency friend actions with a small typed surface. Provider-specific or uncommon
 * friend/group actions remain available through {@link QqProviderActionTool}.
 */
public final class QqFriendTool implements AgentTool {

    private final QqToolContext context;

    public QqFriendTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_friend";
    }

    @Override
    public String description() {
        return "好友常用操作：info（get_stranger_info）、remark（set_friend_remark）、"
                + "delete（delete_friend）、like（send_like）、poke（friend_poke）、"
                + "request_decide（set_friend_add_request）。除 info 外都需 confirm=true。"
                + "返回 provider 的 response/status/retcode；不支持时保留原始错误。";
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("accountId", Map.of("type", "string"));
        props.put("operation", Map.of("type", "string",
                "enum", List.of("info", "remark", "delete", "like", "poke", "request_decide")));
        props.put("userId", Map.of("type", "string", "description", "目标 QQ 号"));
        props.put("remark", Map.of("type", "string", "description", "备注/申请通过备注"));
        props.put("flag", Map.of("type", "string", "description", "好友申请 flag"));
        props.put("approve", Map.of("type", "boolean", "description", "好友申请同意/拒绝"));
        props.put("times", Map.of("type", "integer", "minimum", 1, "maximum", 20));
        props.put("confirm", Map.of("type", "boolean", "description", "写操作必须 true"));
        return Map.of("type", "object", "properties", props,
                "required", List.of("operation"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        String operation = text(toolContext, "operation").toLowerCase(Locale.ROOT);
        if (operation.isBlank()) {
            return ToolResult.error("INVALID_ARGUMENT", "operation 不能为空");
        }
        boolean write = !"info".equals(operation);
        if (write && !Boolean.TRUE.equals(toolContext.arguments().get("confirm"))) {
            return ToolResult.error("CONFIRM_REQUIRED", "好友写操作必须显式 confirm=true");
        }
        try {
            OneBot11Client client = QqToolSupport.client(context,
                    text(toolContext, "accountId"));
            String action;
            Map<String, Object> params = new LinkedHashMap<>();
            switch (operation) {
                case "info" -> {
                    action = "get_stranger_info";
                    params.put("user_id", longArg(toolContext, "userId"));
                }
                case "remark" -> {
                    action = "set_friend_remark";
                    params.put("user_id", longArg(toolContext, "userId"));
                    params.put("remark", text(toolContext, "remark"));
                }
                case "delete" -> {
                    action = "delete_friend";
                    params.put("user_id", longArg(toolContext, "userId"));
                }
                case "like" -> {
                    action = "send_like";
                    params.put("user_id", longArg(toolContext, "userId"));
                    params.put("times", Math.max(1,
                            intArg(toolContext, "times", 1)));
                }
                case "poke" -> {
                    action = "friend_poke";
                    params.put("user_id", longArg(toolContext, "userId"));
                }
                case "request_decide" -> {
                    action = "set_friend_add_request";
                    params.put("flag", text(toolContext, "flag"));
                    params.put("approve", Boolean.TRUE.equals(
                            toolContext.arguments().get("approve")));
                    String remark = text(toolContext, "remark");
                    if (!remark.isBlank()) {
                        params.put("remark", remark);
                    }
                }
                default -> {
                    return ToolResult.error("INVALID_ARGUMENT", "未知 operation: " + operation);
                }
            }
            JsonNode response = client.sendAction(action, params)
                    .orTimeout(15, TimeUnit.SECONDS).join();
            JsonNode provider = response.path("response");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("operation", operation);
            body.put("action", action);
            body.put("ok", "ok".equals(provider.path("status").asText())
                    && provider.path("retcode").asInt(-1) == 0);
            body.put("response", response);
            return ToolResult.ok(QqToolSupport.JSON.writeValueAsString(body));
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        } catch (Exception failure) {
            return ToolResult.error("FRIEND_ACTION_FAILED",
                    "好友操作失败: " + failure.getClass().getSimpleName());
        }
    }

    private static long longArg(ToolContext context, String name) {
        Object raw = context.arguments().get(name);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String value && !value.isBlank()) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException ignored) {
                // fall through to a stable argument error
            }
        }
        throw new IllegalArgumentException(name + " 必须是非空 QQ 号");
    }

    private static int intArg(ToolContext context, String name, int fallback) {
        Object raw = context.arguments().get(name);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String value && !value.isBlank()) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String text(ToolContext context, String name) {
        Object raw = context.arguments().get(name);
        return raw instanceof String value ? value.trim() : "";
    }
}
