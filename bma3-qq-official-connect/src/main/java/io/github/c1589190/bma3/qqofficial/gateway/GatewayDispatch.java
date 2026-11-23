package io.github.c1589190.bma3.qqofficial.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.time.Instant;
import java.util.Set;

/**
 * One raw Dispatch envelope handed to the event layer.
 *
 * <p>No body content is logged by the Gateway. {@code data} is a defensive copy; consumers must
 * still treat it as untrusted platform input. Sequence is the envelope's {@code s}, or the last
 * known sequence when the frame omitted it.</p>
 *
 * <p>{@link #toString()} is deliberately decoupled from the record components. It can be emitted
 * accidentally by a log statement, so it exposes only a whitelisted event type, a numeric
 * top-level field count and the sequence. Event id, arbitrary event-type text, payload keys and
 * payload values are never rendered.</p>
 */
public record GatewayDispatch(
        String eventId,
        String eventType,
        long sequence,
        JsonNode data,
        Instant receivedAt) {

    private static final Set<String> SAFE_EVENT_TYPES = Set.of(
            "READY",
            "RESUMED",
            "C2C_MESSAGE_CREATE",
            "GROUP_AT_MESSAGE_CREATE",
            "GROUP_MESSAGE_CREATE",
            "FRIEND_ADD",
            "FRIEND_DEL",
            "GROUP_ADD_ROBOT",
            "GROUP_DEL_ROBOT",
            "GROUP_MSG_RECEIVE",
            "GROUP_MSG_REJECT");

    public GatewayDispatch {
        eventId = eventId == null ? "" : eventId;
        eventType = eventType == null ? "" : eventType;
        data = data == null ? NullNode.instance : data.deepCopy();
        receivedAt = receivedAt == null ? Instant.EPOCH : receivedAt;
    }

    /** Safe, bounded representation for accidental log/lookup use; never includes payload text. */
    @Override
    public String toString() {
        String safeType = SAFE_EVENT_TYPES.contains(eventType) ? eventType : "UNKNOWN";
        return "GatewayDispatch[eventType=" + safeType
                + ", dataFields=" + data.size()
                + ", sequence=" + sequence + "]";
    }
}
