package io.mosire.bma3.core.store;

import io.mosire.bma3.core.message.Message;

/** Result of appending one inbound message. */
public record AppendResult(Message message, boolean created) {
}
