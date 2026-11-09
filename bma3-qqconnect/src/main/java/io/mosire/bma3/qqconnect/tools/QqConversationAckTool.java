package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.source.SourceAddress;
import java.util.List;
import java.util.Map;

/** Advances the caller's read cursor after processing a conversation. */
public final class QqConversationAckTool implements AgentTool {

    private final QqToolContext context;

    public QqConversationAckTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_conversation_ack";
    }

    @Override
    public String description() {
        return "处理完 source 的消息后推进读取游标；seq 使用 read 返回的 lastSeq。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "source", Map.of("type", "string"),
                "seq", Map.of("type", "integer", "minimum", 0)), "required", List.of("source", "seq"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            SourceAddress source = QqToolSupport.source(toolContext);
            QqToolSupport.client(context, source);
            long seq = QqToolSupport.longValue(toolContext, "seq");
            context.consumers().ack(QqToolSupport.consumer(toolContext), source, seq);
            var root = QqToolSupport.object();
            root.put("source", source.format());
            root.put("seq", seq);
            root.put("acked", true);
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
