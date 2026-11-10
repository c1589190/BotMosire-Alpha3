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
        int revision) {

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
                availableAt, createdAt, createdAt, revision);
    }

    public InboxItem withStatus(ItemStatus newStatus) {
        return new InboxItem(id, source, sourceLabel, summary, entries, totalMessages,
                eventRevision, priority, newStatus, availableAt, createdAt, updatedAt,
                revision + 1);
    }

    public InboxItem withPanelState(String nextSummary, List<InboxEntry> nextEntries,
                                    int nextTotalMessages, long nextEventRevision,
                                    int nextPriority, Instant nextUpdatedAt, int nextRevision) {
        return new InboxItem(id, source, sourceLabel, nextSummary, nextEntries,
                nextTotalMessages, nextEventRevision, nextPriority, status, availableAt,
                createdAt, nextUpdatedAt, nextRevision);
    }
}
