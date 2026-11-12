package io.mosire.bma3.inbox.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.inbox.core.DismissRequest;
import io.mosire.bma3.inbox.core.InboxService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bulk "clear without reading" (D-042).
 *
 * <p>This is the backlog drain: pick items by filter and mark them ACKED without rendering their
 * bodies. It never deletes rows, protects items at/above the immediate threshold unless
 * {@code allowImportant} is set, and can preview with {@code dryRun}.</p>
 */
public final class InboxDismissTool implements AgentTool {

    private final InboxService service;

    public InboxDismissTool(InboxService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "inbox_dismiss";
    }

    @Override
    public String description() {
        return "不看正文直接清掉条目（批量 ack，不删库）：scope=visible 清当前面板窗口；"
                + "scope=filter 按 sourcePrefix/priorityBelow/priorityAtLeast/olderThanSeconds 过滤；"
                + "scope=ids 指定 itemIds。默认跳过 priority >= immediateThreshold 的重要条目"
                + "（allowImportant=true 才动），dryRun=true 只预览不改变状态。"
                + "返回 matched/dismissed/skippedImportant/remaining/truncated 和匹配条目摘要。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "scope", Map.of("type", "string",
                        "enum", List.of("visible", "filter", "ids"),
                        "description", "默认 visible"),
                "itemIds", Map.of("type", "array", "items", Map.of("type", "string")),
                "sourcePrefix", Map.of("type", "string",
                        "description", "来源前缀，例如 qq_group/955432692"),
                "priorityBelow", Map.of("type", "integer",
                        "description", "严格小于该优先级"),
                "priorityAtLeast", Map.of("type", "integer",
                        "description", "大于等于该优先级"),
                "olderThanSeconds", Map.of("type", "integer", "minimum", 0),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum",
                        DismissRequest.MAX_LIMIT),
                "dryRun", Map.of("type", "boolean",
                        "description", "true=只预览，不改变状态"),
                "allowImportant", Map.of("type", "boolean",
                        "description", "true=允许清掉 >= immediateThreshold 的条目"),
                "reason", Map.of("type", "string",
                        "description", "简短原因，只进日志，不含正文")));
    }

    @Override
    public ToolResult execute(ToolContext context) {
        try {
            Map<String, Object> args = context.arguments();
            DismissRequest request = new DismissRequest(
                    string(args.get("scope"), "visible"),
                    ids(args.get("itemIds")),
                    string(args.get("sourcePrefix"), ""),
                    integer(args.get("priorityBelow")),
                    integer(args.get("priorityAtLeast")),
                    longValue(args.get("olderThanSeconds")),
                    integer(args.get("limit")) == null ? 100 : integer(args.get("limit")),
                    Boolean.TRUE.equals(args.get("dryRun")),
                    Boolean.TRUE.equals(args.get("allowImportant")),
                    string(args.get("reason"), ""));
            return InboxTools.json(service.dismiss(request));
        } catch (IllegalArgumentException failure) {
            return ToolResult.error("DISMISS_REJECTED", failure.getMessage());
        }
    }

    private static String string(Object raw, String fallback) {
        return raw instanceof String value && !value.isBlank() ? value.trim() : fallback;
    }

    private static Integer integer(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String value && !value.isBlank()) {
            return Integer.parseInt(value.trim());
        }
        return null;
    }

    private static Long longValue(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String value && !value.isBlank()) {
            return Long.parseLong(value.trim());
        }
        return null;
    }

    private static Set<String> ids(Object raw) {
        Set<String> values = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object value : list) {
                if (value instanceof String text && !text.isBlank()) {
                    values.add(text.trim());
                }
            }
        }
        return values;
    }
}
