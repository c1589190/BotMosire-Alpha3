package io.mosire.bma3.app.channel.router;

import io.mosire.bma3.app.channel.store.QqChannelObservationStore.Channel;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.inbox.core.InboxEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the bounded Inbox preview after a restart. Production adapters may inject their exact
 * channel renderer; the default is deterministic and body-safe for recovery tests/diagnostics.
 */
@FunctionalInterface
public interface QqChannelPreviewFactory {

    InboxEntry render(Channel channel, Message message);

    static QqChannelPreviewFactory generic() {
        return (channel, message) -> {
            StringBuilder text = new StringBuilder();
            List<String> markers = new ArrayList<>();
            for (Segment segment : message.inbound().segments()) {
                if (segment == null) {
                    continue;
                }
                if ("text".equals(segment.type())) {
                    if (!segment.text().isBlank()) {
                        if (!text.isEmpty()) {
                            text.append('\n');
                        }
                        text.append(segment.text());
                    }
                    continue;
                }
                markers.add(switch (segment.type()) {
                    case "at" -> segment.text().isBlank() ? "[@]" : "[@" + segment.text() + "]";
                    case "reply", "quote" -> "[引用]";
                    case "image" -> "[图片]";
                    case "video" -> "[视频]";
                    case "record" -> "[语音]";
                    case "file" -> "[文件]";
                    case "forward" -> "[合并转发]";
                    case "json", "xml", "card" -> "[卡片]";
                    default -> "[" + segment.type() + "]";
                });
            }
            String sender = message.inbound().displayName().isBlank()
                    ? message.inbound().senderId() : message.inbound().displayName();
            return new InboxEntry(message.localSeq(), message.inbound().at(), sender,
                    text.toString(), markers, channel.wireName(),
                    Map.of("rawSource", message.inbound().source().format(),
                            "messageId", message.inbound().messageId()));
        };
    }
}
