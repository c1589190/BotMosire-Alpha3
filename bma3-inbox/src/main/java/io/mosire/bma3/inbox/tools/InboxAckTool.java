package io.mosire.bma3.inbox.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.inbox.core.InboxService;
import java.util.List;
import java.util.Map;

/** Advances the panel read cursor, or acknowledges a legacy delivery by id. */
public final class InboxAckTool implements AgentTool {

    private final InboxService service;

    public InboxAckTool(InboxService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "inbox_ack";
    }

    @Override
    public String description() {
        return "推进 Inbox 面板读游标；可选 deliveryId 用于兼容旧 delivery。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "deliveryId", Map.of("type", "string")));
    }

    @Override
    public ToolResult execute(ToolContext context) {
        Object raw = context.arguments().get("deliveryId");
        if (raw instanceof String deliveryId && !deliveryId.isBlank()) {
            try {
                return InboxTools.json(service.ack(deliveryId));
            } catch (IllegalArgumentException failure) {
                return ToolResult.error("DELIVERY_NOT_FOUND", "delivery 不存在: " + deliveryId);
            }
        }
        return InboxTools.json(service.ackPanel());
    }
}
