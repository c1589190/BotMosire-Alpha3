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
 *
 * <p>{@code truncatedChars} is the number of rendered characters dropped by
 * {@code inbox.display.maxCharsPerItem}; zero means the window was complete.</p>
 *
 * <p>{@code frozenRevision} is the push revision that froze this item, or {@code 0} when the item
 * was never handed to a push. It is the provable "already pushed" eligibility a guarded ACK needs:
 * an item that no push ever carried cannot have been shown to the caller, so it must not be
 * acknowledged through that path.</p>
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
        boolean continued,
        int truncatedChars,
        long frozenRevision,
        boolean waitingForQuiet) {

    public PanelItem {
        source = source == null ? "" : source;
        sourceLabel = sourceLabel == null || sourceLabel.isBlank() ? source : sourceLabel;
        entries = entries == null ? List.of() : List.copyOf(entries);
        status = status == null ? ItemStatus.AVAILABLE : status;
        lastAt = lastAt == null ? Instant.now() : lastAt;
        truncatedChars = Math.max(0, truncatedChars);
        frozenRevision = Math.max(0L, frozenRevision);
    }

    /** Compatibility constructor: no panel position, no truncation/push metadata. */
    public PanelItem(String id, String source, String sourceLabel, int priority,
                     List<InboxEntry> entries, int totalMessages, int omittedMessages,
                     ItemStatus status, Instant lastAt) {
        this(0, id, source, sourceLabel, priority, entries, totalMessages, omittedMessages,
                status, lastAt, false, 0, 0L, false);
    }

    /** Compatibility constructor: no truncation/push metadata. */
    public PanelItem(int index, String id, String source, String sourceLabel, int priority,
                     List<InboxEntry> entries, int totalMessages, int omittedMessages,
                     ItemStatus status, Instant lastAt, boolean continued) {
        this(index, id, source, sourceLabel, priority, entries, totalMessages, omittedMessages,
                status, lastAt, continued, 0, 0L, false);
    }

    public PanelItem withIndex(int newIndex) {
        return new PanelItem(newIndex, id, source, sourceLabel, priority, entries, totalMessages,
                omittedMessages, status, lastAt, continued, truncatedChars, frozenRevision,
                waitingForQuiet);
    }
}
