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
        return QqAccountActionTool.execute(context, toolContext, action, Map.of());
    }
}
