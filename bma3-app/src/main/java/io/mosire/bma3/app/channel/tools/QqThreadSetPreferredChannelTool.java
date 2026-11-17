package io.mosire.bma3.app.channel.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.app.channel.QqChannelRegistry;
import io.mosire.bma3.app.channel.QqChannelRegistry.Channel;
import io.mosire.bma3.app.channel.QqChannelRegistry.DisplayChoice;
import io.mosire.bma3.app.channel.QqChannelRegistry.LogicalThread;
import io.mosire.bma3.app.channel.QqChannelRegistry.ThreadStatus;
import io.mosire.bma3.app.channel.QqChannelRegistry.ThreadUpdate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 设置 logical thread 的首选展示渠道。 */
final class QqThreadSetPreferredChannelTool implements AgentTool {

    private final ChannelToolContext context;

    QqThreadSetPreferredChannelTool(ChannelToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_thread_set_preferred_channel";
    }

    @Override
    public String description() {
        return "设置 logical thread 的 Inbox 首选展示渠道；只影响后续 canonical item 的渠道选择。"
                + "首选渠道不可用/注入关闭时由服务端回退到另一可用渠道，绝不合并发送路由。"
                + "服务端维护 revision，幂等，无需人工确认。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("threadId", ChannelToolSupport.property("string",
                "logical thread ID"));
        properties.put("preferredChannel", ChannelToolSupport.enumProperty(
                "首选展示渠道；必须为 onebot 或 qq_official", "onebot", "qq_official"));
        return ChannelToolSupport.schema(properties, List.of("threadId", "preferredChannel"),
                "设置 logical thread 的首选展示渠道，返回新 revision 与实际 display 选择（若当前可计算）。");
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        return ChannelToolSupport.execute(() -> context.mutationGate().underLock(() -> {
            ChannelToolSupport.requireOnly(toolContext, "threadId", "preferredChannel");
            String threadId = ChannelToolSupport.requiredString(toolContext, "threadId");
            Channel preferred = ChannelToolSupport.requiredChannel(toolContext,
                    "preferredChannel");
            ThreadUpdate update = context.registry().setPreferredChannel(threadId, preferred);
            LogicalThread thread = update.thread();
            ThreadStatus status = context.registry().status(threadId)
                    .orElseThrow(() -> ChannelToolSupport.failure(
                            ChannelToolSupport.THREAD_NOT_FOUND, "logical thread 不存在"));
            DisplayChoice display = status.display().orElse(null);

            ObjectNode root = ChannelToolSupport.object();
            root.put("action", name());
            root.put("threadId", thread.threadId());
            root.put("preferredChannel", thread.preferredChannel() == null
                    ? null : thread.preferredChannel().wireName());
            root.put("configuredInjection", status.configuredInjection());
            root.put("effectiveInjection", status.effectiveInjection());
            root.put("effectiveInjectionScope", "thread_with_active_sources");
            root.put("effectiveInjectionKnown", true);
            root.put("accountAvailabilityApplied", true);
            root.put("changed", update.changed());
            root.put("revision", thread.revision());
            root.put("threadRevision", thread.revision());
            root.set("thread", ChannelToolSupport.threadNode(thread));
            ObjectNode displayNode = ChannelToolSupport.displayNode(display);
            if (displayNode == null) {
                root.putNull("display");
            } else {
                root.set("display", displayNode);
            }
            return ChannelToolSupport.ok(root);
        }));
    }
}
