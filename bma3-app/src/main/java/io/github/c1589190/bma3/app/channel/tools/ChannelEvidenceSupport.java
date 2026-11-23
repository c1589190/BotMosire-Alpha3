package io.github.c1589190.bma3.app.channel.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.RegistryException;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.CorrelationAuditEntry;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.CorrelationPair;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.Observation;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.ObservationCursor;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.StatusCounts;
import io.github.c1589190.bma3.app.channel.store.QqChannelObservationStore.StoreException;
import io.github.c1589190.bma3.core.media.MediaId;
import io.github.c1589190.bma3.core.message.InboundMessage;
import io.github.c1589190.bma3.core.message.Message;
import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.source.SourceAddress;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** compare/history 的共享只读逻辑：安全解析、store/board 错误映射与脱敏 segment。 */
final class ChannelEvidenceSupport {

    static final String OBSERVATION_UNAVAILABLE = "OBSERVATION_UNAVAILABLE";
    static final String OBSERVATION_READ_FAILED = "OBSERVATION_READ_FAILED";
    static final String BOARD_UNAVAILABLE = "BOARD_UNAVAILABLE";
    static final String INVALID_CURSOR = "INVALID_CURSOR";
    static final String INVALID_TIME_RANGE = "INVALID_TIME_RANGE";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final int MAX_CORRELATION_HISTORY = 20;
    private static final Set<String> SAFE_SEGMENT_DATA_FIELDS = Set.of(
            "id", "name", "qq", "subtype", "count", "fileid");
    private static final Set<String> MEDIA_SEGMENT_TYPES = Set.of(
            "image", "record", "video", "file", "forward", "json", "xml");

    private ChannelEvidenceSupport() {
    }

    static ToolResult execute(Supplier<ToolResult> action) {
        try {
            return action.get();
        } catch (ChannelToolSupport.ToolFailure failure) {
            return ToolResult.error(failure.code(), failure.getMessage());
        } catch (RegistryException failure) {
            return ChannelToolSupport.registryFailure(failure.code());
        } catch (StoreException failure) {
            return storeFailure(failure.code());
        } catch (RuntimeException failure) {
            return ToolResult.error(INTERNAL_ERROR, "本地 channel evidence 读取失败");
        }
    }

    static ToolResult storeFailure(QqChannelObservationStore.ErrorCode code) {
        String resultCode;
        String message;
        switch (code) {
            case INVALID_ARGUMENT -> {
                resultCode = "INVALID_ARGUMENT";
                message = "参数非法";
            }
            case INVALID_THREAD_ID -> {
                resultCode = "INVALID_THREAD_ID";
                message = "threadId 格式非法";
            }
            case INVALID_RAW_SOURCE -> {
                resultCode = "INVALID_RAW_SOURCE";
                message = "observation 的 raw source 格式非法";
            }
            case INVALID_CHANNEL -> {
                resultCode = "INVALID_CHANNEL";
                message = "observation 的 channel 非法";
            }
            case OBSERVATION_NOT_FOUND -> {
                resultCode = "OBSERVATION_NOT_FOUND";
                message = "本地 observation 不存在";
            }
            case CURSOR_REGRESSION, CURSOR_EXHAUSTED -> {
                resultCode = INVALID_CURSOR;
                message = "分页 cursor 非法或已失效";
            }
            case STORE_CLOSED, STORAGE_ERROR -> {
                resultCode = OBSERVATION_UNAVAILABLE;
                message = "本地 observation store 暂不可用";
            }
            default -> {
                resultCode = OBSERVATION_READ_FAILED;
                message = "本地 observation 读取失败";
            }
        }
        return ToolResult.error(resultCode, message);
    }

    static ObservationCursor optionalCursor(ToolContext toolContext) {
        Object raw = toolContext.arguments().get("cursor");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)
                || !map.keySet().equals(Set.of("messageAt", "observationId"))) {
            throw ChannelToolSupport.failure(INVALID_CURSOR,
                    "cursor 必须是 {messageAt, observationId} 对象");
        }
        Object time = map.get("messageAt");
        Object id = map.get("observationId");
        if (!(time instanceof String timeText) || !(id instanceof String observationId)
                || observationId.isBlank()) {
            throw ChannelToolSupport.failure(INVALID_CURSOR, "cursor 字段格式非法");
        }
        Instant messageAt;
        try {
            messageAt = Instant.parse(timeText);
        } catch (DateTimeParseException failure) {
            throw ChannelToolSupport.failure(INVALID_CURSOR, "cursor.messageAt 不是 ISO instant");
        }
        return new ObservationCursor(messageAt, observationId);
    }

    static Instant optionalInstant(ToolContext toolContext, String name) {
        Object raw = toolContext.arguments().get(name);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            throw ChannelToolSupport.invalid(name + " 必须是 ISO instant 字符串");
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException failure) {
            throw ChannelToolSupport.invalid(name + " 不是合法 ISO instant");
        }
    }

    static void requireTimeRange(Instant fromInclusive, Instant toExclusive) {
        if (fromInclusive != null && toExclusive != null
                && !fromInclusive.isBefore(toExclusive)) {
            throw ChannelToolSupport.failure(INVALID_TIME_RANGE, "from 必须早于 to");
        }
    }

    static boolean optionalIncludeSegments(ToolContext toolContext) {
        Object raw = toolContext.arguments().get("includeSegments");
        if (raw == null) {
            return false;
        }
        if (!(raw instanceof Boolean value)) {
            throw ChannelToolSupport.invalid("includeSegments 必须是布尔值");
        }
        return value;
    }

    /**
     * 旧 observation 的 raw source 必须仍带显式 account 段。registry 的 sourceGate 读取入口
     * 复用与 registry 写路径相同的三段/平台/账号校验，但不要求当前仍有 active binding。
     */
    static SourceAddress validatedSource(QqChannelRegistry registry, String rawSource) {
        registry.sourceGate(rawSource);
        SourceAddress address = SourceAddress.parse(rawSource);
        if (address.segments().size() < 2 || address.accountId().isBlank()) {
            throw ChannelToolSupport.failure("INVALID_RAW_SOURCE",
                    "旧 raw source 缺少显式 account 段");
        }
        return address;
    }

    static ObjectNode cursorNode(ObservationCursor cursor) {
        ObjectNode node = ChannelToolSupport.object();
        node.put("messageAt", cursor.messageAt().toString());
        node.put("observationId", cursor.observationId());
        return node;
    }

    static ObjectNode statusCountsNode(StatusCounts counts) {
        ObjectNode node = ChannelToolSupport.object();
        node.put("threadId", counts.threadId());
        node.put("observations", counts.observations());
        ObjectNode routes = node.putObject("routeCounts");
        for (QqChannelObservationStore.RouteState state
                : QqChannelObservationStore.RouteState.values()) {
            routes.put(state.name(), counts.routeCounts().getOrDefault(state, 0L));
        }
        ObjectNode correlations = node.putObject("correlationCounts");
        for (QqChannelObservationStore.CorrelationState state
                : QqChannelObservationStore.CorrelationState.values()) {
            correlations.put(state.name(), counts.correlationCounts().getOrDefault(state, 0L));
        }
        return node;
    }

    static ObjectNode correlationNode(Observation observation,
                                      CorrelationPair pair,
                                      List<CorrelationAuditEntry> history) {
        ObjectNode node = ChannelToolSupport.object();
        node.put("state", observation.correlationState().name());
        node.put("pairPresent", pair != null);
        if (pair == null) {
            node.putNull("peerObservationId");
            node.putNull("pairRevision");
            node.putNull("pairCreatedAt");
            node.putNull("pairUpdatedAt");
        } else {
            String peer = pair.observationAId().equals(observation.observationId())
                    ? pair.observationBId() : pair.observationAId();
            node.put("peerObservationId", peer);
            node.put("pairRevision", pair.revision());
            node.put("pairCreatedAt", pair.createdAt().toString());
            node.put("pairUpdatedAt", pair.updatedAt().toString());
        }
        node.put("historyCount", history.size());
        boolean truncated = history.size() > MAX_CORRELATION_HISTORY;
        node.put("historyTruncated", truncated);
        ArrayNode entries = node.putArray("history");
        int end = Math.min(history.size(), MAX_CORRELATION_HISTORY);
        for (int index = 0; index < end; index++) {
            CorrelationAuditEntry entry = history.get(index);
            ObjectNode row = entries.addObject();
            row.put("action", entry.action().name());
            if (entry.fromState().isPresent()) {
                row.put("fromState", entry.fromState().orElseThrow().name());
            } else {
                row.putNull("fromState");
            }
            if (entry.toState().isPresent()) {
                row.put("toState", entry.toState().orElseThrow().name());
            } else {
                row.putNull("toState");
            }
            row.put("changedAt", entry.changedAt().toString());
        }
        return node;
    }

    static ObjectNode observationEvidenceNode(Observation observation) {
        ObjectNode node = ChannelToolSupport.object();
        node.put("observationId", observation.observationId());
        node.put("channel", observation.channel().wireName());
        node.put("accountId", observation.accountId());
        node.put("rawSource", observation.rawSource());
        node.put("channelMessageId", observation.channelMessageId());
        node.put("boardLocalSeq", observation.boardLocalSeq());
        node.put("direction", observation.direction().name());
        node.put("messageAt", observation.messageAt().toString());
        node.put("observedAt", observation.observedAt().toString());
        node.put("priority", observation.priority());
        node.put("timeReliability", observation.timeReliability().name());
        node.put("contentIntegrity", observation.contentIntegrity().name());
        node.put("identityStatus", observation.identityStatus().name());
        node.put("identityRevision", observation.identityRevision());
        node.put("routeState", observation.routeState().name());
        node.put("routeReason", observation.routeReason().name());
        node.put("routeRevision", observation.routeRevision());
        node.put("correlationState", observation.correlationState().name());
        node.put("correlationRevision", observation.correlationRevision());
        if (observation.peerObservationId().isPresent()) {
            node.put("peerObservationId", observation.peerObservationId().orElseThrow());
        } else {
            node.putNull("peerObservationId");
        }
        ObjectNode evidence = node.putObject("evidence");
        evidence.put("bodyFree", true);
        evidence.put("routeState", observation.routeState().name());
        evidence.put("routeReason", observation.routeReason().name());
        evidence.put("identityStatus", observation.identityStatus().name());
        evidence.put("correlationState", observation.correlationState().name());
        evidence.put("correlationRevision", observation.correlationRevision());
        if (observation.peerObservationId().isPresent()) {
            evidence.put("peerObservationId", observation.peerObservationId().orElseThrow());
        } else {
            evidence.putNull("peerObservationId");
        }

        ObjectNode summary = node.putObject("summary");
        summary.put("bodyFree", true);
        summary.put("direction", observation.direction().name());
        summary.put("priority", observation.priority());
        summary.put("timeReliability", observation.timeReliability().name());
        summary.put("contentIntegrity", observation.contentIntegrity().name());
        summary.put("identityStatus", observation.identityStatus().name());
        summary.put("hasBody", false);
        summary.put("hasBodyHash", false);
        return node;
    }

    static ObjectNode boardMessageNode(Message message, Observation observation,
                                       boolean includeSegments) {
        ObjectNode board = ChannelToolSupport.object();
        board.put("boardStatus", "FOUND");
        board.put("localSeq", message.localSeq());
        board.put("source", message.inbound().source().format());
        board.put("messageId", message.inbound().messageId());
        board.put("at", message.inbound().at().toString());
        board.put("direction", message.inbound().direction().name());
        ObjectNode sender = board.putObject("sender");
        sender.put("id", message.inbound().senderId());
        sender.put("name", message.inbound().senderName());
        sender.put("card", message.inbound().senderCard());
        sender.put("role", message.inbound().senderRole());
        board.put("text", messageText(message));
        if (includeSegments) {
            ArrayNode segments = board.putArray("segments");
            List<Segment> source = message.inbound().segments();
            String lookupId = message.externalMessageId().isBlank()
                    ? message.inbound().messageId() : message.externalMessageId();
            for (int index = 0; index < source.size(); index++) {
                Segment segment = source.get(index);
                ObjectNode item = segments.addObject();
                item.put("type", segment.type());
                item.put("text", segment.text());
                if (MEDIA_SEGMENT_TYPES.contains(segment.type())) {
                    item.put("fileId", MediaId.of(message.inbound().source().format(),
                            lookupId, index).encode());
                }
                ObjectNode data = item.putObject("data");
                boolean redacted = false;
                for (Map.Entry<String, String> field : segment.data().entrySet()) {
                    if (safeSegmentData(field.getKey(), field.getValue())) {
                        data.put(field.getKey(), field.getValue());
                    } else {
                        redacted = true;
                    }
                }
                item.put("sensitiveFieldsRedacted", redacted);
            }
        }
        board.put("urlsExposed", false);
        return board;
    }

    static ObjectNode missingBoardNode(String status) {
        ObjectNode node = ChannelToolSupport.object();
        node.put("boardStatus", status);
        node.putNull("localSeq");
        node.putNull("source");
        node.put("textIncluded", false);
        node.put("segmentsIncluded", false);
        return node;
    }

    private static String messageText(Message message) {
        StringBuilder text = new StringBuilder();
        for (Segment segment : message.inbound().segments()) {
            if (segment.text() != null && !segment.text().isBlank()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(segment.text());
            }
        }
        return text.toString();
    }

    private static boolean safeSegmentData(String key, String value) {
        if (key == null || value == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        if (!SAFE_SEGMENT_DATA_FIELDS.contains(normalized)) {
            return false;
        }
        String lowerValue = value.toLowerCase(Locale.ROOT);
        return !lowerValue.contains("http://")
                && !lowerValue.contains("https://")
                && !lowerValue.contains("base64://")
                && !lowerValue.contains("data:")
                && !lowerValue.contains("file://")
                && !lowerValue.contains("token")
                && !lowerValue.contains("secret")
                && !lowerValue.contains("authorization");
    }

    static void requireOnly(ToolContext context, String... allowed) {
        Set<String> accepted = Set.of(allowed);
        for (String key : context.arguments().keySet()) {
            if (!accepted.contains(key)) {
                throw ChannelToolSupport.invalid(
                        "参数不受支持；请只使用 schema 中声明的字段");
            }
        }
    }

    static String requiredThreadId(ToolContext context) {
        Object raw = context.arguments().get("threadId");
        if (!(raw instanceof String value) || value.isBlank()) {
            throw ChannelToolSupport.invalid("threadId 必须是非空字符串");
        }
        return value;
    }

    static int optionalLimit(ToolContext context, int fallback, int max) {
        Object raw = context.arguments().get("limit");
        if (raw == null) {
            return fallback;
        }
        long value;
        if (raw instanceof Integer integer) {
            value = integer;
        } else if (raw instanceof Long number) {
            value = number;
        } else {
            throw ChannelToolSupport.invalid("limit 必须是整数");
        }
        if (value < 1 || value > max) {
            throw ChannelToolSupport.invalid("limit 超出允许范围");
        }
        return Math.toIntExact(value);
    }

    static SourceAddress validateSourceCached(QqChannelRegistry registry,
                                               Map<String, SourceAddress> cache,
                                               String rawSource) {
        SourceAddress existing = cache.get(rawSource);
        if (existing != null) {
            return existing;
        }
        SourceAddress parsed = validatedSource(registry, rawSource);
        cache.put(rawSource, parsed);
        return parsed;
    }

    static ObservationPageRead readPage(QqChannelObservationStore store,
                                        String threadId,
                                        Instant from,
                                        Instant to,
                                        ObservationCursor cursor,
                                        int limit) {
        try {
            return new ObservationPageRead(store.observationsForThread(
                    threadId, from, to, cursor, limit));
        } catch (StoreException failure) {
            throw ChannelToolSupport.failure(storeCode(failure.code()),
                    storeMessage(failure.code()));
        }
    }

    static StatusCounts readCounts(QqChannelObservationStore store, String threadId) {
        try {
            return store.statusCounts(threadId);
        } catch (StoreException failure) {
            throw ChannelToolSupport.failure(storeCode(failure.code()),
                    storeMessage(failure.code()));
        }
    }

    static CorrelationPair readPair(QqChannelObservationStore store,
                                    String rawSource, String channelMessageId) {
        try {
            return store.findCorrelationPair(rawSource, channelMessageId).orElse(null);
        } catch (StoreException failure) {
            throw ChannelToolSupport.failure(storeCode(failure.code()),
                    storeMessage(failure.code()));
        }
    }

    static List<CorrelationAuditEntry> readCorrelationHistory(
            QqChannelObservationStore store, String rawSource, String channelMessageId) {
        try {
            return store.correlationHistory(rawSource, channelMessageId);
        } catch (StoreException failure) {
            throw ChannelToolSupport.failure(storeCode(failure.code()),
                    storeMessage(failure.code()));
        }
    }

    private static String storeCode(QqChannelObservationStore.ErrorCode code) {
        return switch (code) {
            case INVALID_ARGUMENT -> "INVALID_ARGUMENT";
            case INVALID_THREAD_ID -> "INVALID_THREAD_ID";
            case INVALID_RAW_SOURCE -> "INVALID_RAW_SOURCE";
            case INVALID_CHANNEL -> "INVALID_CHANNEL";
            case OBSERVATION_NOT_FOUND -> "OBSERVATION_NOT_FOUND";
            case CURSOR_REGRESSION, CURSOR_EXHAUSTED -> INVALID_CURSOR;
            case STORE_CLOSED, STORAGE_ERROR -> OBSERVATION_UNAVAILABLE;
            default -> OBSERVATION_READ_FAILED;
        };
    }

    private static String storeMessage(QqChannelObservationStore.ErrorCode code) {
        return switch (code) {
            case INVALID_ARGUMENT -> "参数非法";
            case INVALID_THREAD_ID -> "threadId 格式非法";
            case INVALID_RAW_SOURCE -> "observation 的 raw source 格式非法";
            case INVALID_CHANNEL -> "observation 的 channel 非法";
            case OBSERVATION_NOT_FOUND -> "本地 observation 不存在";
            case CURSOR_REGRESSION, CURSOR_EXHAUSTED -> "分页 cursor 非法或已失效";
            case STORE_CLOSED, STORAGE_ERROR -> "本地 observation store 暂不可用";
            default -> "本地 observation 读取失败";
        };
    }

    /** 最小 page 包装，避免把 store 的 cursor 直接暴露给工具 schema。 */
    record ObservationPageRead(QqChannelObservationStore.ObservationPage page) {
        ObservationPageRead {
            Objects.requireNonNull(page, "page");
        }
    }
}
