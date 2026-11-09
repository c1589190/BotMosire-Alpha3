package io.mosire.bma3.inbox.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.inbox.core.InboxService;
import java.util.List;
import java.util.Map;

/** Acknowledges one delivery and closes its covered items. */
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
        return "确认一条已处理的 delivery；幂等，重复 ack 不报错。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "deliveryId", Map.of("type", "string")), "required", List.of("deliveryId"));
    }

    @Override
    public ToolResult execute(ToolContext context) {
        Object raw = context.arguments().get("deliveryId");
        if (!(raw instanceof String deliveryId) || deliveryId.isBlank()) {
            return ToolResult.error("INVALID_ARGUMENT", "deliveryId 不能为空");
        }
        try {
            return InboxTools.json(service.ack(deliveryId));
        } catch (IllegalArgumentException failure) {
            return ToolResult.error("DELIVERY_NOT_FOUND", "delivery 不存在: " + deliveryId);
        }
    }
}
