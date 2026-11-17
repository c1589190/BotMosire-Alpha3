package io.mosire.bma3.app.channel.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.app.channel.QqChannelRegistry;
import io.mosire.bma3.app.channel.QqChannelRegistry.LogicalThread;
import io.mosire.bma3.app.channel.QqChannelRegistry.MatchingMode;
import io.mosire.bma3.app.channel.QqChannelRegistry.ThreadStatus;
import io.mosire.bma3.app.channel.QqChannelRegistry.ThreadUpdate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 设置 logical thread 的一致性匹配模式。 */
final class QqThreadSetMatchingTool implements AgentTool {

    private final ChannelToolContext context;

    QqThreadSetMatchingTool(ChannelToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_thread_set_matching";
    }

    @Override
    public String description() {
        return "设置已有 logical thread 的匹配模式：strict（默认）/loose/off。配置默认值只用于新建 "
                + "thread，此处的持久化设置优先且热重载不会覆盖。服务端维护 revision，幂等，无需人工确认。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("threadId", ChannelToolSupport.property("string",
                "logical thread ID"));
        properties.put("matchingMode", ChannelToolSupport.enumProperty(
                "strict=严格匹配（默认）；loose=显式宽松；off=关闭自动匹配",
                "strict", "loose", "off"));
        return ChannelToolSupport.schema(properties, List.of("threadId", "matchingMode"),
                "设置 logical thread 的持久化 matching mode，返回新 revision。");
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        return ChannelToolSupport.execute(() -> context.mutationGate().underLock(() -> {
            ChannelToolSupport.requireOnly(toolContext, "threadId", "matchingMode");
            String threadId = ChannelToolSupport.requiredString(toolContext, "threadId");
            MatchingMode mode = ChannelToolSupport.requiredMatchingMode(toolContext,
                    "matchingMode");
            ThreadUpdate update = context.registry().setMatchingMode(threadId, mode);
            LogicalThread thread = update.thread();
            ThreadStatus status = context.registry().status(threadId)
                    .orElseThrow(() -> ChannelToolSupport.failure(
                            ChannelToolSupport.THREAD_NOT_FOUND, "logical thread 不存在"));

            ObjectNode root = ChannelToolSupport.object();
            root.put("action", name());
            root.put("threadId", thread.threadId());
            root.put("matchingMode", thread.matchingMode().wireName());
            root.put("configuredInjection", status.configuredInjection());
            root.put("effectiveInjection", status.effectiveInjection());
            root.put("effectiveInjectionScope", "thread_with_active_sources");
            root.put("effectiveInjectionKnown", true);
            root.put("accountAvailabilityApplied", true);
            root.put("changed", update.changed());
            root.put("revision", thread.revision());
            root.put("threadRevision", thread.revision());
            root.set("thread", ChannelToolSupport.threadNode(thread));
            return ChannelToolSupport.ok(root);
        }));
    }
}
