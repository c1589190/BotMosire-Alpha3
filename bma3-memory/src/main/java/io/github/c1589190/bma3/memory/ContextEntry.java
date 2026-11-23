package io.github.c1589190.bma3.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * One pushed context entry, as the DSH plugin sees it in its own session log.
 *
 * @param eventSeq DSH session event sequence number
 * @param kind     {@code user} | {@code assistant} | {@code tool}
 * @param speaker  display name, may be blank
 * @param at       event time, may be null when the plugin cannot tell
 * @param body     full text; redacted and possibly truncated by the store
 */
public record ContextEntry(long eventSeq, String kind, String speaker, Instant at, String body) {

    public ContextEntry {
        if (eventSeq < 0) {
            throw new IllegalArgumentException("eventSeq 不能为负");
        }
        kind = Objects.requireNonNull(kind, "kind").trim().toLowerCase(java.util.Locale.ROOT);
        speaker = speaker == null ? "" : speaker;
        body = body == null ? "" : body;
    }
}
