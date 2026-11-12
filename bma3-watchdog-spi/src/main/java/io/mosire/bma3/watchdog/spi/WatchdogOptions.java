package io.mosire.bma3.watchdog.spi;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Watchdog knobs. Deliberately plain values so the module never depends on the app's config types.
 */
public record WatchdogOptions(
        boolean enabled,
        Duration interval,
        Duration probeTimeout,
        int failureThreshold,
        Duration recoveryCooldown,
        Duration alertCooldown,
        Path stateDir,
        List<String> readyzCritical,
        int maxEvents,
        Duration pluginHeartbeatTimeout,
        long diskMinFreeBytes) {

    public WatchdogOptions {
        interval = interval == null ? Duration.ofSeconds(10) : interval;
        probeTimeout = probeTimeout == null ? Duration.ofSeconds(2) : probeTimeout;
        failureThreshold = Math.max(1, failureThreshold);
        recoveryCooldown = recoveryCooldown == null ? Duration.ofSeconds(30) : recoveryCooldown;
        alertCooldown = alertCooldown == null ? Duration.ofSeconds(600) : alertCooldown;
        stateDir = stateDir == null ? Path.of("data", "watchdog") : stateDir;
        readyzCritical = readyzCritical == null ? List.of() : List.copyOf(readyzCritical);
        maxEvents = Math.max(10, maxEvents);
        pluginHeartbeatTimeout = pluginHeartbeatTimeout == null
                ? Duration.ofSeconds(45) : pluginHeartbeatTimeout;
        diskMinFreeBytes = Math.max(0, diskMinFreeBytes);
    }

    public static WatchdogOptions defaults() {
        return new WatchdogOptions(true, Duration.ofSeconds(10), Duration.ofSeconds(2), 2,
                Duration.ofSeconds(30), Duration.ofSeconds(600), Path.of("data", "watchdog"),
                List.of("mcp", "inboxHttp", "qqAccount", "db"), 200, Duration.ofSeconds(45),
                200L * 1024 * 1024);
    }
}
