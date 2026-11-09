package io.mosire.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.account.AccountId;
import io.mosire.bma3.core.account.ConsumerId;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.util.List;
import java.util.Map;

final class QqToolSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    private QqToolSupport() {
    }

    static List<AgentTool> all(QqToolContext context) {
        return List.of(
                new QqAccountListTool(context),
                new QqConversationReadTool(context),
                new QqConversationSendTool(context),
                new QqConversationSendImageTool(context),
                new QqConversationAckTool(context));
    }

    static SourceAddress source(ToolContext context) {
        Object raw = context.arguments().get("source");
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("source 不能为空");
        }
        return SourceAddress.parse(value);
    }

    static String text(ToolContext context, String name) {
        Object raw = context.arguments().get(name);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    static int integer(ToolContext context, String name, int fallback) {
        Object raw = context.arguments().get(name);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String value && !value.isBlank()) {
            return Integer.parseInt(value);
        }
        return fallback;
    }

    static long longValue(ToolContext context, String name) {
        Object raw = context.arguments().get(name);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String value && !value.isBlank()) {
            return Long.parseLong(value);
        }
        throw new IllegalArgumentException(name + " 不能为空");
    }

    static OneBot11Client client(QqToolContext context, SourceAddress source) {
        return context.accounts().client(new AccountId(source.accountId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "ACCOUNT_NOT_FOUND:" + source.accountId()));
    }

    static ConsumerId consumer(ToolContext context) {
        return new ConsumerId(context.identity().instanceId());
    }

    static ToolResult invalid(IllegalArgumentException failure) {
        String message = failure.getMessage() == null ? "参数错误" : failure.getMessage();
        if (message.startsWith("ACCOUNT_NOT_FOUND:")) {
            return ToolResult.error("ACCOUNT_NOT_FOUND", message);
        }
        return ToolResult.error("INVALID_ARGUMENT", message);
    }

    static ObjectNode object() {
        return JSON.createObjectNode();
    }
}
