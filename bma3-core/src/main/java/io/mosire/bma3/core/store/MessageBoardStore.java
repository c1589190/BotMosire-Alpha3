package io.mosire.bma3.core.store;

import io.mosire.bma3.core.message.DeliveryStatus;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.source.SourceAddress;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Message board persistence port. */
public interface MessageBoardStore extends AutoCloseable {

    AppendResult append(InboundMessage inbound);

    /**
     * Persists a new outbound row as {@link DeliveryStatus#PENDING} with its operation and part
     * metadata, in the same INSERT transaction, before any provider call is made.
     *
     * @throws IllegalArgumentException when the message is inbound or the metadata is invalid
     * @throws IllegalStateException    when the message key already exists (fail closed: never
     *                                  hand the caller another operation's row)
     */
    AppendResult beginOutbound(InboundMessage message, String operationId, int partIndex,
                               int partTotal);

    /**
     * Atomically closes one existing {@code PENDING} outbound row, writing the terminal status and
     * the external message id together.
     *
     * @return {@code true} only when a {@code PENDING} outbound row matched; {@code false} for a
     *         missing row, an inbound row, a legacy row or an already-terminal row, which stay
     *         untouched. An external id is only accepted together with {@link DeliveryStatus#SENT}.
     * @throws IllegalArgumentException for a non-terminal status or invalid metadata
     */
    boolean finishOutbound(long localSeq, DeliveryStatus status, String externalMessageId,
                           String errorCode);

    /**
     * Conservatively converges every {@code PENDING} outbound row to
     * {@link DeliveryStatus#UNKNOWN}. Call this only after the application holds its
     * single-instance data-directory lock and before accepting requests; merely opening a store
     * must never do this.
     *
     * @return number of recovered rows
     */
    int recoverPendingOutbound();

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
     * Exact inbound fact lookup for channel replay/recovery. Unlike
     * {@link #findByExternalMessageId(SourceAddress, String)}, this query is restricted to
     * {@link Direction#INBOUND} and matches the persisted platform {@code message_id}, not the
     * optional outbound external id.
     */
    default Optional<Message> findInbound(SourceAddress source, String messageId) {
        throw new UnsupportedOperationException("findInbound is not implemented");
    }

    /**
     * Highest local sequence currently persisted for one raw source, or {@code 0} when the source
     * has no rows. Channel binding uses this as an inclusive future scan boundary.
     */
    default long maxLocalSeq(SourceAddress source) {
        throw new UnsupportedOperationException("maxLocalSeq is not implemented");
    }

    /**
     * True only when at least one {@link Direction#INBOUND} row exists for the exact source.
     *
     * <p>This is an observation predicate, not a sequence heuristic: outbound rows in any delivery
     * state must never make it return true. Non-SQLite implementations that do not support the
     * query return {@code false} (fail-closed); callers must not fall back to
     * {@link #maxLocalSeq(SourceAddress)} because outbound rows would then masquerade as inbound
     * observation.</p>
     */
    default boolean hasInbound(SourceAddress source) {
        Objects.requireNonNull(source, "source");
        return false;
    }

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
    /**
     * Whether {@code localSeq} names a row in this board. Memory entries that claim
     * {@code source_kind=message_board} are validated through this, so a pointer can never name a
     * message that does not exist.
     *
     * @param localSeq board-local sequence number
     * @return true when the row exists
     */
    default boolean hasLocalSeq(long localSeq) {
        throw new UnsupportedOperationException("hasLocalSeq is not implemented");
    }

    boolean ping();

    @Override
    void close();
}
