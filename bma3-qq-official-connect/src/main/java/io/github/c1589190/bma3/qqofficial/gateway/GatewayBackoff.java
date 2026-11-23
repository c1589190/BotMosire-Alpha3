package io.github.c1589190.bma3.qqofficial.gateway;

import java.time.Duration;

/** Injectable reconnect-delay policy. Attempts are 1-based and reset after READY/RESUMED. */
@FunctionalInterface
public interface GatewayBackoff {

    Duration delayForAttempt(int attempt);
}
