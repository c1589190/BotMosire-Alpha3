package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Map;

/** Read-only OneBot group/friend directory. */
public final class QqContactListTool implements AgentTool {

    private final QqToolContext context;
    private final String name;
    private final String action;
    private final String description;

    public QqContactListTool(QqToolContext context, String name, String action, String description) {
        this.context = context;
        this.name = name;
        this.action = action;
        this.description = description;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "accountId", Map.of("type", "string")));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        ToolResult result = QqAccountActionTool.execute(context, toolContext, action, Map.of());
        // ★ 这里必须看 **provider 的** status/retcode，不能看 ToolResult.success()：
        //   QqAccountActionTool 只要拿到响应就返回 ok，provider 失败也照样是 ok。
        boolean providerOk = providerOk(result);
        if (providerOk && "get_group_list".equals(action) && context.directory() != null) {
            rememberGroups(toolContext, result);
        }
        if (!providerOk && "get_group_list".equals(action)) {
            // P2-7：实时拉取失败时退回本地目录缓存 —— 缓存里的群至少能让 Agent 不至于"一个群都看不见"，
            // 但必须如实标注 source=cache，不能装作是实时结果。
            return cachedGroups(toolContext, result);
        }
        return result;
    }

    /** 解析 {@code {"response":{"status":..,"retcode":..}}}，判定 provider 是否真的成功。 */
    private static boolean providerOk(ToolResult result) {
        if (!result.success() || result.message() == null || result.message().isBlank()) {
            return false;
        }
        try {
            var response = QqToolSupport.JSON.readTree(result.message()).path("response");
            return "ok".equals(response.path("status").asText())
                    && response.path("retcode").asInt(-1) == 0;
        } catch (RuntimeException | java.io.IOException failure) {
            return false;
        }
    }

    private ToolResult cachedGroups(ToolContext toolContext, ToolResult liveFailure) {
        if (context.directory() == null) {
            return liveFailure;
        }
        try {
            String accountId = QqToolSupport.accountId(context, toolContext);
            var groups = context.directory().groups(accountId, 500);
            if (groups.isEmpty()) {
                return liveFailure;
            }
            var root = QqToolSupport.object();
            root.put("source", "cache");
            root.put("liveError", liveFailure.code());
            try {
                var response = QqToolSupport.JSON.readTree(liveFailure.message()).path("response");
                root.put("liveRetcode", response.path("retcode").asInt(-1));
            } catch (RuntimeException | java.io.IOException ignored) {
                // 解析不了就不带，别让回退本身失败
            }
            root.put("hint", "实时 get_group_list 失败，以下是本地目录缓存（可能滞后）；"
                    + "要确认单个群用 qq_group_info");
            var array = root.putArray("groups");
            for (var group : groups) {
                var row = array.addObject();
                row.put("group_id", group.groupId());
                row.put("group_name", group.name());
                row.put("member_count", group.memberCount());
                row.put("updated_at", group.updatedAt() == null ? "" : group.updatedAt().toString());
            }
            root.put("count", groups.size());
            return ToolResult.ok(root.toString());
        } catch (RuntimeException failure) {
            return liveFailure;
        }
    }

    /**
     * Writes group names into the local directory so the inbox can label a group by name and
     * {@code qq_search} can resolve a group name without another API call.
     */
    private void rememberGroups(ToolContext toolContext, ToolResult result) {
        try {
            String accountId = QqToolSupport.accountId(context, toolContext);
            var data = QqToolSupport.JSON.readTree(result.message())
                    .path("response").path("data");
            if (!data.isArray()) {
                return;
            }
            for (var group : data) {
                String groupId = group.path("group_id").asText("");
                if (groupId.isBlank()) {
                    continue;
                }
                context.directory().upsertGroup(accountId, groupId,
                        group.path("group_name").asText(""),
                        group.path("member_count").asInt(0));
            }
        } catch (RuntimeException ignored) {
            // Directory caching is best-effort; the tool result is still valid.
        } catch (Exception ignored) {
            // Same: malformed JSON must not fail a read-only listing.
        }
    }
}
