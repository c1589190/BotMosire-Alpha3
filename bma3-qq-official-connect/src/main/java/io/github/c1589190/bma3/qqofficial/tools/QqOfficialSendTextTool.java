package io.github.c1589190.bma3.qqofficial.tools;

import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.ToolSpec;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.qqofficial.send.OfficialQqSendException;
import io.github.c1589190.bma3.qqofficial.send.OfficialQqSendOutcome;
import io.github.c1589190.bma3.qqofficial.send.OfficialQqSendService;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** AgentTool for active official QQ text sends. */
public final class QqOfficialSendTextTool implements AgentTool {

    public static final String NAME = "qq_official_send_text";
    private final OfficialQqSendService service;

    public QqOfficialSendTextTool(OfficialQqSendService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "向指定 QQ 官方账号与 raw source 主动发送文本；返回逐段 "
                + "SENT/FAILED_BEFORE_SEND/UNKNOWN，UNKNOWN 不自动重发；超长文本按全局 "
                + "reject/split 策略处理。";
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
                                "description", "qq_official_group/<account>/<group_openid> 或 "
                                        + "qq_official_private/<account>/<user_openid>"),
                        "text", Map.of("type", "string", "description", "要发送的文本")),
                "required", List.of("accountId", "source", "text"),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            String accountId = stringArgument(context, "accountId");
            SourceAddress source = source(context);
            String text = stringArgument(context, "text");
            List<OfficialQqSendOutcome> outcomes = service.sendText(accountId, source, text);
            return QqOfficialSendToolResults.result(outcomes);
        } catch (OfficialQqSendException failure) {
            return ToolResult.error(failure.code(), failure.safeMessage());
        } catch (IllegalArgumentException failure) {
            return ToolResult.error("INVALID_ARGUMENT", failure.getMessage());
        } catch (RuntimeException failure) {
            return ToolResult.error("INTERNAL_ERROR", "官方发送发生内部错误");
        }
    }

    static String stringArgument(ToolContext context, String name) {
        Object raw = context.arguments().get(name);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    static SourceAddress source(ToolContext context) {
        String raw = stringArgument(context, "source");
        try {
            return SourceAddress.parse(raw);
        } catch (IllegalArgumentException invalid) {
            // Never echo the raw source: SourceAddress validation text may include it.
            throw new IllegalArgumentException("source 格式无效");
        }
    }
}
