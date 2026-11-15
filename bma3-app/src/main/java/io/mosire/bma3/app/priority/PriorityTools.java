package io.mosire.bma3.app.priority;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.app.config.AppConfig;
import io.mosire.bma3.core.priority.PriorityAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only priority face: "why did (or didn't) this message wake me?".
 *
 * <p>It calls the same {@link PriorityResolver} the ingest path uses, so the answer is the real
 * arithmetic rather than a second implementation that could drift.</p>
 */
public final class PriorityTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PriorityTools() {
    }

    public static List<AgentTool> all(PriorityResolver resolver) {
        return List.of(new ExplainTool(resolver));
    }

    private static final class ExplainTool implements AgentTool {

        private final PriorityResolver resolver;

        private ExplainTool(PriorityResolver resolver) {
            this.resolver = resolver;
        }

        @Override
        public String name() {
            return "bma3_priority_explain";
        }

        @Override
        public String description() {
            return "解释一条消息的优先级算术：给定正文与来源键，返回最终分数、是否丢弃/立即推送，"
                    + "以及命中的来源规则、属性、正文规则链。与 ingest 使用同一个 resolver，"
                    + "用来回答『这条为什么没唤醒我』。只读，不改配置。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("text", Map.of("type", "string", "description", "消息正文"));
            properties.put("source", Map.of("type", "string",
                    "description", "来源键，例如 qq_private/bot-1/3924945183 或 qq_group/bot-1/955432692"));
            properties.put("senderId", Map.of("type", "string", "description", "发送者 QQ 号（可选）"));
            properties.put("senderName", Map.of("type", "string", "description", "发送者昵称（可选）"));
            properties.put("attributes", Map.of("type", "array",
                    "description", "已观察到的属性键（可选），例如 [\"qq_at_bot\",\"qq_command\"]",
                    "items", Map.of("type", "string")));
            return Map.of("type", "object", "properties", properties,
                    "required", List.of("source"));
        }

        @Override
        public ToolResult execute(ToolContext context) {
            Map<String, Object> args = context.arguments();
            String source = text(args.get("source"));
            if (source.isBlank()) {
                return ToolResult.error("INVALID_ARGUMENT", "source 不能为空");
            }
            String text = text(args.get("text"));
            String senderId = text(args.get("senderId"));
            String senderName = text(args.get("senderName"));
            PriorityAttributes attributes = attributes(args.get("attributes"));

            PriorityDecision decision = resolver.resolve(source, senderId, senderName, attributes,
                    text);
            AppConfig.Weighting weighting = resolver.weighting();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", source);
            result.put("textPreview", text.length() <= 200 ? text : text.substring(0, 200) + "…");
            result.put("priority", decision.priority());
            result.put("dropped", decision.dropped());
            result.put("immediate", decision.immediate());
            result.put("reasons", decision.reasons());
            result.put("thresholds", Map.of(
                    "immediateThreshold", weighting.immediateThreshold(),
                    "dropBelow", weighting.dropBelow(),
                    "maxPriority", weighting.maxPriority()));
            result.put("commandPrefixes", weighting.commandPrefixes());
            result.put("rules", weighting.rules());
            try {
                return ToolResult.ok(MAPPER.writeValueAsString(result));
            } catch (Exception failure) {
                return ToolResult.error("SERIALIZATION_FAILED", "无法序列化结果");
            }
        }

        private static String text(Object value) {
            return value instanceof String raw ? raw : "";
        }

        @SuppressWarnings("unchecked")
        private static PriorityAttributes attributes(Object value) {
            if (!(value instanceof List<?> list)) {
                return PriorityAttributes.none();
            }
            List<String> keys = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof String key && !key.isBlank()) {
                    keys.add(key);
                }
            }
            return new PriorityAttributes(new java.util.LinkedHashSet<>(keys));
        }
    }
}
