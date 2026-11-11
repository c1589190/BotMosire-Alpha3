package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Map;

/** Lists per-account user notes. */
public final class QqUserNoteListTool implements AgentTool {

    private final QqToolContext context;

    public QqUserNoteListTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_user_note_list";
    }

    @Override
    public String description() {
        return "列出某个 Bot 账号下的 QQ 用户备注（备注/别名/标签，不含分数）。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "accountId", Map.of("type", "string")));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String accountId = QqToolSupport.accountId(context, toolContext);
            var root = QqToolSupport.object();
            var array = root.putArray("notes");
            for (var note : QqToolSupport.extras(context).notes(accountId)) {
                array.add(QqToolSupport.noteNode(note));
            }
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
