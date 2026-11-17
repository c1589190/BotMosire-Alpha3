package io.mosire.bma3.qqofficial.tools;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.qqofficial.send.OfficialQqSendException;
import io.mosire.bma3.qqofficial.send.OfficialQqSendOutcome;
import io.mosire.bma3.qqofficial.send.OfficialQqSendService;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** AgentTool for passive official QQ replies by real inbound msg_id or event_id. */
public final class QqOfficialReplyTool implements AgentTool {

    public static final String NAME = "qq_official_reply";
    private final OfficialQqSendService service;

    public QqOfficialReplyTool(OfficialQqSendService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "在指定 QQ 官方账号与 raw source 内被动回复；replyToMessageId 与 eventId "
                + "二选一：msg_id 必须是同 source 的真实入站消息，event_id 必须由服务端持久事实"
                + "证明同账号/来源真实入站。返回逐段 SENT/FAILED_BEFORE_SEND/UNKNOWN，"
                + "UNKNOWN 不自动重发；本工具无需人工确认。";
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "accountId", Map.of(
                                "type", "string",
                                "description", "已启用的 QQ 官方机器人账号 ID"),
                        "source", Map.of(
                                "type", "string",
                                "description", "官方群或单聊的 raw source"),
                        "text", Map.of("type", "string", "description", "回复文本"),
                        "replyToMessageId", Map.of(
                                "type", "string",
                                "description", "同 source 的真实入站官方 msg_id"),
                        "eventId", Map.of(
                                "type", "string",
                                "description", "已由服务端持久事件引用证明可回复的官方 event_id")),
                "required", List.of("accountId", "source", "text"),
                "oneOf", List.of(
                        Map.of("required", List.of("replyToMessageId")),
                        Map.of("required", List.of("eventId"))),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            String accountId = QqOfficialSendTextTool.stringArgument(context, "accountId");
            SourceAddress source = QqOfficialSendTextTool.source(context);
            String text = QqOfficialSendTextTool.stringArgument(context, "text");
            String replyTo = optionalString(context, "replyToMessageId");
            String eventId = optionalString(context, "eventId");
            List<OfficialQqSendOutcome> outcomes =
                    service.reply(accountId, source, text, replyTo, eventId);
            return QqOfficialSendToolResults.result(outcomes);
        } catch (OfficialQqSendException failure) {
            return ToolResult.error(failure.code(), failure.safeMessage());
        } catch (IllegalArgumentException failure) {
            return ToolResult.error("INVALID_ARGUMENT", failure.getMessage());
        } catch (RuntimeException failure) {
            return ToolResult.error("INTERNAL_ERROR", "官方回复发生内部错误");
        }
    }

    private static String optionalString(ToolContext context, String name) {
        Object raw = context.arguments().get(name);
        return raw instanceof String value ? value : "";
    }
}
