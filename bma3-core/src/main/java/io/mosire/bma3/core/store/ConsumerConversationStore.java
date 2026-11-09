package io.mosire.bma3.core.store;

import io.mosire.bma3.core.account.ConsumerId;
import io.mosire.bma3.core.source.SourceAddress;

/** Per-consumer read cursor. */
public interface ConsumerConversationStore {

    long readSeq(ConsumerId consumer, SourceAddress source);

    void ack(ConsumerId consumer, SourceAddress source, long seq);
}
