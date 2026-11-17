package io.mosire.bma3.qqofficial.event;

import java.time.Instant;
import java.util.Objects;

/**
 * A non-message official dispatch: READY/RESUMED plus friend, group directory, and message
 * receive-range switch events.
 *
 * <p>The record intentionally contains only routing identities and status metadata. Unknown
 * fields are not retained, so a token or message body hidden in a future payload cannot leak
 * through this model.</p>
 */
public record OfficialQqSystemEvent(
        String eventId,
        String eventType,
        long sequence,
        Instant receivedAt,
        Instant at,
        boolean timestampFallback,
        String groupOpenid,
        String userOpenid,
        String memberOpenid,
        String operatorOpenid,
        String username,
        String memberNickname,
        String sessionId,
        String traceId) implements OfficialQqDispatch {

    public OfficialQqSystemEvent {
        eventId = safe(eventId);
        eventType = safe(eventType);
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        at = Objects.requireNonNull(at, "at");
        groupOpenid = safe(groupOpenid);
        userOpenid = safe(userOpenid);
        memberOpenid = safe(memberOpenid);
        operatorOpenid = safe(operatorOpenid);
        username = safe(username);
        memberNickname = safe(memberNickname);
        sessionId = safe(sessionId);
        traceId = safe(traceId);
    }

    /**
     * Safe diagnostic form: fixed whitelisted event-type name plus non-string metadata only.
     * Session ids, openids, trace ids, and all arbitrary payload fields stay out of logs.
     */
    @Override
    public String toString() {
        return "OfficialQqSystemEvent[eventType=" + safeLogEventType()
                + ", sequence=" + sequence
                + ", receivedAt=" + receivedAt
                + ", at=" + at
                + ", timestampFallback=" + timestampFallback + "]";
    }

    private String safeLogEventType() {
        OfficialQqEventType known = OfficialQqEventType.fromWire(eventType);
        return known != null && known.isSystem() ? known.name() : "UNKNOWN_SYSTEM_EVENT";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
