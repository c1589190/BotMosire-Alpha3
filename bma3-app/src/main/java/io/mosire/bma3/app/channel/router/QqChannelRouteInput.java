package io.mosire.bma3.app.channel.router;

import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.source.SourceView;
import io.mosire.bma3.inbox.core.InboxEntry;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One decoded QQ message offered to the app-side channel router.
 *
 * <p>The priority/drop decision happens before this object reaches the router. A dropped input is
 * still represented explicitly so the router can prove that it writes nothing to the board,
 * channel database or inbox. {@code boardMessage} and {@code inboxEntry} are built by the channel
 * adapter; the body never enters the channel observation database.</p>
 */
public record QqChannelRouteInput(
        boolean dropped,
        String accountId,
        SourceAddress source,
        String messageId,
        String senderId,
        Direction direction,
        Instant messageAt,
        Instant observedAt,
        int priority,
        io.mosire.bma3.app.channel.store.QqChannelObservationStore.TimeReliability
                timeReliability,
        io.mosire.bma3.app.channel.store.QqChannelObservationStore.ContentIntegrity
                contentIntegrity,
        boolean structuredMentionsTrusted,
        List<Segment> segments,
        InboundMessage boardMessage,
        InboxEntry inboxEntry,
        RawProjectionMode rawProjectionMode,
        SourceView sourceView) {

    /**
     * Backward-compatible constructor for adapters/tests that have not supplied a labeled source
     * view. Production adapters should pass the exact SourceView returned by their SourceLabeler.
     */
    public QqChannelRouteInput(boolean dropped, String accountId, SourceAddress source,
                               String messageId, String senderId, Direction direction,
                               Instant messageAt, Instant observedAt, int priority,
                               io.mosire.bma3.app.channel.store.QqChannelObservationStore
                                       .TimeReliability timeReliability,
                               io.mosire.bma3.app.channel.store.QqChannelObservationStore
                                       .ContentIntegrity contentIntegrity,
                               boolean structuredMentionsTrusted, List<Segment> segments,
                               InboundMessage boardMessage, InboxEntry inboxEntry,
                               RawProjectionMode rawProjectionMode) {
        this(dropped, accountId, source, messageId, senderId, direction, messageAt, observedAt,
                priority, timeReliability, contentIntegrity, structuredMentionsTrusted,
                segments, boardMessage, inboxEntry, rawProjectionMode, null);
    }

    public QqChannelRouteInput {
        accountId = accountId == null ? "" : accountId.strip();
        Objects.requireNonNull(source, "source");
        messageId = messageId == null ? "" : messageId;
        senderId = senderId == null ? "" : senderId;
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(messageAt, "messageAt");
        observedAt = observedAt == null ? Instant.now() : observedAt;
        Objects.requireNonNull(timeReliability, "timeReliability");
        Objects.requireNonNull(contentIntegrity, "contentIntegrity");
        segments = segments == null ? List.of() : List.copyOf(segments);
        Objects.requireNonNull(boardMessage, "boardMessage");
        if (!source.equals(boardMessage.source())) {
            throw new IllegalArgumentException("source 与 boardMessage.source 不一致");
        }
        if (!messageId.equals(boardMessage.messageId())) {
            throw new IllegalArgumentException("messageId 与 boardMessage.messageId 不一致");
        }
        if (direction != boardMessage.direction()) {
            throw new IllegalArgumentException("direction 与 boardMessage.direction 不一致");
        }
        Objects.requireNonNull(rawProjectionMode, "rawProjectionMode");
        if (sourceView != null && !source.format().equals(sourceView.raw())) {
            throw new IllegalArgumentException("sourceView.raw 与 source 不一致");
        }
    }

    /** How an unbound source projects its raw body into Inbox. */
    public enum RawProjectionMode {
        /** Exact pre-router OneBot behavior: board duplicate stops before Inbox. */
        LEGACY_APPEND_OR_MERGE,
        /** Official behavior: a stable receipt can recover a post-board Inbox failure. */
        RECEIPT_ONCE
    }
}
