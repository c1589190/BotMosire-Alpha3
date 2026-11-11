package io.mosire.bma3.inbox.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.inbox.core.InboxItem;
import io.mosire.bma3.inbox.core.InboxService;
import io.mosire.bma3.inbox.core.PanelItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compact list of every unacked item, including the ones beyond the panel's top-M window.
 *
 * <p>Deliberately body-free: one short preview line plus counts. Use {@code inbox_get} for the
 * entries themselves and {@code qq_conversation_history} for the full board.</p>
 */
public final class InboxItemsTool implements AgentTool {

    private final InboxService service;

    public InboxItemsTool(InboxService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "inbox_items";
    }

    @Override
    public String description() {
        return "列出所有未 ack 的 inbox 条目（含面板窗口外的）：index/source/priority/status/条数/"
                + "最近时间/一行预览，不含正文。用 inbox_get 取某个条目的完整 entries。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200)));
    }

    @Override
    public ToolResult execute(ToolContext context) {
        int limit = context.arguments().get("limit") instanceof Number number
                ? Math.max(1, Math.min(200, number.intValue())) : 50;
        List<InboxItem> items = service.items();
        boolean includeOmitted = true;
        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 0;
        for (InboxItem item : items) {
            index++;
            if (rows.size() >= limit) {
                break;
            }
            rows.add(Map.of(
                    "index", index,
                    "id", item.id(),
                    "source", item.source(),
                    "sourceLabel", item.sourceLabel(),
                    "priority", item.priority(),
                    "status", item.status().name(),
                    "totalMessages", item.totalMessages(),
                    "entriesShown", Math.min(item.entries().size(), Integer.MAX_VALUE),
                    "lastAt", item.updatedAt().toString(),
                    "preview", preview(item)));
        }
        return InboxTools.json(Map.of(
                "revision", service.panel().revision(),
                "total", items.size(),
                "shown", rows.size(),
                "items", rows));
    }

    private static String preview(InboxItem item) {
        String summary = item.summary() == null ? "" : item.summary().strip();
        if (summary.isEmpty()) {
            return "";
        }
        String firstLine = summary.lines().findFirst().orElse("");
        return firstLine.length() <= 120 ? firstLine : firstLine.substring(0, 120) + "…";
    }

    /** Kept for symmetry with {@link PanelItem}: index order is the same as the panel. */
    static int indexOf(List<PanelItem> items, String itemId) {
        for (PanelItem item : items) {
            if (item.id().equals(itemId)) {
                return item.index();
            }
        }
        return 0;
    }
}
