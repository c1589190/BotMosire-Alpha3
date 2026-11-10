package io.mosire.bma3.inbox.core;

import java.time.Instant;
import java.util.List;

/** One bounded aggregate panel: top M items, each with the last N messages. */
public record InboxPanel(
        String inboxId,
        long revision,
        long ackedRevision,
        Instant generatedAt,
        List<PanelItem> items,
        int totalItems,
        int omittedItems,
        boolean includeOmittedCount) {

    public InboxPanel {
        inboxId = inboxId == null ? "" : inboxId;
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        items = items == null ? List.of() : List.copyOf(items);
    }

    public int unread() {
        return totalItems;
    }
}
