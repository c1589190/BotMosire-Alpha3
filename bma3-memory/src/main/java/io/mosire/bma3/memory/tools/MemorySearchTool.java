package io.mosire.bma3.memory.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.memory.MemoryHit;
import io.mosire.bma3.memory.MemoryOptions;
import io.mosire.bma3.memory.MemoryStore;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Paged digest list. Tags win over body text, and only a digest is returned: the Agent has to ask
 * for {@code memory_view} to see the body.
 */
public final class MemorySearchTool implements AgentTool {

    private final MemoryStore store;
    private final Supplier<MemoryOptions> options;

    public MemorySearchTool(MemoryStore store, Supplier<MemoryOptions> options) {
        this.store = store;
        this.options = options;
    }

    @Override
    public String name() {
        return "memory_search";
    }

    @Override
    public String description() {
        return "检索长期记忆，只返回摘要列表（id/标签/时间/前 60 字），正文用 memory_view 取。"
                + "标签命中优先于正文命中。静音（weight=-1）的记忆不会出现在列表里。"
                + "limit 默认 " + options.get().searchLimit() + "，cursor 用上一页返回值翻页。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return MemoryToolSupport.schema(MemoryToolSupport.property(
                "query", MemoryToolSupport.property(
                        "type", "string",
                        "minLength", 1,
                        "description", "关键词；标签按完整词匹配，正文按子串匹配"),
                "limit", MemoryToolSupport.property(
                        "type", "integer",
                        "minimum", 1,
                        "maximum", 50),
                "cursor", MemoryToolSupport.property(
                        "type", "string",
                        "description", "上一页的 next_cursor")),
                List.of("query"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String query = MemoryToolSupport.string(toolContext, "query", "");
            if (query.isBlank()) {
                return MemoryToolSupport.invalid("query 不能为空");
            }
            int limit = Math.max(1, Math.min(50,
                    MemoryToolSupport.integer(toolContext, "limit",
                            options.get().searchLimit())));
            int offset = 0;
            String cursor = MemoryToolSupport.string(toolContext, "cursor", "");
            if (!cursor.isBlank()) {
                try {
                    offset = Integer.parseInt(cursor);
                } catch (NumberFormatException failure) {
                    return MemoryToolSupport.invalid("cursor 非法: " + cursor);
                }
            }
            List<MemoryHit> hits = store.search(query, limit, offset);
            var payload = MemoryToolSupport.object();
            var items = payload.putArray("items");
            for (MemoryHit hit : hits) {
                var row = items.addObject();
                row.put("id", hit.id());
                row.put("digest", hit.digest());
                row.put("tags", hit.tags());
                row.put("weight", hit.weight());
                row.put("at", String.valueOf(hit.createdAt()));
            }
            payload.put("count", hits.size());
            if (hits.size() >= limit) {
                payload.put("next_cursor", String.valueOf(offset + hits.size()));
            }
            return MemoryToolSupport.ok(payload);
        } catch (IllegalArgumentException failure) {
            return MemoryToolSupport.invalid(failure.getMessage());
        } catch (RuntimeException failure) {
            return ToolResult.error("MEMORY_SEARCH_FAILED", String.valueOf(failure.getMessage()));
        }
    }
}
