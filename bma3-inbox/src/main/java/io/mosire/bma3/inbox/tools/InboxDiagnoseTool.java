package io.mosire.bma3.inbox.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.inbox.core.InboxService;
import io.mosire.bma3.inbox.core.PluginHeartbeat;
import java.util.LinkedHashMap;
import java.util.Map;

/** One read-only screen for inbox, plugin heartbeat and control state. */
public final class InboxDiagnoseTool implements AgentTool {

    private final InboxService service;
    private final String version;

    public InboxDiagnoseTool(InboxService service, String version) {
        this.service = service;
        this.version = version == null ? "unknown" : version;
    }

    @Override
    public String name() {
        return "inbox_diagnose";
    }

    @Override
    public String description() {
        return "一屏只读诊断：jar 版本、runtime/MCP 启动 id、Inbox 计数与错误、DSH 插件心跳、"
                + "deferredPush/freshVerification/toolRegistration 摘要、待应用控制命令。"
                + "不返回消息正文、token 或凭据。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public ToolResult execute(ToolContext context) {
        PluginHeartbeat heartbeat = service.lastHeartbeat();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("runtime", service.runtimeInfo());
        body.put("status", service.status());
        body.put("pluginSessionId", service.pluginSessionId());
        body.put("heartbeat", heartbeat);
        body.put("healthy", heartbeat != null && heartbeat.healthy());
        body.put("pendingControl", service.pendingControl());
        return InboxTools.json(body);
    }
}
