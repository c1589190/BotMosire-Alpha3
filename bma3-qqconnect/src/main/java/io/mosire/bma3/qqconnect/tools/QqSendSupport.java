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

/** Shared outbound text path: configured hard limit, chunking and three-state results. */
final class QqSendSupport {

    private QqSendSupport() {
    }

    static String sendText(QqToolContext context, SourceAddress source, String text,
                           String replyToMessageId, String actionName) {
        QqSendPolicy policy = context.policy();
        List<String> chunks = chunks(text, policy);
        OneBot11Client client = QqToolSupport.client(context, source);
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

    static void validateText(String text, QqToolContext context) {
        QqSendPolicy policy = context.policy();
        int count = text == null ? 0 : text.codePointCount(0, text.length());
        if (count > policy.maxTextChars()) {
            throw new IllegalArgumentException("TEXT_TOO_LONG: actual=" + count
                    + " max=" + policy.maxTextChars());
        }
    }

    static List<String> chunks(String text, QqSendPolicy policy) {
        String value = text == null ? "" : text;
        int count = value.codePointCount(0, value.length());
        if (count <= policy.maxTextChars()) {
            return List.of(value);
        }
        if ("reject".equals(policy.overflow())) {
            throw new IllegalArgumentException("TEXT_TOO_LONG: actual=" + count
                    + " max=" + policy.maxTextChars());
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int end = value.offsetByCodePoints(start,
                    Math.min(policy.maxTextChars(), value.codePointCount(start, value.length())));
            chunks.add(value.substring(start, end));
            start = end;
        }
        return chunks.isEmpty() ? List.of("") : chunks;
    }
}
