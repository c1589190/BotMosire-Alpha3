package io.github.c1589190.bma3.inbox.tools;

import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.inbox.core.InboxService;
import java.util.Map;

/** Returns the inbox counts without exposing content. */
public final class InboxStatusTool implements AgentTool {

    private final InboxService service;

    public InboxStatusTool(InboxService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "inbox_status";
    }

    @Override
    public String description() {
        return "返回当前 Inbox 的 available/acked/remainingImportant、待推送数 pendingPush 与下一次推送时刻 nextPushAt、panel revision 与 unread 计数。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public ToolResult execute(ToolContext context) {
        return InboxTools.json(service.status());
    }
}
