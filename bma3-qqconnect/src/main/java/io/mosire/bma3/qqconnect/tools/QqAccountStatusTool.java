package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Map;

/** Read-only OneBot account status. */
public final class QqAccountStatusTool implements AgentTool {

    private final QqToolContext context;

    public QqAccountStatusTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_account_status";
    }

    @Override
    public String description() {
        return "读取 Bot 账号的 OneBot 在线状态（get_status）；只读。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "accountId", Map.of("type", "string")));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        return QqAccountActionTool.execute(context, toolContext, "get_status", Map.of());
    }
}
