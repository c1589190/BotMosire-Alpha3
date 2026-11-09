package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Map;

/** Returns the newest thread metadata plus its latest message. */
public final class QqRecentMessagesTool implements AgentTool {

    private final QqToolContext context;

    public QqRecentMessagesTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_recent_messages";
    }

    @Override
    public String description() {
        return "查看最近活跃的 QQ thread 及最后一条消息；用于发现会话。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 50)));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            int limit = QqToolSupport.integer(toolContext, "limit", 10);
            var root = QqToolSupport.object();
            var array = root.putArray("threads");
            for (var thread : context.board().threads(limit)) {
                var item = array.addObject();
                item.put("source", thread.source());
                item.put("lastSeq", thread.lastSeq());
                item.put("lastAt", thread.lastAt().toString());
                var source = io.mosire.bma3.core.source.SourceAddress.parse(thread.source());
                var messages = context.board().read(source, thread.lastSeq() - 1, 1);
                if (!messages.isEmpty()) {
                    item.put("lastText", QqToolSupport.shorten(
                            QqToolSupport.messageText(messages.getFirst()), 300));
                }
            }
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
