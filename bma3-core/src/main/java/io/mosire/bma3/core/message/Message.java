package io.mosire.bma3.core.message;

import java.util.Objects;

/**
 * A persisted message-board row.
 *
 * <p>{@code externalMessageId} is the platform's own message id when it is known. Inbound rows
 * store it at append time; outbound rows learn it only after a successful send, so it stays
 * blank until {@code attachExternalMessageId} fills it in. It is the join key that answers
 * "is the message being quoted one of mine?".</p>
 */
public record Message(long localSeq, InboundMessage inbound, String externalMessageId) {

    public Message {
        if (localSeq < 0) {
            throw new IllegalArgumentException("localSeq 不能为负");
        }
        Objects.requireNonNull(inbound, "inbound");
        externalMessageId = externalMessageId == null ? "" : externalMessageId;
    }

    /** Compatibility constructor: no external id recorded yet. */
    public Message(long localSeq, InboundMessage inbound) {
        this(localSeq, inbound, "");
    }

    public boolean self() {
        return inbound.direction() == Direction.OUTBOUND;
    }

    public Message withExternalMessageId(String id) {
        return new Message(localSeq, inbound, id);
    }
}
