package io.mosire.bma3.core.store;

import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.source.SourceAddress;
import java.util.List;

/** Message board persistence port. */
public interface MessageBoardStore extends AutoCloseable {

    AppendResult append(InboundMessage inbound);

    List<Message> read(SourceAddress source, long afterSeq, int limit);

    /** Thread metadata, newest first; never includes full message bodies. */
    List<ThreadSummary> threads(int limit);

    @Override
    void close();
}
