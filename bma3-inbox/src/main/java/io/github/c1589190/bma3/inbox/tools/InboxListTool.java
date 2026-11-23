package io.github.c1589190.bma3.inbox.tools;

import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.inbox.core.InboxService;
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
