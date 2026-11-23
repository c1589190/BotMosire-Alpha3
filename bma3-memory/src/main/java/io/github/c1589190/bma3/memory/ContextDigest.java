package io.github.c1589190.bma3.memory;

import java.time.Instant;

/**
 * One stored row of the rolling context copy.
 *
 * @param id        row id
 * @param sessionId DSH session the entry came from
 * @param eventSeq  DSH session event sequence number
 * @param kind      {@code user} | {@code assistant} | {@code tool}
 * @param speaker   display name, blank when unknown
 * @param at        event time, null when unknown
 * @param body      redacted body, possibly truncated
 * @param chars     stored body length in characters
 * @param truncated whether the body was cut at {@code memory.context.maxEntryChars}
 * @param pushedAt  when the plugin last pushed this row
 */
public record ContextDigest(long id, String sessionId, long eventSeq, String kind, String speaker,
        Instant at, String body, int chars, boolean truncated, Instant pushedAt) {

    /** Catalog line for the model: sequence, time, speaker and the first 60 characters. */
    public String digest(int chars) {
        String flat = body.replaceAll("\\s+", " ").strip();
        String head = flat.length() <= chars ? flat : flat.substring(0, Math.max(0, chars)) + "…";
        return "#" + eventSeq + " · " + (at == null ? "-" : at) + " · "
                + (speaker.isBlank() ? kind : speaker) + " · " + head;
    }
}
