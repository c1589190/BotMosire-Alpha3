package io.mosire.bma3.core.message;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One normalized message segment.
 *
 * <p>{@code text} is the rendered placeholder used by panel/board text (for example
 * {@code [图片]}); {@code data} carries the structured payload the adapter managed to decode
 * (image {@code file}/{@code url}/{@code subType}, reply {@code id}, at {@code qq}/{@code name},
 * forward {@code id}/{@code count} …). {@code data} is never rendered into
 * {@code chat_message.text} and never enters the Inbox panel; it exists so tools can resolve
 * media and relations on demand.</p>
 */
public record Segment(String type, String text, Map<String, String> data) {

    public Segment {
        Objects.requireNonNull(type, "type");
        text = text == null ? "" : text;
        if (data == null || data.isEmpty()) {
            data = Map.of();
        } else {
            Map<String, String> copy = new LinkedHashMap<>();
            data.forEach((key, value) -> {
                if (key != null && value != null && !value.isBlank()) {
                    copy.put(key, value);
                }
            });
            data = Map.copyOf(copy);
        }
    }

    /** Compatibility constructor: a segment with no structured payload. */
    public Segment(String type, String text) {
        this(type, text, Map.of());
    }

    public String data(String key) {
        return data.getOrDefault(key, "");
    }
}
