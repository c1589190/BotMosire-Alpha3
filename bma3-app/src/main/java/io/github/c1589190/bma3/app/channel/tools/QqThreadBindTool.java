package io.github.c1589190.bma3.app.channel.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.Channel;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.ChannelBinding;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.LogicalThread;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.SourceStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 绑定 raw source 到 logical thread；创建 thread 或追加绑定。 */
final class QqThreadBindTool implements AgentTool {

    private final ChannelToolContext context;

    QqThreadBindTool(ChannelToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_thread_bind";
    }

    @Override
    public String description() {
        return "把一条已支持的 QQ raw source 绑定到 logical thread；thread 不存在时创建。"
                + "绑定幂等，服务端原子维护 revision，不需要 expectedRevision，也不需要人工二次确认。"
                + "账号是否存在由 registry 的装配校验器决定；工具不接收 token/secret/正文，"
                + "不删除消息板或 Inbox 事实。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("threadId", ChannelToolSupport.property("string",
                "logical thread ID，只允许 [A-Za-z0-9._-]，长度 1..128"));
        properties.put("rawSource", ChannelToolSupport.property("string",
                "渠道原始来源，例如 qq_group/bot-1/<群号> 或 "
                        + "qq_official_group/app-1/group_openid_A；必须为 platform/account/id 三段"));
        properties.put("channel", ChannelToolSupport.enumProperty(
                "可选；显式声明 raw source 的渠道，必须与前缀一致（onebot / qq_official）",
                "onebot", "qq_official"));
        properties.put("label", ChannelToolSupport.property("string",
                "可选 thread label，最长 256 code point，不含控制字符"));
        return ChannelToolSupport.schema(properties, List.of("threadId", "rawSource"),
                "绑定 raw source 到 logical thread。重复绑定同一对返回当前状态且 changed=false；"
                        + "返回服务端 thread revision 与 binding revision。");
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        return ChannelToolSupport.execute(() -> context.mutationGate().underLock(() -> {
            ChannelToolSupport.requireOnly(toolContext, "threadId", "rawSource", "channel", "label");
            String threadId = ChannelToolSupport.requiredString(toolContext, "threadId");
            String rawSource = ChannelToolSupport.requiredString(toolContext, "rawSource");
            Channel expectedChannel = ChannelToolSupport.optionalChannel(toolContext, "channel");
            String label = ChannelToolSupport.optionalString(toolContext, "label");
            ChannelToolSupport.requireChannelMatchesRawSource(context.registry(), rawSource,
                    expectedChannel);
            BindingOperations.BindingResult result = context.bindingOperations().bind(
                    threadId, rawSource, label == null ? "" : label);
            LogicalThread thread = context.registry().thread(threadId)
                    .orElseThrow(() -> ChannelToolSupport.failure(
                            ChannelToolSupport.INTERNAL_ERROR, "绑定后的 thread 状态不可用"));
            ChannelBinding binding = context.registry().binding(rawSource)
                    .filter(ChannelBinding::active)
                    .orElseThrow(() -> ChannelToolSupport.failure(
                            ChannelToolSupport.INTERNAL_ERROR, "绑定后的 binding 状态不可用"));
            SourceStatus sourceStatus = context.registry().sourceStatus(rawSource)
                    .orElseThrow(() -> ChannelToolSupport.failure(
                            ChannelToolSupport.INTERNAL_ERROR, "绑定后的 source 状态不可用"));

            ObjectNode root = ChannelToolSupport.object();
            root.put("action", name());
            root.put("threadId", thread.threadId());
            root.put("rawSource", binding.rawSource());
            root.put("channel", binding.channel().wireName());
            root.put("accountId", binding.accountId());
            root.put("bound", true);
            root.put("state", binding.state().name());
            root.put("pendingObservation",
                    binding.state() == QqChannelRegistry.BindingState.PENDING_OBSERVATION);
            root.put("active", binding.active());
            root.put("injectionEnabled", binding.injectionEnabled());
            root.put("configuredInjection", sourceStatus.configuredInjection());
            root.put("effectiveInjection", sourceStatus.effectiveInjection());
            root.put("effectiveInjectionScope", "bound_source");
            root.put("effectiveInjectionKnown", true);
            root.put("accountAvailabilityApplied", true);
            root.put("changed", result.changed());
            root.put("revision", thread.revision());
            root.put("threadRevision", thread.revision());
            root.put("bindingRevision", binding.revision());
            root.put("coordinated", result.coordinated());
            if (result.ledgerGeneration().isPresent()) {
                root.put("ledgerGeneration", result.ledgerGeneration().getAsLong());
            } else {
                root.putNull("ledgerGeneration");
            }
            if (result.startBoardSeq().isPresent()) {
                root.put("startBoardSeq", result.startBoardSeq().getAsLong());
            } else {
                root.putNull("startBoardSeq");
            }
            root.set("thread", ChannelToolSupport.threadNode(thread));
            root.set("binding", ChannelToolSupport.bindingNode(binding));
            return ChannelToolSupport.ok(root);
        }));
    }
}
