package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.source.SourceAddress;
import java.util.List;
import java.util.Map;

/** Starts a private conversation with an arbitrary QQ user. */
public final class QqPrivateSendTool implements AgentTool {

    private final QqToolContext context;

    public QqPrivateSendTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_private_send";
    }

    @Override
    public String description() {
        return "主动向任意 QQ 用户发私聊；先写消息板 OUT，再发送并返回三态。";
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "accountId", Map.of("type", "string"),
                "userId", Map.of("type", "integer"),
                "text", Map.of("type", "string")),
                "required", List.of("userId", "text"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String accountId = toolContext.arguments().get("accountId") instanceof String value
                    ? value : "";
            long userId = QqToolSupport.longValue(toolContext, "userId");
            String text = QqToolSupport.text(toolContext, "text");
            QqToolSupport.client(context, accountId);
            String resolved = accountId.isBlank()
                    ? context.accounts().accounts().getFirst().value() : accountId;
            var source = SourceAddress.parse("qq_private/" + resolved + "/" + userId);
            return ToolResult.ok(QqSendSupport.sendText(context, source, text, "", "send_private_msg"));
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
