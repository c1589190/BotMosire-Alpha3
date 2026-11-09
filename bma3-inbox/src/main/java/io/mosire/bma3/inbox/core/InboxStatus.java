package io.mosire.bma3.inbox.core;

/** Counts shown to the Agent and the HTTP panel. */
public record InboxStatus(
        int available,
        int delivered,
        int acked,
        int undelivered,
        int remaining,
        int remainingImportant) {
}
