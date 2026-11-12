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
 * Approves or rejects one group system request via {@code set_group_add_request}.
 *
 * <p>Three-state result: {@code SENT} only when the provider said ok; a transport failure is
 * {@code UNKNOWN} and is never retried automatically. No raw flag or ticket is returned.</p>
 */
public final class QqGroupRequestDecideTool implements AgentTool {

    private final QqToolContext context;

    public QqGroupRequestDecideTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_group_request_decide";
    }

    @Override
    public String description() {
        return "处理一条群系统请求：approve=true 同意（群邀请或入群申请），false 拒绝。"
                + "requestId 来自 qq_group_request_list；结果不确定（UNKNOWN）不自动重试。";
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "accountId", Map.of("type", "string"),
                "requestId", Map.of("type", "string"),
                "approve", Map.of("type", "boolean"),
                "reason", Map.of("type", "string")),
                "required", List.of("requestId", "approve"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String requestId = QqToolSupport.text(toolContext, "requestId");
            boolean approve = Boolean.TRUE.equals(toolContext.arguments().get("approve"));
            String reason = toolContext.arguments().get("reason") instanceof String value
                    ? value : "";
            String accountId = toolContext.arguments().get("accountId") instanceof String value
                    ? value : "";
            OneBot11Client client = QqToolSupport.client(context, accountId);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("flag", requestId);
            params.put("approve", approve);
            if (!reason.isBlank()) {
                params.put("reason", reason);
            }
            String state;
            String detail = "";
            try {
                JsonNode response = client.sendAction("set_group_add_request", params)
                        .orTimeout(8, TimeUnit.SECONDS)
                        .join();
                boolean ok = "ok".equals(response.path("status").asText())
                        && response.path("retcode").asInt(-1) == 0;
                state = ok ? "SENT" : "FAILED_BEFORE_SEND";
                detail = ok ? "" : response.path("wording").asText("");
            } catch (RuntimeException failure) {
                state = "UNKNOWN";
            }
            var root = QqToolSupport.object();
            root.put("state", state);
            root.put("requestId", requestId);
            root.put("approve", approve);
            if (!detail.isBlank()) {
                root.put("detail", detail);
            }
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
