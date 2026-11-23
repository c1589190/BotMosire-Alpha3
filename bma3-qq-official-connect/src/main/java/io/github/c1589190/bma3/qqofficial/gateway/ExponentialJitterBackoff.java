package io.github.c1589190.bma3.qqofficial.gateway;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Capped exponential backoff with equal jitter.
 *
 * <p>For attempt {@code n}, the base delay is {@code initial * 2^(n-1)} capped at
 * {@code maximum}. The returned delay is between 50% and 100% of that base. This avoids a
 * reconnect storm while still guaranteeing a non-zero delay.</p>
 */
public final class ExponentialJitterBackoff implements GatewayBackoff {

    private final Duration initial;
    private final Duration maximum;
    private final DoubleSupplier jitter;

    public ExponentialJitterBackoff(Duration initial, Duration maximum) {
        this(initial, maximum, () -> ThreadLocalRandom.current().nextDouble());
    }

    public ExponentialJitterBackoff(Duration initial, Duration maximum, DoubleSupplier jitter) {
        this.initial = Objects.requireNonNull(initial, "initial");
        this.maximum = Objects.requireNonNull(maximum, "maximum");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        if (initial.isNegative() || maximum.isNegative() || maximum.compareTo(initial) < 0) {
            throw new IllegalArgumentException("invalid backoff bounds");
        }
    }

    public static ExponentialJitterBackoff defaults() {
        return new ExponentialJitterBackoff(Duration.ofSeconds(1), Duration.ofSeconds(60));
    }

    @Override
    public Duration delayForAttempt(int attempt) {
        int safeAttempt = Math.max(1, attempt);
        long initialMillis = Math.max(1L, initial.toMillis());
        long maximumMillis = Math.max(initialMillis, maximum.toMillis());
        long baseMillis = initialMillis;
        for (int index = 1; index < safeAttempt && baseMillis < maximumMillis; index++) {
            baseMillis = Math.min(maximumMillis, baseMillis * 2L);
        }
        double fraction = jitter.getAsDouble();
        if (Double.isNaN(fraction) || fraction < 0.0) {
            fraction = 0.0;
        } else if (fraction > 1.0) {
            fraction = 1.0;
        }
        long jittered = Math.max(1L, (long) (baseMillis * (0.5d + 0.5d * fraction)));
        return Duration.ofMillis(Math.min(maximumMillis, jittered));
    }
}
