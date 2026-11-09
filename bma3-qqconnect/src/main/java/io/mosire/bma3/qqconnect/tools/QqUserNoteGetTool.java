package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.List;
import java.util.Map;

/** Reads one per-account QQ user note. */
public final class QqUserNoteGetTool implements AgentTool {

    private final QqToolContext context;

    public QqUserNoteGetTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_user_note_get";
    }

    @Override
    public String description() {
        return "读取某个 Bot 账号下 QQ 用户的备注、标签和 priority。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "accountId", Map.of("type", "string"),
                "userId", Map.of("type", "string")),
                "required", List.of("userId"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            var extras = QqToolSupport.extras(context);
            String accountId = QqToolSupport.accountId(context, toolContext);
            String userId = QqToolSupport.text(toolContext, "userId");
            return extras.note(accountId, userId)
                    .map(note -> ToolResult.ok(QqToolSupport.noteJson(note)))
                    .orElseGet(() -> ToolResult.error("NOTE_NOT_FOUND", userId));
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
