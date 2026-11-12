package io.mosire.bma3.inbox.core;

/** Stable identity of this inbox process, used by the plugin to notice a BMA3 restart. */
public record InboxRuntimeInfo(
        String processId,
        String mcpStartId,
        long startedAtEpochMillis,
        long panelRevision,
        long pushRevision,
        long ackedRevision) {
}
