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

    /**
     * Full-text-ish search over the board: message text, sender nickname and group card.
     *
     * @param query        literal substring (case-insensitive via SQLite LIKE)
     * @param sourcePrefix optional source prefix, e.g. {@code qq_group/955432692}
     * @param from         optional lower bound (inclusive)
     * @param to           optional upper bound (inclusive)
     * @param beforeSeq    only rows with {@code local_seq < beforeSeq} (0 = no bound)
     * @param limit        1..500
     */
    List<Message> search(String query, String sourcePrefix, java.time.Instant from,
                         java.time.Instant to, long beforeSeq, int limit);

    /** Cheap liveness check used by the watchdog. */
    boolean ping();

    @Override
    void close();
}
