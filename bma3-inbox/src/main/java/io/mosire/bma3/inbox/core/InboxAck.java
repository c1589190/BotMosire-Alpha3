package io.mosire.bma3.inbox.core;

/** Result of advancing the panel read cursor. */
public record InboxAck(long ackedRevision, int remaining, long panelRevision) {
}
