package io.mosire.bma3.inbox.core;

import java.time.Duration;

/** Push timing for one inbox: the immediate line and the two delays. */
public record DeliveryPolicy(
        int importantThreshold,
        Duration importantDelay,
        Duration normalDelay) {

    public DeliveryPolicy {
        importantDelay = importantDelay == null ? Duration.ofSeconds(1) : importantDelay;
        normalDelay = normalDelay == null ? Duration.ofSeconds(30) : normalDelay;
    }

    public Duration delayFor(boolean important) {
        return important ? importantDelay : normalDelay;
    }
}
