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
        if (result.success() && "get_group_list".equals(action) && context.directory() != null) {
            rememberGroups(toolContext, result);
        }
        return result;
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
