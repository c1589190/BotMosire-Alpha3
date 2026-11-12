package io.mosire.bma3.core.message;

import io.mosire.bma3.core.source.SourceAddress;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A normalized message before it receives a message-board sequence.
 *
 * <p>{@code senderId}/{@code senderName} are the platform identity of whoever produced the row;
 * {@code senderCard} is the group card (群昵称) and {@code senderRole} the OneBot role. For outbound
 * rows they describe the bot account itself. They are persisted so history tools can answer "who
 * said this, and how were they named in this group" without replaying raw OneBot events.</p>
 */
public record InboundMessage(
        SourceAddress source,
        String messageId,
        Instant at,
        List<Segment> segments,
        Direction direction,
        String senderId,
        String senderName,
        String senderCard,
        String senderRole) {

    public InboundMessage {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(direction, "direction");
        messageId = messageId == null ? "" : messageId;
        segments = segments == null ? List.of() : List.copyOf(segments);
        senderId = senderId == null ? "" : senderId;
        senderName = senderName == null ? "" : senderName;
        senderCard = senderCard == null ? "" : senderCard;
        senderRole = senderRole == null ? "" : senderRole;
    }

    /** Compatibility constructor: no group card/role yet. */
    public InboundMessage(SourceAddress source, String messageId, Instant at,
                          List<Segment> segments, Direction direction,
                          String senderId, String senderName) {
        this(source, messageId, at, segments, direction, senderId, senderName, "", "");
    }

    /** Compatibility constructor: a message whose sender was not decoded. */
    public InboundMessage(SourceAddress source, String messageId, Instant at,
                          List<Segment> segments, Direction direction) {
        this(source, messageId, at, segments, direction, "", "");
    }

    public InboundMessage withSender(String id, String name) {
        return new InboundMessage(source, messageId, at, segments, direction, id, name,
                senderCard, senderRole);
    }

    public InboundMessage withSender(String id, String name, String card, String role) {
        return new InboundMessage(source, messageId, at, segments, direction, id, name, card, role);
    }

    /** Display name: group card wins over the QQ nickname. */
    public String displayName() {
        return senderCard.isBlank() ? senderName : senderCard;
    }
}
