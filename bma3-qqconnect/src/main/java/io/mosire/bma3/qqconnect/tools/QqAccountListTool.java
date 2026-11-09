package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.List;
import java.util.Map;

/** Lists configured QQ accounts and whether they currently have a connection. */
public final class QqAccountListTool implements AgentTool {

    private final QqToolContext context;

    public QqAccountListTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_account_list";
    }

    @Override
    public String description() {
        return "列出已配置的 QQ 账号及连接状态。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public ToolResult execute(ToolContext ignored) {
        var root = QqToolSupport.object();
        var accounts = root.putArray("accounts");
        for (var accountId : context.accounts().accounts()) {
            var item = accounts.addObject();
            item.put("accountId", accountId.value());
            item.put("connected", context.accounts().client(accountId).isPresent());
        }
        return ToolResult.ok(root.toString());
    }
}
