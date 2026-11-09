package io.mosire.bma3.inbox.core;

import java.time.Duration;

/** Delivery timing and retry policy for one inbox. */
public record DeliveryPolicy(
        int importantThreshold,
        Duration importantDelay,
        Duration normalDelay,
        Duration retryTtl,
        int maxAttempts) {

    public DeliveryPolicy {
        importantDelay = importantDelay == null ? Duration.ofSeconds(1) : importantDelay;
        normalDelay = normalDelay == null ? Duration.ofSeconds(30) : normalDelay;
        retryTtl = retryTtl == null ? Duration.ofMinutes(10) : retryTtl;
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须 >= 1");
        }
    }

    public Duration delayFor(boolean important) {
        return important ? importantDelay : normalDelay;
    }
}
