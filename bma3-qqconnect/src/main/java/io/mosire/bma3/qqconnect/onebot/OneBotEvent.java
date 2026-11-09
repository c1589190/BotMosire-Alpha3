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
        String replyToMessageId) {

    public OneBotEvent {
        segments = segments == null ? List.of() : List.copyOf(segments);
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

    public SourceAddress source(String accountId) {
        if (groupId > 0L) {
            return SourceAddress.parse("qq_group/" + accountId + "/" + groupId);
        }
        return SourceAddress.parse("qq_private/" + accountId + "/" + userId);
    }
}
