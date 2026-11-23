package io.github.c1589190.bma3.inbox.core;

/**
 * Stable identity of this inbox process plus the durable database identity, used by the plugin to
 * notice a BMA3 restart and to prove which database it is acknowledging.
 *
 * <p>{@code processId} and {@code mcpStartId} change on every start; {@code inboxIdentity} does
 * not, because it belongs to the database rather than to the process.</p>
 */
public record InboxRuntimeInfo(
        String processId,
        String mcpStartId,
        long startedAtEpochMillis,
        long panelRevision,
        long pushRevision,
        long ackedRevision,
        String inboxIdentity) {

    public InboxRuntimeInfo {
        inboxIdentity = inboxIdentity == null ? "" : inboxIdentity;
    }

    /** Compatibility constructor without the durable database identity. */
    public InboxRuntimeInfo(String processId, String mcpStartId, long startedAtEpochMillis,
                            long panelRevision, long pushRevision, long ackedRevision) {
        this(processId, mcpStartId, startedAtEpochMillis, panelRevision, pushRevision,
                ackedRevision, "");
    }
}
