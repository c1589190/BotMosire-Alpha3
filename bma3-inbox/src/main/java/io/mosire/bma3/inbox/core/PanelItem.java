package io.mosire.bma3.inbox.core;

import java.time.Instant;
import java.util.List;

/** One bounded source window in the aggregate panel. */
public record PanelItem(
        String id,
        String source,
        String sourceLabel,
        int priority,
        List<InboxEntry> entries,
        int totalMessages,
        int omittedMessages,
        ItemStatus status,
        Instant lastAt) {

    public PanelItem {
        source = source == null ? "" : source;
        sourceLabel = sourceLabel == null || sourceLabel.isBlank() ? source : sourceLabel;
        entries = entries == null ? List.of() : List.copyOf(entries);
        status = status == null ? ItemStatus.AVAILABLE : status;
        lastAt = lastAt == null ? Instant.now() : lastAt;
    }
}
