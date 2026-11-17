package io.mosire.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Lists pending group invitations / join requests from {@code get_group_system_msg}. */
public final class QqGroupRequestListTool implements AgentTool {

    private final QqToolContext context;

    public QqGroupRequestListTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_group_request_list";
    }

    @Override
    public String description() {
        return "列出待处理/最近的群系统请求：requestId/kind/groupId/groupName/邀请人/申请理由/handled。"
                + "兼容分类对象和扁平数组；未知类型标 UNKNOWN。不同 provider 的 flag 语义不同，"
                + "decisionSupported=false 的条目只用于读取。不返回 raw flag。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "accountId", Map.of("type", "string"),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100)));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String accountId = toolContext.arguments().get("accountId") instanceof String value
                    ? value : "";
            int limit = Math.max(1, Math.min(100,
                    QqToolSupport.integer(toolContext, "limit", 20)));
            OneBot11Client client = QqToolSupport.client(context, accountId);
            JsonNode response = client.sendAction("get_group_system_msg", Map.of())
                    .orTimeout(8, TimeUnit.SECONDS)
                    .join();
            return parseResponse(response, limit);
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        } catch (RuntimeException failure) {
            return ToolResult.error("GROUP_REQUEST_LIST_FAILED",
                    failure.getClass().getSimpleName());
        }
    }

    /** Normalizes known provider response shapes without exposing their private request flags. */
    static ToolResult parseResponse(JsonNode response, int limit) {
        if (response == null || response.path("retcode").asInt(-1) != 0
                || !"ok".equalsIgnoreCase(response.path("status").asText(""))) {
            return ToolResult.error("GROUP_REQUEST_LIST_FAILED", "provider 未成功返回群系统请求");
        }
        JsonNode data = response.path("data");
        var root = QqToolSupport.object();
        var rows = root.putArray("requests");
        int count;
        if (data.isArray()) {
            if (!objectRows(data)) {
                return ToolResult.error("GROUP_REQUEST_LIST_FAILED", "provider 群请求条目格式不受支持");
            }
            count = append(rows, data, "", limit);
        } else if (data.isObject()
                && (data.has("invited_requests") || data.has("join_requests"))) {
            JsonNode invited = data.path("invited_requests");
            JsonNode joined = data.path("join_requests");
            if ((!invited.isMissingNode() && !invited.isArray())
                    || (!joined.isMissingNode() && !joined.isArray())
                    || !objectRows(invited) || !objectRows(joined)) {
                return ToolResult.error("GROUP_REQUEST_LIST_FAILED", "provider 群请求列表格式不受支持");
            }
            count = append(rows, invited, "INVITE", limit);
            count += append(rows, joined, "JOIN", limit - count);
        } else if (data.isObject() && data.path("requests").isArray()) {
            if (!objectRows(data.path("requests"))) {
                return ToolResult.error("GROUP_REQUEST_LIST_FAILED", "provider 群请求条目格式不受支持");
            }
            count = append(rows, data.path("requests"), "", limit);
        } else {
            return ToolResult.error("GROUP_REQUEST_LIST_FAILED", "provider 群请求响应格式不受支持");
        }
        root.put("total", count);
        return ToolResult.ok(root.toString());
    }

    private static boolean objectRows(JsonNode items) {
        if (items.isMissingNode()) {
            return true;
        }
        if (!items.isArray()) {
            return false;
        }
        for (JsonNode item : items) {
            if (!item.isObject()) {
                return false;
            }
        }
        return true;
    }

    private static int append(com.fasterxml.jackson.databind.node.ArrayNode rows,
                              JsonNode items, String kind, int remaining) {
        if (!items.isArray() || remaining <= 0) {
            return 0;
        }
        int added = 0;
        for (JsonNode item : items) {
            if (added >= remaining) {
                break;
            }
            var row = rows.addObject();
            row.put("requestId", item.path("request_id").asText(""));
            row.put("kind", kind.isEmpty() ? flatKind(item) : kind);
            if (kind.isEmpty()) {
                // A flat provider may use a separate opaque flag/subtype for write actions.
                // The numeric request_id cannot safely be substituted for that flag.
                row.put("decisionSupported", false);
            }
            row.put("groupId", item.path("group_id").asText(""));
            row.put("groupName", item.path("group_name").asText(""));
            row.put("inviterId", firstText(item, "invitor_uin", "inviter_uin"));
            row.put("inviterName", firstText(item, "invitor_nick", "inviter_nick"));
            row.put("requesterId", firstText(item, "requester_uin", "requester_id"));
            row.put("requesterName", firstText(item, "requester_nick", "requester_name"));
            row.put("message", firstText(item, "message", "comment"));
            row.put("handled", item.path("checked").asBoolean(false));
            added++;
        }
        return added;
    }

    private static String flatKind(JsonNode item) {
        String type = firstText(item, "sub_type", "request_type", "kind");
        if ("invite".equalsIgnoreCase(type) || "invited".equalsIgnoreCase(type)) {
            return "INVITE";
        }
        if ("add".equalsIgnoreCase(type) || "join".equalsIgnoreCase(type)) {
            return "JOIN";
        }
        return "UNKNOWN";
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText("").isBlank()) {
                return value.asText("");
            }
        }
        return "";
    }

    static List<String> kinds() {
        return List.of("INVITE", "JOIN");
    }
}
