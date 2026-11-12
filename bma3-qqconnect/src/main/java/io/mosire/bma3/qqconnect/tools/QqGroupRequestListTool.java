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
        return "列出待处理/最近的群系统请求（群邀请 invited_requests + 入群申请 join_requests）："
                + "requestId/kind/groupId/groupName/邀请人/申请理由/handled。用 qq_group_request_decide "
                + "或 qq_group_invite_accept 处理。不返回 raw flag。";
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
            JsonNode data = response.path("data");
            var root = QqToolSupport.object();
            var rows = root.putArray("requests");
            int count = 0;
            count += append(rows, data.path("invited_requests"), "INVITE", limit - count);
            count += append(rows, data.path("join_requests"), "JOIN", limit - count);
            root.put("total", count);
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        } catch (RuntimeException failure) {
            return ToolResult.error("GROUP_REQUEST_LIST_FAILED",
                    failure.getClass().getSimpleName());
        }
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
            row.put("kind", kind);
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
