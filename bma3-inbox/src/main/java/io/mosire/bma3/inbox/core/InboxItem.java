package io.mosire.bma3.inbox.core;

import java.time.Instant;

/** Persisted inbox item. */
public record InboxItem(
        String id,
        String source,
        String summary,
        int priority,
        ItemStatus status,
        Instant availableAt,
        Instant createdAt,
        int revision) {

    public InboxItem withStatus(ItemStatus newStatus) {
        return new InboxItem(id, source, summary, priority, newStatus,
                availableAt, createdAt, revision + 1);
    }
}
