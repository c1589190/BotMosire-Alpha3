package io.github.c1589190.bma3.app.channel.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.Channel;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.ChannelIdentityBinding;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.IdentityScope;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.LogicalThread;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.ThreadStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 建立渠道发送者身份映射；scope 限定在 thread 或具体 raw source。 */
final class QqThreadIdentityBindTool implements AgentTool {

    private final ChannelToolContext context;

    QqThreadIdentityBindTool(ChannelToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_thread_identity_bind";
    }

    @Override
    public String description() {
        return "在 logical thread 内建立渠道发送者身份映射：onebot QQ 号 ↔ "
                + "qq_official member_openid/user_openid。scope 必须显式指定 thread 或具体 raw source，"
                + "不能跨会话全局串用；该 thread 必须有对应 channel 的 active binding。"
                + "服务端维护 revision，幂等，无需人工确认。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("threadId", ChannelToolSupport.property("string",
                "logical thread ID，必须已有该 channel 的 active binding"));
        properties.put("channel", ChannelToolSupport.enumProperty(
                "身份来自哪个渠道", "onebot", "qq_official"));
        properties.put("channelUserId", ChannelToolSupport.property("string",
                "该渠道中的用户 ID；onebot 为正整数 QQ 号，qq_official 为 openid/member_openid"));
        properties.put("canonicalUserId", ChannelToolSupport.property("string",
                "调用方定义的会话内统一身份 ID，不得含空白、斜杠或控制字符"));
        properties.put("scope", Map.of(
                "type", "object",
                "description", "映射作用域；thread scope 必须指向当前 thread，source scope 必须是当前 thread "
                        + "下同 channel 的 active raw source",
                "properties", Map.of(
                        "type", Map.of("type", "string", "enum", List.of("thread", "source"),
                                "description", "thread 或 source"),
                        "value", Map.of("type", "string",
                                "description", "thread scope 时为 threadId；source scope 时为 raw source")),
                "required", List.of("type", "value"),
                "additionalProperties", false));
        return ChannelToolSupport.schema(properties,
                List.of("threadId", "channel", "channelUserId", "canonicalUserId", "scope"),
                "建立显式、带 scope 的发送者身份映射。重复同一映射返回 changed=false；"
                        + "与已有映射冲突时 fail-closed。");
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        return ChannelToolSupport.execute(() -> context.mutationGate().underLock(() -> {
            ChannelToolSupport.requireOnly(toolContext, "threadId", "channel", "channelUserId",
                    "canonicalUserId", "scope");
            String threadId = ChannelToolSupport.requiredString(toolContext, "threadId");
            Channel channel = ChannelToolSupport.requiredChannel(toolContext, "channel");
            String channelUserId = ChannelToolSupport.requiredString(toolContext, "channelUserId");
            String canonicalUserId = ChannelToolSupport.requiredString(toolContext,
                    "canonicalUserId");
            IdentityScope scope = ChannelToolSupport.requiredScope(toolContext);
            QqChannelRegistry.IdentityBindResult result = context.registry().bindIdentity(
                    threadId, channel, channelUserId, canonicalUserId, scope);
            LogicalThread thread = result.thread();
            ChannelIdentityBinding identity = result.identity();
            ThreadStatus status = context.registry().status(threadId)
                    .orElseThrow(() -> ChannelToolSupport.failure(
                            ChannelToolSupport.THREAD_NOT_FOUND, "logical thread 不存在"));

            ObjectNode root = ChannelToolSupport.object();
            root.put("action", name());
            root.put("threadId", thread.threadId());
            root.put("channel", identity.channel().wireName());
            root.put("channelUserId", identity.channelUserId());
            root.put("canonicalUserId", identity.canonicalUserId());
            root.put("scopeType", identity.scope().type() == IdentityScope.Type.THREAD
                    ? "thread" : "source");
            root.put("scopeValue", identity.scope().value());
            root.put("active", identity.active());
            root.put("configuredInjection", status.configuredInjection());
            root.put("effectiveInjection", status.effectiveInjection());
            root.put("effectiveInjectionScope", "thread_with_active_sources");
            root.put("effectiveInjectionKnown", true);
            root.put("accountAvailabilityApplied", true);
            root.put("changed", result.changed());
            root.put("revision", thread.revision());
            root.put("threadRevision", thread.revision());
            root.put("identityRevision", identity.revision());
            root.set("thread", ChannelToolSupport.threadNode(thread));
            root.set("identity", ChannelToolSupport.identityNode(identity));
            return ChannelToolSupport.ok(root);
        }));
    }
}
