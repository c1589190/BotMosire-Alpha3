package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.List;
import java.util.Map;

/** Upserts a per-source weight policy. */
public final class QqSourceWeightSetTool implements AgentTool {

    private final QqToolContext context;

    public QqSourceWeightSetTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_source_weight_set";
    }

    @Override
    public String description() {
        return "设置某个 QQ source 的权重和阈值；后续入站消息用它决定 Inbox priority。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "source", Map.of("type", "string"),
                "weight", Map.of("type", "integer", "minimum", -1000, "maximum", 1000),
                "notifyThreshold", Map.of("type", "integer", "minimum", -1000, "maximum", 1000),
                "expectedRevision", Map.of("type", "integer", "minimum", 0)),
                "required", List.of("source", "weight"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String source = QqToolSupport.text(toolContext, "source");
            int weight = QqToolSupport.integer(toolContext, "weight", 0);
            int threshold = QqToolSupport.integer(toolContext, "notifyThreshold", 200);
            Integer expected = toolContext.arguments().get("expectedRevision") instanceof Number n
                    ? n.intValue() : null;
            var saved = QqToolSupport.extras(context).upsertWeight(source, weight, threshold, expected);
            return ToolResult.ok(QqToolSupport.weightJson(saved));
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
