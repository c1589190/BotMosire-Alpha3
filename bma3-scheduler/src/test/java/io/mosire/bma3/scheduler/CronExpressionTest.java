package io.mosire.bma3.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Cron subset parsing and next-match arithmetic (acceptance C3). */
class CronExpressionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void everyFiveMinutesLandsOnTheNextBoundary() {
        CronExpression cron = CronExpression.parse("*/5 * * * *");

        Instant next = cron.next(Instant.parse("2026-11-15T10:02:30Z"), UTC);

        assertThat(next).isEqualTo(Instant.parse("2026-11-15T10:05:00Z"));
    }

    @Test
    void dailyAtEightRollsToTheNextDay() {
        CronExpression cron = CronExpression.parse("0 8 * * *");

        Instant next = cron.next(Instant.parse("2026-11-15T09:00:00Z"), UTC);

        assertThat(next).isEqualTo(Instant.parse("2026-11-16T08:00:00Z"));
    }

    @Test
    void listsRangesAndStepsAreAccepted() {
        assertThat(CronExpression.parse("1,3,5-9/2 * * * *")
                .next(Instant.parse("2026-11-15T10:00:00Z"), UTC))
                .isEqualTo(Instant.parse("2026-11-15T10:01:00Z"));
        assertThat(CronExpression.parse("0 0 1 * *")
                .next(Instant.parse("2026-11-15T10:00:00Z"), UTC))
                .isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(CronExpression.parse("0 0 * * 1")
                .next(Instant.parse("2026-11-15T10:00:00Z"), UTC))
                .isEqualTo(Instant.parse("2026-11-16T00:00:00Z"));
    }

    @Test
    void malformedExpressionsAreRejectedUpFront() {
        assertThatThrownBy(() -> CronExpression.parse("* * * *"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("5 个字段");
        assertThatThrownBy(() -> CronExpression.parse("60 * * * *"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0..59");
        assertThatThrownBy(() -> CronExpression.parse("5-1 * * * *"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("范围反向");
        assertThatThrownBy(() -> CronExpression.parse("*/0 * * * *"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("步长");
    }
}
