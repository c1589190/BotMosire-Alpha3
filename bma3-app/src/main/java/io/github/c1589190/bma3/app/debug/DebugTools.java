package io.github.c1589190.bma3.app.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import java.util.List;
import java.util.Map;

/**
 * MCP face of the debug surface: how a bound Agent answers a remote question.
 *
 * <p>The local operator asks over HTTP ({@code POST /debug/ask}); the question arrives as an Inbox
 * item; the Agent replies through {@code bma3_debug_reply} so the caller's blocking HTTP request
 * gets its answer. {@code bma3_debug_pending} exists for the case where the Inbox item was already
 * acknowledged before it was answered.</p>
 */
public final class DebugTools {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private DebugTools() {
    }

    public static List<AgentTool> all(DebugService service) {
        return List.of(new ReplyTool(service), new PendingTool(service));
    }

    private static ToolResult json(Object value) {
        try {
            return ToolResult.ok(MAPPER.writeValueAsString(value));
        } catch (Exception failure) {
            return ToolResult.error("SERIALIZATION_FAILED", "无法序列化结果");
        }
    }

    private static String str(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof String text ? text : "";
    }

    private static final class ReplyTool implements AgentTool {

        private final DebugService service;

        private ReplyTool(DebugService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "bma3_debug_reply";
        }

        @Override
        public String description() {
            return "回答一条远程调试提问（请求 id 见 Inbox 里的『调试提问 rq-…』条目）。"
                    + "首个回答生效；过期请求会被拒绝。只回传文本，不执行任何命令。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object",
                    "properties", Map.of(
                            "requestId", Map.of("type", "string",
                                    "description", "调试提问 id，形如 rq-1a2b3c4d5e6f"),
                            "text", Map.of("type", "string",
                                    "description", "回答正文（超过 debug.answerMaxBytes 会被拒）")),
                    "required", List.of("requestId", "text"));
        }

        @Override
        public ToolResult execute(ToolContext context) {
            Map<String, Object> args = context.arguments();
            try {
                return json(service.answer(str(args, "requestId"), str(args, "text"), "agent"));
            } catch (DebugException failure) {
                return ToolResult.error(failure.code().toUpperCase(java.util.Locale.ROOT),
                        failure.getMessage());
            }
        }
    }

    private static final class PendingTool implements AgentTool {

        private final DebugService service;

        private PendingTool(DebugService service) {
            this.service = service;
        }

        @Override
        public String name() {
            return "bma3_debug_pending";
        }

        @Override
        public String description() {
            return "列出尚未回答的远程调试提问（id、提问正文、创建/过期时刻）。只读。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public ToolResult execute(ToolContext context) {
            return json(service.list(DebugRequest.Status.PENDING, 50));
        }
    }
}
