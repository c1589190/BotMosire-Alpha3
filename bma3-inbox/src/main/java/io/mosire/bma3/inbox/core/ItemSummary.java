package io.mosire.bma3.inbox.core;

import java.time.Instant;

/** Compact item view; never carries full message content. */
public record ItemSummary(
        String id,
        String source,
        String summary,
        int priority,
        ItemStatus status,
        Instant availableAt,
        int revision,
        boolean continued) {
}
