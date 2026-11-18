package io.mosire.bma3.memory.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.memory.MemoryOptions;
import io.mosire.bma3.memory.MemoryStore;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ranking feedback. {@code useful}/{@code useless} move the weight inside
 * {@code [memory.rate.floor, +inf)}; only {@code mute} sets the {@code -1} sentinel, and muting is
 * one-way because there is no forget/delete path.
 */
public final class MemoryRateTool implements AgentTool {

    private final MemoryStore store;

    public MemoryRateTool(MemoryStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "memory_rate";
    }

    @Override
    public String description() {
        return "给记忆打反馈：useful 加权、useless 减权（下限 " + "memory.rate.floor"
                + "）、mute 直接置为 " + MemoryOptions.MUTED
                + " 后不再出现在检索列表（仍可按 id 读取）。静音不可逆。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return MemoryToolSupport.schema(MemoryToolSupport.property(
                "id", MemoryToolSupport.property(
                        "type", "integer",
                        "minimum", 1),
                "value", MemoryToolSupport.property(
                        "type", "string",
                        "enum", List.of("useful", "useless", "mute"))),
                List.of("id", "value"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            long id = MemoryToolSupport.lng(toolContext, "id", 0L);
            if (id <= 0) {
                return MemoryToolSupport.invalid("id 必须是正整数");
            }
            String raw = MemoryToolSupport.string(toolContext, "value", "");
            MemoryStore.Vote vote = switch (raw.toLowerCase(Locale.ROOT)) {
                case "useful" -> MemoryStore.Vote.USEFUL;
                case "useless" -> MemoryStore.Vote.USELESS;
                case "mute" -> MemoryStore.Vote.MUTE;
                default -> null;
            };
            if (vote == null) {
                return MemoryToolSupport.invalid("value 只能是 useful/useless/mute");
            }
            double weight = store.rate(id, vote, Instant.now());
            var payload = MemoryToolSupport.object();
            payload.put("id", id);
            payload.put("value", raw.toLowerCase(Locale.ROOT));
            payload.put("weight", weight);
            payload.put("muted", weight == MemoryOptions.MUTED);
            return MemoryToolSupport.ok(payload);
        } catch (IllegalArgumentException failure) {
            return MemoryToolSupport.invalid(failure.getMessage());
        } catch (IllegalStateException failure) {
            return ToolResult.error("MEMORY_RATE_REJECTED", String.valueOf(failure.getMessage()));
        } catch (RuntimeException failure) {
            return ToolResult.error("MEMORY_RATE_FAILED", String.valueOf(failure.getMessage()));
        }
    }
}
