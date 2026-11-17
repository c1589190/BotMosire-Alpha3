package io.mosire.bma3.qqofficial.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExponentialJitterBackoffTest {

    @Test
    void delaysGrowExponentiallyAndCapAtMaximum() {
        var backoff = new ExponentialJitterBackoff(
                Duration.ofMillis(100), Duration.ofMillis(1_000), () -> 0.0);

        assertThat(backoff.delayForAttempt(1)).isEqualTo(Duration.ofMillis(50));
        assertThat(backoff.delayForAttempt(2)).isEqualTo(Duration.ofMillis(100));
        assertThat(backoff.delayForAttempt(3)).isEqualTo(Duration.ofMillis(200));
        assertThat(backoff.delayForAttempt(10)).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void fullJitterStaysWithinHalfToFullBaseAndNeverZero() {
        var noJitter = new ExponentialJitterBackoff(
                Duration.ofMillis(100), Duration.ofSeconds(1), () -> 0.0);
        var fullJitter = new ExponentialJitterBackoff(
                Duration.ofMillis(100), Duration.ofSeconds(1), () -> 0.999999);

        assertThat(noJitter.delayForAttempt(5)).isEqualTo(Duration.ofMillis(500));
        assertThat(fullJitter.delayForAttempt(5)).isBetween(
                Duration.ofMillis(500), Duration.ofMillis(1_000));
        assertThat(noJitter.delayForAttempt(0)).isEqualTo(Duration.ofMillis(50));
    }
}
