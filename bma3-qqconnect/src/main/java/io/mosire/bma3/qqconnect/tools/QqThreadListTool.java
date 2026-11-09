package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Map;

/** Lists source threads known to the local message board. */
public final class QqThreadListTool implements AgentTool {

    private final QqToolContext context;

    public QqThreadListTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_thread_list";
    }

    @Override
    public String description() {
        return "列出消息板里的 QQ 来源 thread（私聊/群聊），按最近消息排序；只返回元数据，不返回正文。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200)));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            int limit = QqToolSupport.integer(toolContext, "limit", 50);
            var root = QqToolSupport.object();
            var array = root.putArray("threads");
            for (var thread : context.board().threads(limit)) {
                var item = array.addObject();
                item.put("source", thread.source());
                item.put("accountId", thread.accountId());
                item.put("lastSeq", thread.lastSeq());
                item.put("lastAt", thread.lastAt().toString());
                item.put("messageCount", thread.messageCount());
            }
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
