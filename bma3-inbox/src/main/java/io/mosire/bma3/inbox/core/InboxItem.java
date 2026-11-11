package io.mosire.bma3.inbox.core;

import java.time.Instant;
import java.util.List;

/** Persisted inbox item: a bounded source window plus a server-managed revision. */
public record InboxItem(
        String id,
        String source,
        String sourceLabel,
        String summary,
        List<InboxEntry> entries,
        int totalMessages,
        long eventRevision,
        int priority,
        ItemStatus status,
        Instant availableAt,
        Instant createdAt,
        Instant updatedAt,
        int revision,
        /** Push revision that froze this item; 0 while it is still open to merging. */
        long frozenRevision,
        /** True once the item grew after it had already been pushed at least once. */
        boolean continued) {

    public InboxItem {
        sourceLabel = sourceLabel == null || sourceLabel.isBlank() ? source : sourceLabel;
        summary = summary == null ? "" : summary;
        entries = entries == null ? List.of() : List.copyOf(entries);
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        status = status == null ? ItemStatus.AVAILABLE : status;
    }

    /** Compatibility constructor for old call sites/tests; no structured entries yet. */
    public InboxItem(String id, String source, String summary, int priority,
                     ItemStatus status, Instant availableAt, Instant createdAt, int revision) {
        this(id, source, source, summary, List.of(), 0, 0L, priority, status,
                availableAt, createdAt, createdAt, revision, 0L, false);
    }

    /** Whether the item is closed to further merging (it was handed to a push). */
    public boolean frozen() {
        return frozenRevision > 0L;
    }

    public InboxItem withStatus(ItemStatus newStatus) {
        return new InboxItem(id, source, sourceLabel, summary, entries, totalMessages,
                eventRevision, priority, newStatus, availableAt, createdAt, updatedAt,
                revision + 1, frozenRevision, continued);
    }

    /**
     * Freezes the item at {@code pushRevision}. Freezing changes only delivery state, never content,
     * so {@code revision} stays put — callers keep comparing it against the pushed revision.
     */
    /** Marks the item as "grew after delivery" (shown to readers as 续). */
    public InboxItem withContinued(boolean value) {
        return new InboxItem(id, source, sourceLabel, summary, entries, totalMessages,
                eventRevision, priority, status, availableAt, createdAt, updatedAt,
                revision, frozenRevision, value);
    }

    public InboxItem withFrozen(long pushRevision) {
        return new InboxItem(id, source, sourceLabel, summary, entries, totalMessages,
                eventRevision, priority, status, availableAt, createdAt, updatedAt,
                revision, pushRevision, continued);
    }

    public InboxItem withPanelState(String nextSummary, List<InboxEntry> nextEntries,
                                    int nextTotalMessages, long nextEventRevision,
                                    int nextPriority, Instant nextUpdatedAt, int nextRevision) {
        return new InboxItem(id, source, sourceLabel, nextSummary, nextEntries,
                nextTotalMessages, nextEventRevision, nextPriority, status, availableAt,
                createdAt, nextUpdatedAt, nextRevision, frozenRevision, continued);
    }
}
