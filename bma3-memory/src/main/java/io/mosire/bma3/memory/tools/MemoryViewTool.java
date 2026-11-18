package io.mosire.bma3.memory.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.memory.MemoryItem;
import io.mosire.bma3.memory.MemoryStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Full body by id. Muted rows stay readable: muting only removes an entry from search lists.
 * A successful read counts as a use, which {@code memory_rate} and any later reranker can weigh.
 */
public final class MemoryViewTool implements AgentTool {

    private final MemoryStore store;

    public MemoryViewTool(MemoryStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "memory_view";
    }

    @Override
    public String description() {
        return "按 id 读取一条记忆的完整正文与元数据；静音（weight=-1）的记忆同样可读。"
                + "读取会累加 use_count。看完请用 memory_rate 标记有用/无用。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return MemoryToolSupport.schema(MemoryToolSupport.property(
                "id", MemoryToolSupport.property(
                        "type", "integer",
                        "minimum", 1,
                        "description", "memory_search 返回的 id")),
                List.of("id"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            long id = MemoryToolSupport.lng(toolContext, "id", 0L);
            if (id <= 0) {
                return MemoryToolSupport.invalid("id 必须是正整数");
            }
            Optional<MemoryItem> found = store.view(id, Instant.now(), true);
            if (found.isEmpty()) {
                return MemoryToolSupport.missing("记忆不存在: " + id);
            }
            MemoryItem item = found.get();
            var payload = MemoryToolSupport.object();
            payload.put("id", item.id());
            payload.put("text", item.text());
            payload.put("tags", item.tags());
            payload.put("weight", item.weight());
            payload.put("muted", item.muted());
            payload.put("source_kind", item.sourceKind());
            payload.put("source_ref", item.sourceRef());
            payload.put("context_ref", item.contextRef());
            payload.put("created_at", String.valueOf(item.createdAt()));
            payload.put("last_used_at",
                    item.lastUsedAt() == null ? null : item.lastUsedAt().toString());
            payload.put("use_count", item.useCount());
            return MemoryToolSupport.ok(payload);
        } catch (IllegalArgumentException failure) {
            return MemoryToolSupport.invalid(failure.getMessage());
        } catch (RuntimeException failure) {
            return ToolResult.error("MEMORY_VIEW_FAILED", String.valueOf(failure.getMessage()));
        }
    }
}
