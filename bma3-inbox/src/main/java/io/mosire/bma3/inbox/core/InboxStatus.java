package io.mosire.bma3.inbox.core;

import java.util.List;

/**
 * Counts shown to the Agent and the HTTP panel.
 *
 * <p>The counters are deliberately coarse and content-free: they answer "is the wake path
 * healthy?" without putting message bodies into status output.</p>
 *
 * <p>{@code frozenInWindow}, {@code frozenWindowBlocked} and {@code pendingBlockedByFrozen} are the
 * frozen-window starvation diagnostic: the push loop only hands out items inside the bounded panel
 * window, so when every slot is held by an already-pushed item that was never acknowledged, newer
 * pending items outside the window cannot be delivered until someone acks. This is observation
 * only — it does not change push ordering, freezing, or the strict single push per item.</p>
 */
public record InboxStatus(
        int available,
        int acked,
        int remainingImportant,
        int pendingPush,
        String nextPushAt,
        long panelRevision,
        long ackedRevision,
        int unread,
        long pushRevision,
        long ackConflicts,
        long pushes,
        long repushes,
        long dismissed,
        List<String> recentErrors,
        int frozenInWindow,
        boolean frozenWindowBlocked,
        int pendingBlockedByFrozen) {

    public InboxStatus {
        nextPushAt = nextPushAt == null ? "" : nextPushAt;
        recentErrors = recentErrors == null ? List.of() : List.copyOf(recentErrors);
        frozenInWindow = Math.max(0, frozenInWindow);
        pendingBlockedByFrozen = Math.max(0, pendingBlockedByFrozen);
        frozenWindowBlocked = pendingBlockedByFrozen > 0;
    }

    /** Compatibility constructor for call sites created before the counters existed. */
    public InboxStatus(int available, int acked, int remainingImportant, int pendingPush,
                       String nextPushAt, long panelRevision, long ackedRevision, int unread) {
        this(available, acked, remainingImportant, pendingPush, nextPushAt, panelRevision,
                ackedRevision, unread, 0L, 0L, 0L, 0L, 0L, List.of(), 0, false, 0);
    }

    /** Everything not yet acked. */
    public int remaining() {
        return available;
    }
}
