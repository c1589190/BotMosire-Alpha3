package io.mosire.bma3.inbox.core;

import java.util.List;

/**
 * Result of one ACK call, including per-item proof.
 *
 * <p>The three id arrays are the authoritative answer. A caller may treat an ACK as
 * <b>complete</b> only when {@link #complete()} is true, which by construction means: the disjoint
 * union of {@link #ackedItemIds()} and {@link #alreadyAckedItemIds()} is exactly the requested item
 * set (no duplicates, no extra ids) and {@link #missingItemIds()} is empty. A 2xx response alone is
 * never proof that every item was confirmed.</p>
 *
 * <p>{@code ackedRevision} stays an observation of the read cursor; the item arrays are the fact.
 * {@code inboxIdentity} echoes the database identity that answered, so the caller can notice a
 * substitution.</p>
 */
public record InboxAck(
        long ackedRevision,
        int remaining,
        long panelRevision,
        List<String> ackedItemIds,
        List<String> alreadyAckedItemIds,
        List<String> missingItemIds,
        String inboxIdentity,
        boolean complete) {

    public InboxAck {
        ackedItemIds = ackedItemIds == null ? List.of() : List.copyOf(ackedItemIds);
        alreadyAckedItemIds =
                alreadyAckedItemIds == null ? List.of() : List.copyOf(alreadyAckedItemIds);
        missingItemIds = missingItemIds == null ? List.of() : List.copyOf(missingItemIds);
        inboxIdentity = inboxIdentity == null ? "" : inboxIdentity;
    }

    /** Compatibility result for legacy callers that only read the cursor (no per-item proof). */
    public InboxAck(long ackedRevision, int remaining, long panelRevision) {
        this(ackedRevision, remaining, panelRevision, List.of(), List.of(), List.of(), "", true);
    }
}
