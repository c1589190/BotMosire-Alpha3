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
        SendRoute route = route(source, actionName, extras);
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
            Map<String, Object> params = route.params(message);
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
            QqDelivery.ProviderResult providerResult = client.connected()
                    ? QqDelivery.dispatch(client, actionName, params, QqDelivery.TEXT_WAIT)
                    : QqDelivery.failedBeforeSend(QqDelivery.NOT_DISPATCHED);
            QqDelivery.Outcome outcome = QqDelivery.finish(context, pending, providerResult);
            outcomes.add(outcome);
            if (outcome.status() != DeliveryStatus.SENT || !outcome.recorded()) {
                break;
            }
        }
        return outcomes;
    }

    /**
     * Strict OneBot source/action routing. This runs before both the board PENDING row and the
     * provider call. Only the two existing QQ platforms are accepted, each source must be exactly
     * {@code platform/accountId/positiveNumericTarget}, and extras are limited to the existing
     * private temporary-conversation {@code group_id}.
     */
    static SendRoute route(SourceAddress source, String actionName, Map<String, Object> extras) {
        if (source == null) {
            throw new IllegalArgumentException("QQ source 不能为空");
        }
        String platform = source.platform();
        if (!"qq_group".equals(platform) && !"qq_private".equals(platform)) {
            throw new IllegalArgumentException("不支持的 QQ source platform");
        }
        if (source.segments().size() != 2) {
            throw new IllegalArgumentException(
                    "QQ source 必须严格为 platform/accountId/target");
        }
        String action = actionName == null ? "" : actionName;
        if ("qq_group".equals(platform)) {
            if (!"send_group_msg".equals(action) && !"upload_group_file".equals(action)) {
                throw new IllegalArgumentException(
                        "qq_group 只能使用 send_group_msg 或 upload_group_file");
            }
        } else if (!"send_private_msg".equals(action)
                && !"upload_private_file".equals(action)) {
            throw new IllegalArgumentException(
                    "qq_private 只能使用 send_private_msg 或 upload_private_file");
        }

        String targetText = source.segments().get(1);
        if (!targetText.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("QQ source 目标必须是正整数");
        }
        long target;
        try {
            target = Long.parseLong(targetText);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("QQ source 目标必须是正整数");
        }
        if (target <= 0L) {
            throw new IllegalArgumentException("QQ source 目标必须是正整数");
        }

        Map<String, Object> extra = extras == null ? Map.of() : extras;
        Long temporaryGroup = null;
        if (!extra.isEmpty()) {
            if (!"qq_private".equals(platform) || extra.size() != 1
                    || !extra.containsKey("group_id")) {
                throw new IllegalArgumentException(
                        "QQ 发送 extras 仅允许 qq_private 的 group_id 临时会话");
            }
            temporaryGroup = positiveIntegral(extra.get("group_id"));
        }
        return new SendRoute(platform, target, temporaryGroup);
    }

    private static long positiveIntegral(Object value) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            if (number > 0L) {
                return number;
            }
        }
        throw new IllegalArgumentException("group_id 必须是正整数");
    }

    static record SendRoute(String platform, long target, Long temporaryGroup) {

        Map<String, Object> targetParams() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("qq_group".equals(platform) ? "group_id" : "user_id", target);
            return values;
        }

        Map<String, Object> params(Object message) {
            Map<String, Object> values = targetParams();
            values.put("message", message);
            if (temporaryGroup != null) {
                values.put("group_id", temporaryGroup);
            }
            return values;
        }
    }

    static void validateText(String text, QqToolContext context) {
        context.policy().textPolicy().validate(text);
    }

    static List<String> chunks(String text, QqSendPolicy policy) {
        return policy.textPolicy().chunks(text);
    }
}
