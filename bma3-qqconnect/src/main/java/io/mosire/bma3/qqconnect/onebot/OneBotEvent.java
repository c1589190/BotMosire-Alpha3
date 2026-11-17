package io.mosire.bma3.qqconnect.onebot;

import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import java.time.Instant;
import java.util.List;

/** Normalized subset of an OneBot 11 message event. */
public record OneBotEvent(
        String postType,
        String messageType,
        String subType,
        long selfId,
        long userId,
        long groupId,
        long messageId,
        Instant at,
        String senderName,
        List<Segment> segments,
        String replyToMessageId,
        /** Group card / 群名片; blank for private chats and for members without a card. */
        String senderCard,
        /** OneBot role: owner/admin/member; blank when the provider did not send it. */
        String senderRole,
        /** True when {@link #at()} is a server fallback rather than a trusted platform timestamp. */
        boolean timestampFallback,
        /**
         * True only when the codec saw a complete, understood segment array and changed nothing.
         * Older constructors default this to false because their provenance is unknown.
         */
        boolean contentComplete) {

    public OneBotEvent {
        segments = segments == null ? List.of() : List.copyOf(segments);
        senderName = senderName == null ? "" : senderName;
        replyToMessageId = replyToMessageId == null ? "" : replyToMessageId;
        senderCard = senderCard == null ? "" : senderCard;
        senderRole = senderRole == null ? "" : senderRole;
    }

    /**
     * Compatibility constructor for callers that have an explicit timestamp but do not know
     * whether the segment payload was complete. The explicit time is treated as reliable;
     * content completeness is conservatively false.
     */
    public OneBotEvent(String postType, String messageType, String subType, long selfId,
                       long userId, long groupId, long messageId, Instant at,
                       String senderName, List<Segment> segments, String replyToMessageId,
                       String senderCard, String senderRole) {
        this(postType, messageType, subType, selfId, userId, groupId, messageId, at,
                senderName, segments, replyToMessageId, senderCard, senderRole, false, false);
    }

    /** Compatibility constructor used by older callers/tests. */
    public OneBotEvent(String postType, String messageType, String subType, long selfId,
                       long userId, long groupId, long messageId, Instant at,
                       String senderName, List<Segment> segments, String replyToMessageId) {
        this(postType, messageType, subType, selfId, userId, groupId, messageId, at,
                senderName, segments, replyToMessageId, "", "");
    }

    public String text() {
        StringBuilder text = new StringBuilder();
        for (Segment segment : segments) {
            if (!"text".equals(segment.type())) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(segment.text());
        }
        return text.toString();
    }

    /** Card if present, otherwise the QQ nickname. Used for display only, never as a score key. */
    public String displayName() {
        return senderCard.isBlank() ? senderName : senderCard;
    }

    public SourceAddress source(String accountId) {
        if (groupId > 0L) {
            return SourceAddress.parse("qq_group/" + accountId + "/" + groupId);
        }
        return SourceAddress.parse("qq_private/" + accountId + "/" + userId);
    }
}
