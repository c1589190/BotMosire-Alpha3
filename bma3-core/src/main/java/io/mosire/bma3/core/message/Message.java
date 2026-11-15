package io.mosire.bma3.core.message;

import java.util.Objects;

/**
 * A persisted message-board row.
 *
 * <p>{@code externalMessageId} is the platform's own message id when it is known. Inbound rows
 * store it at append time; outbound rows learn it only after a successful send, so it stays
 * blank until {@code attachExternalMessageId} fills it in. It is the join key that answers
 * "is the message being quoted one of mine?".</p>
 *
 * <p>{@code delivery} is the outbound delivery state, or {@code null} for inbound rows (and for
 * pre-delivery outbound rows until the store migrates them to
 * {@link DeliveryStatus#LEGACY_UNKNOWN}).</p>
 */
public record Message(long localSeq, InboundMessage inbound, String externalMessageId,
                      Delivery delivery) {

    public Message {
        if (localSeq < 0) {
            throw new IllegalArgumentException("localSeq 不能为负");
        }
        Objects.requireNonNull(inbound, "inbound");
        externalMessageId = externalMessageId == null ? "" : externalMessageId;
        if (inbound.direction() == Direction.INBOUND && delivery != null) {
            throw new IllegalArgumentException("入站记录不能有发送状态");
        }
    }

    /** Compatibility constructor: no external id and no delivery metadata recorded yet. */
    public Message(long localSeq, InboundMessage inbound) {
        this(localSeq, inbound, "");
    }

    /** Compatibility constructor: external id only, no delivery metadata. */
    public Message(long localSeq, InboundMessage inbound, String externalMessageId) {
        this(localSeq, inbound, externalMessageId, null);
    }

    public boolean self() {
        return inbound.direction() == Direction.OUTBOUND;
    }

    public Message withExternalMessageId(String id) {
        return new Message(localSeq, inbound, id, delivery);
    }
}
