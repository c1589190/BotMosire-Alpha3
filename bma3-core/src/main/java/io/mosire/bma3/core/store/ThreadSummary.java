package io.mosire.bma3.core.store;

import java.time.Instant;

/** One source thread in the local message board. */
public record ThreadSummary(
        String source,
        String accountId,
        long lastSeq,
        Instant lastAt,
        long messageCount) {
}
