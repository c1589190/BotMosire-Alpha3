package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.List;
import java.util.Map;

/** Reads one per-source weight policy. */
public final class QqSourceWeightGetTool implements AgentTool {

    private final QqToolContext context;

    public QqSourceWeightGetTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_source_weight_get";
    }

    @Override
    public String description() {
        return "读取某个 QQ source 的权重和阈值策略。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "source", Map.of("type", "string")), "required", List.of("source"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String source = QqToolSupport.text(toolContext, "source");
            return QqToolSupport.extras(context).sourceWeight(source)
                    .map(w -> ToolResult.ok(QqToolSupport.weightJson(w)))
                    .orElseGet(() -> ToolResult.error("WEIGHT_NOT_FOUND", source));
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
