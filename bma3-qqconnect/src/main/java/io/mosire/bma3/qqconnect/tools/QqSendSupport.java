package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.message.DeliveryStatus;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared outbound text path: configured hard limit, chunking and three-state results. */
final class QqSendSupport {

    private QqSendSupport() {
    }

    static String sendText(QqToolContext context, SourceAddress source, String text,
                           String replyToMessageId, String actionName) {
        return sendText(context, source, text, replyToMessageId, actionName, Map.of());
    }

    /**
     * 同上，外加 payload 附加字段（目前只有群临时会话要往 {@code send_private_msg} 里塞 {@code group_id}）。
     */
    static String sendText(QqToolContext context, SourceAddress source, String text,
                           String replyToMessageId, String actionName,
                           Map<String, Object> extras) {
        return jsonArray(sendParts(context, source, text, replyToMessageId, actionName, extras));
    }

    /**
     * Entry-point result for the three send tools: a board write failure is an outer tool error
     * carrying the complete structured parts array; provider UNKNOWN stays an ordinary result
     * (it may already have been sent and must not be treated as retryable).
     */
    static ToolResult sendToolResult(QqToolContext context, SourceAddress source, String text,
                                     String replyToMessageId, String actionName) {
        return sendToolResult(context, source, text, replyToMessageId, actionName, Map.of());
    }

    static ToolResult sendToolResult(QqToolContext context, SourceAddress source, String text,
                                     String replyToMessageId, String actionName,
                                     Map<String, Object> extras) {
        List<QqDelivery.Outcome> outcomes = sendParts(context, source, text, replyToMessageId,
                actionName, extras);
        boolean boardWriteFailed = outcomes.stream().anyMatch(outcome ->
                QqDelivery.BOARD_WRITE_FAILED.equals(outcome.errorCode()));
        String json = jsonArray(outcomes, boardWriteFailed);
        return boardWriteFailed
                ? ToolResult.error(QqDelivery.BOARD_WRITE_FAILED, json)
                : ToolResult.ok(json);
    }

    private static String jsonArray(List<QqDelivery.Outcome> outcomes) {
        return jsonArray(outcomes, false);
    }

    private static String jsonArray(List<QqDelivery.Outcome> outcomes, boolean resendForbidden) {
        var array = QqToolSupport.JSON.createArrayNode();
        outcomes.forEach(outcome -> {
            var node = QqDelivery.json(outcome);
            if (resendForbidden) {
                // The whole operation is not resendable once any part could not be recorded.
                node.put("resendAllowed", false);
            }
            array.add(node);
        });
        return array.toString();
    }

    /**
     * Persists one PENDING row per actual chunk before dispatch and stops after the first part that
     * is not {@link DeliveryStatus#SENT} or whose terminal state could not be recorded.
     */
    static List<QqDelivery.Outcome> sendParts(QqToolContext context, SourceAddress source,
                                              String text, String replyToMessageId,
                                              String actionName, Map<String, Object> extras) {
        QqSendPolicy policy = context.policy();
        List<String> chunks = chunks(text, policy);
        OneBot11Client client = QqToolSupport.client(context, source);
        var self = context.accounts().self(source.accountId())
                .orElse(new io.mosire.bma3.qqconnect.account.AccountRegistry.SelfIdentity("", ""));
        // One fresh server-generated operation id per tool call; each chunk is a part of it.
        String operationId = "op-" + UUID.randomUUID();
        List<QqDelivery.Outcome> outcomes = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            String chunk = chunks.get(index);
            InboundMessage outbound = new InboundMessage(
                    source, "out-" + UUID.randomUUID(), Instant.now(),
                    List.of(new Segment("text", chunk)), Direction.OUTBOUND,
                    self.userId(), self.nickname());
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
            } else if (extras.isEmpty()) {
                params = Map.of("user_id", Long.parseLong(source.segments().get(1)),
                        "message", message);
            } else {
                // 群临时会话：user_id + group_id 一起给
                Map<String, Object> withExtras = new LinkedHashMap<>();
                withExtras.put("user_id", Long.parseLong(source.segments().get(1)));
                withExtras.put("message", message);
                withExtras.putAll(extras);
                params = withExtras;
            }
            // Persist PENDING only after the request is fully built, and before it is dispatched.
            // A failed part write must not lose the earlier parts' structured results, and this
            // part's provider must not be called.
            QqDelivery.Pending pending;
            try {
                pending = QqDelivery.begin(context, outbound, operationId,
                        index + 1, chunks.size());
            } catch (RuntimeException failure) {
                outcomes.add(QqDelivery.boardWriteFailure(operationId, index + 1, chunks.size()));
                break;
            }
            QqDelivery.ProviderResult providerResult = QqDelivery.dispatch(client, actionName,
                    params, QqDelivery.TEXT_WAIT);
            QqDelivery.Outcome outcome = QqDelivery.finish(context, pending, providerResult);
            outcomes.add(outcome);
            if (outcome.status() != DeliveryStatus.SENT || !outcome.recorded()) {
                break;
            }
        }
        return outcomes;
    }

    static void validateText(String text, QqToolContext context) {
        QqSendPolicy policy = context.policy();
        int count = text == null ? 0 : text.codePointCount(0, text.length());
        if (count > policy.maxTextChars()) {
            throw new IllegalArgumentException("TEXT_TOO_LONG: actual=" + count
                    + " max=" + policy.maxTextChars() + "（当前上限，见 set_config/qq_policy）");
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
                    + " max=" + policy.maxTextChars() + "（当前上限，见 set_config/qq_policy）");
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
