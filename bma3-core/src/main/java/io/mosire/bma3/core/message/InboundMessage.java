package io.mosire.bma3.core.message;

import io.mosire.bma3.core.source.SourceAddress;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A normalized message before it receives a message-board sequence.
 *
 * <p>{@code senderId}/{@code senderName} are the platform identity of whoever produced the row.
 * For outbound rows they describe the bot account itself. They are persisted so that history
 * tools can answer "who said this" without replaying raw OneBot events.</p>
 */
public record InboundMessage(
        SourceAddress source,
        String messageId,
        Instant at,
        List<Segment> segments,
        Direction direction,
        String senderId,
        String senderName) {

    public InboundMessage {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(direction, "direction");
        messageId = messageId == null ? "" : messageId;
        segments = segments == null ? List.of() : List.copyOf(segments);
        senderId = senderId == null ? "" : senderId;
        senderName = senderName == null ? "" : senderName;
    }

    /** Compatibility constructor: a message whose sender was not decoded. */
    public InboundMessage(SourceAddress source, String messageId, Instant at,
                          List<Segment> segments, Direction direction) {
        this(source, messageId, at, segments, direction, "", "");
    }

    public InboundMessage withSender(String id, String name) {
        return new InboundMessage(source, messageId, at, segments, direction, id, name);
    }
}
