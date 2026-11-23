package io.github.c1589190.bma3.app.channel.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.Channel;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.IdentityScope;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.LogicalThread;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.ThreadStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 删除带 scope 的渠道发送者身份映射。 */
final class QqThreadIdentityUnbindTool implements AgentTool {

    private final ChannelToolContext context;

    QqThreadIdentityUnbindTool(ChannelToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_thread_identity_unbind";
    }

    @Override
    public String description() {
        return "删除 logical thread 内指定 channel + channelUserId + scope 的发送者身份映射。"
                + "只会停用映射，不删除消息板或 Inbox；重复删除幂等。服务端维护 revision。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("threadId", ChannelToolSupport.property("string", "logical thread ID"));
        properties.put("channel", ChannelToolSupport.enumProperty(
                "身份映射所属渠道", "onebot", "qq_official"));
        properties.put("channelUserId", ChannelToolSupport.property("string",
                "该渠道用户 ID；必须与建立时一致"));
        properties.put("scope", Map.of(
                "type", "object",
                "description", "要删除的映射作用域",
                "properties", Map.of(
                        "type", Map.of("type", "string", "enum", List.of("thread", "source")),
                        "value", Map.of("type", "string",
                                "description", "threadId 或 raw source")),
                "required", List.of("type", "value"),
                "additionalProperties", false));
        return ChannelToolSupport.schema(properties,
                List.of("threadId", "channel", "channelUserId", "scope"),
                "按 threadId + channel + channelUserId + scope 删除身份映射；不存在时 changed=false。");
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        return ChannelToolSupport.execute(() -> context.mutationGate().underLock(() -> {
            ChannelToolSupport.requireOnly(toolContext, "threadId", "channel", "channelUserId",
                    "scope");
            String threadId = ChannelToolSupport.requiredString(toolContext, "threadId");
            Channel channel = ChannelToolSupport.requiredChannel(toolContext, "channel");
            String channelUserId = ChannelToolSupport.requiredString(toolContext, "channelUserId");
            IdentityScope scope = ChannelToolSupport.requiredScope(toolContext);
            QqChannelRegistry.IdentityUnbindResult result = context.registry().unbindIdentity(
                    threadId, channel, channelUserId, scope);
            LogicalThread thread = result.thread();
            ThreadStatus status = context.registry().status(threadId)
                    .orElseThrow(() -> ChannelToolSupport.failure(
                            ChannelToolSupport.THREAD_NOT_FOUND, "logical thread 不存在"));

            ObjectNode root = ChannelToolSupport.object();
            root.put("action", name());
            root.put("threadId", thread.threadId());
            root.put("channel", channel.wireName());
            root.put("channelUserId", channelUserId);
            root.put("scopeType", scope.type() == IdentityScope.Type.THREAD
                    ? "thread" : "source");
            root.put("scopeValue", scope.value());
            root.put("active", false);
            root.put("configuredInjection", status.configuredInjection());
            root.put("effectiveInjection", status.effectiveInjection());
            root.put("effectiveInjectionScope", "thread_with_active_sources");
            root.put("effectiveInjectionKnown", true);
            root.put("accountAvailabilityApplied", true);
            root.put("changed", result.changed());
            root.put("revision", thread.revision());
            root.put("threadRevision", thread.revision());
            root.set("thread", ChannelToolSupport.threadNode(thread));
            return ChannelToolSupport.ok(root);
        }));
    }
}
