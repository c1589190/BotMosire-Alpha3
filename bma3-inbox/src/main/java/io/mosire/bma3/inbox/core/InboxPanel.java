package io.mosire.bma3.inbox.core;

import java.time.Instant;
import java.util.List;

/**
 * One bounded aggregate panel: top M items, each with the last N messages.
 *
 * <p>{@code inboxIdentity} is the durable identity of the database this panel came from. A plugin
 * must echo it back on every item ACK, so an ACK can never be applied to a different database that
 * happens to reuse an item id.</p>
 */
public record InboxPanel(
        String inboxId,
        long revision,
        long pushRevision,
        long ackedRevision,
        Instant generatedAt,
        List<PanelItem> items,
        int totalItems,
        int omittedItems,
        boolean includeOmittedCount,
        String inboxIdentity) {

    public InboxPanel {
        inboxId = inboxId == null ? "" : inboxId;
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        items = items == null ? List.of() : List.copyOf(items);
        inboxIdentity = inboxIdentity == null ? "" : inboxIdentity;
    }

    /** Compatibility constructor for call sites created before push scheduling. */
    public InboxPanel(String inboxId, long revision, long ackedRevision, Instant generatedAt,
                      List<PanelItem> items, int totalItems, int omittedItems,
                      boolean includeOmittedCount) {
        this(inboxId, revision, revision, ackedRevision, generatedAt, items, totalItems,
                omittedItems, includeOmittedCount, "");
    }

    public int unread() {
        return totalItems;
    }
}
