package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        return "向 source 指定的 QQ 会话发送文本；返回 SENT / FAILED_BEFORE_SEND / UNKNOWN，UNKNOWN 不自动重发。";
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
            OneBot11Client client = QqToolSupport.client(context, source);
            String messageKey = "out-" + UUID.randomUUID();
            var stored = context.board().append(new InboundMessage(
                    source, messageKey, Instant.now(), List.of(new Segment("text", text)),
                    Direction.OUTBOUND));
            String action;
            Map<String, Object> params;
            if ("qq_group".equals(source.platform())) {
                action = "send_group_msg";
                params = Map.of("group_id", Long.parseLong(source.segments().get(1)), "message", text);
            } else {
                action = "send_private_msg";
                params = Map.of("user_id", Long.parseLong(source.segments().get(1)), "message", text);
            }
            String state;
            String externalId = "";
            try {
                var response = client.sendAction(action, params).join();
                boolean ok = "ok".equals(response.path("status").asText())
                        && response.path("retcode").asInt(-1) == 0;
                state = ok ? "SENT" : "FAILED_BEFORE_SEND";
                externalId = response.path("data").path("message_id").asText("");
            } catch (RuntimeException failure) {
                state = "UNKNOWN";
            }
            var root = QqToolSupport.object();
            root.put("state", state);
            root.put("localSeq", stored.message().localSeq());
            root.put("messageId", externalId);
            root.put("action", action);
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
