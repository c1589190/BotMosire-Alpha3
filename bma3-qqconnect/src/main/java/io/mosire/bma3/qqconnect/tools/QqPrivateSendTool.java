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
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("accountId", Map.of("type", "string"));
        props.put("userId", Map.of("type", "integer"));
        props.put("text", Map.of("type", "string"));
        props.put("groupId", Map.of("type", "integer",
                "description", "群临时会话：传群号后走 send_private_msg 的 group_id 通道，"
                        + "给对方发消息不需要先加好友（该群需要机器人也在）"));
        return Map.of("type", "object", "properties", props,
                "required", List.of("userId", "text"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        // 说明：groupId 缺省时走普通私聊（要求好友关系）；给了 groupId 走群临时会话。
        // 非好友 + provider 不回话时状态是 UNKNOWN（可能已发出），绝不自动重发。
        try {
            String accountId = toolContext.arguments().get("accountId") instanceof String value
                    ? value : "";
            long userId = QqToolSupport.longValue(toolContext, "userId");
            String text = QqToolSupport.text(toolContext, "text");
            QqToolSupport.client(context, accountId);
            String resolved = accountId.isBlank()
                    ? context.accounts().accounts().getFirst().value() : accountId;
            var source = SourceAddress.parse("qq_private/" + resolved + "/" + userId);
            long groupId = QqToolSupport.longValue(toolContext, "groupId");
            if (groupId > 0) {
                // 群临时会话：同一行动作带上 group_id，是 OneBot 给"非好友"准备的通道
                return ToolResult.ok(QqSendSupport.sendText(context, source, text,
                        "", "send_private_msg", java.util.Map.of("group_id", groupId)));
            }
            return ToolResult.ok(QqSendSupport.sendText(context, source, text, "", "send_private_msg"));
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
