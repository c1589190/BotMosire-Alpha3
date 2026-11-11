package io.mosire.bma3.inbox.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.inbox.core.InboxService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Acks inbox items. Each item is acked independently; the global cursor is only an observation.
 *
 * <p>Three addressing modes, because they serve different call sites:</p>
 * <ul>
 *   <li>{@code itemIds} — exact, survives reordering;</li>
 *   <li>{@code indices} / {@code rangeFrom+rangeTo} — the 1-based positions in the panel the
 *       caller just read (pair with {@code revision} so a stale index cannot ack the wrong item);</li>
 *   <li>no argument — ack everything currently visible.</li>
 * </ul>
 */
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
        return "ack 指定 inbox 条目（每个条目独立 ack）：itemIds[] 精确指定；indices[] 或 "
                + "rangeFrom/rangeTo 按面板 1-based 序号；都不给则 ack 当前所有可见条目。"
                + "建议带 revision（来自 inbox_list/inbox_items）做冲突校验。"
                + "deliveryId 仅用于兼容旧 delivery 路径。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "itemIds", Map.of("type", "array", "items", Map.of("type", "string")),
                "indices", Map.of("type", "array", "items", Map.of("type", "integer",
                        "minimum", 1)),
                "rangeFrom", Map.of("type", "integer", "minimum", 1),
                "rangeTo", Map.of("type", "integer", "minimum", 1),
                "revision", Map.of("type", "integer", "minimum", 0,
                        "description", "面板 revision；与当前不一致则拒绝"),
                "deliveryId", Map.of("type", "string")));
    }

    @Override
    public ToolResult execute(ToolContext context) {
        Map<String, Object> args = context.arguments();
        Long revision = args.get("revision") instanceof Number number
                ? number.longValue() : null;
        try {
            if (args.get("deliveryId") instanceof String deliveryId && !deliveryId.isBlank()) {
                return InboxTools.json(service.ack(deliveryId));
            }
            List<String> itemIds = strings(args.get("itemIds"));
            if (!itemIds.isEmpty()) {
                return InboxTools.json(service.ackItems(new java.util.LinkedHashSet<>(itemIds),
                        revision));
            }
            List<Integer> indices = integers(args.get("indices"));
            Integer from = args.get("rangeFrom") instanceof Number n ? n.intValue() : null;
            Integer to = args.get("rangeTo") instanceof Number n ? n.intValue() : null;
            if (from != null || to != null) {
                int start = from == null ? 1 : from;
                int end = to == null ? start : to;
                if (end < start) {
                    return ToolResult.error("INVALID_ARGUMENT", "rangeTo 不能小于 rangeFrom");
                }
                for (int index = start; index <= end; index++) {
                    indices.add(index);
                }
            }
            if (!indices.isEmpty()) {
                return InboxTools.json(service.ackIndices(indices, revision));
            }
            return InboxTools.json(service.ackPanel());
        } catch (IllegalArgumentException failure) {
            return ToolResult.error("ACK_REJECTED", failure.getMessage());
        }
    }

    private static List<String> strings(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object value : list) {
                if (value instanceof String text && !text.isBlank()) {
                    values.add(text);
                }
            }
        }
        return values;
    }

    private static List<Integer> integers(Object raw) {
        List<Integer> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object value : list) {
                if (value instanceof Number number) {
                    values.add(number.intValue());
                }
            }
        }
        return values;
    }
}
