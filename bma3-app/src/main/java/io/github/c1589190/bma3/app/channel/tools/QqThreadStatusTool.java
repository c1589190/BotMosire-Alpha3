package io.github.c1589190.bma3.app.channel.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.ChannelBinding;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.ChannelIdentityBinding;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.LogicalThread;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.SourceStatus;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.ThreadStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 返回 logical thread 的绑定、身份、注入与 display 状态。 */
final class QqThreadStatusTool implements AgentTool {

    private final ChannelToolContext context;

    QqThreadStatusTool(ChannelToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_thread_status";
    }

    @Override
    public String description() {
        return "查看 logical thread 的服务端 revision、绑定、身份映射、注入门与当前 display 选择。"
                + "只返回控制面元数据，不返回消息正文、token 或 secret。Phase 8 尚未接入证据表时，"
                + "consistency.available=false，不伪造 MATCHED/CONFLICT。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("threadId", ChannelToolSupport.property("string",
                "要查看的 logical thread ID"));
        return ChannelToolSupport.schema(properties, List.of("threadId"),
                "读取单个 logical thread 的持久化控制面状态与 revision。");
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        return ChannelToolSupport.execute(() -> {
            ChannelToolSupport.requireOnly(toolContext, "threadId");
            String threadId = ChannelToolSupport.requiredString(toolContext, "threadId");
            ThreadStatus status = context.registry().status(threadId)
                    .orElseThrow(() -> ChannelToolSupport.failure(
                            ChannelToolSupport.THREAD_NOT_FOUND, "logical thread 不存在"));
            LogicalThread thread = status.thread();

            ObjectNode root = ChannelToolSupport.object();
            root.put("action", name());
            root.put("threadId", thread.threadId());
            root.put("revision", thread.revision());
            root.put("threadRevision", thread.revision());
            root.put("configuredInjection", status.configuredInjection());
            root.put("effectiveInjection", status.effectiveInjection());
            root.put("effectiveInjectionScope", "thread_with_active_sources");
            root.put("effectiveInjectionKnown", true);
            root.put("accountAvailabilityApplied", true);
            root.set("thread", ChannelToolSupport.threadNode(thread));

            ArrayNode bindings = root.putArray("bindings");
            for (ChannelBinding binding : status.bindings()) {
                ObjectNode bindingNode = ChannelToolSupport.bindingNode(binding);
                SourceStatus sourceStatus = context.registry().sourceStatus(binding.rawSource())
                        .orElse(null);
                if (sourceStatus == null) {
                    bindingNode.putNull("configuredInjection");
                    bindingNode.putNull("effectiveInjection");
                } else {
                    bindingNode.put("configuredInjection", sourceStatus.configuredInjection());
                    bindingNode.put("effectiveInjection", sourceStatus.effectiveInjection());
                    bindingNode.put("effectiveInjectionKnown", true);
                }
                bindings.add(bindingNode);
            }
            ArrayNode identities = root.putArray("identities");
            for (ChannelIdentityBinding identity : status.identities()) {
                identities.add(ChannelToolSupport.identityNode(identity));
            }
            ObjectNode displayNode = status.display()
                    .map(ChannelToolSupport::displayNode).orElse(null);
            if (displayNode == null) {
                root.putNull("display");
            } else {
                root.set("display", displayNode);
            }
            root.set("consistency", ChannelToolSupport.consistencyNode());
            return ChannelToolSupport.ok(root);
        });
    }
}
