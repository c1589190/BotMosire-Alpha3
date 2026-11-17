package io.mosire.bma3.app.channel.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.app.channel.QqChannelRegistry;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.Observation;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.ObservationCursor;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.ObservationPage;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.StatusCounts;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.source.SourceAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只读 logical-thread 统一时间线：observation 元数据 + 按 raw source/localSeq 精确取本地 board。 */
final class QqThreadHistoryTool implements AgentTool {

    private static final int MAX_LIMIT = 200;

    private final ChannelEvidenceToolContext context;

    QqThreadHistoryTool(ChannelEvidenceToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_thread_history";
    }

    @Override
    public String description() {
        return "按 messageAt 分页读取 logical thread 的本地统一事实时间线。每条先返回 channel/"
                + "account/raw source、observation ID、route/correlation evidence 元数据，再按"
                + "raw source + boardLocalSeq 精确读取本地消息板正文；核对 localSeq/source，"
                + "缺失或错配分别标 MISSING_BOARD/BOARD_MISMATCH，不造事实。includeSegments=true "
                + "才返回脱敏 segments；默认不返回 segment URL/敏感 payload。不是 QQ 平台全量历史。"
                + "source 解绑后仍可读取既有本地 observation 与 board。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("threadId", Map.of(
                "type", "string",
                "description", "logical thread ID；解绑后仍可查询本地历史"));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "本页 observation/board 行数上限",
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
        properties.put("includeSegments", Map.of(
                "type", "boolean",
                "description", "默认 false；true 时只返回脱敏 segments（不含 URL/token/secret）",
                "default", false));
        return ChannelToolSupport.schema(properties, List.of("threadId"),
                "本地 observation+board 时间线分页；正文只来自精确匹配的 board 行，"
                        + "默认不返回 segments/URL。");
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        return ChannelEvidenceSupport.execute(() -> {
            ChannelEvidenceSupport.requireOnly(toolContext,
                    "threadId", "limit", "cursor", "from", "to", "includeSegments");
            String threadId = ChannelEvidenceSupport.requiredThreadId(toolContext);
            int limit = ChannelEvidenceSupport.optionalLimit(toolContext, 50, MAX_LIMIT);
            ObservationCursor cursor = ChannelEvidenceSupport.optionalCursor(toolContext);
            Instant from = ChannelEvidenceSupport.optionalInstant(toolContext, "from");
            Instant to = ChannelEvidenceSupport.optionalInstant(toolContext, "to");
            boolean includeSegments = ChannelEvidenceSupport.optionalIncludeSegments(toolContext);
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
            root.put("scope", "local_board_and_observation");
            root.put("platformComplete", false);
            root.put("available", true);
            root.put("unavailable", false);
            root.put("empty", counts.observations() == 0);
            root.put("pageEmpty", page.items().isEmpty());
            root.put("threadObservationCount", counts.observations());
            root.put("returned", page.items().size());
            root.put("hasMore", page.hasMore());
            root.put("includeSegments", includeSegments);
            root.put("segmentUrlFieldsExposed", false);
            root.put("notice", "只读本地 observation 与消息板事实；不是 QQ 平台全量历史。"
                    + "缺失 board 行只标 MISSING_BOARD，不补造正文。");
            root.set("statusCounts", ChannelEvidenceSupport.statusCountsNode(counts));

            Map<String, SourceAddress> validatedSources = new LinkedHashMap<>();
            int found = 0;
            int missing = 0;
            int mismatch = 0;
            ArrayNode timeline = root.putArray("timeline");
            for (Observation observation : page.items()) {
                SourceAddress source = ChannelEvidenceSupport.validateSourceCached(
                        context.registry(), validatedSources, observation.rawSource());
                ObjectNode item = ChannelEvidenceSupport.observationEvidenceNode(observation);
                BoardLookup lookup = lookupBoard(source, observation, includeSegments);
                item.set("board", lookup.node);
                if ("FOUND".equals(lookup.status)) {
                    found++;
                } else if ("MISSING_BOARD".equals(lookup.status)) {
                    missing++;
                } else {
                    mismatch++;
                }
                timeline.add(item);
            }
            ObjectNode boardSummary = root.putObject("pageBoardSummary");
            boardSummary.put("found", found);
            boardSummary.put("missing", missing);
            boardSummary.put("mismatch", mismatch);
            boardSummary.put("urlsExposed", false);
            if (page.nextCursor().isPresent()) {
                root.set("nextCursor", ChannelEvidenceSupport.cursorNode(
                        page.nextCursor().orElseThrow()));
            } else {
                root.putNull("nextCursor");
            }
            return ChannelToolSupport.ok(root);
        });
    }

    private BoardLookup lookupBoard(SourceAddress source, Observation observation,
                                    boolean includeSegments) {
        if (observation.boardLocalSeq() <= 0) {
            return new BoardLookup("MISSING_BOARD",
                    ChannelEvidenceSupport.missingBoardNode("MISSING_BOARD"));
        }
        List<Message> rows;
        try {
            rows = context.board().read(source, observation.boardLocalSeq() - 1L, 1);
        } catch (RuntimeException failure) {
            throw ChannelToolSupport.failure(ChannelEvidenceSupport.BOARD_UNAVAILABLE,
                    "本地消息板暂不可用，拒绝返回不完整历史");
        }
        if (rows == null || rows.isEmpty()) {
            return new BoardLookup("MISSING_BOARD",
                    ChannelEvidenceSupport.missingBoardNode("MISSING_BOARD"));
        }
        Message message = rows.getFirst();
        if (message.localSeq() != observation.boardLocalSeq()
                || !message.inbound().source().format().equals(observation.rawSource())) {
            return new BoardLookup("BOARD_MISMATCH",
                    ChannelEvidenceSupport.missingBoardNode("BOARD_MISMATCH"));
        }
        return new BoardLookup("FOUND", ChannelEvidenceSupport.boardMessageNode(
                message, observation, includeSegments));
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

    private record BoardLookup(String status, ObjectNode node) {
    }
}
