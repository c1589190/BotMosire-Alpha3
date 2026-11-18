package io.mosire.bma3.memory;

import java.time.Instant;

/**
 * One notebook entry. {@code weight} is the ranking bias ({@link MemoryOptions#MUTED} means
 * muted); {@code contextRef} anchors the entry to a DSH session event, {@code sourceRef} to a
 * message-board {@code local_seq} — both optional, both auditable.
 */
public record MemoryItem(long id, String text, String tags, double weight, String sourceKind,
        String sourceRef, String contextRef, Instant createdAt, Instant lastUsedAt,
        long useCount) {

    public boolean muted() {
        return weight == MemoryOptions.MUTED;
    }
}
