package io.mosire.bma3.inbox.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.inbox.core.InboxService;
import java.util.Map;

/** Lists compact inbox items; full content stays in the message board. */
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
        return "列出当前 Inbox 的未完成条目（source/summary/priority/status），不含正文。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public ToolResult execute(ToolContext context) {
        return InboxTools.json(service.list());
    }
}
