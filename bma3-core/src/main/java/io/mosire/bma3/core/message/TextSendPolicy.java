package io.mosire.bma3.core.message;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider-neutral outbound text limit and overflow policy.
 *
 * <p>Every adapter shares this exact codepoint-counting and splitting implementation. The policy
 * never truncates silently: {@code reject} raises the bounded {@code TEXT_TOO_LONG} error and
 * {@code split} produces codepoint-safe chunks. A surrogate pair is never split between chunks.</p>
 */
public record TextSendPolicy(int maxTextChars, String overflow) {

    public static final int DEFAULT_MAX_TEXT_CHARS = 3000;
    public static final String REJECT = "reject";
    public static final String SPLIT = "split";

    public TextSendPolicy {
        overflow = overflow == null || overflow.isBlank() ? REJECT : overflow.trim();
        if (maxTextChars < 1 || maxTextChars > DEFAULT_MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("maxTextChars 必须在 1..3000");
        }
        if (!REJECT.equals(overflow) && !SPLIT.equals(overflow)) {
            throw new IllegalArgumentException("overflow 必须是 reject/split");
        }
    }

    public static TextSendPolicy defaults() {
        return new TextSendPolicy(DEFAULT_MAX_TEXT_CHARS, REJECT);
    }

    public static TextSendPolicy from(int maxTextChars, String overflow) {
        return new TextSendPolicy(maxTextChars, overflow);
    }

    /** Number of Unicode code points, matching the platform's character limit semantics. */
    public int count(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    /**
     * Validates a text under this policy.
     *
     * @throws IllegalArgumentException with a {@code TEXT_TOO_LONG} prefix when overflow is reject
     */
    public void validate(String text) {
        int count = count(text);
        if (count > maxTextChars) {
            throw tooLong(count);
        }
    }

    /**
     * Returns one chunk for text within the limit, or codepoint-safe chunks when {@code split} is
     * configured.
     *
     * @throws IllegalArgumentException with a {@code TEXT_TOO_LONG} prefix when overflow is reject
     */
    public List<String> chunks(String text) {
        String value = text == null ? "" : text;
        int count = count(value);
        if (count <= maxTextChars) {
            return List.of(value);
        }
        if (REJECT.equals(overflow)) {
            throw tooLong(count);
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int remaining = value.codePointCount(start, value.length());
            int end = value.offsetByCodePoints(start, Math.min(maxTextChars, remaining));
            chunks.add(value.substring(start, end));
            start = end;
        }
        return chunks.isEmpty() ? List.of("") : List.copyOf(chunks);
    }

    private IllegalArgumentException tooLong(int actual) {
        return new IllegalArgumentException("TEXT_TOO_LONG: actual=" + actual
                + " max=" + maxTextChars + "（当前上限，见 set_config/qq_policy）");
    }
}
