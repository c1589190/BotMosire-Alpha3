package io.github.c1589190.bma3.core.message;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Delivery metadata attached to one outbound message-board row.
 *
 * <p>{@code operationId} ties the parts of one logical send together; {@code partIndex} is
 * 1-based and never exceeds {@code partTotal}. Historical rows migrated from a pre-delivery
 * database have no operation or part information: their status is {@link DeliveryStatus#LEGACY_UNKNOWN},
 * {@code operationId} is blank and both part numbers are zero.</p>
 *
 * <p>{@code errorCode} is a bounded category such as {@code TIMEOUT} or
 * {@code PROCESS_INTERRUPTED}. Raw provider messages and exception text are rejected here so they
 * can never reach the board.</p>
 */
public record Delivery(String operationId, int partIndex, int partTotal,
                       DeliveryStatus status, String errorCode) {

    private static final Pattern BOUNDED_ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public Delivery {
        Objects.requireNonNull(status, "status");
        operationId = operationId == null ? "" : operationId;
        errorCode = normalizeErrorCode(errorCode);
        if (status == DeliveryStatus.LEGACY_UNKNOWN) {
            if (!operationId.isBlank()) {
                throw new IllegalArgumentException("历史出站记录没有操作标识");
            }
            if (partIndex != 0 || partTotal != 0) {
                throw new IllegalArgumentException("历史出站记录没有分片信息");
            }
        } else {
            if (operationId.isBlank()) {
                throw new IllegalArgumentException("操作标识不能为空");
            }
            if (partTotal < 1 || partIndex < 1 || partIndex > partTotal) {
                throw new IllegalArgumentException("分片序号必须在 1..partTotal 内");
            }
        }
        if (errorCode != null
                && status != DeliveryStatus.FAILED_BEFORE_SEND
                && status != DeliveryStatus.UNKNOWN) {
            throw new IllegalArgumentException("只有失败或不确定状态可以携带错误类别");
        }
    }

    /**
     * Normalizes an optional bounded error category: blank becomes {@code null}, anything that is
     * not an uppercase category is rejected without echoing the rejected text.
     */
    public static String normalizeErrorCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (!BOUNDED_ERROR_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("错误类别必须是有界大写标识（例如 TIMEOUT）");
        }
        return value;
    }
}
