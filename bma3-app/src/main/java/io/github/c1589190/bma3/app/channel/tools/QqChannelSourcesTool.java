package io.github.c1589190.bma3.app.channel.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.ChannelBinding;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.LogicalThread;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.SourceGate;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.SourceStatus;
import io.github.c1589190.bma3.app.channel.tools.ChannelSourceCatalog.ObservedSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列出注入 source catalog 里的本地已观察 raw source。
 *
 * <p>工具把 {@code platformComplete=false} 和固定 notice 写进每次结果，避免把“本地目录为空”或
 * “本地只看到两个群”误读成 QQ 平台全量。目录是注入事实，不由 registry 里的空表反推。</p>
 */
final class QqChannelSourcesTool implements AgentTool {

    private final ChannelToolContext context;

    QqChannelSourcesTool(ChannelToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_channel_sources";
    }

    @Override
    public String description() {
        return "列出注入 source catalog 中本地已观察到的 QQ raw source、渠道、账号、最后活动时间，"
                + "以及当前绑定/注入/display 状态。该列表不是 QQ 平台全量来源枚举；本地为空也不代表"
                + "平台没有来源。只读，不返回正文或 secret。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "最多返回的本地已观察 raw source 数",
                "minimum", 1,
                "maximum", 500,
                "default", 100));
        return ChannelToolSupport.schema(properties, List.of(),
                "列出注入 source catalog 的本地 observed raw source 及控制面状态；"
                        + "结果永远标记 platformComplete=false，不是平台全量列表。");
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        return ChannelToolSupport.execute(() -> {
            ChannelToolSupport.requireOnly(toolContext, "limit");
            int limit = ChannelToolSupport.optionalInt(toolContext, "limit", 100, 1, 500);
            List<ObservedSource> catalog;
            try {
                catalog = context.sourceCatalog().listObservedSources(limit);
            } catch (RuntimeException failure) {
                throw ChannelToolSupport.failure(ChannelToolSupport.SOURCE_CATALOG_UNAVAILABLE,
                        "source catalog 暂不可用，无法确认本地来源");
            }
            if (catalog == null) {
                throw ChannelToolSupport.failure(ChannelToolSupport.SOURCE_CATALOG_INVALID,
                        "source catalog 返回空引用");
            }

            Map<String, ObservedSource> unique = new LinkedHashMap<>();
            for (ObservedSource source : catalog) {
                if (source == null || source.rawSource() == null) {
                    throw ChannelToolSupport.failure(ChannelToolSupport.SOURCE_CATALOG_INVALID,
                            "source catalog 含空条目");
                }
                String rawSource = source.rawSource();
                if (unique.containsKey(rawSource)) {
                    throw ChannelToolSupport.failure(ChannelToolSupport.SOURCE_CATALOG_INVALID,
                            "source catalog 含重复 raw source");
                }
                // 用 registry 的公开读取入口执行与写操作相同的 raw source 格式校验。
                context.registry().sourceStatus(rawSource);
                unique.put(rawSource, source);
            }

            List<ObservedSource> sorted = new ArrayList<>(unique.values());
            sorted.sort(Comparator
                    .comparing((ObservedSource source) -> source.lastActivity(),
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(ObservedSource::rawSource));
            boolean truncated = sorted.size() > limit;
            if (truncated) {
                sorted = new ArrayList<>(sorted.subList(0, limit));
            }

            ObjectNode root = ChannelToolSupport.object();
            root.put("action", name());
            root.put("scope", "local_observed_catalog");
            root.put("platformComplete", false);
            root.put("catalogInjected", true);
            root.put("generatedAt", context.clock().instant().toString());
            root.put("count", sorted.size());
            root.put("empty", sorted.isEmpty());
            root.put("truncated", truncated);
            root.put("notice", "仅列出注入 source catalog 中本地已观察到的 raw source；不是 QQ 平台全量"
                    + "列表，空本地结果也不代表平台为空。");
            ArrayNode rows = root.putArray("sources");
            for (ObservedSource source : sorted) {
                rows.add(sourceNode(source));
            }
            return ChannelToolSupport.ok(root);
        });
    }

    private ObjectNode sourceNode(ObservedSource source) {
        String rawSource = source.rawSource();
        SourceStatus status = context.registry().sourceStatus(rawSource).orElse(null);
        ChannelBinding binding = status == null ? null : status.binding();
        LogicalThread thread = status == null ? null : status.thread();
        SourceGate gate = status != null
                ? status.sourceGate().orElse(null)
                : context.registry().sourceGate(rawSource).orElse(null);
        boolean sourceEnabled = gate == null || gate.injectionEnabled();
        boolean configured = status != null ? status.configuredInjection() : sourceEnabled;
        boolean effective = status != null ? status.effectiveInjection() : sourceEnabled;
        String channel = binding != null ? binding.channel().wireName()
                : ChannelToolSupport.sourceChannel(rawSource);
        String accountId = binding != null ? binding.accountId()
                : ChannelToolSupport.sourceAccountId(rawSource);

        ObjectNode row = ChannelToolSupport.object();
        row.put("rawSource", rawSource);
        row.put("channel", channel);
        row.put("accountId", accountId);
        row.put("lastActivity", ChannelToolSupport.timestamp(source.lastActivity()));
        row.put("bound", status != null);
        row.put("configuredInjection", configured);
        row.put("rawSourceGateEnabled", sourceEnabled);
        if (status == null) {
            row.putNull("effectiveInjection");
        } else {
            row.put("effectiveInjection", effective);
        }
        row.put("effectiveInjectionKnown", status != null);
        row.put("effectiveInjectionScope", status != null
                ? "bound_source" : "unbound_raw_source_gate_only");
        row.put("accountAvailabilityApplied", status != null);
        if (thread != null) {
            row.put("revision", thread.revision());
        } else if (gate != null) {
            row.put("revision", gate.revision());
        } else {
            row.putNull("revision");
        }
        row.putNull("threadRevision");
        row.putNull("bindingRevision");
        row.putNull("sourceRevision");
        if (thread != null) {
            row.put("threadRevision", thread.revision());
        }
        if (binding != null) {
            row.put("bindingRevision", binding.revision());
        }
        if (gate != null) {
            row.put("sourceRevision", gate.revision());
        }
        if (binding == null) {
            row.putNull("binding");
            row.putNull("thread");
        } else {
            row.set("binding", ChannelToolSupport.bindingNode(binding));
            row.set("thread", ChannelToolSupport.threadNode(thread));
        }

        ObjectNode injection = row.putObject("injection");
        injection.put("sourceEnabled", sourceEnabled);
        if (thread == null) {
            injection.putNull("threadEnabled");
        } else {
            injection.put("threadEnabled", thread.injectionEnabled());
        }
        if (binding == null) {
            injection.putNull("bindingEnabled");
        } else {
            injection.put("bindingEnabled", binding.injectionEnabled());
        }
        injection.put("configured", configured);
        injection.put("rawSourceGateEnabled", sourceEnabled);
        if (status == null) {
            injection.putNull("effective");
            injection.put("effectiveKnown", false);
            injection.put("effectiveScope", "unbound_raw_source_gate_only");
            injection.put("effectiveExplanation",
                    "raw source 未绑定：registry 无 sourceStatus 可给出 binding 级 effective；"
                            + "后续 ingest 仅按 raw source gate 决定是否注入新消息，"
                            + "不涉及 thread/binding/pending/账号在线校验。");
        } else {
            injection.put("effective", effective);
            injection.put("effectiveKnown", true);
            injection.put("effectiveScope", "bound_source");
        }
        injection.put("accountAvailabilityApplied", status != null);
        injection.put("affects", "new_messages_only");

        ObjectNode displayNode = status == null || status.display().isEmpty()
                ? null : ChannelToolSupport.displayNode(status.display().orElseThrow());
        if (displayNode == null) {
            row.putNull("display");
        } else {
            row.set("display", displayNode);
        }
        return row;
    }
}
