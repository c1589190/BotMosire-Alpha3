package io.mosire.bma3.qqofficial.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.bma3.core.message.DeliveryStatus;
import java.util.Objects;

/**
 * Safe tool-facing result for one official media attempt.
 *
 * <p>It deliberately contains no path, file name, message content, {@code file_info}, Base64 bytes
 * or provider response body. {@code resendAllowed} is always false.</p>
 */
public record OfficialQqMediaOutcome(
        String operationId,
        Long localSeq,
        DeliveryStatus status,
        DeliveryStatus providerState,
        String externalMessageId,
        String errorCode,
        boolean recorded,
        Boolean dispatched,
        int fileType,
        long bytes,
        OfficialQqMediaUploadState uploadState) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public OfficialQqMediaOutcome {
        operationId = Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(uploadState, "uploadState");
        if (!status.terminal()) {
            throw new IllegalArgumentException("媒体出站结果必须是终止状态");
        }
        if (fileType < 1 || fileType > 4) {
            throw new IllegalArgumentException("非法 fileType");
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes 不能为负");
        }
        if (localSeq != null && localSeq < 1) {
            throw new IllegalArgumentException("localSeq 必须为正");
        }
        if (localSeq == null && (recorded || status != DeliveryStatus.FAILED_BEFORE_SEND)) {
            throw new IllegalArgumentException("没有板上行时只能报告未派发的板写失败");
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
        node.put("fileType", fileType);
        node.put("bytes", bytes);
        node.put("uploadState", uploadState.name());
        node.put("resendAllowed", resendAllowed());
        return node;
    }
}
