package io.mosire.bma3.qqofficial.event;

import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A normalized QQ official message event (C2C, group @, or group-wide message).
 *
 * <p>All official identifiers remain strings. In particular openids and message ids have no
 * relationship to OneBot QQ numbers and must never be parsed into {@code long}s.</p>
 *
 * <p>{@code segments} are the only place structured attachment URLs, filenames, mention ids,
 * references, and card payloads may live. {@link #text()} deliberately returns only text
 * segment bodies; placeholders such as {@code [图片]} and attachment metadata never become the
 * message body.</p>
 */
public record OfficialQqEvent(
        String eventId,
        String eventType,
        String messageId,
        String groupOpenid,
        String userOpenid,
        String memberOpenid,
        String username,
        String memberNickname,
        String memberRole,
        Instant at,
        List<Segment> segments,
        String refMsgIdx,
        String traceId,
        String msgIdx,
        boolean atBot,
        boolean atAll,
        boolean timestampFallback,
        long sequence,
        Instant receivedAt,
        /**
         * True only when the decoder had a complete understood representation and redaction did
         * not change the text. Different raw values must never collapse into a false MATCHED.
         */
        boolean contentComplete) implements OfficialQqDispatch {

    public OfficialQqEvent {
        eventId = safe(eventId);
        eventType = safe(eventType);
        messageId = OfficialQqSourceIds.requireMessageId(safe(messageId));
        groupOpenid = safe(groupOpenid);
        userOpenid = safe(userOpenid);
        memberOpenid = safe(memberOpenid);
        username = safe(username);
        memberNickname = safe(memberNickname);
        memberRole = safe(memberRole);
        refMsgIdx = safe(refMsgIdx);
        traceId = safe(traceId);
        msgIdx = safe(msgIdx);
        at = Objects.requireNonNull(at, "at");
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    /**
     * Compact constructor matching the design-spec record shape. Newer routing/flag/sequence
     * fields default to absent values; {@code receivedAt} falls back to the message timestamp.
     */
    public OfficialQqEvent(
            String eventId,
            String eventType,
            String messageId,
            String groupOpenid,
            String userOpenid,
            String memberOpenid,
            String username,
            String memberNickname,
            String memberRole,
            Instant at,
            List<Segment> segments,
            String refMsgIdx,
            String traceId) {
        this(eventId, eventType, messageId, groupOpenid, userOpenid, memberOpenid, username,
                memberNickname, memberRole, at, segments, refMsgIdx, traceId, "", false, false,
                false, -1L, at, false);
    }

    /**
     * Compatibility constructor for callers that predate content-completeness metadata. The
     * event is still valid for board persistence, but it conservatively does not claim that
     * redaction/structure preserved enough to support a strict cross-channel MATCHED decision.
     */
    public OfficialQqEvent(
            String eventId,
            String eventType,
            String messageId,
            String groupOpenid,
            String userOpenid,
            String memberOpenid,
            String username,
            String memberNickname,
            String memberRole,
            Instant at,
            List<Segment> segments,
            String refMsgIdx,
            String traceId,
            String msgIdx,
            boolean atBot,
            boolean atAll,
            boolean timestampFallback,
            long sequence,
            Instant receivedAt) {
        this(eventId, eventType, messageId, groupOpenid, userOpenid, memberOpenid, username,
                memberNickname, memberRole, at, segments, refMsgIdx, traceId, msgIdx, atBot,
                atAll, timestampFallback, sequence, receivedAt, false);
    }

    /** The text body only; URLs and structured attachment payloads stay in {@link #segments()}. */
    public String text() {
        StringBuilder text = new StringBuilder();
        for (Segment segment : segments) {
            if (!"text".equals(segment.type()) || segment.text().isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(segment.text());
        }
        return text.toString();
    }

    public boolean groupMessage() {
        return !groupOpenid.isBlank();
    }

    public boolean privateMessage() {
        return groupOpenid.isBlank() && !userOpenid.isBlank();
    }

    /**
     * Raw official source; intentionally distinct from the OneBot {@code qq_group/*} namespace.
     *
     * <p>Both the account id and openid must be a single safe source segment. Rejecting slashes,
     * whitespace, and control characters prevents one malformed openid from routing a message
     * into another channel's source. The exception never echoes the offending value.</p>
     */
    public SourceAddress source(String accountId) {
        String safeAccount = OfficialQqSourceIds.requireSegment(accountId, "accountId");
        if (groupMessage()) {
            String safeGroup = OfficialQqSourceIds.requireSegment(groupOpenid, "groupOpenid");
            return SourceAddress.parse("qq_official_group/" + safeAccount + "/" + safeGroup);
        }
        if (privateMessage()) {
            String safeUser = OfficialQqSourceIds.requireSegment(userOpenid, "userOpenid");
            return SourceAddress.parse("qq_official_private/" + safeAccount + "/" + safeUser);
        }
        throw new IllegalStateException("官方消息缺少群或单聊 openid，不能构造 source");
    }

    /** Group member id wins in groups; C2C events only have a user openid. */
    public String senderId() {
        if (groupMessage()) {
            return memberOpenid.isBlank() ? userOpenid : memberOpenid;
        }
        return userOpenid;
    }

    /** Group card wins over the account nickname for display only. */
    public String displayName() {
        return memberNickname.isBlank() ? username : memberNickname;
    }

    /**
     * Safe diagnostic form.
     *
     * <p>Every string from the wire is untrusted. This form therefore emits only a whitelisted
     * fixed event-type name plus non-string metadata (times, counts, booleans, sequence). It
     * omits ids, openids, names, references, trace ids, message body, and every segment payload
     * so an accidental {@code LOG.debug("{}", event)} cannot inject log lines or leak content.</p>
     */
    @Override
    public String toString() {
        return "OfficialQqEvent[eventType=" + safeLogEventType()
                + ", at=" + at
                + ", segmentCount=" + segments.size()
                + ", atBot=" + atBot
                + ", atAll=" + atAll
                + ", timestampFallback=" + timestampFallback
                + ", contentComplete=" + contentComplete
                + ", groupMessage=" + groupMessage()
                + ", sequence=" + sequence
                + ", receivedAt=" + receivedAt + "]";
    }

    private String safeLogEventType() {
        OfficialQqEventType known = OfficialQqEventType.fromWire(eventType);
        return known != null && known.isMessage() ? known.name() : "UNKNOWN_MESSAGE_EVENT";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
