package io.github.c1589190.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.core.account.AccountId;
import io.github.c1589190.bma3.core.account.ConsumerId;
import io.github.c1589190.bma3.core.message.Message;
import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.qqconnect.onebot.OneBot11Client;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QqToolSupport {

    static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TEXT_TOO_LONG = Pattern.compile(
            "^TEXT_TOO_LONG:\\s*actual=(\\d+)\\s+max=(\\d+).*$");
    /**
     * Bounded diagnoses whose text is generated locally and cannot contain caller/provider data.
     * Any other exception text is replaced by the generic INVALID_ARGUMENT message.
     */
    private static final Set<String> SAFE_DIAGNOSTIC_CODES = Set.of(
            "ACCOUNT_NOT_FOUND",
            "TEXT_TOO_LONG",
            "INVALID_FILE_ID",
            "MEDIA_TOO_LARGE",
            "MEDIA_NOT_FOUND",
            "MEDIA_FETCH_FAILED",
            "MEMBER_NOT_FOUND",
            "DIRECTORY_UNAVAILABLE",
            "QQ_EXTRA_STORE_UNAVAILABLE",
            "REVISION_CONFLICT");

    private QqToolSupport() {
    }

    static List<AgentTool> all(QqToolContext context) {
        return List.of(
                new QqAccountListTool(context),
                new QqAccountStatusTool(context),
                new QqContactListTool(context, "qq_group_list", "get_group_list",
                        "列出 Bot 账号已加入的 QQ 群（OneBot get_group_list）；只读。"),
                new QqContactListTool(context, "qq_friend_list", "get_friend_list",
                        "列出 Bot 账号的 QQ 好友（OneBot get_friend_list）；只读。"),
                new QqFriendTool(context),
                new QqProviderActionTool(context),
                new QqThreadListTool(context),
                new QqConversationHistoryTool(context),
                new QqGroupMembersTool(context),
                new QqGroupInfoTool(context),
                new QqGroupMemberInfoTool(context),
                new QqDeleteMsgTool(context),
                new QqSearchTool(context),
                new QqCardViewTool(context),
                new QqForwardViewTool(context),
                new QqGroupRequestListTool(context),
                new QqGroupRequestDecideTool(context),
                new QqGroupInviteAcceptTool(context),
                new QqRemoteHistoryTool(context),
                new QqMessageGetTool(context),
                new QqConversationReadTool(context),
                new QqConversationSendTool(context),
                new QqConversationSendImageTool(context),
                new QqConversationSendFileTool(context),
                new QqMediaViewTool(context),
                new QqReplyTool(context),
                new QqPrivateSendTool(context),
                new QqUserNoteListTool(context),
                new QqUserNoteUpsertTool(context),
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
        node.put("revision", note.revision());
        return node;
    }

    /** One message row as the read tools expose it. */
    static ObjectNode messageNode(Message message, boolean includeSegments) {
        ObjectNode node = object();
        node.put("seq", message.localSeq());
        node.put("messageId", message.inbound().messageId());
        node.put("externalMessageId", message.externalMessageId());
        node.put("at", message.inbound().at().toString());
        node.put("direction", message.inbound().direction() == io.github.c1589190.bma3.core.message.Direction.INBOUND
                ? "IN" : "OUT");
        node.put("self", message.self());
        var sender = node.putObject("sender");
        sender.put("id", message.inbound().senderId());
        sender.put("name", message.inbound().senderName());
        sender.put("card", message.inbound().senderCard());
        sender.put("role", message.inbound().senderRole());
        node.put("text", messageText(message));
        var delivery = message.delivery();
        if (delivery != null) {
            // Outbound-only: inbound rows have no delivery state and must never appear to have one.
            ObjectNode deliveryNode = node.putObject("delivery");
            deliveryNode.put("status", delivery.status().name());
            deliveryNode.put("operationId", delivery.operationId());
            deliveryNode.put("partIndex", delivery.partIndex());
            deliveryNode.put("partTotal", delivery.partTotal());
            if (delivery.errorCode() != null) {
                deliveryNode.put("errorCode", delivery.errorCode());
            }
        }
        if (includeSegments) {
            String lookupId = lookupId(message);
            ArrayNode segments = node.putArray("segments");
            List<Segment> source = message.inbound().segments();
            for (int index = 0; index < source.size(); index++) {
                Segment segment = source.get(index);
                ObjectNode item = segments.addObject();
                item.put("type", segment.type());
                item.put("text", segment.text());
                if (QqMediaSupport.isMedia(segment.type())
                        || "forward".equals(segment.type())
                        || "json".equals(segment.type())
                        || "xml".equals(segment.type())) {
                    item.put("fileId", io.github.c1589190.bma3.core.media.MediaId.of(
                            message.inbound().source().format(), lookupId, index).encode());
                }
                var data = item.putObject("data");
                segment.data().forEach(data::put);
            }
        }
        return node;
    }

    /** The id a media file id must encode: the platform id when known, else the board id. */
    private static String lookupId(Message message) {
        return message.externalMessageId().isBlank()
                ? message.inbound().messageId() : message.externalMessageId();
    }

    static boolean includeSegments(ToolContext context) {
        return context.arguments().get("includeSegments") instanceof Boolean value && value;
    }

    static Map<String, Object> segmentsSchema() {
        return Map.of("type", "boolean",
                "description", "true 时返回结构化 segments（图片 file/url、引用 id、at 目标等）；默认 false。");
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
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(name + " 必须是整数");
            }
        }
        return fallback;
    }

    static long longValue(ToolContext context, String name) {
        Object raw = context.arguments().get(name);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String value && !value.isBlank()) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(name + " 必须是整数");
            }
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
        String code = safeDiagnosticCode(failure);
        if ("ACCOUNT_NOT_FOUND".equals(code)) {
            return ToolResult.error(code, "账号不存在");
        }
        if ("TEXT_TOO_LONG".equals(code)) {
            Matcher matcher = TEXT_TOO_LONG.matcher(failure.getMessage() == null
                    ? "" : failure.getMessage());
            if (matcher.matches()) {
                return ToolResult.error(code,
                        "TEXT_TOO_LONG: actual=" + matcher.group(1)
                                + " max=" + matcher.group(2));
            }
            return ToolResult.error(code, "TEXT_TOO_LONG");
        }
        return SAFE_DIAGNOSTIC_CODES.contains(code) && !"INVALID_ARGUMENT".equals(code)
                ? ToolResult.error("INVALID_ARGUMENT", code)
                : ToolResult.error("INVALID_ARGUMENT", "参数无效");
    }

    /** Returns only a bounded locally-known code; arbitrary exception text is never returned. */
    static String safeDiagnosticCode(IllegalArgumentException failure) {
        String message = failure == null || failure.getMessage() == null
                ? "" : failure.getMessage().strip();
        int colon = message.indexOf(':');
        String candidate = (colon >= 0 ? message.substring(0, colon) : message).strip();
        return candidate.matches("[A-Z][A-Z0-9_]{0,63}")
                && SAFE_DIAGNOSTIC_CODES.contains(candidate)
                ? candidate : "INVALID_ARGUMENT";
    }

    static String messageText(Message message) {
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
