package io.mosire.bma3.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP face of the scheduler: create/list/get/cancel/enable. No schedule body is logged. */
public final class ScheduleTools {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ScheduleTools() {
    }

    public static List<AgentTool> all(ScheduleService service) {
        return List.of(new CreateTool(service), new ListTool(service), new GetTool(service),
                new CancelTool(service), new SetEnabledTool(service));
    }

    private static ToolResult json(Object value) {
        try {
            return ToolResult.ok(MAPPER.writeValueAsString(value));
        } catch (Exception failure) {
            return ToolResult.error("SERIALIZATION_FAILED", "无法序列化结果");
        }
    }

    private static String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof String raw ? raw : "";
    }

    private static Integer integer(Map<String, Object> args, String key) {
        return args.get(key) instanceof Number number ? number.intValue() : null;
    }

    private static final class CreateTool implements AgentTool {

        private final ScheduleService service;

        private CreateTool(ScheduleService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "bma3_schedule_create";
        }

        @Override
        public String description() {
            return "创建一个定时任务：到点往 Inbox 注入一条通知（source=bma3_task/<id>），由你在会话里"
                    + "看到后决定做什么。kind=once（spec 为 ISO-8601 时刻）/interval（spec 为秒数）"
                    + "/cron（5 字段：分 时 日 月 周）。任务不会自己执行代码。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("kind", Map.of("type", "string",
                    "enum", List.of(ScheduleService.KIND_ONCE, ScheduleService.KIND_INTERVAL,
                            ScheduleService.KIND_CRON)));
            properties.put("spec", Map.of("type", "string",
                    "description", "once=ISO-8601；interval=秒；cron=5 字段"));
            properties.put("text", Map.of("type", "string",
                    "description", "到点后要在面板上显示的提醒内容"));
            properties.put("priority", Map.of("type", "integer",
                    "description", "通知优先级，默认 350（≥300 立即推送）"));
            properties.put("missedPolicy", Map.of("type", "string",
                    "description", "停机错过时的策略：skip（默认，跳过）或 once（补一次）"));
            return Map.of("type", "object", "properties", properties,
                    "required", List.of("kind", "spec", "text"));
        }

        @Override
        public ToolResult execute(ToolContext context) {
            Map<String, Object> args = context.arguments();
            try {
                return json(service.create(text(args, "kind"), text(args, "spec"),
                        text(args, "text"), integer(args, "priority"),
                        text(args, "missedPolicy")));
            } catch (IllegalArgumentException failure) {
                return ToolResult.error("INVALID_ARGUMENT", failure.getMessage());
            }
        }
    }

    private static final class ListTool implements AgentTool {

        private final ScheduleService service;

        private ListTool(ScheduleService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "bma3_schedule_list";
        }

        @Override
        public String description() {
            return "列出全部定时任务：id、kind、spec、下次触发时刻、是否启用。只读。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public ToolResult execute(ToolContext context) {
            return json(service.list());
        }
    }

    private static final class GetTool implements AgentTool {

        private final ScheduleService service;

        private GetTool(ScheduleService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "bma3_schedule_get";
        }

        @Override
        public String description() {
            return "查看单个定时任务及其最近运行记录。只读。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object",
                    "properties", Map.of("id", Map.of("type", "string")),
                    "required", List.of("id"));
        }

        @Override
        public ToolResult execute(ToolContext context) {
            String id = text(context.arguments(), "id");
            var schedule = service.get(id);
            if (schedule.isEmpty()) {
                return ToolResult.error("NOT_FOUND", "schedule 不存在: " + id);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schedule", schedule.get());
            result.put("runs", service.runs(20));
            return json(result);
        }
    }

    private static final class CancelTool implements AgentTool {

        private final ScheduleService service;

        private CancelTool(ScheduleService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "bma3_schedule_cancel";
        }

        @Override
        public String description() {
            return "删除一个定时任务；删除后不再触发。运行历史保留。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object",
                    "properties", Map.of("id", Map.of("type", "string")),
                    "required", List.of("id"));
        }

        @Override
        public ToolResult execute(ToolContext context) {
            String id = text(context.arguments(), "id");
            return json(Map.of("id", id, "cancelled", service.cancel(id)));
        }
    }

    private static final class SetEnabledTool implements AgentTool {

        private final ScheduleService service;

        private SetEnabledTool(ScheduleService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "bma3_schedule_set_enabled";
        }

        @Override
        public String description() {
            return "暂停或恢复一个定时任务；恢复时会从当前时间重新计算下次触发时刻。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object",
                    "properties", Map.of(
                            "id", Map.of("type", "string"),
                            "enabled", Map.of("type", "boolean")),
                    "required", List.of("id", "enabled"));
        }

        @Override
        public ToolResult execute(ToolContext context) {
            String id = text(context.arguments(), "id");
            boolean enabled = !Boolean.FALSE.equals(context.arguments().get("enabled"));
            try {
                return json(service.setEnabled(id, enabled));
            } catch (IllegalArgumentException failure) {
                return ToolResult.error("INVALID_ARGUMENT", failure.getMessage());
            }
        }
    }
}
