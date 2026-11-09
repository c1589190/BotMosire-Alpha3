package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.source.SourceAddress;
import java.util.List;
import java.util.Map;

/** Reads both inbound and outbound history for one source. */
public final class QqConversationHistoryTool implements AgentTool {

    private final QqToolContext context;

    public QqConversationHistoryTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_conversation_history";
    }

    @Override
    public String description() {
        return "按 source 读取消息板历史（含入站/出站）；不推进读取游标。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "source", Map.of("type", "string"),
                "afterSeq", Map.of("type", "integer", "minimum", 0),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 500)),
                "required", List.of("source"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            SourceAddress source = QqToolSupport.source(toolContext);
            int limit = QqToolSupport.integer(toolContext, "limit", 50);
            long after = toolContext.arguments().get("afterSeq") instanceof Number n
                    ? n.longValue() : 0L;
            var root = QqToolSupport.object();
            root.put("source", source.format());
            var array = root.putArray("messages");
            for (var message : context.board().read(source, after, limit)) {
                var item = array.addObject();
                item.put("seq", message.localSeq());
                item.put("messageId", message.inbound().messageId());
                item.put("at", message.inbound().at().toString());
                item.put("direction", message.inbound().direction() == Direction.INBOUND
                        ? "IN" : "OUT");
                item.put("text", QqToolSupport.messageText(message));
            }
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
