package io.mosire.bma3.inbox.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.inbox.core.InboxPanel;
import io.mosire.bma3.inbox.core.InboxService;
import java.time.Duration;
import java.util.Map;

/**
 * Blocking wait for the next inbox push.
 *
 * <p>This is the sanctioned alternative to polling: the server holds the call until a batch
 * flushes or the timeout expires. It is <b>read-only</b> — it never acks, so it cannot steal a
 * wake-up from the injector.</p>
 *
 * <p>Timeouts are self-imposed because AgentLib 0.2.5 has no deadline/cancellation on
 * {@code ToolContext}; the cap keeps a stuck call from outliving the MCP request.</p>
 */
public final class InboxWaitTool implements AgentTool {

    public static final int DEFAULT_TIMEOUT_MS = 20_000;
    public static final int MAX_TIMEOUT_MS = 60_000;

    private final InboxService service;

    public InboxWaitTool(InboxService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "inbox_wait";
    }

    @Override
    public String description() {
        return "阻塞等待下一次 inbox 推送（服务端 hold，不轮询）。超时返回 changed=false；"
                + "有新条目返回 changed=true + 当前面板。只读：不推进 ack 游标。"
                + "timeoutMs 默认 20000，上限 60000。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "timeoutMs", Map.of("type", "integer", "minimum", 1, "maximum", MAX_TIMEOUT_MS),
                "minPriority", Map.of("type", "integer",
                        "description", "只看达到该分数的条目（可选；默认不过滤）")));
    }

    @Override
    public ToolResult execute(ToolContext context) {
        int requested = context.arguments().get("timeoutMs") instanceof Number number
                ? number.intValue() : DEFAULT_TIMEOUT_MS;
        int timeout = Math.max(1, Math.min(MAX_TIMEOUT_MS, requested));
        InboxPanel panel = service.awaitPush(Duration.ofMillis(timeout));
        if (panel == null) {
            return InboxTools.json(Map.of(
                    "changed", false,
                    "reason", "timeout",
                    "timeoutMs", timeout,
                    "revision", service.panel().revision()));
        }
        return InboxTools.json(Map.of("changed", true, "panel", panel));
    }
}
