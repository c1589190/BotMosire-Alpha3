package io.mosire.bma3.core.message;

import java.util.Objects;

/** A persisted message-board row. */
public record Message(long localSeq, InboundMessage inbound) {

    public Message {
        if (localSeq < 0) {
            throw new IllegalArgumentException("localSeq 不能为负");
        }
        Objects.requireNonNull(inbound, "inbound");
    }
}
