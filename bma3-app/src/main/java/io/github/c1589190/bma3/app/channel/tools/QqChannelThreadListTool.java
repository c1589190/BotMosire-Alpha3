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

/**
 * 列出本地 registry 中的 logical thread。
 *
 * <p>名字故意是 {@code qq_channel_thread_list}，不能覆盖既有 OneBot 的
 * {@code qq_thread_list}（后者语义是消息板 raw source 列表）。</p>
 */
final class QqChannelThreadListTool implements AgentTool {

    private final ChannelToolContext context;

    QqChannelThreadListTool(ChannelToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_channel_thread_list";
    }

    @Override
    public String description() {
        return "列出本地 QqChannelRegistry 中的 logical thread 及每个渠道绑定、首选渠道、matching mode、"
                + "注入状态和 display 选择；不是既有 OneBot qq_thread_list，也不枚举 QQ 平台会话。"
                + "只返回元数据，不返回正文或 secret。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "最多返回的 logical thread 数",
                "minimum", 1,
                "maximum", 500,
                "default", 50));
        return ChannelToolSupport.schema(properties, List.of(),
                "列出本地 registry 中的 logical thread（按 updatedAt 倒序）；空列表不代表平台无会话。");
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        return ChannelToolSupport.execute(() -> {
            ChannelToolSupport.requireOnly(toolContext, "limit");
            int limit = ChannelToolSupport.optionalInt(toolContext, "limit", 50, 1, 500);
            List<LogicalThread> threads = context.registry().listThreads(limit);

            ObjectNode root = ChannelToolSupport.object();
            root.put("action", name());
            root.put("scope", "local_registry");
            root.put("platformComplete", false);
            root.put("generatedAt", context.clock().instant().toString());
            root.put("count", threads.size());
            root.put("empty", threads.isEmpty());
            root.put("notice", "仅列出本地 QqChannelRegistry 的 logical thread；不是 QQ 平台会话全量列表，"
                    + "空本地结果也不代表平台为空。");
            ArrayNode rows = root.putArray("threads");
            for (LogicalThread thread : threads) {
                ThreadStatus status = context.registry().status(thread.threadId()).orElse(null);
                if (status == null) {
                    continue;
                }
                ObjectNode row = ChannelToolSupport.object();
                row.put("threadId", thread.threadId());
                row.put("label", thread.label());
                row.put("preferredChannel", thread.preferredChannel() == null
                        ? null : thread.preferredChannel().wireName());
                row.put("matchingMode", thread.matchingMode().wireName());
                row.put("injectionEnabled", thread.injectionEnabled());
                row.put("configuredInjection", status.configuredInjection());
                row.put("effectiveInjection", status.effectiveInjection());
                row.put("effectiveInjectionScope", "thread_with_active_sources");
                row.put("effectiveInjectionKnown", true);
                row.put("accountAvailabilityApplied", true);
                row.put("revision", thread.revision());
                row.put("updatedAt", thread.updatedAt().toString());
                row.set("thread", ChannelToolSupport.threadNode(thread));
                ArrayNode bindings = row.putArray("bindings");
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
                ArrayNode identities = row.putArray("identities");
                for (ChannelIdentityBinding identity : status.identities()) {
                    identities.add(ChannelToolSupport.identityNode(identity));
                }
                ObjectNode displayNode = status.display()
                        .map(ChannelToolSupport::displayNode).orElse(null);
                if (displayNode == null) {
                    row.putNull("display");
                } else {
                    row.set("display", displayNode);
                }
                row.set("consistency", ChannelToolSupport.consistencyNode());
                rows.add(row);
            }
            return ChannelToolSupport.ok(root);
        });
    }
}
