package io.mosire.bma3.qqconnect.onebot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.bma3.core.message.Segment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Decodes OneBot 11 message events into the small BMA3 model. */
public final class OneBot11Codec {

    private final ObjectMapper mapper = new ObjectMapper();

    public Optional<OneBotEvent> decode(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !"message".equals(root.path("post_type").asText())) {
                return Optional.empty();
            }
            long time = root.path("time").asLong(Instant.now().getEpochSecond());
            long groupId = root.path("group_id").asLong(0L);
            List<Segment> segments = segments(root.path("message"));
            String replyTo = segments.stream()
                    .filter(segment -> "reply".equals(segment.type()))
                    .map(Segment::text)
                    .findFirst()
                    .orElse("");
            return Optional.of(new OneBotEvent(
                    root.path("post_type").asText(),
                    root.path("message_type").asText(),
                    root.path("sub_type").asText(""),
                    root.path("self_id").asLong(),
                    root.path("user_id").asLong(),
                    groupId,
                    root.path("message_id").asLong(),
                    Instant.ofEpochSecond(time),
                    root.path("sender").path("nickname").asText(""),
                    segments,
                    replyTo));
        } catch (Exception failure) {
            throw new IllegalArgumentException("OneBot 事件解析失败", failure);
        }
    }

    private static List<Segment> segments(JsonNode message) {
        if (message == null || message.isMissingNode() || message.isNull()) {
            return List.of();
        }
        if (message.isTextual()) {
            return List.of(new Segment("text", message.asText()));
        }
        List<Segment> segments = new ArrayList<>();
        for (JsonNode segment : message) {
            String type = segment.path("type").asText("text");
            JsonNode data = segment.path("data");
            String text = switch (type) {
                case "text" -> data.path("text").asText("");
                case "reply" -> data.path("id").asText("");
                case "at" -> data.path("qq").asText("");
                case "image" -> "[图片]";
                case "file" -> "[文件]";
                case "record" -> "[语音]";
                case "video" -> "[视频]";
                case "forward" -> "[合并转发]";
                default -> data.path("text").asText("[" + type + "]");
            };
            segments.add(new Segment(type, text));
        }
        return List.copyOf(segments);
    }
}
