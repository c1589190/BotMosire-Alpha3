package io.mosire.bma3.inbox.core;

import java.time.Instant;
import java.util.List;

/**
 * One bounded source window in the aggregate panel.
 *
 * <p>{@code index} is the 1-based position in the panel that was last generated (priority desc,
 * then last activity). It exists so a caller can say "ack 2..4" without having to echo ids, and it
 * is only meaningful together with the panel {@code revision} it came from — the index drifts as
 * soon as anything new arrives.</p>
 */
public record PanelItem(
        int index,
        String id,
        String source,
        String sourceLabel,
        int priority,
        List<InboxEntry> entries,
        int totalMessages,
        int omittedMessages,
        ItemStatus status,
        Instant lastAt,
        boolean continued) {

    public PanelItem {
        source = source == null ? "" : source;
        sourceLabel = sourceLabel == null || sourceLabel.isBlank() ? source : sourceLabel;
        entries = entries == null ? List.of() : List.copyOf(entries);
        status = status == null ? ItemStatus.AVAILABLE : status;
        lastAt = lastAt == null ? Instant.now() : lastAt;
    }

    /** Compatibility constructor: no panel position. */
    public PanelItem(String id, String source, String sourceLabel, int priority,
                     List<InboxEntry> entries, int totalMessages, int omittedMessages,
                     ItemStatus status, Instant lastAt) {
        this(0, id, source, sourceLabel, priority, entries, totalMessages, omittedMessages,
                status, lastAt, false);
    }

    public PanelItem withIndex(int newIndex) {
        return new PanelItem(newIndex, id, source, sourceLabel, priority, entries, totalMessages,
                omittedMessages, status, lastAt, continued);
    }
}
