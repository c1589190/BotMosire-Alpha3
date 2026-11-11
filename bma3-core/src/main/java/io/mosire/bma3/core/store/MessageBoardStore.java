package io.mosire.bma3.core.store;

import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.source.SourceAddress;
import java.util.List;
import java.util.Optional;

/** Message board persistence port. */
public interface MessageBoardStore extends AutoCloseable {

    AppendResult append(InboundMessage inbound);

    List<Message> read(SourceAddress source, long afterSeq, int limit);

    /** Thread metadata, newest first; never includes full message bodies. */
    List<ThreadSummary> threads(int limit);

    /**
     * Records the platform message id of an already-persisted row. Outbound rows are written
     * before the send (so an {@code UNKNOWN} send is never lost) and learn their platform id only
     * afterwards.
     */
    void attachExternalMessageId(long localSeq, String externalMessageId);

    /** Reverse lookup used to answer "is the quoted message one of mine?". */
    Optional<Message> findByExternalMessageId(SourceAddress source, String externalMessageId);

    @Override
    void close();
}
