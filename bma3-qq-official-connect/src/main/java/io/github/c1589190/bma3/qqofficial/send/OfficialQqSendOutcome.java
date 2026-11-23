package io.github.c1589190.bma3.qqofficial.send;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.c1589190.bma3.core.message.DeliveryStatus;
import java.util.Objects;

/**
 * Safe tool-facing result for one dispatched/attempted chunk.
 *
 * <p>{@code status} is the conservative durable state. When {@code recorded=false}, the board is
 * still the source of truth and the result is never presented as a durable success.</p>
 */
public record OfficialQqSendOutcome(
        String operationId,
        int partIndex,
        int partTotal,
        Long localSeq,
        DeliveryStatus status,
        DeliveryStatus providerState,
        String externalMessageId,
        String errorCode,
        boolean recorded,
        Boolean dispatched) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public OfficialQqSendOutcome {
        operationId = Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(status, "status");
        if (!status.terminal()) {
            throw new IllegalArgumentException("出站结果必须是终止状态");
        }
        if (partIndex < 1 || partTotal < 1 || partIndex > partTotal) {
            throw new IllegalArgumentException("非法分片元数据");
        }
        externalMessageId = externalMessageId == null ? "" : externalMessageId;
        errorCode = errorCode == null || errorCode.isBlank() ? null : errorCode;
        if (!externalMessageId.isEmpty() && status != DeliveryStatus.SENT) {
            throw new IllegalArgumentException("只有 SENT 可以携带外部消息 ID");
        }
    }

    public boolean resendAllowed() {
        return false;
    }

    public ObjectNode json() {
        ObjectNode node = JSON.createObjectNode();
        node.put("operationId", operationId);
        node.put("partIndex", partIndex);
        node.put("partTotal", partTotal);
        if (localSeq == null) {
            node.putNull("localSeq");
        } else {
            node.put("localSeq", localSeq);
        }
        node.put("state", status.name());
        if (providerState != null) {
            node.put("providerState", providerState.name());
        }
        node.put("messageId", externalMessageId);
        if (errorCode != null) {
            node.put("errorCode", errorCode);
        }
        node.put("recorded", recorded);
        if (dispatched != null) {
            node.put("dispatched", dispatched);
        }
        node.put("resendAllowed", resendAllowed());
        return node;
    }
}
