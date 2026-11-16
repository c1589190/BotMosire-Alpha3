package io.mosire.bma3.watchdog.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.watchdog.WatchdogEngine;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Watchdog MCP face: status, run-probes, manual remediation and the content-free diagnostics
 * bundle.
 *
 * <p>Names start with {@code bma3_} so the DSH preset's {@code bma3_core} group includes them
 * automatically; no extra preset row is needed.</p>
 */
public final class WatchdogTools {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private WatchdogTools() {
    }

    public static List<AgentTool> all(WatchdogEngine engine) {
        return all(engine, null);
    }

    public static List<AgentTool> all(WatchdogEngine engine,
                                      Supplier<Map<String, Object>> diagnostics) {
        return List.of(new StatusTool(engine), new ProbeTool(engine), new RemediateTool(engine),
                new DiagnoseTool(engine, diagnostics));
    }

    private static ToolResult json(Object value) {
        try {
            return ToolResult.ok(MAPPER.writeValueAsString(value));
        } catch (Exception failure) {
            return ToolResult.error("SERIALIZATION_FAILED", "无法序列化结果");
        }
    }

    private static final class StatusTool implements AgentTool {

        private final WatchdogEngine engine;

        private StatusTool(WatchdogEngine engine) {
            this.engine = engine;
        }

        @Override
        public String name() {
            return "bma3_watchdog_status";
        }

        @Override
        public String description() {
            return "查看 BMA3 watchdog：各组件当前健康（process/disk/db/inboxHttp/inboxBacklog/mcp/"
                    + "qqAccount/qqZombie/dshPlugin/webconnect/media/xvfb/schedule/config）、ready 判定、最近事件、"
                    + "探针/恢复/抑制/报警计数。只读，不返回消息正文或凭据。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public ToolResult execute(ToolContext context) {
            return json(engine.snapshot());
        }
    }

    private static final class ProbeTool implements AgentTool {

        private final WatchdogEngine engine;

        private ProbeTool(WatchdogEngine engine) {
            this.engine = engine;
        }

        @Override
        public String name() {
            return "bma3_watchdog_probe";
        }

        @Override
        public String description() {
            return "立即运行健康探针（可选 component 只跑一个），返回每个组件的 health/code/detail/facts，"
                    + "并把结果写进 watchdog 状态。只读，不触发恢复动作。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "component", Map.of("type", "string",
                            "description", "可选：process/disk/db/inboxHttp/mcp/qqAccount/…")));
        }

        @Override
        public ToolResult execute(ToolContext context) {
            Object raw = context.arguments().get("component");
            String component = raw instanceof String text ? text.trim() : "";
            return json(engine.runProbeNow(component));
        }
    }

    private static final class DiagnoseTool implements AgentTool {

        private final WatchdogEngine engine;
        private final Supplier<Map<String, Object>> diagnostics;

        private DiagnoseTool(WatchdogEngine engine, Supplier<Map<String, Object>> diagnostics) {
            this.engine = engine;
            this.diagnostics = diagnostics;
        }

        @Override
        public String name() {
            return "bma3_watchdog_diagnose";
        }

        @Override
        public String description() {
            return "一屏只读诊断包：watchdog snapshot + Inbox/媒体/文件大小等无正文诊断。"
                    + "不执行命令、不触发恢复、不返回正文/token/凭据/绝对路径。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public ToolResult execute(ToolContext context) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("watchdog", engine.snapshot());
            if (diagnostics != null) {
                body.put("bundle", diagnostics.get());
            }
            return json(body);
        }
    }

    private static final class RemediateTool implements AgentTool {

        private final WatchdogEngine engine;

        private RemediateTool(WatchdogEngine engine) {
            this.engine = engine;
        }

        @Override
        public String name() {
            return "bma3_watchdog_remediate";
        }

        @Override
        public String description() {
            return "手动触发某个组件的恢复动作（如 qqAccount 重连、inboxHttp 重启、mcp 重启、"
                    + "dshPlugin 恢复绑定）。仍受冷却限制；返回 changed/outcome。写操作。";
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, true, false);
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "component", Map.of("type", "string")),
                    "required", List.of("component"));
        }

        @Override
        public ToolResult execute(ToolContext context) {
            Object raw = context.arguments().get("component");
            if (!(raw instanceof String component) || component.isBlank()) {
                return ToolResult.error("INVALID_ARGUMENT", "component 不能为空");
            }
            boolean changed = engine.remediateNow(component.trim());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("component", component.trim());
            body.put("changed", changed);
            return json(body);
        }
    }
}
