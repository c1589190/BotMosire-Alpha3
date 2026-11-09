package io.mosire.bma3.core.message;

import io.mosire.bma3.core.source.SourceAddress;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A normalized inbound message before it receives a message-board sequence. */
public record InboundMessage(
        SourceAddress source,
        String messageId,
        Instant at,
        List<Segment> segments,
        Direction direction) {

    public InboundMessage {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(direction, "direction");
        messageId = messageId == null ? "" : messageId;
        segments = segments == null ? List.of() : List.copyOf(segments);
    }
}
