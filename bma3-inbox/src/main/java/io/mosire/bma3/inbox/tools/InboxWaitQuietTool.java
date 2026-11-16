package io.mosire.bma3.inbox.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.inbox.core.InboxItem;
import io.mosire.bma3.inbox.core.InboxService;
import io.mosire.bma3.inbox.core.WaitQuietResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent-invoked, read-only source quiet wait.
 *
 * <p>This is deliberately separate from {@link InboxWaitTool}: that tool waits for the next
 * arbitrary push, while this one waits for one source (or source prefix) to stop changing. It
 * never ACKs, freezes, creates items, or changes the push schedule.</p>
 */
public final class InboxWaitQuietTool implements AgentTool {

    public static final int DEFAULT_QUIET_MS = 2_000;
    public static final int DEFAULT_MAX_WAIT_MS = 20_000;
    public static final int MAX_QUIET_MS = 60_000;
    public static final int MAX_WAIT_MS = 120_000;

    private final InboxService service;

    public InboxWaitQuietTool(InboxService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "inbox_wait_quiet";
    }

    @Override
    public String description() {
        return "按 source/sourcePrefix 只读等待来源安静：默认 quietMs=2000、maxWaitMs=20000。"
                + "静默满足返回 settled=true；持续有新消息到硬上限返回 settled=false/stillActive=true。"
                + "支持任意来源；不 ACK、不冻结、不改变 Inbox 推送节奏。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("source", Map.of("type", "string",
                "description", "精确来源，例如 qq_private/account/12345"));
        props.put("sourcePrefix", Map.of("type", "string",
                "description", "来源前缀；与 source 二选一，支持任意来源"));
        props.put("quietMs", Map.of("type", "integer", "minimum", 0, "maximum", MAX_QUIET_MS,
                "description", "连续多静默才算稳定，默认 " + DEFAULT_QUIET_MS));
        props.put("maxWaitMs", Map.of("type", "integer", "minimum", 1, "maximum", MAX_WAIT_MS,
                "description", "总等待上限，默认 " + DEFAULT_MAX_WAIT_MS));
        props.put("includeEntries", Map.of("type", "boolean",
                "description", "true 时附带匹配 item 的 bounded entries；默认 false"));
        return Map.of("type", "object", "properties", props);
    }

    @Override
    public ToolResult execute(ToolContext context) {
        String source = text(context, "source");
        String sourcePrefix = text(context, "sourcePrefix");
        if (source.isBlank() && sourcePrefix.isBlank()) {
            return ToolResult.error("SOURCE_REQUIRED", "source 或 sourcePrefix 至少提供一个");
        }
        int quietMs = integer(context, "quietMs", DEFAULT_QUIET_MS);
        int maxWaitMs = integer(context, "maxWaitMs", DEFAULT_MAX_WAIT_MS);
        boolean includeEntries = Boolean.TRUE.equals(context.arguments().get("includeEntries"));
        try {
            WaitQuietResult result = service.awaitQuiet(source, sourcePrefix,
                    Duration.ofMillis(quietMs), Duration.ofMillis(maxWaitMs));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("settled", result.settled());
            body.put("stillActive", result.stillActive());
            body.put("reason", result.reason());
            body.put("source", result.source());
            body.put("matchedBy", result.matchedBy());
            body.put("quietMs", quietMs);
            body.put("maxWaitMs", maxWaitMs);
            body.put("waitedMs", result.waitedMs());
            body.put("lastActivityAt", result.lastActivityAt());
            List<Map<String, Object>> items = new ArrayList<>();
            for (InboxItem item : result.items()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.id());
                row.put("source", item.source());
                row.put("sourceLabel", item.sourceLabel());
                row.put("priority", item.priority());
                row.put("status", item.status().name());
                row.put("totalMessages", item.totalMessages());
                row.put("updatedAt", item.updatedAt());
                row.put("revision", item.revision());
                row.put("frozenRevision", item.frozenRevision());
                row.put("continued", item.continued());
                if (includeEntries) {
                    row.put("entries", item.entries());
                }
                items.add(row);
            }
            body.put("items", items);
            body.put("hint", result.settled()
                    ? "来源已安静，可以正式回复或继续处理其他事"
                    : "来源仍在活动；可以再调用一次继续等，或现在给出阶段性回复");
            return InboxTools.json(body);
        } catch (IllegalArgumentException failure) {
            String message = failure.getMessage() == null ? "参数错误" : failure.getMessage();
            String code = message.equals("SOURCE_REQUIRED") ? "SOURCE_REQUIRED"
                    : "INVALID_WAIT_PARAMETERS";
            return ToolResult.error(code, message);
        }
    }

    private static String text(ToolContext context, String name) {
        Object raw = context.arguments().get(name);
        return raw instanceof String value ? value.trim() : "";
    }

    private static int integer(ToolContext context, String name, int fallback) {
        Object raw = context.arguments().get(name);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String value && !value.isBlank()) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
