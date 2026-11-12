package io.mosire.bma3.watchdog.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Current watchdog view: per-component health, counters and the recent event ring. */
public record WatchdogSnapshot(
        Health overall,
        boolean ready,
        Instant generatedAt,
        Map<String, ProbeResult> components,
        List<WatchdogEvent> recentEvents,
        long probesRun,
        long remediationsRun,
        long remediationsSuppressed,
        long alertsSent) {

    public WatchdogSnapshot {
        overall = overall == null ? Health.UNKNOWN : overall;
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        components = components == null ? Map.of() : Map.copyOf(components);
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
    }
}
