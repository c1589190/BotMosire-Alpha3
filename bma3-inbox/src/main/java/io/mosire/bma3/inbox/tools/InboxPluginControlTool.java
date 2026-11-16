package io.mosire.bma3.inbox.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.inbox.core.ControlCommand;
import io.mosire.bma3.inbox.core.InboxService;
import io.mosire.bma3.inbox.core.PluginHeartbeat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP fallback for the native {@code bma3_inbox} tool. It can only inspect the plugin heartbeat
 * and queue the existing restore/remind control commands; it never ACKs, changes inbox identity
 * or writes message state.
 */
public final class InboxPluginControlTool implements AgentTool {

    private final InboxService service;

    public InboxPluginControlTool(InboxService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "inbox_plugin_control";
    }

    @Override
    public String description() {
        return "MCP 兜底查看/触发 DSH 插件绑定：action=status 返回心跳和 pending control；"
                + "restore 请求插件恢复已保存的会话（可选 sessionId）；remind 向已绑定会话发送短提醒；"
                + "recover 需 confirm=true，清除 stale pendingPush/deferredPush 并重试当前面板。"
                + "只排队控制命令，不直接 ACK、改身份或写消息。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string",
                "enum", List.of("status", "restore", "remind", "recover"),
                "description", "默认 status"));
        properties.put("sessionId", Map.of("type", "string",
                "description", "restore 可选；省略时使用服务端保存的 pluginSessionId"));
        properties.put("text", Map.of("type", "string",
                "description", "remind 的短文本，最多 500 字符"));
        properties.put("confirm", Map.of("type", "boolean",
                "description", "recover 必须为 true；会清除 stale pendingPush/deferredPush"));
        return Map.of("type", "object", "properties", properties,
                "required", List.of("action"));
    }

    @Override
    public ToolResult execute(ToolContext context) {
        Map<String, Object> args = context.arguments();
        String action = text(args.get("action"), "status");
        try {
            ControlCommand command;
            String message;
            if ("status".equals(action)) {
                return InboxTools.json(statusBody());
            } else if ("restore".equals(action)) {
                command = service.requestControl("restore", text(args.get("sessionId"), ""),
                        "MCP operator requested inbox restore");
                message = "已排队 restore；插件下一次心跳会应用。";
            } else if ("remind".equals(action)) {
                String reminder = text(args.get("text"), "").trim();
                if (reminder.isEmpty()) {
                    return ToolResult.error("INVALID_ARGUMENT", "remind 的 text 不能为空");
                }
                if (reminder.length() > 500) {
                    return ToolResult.error("INVALID_ARGUMENT", "remind 的 text 不能超过 500 字符");
                }
                command = service.requestControl("remind", "", reminder);
                message = "已排队 remind；插件下一次心跳会应用。";
            } else if ("recover".equals(action)) {
                if (!Boolean.TRUE.equals(args.get("confirm"))) {
                    return ToolResult.error("CONFIRM_REQUIRED",
                            "recover 需要 confirm=true：会清除 pendingPush/deferredPush 并重新校验面板");
                }
                command = service.requestControl("recover", "",
                        "MCP operator requested stale pendingPush recovery");
                message = "已排队 recover；插件下一次心跳会清除 stale 状态并重新校验当前面板。";
            } else {
                return ToolResult.error("INVALID_ARGUMENT",
                        "action 只能是 status/restore/remind/recover");
            }
            Map<String, Object> body = statusBody();
            body.put("message", message);
            body.put("control", command);
            return InboxTools.json(body);
        } catch (RuntimeException failure) {
            return ToolResult.error("PLUGIN_CONTROL_FAILED", failure.getMessage());
        }
    }

    private Map<String, Object> statusBody() {
        PluginHeartbeat heartbeat = service.lastHeartbeat();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pluginSessionId", service.pluginSessionId());
        body.put("heartbeat", heartbeat);
        body.put("healthy", heartbeat != null && heartbeat.healthy());
        body.put("pendingControl", service.pendingControl());
        body.put("runtime", service.runtimeInfo());
        return body;
    }

    private static String text(Object value, String fallback) {
        return value instanceof String raw && !raw.isBlank() ? raw.trim() : fallback;
    }
}
