package io.mosire.bma3.app.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Map;

/** Read-only view of the configuration that is actually in effect right now. */
public final class GetConfigTool implements AgentTool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConfigToolContext context;

    public GetConfigTool(ConfigToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "get_config";
    }

    @Override
    public String description() {
        return "读取当前生效的配置（weighting 规则与加分、inbox.display、qqconnect.send 上限、账号、端口、日志级别）。"
                + "只返回非敏感值；token 只以环境变量名出现。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        return ToolResult.ok(ConfigView.effective(context.effective().get()).toString());
    }
}
