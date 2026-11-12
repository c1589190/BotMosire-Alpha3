package io.mosire.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Accepts a group invitation and adds the bot to the group.
 *
 * <p>QQ has no "join by group number" action: the realistic paths are a pending notification or
 * the invite card's {@code msgseq}. The standard action is {@code set_group_add_request}; when the
 * invitation was already consumed (or never appeared in the notification list), the LLBot kernel
 * fallback {@code llonebot_debug → handleGroupRequest} is tried. Unsupported providers return
 * {@code UNSUPPORTED} rather than pretending success.</p>
 */
public final class QqGroupInviteAcceptTool implements AgentTool {

    private static final int EVENT_TYPE_INVITE = 2;
    private static final int OPERATION_ACCEPT = 2;

    private final QqToolContext context;

    public QqGroupInviteAcceptTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_group_invite_accept";
    }

    @Override
    public String description() {
        return "接受一条群邀请并把 Bot 加进群。groupId 必填；sequence 可省略（按群号在 pending 邀请里找，"
                + "或从 qq_card_view 的 groupInvite.sequence 取）。走 set_group_add_request，失败再试 "
                + "llonebot_debug 内核回退；provider 不支持返回 UNSUPPORTED，UNKNOWN 不自动重试。";
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "accountId", Map.of("type", "string"),
                "groupId", Map.of("type", "string"),
                "sequence", Map.of("type", "string",
                        "description", "邀请卡片的 msgseq / 通知 request_id；省略则自动查找"),
                "eventType", Map.of("type", "integer",
                        "description", "QQ 通知类型，群邀请默认 2")),
                "required", List.of("groupId"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String accountId = toolContext.arguments().get("accountId") instanceof String value
                    ? value : "";
            String groupId = QqToolSupport.text(toolContext, "groupId").trim();
            String sequence = toolContext.arguments().get("sequence") instanceof String value
                    ? value.trim() : "";
            int eventType = toolContext.arguments().get("eventType") instanceof Number number
                    ? number.intValue() : EVENT_TYPE_INVITE;
            OneBot11Client client = QqToolSupport.client(context, accountId);
            if (sequence.isBlank()) {
                sequence = findSequence(client, groupId);
            }
            if (sequence.isBlank()) {
                return ToolResult.error("INVITE_NOT_FOUND",
                        "没有找到群 " + groupId + " 的待处理邀请；需要 sequence 才能接受");
            }
            String via = "set_group_add_request";
            String state = sendStandard(client, sequence);
            if (!"SENT".equals(state)) {
                String kernel = sendKernel(client, sequence, eventType, groupId);
                if ("SENT".equals(kernel)) {
                    state = "SENT";
                    via = "llonebot_debug";
                } else if ("UNSUPPORTED".equals(kernel)) {
                    state = "UNSUPPORTED";
                    via = "none";
                } else if ("UNKNOWN".equals(state) || "UNKNOWN".equals(kernel)) {
                    state = "UNKNOWN";
                    via = "set_group_add_request+llonebot_debug";
                }
            }
            var root = QqToolSupport.object();
            root.put("state", state);
            root.put("via", via);
            root.put("groupId", groupId);
            root.put("sequence", sequence);
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }

    private String findSequence(OneBot11Client client, String groupId) {
        try {
            JsonNode response = client.sendAction("get_group_system_msg", Map.of())
                    .orTimeout(8, TimeUnit.SECONDS)
                    .join();
            JsonNode invites = response.path("data").path("invited_requests");
            if (!invites.isArray()) {
                return "";
            }
            for (JsonNode invite : invites) {
                if (groupId.equals(invite.path("group_id").asText(""))) {
                    return invite.path("request_id").asText("");
                }
            }
            return "";
        } catch (RuntimeException failure) {
            return "";
        }
    }

    private String sendStandard(OneBot11Client client, String sequence) {
        try {
            JsonNode response = client.sendAction("set_group_add_request",
                            Map.of("flag", sequence, "approve", true))
                    .orTimeout(8, TimeUnit.SECONDS)
                    .join();
            boolean ok = "ok".equals(response.path("status").asText())
                    && response.path("retcode").asInt(-1) == 0;
            return ok ? "SENT" : "FAILED_BEFORE_SEND";
        } catch (RuntimeException failure) {
            return "UNKNOWN";
        }
    }

    private String sendKernel(OneBot11Client client, String sequence, int eventType,
                              String groupId) {
        long seq;
        long group;
        try {
            seq = Long.parseLong(sequence);
            group = Long.parseLong(groupId);
        } catch (NumberFormatException invalid) {
            return "UNSUPPORTED";
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("apiClass", "qqProtocol");
        params.put("method", "handleGroupRequest");
        params.put("args", List.of(seq, eventType, group, OPERATION_ACCEPT, "", false));
        try {
            JsonNode response = client.sendAction("llonebot_debug", params)
                    .orTimeout(8, TimeUnit.SECONDS)
                    .join();
            JsonNode data = response.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return "UNSUPPORTED";
            }
            long errorCode = data.path("errorCode").asLong(-1L);
            return errorCode == 0L ? "SENT" : "FAILED_BEFORE_SEND";
        } catch (RuntimeException failure) {
            return "UNKNOWN";
        }
    }
}
