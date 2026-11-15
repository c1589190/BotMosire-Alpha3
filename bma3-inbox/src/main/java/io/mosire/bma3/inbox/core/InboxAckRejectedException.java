package io.mosire.bma3.inbox.core;

import java.util.List;

/**
 * A rejected item ACK that changed nothing.
 *
 * <p>The guarded ACK path never half-confirms a batch: if any requested item cannot be proven
 * (wrong database identity, unknown/cleaned id, never-pushed item) the whole request is rejected
 * and every row keeps its previous state. The reason and the offending ids are carried as data so
 * the HTTP layer can return a distinct, diagnosable 409 instead of a generic error.</p>
 *
 * <p>It extends {@link IllegalArgumentException} so legacy callers that catch that keep working.</p>
 */
public final class InboxAckRejectedException extends IllegalArgumentException {

    /** Why a guarded ACK was rejected. */
    public enum Reason {
        /** The caller presented a different database's identity. */
        INBOX_IDENTITY_MISMATCH,
        /** The caller named item ids without presenting any identity. */
        INBOX_IDENTITY_REQUIRED,
        /** The guarded request did not carry the original panel revision at all. */
        REVISION_REQUIRED,
        /** The guarded request carried a revision that is not a non-negative integer. */
        REVISION_INVALID,
        /** At least one requested id does not exist in this database (unknown or cleaned up). */
        ITEM_MISSING,
        /** At least one requested id exists but was never handed to a push. */
        ITEM_NOT_PUSHED
    }

    private final Reason reason;
    private final List<String> itemIds;

    public InboxAckRejectedException(Reason reason, List<String> itemIds) {
        super(reason.name() + (itemIds == null || itemIds.isEmpty()
                ? "" : ": " + String.join(",", itemIds)));
        this.reason = reason;
        this.itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
    }

    public Reason reason() {
        return reason;
    }

    /** The offending ids (empty for identity rejections). */
    public List<String> itemIds() {
        return itemIds;
    }
}
