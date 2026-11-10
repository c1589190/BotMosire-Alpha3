package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.source.SourceAddress;
import java.util.List;
import java.util.Map;

/** Sends a text reply through the configured account; returns SENT/FAILED_BEFORE_SEND/UNKNOWN. */
public final class QqConversationSendTool implements AgentTool {

    private final QqToolContext context;

    public QqConversationSendTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_conversation_send";
    }

    @Override
    public String description() {
        return "向 source 指定的 QQ 会话发送文本；返回逐段 SENT / FAILED_BEFORE_SEND / UNKNOWN，"
                + "UNKNOWN 不自动重发；超过配置上限按 reject/split 处理。";
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "source", Map.of("type", "string"),
                "text", Map.of("type", "string")), "required", List.of("source", "text"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            SourceAddress source = QqToolSupport.source(toolContext);
            String text = QqToolSupport.text(toolContext, "text");
            String action = "qq_group".equals(source.platform())
                    ? "send_group_msg" : "send_private_msg";
            return ToolResult.ok(QqSendSupport.sendText(context, source, text, "", action));
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
