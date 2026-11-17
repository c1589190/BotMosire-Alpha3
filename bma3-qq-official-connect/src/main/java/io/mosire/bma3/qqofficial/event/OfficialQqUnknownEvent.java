package io.mosire.bma3.qqofficial.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Metadata-only view of an unhandled dispatch.
 *
 * <p>No payload node, content, author, token, or raw JSON is retained. This makes the unknown
 * event safe to count and log without risking message-body or credential leakage.</p>
 */
public record OfficialQqUnknownEvent(
        String eventId,
        String eventType,
        long sequence,
        Instant receivedAt,
        int payloadLength) implements OfficialQqDispatch {

    public OfficialQqUnknownEvent {
        eventId = eventId == null ? "" : eventId;
        eventType = eventType == null ? "" : eventType;
        receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        if (payloadLength < 0) {
            payloadLength = 0;
        }
    }

    /**
     * Safe diagnostic form. Unknown event ids/types are attacker-controlled and are deliberately
     * omitted; only numeric metadata is emitted.
     */
    @Override
    public String toString() {
        return "OfficialQqUnknownEvent[payloadLength=" + payloadLength
                + ", sequence=" + sequence
                + ", receivedAt=" + receivedAt + "]";
    }
}
