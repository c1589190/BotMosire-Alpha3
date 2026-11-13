package io.mosire.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code qq_group_info}：实时查一个群（OneBot {@code get_group_info}）。
 *
 * <p>存在的理由（P1-2/P2-7）：{@code qq_group_list} 是"拉一次 + 缓存"，新加入的群可能不在里面；
 * 而历史读不到时，Agent 需要能回答"我到底在不在这个群、群叫什么、有多少人"，否则拿到 0 条历史
 * 无从判断是权限、禁言还是真的没消息。
 *
 * <p>成功即写一遍本地目录缓存（与 {@code qq_group_list} 同一份），让来源标签/搜索也能跟上。
 * <p>只读。
 */
public final class QqGroupInfoTool implements AgentTool {

    private final QqToolContext context;

    public QqGroupInfoTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_group_info";
    }

    @Override
    public String description() {
        return "实时查一个群的详情（OneBot get_group_info）：群名、人数、上限。"
                + "用于确认机器人是否真的在某个群里、群是否改名；成功会顺手刷新本地群缓存。只读。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("accountId", Map.of("type", "string"));
        props.put("groupId", Map.of("type", "integer", "description", "群号"));
        return Map.of("type", "object", "properties", props,
                "required", List.of("groupId"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String accountId = toolContext.arguments().get("accountId") instanceof String value
                    ? value : "";
            long groupId = QqToolSupport.longValue(toolContext, "groupId");
            if (groupId <= 0) {
                throw new IllegalArgumentException("groupId 必须是正数");
            }
            JsonNode response = QqToolSupport.client(context, accountId)
                    .sendAction("get_group_info", Map.of("group_id", groupId))
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();
            int retcode = response.path("retcode").asInt(-1);
            JsonNode data = response.path("data");
            var root = QqToolSupport.object();
            root.put("groupId", groupId);
            root.put("retcode", retcode);
            root.put("ok", retcode == 0 && data.isObject());
            if (data.isObject()) {
                root.put("groupName", data.path("group_name").asText(""));
                root.put("memberCount", data.path("member_count").asInt(0));
                root.put("maxMemberCount", data.path("max_member_count").asInt(0));
                remember(accountId, groupId, data);
            } else {
                String providerMessage = response.path("message").asText("");
                root.put("hint", retcode == 0
                        ? "provider 返回了空数据；可能机器人不在该群，或 provider 不支持这个动作"
                        : "provider retcode=" + retcode
                        + (providerMessage.isBlank() ? "" : "：" + providerMessage)
                        + "（常见：不在群/权限不足）");
            }
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        } catch (RuntimeException failure) {
            return ToolResult.error("GROUP_INFO_FAILED",
                    "拿不到群信息（超时或连接失败）：" + failure.getClass().getSimpleName());
        }
    }

    private void remember(String accountId, long groupId, JsonNode data) {
        if (context.directory() == null) {
            return;
        }
        try {
            String resolved = accountId.isBlank()
                    ? context.accounts().accounts().getFirst().value() : accountId;
            context.directory().upsertGroup(resolved, Long.toString(groupId),
                    data.path("group_name").asText(""),
                    data.path("member_count").asInt(0));
        } catch (RuntimeException ignored) {
            // 缓存刷新失败不影响查询结果
        }
    }
}
