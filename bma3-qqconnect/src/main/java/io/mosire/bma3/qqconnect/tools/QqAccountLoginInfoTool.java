package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Map;

/** Read-only OneBot login identity. */
public final class QqAccountLoginInfoTool implements AgentTool {

    private final QqToolContext context;

    public QqAccountLoginInfoTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_account_login_info";
    }

    @Override
    public String description() {
        return "读取 Bot 账号昵称和 QQ 号（get_login_info）；只读。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "accountId", Map.of("type", "string")));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        return QqAccountActionTool.execute(context, toolContext, "get_login_info", Map.of());
    }
}
