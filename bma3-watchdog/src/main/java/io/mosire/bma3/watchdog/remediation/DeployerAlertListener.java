package io.mosire.bma3.watchdog.remediation;

import io.mosire.bma3.watchdog.spi.Health;
import io.mosire.bma3.watchdog.spi.WatchdogEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Sends one private message to the deployer when a component transitions to DOWN.
 *
 * <p>Rate limited per component, and deliberately content-free: only the component id, code and
 * time are sent. The notifier is supplied by the application (QQ send path); the watchdog module
 * does not know how QQ works.</p>
 */
public final class DeployerAlertListener implements Consumer<WatchdogEvent> {

    private final Consumer<String> notifier;
    private final Duration cooldown;
    private final boolean enabled;
    private final Map<String, Instant> lastAlert = new ConcurrentHashMap<>();

    public DeployerAlertListener(Consumer<String> notifier, Duration cooldown, boolean enabled) {
        this.notifier = notifier;
        this.cooldown = cooldown == null ? Duration.ofMinutes(10) : cooldown;
        this.enabled = enabled;
    }

    @Override
    public void accept(WatchdogEvent event) {
        if (!enabled || notifier == null || event == null
                || event.health() != Health.DOWN || !event.changed()) {
            return;
        }
        Instant previous = lastAlert.get(event.component());
        if (previous != null && Instant.now().isBefore(previous.plus(cooldown))) {
            return;
        }
        lastAlert.put(event.component(), Instant.now());
        String text = "[BMA3 watchdog] " + event.component() + " DOWN: " + event.code()
                + (event.detail().isBlank() ? "" : " (" + event.detail() + ")")
                + " at " + event.at();
        try {
            notifier.accept(text);
        } catch (RuntimeException ignored) {
            // Alerting must never break the watchdog loop.
        }
    }
}
