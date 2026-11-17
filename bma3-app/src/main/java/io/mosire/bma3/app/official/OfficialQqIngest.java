package io.mosire.bma3.app.official;

import io.mosire.bma3.app.channel.router.QqChannelRouteInput;
import io.mosire.bma3.app.channel.router.QqChannelRouteResult;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.ContentIntegrity;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.TimeReliability;
import io.mosire.bma3.app.priority.PriorityDecision;
import io.mosire.bma3.app.priority.PriorityResolver;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.priority.PriorityAttributes;
import io.mosire.bma3.core.priority.SourceKeyer;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.source.SourceLabels;
import io.mosire.bma3.core.source.SourceView;
import io.mosire.bma3.core.store.AppendResult;
import io.mosire.bma3.core.store.MessageBoardStore;
import io.mosire.bma3.inbox.core.InboxEntry;
import io.mosire.bma3.qqofficial.account.OfficialQqAccountEvent;
import io.mosire.bma3.qqofficial.account.OfficialQqAccountEventSink;
import io.mosire.bma3.qqofficial.directory.OfficialQqDirectoryStore;
import io.mosire.bma3.qqofficial.event.OfficialQqDispatch;
import io.mosire.bma3.qqofficial.event.OfficialQqEventDecoder;
import io.mosire.bma3.qqofficial.event.OfficialQqEvent;
import io.mosire.bma3.qqofficial.event.OfficialQqSecretRedactor;
import io.mosire.bma3.qqofficial.event.OfficialQqSystemEvent;
import io.mosire.bma3.qqofficial.event.OfficialQqUnknownEvent;
import io.mosire.bma3.qqofficial.eventstore.OfficialQqEventReferenceStore;
import io.mosire.bma3.qqofficial.gateway.GatewayEventSink;
import io.mosire.bma3.qqofficial.tools.QqOfficialSignalExtractor;
import io.mosire.bma3.qqofficial.tools.QqOfficialSourceKeyer;
import io.mosire.bma3.qqofficial.tools.QqOfficialSourceLabeler;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Board-first ingest for decoded QQ official account events.
 *
 * <p>This class is directly usable as an {@link OfficialQqAccountEventSink}: the account registry
 * can pass every decoded C2C/group/system/unknown event here. It does not create clients, read
 * credentials, open network connections, or start gateways. It validates the account against an
 * injected known-account predicate and reconstructs/validates the canonical raw source from the
 * event's string openids.</p>
 *
 * <p>Message order is mandatory (D-027/D-028 and spec 2026-11-17 §8.2):</p>
 * <ol>
 *   <li>sanitize segments and compute the configured priority using the official signal extractor
 *       and official source keyer;</li>
 *   <li>if dropped, return before board, directory observation, or Inbox;</li>
 *   <li>append the board row idempotently by raw source + exact string message id;</li>
 *   <li>only after a board row exists, build the bounded Inbox entry and call the injected
 *       projection sink with the stable request key.</li>
 * </ol>
 *
 * <p>There is deliberately no early return on a duplicate board row. If a process died after the
 * board commit but before/during Inbox projection, the replayed dispatch must still reach the
 * sink; the default sink is durably idempotent by request key. All failures propagate (runtime
 * failures unchanged, checked failures wrapped as {@link OfficialIngestException}) so the gateway
 * can resume/replay rather than silently losing a committed message.</p>
 *
 * <p>When a {@link PreBoardIngressHandler} is injected, the D027 gate remains first and dropped
 * messages never reach the handler. Accepted messages are offered to the handler before any board
 * write; the handler is expected to atomically execute the dual-channel router's ingest. The legacy
 * board append + raw projection sink path is used only when the handler is absent.</p>
 */
public final class OfficialQqIngest implements OfficialQqAccountEventSink {

    private static final Logger LOG = LoggerFactory.getLogger(OfficialQqIngest.class);
    private static final int MAX_ACCOUNT_CODE_POINTS = 128;
    private static final int MAX_SOURCE_SEGMENT_CODE_POINTS = 512;
    private static final int MAX_SENDER_LABEL_CODE_POINTS = 256;
    private static final java.util.regex.Pattern HTTP_URL =
            java.util.regex.Pattern.compile("(?i)https?://[^\\s\"'<>]+");

    private final MessageBoardStore board;
    private final OfficialQqDirectoryStore directory;
    private final PriorityResolver priorities;
    private final SourceKeyer sourceKeyer;
    private final SourceLabels sourceLabels;
    private final Predicate<String> accountKnown;
    private final Supplier<String> selfName;
    private final OfficialQqProjectionSink projectionSink;
    private final OfficialQqRouteObserver routeObserver;
    private final OfficialQqEventReferenceStore eventReferenceStore;
    private final PreBoardIngressHandler preBoardIngressHandler;
    private final Clock clock;

    private final AtomicLong unknownDispatches = new AtomicLong();
    private final AtomicLong unknownSystemEvents = new AtomicLong();
    private final AtomicLong droppedMessages = new AtomicLong();
    private final AtomicLong boardAcceptedMessages = new AtomicLong();
    private final AtomicLong projectedMessages = new AtomicLong();

    private OfficialQqIngest(Builder builder) {
        this.board = Objects.requireNonNull(builder.board, "board");
        this.directory = Objects.requireNonNull(builder.directory, "directory");
        this.priorities = Objects.requireNonNull(builder.priorities, "priorities");
        this.accountKnown = Objects.requireNonNull(builder.accountKnown, "accountKnown");
        this.projectionSink = Objects.requireNonNull(builder.projectionSink, "projectionSink");
        this.sourceKeyer = builder.sourceKeyer == null
                ? new QqOfficialSourceKeyer() : builder.sourceKeyer;
        this.sourceLabels = builder.sourceLabels == null
                ? SourceLabels.builder()
                        .register(new QqOfficialSourceLabeler(() -> builder.directory))
                        .build()
                : builder.sourceLabels;
        this.selfName = builder.selfName == null ? () -> "" : builder.selfName;
        this.routeObserver = builder.routeObserver == null
                ? OfficialQqRouteObserver.noop() : builder.routeObserver;
        this.eventReferenceStore = builder.eventReferenceStore;
        this.preBoardIngressHandler = builder.preBoardIngressHandler;
        this.clock = builder.clock == null ? Clock.systemUTC() : builder.clock;
    }

    public static Builder builder(MessageBoardStore board, OfficialQqDirectoryStore directory,
                                  PriorityResolver priorities, Predicate<String> accountKnown,
                                  OfficialQqProjectionSink projectionSink) {
        return new Builder(board, directory, priorities, accountKnown, projectionSink);
    }

    /**
     * Direct per-account gateway seam for composition code that already owns the account id.
     *
     * <p>It has exactly the same validation and propagation behavior as
     * {@link #onEvent(OfficialQqAccountEvent)}. Keeping this method public makes it possible for the
     * composition root to wire exceptions straight to the gateway's Resume path rather than through
     * an intermediary that catches them.</p>
     */
    public void onDispatch(String accountId, OfficialQqDispatch dispatch) {
        onEvent(new OfficialQqAccountEvent(accountId, dispatch));
    }

    /**
     * Gateway-facing adapter for one account. Exceptions intentionally escape unchanged; the
     * official gateway client treats a sink failure as uncommitted and requests Resume.
     */
    public GatewayEventSink gatewaySink(String accountId, OfficialQqEventDecoder decoder) {
        Objects.requireNonNull(decoder, "decoder");
        return gatewayDispatch -> decoder.decode(gatewayDispatch)
                .ifPresent(dispatch -> onDispatch(accountId, dispatch));
    }

    /**
     * Pre-board ingress seam for the real dual-channel router.
     *
     * <p>When supplied, OfficialQqIngest builds the complete {@link QqChannelRouteInput} and calls
     * this handler before any board write. The handler owns the atomic router.ingest call (and the
     * board write inside it). The old raw projection-sink path is used only when this handler is
     * absent.</p>
     */
    @FunctionalInterface
    public interface PreBoardIngressHandler {
        QqChannelRouteResult ingest(QqChannelRouteInput input);
    }

    @Override
    public void onEvent(OfficialQqAccountEvent accountEvent) {
        Objects.requireNonNull(accountEvent, "accountEvent");
        String accountId = validateAccount(accountEvent.accountId());
        OfficialQqDispatch dispatch = accountEvent.dispatch();
        if (dispatch instanceof OfficialQqEvent message) {
            ingestMessage(accountId, message);
            return;
        }
        if (dispatch instanceof OfficialQqSystemEvent system) {
            ingestSystem(accountId, system);
            return;
        }
        if (dispatch instanceof OfficialQqUnknownEvent unknown) {
            unknownDispatches.incrementAndGet();
            // Never log the unknown type/id: both are upstream-controlled and may contain a
            // credential-shaped string. Payload length is numeric metadata only.
            LOG.debug("event=qq_official_unknown_dispatch payloadLength={} sequence={}",
                    unknown.payloadLength(), unknown.sequence());
            return;
        }
        throw new OfficialIngestException(OfficialIngestException.Code.INVALID_SOURCE,
                "不支持的官方 dispatch 类型");
    }

    /** Current ingest counters; safe for diagnostics and tests (no ids, bodies, or credentials). */
    public Stats stats() {
        return new Stats(unknownDispatches.get(), unknownSystemEvents.get(),
                droppedMessages.get(), boardAcceptedMessages.get(), projectedMessages.get());
    }

    public record Stats(long unknownDispatches, long unknownSystemEvents,
                        long droppedMessages, long boardAcceptedMessages,
                        long projectedMessages) {
    }

    private void ingestMessage(String accountId, OfficialQqEvent event) {
        String messageId = validateMessageId(event.messageId());
        SourceAddress source = validateSource(accountId, event);
        String sourceKey;
        try {
            sourceKey = sourceKeyer.keyFor(source);
        } catch (RuntimeException failure) {
            throw new OfficialIngestException(OfficialIngestException.Code.INVALID_SOURCE,
                    "官方 source 无法映射为优先级 key", failure);
        }
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new OfficialIngestException(OfficialIngestException.Code.INVALID_SOURCE,
                    "官方 source 的优先级 key 为空");
        }

        SourceView sourceView = sourceLabels.view(source);
        List<Segment> segments = OfficialQqSegments.sanitize(event.segments());
        String bodyText = OfficialQqSegments.bodyText(segments);
        String senderId = validateSenderId(event.senderId());
        String eventDisplayName = safeDisplayName(event.displayName());
        boolean replyToBot = isReplyToBot(source, segments, event.refMsgIdx());
        String configuredSelfName;
        try {
            configuredSelfName = safeDisplayName(selfName.get());
        } catch (RuntimeException failure) {
            throw new OfficialIngestException(OfficialIngestException.Code.INVALID_SOURCE,
                    "读取官方账号自身名称失败", failure);
        }

        PriorityAttributes attributes = QqOfficialSignalExtractor.attributes(
                event, configuredSelfName, replyToBot,
                priorities.weighting().commandPrefixes());
        PriorityDecision decision = priorities.resolve(
                sourceKey, senderId, eventDisplayName, attributes, bodyText);

        // D027 remains the strongest gate: a dropped message must not even reach an injected
        // handler, so a faulty handler can never turn it into a board/Inbox write.
        if (decision.dropped()) {
            droppedMessages.incrementAndGet();
            // IDs/account/source and arithmetic are already log-safe; body text and segment data
            // are deliberately absent.
            LOG.info("event=qq_official_message_dropped accountId={} sourceKey={} priority={}",
                    accountId, sourceKey, decision.priority());
            return;
        }

        InboundMessage boardMessage = buildBoardMessage(source, messageId, event, senderId, segments);
        if (preBoardIngressHandler != null) {
            // The handler owns the atomic router.ingest call and therefore the board write. Never
            // call board.append first on this path.
            String sender = senderLabel(accountId, event, senderId, eventDisplayName);
            InboxEntry preBoardEntry = preBoardInboxEntry(
                    source, messageId, event, sender, segments);
            QqChannelRouteInput input = new QqChannelRouteInput(
                    false,
                    accountId,
                    source,
                    messageId,
                    senderId,
                    Direction.INBOUND,
                    event.at(),
                    clock.instant(),
                    decision.priority(),
                    timeReliability(event),
                    contentIntegrity(event),
                    structuredMentionsTrusted(event, segments),
                    segments,
                    boardMessage,
                    preBoardEntry,
                    QqChannelRouteInput.RawProjectionMode.RECEIPT_ONCE,
                    sourceView);
            QqChannelRouteResult result;
            try {
                result = preBoardIngressHandler.ingest(input);
            } catch (RuntimeException failure) {
                LOG.warn("event=qq_official_pre_board_ingress_failed accountId={} type={}",
                        accountId, failure.getClass().getSimpleName());
                throw failure;
            }
            if (result == null) {
                throw new IllegalStateException("pre-board ingress handler 未返回结果");
            }

            // A handler returning success is not evidence that the board fact exists. Verify the
            // exact inbound row before creating replyability/directory side effects.
            Message fact = board.findInbound(source, messageId)
                    .orElseThrow(() -> new IllegalStateException(
                            "pre-board ingress 成功后未找到入站消息事实"));
            if (!source.equals(fact.inbound().source())
                    || !messageId.equals(fact.inbound().messageId())) {
                throw new IllegalStateException("pre-board ingress 入站消息事实不匹配");
            }
            boardAcceptedMessages.incrementAndGet();
            recordReplyabilityAndDirectory(accountId, event);
            if (!result.itemId().isBlank()
                    && (result.disposition() == QqChannelRouteResult.Disposition.UNBOUND_RAW
                    || result.disposition() == QqChannelRouteResult.Disposition.BOUND_DISPLAY)) {
                // As on the legacy receipt path, this counts a successful body projection result;
                // it is not a claim that every replay caused a new item or wake.
                projectedMessages.incrementAndGet();
            }
            return;
        }

        // Legacy raw path: unchanged board-first behavior and raw projection sink.
        AppendResult appended = board.append(boardMessage);
        boardAcceptedMessages.incrementAndGet();
        recordReplyabilityAndDirectory(accountId, event);

        String sender = senderLabel(accountId, event, senderId, eventDisplayName);
        String channel = OfficialInboxProjectionSink.CHANNEL;
        InboxEntry entry = OfficialQqSegments.entry(
                appended.message().localSeq(), event.at(), sender, segments)
                .withChannelEvidence(channel, Map.of(
                        "rawSource", source.format(),
                        "messageId", messageId));
        String requestKey = OfficialQqSegments.requestKey(source.format(), messageId);
        Map<String, Object> channelSummary = new LinkedHashMap<>();
        channelSummary.put("channel", channel);
        channelSummary.put("rawSource", source.format());
        channelSummary.put("messageId", messageId);
        channelSummary.put("duplicateOnBoard", !appended.created());

        OfficialQqProjection projection = new OfficialQqProjection(
                accountId,
                event,
                appended,
                sourceView,
                decision,
                entry,
                requestKey,
                clock.instant(),
                channel,
                channelSummary);

        OfficialQqProjectionSink.Result result;
        try {
            result = projectionSink.project(projection);
        } catch (RuntimeException failure) {
            // Never swallow a post-board projection failure. The runtime type/code is safe to log;
            // the gateway sees the original exception is not swallowed and can replay.
            LOG.warn("event=qq_official_projection_failed accountId={} duplicate={} type={}",
                    accountId, !appended.created(), failure.getClass().getSimpleName());
            throw failure;
        } catch (Exception failure) {
            LOG.warn("event=qq_official_projection_failed accountId={} duplicate={} type={}",
                    accountId, !appended.created(), failure.getClass().getSimpleName());
            throw new OfficialIngestException(OfficialIngestException.Code.PROJECTION_FAILED,
                    "官方消息 Inbox 投影失败", failure);
        }
        if (result == null) {
            throw new OfficialIngestException(
                    OfficialIngestException.Code.PROJECTION_RESULT_MISSING,
                    "官方消息投影 sink 未返回结果");
        }
        if (result.projected()) {
            projectedMessages.incrementAndGet();
        }
        // Route decisions are an app concern, but recording them here gives the future channel
        // router a durable/auditable seam and keeps observer failures visible to replay.
        routeObserver.onDecision(projection, result);
    }

    private static InboundMessage buildBoardMessage(SourceAddress source, String messageId,
                                                    OfficialQqEvent event, String senderId,
                                                    List<Segment> segments) {
        return new InboundMessage(
                source,
                messageId,
                event.at(),
                segments,
                Direction.INBOUND,
                senderId,
                safeDisplayName(event.username()),
                safeDisplayName(event.memberNickname()),
                safeDisplayName(event.memberRole()));
    }

    private InboxEntry preBoardInboxEntry(SourceAddress source, String messageId,
                                          OfficialQqEvent event, String sender,
                                          List<Segment> segments) {
        String channel = OfficialInboxProjectionSink.CHANNEL;
        return OfficialQqSegments.entry(0L, event.at(), sender, segments)
                .withChannelEvidence(channel, Map.of(
                        "rawSource", source.format(),
                        "messageId", messageId));
    }

    private void recordReplyabilityAndDirectory(String accountId, OfficialQqEvent event) {
        // eventReferenceStore failures and directory failures must escape for gateway replay.
        if (eventReferenceStore != null && !event.eventId().isBlank()) {
            eventReferenceStore.recordMessage(accountId, event);
        }
        observeMessage(accountId, event);
    }

    private static TimeReliability timeReliability(OfficialQqEvent event) {
        return event.timestampFallback() ? TimeReliability.FALLBACK : TimeReliability.RELIABLE;
    }

    private static ContentIntegrity contentIntegrity(OfficialQqEvent event) {
        // Positive completeness only when the decoder explicitly confirms it. Compatibility
        // constructors default contentComplete=false, which maps to INCOMPLETE, never COMPLETE.
        return event.contentComplete() ? ContentIntegrity.COMPLETE : ContentIntegrity.INCOMPLETE;
    }

    /**
     * The matcher may ignore at-segments entirely, so claiming structured mention trust requires
     * positive proof for every at segment: at least one at, no @all, and every at is_you=true.
     * No at segments therefore stays false.
     */
    private static boolean structuredMentionsTrusted(OfficialQqEvent event,
                                                     List<Segment> segments) {
        if (event.atAll()) {
            return false;
        }
        boolean sawAt = false;
        for (Segment segment : segments) {
            if (segment == null || !"at".equals(segment.type())) {
                continue;
            }
            sawAt = true;
            String scope = segment.data("scope");
            if ("all".equalsIgnoreCase(scope)) {
                return false;
            }
            if (!"true".equalsIgnoreCase(segment.data("is_you"))) {
                return false;
            }
        }
        return sawAt;
    }

    private void ingestSystem(String accountId, OfficialQqSystemEvent event) {
        String type = event.eventType();
        // Only concrete, source-bearing group/friend system events with a non-empty event id are
        // eligible for event_id reply. READY/RESUMED, empty ids, unknown events and events without
        // a real source are skipped without fabricating a replyable reference.
        if (eventReferenceStore != null
                && !event.eventId().isBlank()
                && isReplyableSystemType(type)
                && hasSystemRawSource(type, event)) {
            eventReferenceStore.recordSystem(accountId, event);
        }
        switch (type) {
            case "READY", "RESUMED" -> {
                // Connection lifecycle only. No directory fact to write.
            }
            case "GROUP_ADD_ROBOT" -> {
                String group = requireDirectoryId(event.groupOpenid(), "groupOpenid");
                directoryWrite(() -> directory.setGroupJoined(
                        accountId, group, true, event.at()));
            }
            case "GROUP_DEL_ROBOT" -> {
                String group = requireDirectoryId(event.groupOpenid(), "groupOpenid");
                directoryWrite(() -> directory.setGroupJoined(
                        accountId, group, false, event.at()));
            }
            case "GROUP_MSG_RECEIVE" -> {
                String group = requireDirectoryId(event.groupOpenid(), "groupOpenid");
                directoryWrite(() -> directory.setGroupReceiveMessages(
                        accountId, group, true, event.at()));
            }
            case "GROUP_MSG_REJECT" -> {
                String group = requireDirectoryId(event.groupOpenid(), "groupOpenid");
                directoryWrite(() -> directory.setGroupReceiveMessages(
                        accountId, group, false, event.at()));
            }
            case "FRIEND_ADD" -> {
                String user = requireDirectoryId(
                        firstNonBlank(event.userOpenid(), event.memberOpenid()), "userOpenid");
                directoryWrite(() -> {
                    directory.observeUser(accountId, user,
                            safeDisplayName(firstNonBlank(
                                    event.memberNickname(), event.username())), event.at());
                    directory.setFriend(accountId, user, true, event.at());
                });
            }
            case "FRIEND_DEL" -> {
                String user = requireDirectoryId(
                        firstNonBlank(event.userOpenid(), event.memberOpenid()), "userOpenid");
                directoryWrite(() -> directory.setFriend(accountId, user, false, event.at()));
            }
            default -> {
                unknownSystemEvents.incrementAndGet();
                LOG.debug("event=qq_official_unknown_system_dispatch sequence={}",
                        event.sequence());
            }
        }
    }

    private static boolean isReplyableSystemType(String type) {
        return switch (type) {
            case "GROUP_ADD_ROBOT", "GROUP_DEL_ROBOT", "GROUP_MSG_RECEIVE", "GROUP_MSG_REJECT",
                    "FRIEND_ADD", "FRIEND_DEL" -> true;
            default -> false;
        };
    }

    private static boolean hasSystemRawSource(String type, OfficialQqSystemEvent event) {
        return switch (type) {
            case "FRIEND_ADD", "FRIEND_DEL" -> !event.userOpenid().isBlank();
            case "GROUP_ADD_ROBOT", "GROUP_DEL_ROBOT", "GROUP_MSG_RECEIVE",
                    "GROUP_MSG_REJECT" -> !event.groupOpenid().isBlank();
            default -> false;
        };
    }

    private void observeMessage(String accountId, OfficialQqEvent event) {
        String group = event.groupOpenid();
        String user = firstNonBlank(event.userOpenid(), event.memberOpenid());
        if (!group.isBlank()) {
            requireDirectoryId(group, "groupOpenid");
            directoryWrite(() -> directory.observeGroup(accountId, group, event.at()));
        }
        if (!user.isBlank()) {
            String name = safeDisplayName(event.displayName());
            requireDirectoryId(user, "userOpenid");
            directoryWrite(() -> directory.observeUser(accountId, user, name, event.at()));
        }
    }

    private String senderLabel(String accountId, OfficialQqEvent event, String senderId,
                               String eventDisplayName) {
        if (!eventDisplayName.isBlank()) {
            return eventDisplayName;
        }
        String userId = firstNonBlank(event.userOpenid(), event.memberOpenid(), senderId);
        if (!userId.isBlank()) {
            String stored = directoryWriteValue(() -> directory.user(accountId, userId)
                    .map(OfficialQqDirectoryStore.User::name)
                    .map(OfficialQqIngest::safeDisplayName)
                    .orElse(""));
            if (!stored.isBlank()) {
                return stored;
            }
            return "QQ用户 " + shortId(userId);
        }
        return "QQ用户";
    }

    private boolean isReplyToBot(SourceAddress source, List<Segment> segments, String refMsgIdx) {
        String reference = "";
        for (Segment segment : segments) {
            if (segment == null || !"quote".equals(segment.type())) {
                continue;
            }
            reference = firstNonBlank(
                    segment.data("message_id"), segment.data("ref_msg_idx"));
            if (!reference.isBlank()) {
                break;
            }
        }
        if (reference.isBlank()) {
            reference = refMsgIdx;
        }
        if (reference.isBlank() || !safeIdentifier(reference, MAX_SOURCE_SEGMENT_CODE_POINTS)) {
            return false;
        }
        return board.findByExternalMessageId(source, reference)
                .map(Message::self)
                .orElse(false);
    }

    private String validateAccount(String rawAccountId) {
        String accountId = rawAccountId == null ? "" : rawAccountId;
        if (!safeIdentifier(accountId, MAX_ACCOUNT_CODE_POINTS) || accountId.indexOf('|') >= 0) {
            throw new OfficialIngestException(OfficialIngestException.Code.INVALID_ACCOUNT,
                    "官方 accountId 格式无效");
        }
        boolean known;
        try {
            known = accountKnown.test(accountId);
        } catch (RuntimeException failure) {
            throw new OfficialIngestException(
                    OfficialIngestException.Code.ACCOUNT_LOOKUP_FAILED,
                    "官方 accountId 校验失败", failure);
        }
        if (!known) {
            throw new OfficialIngestException(OfficialIngestException.Code.UNKNOWN_ACCOUNT,
                    "官方 accountId 未配置或不可用");
        }
        return accountId;
    }

    private SourceAddress validateSource(String accountId, OfficialQqEvent event) {
        SourceAddress source;
        try {
            source = event.source(accountId);
        } catch (RuntimeException failure) {
            throw new OfficialIngestException(OfficialIngestException.Code.INVALID_SOURCE,
                    "官方事件不能构造安全 raw source", failure);
        }
        if (!QqOfficialSourceKeyer.supportsPlatform(source.platform())
                || source.segments().size() != 2
                || !accountId.equals(source.accountId())
                || !safeIdentifier(source.lastSegment(),
                        MAX_SOURCE_SEGMENT_CODE_POINTS)
                || source.lastSegment().indexOf('|') >= 0) {
            throw new OfficialIngestException(OfficialIngestException.Code.INVALID_SOURCE,
                    "官方 raw source 结构无效");
        }
        return source;
    }

    // Package-visible so the defense-in-depth boundary can be tested directly even though the
    // official event record constructor also rejects these values upstream.
    static String validateMessageId(String messageId) {
        String value = messageId == null ? "" : messageId;
        if (!safeIdentifier(value, Integer.MAX_VALUE) || value.indexOf('|') >= 0) {
            throw new OfficialIngestException(OfficialIngestException.Code.INVALID_MESSAGE_ID,
                    "官方 messageId 必须是非空且不含空白/控制字符的字符串");
        }
        return value;
    }

    private static String validateSenderId(String senderId) {
        String value = senderId == null ? "" : senderId;
        if (value.isBlank()) {
            return "";
        }
        if (!safeIdentifier(value, MAX_SOURCE_SEGMENT_CODE_POINTS)) {
            throw new OfficialIngestException(OfficialIngestException.Code.INVALID_SOURCE,
                    "官方 sender openid 格式无效");
        }
        return value;
    }

    private static String requireDirectoryId(String raw, String field) {
        String value = raw == null ? "" : raw;
        if (!safeIdentifier(value, MAX_SOURCE_SEGMENT_CODE_POINTS)) {
            throw new OfficialIngestException(OfficialIngestException.Code.INVALID_SOURCE,
                    field + " 格式无效");
        }
        return value;
    }

    private void directoryWrite(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            throw new OfficialIngestException(
                    OfficialIngestException.Code.DIRECTORY_FAILED,
                    "官方 QQ 目录写入失败", failure);
        }
    }

    private <T> T directoryWriteValue(Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException failure) {
            throw new OfficialIngestException(
                    OfficialIngestException.Code.DIRECTORY_FAILED,
                    "官方 QQ 目录读取失败", failure);
        }
    }

    /**
     * Strict single-source-segment check. It intentionally does not trim: identifiers must be
     * persisted/compared exactly as received, while whitespace, slashes, controls, format chars,
     * and the board-message-key delimiter {@code |} are rejected.
     */
    private static boolean safeIdentifier(String value, int maxCodePoints) {
        if (value == null || value.isBlank()) {
            return false;
        }
        int count = value.codePointCount(0, value.length());
        if (count > maxCodePoints || !value.equals(value.strip())) {
            return false;
        }
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            int type = Character.getType(codePoint);
            if (codePoint == '/' || codePoint == '\\'
                    || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private static String safeDisplayName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String redacted = OfficialQqSecretRedactor.redactText(value);
        redacted = HTTP_URL.matcher(redacted).replaceAll("[链接]");
        StringBuilder out = new StringBuilder(Math.min(redacted.length(), 256));
        int codePoints = 0;
        for (int index = 0; index < redacted.length()
                && codePoints < MAX_SENDER_LABEL_CODE_POINTS; ) {
            int codePoint = redacted.codePointAt(index);
            int type = Character.getType(codePoint);
            boolean unsafe = Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR;
            if (!unsafe) {
                out.appendCodePoint(codePoint);
                codePoints++;
            }
            index += Character.charCount(codePoint);
        }
        return out.toString().strip();
    }

    private static String shortId(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        int count = id.codePointCount(0, id.length());
        if (count <= 8) {
            return id;
        }
        return id.substring(0, id.offsetByCodePoints(0, 8)) + "…";
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    /** Builder keeps the production composition root explicit about every injected seam. */
    public static final class Builder {
        private final MessageBoardStore board;
        private final OfficialQqDirectoryStore directory;
        private final PriorityResolver priorities;
        private final Predicate<String> accountKnown;
        private final OfficialQqProjectionSink projectionSink;
        private SourceKeyer sourceKeyer;
        private SourceLabels sourceLabels;
        private Supplier<String> selfName;
        private OfficialQqRouteObserver routeObserver;
        private OfficialQqEventReferenceStore eventReferenceStore;
        private PreBoardIngressHandler preBoardIngressHandler;
        private Clock clock;

        private Builder(MessageBoardStore board, OfficialQqDirectoryStore directory,
                        PriorityResolver priorities, Predicate<String> accountKnown,
                        OfficialQqProjectionSink projectionSink) {
            this.board = board;
            this.directory = directory;
            this.priorities = priorities;
            this.accountKnown = accountKnown;
            this.projectionSink = projectionSink;
        }

        public Builder sourceKeyer(SourceKeyer value) {
            this.sourceKeyer = value;
            return this;
        }

        public Builder sourceLabels(SourceLabels value) {
            this.sourceLabels = value;
            return this;
        }

        public Builder selfName(Supplier<String> value) {
            this.selfName = value;
            return this;
        }

        public Builder routeObserver(OfficialQqRouteObserver value) {
            this.routeObserver = value;
            return this;
        }

        /** Optional until the composer wires the shared durable event-reference store. */
        public Builder eventReferenceStore(OfficialQqEventReferenceStore value) {
            this.eventReferenceStore = value;
            return this;
        }

        /**
         * Optional dual-channel router seam. When absent, the legacy raw board+projection path is
         * preserved exactly.
         */
        public Builder preBoardIngressHandler(PreBoardIngressHandler value) {
            this.preBoardIngressHandler = value;
            return this;
        }

        public Builder clock(Clock value) {
            this.clock = value;
            return this;
        }

        public OfficialQqIngest build() {
            return new OfficialQqIngest(this);
        }
    }
}
