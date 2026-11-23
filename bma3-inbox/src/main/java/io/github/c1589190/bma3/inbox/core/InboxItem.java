package io.github.c1589190.bma3.inbox.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Persisted inbox item: a bounded source window plus a server-managed revision.
 *
 * <p>{@code displayChannel} is fixed for the lifetime of one open item. Once set, body entries
 * from other channels are not merged into it; those observations must go through the evidence-only
 * path. Legacy rows have {@code displayChannel=""}. {@code channelSummary} is an opaque structured
 * summary (per-channel observation counts, matched/channel-only/conflict counts, evidence ids, …);
 * Inbox never interprets a platform name.</p>
 */
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
        boolean continued,
        /** Single channel whose body is projected into this item; empty for legacy items. */
        String displayChannel,
        /** Opaque, structured channel/evidence summary; empty for legacy items. */
        Map<String, Object> channelSummary) {

    public InboxItem {
        sourceLabel = sourceLabel == null || sourceLabel.isBlank() ? source : sourceLabel;
        summary = summary == null ? "" : summary;
        entries = entries == null ? List.of() : List.copyOf(entries);
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        status = status == null ? ItemStatus.AVAILABLE : status;
        displayChannel = displayChannel == null ? "" : displayChannel.trim();
        channelSummary = InboxEntry.immutableMap(channelSummary);
    }

    /** Compatibility constructor for old call sites/tests; no structured entries yet. */
    public InboxItem(String id, String source, String summary, int priority,
                     ItemStatus status, Instant availableAt, Instant createdAt, int revision) {
        this(id, source, source, summary, List.of(), 0, 0L, priority, status,
                availableAt, createdAt, createdAt, revision, 0L, false, "", Map.of());
    }

    /** Compatibility constructor for callers created before channel projection (D-043 era). */
    public InboxItem(String id, String source, String sourceLabel, String summary,
                     List<InboxEntry> entries, int totalMessages, long eventRevision, int priority,
                     ItemStatus status, Instant availableAt, Instant createdAt, Instant updatedAt,
                     int revision, long frozenRevision, boolean continued) {
        this(id, source, sourceLabel, summary, entries, totalMessages, eventRevision, priority,
                status, availableAt, createdAt, updatedAt, revision, frozenRevision, continued,
                "", Map.of());
    }

    /** Whether the item is closed to further merging (it was handed to a push). */
    public boolean frozen() {
        return frozenRevision > 0L;
    }

    public InboxItem withStatus(ItemStatus newStatus) {
        return new InboxItem(id, source, sourceLabel, summary, entries, totalMessages,
                eventRevision, priority, newStatus, availableAt, createdAt, updatedAt,
                revision + 1, frozenRevision, continued, displayChannel, channelSummary);
    }

    /** Marks the item as "grew after delivery" (shown to readers as 续). */
    public InboxItem withContinued(boolean value) {
        return new InboxItem(id, source, sourceLabel, summary, entries, totalMessages,
                eventRevision, priority, status, availableAt, createdAt, updatedAt,
                revision, frozenRevision, value, displayChannel, channelSummary);
    }

    public InboxItem withFrozen(long pushRevision) {
        return new InboxItem(id, source, sourceLabel, summary, entries, totalMessages,
                eventRevision, priority, status, availableAt, createdAt, updatedAt,
                revision, pushRevision, continued, displayChannel, channelSummary);
    }

    /** Sets the item's lifetime channel projection and summary without touching body content. */
    public InboxItem withChannelProjection(String nextDisplayChannel,
                                           Map<String, Object> nextChannelSummary) {
        return new InboxItem(id, source, sourceLabel, summary, entries, totalMessages,
                eventRevision, priority, status, availableAt, createdAt, updatedAt,
                revision, frozenRevision, continued, nextDisplayChannel, nextChannelSummary);
    }

    public InboxItem withPanelState(String nextSummary, List<InboxEntry> nextEntries,
                                    int nextTotalMessages, long nextEventRevision,
                                    int nextPriority, Instant nextUpdatedAt, int nextRevision) {
        return withPanelState(nextSummary, nextEntries, nextTotalMessages, nextEventRevision,
                nextPriority, nextUpdatedAt, nextRevision, channelSummary);
    }

    /** Like {@link #withPanelState} but lets a projected append merge its channel summary. */
    public InboxItem withPanelState(String nextSummary, List<InboxEntry> nextEntries,
                                    int nextTotalMessages, long nextEventRevision,
                                    int nextPriority, Instant nextUpdatedAt, int nextRevision,
                                    Map<String, Object> nextChannelSummary) {
        return new InboxItem(id, source, sourceLabel, nextSummary, nextEntries,
                nextTotalMessages, nextEventRevision, nextPriority, status, availableAt,
                createdAt, nextUpdatedAt, nextRevision, frozenRevision, continued,
                displayChannel, nextChannelSummary);
    }

    /** Evidence-only mutation: never changes entries/summary/push state or wake metadata. */
    public InboxItem withEvidenceState(int nextPriority, Map<String, Object> nextChannelSummary) {
        return new InboxItem(id, source, sourceLabel, summary, entries, totalMessages,
                eventRevision, nextPriority, status, availableAt, createdAt, updatedAt,
                revision, frozenRevision, continued, displayChannel, nextChannelSummary);
    }
}
