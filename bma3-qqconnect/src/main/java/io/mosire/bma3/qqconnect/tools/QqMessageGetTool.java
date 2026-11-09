package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.source.SourceAddress;
import java.util.List;
import java.util.Map;

/** Gets one message by source and local sequence. */
public final class QqMessageGetTool implements AgentTool {

    private final QqToolContext context;

    public QqMessageGetTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_message_get";
    }

    @Override
    public String description() {
        return "按 source + local seq 读取消息板中的一条消息。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "source", Map.of("type", "string"),
                "seq", Map.of("type", "integer", "minimum", 1)),
                "required", List.of("source", "seq"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            SourceAddress source = QqToolSupport.source(toolContext);
            long seq = QqToolSupport.longValue(toolContext, "seq");
            var messages = context.board().read(source, seq - 1, 1);
            if (messages.isEmpty() || messages.getFirst().localSeq() != seq) {
                return ToolResult.error("MESSAGE_NOT_FOUND", source.format() + "#" + seq);
            }
            var message = messages.getFirst();
            var root = QqToolSupport.object();
            root.put("seq", message.localSeq());
            root.put("messageId", message.inbound().messageId());
            root.put("at", message.inbound().at().toString());
            root.put("text", QqToolSupport.messageText(message));
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
