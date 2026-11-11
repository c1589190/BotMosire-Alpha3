package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.source.SourceAddress;
import java.util.List;
import java.util.Map;

/**
 * Reads inbound messages after the caller's read cursor. Reads do not advance the cursor and do
 * not require a live OneBot connection: the message board is the source of truth.
 */
public final class QqConversationReadTool implements AgentTool {

    private final QqToolContext context;

    public QqConversationReadTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_conversation_read";
    }

    @Override
    public String description() {
        return "按 source 读取会话中未读的入站消息（每条带 sender{id,name}、externalMessageId、self）；"
                + "读取不推进游标，处理完调用 qq_conversation_ack。离线也可读。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "source", Map.of("type", "string"),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100),
                "includeSegments", QqToolSupport.segmentsSchema()),
                "required", List.of("source"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            SourceAddress source = QqToolSupport.source(toolContext);
            int limit = QqToolSupport.integer(toolContext, "limit", 20);
            boolean includeSegments = QqToolSupport.includeSegments(toolContext);
            long after = context.consumers().readSeq(QqToolSupport.consumer(toolContext), source);
            List<Message> messages = context.board().read(source, after, limit);
            var root = QqToolSupport.object();
            root.put("source", source.format());
            root.put("readSeq", after);
            var array = root.putArray("messages");
            long last = after;
            for (Message message : messages) {
                if (message.inbound().direction() != Direction.INBOUND) {
                    continue;
                }
                array.add(QqToolSupport.messageNode(message, includeSegments));
                last = Math.max(last, message.localSeq());
            }
            root.put("lastSeq", last);
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
