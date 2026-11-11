package io.mosire.bma3.inbox.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.inbox.core.InboxEntry;
import io.mosire.bma3.inbox.core.InboxItem;
import io.mosire.bma3.inbox.core.InboxService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Full detail for one inbox item: its bounded entries plus counts and revision. */
public final class InboxGetTool implements AgentTool {

    private final InboxService service;

    public InboxGetTool(InboxService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "inbox_get";
    }

    @Override
    public String description() {
        return "按 itemId 取一个 inbox 条目的完整窗口（≤ maxMessagesPerItem 条 entries，含 sender/时间/"
                + "序号/markers/渲染行）、totalMessages、priority、status、revision、continued"
                + "（true=这条在被推送过之后又续上了新消息，行尾带「续」标）。"
                + "已 ack 的条目仍可取（status=ACKED），但不再出现在面板里。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "itemId", Map.of("type", "string")),
                "required", List.of("itemId"));
    }

    @Override
    public ToolResult execute(ToolContext context) {
        Object raw = context.arguments().get("itemId");
        if (!(raw instanceof String itemId) || itemId.isBlank()) {
            return ToolResult.error("INVALID_ARGUMENT", "itemId 不能为空");
        }
        try {
            InboxItem item = service.item(itemId);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (InboxEntry entry : item.entries()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("localSeq", entry.localSeq());
                row.put("at", entry.at().toString());
                row.put("sender", entry.sender());
                row.put("text", entry.text());
                row.put("markers", entry.markers());
                row.put("line", entry.line());
                entries.add(row);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", item.id());
            body.put("source", item.source());
            body.put("sourceLabel", item.sourceLabel());
            body.put("priority", item.priority());
            body.put("status", item.status().name());
            body.put("totalMessages", item.totalMessages());
            body.put("omittedMessages", Math.max(0, item.totalMessages() - item.entries().size()));
            body.put("eventRevision", item.eventRevision());
            body.put("revision", item.revision());
            body.put("createdAt", item.createdAt().toString());
            body.put("updatedAt", item.updatedAt().toString());
            body.put("continued", item.continued());
            body.put("entries", entries);
            return InboxTools.json(body);
        } catch (IllegalArgumentException failure) {
            return ToolResult.error("ITEM_NOT_FOUND", failure.getMessage());
        }
    }
}
