package io.mosire.bma3.qqofficial.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.qqofficial.gateway.GatewayDispatch;
import io.mosire.bma3.qqofficial.gateway.GatewayEventSink;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes QQ official Gateway JSON dispatch frames into the independent event model.
 *
 * <p>Input is the raw envelope ({@code {"id","op","s","t","d"}}). Non-dispatch control frames
 * (Hello, heartbeat ACK, Reconnect, Invalid Session) return {@link Optional#empty()}; an
 * {@code op=0} dispatch becomes exactly one of the sealed {@link OfficialQqDispatch} records.
 * The transport can inject a {@link OfficialQqDispatchHandler} through
 * {@link #dispatch(String, OfficialQqDispatchHandler)} or via the direct {@link GatewayDispatch}
 * bridge ({@link #decode(GatewayDispatch)}, {@link #dispatch(GatewayDispatch,
 * OfficialQqDispatchHandler)}, {@link #gatewayEventSink(OfficialQqDispatchHandler)}) and therefore
 * never needs to know official JSON shapes.</p>
 *
 * <p>Security boundaries: only modeled fields are copied out of the payload; unknown events
 * retain just envelope metadata and payload length; open-ended card/nested data is recursively
 * redacted; attachment URLs stay in {@link Segment#data()}; and no log line includes message
 * content or raw JSON.</p>
 */
public final class OfficialQqEventDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(OfficialQqEventDecoder.class);
    public static final int MAX_MESSAGE_TEXT_CHARS = 64_000;
    public static final int MAX_SEGMENTS = 256;
    public static final int MAX_ATTACHMENTS = 100;
    public static final int MAX_MENTIONS = 200;
    public static final int MAX_NESTED_ELEMENTS = 512;
    public static final int MAX_UNKNOWN_PAYLOAD_LENGTH = 65_536;
    private static final int MAX_CARD_TEXT_CHARS = 500;
    private static final int MAX_CARD_RAW_CHARS = 8192;
    private static final int MAX_CARD_SEARCH_DEPTH = 6;
    private static final int MAX_CARD_SEARCH_NODES = 256;
    public static final int MAX_NESTING_DEPTH = 8;
    private static final int MAX_UNKNOWN_SCAN_DEPTH = 32;

    private final ObjectMapper mapper;
    private final Clock clock;
    private final Consumer<String> warningSink;

    public OfficialQqEventDecoder() {
        this(Clock.systemUTC());
    }

    public OfficialQqEventDecoder(Clock clock) {
        this(clock, message -> LOG.warn(message));
    }

    /** Test seam: production callers use the Clock-only constructor and SLF4J. */
    OfficialQqEventDecoder(Clock clock, Consumer<String> warningSink) {
        this.mapper = new ObjectMapper();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }

    /**
     * Decodes one Gateway frame.
     *
     * @return a dispatch for {@code op=0}; empty for a non-dispatch/control frame
     * @throws OfficialQqDecodeException when the frame is malformed; its message never echoes
     *     the input, which may contain secrets
     */
    public Optional<OfficialQqDispatch> decode(String gatewayJson) {
        Instant receivedAt = clock.instant();
        JsonNode root;
        try {
            root = mapper.readTree(gatewayJson);
        } catch (Exception failure) {
            throw OfficialQqDecodeException.malformed(failure);
        }
        if (root == null || !root.isObject()) {
            throw OfficialQqDecodeException.notAnObject();
        }
        int op = root.path("op").asInt(-1);
        if (op != 0) {
            return Optional.empty();
        }
        String eventType = text(root, "t");
        if (eventType.isBlank()) {
            throw OfficialQqDecodeException.missingEventType();
        }
        String eventId = text(root, "id");
        long sequence = root.path("s").asLong(-1L);
        JsonNode data = root.path("d");
        String traceId = firstNonBlank(text(root, "trace_id"), text(data, "trace_id"));
        return Optional.of(decodeEnvelope(
                eventId, eventType, sequence, receivedAt, data, traceId));
    }

    /**
     * Direct bridge for the gateway package's already-parsed dispatch. No JSON is serialized or
     * logged by this bridge.
     */
    public Optional<OfficialQqDispatch> decode(GatewayDispatch gatewayDispatch) {
        Objects.requireNonNull(gatewayDispatch, "gatewayDispatch");
        String traceId = text(gatewayDispatch.data(), "trace_id");
        return Optional.of(decodeEnvelope(
                gatewayDispatch.eventId(),
                gatewayDispatch.eventType(),
                gatewayDispatch.sequence(),
                gatewayDispatch.receivedAt(),
                gatewayDispatch.data(),
                traceId));
    }

    private OfficialQqDispatch decodeEnvelope(
            String eventId,
            String eventType,
            long sequence,
            Instant receivedAt,
            JsonNode data,
            String traceId) {
        if (eventType == null || eventType.isBlank()) {
            throw OfficialQqDecodeException.missingEventType();
        }
        OfficialQqEventType type = OfficialQqEventType.fromWire(eventType);
        if (type != null && type.isMessage()) {
            OfficialQqEvent event = decodeMessagePayload(
                    eventId, eventType, sequence, receivedAt, data, traceId);
            if ("GROUP_AT_MESSAGE_CREATE".equals(eventType) && !event.atBot()) {
                event = new OfficialQqEvent(
                        event.eventId(), event.eventType(), event.messageId(), event.groupOpenid(),
                        event.userOpenid(), event.memberOpenid(), event.username(),
                        event.memberNickname(), event.memberRole(), event.at(), event.segments(),
                        event.refMsgIdx(), event.traceId(), event.msgIdx(), true, event.atAll(),
                        event.timestampFallback(), event.sequence(), event.receivedAt(),
                        event.contentComplete());
            }
            return event;
        }
        if (type != null && type.isSystem()) {
            return decodeSystemPayload(
                    eventId, eventType, sequence, receivedAt, data, traceId);
        }
        int payloadLength = boundedJsonLength(data);
        return new OfficialQqUnknownEvent(
                eventId, eventType, sequence, receivedAt, payloadLength);
    }

    /** Convenience for tests/ingest callers that only accept message events. */
    public Optional<OfficialQqEvent> decodeMessage(String gatewayJson) {
        return decode(gatewayJson).filter(OfficialQqEvent.class::isInstance)
                .map(OfficialQqEvent.class::cast);
    }

    /** Convenience for tests/ingest callers that only accept directory/switch events. */
    public Optional<OfficialQqSystemEvent> decodeSystem(String gatewayJson) {
        return decode(gatewayJson).filter(OfficialQqSystemEvent.class::isInstance)
                .map(OfficialQqSystemEvent.class::cast);
    }

    /** Direct message-only convenience for a parsed gateway dispatch. */
    public Optional<OfficialQqEvent> decodeMessage(GatewayDispatch gatewayDispatch) {
        return decode(gatewayDispatch).filter(OfficialQqEvent.class::isInstance)
                .map(OfficialQqEvent.class::cast);
    }

    /** Direct system-only convenience for a parsed gateway dispatch. */
    public Optional<OfficialQqSystemEvent> decodeSystem(GatewayDispatch gatewayDispatch) {
        return decode(gatewayDispatch).filter(OfficialQqSystemEvent.class::isInstance)
                .map(OfficialQqSystemEvent.class::cast);
    }

    /**
     * Decodes and injects the result into a caller-supplied handler.
     *
     * @return true when a dispatch was emitted, false for a non-dispatch control frame
     */
    public boolean dispatch(String gatewayJson, OfficialQqDispatchHandler handler) {
        Objects.requireNonNull(handler, "handler");
        Optional<OfficialQqDispatch> dispatch = decode(gatewayJson);
        dispatch.ifPresent(handler::handle);
        return dispatch.isPresent();
    }

    /** Direct handler bridge for a parsed {@link GatewayDispatch}. */
    public boolean dispatch(
            GatewayDispatch gatewayDispatch, OfficialQqDispatchHandler handler) {
        Objects.requireNonNull(handler, "handler");
        Optional<OfficialQqDispatch> dispatch = decode(gatewayDispatch);
        dispatch.ifPresent(handler::handle);
        return dispatch.isPresent();
    }

    /**
     * Returns a {@link GatewayEventSink} suitable for
     * {@code OfficialQqGatewayClient.Builder.eventSink(...)}; the gateway does not need to
     * serialize dispatch data back to JSON.
     */
    public GatewayEventSink gatewayEventSink(OfficialQqDispatchHandler handler) {
        Objects.requireNonNull(handler, "handler");
        return gatewayDispatch -> dispatch(gatewayDispatch, handler);
    }

    private OfficialQqEvent decodeMessagePayload(
            String eventId,
            String eventType,
            long sequence,
            Instant receivedAt,
            JsonNode data,
            String envelopeTraceId) {
        JsonNode payload = objectOrEmpty(data);
        JsonNode author = payload.path("author");
        Timestamp timestamp = parseTimestamp(payload, receivedAt);
        String traceId = firstNonBlank(
                envelopeTraceId, text(payload, "trace_id"), text(author, "trace_id"));
        String messageId = firstNonBlank(text(payload, "id"), text(payload, "message_id"));
        if (messageId.isBlank()) {
            throw OfficialQqDecodeException.missingMessageId();
        }
        try {
            messageId = OfficialQqSourceIds.requireMessageId(messageId);
        } catch (IllegalArgumentException invalid) {
            throw OfficialQqDecodeException.invalidMessageId();
        }

        ReferenceContext references = new ReferenceContext();
        readReferences(payload, references);

        List<Segment> segments = new ArrayList<>();
        MessageBudget budget = new MessageBudget();
        MessageFlags flags = new MessageFlags();
        String rawContent = text(payload, "content");
        if (rawContent.length() > MAX_MESSAGE_TEXT_CHARS) {
            throw OfficialQqDecodeException.contentTooLarge();
        }
        String content = OfficialQqSecretRedactor.redactText(rawContent);
        boolean contentComplete = contentComplete(payload, rawContent, content);
        if (!content.isBlank()) {
            addTextSegment(budget, segments, content);
        }
        appendMentions(payload.path("mentions"), segments, flags, budget);
        appendAttachments(payload.path("attachments"), segments, budget);
        JsonNode arkData = payload.get("ark_data");
        if (arkData != null && !arkData.isNull()) {
            appendArk(arkData, segments, budget);
        }
        appendNestedElements(
                payload.path("msg_elements"), segments, flags, references, 0, budget);
        addQuoteSegment(segments, references, budget);

        String groupOpenid = firstNonBlank(
                text(payload, "group_openid"), text(payload.path("group"), "openid"));
        String userOpenid = firstNonBlank(
                text(payload, "user_openid"), text(author, "user_openid"),
                privateAuthorId(author, payload), text(payload, "openid"));
        String memberOpenid = firstNonBlank(
                text(payload, "member_openid"), text(author, "member_openid"));
        String username = OfficialQqSecretRedactor.redactText(firstNonBlank(
                text(author, "username"), text(payload, "username")));
        String memberNickname = OfficialQqSecretRedactor.redactText(firstNonBlank(
                text(author, "member_nickname"), text(author, "nickname"),
                text(payload, "member_nickname")));
        String memberRole = firstNonBlank(
                text(author, "member_role"), text(payload, "member_role"));

        return new OfficialQqEvent(
                eventId,
                eventType,
                messageId,
                groupOpenid,
                userOpenid,
                memberOpenid,
                username,
                memberNickname,
                memberRole,
                timestamp.at(),
                segments,
                references.refMsgIdx,
                cap(traceId, 512),
                references.msgIdx,
                flags.atBot,
                flags.atAll,
                timestamp.fallback(),
                sequence,
                receivedAt,
                contentComplete);
    }

    private OfficialQqSystemEvent decodeSystemPayload(
            String eventId,
            String eventType,
            long sequence,
            Instant receivedAt,
            JsonNode data,
            String envelopeTraceId) {
        JsonNode payload = objectOrEmpty(data);
        JsonNode author = payload.path("author");
        Timestamp timestamp = parseTimestamp(payload, receivedAt);
        String traceId = firstNonBlank(
                envelopeTraceId, text(payload, "trace_id"), text(author, "trace_id"));
        String groupOpenid = firstNonBlank(
                text(payload, "group_openid"), text(payload.path("group"), "openid"));
        String userOpenid = firstNonBlank(
                text(payload, "user_openid"), text(author, "user_openid"),
                privateAuthorId(author, payload), text(payload, "openid"));
        String memberOpenid = firstNonBlank(
                text(payload, "member_openid"), text(author, "member_openid"));
        String operatorOpenid = firstNonBlank(
                text(payload, "op_member_openid"), text(payload, "operator_openid"),
                text(payload, "op_user_openid"));
        String username = OfficialQqSecretRedactor.redactText(firstNonBlank(
                text(author, "username"), text(payload, "username")));
        String memberNickname = OfficialQqSecretRedactor.redactText(firstNonBlank(
                text(author, "member_nickname"), text(author, "nickname"),
                text(payload, "member_nickname")));
        return new OfficialQqSystemEvent(
                eventId,
                eventType,
                sequence,
                receivedAt,
                timestamp.at(),
                timestamp.fallback(),
                groupOpenid,
                userOpenid,
                memberOpenid,
                operatorOpenid,
                username,
                memberNickname,
                text(payload, "session_id"),
                cap(traceId, 512));
    }

    private void appendMentions(
            JsonNode mentions, List<Segment> out, MessageFlags flags, MessageBudget budget) {
        if (mentions == null || mentions.isMissingNode() || mentions.isNull()) {
            return;
        }
        if (mentions.isObject()) {
            appendMention(mentions, out, flags, budget);
            return;
        }
        if (!mentions.isArray()) {
            return;
        }
        for (JsonNode mention : mentions) {
            if (mention.isObject()) {
                appendMention(mention, out, flags, budget);
            }
        }
    }

    private void appendMention(
            JsonNode mention, List<Segment> out, MessageFlags flags, MessageBudget budget) {
        budget.addMention();

        String scope = firstNonBlank(text(mention, "scope"), text(mention, "mention_scope"));
        boolean isAll = "all".equalsIgnoreCase(scope)
                || boolValue(mention, "is_all")
                || boolValue(mention, "at_all");
        boolean isYou = boolValue(mention, "is_you")
                || boolValue(mention, "is_bot")
                || boolValue(mention, "at_bot");
        String openid = firstNonBlank(
                text(mention, "user_openid"), text(mention, "member_openid"),
                text(mention, "openid"), text(mention, "id"));
        String name = OfficialQqSecretRedactor.redactText(firstNonBlank(
                text(mention, "member_nickname"), text(mention, "nickname"),
                text(mention, "username"), text(mention, "name")));

        Map<String, String> data = new LinkedHashMap<>();
        put(data, "openid", openid);
        put(data, "name", name);
        if (isYou) {
            put(data, "is_you", "true");
            flags.atBot = true;
        }
        if (isAll) {
            put(data, "scope", "all");
            flags.atAll = true;
        } else {
            put(data, "scope", scope);
        }

        String marker;
        if (isAll) {
            marker = "@全体成员";
        } else if (!name.isBlank()) {
            marker = "@" + name;
        } else if (!openid.isBlank()) {
            marker = "@" + openid;
        } else {
            marker = "@";
        }
        addSegment(budget, out, new Segment("at", marker, data));
    }

    private void appendAttachments(
            JsonNode attachments, List<Segment> out, MessageBudget budget) {
        if (attachments == null || attachments.isMissingNode() || attachments.isNull()) {
            return;
        }
        if (attachments.isObject()) {
            appendAttachment(attachments, out, budget);
            return;
        }
        if (!attachments.isArray()) {
            return;
        }
        for (JsonNode attachment : attachments) {
            if (attachment.isObject()) {
                appendAttachment(attachment, out, budget);
            }
        }
    }

    private void appendAttachment(JsonNode attachment, List<Segment> out, MessageBudget budget) {
        budget.addAttachment();
        String kind = attachmentKind(attachment);
        String placeholder = switch (kind) {
            case "image" -> "[图片]";
            case "video" -> "[视频]";
            case "record" -> "[语音]";
            default -> "[文件]";
        };
        Map<String, String> data = new LinkedHashMap<>();
        put(data, "kind", kind);
        put(data, "url", OfficialQqSecretRedactor.sanitizeUrl(text(attachment, "url")));
        put(data, "filename", OfficialQqSecretRedactor.redactText(firstNonBlank(
                text(attachment, "filename"), text(attachment, "file_name"),
                text(attachment, "name"))));
        put(data, "content_type", text(attachment, "content_type"));
        put(data, "size", text(attachment, "size"));
        put(data, "width", text(attachment, "width"));
        put(data, "height", text(attachment, "height"));
        put(data, "file_uuid", text(attachment, "file_uuid"));
        JsonNode fileInfo = attachment.get("file_info");
        if (fileInfo != null && !fileInfo.isNull()) {
            put(data, "file_info", OfficialQqSecretRedactor.safeJson(fileInfo));
        }
        addSegment(budget, out, new Segment(kind, placeholder, data));
    }

    private void appendArk(JsonNode arkData, List<Segment> out, MessageBudget budget) {
        JsonNode card = arkData;
        if (arkData.isTextual()) {
            String raw = arkData.asText("");
            try {
                card = mapper.readTree(raw);
            } catch (Exception ignored) {
                card = null;
            }
            if (card == null || card.isNull() || card.isMissingNode()) {
                card = mapper.getNodeFactory().textNode(
                        OfficialQqSecretRedactor.redactText(raw));
            }
        }
        String title = OfficialQqSecretRedactor.redactText(firstNonBlank(
                findFirstText(card, "title", "prompt"),
                findFirstText(card, "desc", "summary")));
        String desc = OfficialQqSecretRedactor.redactText(
                findFirstText(card, "desc", "summary", "description"));
        String url = OfficialQqSecretRedactor.sanitizeUrl(
                findFirstText(card, "jump_url", "qqdocurl", "jumpUrl", "url"));
        String raw = OfficialQqSecretRedactor.safeJson(card, MAX_CARD_RAW_CHARS);

        Map<String, String> data = new LinkedHashMap<>();
        put(data, "raw", raw);
        put(data, "title", cap(title, MAX_CARD_TEXT_CHARS));
        put(data, "desc", cap(desc, MAX_CARD_TEXT_CHARS));
        put(data, "url", url);
        put(data, "appid", text(card, "appid"));
        put(data, "app", text(card, "app"));
        put(data, "bizsrc", text(card, "bizsrc"));
        put(data, "type", text(card, "type"));
        String text = title.isBlank() ? "[卡片]" : "[卡片] " + cap(title, 80);
        addSegment(budget, out, new Segment("card", text, data));
    }

    private void appendNestedElements(
            JsonNode elements,
            List<Segment> out,
            MessageFlags flags,
            ReferenceContext references,
            int depth,
            MessageBudget budget) {
        if (elements == null || elements.isMissingNode() || elements.isNull()) {
            return;
        }
        if (depth > MAX_NESTING_DEPTH) {
            throw OfficialQqDecodeException.nestingLimitExceeded();
        }
        if (elements.isArray()) {
            for (JsonNode element : elements) {
                appendNestedElements(element, out, flags, references, depth + 1, budget);
            }
            return;
        }
        if (!elements.isObject()) {
            return;
        }

        budget.addNestedElement();
        readReferences(elements, references);
        String rawContent = firstNonBlank(
                text(elements, "content"), text(elements.path("content"), "text"));
        if (rawContent.length() > MAX_MESSAGE_TEXT_CHARS) {
            throw OfficialQqDecodeException.contentTooLarge();
        }
        String content = OfficialQqSecretRedactor.redactText(rawContent);
        if (!content.isBlank()) {
            Map<String, String> data = new LinkedHashMap<>();
            put(data, "origin", "msg_elements");
            put(data, "msg_idx", text(elements, "msg_idx"));
            put(data, "ref_msg_idx", text(elements, "ref_msg_idx"));
            addTextSegment(budget, out, content, data);
        }
        appendMentions(elements.path("mentions"), out, flags, budget);
        appendAttachments(elements.path("attachments"), out, budget);
        JsonNode arkData = elements.get("ark_data");
        if (arkData != null && !arkData.isNull()) {
            appendArk(arkData, out, budget);
        }
        appendNestedElements(
                elements.path("msg_elements"), out, flags, references, depth + 1, budget);
        appendNestedElements(
                elements.path("elements"), out, flags, references, depth + 1, budget);
        appendNestedElements(
                elements.path("message_elements"), out, flags, references, depth + 1, budget);
    }

    private void addQuoteSegment(
            List<Segment> segments, ReferenceContext references, MessageBudget budget) {
        Map<String, String> data = new LinkedHashMap<>();
        put(data, "msg_idx", references.msgIdx);
        put(data, "ref_msg_idx", references.refMsgIdx);
        put(data, "message_id", references.messageReferenceId);
        if (!data.isEmpty()) {
            addSegment(budget, segments, new Segment("quote", "[引用]", data), true);
        }
    }

    private static void addTextSegment(
            MessageBudget budget, List<Segment> out, String text) {
        addTextSegment(budget, out, text, Map.of());
    }

    private static void addTextSegment(
            MessageBudget budget, List<Segment> out, String text, Map<String, String> data) {
        budget.addText(text.length());
        addSegment(budget, out, new Segment("text", text, data));
    }

    private static void addSegment(
            MessageBudget budget, List<Segment> out, Segment segment) {
        addSegment(budget, out, segment, false);
    }

    private static void addSegment(
            MessageBudget budget, List<Segment> out, Segment segment, boolean prepend) {
        budget.addSegment();
        if (prepend) {
            out.add(0, segment);
        } else {
            out.add(segment);
        }
    }

    private static int boundedJsonLength(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return 0;
        }
        LengthCounter counter = new LengthCounter(MAX_UNKNOWN_PAYLOAD_LENGTH);
        countJsonLength(data, 0, counter, 0);
        return counter.length();
    }

    private static void countJsonLength(
            JsonNode node, int depth, LengthCounter counter, int visitedNodes) {
        if (counter.full()) {
            return;
        }
        if (visitedNodes > 4096 || depth > MAX_UNKNOWN_SCAN_DEPTH) {
            counter.addRemaining();
            return;
        }
        if (node == null || node.isMissingNode() || node.isNull()) {
            counter.add(4);
            return;
        }
        if (node.isObject()) {
            counter.add(2);
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            boolean first = true;
            while (fields.hasNext() && !counter.full()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!first) {
                    counter.add(1);
                }
                first = false;
                counter.add(2L + field.getKey().length() + 1);
                countJsonLength(field.getValue(), depth + 1, counter, visitedNodes + 1);
            }
            counter.add(1);
            return;
        }
        if (node.isArray()) {
            counter.add(2);
            boolean first = true;
            for (JsonNode element : node) {
                if (counter.full()) {
                    break;
                }
                if (!first) {
                    counter.add(1);
                }
                first = false;
                countJsonLength(element, depth + 1, counter, visitedNodes + 1);
            }
            counter.add(1);
            return;
        }
        if (node.isTextual()) {
            counter.add(2L + node.textValue().length());
        } else {
            counter.add(16);
        }
    }

    private void readReferences(JsonNode node, ReferenceContext references) {
        if (node == null || !node.isObject()) {
            return;
        }
        JsonNode ext = node.path("message_scene").path("ext");
        references.msgIdx = firstNonBlank(
                references.msgIdx, text(ext, "msg_idx"), text(node, "msg_idx"),
                text(node, "msgIdx"));
        references.refMsgIdx = firstNonBlank(
                references.refMsgIdx, text(ext, "ref_msg_idx"), text(node, "ref_msg_idx"),
                text(node, "refMsgIdx"));
        JsonNode reference = node.path("message_reference");
        references.messageReferenceId = firstNonBlank(
                references.messageReferenceId, text(reference, "message_id"),
                text(reference, "id"));
    }

    private Timestamp parseTimestamp(JsonNode payload, Instant receivedAt) {
        String raw = firstNonBlank(text(payload, "timestamp"), text(payload, "time"));
        if (raw.isBlank()) {
            warningSink.accept("官方 QQ 事件 timestamp 缺失，回退接收时间");
            return new Timestamp(receivedAt, true);
        }
        try {
            return new Timestamp(Instant.parse(raw), false);
        } catch (DateTimeParseException ignored) {
            try {
                return new Timestamp(OffsetDateTime.parse(raw).toInstant(), false);
            } catch (DateTimeParseException invalid) {
                warningSink.accept("官方 QQ 事件 timestamp 非法，回退接收时间: reason="
                        + invalid.getClass().getSimpleName());
                return new Timestamp(receivedAt, true);
            }
        }
    }

    /**
     * Content completeness is deliberately conservative. A strict cross-channel MATCHED decision
     * must not claim equivalence when redaction changed the body or when the decoder did not
     * fully understand the content-bearing structure. The raw text/hash is never retained.
     */
    private static boolean contentComplete(
            JsonNode payload, String rawContent, String redactedContent) {
        if (payload == null
                || !payload.has("content")
                || !payload.get("content").isTextual()
                || !rawContent.equals(redactedContent)) {
            return false;
        }
        JsonNode messageType = payload.get("message_type");
        if (messageType != null
                && (!messageType.isIntegralNumber() || messageType.asInt(-1) != 0)) {
            return false;
        }
        JsonNode ark = payload.get("ark_data");
        if (ark != null && !ark.isNull()) {
            return false;
        }
        JsonNode elements = payload.get("msg_elements");
        if (elements != null && !elements.isNull()
                && !(elements.isArray() && elements.isEmpty())) {
            return false;
        }
        return attachmentsComplete(payload.path("attachments"))
                && mentionsComplete(payload.path("mentions"));
    }

    private static boolean attachmentsComplete(JsonNode attachments) {
        if (attachments == null || attachments.isMissingNode() || attachments.isNull()) {
            return true;
        }
        if (attachments.isObject()) {
            return attachmentComplete(attachments);
        }
        if (!attachments.isArray()) {
            return false;
        }
        for (JsonNode attachment : attachments) {
            if (!attachment.isObject() || !attachmentComplete(attachment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean attachmentComplete(JsonNode attachment) {
        String combined = (firstNonBlank(
                        text(attachment, "type"), text(attachment, "file_type"),
                        text(attachment, "kind"))
                + " " + text(attachment, "content_type")).toLowerCase(Locale.ROOT);
        if (combined.isBlank()) {
            return false;
        }
        return combined.contains("image")
                || combined.contains("video")
                || combined.contains("audio")
                || combined.contains("voice")
                || combined.contains("record")
                || combined.contains("file")
                || combined.contains("application/")
                || combined.contains("text/");
    }

    private static boolean mentionsComplete(JsonNode mentions) {
        if (mentions == null || mentions.isMissingNode() || mentions.isNull()) {
            return true;
        }
        if (mentions.isObject()) {
            return true;
        }
        if (!mentions.isArray()) {
            return false;
        }
        for (JsonNode mention : mentions) {
            if (!mention.isObject()) {
                return false;
            }
        }
        return true;
    }

    private static String attachmentKind(JsonNode attachment) {
        String hint = firstNonBlank(
                text(attachment, "type"), text(attachment, "file_type"),
                text(attachment, "kind")).toLowerCase(Locale.ROOT);
        String contentType = text(attachment, "content_type").toLowerCase(Locale.ROOT);
        String combined = hint + " " + contentType;
        if (combined.contains("image")) {
            return "image";
        }
        if (combined.contains("video")) {
            return "video";
        }
        if (combined.contains("audio") || combined.contains("voice")
                || combined.contains("record")) {
            return "record";
        }
        return "file";
    }

    private static String privateAuthorId(JsonNode author, JsonNode payload) {
        String value = firstNonBlank(text(author, "id"), text(author, "openid"));
        return text(payload, "group_openid").isBlank() ? value : "";
    }

    private static JsonNode objectOrEmpty(JsonNode node) {
        return node != null && node.isObject() ? node : com.fasterxml.jackson.databind.node.JsonNodeFactory
                .instance.objectNode();
    }

    private static String findFirstText(JsonNode node, String... names) {
        return findFirstText(node, names, 0, new int[] {MAX_CARD_SEARCH_NODES});
    }

    private static String findFirstText(
            JsonNode node, String[] names, int depth, int[] remainingNodes) {
        if (node == null || node.isMissingNode() || node.isNull()
                || depth > MAX_CARD_SEARCH_DEPTH || remainingNodes[0]-- <= 0) {
            return "";
        }
        if (node.isObject()) {
            for (String name : names) {
                JsonNode value = node.get(name);
                if (value != null && value.isValueNode()) {
                    String text = value.asText("");
                    if (!text.isBlank()) {
                        return text;
                    }
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (OfficialQqSecretRedactor.sensitiveKey(field.getKey())) {
                    continue;
                }
                String found = findFirstText(field.getValue(), names, depth + 1, remainingNodes);
                if (!found.isBlank()) {
                    return found;
                }
            }
            return "";
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                String found = findFirstText(element, names, depth + 1, remainingNodes);
                if (!found.isBlank()) {
                    return found;
                }
            }
        }
        return "";
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isValueNode() ? value.asText("") : "";
    }

    private static boolean boolValue(JsonNode node, String field) {
        if (node == null) {
            return false;
        }
        JsonNode value = node.path(field);
        if (value.isBoolean()) {
            return value.asBoolean(false);
        }
        return value.isValueNode() && "true".equalsIgnoreCase(value.asText(""));
    }

    private static void put(Map<String, String> data, String key, String value) {
        if (value != null && !value.isBlank()) {
            data.put(key, value);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String cap(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…";
    }

    private static final class MessageFlags {
        private boolean atBot;
        private boolean atAll;
    }

    private static final class ReferenceContext {
        private String msgIdx = "";
        private String refMsgIdx = "";
        private String messageReferenceId = "";
    }

    private static final class MessageBudget {
        private int textChars;
        private int segments;
        private int attachments;
        private int mentions;
        private int nestedElements;

        private void addText(int chars) {
            if (chars < 0 || chars > MAX_MESSAGE_TEXT_CHARS - textChars) {
                throw OfficialQqDecodeException.contentTooLarge();
            }
            textChars += chars;
        }

        private void addSegment() {
            if (segments >= MAX_SEGMENTS) {
                throw OfficialQqDecodeException.segmentLimitExceeded();
            }
            segments++;
        }

        private void addAttachment() {
            if (attachments >= MAX_ATTACHMENTS) {
                throw OfficialQqDecodeException.attachmentLimitExceeded();
            }
            attachments++;
        }

        private void addMention() {
            if (mentions >= MAX_MENTIONS) {
                throw OfficialQqDecodeException.mentionLimitExceeded();
            }
            mentions++;
        }

        private void addNestedElement() {
            if (nestedElements >= MAX_NESTED_ELEMENTS) {
                throw OfficialQqDecodeException.nestingLimitExceeded();
            }
            nestedElements++;
        }
    }

    private static final class LengthCounter {
        private final int maximum;
        private int length;

        private LengthCounter(int maximum) {
            this.maximum = maximum;
        }

        private void add(long amount) {
            if (amount <= 0 || length >= maximum) {
                return;
            }
            length = (int) Math.min(maximum, length + Math.min(amount, maximum - length));
        }

        private void addRemaining() {
            length = maximum;
        }

        private boolean full() {
            return length >= maximum;
        }

        private int length() {
            return length;
        }
    }

    private record Timestamp(Instant at, boolean fallback) {
    }
}
