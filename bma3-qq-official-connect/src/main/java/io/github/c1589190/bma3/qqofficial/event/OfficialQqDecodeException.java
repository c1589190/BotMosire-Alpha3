package io.github.c1589190.bma3.qqofficial.event;

/**
 * Safe decoder failure.
 *
 * <p>The exception deliberately carries no raw JSON and no cause: Jackson and other parsers may
 * embed input fragments in their exception messages, which could expose message bodies or
 * {@code auth_token}. Callers get a stable code plus a non-sensitive category instead.</p>
 */
public final class OfficialQqDecodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String MALFORMED_JSON = "MALFORMED_JSON";
    public static final String NOT_AN_OBJECT = "NOT_AN_OBJECT";
    public static final String MISSING_EVENT_TYPE = "MISSING_EVENT_TYPE";
    public static final String MISSING_MESSAGE_ID = "MISSING_MESSAGE_ID";
    public static final String INVALID_MESSAGE_ID = "INVALID_MESSAGE_ID";
    public static final String CONTENT_TOO_LARGE = "CONTENT_TOO_LARGE";
    public static final String SEGMENT_LIMIT_EXCEEDED = "SEGMENT_LIMIT_EXCEEDED";
    public static final String ATTACHMENT_LIMIT_EXCEEDED = "ATTACHMENT_LIMIT_EXCEEDED";
    public static final String MENTION_LIMIT_EXCEEDED = "MENTION_LIMIT_EXCEEDED";
    public static final String NESTING_LIMIT_EXCEEDED = "NESTING_LIMIT_EXCEEDED";
    public static final String INVALID_EVENT = "INVALID_EVENT";

    private final String code;

    public OfficialQqDecodeException(String code, String message) {
        super(message);
        this.code = code == null ? INVALID_EVENT : code;
    }

    public String code() {
        return code;
    }

    public static OfficialQqDecodeException malformed(Throwable failure) {
        String category = failure == null ? "unknown" : failure.getClass().getSimpleName();
        return new OfficialQqDecodeException(
                MALFORMED_JSON,
                "官方 QQ Gateway dispatch JSON 解析失败（原因类型 " + category + "）");
    }

    public static OfficialQqDecodeException notAnObject() {
        return new OfficialQqDecodeException(NOT_AN_OBJECT, "官方 QQ Gateway dispatch 不是 JSON 对象");
    }

    public static OfficialQqDecodeException missingEventType() {
        return new OfficialQqDecodeException(
                MISSING_EVENT_TYPE, "官方 QQ Gateway dispatch 缺少非空 t 事件名");
    }

    public static OfficialQqDecodeException missingMessageId() {
        return new OfficialQqDecodeException(
                MISSING_MESSAGE_ID, "官方 QQ 消息事件缺少 message id，已 fail-closed");
    }

    public static OfficialQqDecodeException invalidMessageId() {
        return new OfficialQqDecodeException(
                INVALID_MESSAGE_ID, "官方 QQ 消息事件的 message id 非法，已 fail-closed");
    }

    public static OfficialQqDecodeException contentTooLarge() {
        return new OfficialQqDecodeException(
                CONTENT_TOO_LARGE, "官方 QQ 消息正文超过安全上限，已 fail-closed");
    }

    public static OfficialQqDecodeException segmentLimitExceeded() {
        return new OfficialQqDecodeException(
                SEGMENT_LIMIT_EXCEEDED, "官方 QQ 消息 segment 数量超过安全上限，已 fail-closed");
    }

    public static OfficialQqDecodeException attachmentLimitExceeded() {
        return new OfficialQqDecodeException(
                ATTACHMENT_LIMIT_EXCEEDED, "官方 QQ 消息附件数量超过安全上限，已 fail-closed");
    }

    public static OfficialQqDecodeException mentionLimitExceeded() {
        return new OfficialQqDecodeException(
                MENTION_LIMIT_EXCEEDED, "官方 QQ 消息 mention 数量超过安全上限，已 fail-closed");
    }

    public static OfficialQqDecodeException nestingLimitExceeded() {
        return new OfficialQqDecodeException(
                NESTING_LIMIT_EXCEEDED, "官方 QQ 消息嵌套层数或元素数量超过安全上限，已 fail-closed");
    }
}
