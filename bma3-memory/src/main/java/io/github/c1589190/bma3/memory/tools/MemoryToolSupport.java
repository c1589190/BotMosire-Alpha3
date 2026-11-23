package io.github.c1589190.bma3.memory.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import java.util.List;
import java.util.Map;

/** Argument coercion and JSON shaping shared by the memory tools. */
final class MemoryToolSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    private MemoryToolSupport() {
    }

    static ObjectNode object() {
        return JSON.createObjectNode();
    }

    static String string(ToolContext context, String key, String fallback) {
        Object raw = context.arguments().get(key);
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? fallback : value;
    }

    static int integer(ToolContext context, String key, int fallback) {
        Object raw = context.arguments().get(key);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " 必须是整数");
        }
    }

    static long lng(ToolContext context, String key, long fallback) {
        Object raw = context.arguments().get(key);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " 必须是整数");
        }
    }

    static ToolResult ok(ObjectNode payload) {
        return ToolResult.ok(payload.toString());
    }

    static ToolResult invalid(String message) {
        return ToolResult.error("INVALID_ARGUMENT", message);
    }

    static ToolResult missing(String message) {
        return ToolResult.error("NOT_FOUND", message);
    }

    static Map<String, Object> property(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("type", "object");
        map.put("properties", properties);
        if (!required.isEmpty()) {
            map.put("required", required);
        }
        map.put("additionalProperties", false);
        return map;
    }
}
