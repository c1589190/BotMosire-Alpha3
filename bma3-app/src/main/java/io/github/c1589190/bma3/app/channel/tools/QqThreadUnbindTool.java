package io.github.c1589190.bma3.app.channel.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.LogicalThread;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.ThreadStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 解绑 raw source；保留历史、消息板和 Inbox item。 */
final class QqThreadUnbindTool implements AgentTool {

    private final ChannelToolContext context;

    QqThreadUnbindTool(ChannelToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_thread_unbind";
    }

    @Override
    public String description() {
        return "解除 raw source 与 logical thread 的 active 绑定；不删除消息板、Inbox item、ACK "
                + "或历史。重复解绑幂等。服务端维护 revision，无需 expectedRevision 或人工确认。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("threadId", ChannelToolSupport.property("string",
                "logical thread ID；解绑时必须与 raw source 当前 active 绑定一致"));
        properties.put("rawSource", ChannelToolSupport.property("string",
                "要解绑的 raw source，格式为 platform/account/id"));
        return ChannelToolSupport.schema(properties, List.of("threadId", "rawSource"),
                "按 threadId + rawSource 解绑。已解绑时返回 changed=false，revision 仍为服务端当前值。");
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        return ChannelToolSupport.execute(() -> context.mutationGate().underLock(() -> {
            ChannelToolSupport.requireOnly(toolContext, "threadId", "rawSource");
            String threadId = ChannelToolSupport.requiredString(toolContext, "threadId");
            String rawSource = ChannelToolSupport.requiredString(toolContext, "rawSource");
            BindingOperations.BindingResult result = context.bindingOperations().unbind(
                    threadId, rawSource);
            LogicalThread thread = context.registry().thread(threadId)
                    .orElseThrow(() -> ChannelToolSupport.failure(
                            ChannelToolSupport.THREAD_NOT_FOUND, "logical thread 不存在"));
            ThreadStatus status = context.registry().status(threadId)
                    .orElseThrow(() -> ChannelToolSupport.failure(
                            ChannelToolSupport.THREAD_NOT_FOUND, "logical thread 不存在"));

            ObjectNode root = ChannelToolSupport.object();
            root.put("action", name());
            root.put("threadId", thread.threadId());
            root.put("rawSource", rawSource);
            root.put("bound", false);
            root.put("active", false);
            root.put("configuredInjection", status.configuredInjection());
            root.put("effectiveInjection", status.effectiveInjection());
            root.put("effectiveInjectionScope", "thread_with_active_sources");
            root.put("effectiveInjectionKnown", true);
            root.put("accountAvailabilityApplied", true);
            root.put("coordinated", result.coordinated());
            if (result.ledgerUnbound().isPresent()) {
                root.put("ledgerUnbound", result.ledgerUnbound().orElseThrow());
            } else {
                root.putNull("ledgerUnbound");
            }
            if (result.complete().isPresent()) {
                root.put("ledgerComplete", result.complete().orElseThrow());
            } else {
                root.putNull("ledgerComplete");
            }
            root.put("changed", result.changed());
            root.put("revision", thread.revision());
            root.put("threadRevision", thread.revision());
            root.set("thread", ChannelToolSupport.threadNode(thread));
            return ChannelToolSupport.ok(root);
        }));
    }
}
