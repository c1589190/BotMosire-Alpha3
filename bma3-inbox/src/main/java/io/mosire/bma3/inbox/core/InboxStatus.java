package io.mosire.bma3.inbox.core;

/** Counts shown to the Agent and the HTTP panel. */
public record InboxStatus(
        int available,
        int acked,
        int remainingImportant,
        long panelRevision,
        long ackedRevision,
        int unread) {

    /** Everything not yet acked. */
    public int remaining() {
        return available;
    }
}
