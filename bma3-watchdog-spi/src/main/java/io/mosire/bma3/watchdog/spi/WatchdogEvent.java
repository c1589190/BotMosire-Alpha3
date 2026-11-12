package io.mosire.bma3.watchdog.spi;

import java.time.Instant;

/** One bounded watchdog event (status transition or remediation). No content, no secrets. */
public record WatchdogEvent(
        Instant at,
        String component,
        Health health,
        String code,
        String detail,
        String remediation,
        boolean changed) {

    public WatchdogEvent {
        at = at == null ? Instant.now() : at;
        component = component == null ? "" : component;
        health = health == null ? Health.UNKNOWN : health;
        code = code == null ? "" : code;
        detail = detail == null ? "" : detail;
        remediation = remediation == null ? "" : remediation;
    }
}
