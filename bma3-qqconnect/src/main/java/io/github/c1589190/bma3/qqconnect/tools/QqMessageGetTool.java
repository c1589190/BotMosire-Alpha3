package io.github.c1589190.bma3.qqconnect.tools;

import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.core.source.SourceAddress;
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
        return "按 source + local seq 读取消息板中的一条消息；带 sender、externalMessageId、self，"
                + "出站记录另带 delivery{status,operationId,partIndex,partTotal,errorCode}；"
                + "includeSegments=true 可拿到该消息的结构化段（图片 file/url、引用 id 等）。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "source", Map.of("type", "string"),
                "seq", Map.of("type", "integer", "minimum", 1),
                "includeSegments", QqToolSupport.segmentsSchema()),
                "required", List.of("source", "seq"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            SourceAddress source = QqToolSupport.source(toolContext);
            long seq = QqToolSupport.longValue(toolContext, "seq");
            boolean includeSegments = QqToolSupport.includeSegments(toolContext);
            var messages = context.board().read(source, seq - 1, 1);
            if (messages.isEmpty() || messages.getFirst().localSeq() != seq) {
                return ToolResult.error("MESSAGE_NOT_FOUND", source.format() + "#" + seq);
            }
            var root = QqToolSupport.messageNode(messages.getFirst(), includeSegments);
            root.put("source", source.format());
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
