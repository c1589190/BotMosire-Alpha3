package io.mosire.bma3.watchdog.spi;

import java.time.Duration;
import java.time.Instant;

/** A bounded recovery action for one component. */
public interface Remediation {

    String name();

    default String component() {
        return "";
    }

    default Duration cooldown() {
        return Duration.ofSeconds(30);
    }

    /** Whether this remediation is relevant for the observed result. */
    default boolean applies(ProbeResult result) {
        return result != null && !result.health().healthy();
    }

    RemediationResult remediate(ProbeResult result);

    record RemediationResult(
            String action,
            boolean changed,
            String outcome,
            Instant at) {

        public RemediationResult {
            action = action == null ? "" : action;
            outcome = outcome == null ? "" : outcome;
            at = at == null ? Instant.now() : at;
        }

        public static RemediationResult unchanged(String action, String outcome) {
            return new RemediationResult(action, false, outcome, Instant.now());
        }

        public static RemediationResult changed(String action, String outcome) {
            return new RemediationResult(action, true, outcome, Instant.now());
        }
    }
}
