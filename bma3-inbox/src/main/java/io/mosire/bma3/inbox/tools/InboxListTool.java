package io.mosire.bma3.inbox.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.inbox.core.InboxService;
import java.util.Map;

/** Returns the bounded aggregate panel; full content stays in the message board. */
public final class InboxListTool implements AgentTool {

    private final InboxService service;

    public InboxListTool(InboxService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "inbox_list";
    }

    @Override
    public String description() {
        return "返回 Inbox 总面板：最多 top M 个来源窗口，每个窗口最近 N 条消息；完整正文在消息板。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public ToolResult execute(ToolContext context) {
        return InboxTools.json(service.panel());
    }
}
