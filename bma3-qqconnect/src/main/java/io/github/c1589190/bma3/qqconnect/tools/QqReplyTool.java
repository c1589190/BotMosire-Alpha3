package io.github.c1589190.bma3.qqconnect.tools;

import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.ToolSpec;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.core.source.SourceAddress;
import java.util.List;
import java.util.Map;

/** Sends text as a reply in an existing source. */
public final class QqReplyTool implements AgentTool {

    private final QqToolContext context;

    public QqReplyTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_reply";
    }

    @Override
    public String description() {
        return "在已有 source 中引用回复一条 QQ 消息；超长文本按配置上限切片。";
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "source", Map.of("type", "string"),
                "text", Map.of("type", "string"),
                "replyToMessageId", Map.of("type", "string")),
                "required", List.of("source", "text"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            SourceAddress source = QqToolSupport.source(toolContext);
            String text = QqToolSupport.text(toolContext, "text");
            String replyTo = toolContext.arguments().get("replyToMessageId") instanceof String value
                    ? value : "";
            String action = "qq_group".equals(source.platform())
                    ? "send_group_msg" : "send_private_msg";
            return QqSendSupport.sendToolResult(context, source, text, replyTo, action);
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
