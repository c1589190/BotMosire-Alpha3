package io.mosire.bma3.memory.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.memory.MemoryOptions;
import io.mosire.bma3.memory.MemoryStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

/**
 * Appends one notebook entry. This is the only write path into memory: nothing extracts,
 * summarises or auto-ingests in the background.
 */
public final class MemoryWriteTool implements AgentTool {

    private static final Set<String> SOURCE_KINDS =
            Set.of("agent", "message_board", "user_said", "import");

    private final MemoryStore store;
    private final Supplier<MemoryOptions> options;
    private final LongPredicate boardSeqExists;

    public MemoryWriteTool(MemoryStore store, Supplier<MemoryOptions> options,
            LongPredicate boardSeqExists) {
        this.store = store;
        this.options = options;
        this.boardSeqExists = boardSeqExists;
    }

    @Override
    public String name() {
        return "memory_write";
    }

    @Override
    public String description() {
        return "写一条长期记忆（只增不删）。text 是完整正文，tags 用空格或逗号分隔，"
                + "标签在检索时权重远高于正文命中。source_kind=message_board 时 source_ref 必须是"
                + "真实存在的消息板 local_seq；context_ref 可放 dsh 会话事件锚点。"
                + "上限 " + options.get().maxTextChars() + " 字符。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return MemoryToolSupport.schema(MemoryToolSupport.property(
                "text", MemoryToolSupport.property(
                        "type", "string",
                        "minLength", 1,
                        "description", "记忆正文"),
                "tags", MemoryToolSupport.property(
                        "type", "string",
                        "description", "空格或逗号分隔的标签"),
                "source_kind", MemoryToolSupport.property(
                        "type", "string",
                        "enum", List.copyOf(SOURCE_KINDS),
                        "description", "默认 agent"),
                "source_ref", MemoryToolSupport.property(
                        "type", "string",
                        "description", "消息板 local_seq 或导入文件名"),
                "context_ref", MemoryToolSupport.property(
                        "type", "string",
                        "description", "dsh 会话事件锚点，形如 session=<id>#seq=<n>")),
                List.of("text"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String text = MemoryToolSupport.string(toolContext, "text", "");
            if (text.isBlank()) {
                return MemoryToolSupport.invalid("text 不能为空");
            }
            // The store enforces its own ceiling too; the tool checks the advertised one so the
            // description and the rejection message can never disagree.
            int maxChars = options.get().maxTextChars();
            if (text.codePointCount(0, text.length()) > maxChars) {
                return MemoryToolSupport.invalid("text 超过上限 " + maxChars + " 字符");
            }
            String kind = MemoryToolSupport.string(toolContext, "source_kind", "agent");
            if (!SOURCE_KINDS.contains(kind)) {
                return MemoryToolSupport.invalid("source_kind 非法: " + kind);
            }
            String sourceRef = MemoryToolSupport.string(toolContext, "source_ref", "");
            if (kind.equals("message_board")) {
                long seq;
                try {
                    seq = Long.parseLong(sourceRef);
                } catch (NumberFormatException failure) {
                    return MemoryToolSupport.invalid(
                            "source_kind=message_board 需要 source_ref 是 local_seq");
                }
                if (seq <= 0 || !boardSeqExists.test(seq)) {
                    return MemoryToolSupport.invalid("消息板不存在 local_seq=" + seq);
                }
            }
            String tags = MemoryToolSupport.string(toolContext, "tags", "");
            String contextRef = MemoryToolSupport.string(toolContext, "context_ref", "");
            long id = store.write(text, tags, kind, sourceRef, contextRef, Instant.now());
            var payload = MemoryToolSupport.object();
            payload.put("id", id);
            payload.put("chars", text.strip().length());
            payload.put("tags", tags);
            return MemoryToolSupport.ok(payload);
        } catch (IllegalArgumentException failure) {
            return MemoryToolSupport.invalid(failure.getMessage());
        } catch (RuntimeException failure) {
            return ToolResult.error("MEMORY_WRITE_FAILED", String.valueOf(failure.getMessage()));
        }
    }
}
