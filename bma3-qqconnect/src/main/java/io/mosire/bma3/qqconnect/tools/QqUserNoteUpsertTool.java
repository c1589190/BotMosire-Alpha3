package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Upserts a per-account user note: display name, tags and free text. */
public final class QqUserNoteUpsertTool implements AgentTool {

    private final QqToolContext context;

    public QqUserNoteUpsertTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_user_note_upsert";
    }

    @Override
    public String description() {
        return "新建或更新 QQ 用户备注/别名/标签；省略字段保留旧值，tags 传入时整体替换。"
                + "分数不在这里：优先级走 set_config 的 weighting.sourcePriority / weighting.attributes。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "accountId", Map.of("type", "string"),
                "userId", Map.of("type", "string"),
                "displayName", Map.of("type", "string"),
                "tags", Map.of("type", "array", "items", Map.of("type", "string")),
                "note", Map.of("type", "string"),
                "expectedRevision", Map.of("type", "integer", "minimum", 0)),
                "required", List.of("userId"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            var extras = QqToolSupport.extras(context);
            String accountId = QqToolSupport.accountId(context, toolContext);
            String userId = QqToolSupport.text(toolContext, "userId");
            var current = extras.note(accountId, userId).orElse(null);
            String displayName = toolContext.arguments().get("displayName") instanceof String s
                    ? s : current == null ? "" : current.displayName();
            List<String> tags = current == null ? List.of() : current.tags();
            if (toolContext.arguments().get("tags") instanceof List<?> rawTags) {
                tags = new ArrayList<>();
                for (Object tag : rawTags) {
                    tags.add(String.valueOf(tag));
                }
            }
            String note = toolContext.arguments().get("note") instanceof String s
                    ? s : current == null ? "" : current.note();
            Integer expected = toolContext.arguments().get("expectedRevision") instanceof Number n
                    ? n.intValue() : current == null ? null : current.revision();
            var saved = extras.upsertNote(accountId, userId, displayName, tags, note, expected);
            return ToolResult.ok(QqToolSupport.noteJson(saved));
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
