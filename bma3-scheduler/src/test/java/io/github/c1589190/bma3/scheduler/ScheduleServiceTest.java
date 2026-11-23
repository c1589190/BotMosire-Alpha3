package io.github.c1589190.bma3.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Scheduler behaviour: once/interval/cron, restart policy, delivery failure (acceptance C1–C6). */
class ScheduleServiceTest {

    private static final Instant BASE = Instant.parse("2026-11-15T00:00:00Z");

    @TempDir
    Path tempDir;

    private final MutableClock clock = new MutableClock(BASE);
    private final List<ScheduleFired> fired = new ArrayList<>();

    private ScheduleService service(boolean failingSink) {
        return new ScheduleService(new ScheduleStore(tempDir.resolve("schedule.db")), fire -> {
            if (failingSink) {
                throw new IllegalStateException("inbox down");
            }
            fired.add(fire);
        }, ScheduleOptions::defaults, clock, ZoneOffset.UTC);
    }

    @Test
    void onceFiresExactlyOnceAtItsInstant() {
        try (ScheduleService service = service(false)) {
            Schedule schedule = service.create(ScheduleService.KIND_ONCE,
                    "2026-11-15T00:01:00Z", "该吃药了", null, null);

            assertThat(service.tick(BASE.plusSeconds(59))).isZero();
            assertThat(service.tick(BASE.plusSeconds(60))).isEqualTo(1);
            assertThat(service.tick(BASE.plusSeconds(61))).isZero();
            assertThat(fired).hasSize(1);
            assertThat(fired.getFirst().text()).isEqualTo("该吃药了");
            assertThat(service.get(schedule.id()).orElseThrow().enabled()).isFalse();
        }
    }

    @Test
    void intervalKeepsFiringWithoutReplayingMissedSlots() {
        try (ScheduleService service = service(false)) {
            service.create(ScheduleService.KIND_INTERVAL, "60", "每分钟一次", 350, null);

            assertThat(service.tick(BASE.plusSeconds(60))).isEqualTo(1);
            assertThat(service.tick(BASE.plusSeconds(60))).isZero();
            // One very late tick collapses the missed slots into a single notification.
            assertThat(service.tick(BASE.plusSeconds(600))).isEqualTo(1);
            assertThat(fired).hasSize(2);
        }
    }

    @Test
    void cronFiresOnItsNextMatch() {
        try (ScheduleService service = service(false)) {
            Schedule schedule = service.create(ScheduleService.KIND_CRON, "*/5 * * * *",
                    "五分钟巡检", 350, null);
            Instant due = schedule.nextRunAt();

            assertThat(service.tick(due.minusSeconds(1))).isZero();
            assertThat(service.tick(due)).isEqualTo(1);
            assertThat(service.get(schedule.id()).orElseThrow().nextRunAt())
                    .isEqualTo(due.plusSeconds(300));
        }
    }

    @Test
    void restartSkipsMissedSlotsByDefaultButCanCatchUpOnce() {
        try (ScheduleService first = service(false)) {
            first.create(ScheduleService.KIND_INTERVAL, "60", "skip 用", 350, "skip");
            first.create(ScheduleService.KIND_INTERVAL, "60", "once 用", 350, "once");
        }
        clock.advance(Duration.ofSeconds(250));

        try (ScheduleService restarted = service(false)) {
            assertThat(restarted.tick(BASE.plusSeconds(250))).isEqualTo(1);
            assertThat(fired).hasSize(1);
            assertThat(fired.getFirst().text()).isEqualTo("once 用");
            assertThat(restarted.tick(BASE.plusSeconds(300))).isEqualTo(1);
            assertThat(fired.getLast().text()).isEqualTo("skip 用");
        }
    }

    @Test
    void deliveryFailureIsRecordedAndNeverRetried() {
        try (ScheduleService service = service(true)) {
            Schedule schedule = service.create(ScheduleService.KIND_INTERVAL, "60", "会失败", 350,
                    null);

            assertThat(service.tick(BASE.plusSeconds(60))).isZero();
            List<java.util.Map<String, Object>> runs = service.runs(10);
            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst().get("status")).isEqualTo("FAILED");
            assertThat(runs.getFirst().get("scheduleId")).isEqualTo(schedule.id());
            assertThat(service.get(schedule.id()).orElseThrow().nextRunAt())
                    .isEqualTo(BASE.plusSeconds(120));
        }
    }

    @Test
    void cancelAndDisableStopFurtherFiring() {
        try (ScheduleService service = service(false)) {
            Schedule cancelled = service.create(ScheduleService.KIND_INTERVAL, "60", "将被删", 350,
                    null);
            Schedule paused = service.create(ScheduleService.KIND_INTERVAL, "60", "将被暂停", 350,
                    null);

            service.setEnabled(paused.id(), false);
            assertThat(service.cancel(cancelled.id())).isTrue();

            assertThat(service.tick(BASE.plusSeconds(60))).isZero();
            assertThat(fired).isEmpty();
            assertThatThrownBy(() -> service.setEnabled("sch-missing", true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void invalidSpecsAreRejectedBeforeAnythingIsStored() {
        try (ScheduleService service = service(false)) {
            assertThatThrownBy(() -> service.create(ScheduleService.KIND_CRON, "* * * *",
                    "坏 cron", null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.create(ScheduleService.KIND_INTERVAL, "0",
                    "坏间隔", null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.create(ScheduleService.KIND_ONCE,
                    "2026-11-14T00:00:00Z", "过去时刻", null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("未来");
            assertThatThrownBy(() -> service.create(ScheduleService.KIND_ONCE,
                    "2026-11-16T00:00:00Z", "", null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("text");
            assertThat(service.list()).isEmpty();
        }
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
