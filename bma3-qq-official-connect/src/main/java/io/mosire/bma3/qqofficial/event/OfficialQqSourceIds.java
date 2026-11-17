package io.mosire.bma3.qqofficial.event;

/**
 * Validation for values that become part of a raw source key.
 *
 * <p>Source addresses are slash-delimited opaque keys and the durable message-board key also
 * joins source and message id with {@code |}. Official openids/account ids are single segments,
 * and message ids are one key component, so slash, pipe, and whitespace/control characters must
 * be rejected before a store key can be built. Errors name the field but never echo the rejected
 * value.</p>
 */
final class OfficialQqSourceIds {

    private OfficialQqSourceIds() {
    }

    static String requireSegment(String value, String field) {
        if (value == null || value.isBlank() || !isSafeSegment(value)) {
            throw new IllegalArgumentException(field + " 必须是单个非空 source 段");
        }
        return value;
    }

    static String requireRawSource(String value) {
        if (value == null || value.isBlank()
                || containsWhitespaceOrControl(value)
                || value.indexOf('|') >= 0) {
            throw new IllegalArgumentException(
                    "rawSource 必须是非空且不含空白/控制字符或 | 的 source");
        }
        return value;
    }

    static String requireMessageId(String value) {
        if (value == null || value.isBlank()
                || containsWhitespaceOrControl(value)
                || value.indexOf('|') >= 0) {
            throw new IllegalArgumentException(
                    "messageId 必须是非空且不含空白/控制字符或 | 的官方消息 ID");
        }
        return value;
    }

    private static boolean isSafeSegment(String value) {
        if (value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.indexOf('|') >= 0) {
            return false;
        }
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            if (isUnsafeCodePoint(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean containsWhitespaceOrControl(String value) {
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            if (isUnsafeCodePoint(codePoint)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean isUnsafeCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.SURROGATE
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
