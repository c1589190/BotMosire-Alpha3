package io.mosire.bma3.inbox.core;

import java.time.Instant;
import java.util.List;

/** Result of one bulk dismiss. Every field is safe to print: no message body, no URLs. */
public record DismissResult(
        int matched,
        int dismissed,
        int skippedImportant,
        int remaining,
        boolean truncated,
        String reason,
        List<DismissedItem> items) {

    public DismissResult {
        reason = reason == null ? "" : reason;
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** One matched row, for the dry-run preview and the operator log. */
    public record DismissedItem(
            String id,
            String source,
            String sourceLabel,
            int priority,
            int totalMessages,
            Instant lastAt,
            String status,
            boolean active) {

        public DismissedItem {
            source = source == null ? "" : source;
            sourceLabel = sourceLabel == null || sourceLabel.isBlank() ? source : sourceLabel;
            status = status == null ? "" : status;
        }
    }
}
