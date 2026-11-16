package io.mosire.bma3.inbox.core;

import java.time.Instant;
import java.util.List;

/**
 * Result of one read-only Agent source wait. It never carries ACK/freeze mutations.
 *
 * @param settled true when the source stayed quiet for the requested window
 * @param reason quiet, max_wait, or interrupted
 * @param source the exact source or source prefix supplied by the caller
 * @param matchedBy source or sourcePrefix
 * @param waitedMs elapsed wait time
 * @param lastActivityAt latest matching item update, or the wait start when no item exists
 * @param items unacked matching items, newest first, bounded by the service
 */
public record WaitQuietResult(
        boolean settled,
        String reason,
        String source,
        String matchedBy,
        long waitedMs,
        Instant lastActivityAt,
        List<InboxItem> items) {

    public WaitQuietResult {
        reason = reason == null ? "" : reason;
        source = source == null ? "" : source;
        matchedBy = matchedBy == null ? "" : matchedBy;
        waitedMs = Math.max(0, waitedMs);
        lastActivityAt = lastActivityAt == null ? Instant.now() : lastActivityAt;
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean stillActive() {
        return !settled;
    }
}
