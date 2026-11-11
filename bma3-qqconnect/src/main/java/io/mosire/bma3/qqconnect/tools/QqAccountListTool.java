package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Map;

/**
 * Lists configured QQ accounts with a truthful connection state and the last failure.
 *
 * <p>{@code connected} is the socket state, not "is there a registry entry": a dropped connection
 * used to keep reporting {@code true}. {@code lastError.hint} explains what to fix (wrong port,
 * HTTP-vs-WS endpoint, bad token, unreachable host).</p>
 */
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
        return "列出已配置的 QQ 账号：connected（真实 socket 状态）、lastError{phase,httpStatus,hint,at}。"
                + "连不上时先看 lastError.hint。";
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
            item.put("connected", context.accounts().connected(accountId.value()));
            var fault = context.accounts().fault(accountId.value());
            if (fault.isPresent()) {
                var error = item.putObject("lastError");
                error.put("phase", fault.get().phase());
                error.put("httpStatus", fault.get().httpStatus());
                error.put("hint", fault.get().hint());
                error.put("at", fault.get().at().toString());
            }
        }
        context.accounts().selfIdentitySummary().forEach((accountId, identity) -> {
            for (var node : accounts) {
                if (accountId.equals(node.path("accountId").asText())
                        && node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                    var self = object.putObject("self");
                    self.put("userId", identity.userId());
                    self.put("nickname", identity.nickname());
                }
            }
        });
        return ToolResult.ok(root.toString());
    }
}
