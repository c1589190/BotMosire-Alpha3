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
import java.util.function.BiPredicate;

/**
 * Full body by id. Muted rows stay readable: muting only removes an entry from search lists.
 * A successful read counts as a use, which {@code memory_rate} and any later reranker can weigh.
 *
 * <p>When a row carries a {@code context_ref} the answer reports {@code stale}: a pointer whose
 * DSH event is no longer present in the jar's rolling copy (evicted, never pushed, or forged) is
 * not a usable citation, and the Agent must be told rather than quietly trusting it.</p>
 */
public final class MemoryViewTool implements AgentTool {

    private static final String SESSION_MARK = "session=";
    private static final String SEQ_MARK = "#seq=";

    private final MemoryStore store;
    private final BiPredicate<String, Long> contextExists;

    public MemoryViewTool(MemoryStore store) {
        this(store, null);
    }

    public MemoryViewTool(MemoryStore store, BiPredicate<String, Long> contextExists) {
        this.store = store;
        this.contextExists = contextExists;
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
            payload.put("stale", stale(item.contextRef()));
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

    /**
     * True when the pointer cannot be resolved. A blank pointer is not stale — it simply makes no
     * claim. A pointer we cannot parse, or cannot check, is reported stale: unverifiable is not
     * the same thing as verified.
     */
    private boolean stale(String contextRef) {
        if (contextRef == null || contextRef.isBlank()) {
            return false;
        }
        int sessionAt = contextRef.indexOf(SESSION_MARK);
        int seqAt = contextRef.indexOf(SEQ_MARK);
        if (sessionAt < 0 || seqAt < 0 || seqAt < sessionAt) {
            return true;
        }
        String session = contextRef.substring(sessionAt + SESSION_MARK.length(), seqAt).trim();
        String seqText = contextRef.substring(seqAt + SEQ_MARK.length()).trim();
        int end = 0;
        while (end < seqText.length() && Character.isDigit(seqText.charAt(end))) {
            end++;
        }
        if (session.isEmpty() || end == 0) {
            return true;
        }
        if (contextExists == null) {
            return true;
        }
        return !contextExists.test(session, Long.parseLong(seqText.substring(0, end)));
    }
}
