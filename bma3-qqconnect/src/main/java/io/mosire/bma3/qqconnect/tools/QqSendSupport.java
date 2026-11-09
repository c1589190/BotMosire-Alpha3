package io.mosire.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared outbound send path with 2000-char chunking and the three-state result. */
final class QqSendSupport {

    static final int MAX_TEXT = 2000;

    private QqSendSupport() {
    }

    static String sendText(QqToolContext context, SourceAddress source, String text,
                           String replyToMessageId, String actionName) {
        OneBot11Client client = QqToolSupport.client(context, source);
        List<String> chunks = chunks(text);
        StringBuilder states = new StringBuilder("[");
        for (int index = 0; index < chunks.size(); index++) {
            String chunk = chunks.get(index);
            var stored = context.board().append(new InboundMessage(
                    source, "out-" + UUID.randomUUID(), Instant.now(),
                    List.of(new Segment("text", chunk)), Direction.OUTBOUND));
            Object message;
            if (replyToMessageId != null && !replyToMessageId.isBlank() && index == 0) {
                message = List.of(
                        Map.of("type", "reply", "data", Map.of("id", replyToMessageId)),
                        Map.of("type", "text", "data", Map.of("text", chunk)));
            } else {
                message = chunk;
            }
            Map<String, Object> params;
            if ("qq_group".equals(source.platform())) {
                params = Map.of("group_id", Long.parseLong(source.segments().get(1)),
                        "message", message);
            } else {
                params = Map.of("user_id", Long.parseLong(source.segments().get(1)),
                        "message", message);
            }
            String state;
            String messageId = "";
            try {
                JsonNode response = client.sendAction(actionName, params).join();
                boolean ok = "ok".equals(response.path("status").asText())
                        && response.path("retcode").asInt(-1) == 0;
                state = ok ? "SENT" : "FAILED_BEFORE_SEND";
                messageId = response.path("data").path("message_id").asText("");
            } catch (RuntimeException failure) {
                state = "UNKNOWN";
            }
            if (states.length() > 1) {
                states.append(',');
            }
            states.append("{\"localSeq\":").append(stored.message().localSeq())
                    .append(",\"state\":\"").append(state).append("\"")
                    .append(",\"messageId\":\"").append(messageId).append("\"}");
            if (!"SENT".equals(state)) {
                break;
            }
        }
        states.append(']');
        return states.toString();
    }

    private static List<String> chunks(String text) {
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += MAX_TEXT) {
            chunks.add(text.substring(start, Math.min(text.length(), start + MAX_TEXT)));
        }
        return chunks.isEmpty() ? List.of("") : chunks;
    }
}
