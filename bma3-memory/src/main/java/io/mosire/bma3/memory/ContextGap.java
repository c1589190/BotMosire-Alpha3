package io.mosire.bma3.memory;

import java.time.Instant;

/**
 * A hole in the pushed sequence: the plugin skipped {@code fromSeq..toSeq}. The jar never fills a
 * gap itself — it only records it so the watchdog and the Agent can see the copy is incomplete.
 */
public record ContextGap(long id, String sessionId, long fromSeq, long toSeq,
        Instant detectedAt) {
}
