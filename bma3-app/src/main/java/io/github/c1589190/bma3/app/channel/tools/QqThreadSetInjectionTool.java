package io.github.c1589190.bma3.app.channel.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.ChannelBinding;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.LogicalThread;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.SourceInjectionUpdate;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.SourceStatus;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.ThreadStatus;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.ThreadUpdate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按 raw source 或 logical thread 启停后续 Inbox 注入。 */
final class QqThreadSetInjectionTool implements AgentTool {

    private final ChannelToolContext context;

    QqThreadSetInjectionTool(ChannelToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_thread_set_injection";
    }

    @Override
    public String description() {
        return "设置 raw source 或 logical thread 的后续 Inbox 注入开关；两者同时存在时以更严格者生效。"
                + "只影响之后的新消息：不 ACK、不 dismiss、不删除既有 Inbox item，也不写/删消息板。"
                + "恢复后不补注入暂停期间的消息。服务端维护 revision，幂等，无需人工确认。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("rawSource", ChannelToolSupport.property("string",
                "按 raw source 设置时提供；格式为 platform/account/id，与 threadId 二选一"));
        properties.put("threadId", ChannelToolSupport.property("string",
                "按 logical thread 设置时提供；与 rawSource 二选一"));
        properties.put("enabled", ChannelToolSupport.property("boolean",
                "true=允许后续新消息参与注入；false=暂停后续注入"));
        Map<String, Object> schema = ChannelToolSupport.schema(properties,
                List.of("enabled"),
                "设置 raw source 或 thread 的注入 gate。必须且只能提供 rawSource/threadId 之一。"
                        + "暂停注入只阻止后续新 Inbox item/唤醒，消息板与观察事实保持不变。");
        schema.put("oneOf", List.of(
                Map.of("required", List.of("rawSource"), "not", Map.of("required", List.of("threadId"))),
                Map.of("required", List.of("threadId"), "not", Map.of("required", List.of("rawSource")))));
        return schema;
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        return ChannelToolSupport.execute(() -> context.mutationGate().underLock(() -> {
            ChannelToolSupport.requireOnly(toolContext, "rawSource", "threadId", "enabled");
            String rawSource = ChannelToolSupport.optionalString(toolContext, "rawSource");
            String threadId = ChannelToolSupport.optionalString(toolContext, "threadId");
            boolean enabled = ChannelToolSupport.requiredBoolean(toolContext, "enabled");
            if ((rawSource == null) == (threadId == null)) {
                throw ChannelToolSupport.invalid("rawSource 与 threadId 必须且只能提供一个");
            }

            if (rawSource != null) {
                SourceInjectionUpdate update = context.registry().setSourceInjection(
                        rawSource, enabled);
                SourceStatus sourceStatus = context.registry().sourceStatus(rawSource)
                        .orElse(null);

                ObjectNode root = ChannelToolSupport.object();
                root.put("action", name());
                root.put("target", "raw_source");
                root.put("rawSource", update.gate().rawSource());
                root.put("enabled", update.gate().injectionEnabled());
                root.put("changed", update.changed());
                root.put("affects", "new_messages_only");
                root.put("boardOrAckChanged", false);
                root.set("sourceGate", ChannelToolSupport.sourceGateNode(update.gate()));

                if (sourceStatus == null) {
                    // 未绑定 raw source 没有 thread/binding/账号在线可评估；这里只表达 raw gate
                    // 自身对新消息的本地语义，并明确它不等同于 binding 级 effective。
                    boolean rawGateEnabled = update.gate().injectionEnabled();
                    root.put("bound", false);
                    root.put("configuredInjection", rawGateEnabled);
                    root.put("rawSourceGateEnabled", rawGateEnabled);
                    root.putNull("effectiveInjection");
                    root.put("effectiveInjectionKnown", false);
                    root.put("effectiveInjectionScope", "unbound_raw_source_gate_only");
                    root.put("accountAvailabilityApplied", false);
                    root.put("effectiveInjectionExplanation",
                            "raw source 未绑定：registry 无 sourceStatus 可给出 binding 级 effective；"
                                    + "effectiveInjection 因此为 null。后续 ingest 仅按 raw source gate "
                                    + "决定是否注入新消息，不涉及 thread/binding/pending/账号在线校验。");
                    root.put("revision", update.gate().revision());
                    root.put("sourceRevision", update.gate().revision());
                    root.putNull("threadRevision");
                    root.putNull("bindingRevision");
                    root.putNull("thread");
                    root.putNull("binding");
                    return ChannelToolSupport.ok(root);
                }

                ChannelBinding binding = sourceStatus.binding();
                LogicalThread thread = sourceStatus.thread();
                root.put("bound", true);
                root.put("state", binding.state().name());
                root.put("configuredInjection", sourceStatus.configuredInjection());
                root.put("effectiveInjection", sourceStatus.effectiveInjection());
                root.put("effectiveInjectionScope", "bound_source");
                root.put("effectiveInjectionKnown", true);
                root.put("accountAvailabilityApplied", true);
                root.put("revision", thread.revision());
                root.put("threadRevision", thread.revision());
                root.put("bindingRevision", binding.revision());
                root.put("sourceRevision", update.gate().revision());
                root.set("thread", ChannelToolSupport.threadNode(thread));
                root.set("binding", ChannelToolSupport.bindingNode(binding));
                return ChannelToolSupport.ok(root);
            }

            ThreadUpdate update = context.registry().setThreadInjection(threadId, enabled);
            LogicalThread thread = update.thread();
            ThreadStatus status = context.registry().status(threadId)
                    .orElseThrow(() -> ChannelToolSupport.failure(
                            ChannelToolSupport.THREAD_NOT_FOUND, "logical thread 不存在"));
            ObjectNode root = ChannelToolSupport.object();
            root.put("action", name());
            root.put("target", "thread");
            root.put("threadId", thread.threadId());
            root.put("enabled", thread.injectionEnabled());
            root.put("configuredInjection", status.configuredInjection());
            root.put("effectiveInjection", status.effectiveInjection());
            root.put("effectiveInjectionScope", "thread_with_active_sources");
            root.put("effectiveInjectionKnown", true);
            root.put("accountAvailabilityApplied", true);
            root.put("changed", update.changed());
            root.put("revision", thread.revision());
            root.put("threadRevision", thread.revision());
            root.set("thread", ChannelToolSupport.threadNode(thread));
            root.put("affects", "new_messages_only");
            root.put("boardOrAckChanged", false);
            return ChannelToolSupport.ok(root);
        }));
    }
}
