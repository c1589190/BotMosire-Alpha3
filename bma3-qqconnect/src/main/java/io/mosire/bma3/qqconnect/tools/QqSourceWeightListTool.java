package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Map;

/** Lists per-source weight policies. */
public final class QqSourceWeightListTool implements AgentTool {

    private final QqToolContext context;

    public QqSourceWeightListTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_source_weight_list";
    }

    @Override
    public String description() {
        return "列出所有 QQ source 的权重策略。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            var root = QqToolSupport.object();
            var array = root.putArray("weights");
            for (var weight : QqToolSupport.extras(context).weights()) {
                array.add(QqToolSupport.weightNode(weight));
            }
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
