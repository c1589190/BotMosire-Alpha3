package io.mosire.bma3.inbox.core;

import java.util.List;

/**
 * Counts shown to the Agent and the HTTP panel.
 *
 * <p>The counters are deliberately coarse and content-free: they answer "is the wake path
 * healthy?" without putting message bodies into status output.</p>
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
        List<String> recentErrors) {

    public InboxStatus {
        nextPushAt = nextPushAt == null ? "" : nextPushAt;
        recentErrors = recentErrors == null ? List.of() : List.copyOf(recentErrors);
    }

    /** Compatibility constructor for call sites created before the counters existed. */
    public InboxStatus(int available, int acked, int remainingImportant, int pendingPush,
                       String nextPushAt, long panelRevision, long ackedRevision, int unread) {
        this(available, acked, remainingImportant, pendingPush, nextPushAt, panelRevision,
                ackedRevision, unread, 0L, 0L, 0L, 0L, 0L, List.of());
    }

    /** Everything not yet acked. */
    public int remaining() {
        return available;
    }
}
