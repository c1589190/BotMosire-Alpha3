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
                new QqAccountStatusTool(context),
                new QqAccountLoginInfoTool(context),
                new QqContactListTool(context, "qq_group_list", "get_group_list",
                        "列出 Bot 账号已加入的 QQ 群（OneBot get_group_list）；只读。"),
                new QqContactListTool(context, "qq_friend_list", "get_friend_list",
                        "列出 Bot 账号的 QQ 好友（OneBot get_friend_list）；只读。"),
                new QqThreadListTool(context),
                new QqConversationHistoryTool(context),
                new QqRecentMessagesTool(context),
                new QqMessageGetTool(context),
                new QqConversationReadTool(context),
                new QqConversationSendTool(context),
                new QqConversationSendImageTool(context),
                new QqReplyTool(context),
                new QqPrivateSendTool(context),
                new QqUserNoteGetTool(context),
                new QqUserNoteListTool(context),
                new QqUserNoteUpsertTool(context),
                new QqSourceWeightGetTool(context),
                new QqSourceWeightListTool(context),
                new QqSourceWeightSetTool(context),
                new QqConversationAckTool(context));
    }

    static SourceAddress source(ToolContext context) {
        Object raw = context.arguments().get("source");
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("source 不能为空");
        }
        return SourceAddress.parse(value);
    }

    static QqExtrasStore extras(QqToolContext context) {
        if (context.extras() == null) {
            throw new IllegalArgumentException("QQ_EXTRA_STORE_UNAVAILABLE");
        }
        return context.extras();
    }

    static String accountId(QqToolContext context, ToolContext toolContext) {
        Object raw = toolContext.arguments().get("accountId");
        if (raw instanceof String value && !value.isBlank()) {
            return value;
        }
        var accounts = context.accounts().accounts();
        if (accounts.size() != 1) {
            throw new IllegalArgumentException("accountId 不能为空（当前账号数 " + accounts.size() + "）");
        }
        return accounts.getFirst().value();
    }

    static String noteJson(QqExtrasStore.UserNote note) {
        return noteNode(note).toString();
    }

    static ObjectNode noteNode(QqExtrasStore.UserNote note) {
        ObjectNode node = object();
        node.put("accountId", note.accountId());
        node.put("userId", note.userId());
        node.put("displayName", note.displayName());
        var tags = node.putArray("tags");
        note.tags().forEach(tags::add);
        node.put("note", note.note());
        node.put("priority", note.priority());
        node.put("revision", note.revision());
        return node;
    }

    static String weightJson(QqExtrasStore.SourceWeight weight) {
        return weightNode(weight).toString();
    }

    static ObjectNode weightNode(QqExtrasStore.SourceWeight weight) {
        ObjectNode node = object();
        node.put("source", weight.source());
        node.put("sourceWeight", weight.weight());
        node.put("notifyThreshold", weight.notifyThreshold());
        node.put("revision", weight.revision());
        return node;
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

    static OneBot11Client client(QqToolContext context, String accountId) {
        if (accountId == null || accountId.isBlank()) {
            var accounts = context.accounts().accounts();
            if (accounts.size() != 1) {
                throw new IllegalArgumentException("accountId 不能为空（当前账号数 " + accounts.size() + "）");
            }
            accountId = accounts.getFirst().value();
        }
        String resolved = accountId;
        return context.accounts().client(new AccountId(resolved))
                .orElseThrow(() -> new IllegalArgumentException("ACCOUNT_NOT_FOUND:" + resolved));
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
        if (message.startsWith("TEXT_TOO_LONG:")) {
            return ToolResult.error("TEXT_TOO_LONG", message);
        }
        return ToolResult.error("INVALID_ARGUMENT", message);
    }

    static String messageText(io.mosire.bma3.core.message.Message message) {
        StringBuilder text = new StringBuilder();
        message.inbound().segments().forEach(segment -> {
            if (segment.text() != null && !segment.text().isBlank()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(segment.text());
            }
        });
        return text.toString();
    }

    static String shorten(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    static ObjectNode object() {
        return JSON.createObjectNode();
    }
}
