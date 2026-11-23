package io.github.c1589190.bma3.scheduler;

/**
 * Runtime knobs for the scheduler.
 *
 * @param enabled       false stops the tick loop and rejects new schedules
 * @param tickSeconds   how often due schedules are checked (1..60)
 * @param maxSchedules  upper bound on stored schedules (1..2000)
 * @param defaultMissedPolicy {@code skip} (default) or {@code once}
 */
public record ScheduleOptions(boolean enabled, int tickSeconds, int maxSchedules,
                              String defaultMissedPolicy) {

    public ScheduleOptions {
        if (tickSeconds < 1 || tickSeconds > 60) {
            throw new IllegalArgumentException("schedule.tickSeconds 必须在 1..60");
        }
        if (maxSchedules < 1 || maxSchedules > 2000) {
            throw new IllegalArgumentException("schedule.maxSchedules 必须在 1..2000");
        }
        defaultMissedPolicy = ScheduleService.normalizeMissedPolicy(defaultMissedPolicy);
    }

    public static ScheduleOptions defaults() {
        return new ScheduleOptions(true, 1, 200, ScheduleService.MISSED_SKIP);
    }
}
