package io.github.c1589190.bma3.app.channel.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.CorrelationPair;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.Observation;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ObservationCursor;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ObservationPage;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.StatusCounts;
import io.github.c1589190.bma3.core.source.SourceAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只读跨渠道证据/一致性分页视图；绝不读取或返回消息正文。 */
final class QqThreadCompareTool implements AgentTool {

    private static final int MAX_LIMIT = 200;

    private final ChannelEvidenceToolContext context;

    QqThreadCompareTool(ChannelEvidenceToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_thread_compare";
    }

    @Override
    public String description() {
        return "按 logical thread 分页列出本地 channel observation 的 channel/account/raw source、"
                + "route/correlation state、双方 observation ID、时间与非敏感摘要，并返回当前 route/"
                + "correlation 统计。只读本地 observation store，不读取消息正文/segments/URL，不返回"
                + "无盐正文摘要、token 或凭据；不是 QQ 平台全量证据。causal correlation 为空时明确"
                + "pairPresent=false。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("threadId", Map.of(
                "type", "string",
                "description", "logical thread ID；thread 解绑后仍可查询本地历史"));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "本页 observation 数上限",
                "minimum", 1,
                "maximum", MAX_LIMIT,
                "default", 50));
        properties.put("cursor", cursorSchema());
        properties.put("from", Map.of(
                "type", "string",
                "format", "date-time",
                "description", "可选 messageAt 下界（含）"));
        properties.put("to", Map.of(
                "type", "string",
                "format", "date-time",
                "description", "可选 messageAt 上界（不含）"));
        return ChannelToolSupport.schema(properties, List.of("threadId"),
                "只读、分页、body-free 的 thread 证据视图；始终返回 statusCounts 与 available/empty。");
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        return ChannelEvidenceSupport.execute(() -> {
            ChannelEvidenceSupport.requireOnly(toolContext,
                    "threadId", "limit", "cursor", "from", "to");
            String threadId = ChannelEvidenceSupport.requiredThreadId(toolContext);
            int limit = ChannelEvidenceSupport.optionalLimit(toolContext, 50, MAX_LIMIT);
            ObservationCursor cursor = ChannelEvidenceSupport.optionalCursor(toolContext);
            Instant from = ChannelEvidenceSupport.optionalInstant(toolContext, "from");
            Instant to = ChannelEvidenceSupport.optionalInstant(toolContext, "to");
            ChannelEvidenceSupport.requireTimeRange(from, to);

            context.registry().thread(threadId).orElseThrow(() ->
                    ChannelToolSupport.failure(ChannelToolSupport.THREAD_NOT_FOUND,
                            "logical thread 不存在"));
            StatusCounts counts = ChannelEvidenceSupport.readCounts(
                    context.observations(), threadId);
            ObservationPage page = ChannelEvidenceSupport.readPage(context.observations(),
                    threadId, from, to, cursor, limit).page();

            ObjectNode root = ChannelToolSupport.object();
            root.put("action", name());
            root.put("threadId", threadId);
            root.put("scope", "local_observation_store");
            root.put("platformComplete", false);
            root.put("available", true);
            root.put("unavailable", false);
            root.put("empty", counts.observations() == 0);
            root.put("pageEmpty", page.items().isEmpty());
            root.put("threadObservationCount", counts.observations());
            root.put("returned", page.items().size());
            root.put("hasMore", page.hasMore());
            root.put("containsBody", false);
            root.put("containsSegments", false);
            root.put("containsContentHash", false);
            root.put("containsRawUrls", false);
            root.put("notice", "仅列出本地 observation 元数据；不含正文、segments、URL、无盐正文摘要或"
                    + "凭据，也不代表 QQ 平台全量证据。");
            root.set("statusCounts", ChannelEvidenceSupport.statusCountsNode(counts));

            Map<String, Long> channelCounts = new LinkedHashMap<>();
            channelCounts.put(QqChannelObservationStore.Channel.ONEBOT.wireName(), 0L);
            channelCounts.put(QqChannelObservationStore.Channel.QQ_OFFICIAL.wireName(), 0L);
            Map<String, SourceAddress> validatedSources = new LinkedHashMap<>();
            ArrayNode observations = root.putArray("observations");
            for (Observation observation : page.items()) {
                ChannelEvidenceSupport.validateSourceCached(context.registry(),
                        validatedSources, observation.rawSource());
                channelCounts.merge(observation.channel().wireName(), 1L, Long::sum);
                CorrelationPair pair = ChannelEvidenceSupport.readPair(context.observations(),
                        observation.rawSource(), observation.channelMessageId());
                ObjectNode item = ChannelEvidenceSupport.observationEvidenceNode(observation);
                item.set("correlation", ChannelEvidenceSupport.correlationNode(observation, pair,
                        ChannelEvidenceSupport.readCorrelationHistory(context.observations(),
                                observation.rawSource(), observation.channelMessageId())));
                item.put("sourceAccountVerified", true);
                observations.add(item);
            }
            ObjectNode channels = root.putObject("pageChannelCounts");
            channelCounts.forEach(channels::put);
            if (page.nextCursor().isPresent()) {
                root.set("nextCursor", ChannelEvidenceSupport.cursorNode(
                        page.nextCursor().orElseThrow()));
            } else {
                root.putNull("nextCursor");
            }
            return ChannelToolSupport.ok(root);
        });
    }

    private static Map<String, Object> cursorSchema() {
        return Map.of(
                "type", "object",
                "description", "上一页返回的 nextCursor；不传表示第一页",
                "properties", Map.of(
                        "messageAt", Map.of("type", "string", "format", "date-time"),
                        "observationId", Map.of("type", "string")),
                "required", List.of("messageAt", "observationId"),
                "additionalProperties", false);
    }
}
