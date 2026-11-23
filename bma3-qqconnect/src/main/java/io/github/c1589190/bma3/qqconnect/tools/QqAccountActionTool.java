package io.github.c1589190.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import java.util.Map;

/** Shared read-only account action. */
final class QqAccountActionTool {

    private QqAccountActionTool() {
    }

    static ToolResult execute(QqToolContext context, ToolContext toolContext,
                              String action, Map<String, Object> params) {
        try {
            String accountId = toolContext.arguments().get("accountId") instanceof String value
                    ? value : "";
            JsonNode response = QqToolSupport.client(context, accountId)
                    .sendAction(action, params).join();
            var root = QqToolSupport.object();
            root.set("response", response);
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
