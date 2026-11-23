package io.github.c1589190.bma3.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable schedule engine.
 *
 * <p>Contract (spec {@code 2026-11-15-debug-channel-schedule-triggers-design.md} §4): persist the
 * run row first, then notify; one notification per {@code (schedule, due)}; the tick thread never
 * executes user work. The engine is time-driven only through {@link #tick(Instant)} so tests do not
 * need to sleep.</p>
 */
public final class ScheduleService implements AutoCloseable {

    public static final String KIND_ONCE = "once";
    public static final String KIND_INTERVAL = "interval";
    public static final String KIND_CRON = "cron";
    public static final String MISSED_SKIP = "skip";
    public static final String MISSED_ONCE = "once";

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleService.class);
    private static final int MAX_TEXT_CHARS = 4000;
    private static final long MAX_INTERVAL_SECONDS = 30L * 24 * 60 * 60;

    private final ScheduleStore store;
    private final ScheduleSink sink;
    private final Supplier<ScheduleOptions> options;
    private final Clock clock;
    private final ZoneId zone;
    private final LinkedHashMap<String, Schedule> schedules = new LinkedHashMap<>();
    private ScheduledExecutorService executor;

    public ScheduleService(ScheduleStore store, ScheduleSink sink,
                           Supplier<ScheduleOptions> options) {
        this(store, sink, options, Clock.systemUTC(), ZoneId.systemDefault());
    }

    public ScheduleService(ScheduleStore store, ScheduleSink sink, Supplier<ScheduleOptions> options,
                           Clock clock, ZoneId zone) {
        this.store = store;
        this.sink = sink;
        this.options = options == null ? ScheduleOptions::defaults : options;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
        for (Schedule schedule : store.all()) {
            schedules.put(schedule.id(), schedule);
        }
        int recovered = recover(this.clock.instant());
        if (recovered > 0) {
            LOG.info("event=schedule_recovered count={}", recovered);
        }
    }

    /** Create and persist one schedule; invalid kind/spec is rejected before anything is stored. */
    public synchronized Schedule create(String kind, String spec, String text, Integer priority,
                                        String missedPolicy) {
        ScheduleOptions limits = options.get();
        if (!limits.enabled()) {
            throw new IllegalArgumentException("schedule.enabled=false，拒绝创建新任务");
        }
        if (schedules.size() >= limits.maxSchedules()) {
            throw new IllegalArgumentException("已达 schedule.maxSchedules="
                    + limits.maxSchedules());
        }
        String normalizedKind = normalizeKind(kind);
        String normalizedSpec = spec == null ? "" : spec.trim();
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("text 不能为空");
        }
        if (body.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("text 不能超过 " + MAX_TEXT_CHARS + " 字符");
        }
        int effectivePriority = priority == null ? 350 : priority;
        if (effectivePriority < -1000 || effectivePriority > 1000) {
            throw new IllegalArgumentException("priority 必须在 -1000..1000");
        }
        String policy = normalizeMissedPolicy(
                missedPolicy == null || missedPolicy.isBlank()
                        ? limits.defaultMissedPolicy() : missedPolicy);
        Instant now = clock.instant();
        Instant next = firstRun(normalizedKind, normalizedSpec, now);
        String id = "sch-" + UUID.randomUUID().toString().substring(0, 8);
        Schedule schedule = new Schedule(id, normalizedKind, normalizedSpec, body,
                effectivePriority, true, next, null, policy, now, now);
        store.save(schedule);
        schedules.put(id, schedule);
        return schedule;
    }

    public synchronized List<Schedule> list() {
        return List.copyOf(schedules.values());
    }

    public synchronized Optional<Schedule> get(String id) {
        return Optional.ofNullable(schedules.get(id));
    }

    public synchronized boolean cancel(String id) {
        if (schedules.remove(id) == null) {
            return false;
        }
        store.delete(id);
        return true;
    }

    public synchronized Schedule setEnabled(String id, boolean enabled) {
        Schedule current = schedules.get(id);
        if (current == null) {
            throw new IllegalArgumentException("schedule 不存在: " + id);
        }
        Instant now = clock.instant();
        Instant next = current.nextRunAt();
        if (enabled && (next == null || !next.isAfter(now))) {
            next = firstRun(current.kind(), current.spec(), now);
        }
        Schedule updated = new Schedule(current.id(), current.kind(), current.spec(),
                current.text(), current.priority(), enabled, enabled ? next : current.nextRunAt(),
                current.lastRunAt(), current.missedPolicy(), current.createdAt(), now);
        store.save(updated);
        schedules.put(id, updated);
        return updated;
    }

    /** Health check for the watchdog probe. */
    public boolean ping() {
        return store.ping();
    }

    public synchronized List<Map<String, Object>> runs(int limit) {
        return store.runs(limit);
    }

    /**
     * Fire every due schedule.
     *
     * @return how many notifications were delivered
     */
    public synchronized int tick(Instant now) {
        List<Schedule> due = new ArrayList<>();
        for (Schedule schedule : schedules.values()) {
            if (schedule.enabled() && schedule.nextRunAt() != null
                    && !schedule.nextRunAt().isAfter(now)) {
                due.add(schedule);
            }
        }
        due.sort(Comparator.comparing(Schedule::nextRunAt));
        int delivered = 0;
        for (Schedule schedule : due) {
            if (fire(schedule, now)) {
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * Startup policy for schedules whose slot passed while the process was down.
     *
     * @return how many schedules were adjusted
     */
    public synchronized int recover(Instant now) {
        int adjusted = 0;
        for (Schedule schedule : new ArrayList<>(schedules.values())) {
            if (!schedule.enabled() || schedule.nextRunAt() == null
                    || schedule.nextRunAt().isAfter(now)) {
                continue;
            }
            Instant next;
            if (MISSED_ONCE.equals(schedule.missedPolicy())) {
                next = now;
            } else {
                next = KIND_ONCE.equals(schedule.kind())
                        ? null : nextAfter(schedule, schedule.nextRunAt(), now);
            }
            boolean enabled = next != null;
            Schedule updated = new Schedule(schedule.id(), schedule.kind(), schedule.spec(),
                    schedule.text(), schedule.priority(), enabled, next, schedule.lastRunAt(),
                    schedule.missedPolicy(), schedule.createdAt(), now);
            store.save(updated);
            schedules.put(schedule.id(), updated);
            adjusted++;
        }
        return adjusted;
    }

    /** Start the tick loop; a no-op when already started or disabled. */
    public synchronized void start() {
        if (executor != null || !options.get().enabled()) {
            return;
        }
        int seconds = Math.max(1, options.get().tickSeconds());
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "bma3-schedule");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(() -> {
            try {
                tick(clock.instant());
            } catch (RuntimeException failure) {
                LOG.warn("event=schedule_tick_failed error={}",
                        failure.getClass().getSimpleName());
            }
        }, seconds, seconds, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        store.close();
    }

    private boolean fire(Schedule schedule, Instant now) {
        Instant due = schedule.nextRunAt();
        Instant next = KIND_ONCE.equals(schedule.kind()) ? null : nextAfter(schedule, due, now);
        Schedule updated = new Schedule(schedule.id(), schedule.kind(), schedule.spec(),
                schedule.text(), schedule.priority(), next != null, next, now,
                schedule.missedPolicy(), schedule.createdAt(), now);
        String runId = runId(schedule.id(), due);
        boolean fresh = store.recordRun(runId, schedule.id(), due, now);
        // Advance before notifying: a crash between the two must not replay the same slot.
        store.save(updated);
        schedules.put(schedule.id(), updated);
        if (!fresh) {
            return false;
        }
        try {
            sink.fire(new ScheduleFired(schedule.id(), due, now, schedule.text(),
                    schedule.priority()));
            store.updateRun(runId, "DELIVERED", "");
            return true;
        } catch (RuntimeException failure) {
            LOG.warn("event=schedule_delivery_failed scheduleId={} error={}", schedule.id(),
                    failure.getClass().getSimpleName());
            store.updateRun(runId, "FAILED", failure.getClass().getSimpleName());
            return false;
        }
    }

    private Instant firstRun(String kind, String spec, Instant now) {
        return switch (kind) {
            case KIND_ONCE -> {
                Instant at = parseInstant(spec);
                if (!at.isAfter(now)) {
                    throw new IllegalArgumentException("once 时刻必须在未来: " + spec);
                }
                yield at;
            }
            case KIND_INTERVAL -> now.plusSeconds(parseInterval(spec));
            case KIND_CRON -> CronExpression.parse(spec).next(now, zone);
            default -> throw new IllegalArgumentException("未知 kind: " + kind);
        };
    }

    private Instant nextAfter(Schedule schedule, Instant due, Instant now) {
        if (KIND_INTERVAL.equals(schedule.kind())) {
            long seconds = parseInterval(schedule.spec());
            Instant candidate = due.plusSeconds(seconds);
            while (!candidate.isAfter(now)) {
                candidate = candidate.plusSeconds(seconds);
            }
            return candidate;
        }
        CronExpression cron = CronExpression.parse(schedule.spec());
        Instant candidate = cron.next(due, zone);
        while (!candidate.isAfter(now)) {
            candidate = cron.next(candidate, zone);
        }
        return candidate;
    }

    private static long parseInterval(String spec) {
        long seconds;
        try {
            seconds = Long.parseLong(spec.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("interval 必须是整秒数: " + spec);
        }
        if (seconds < 1 || seconds > MAX_INTERVAL_SECONDS) {
            throw new IllegalArgumentException(
                    "interval 必须在 1.." + MAX_INTERVAL_SECONDS + " 秒");
        }
        return seconds;
    }

    private Instant parseInstant(String spec) {
        String value = spec.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Try an offset date-time, then a local date-time in the configured zone.
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // Fall through to local time.
        }
        try {
            return LocalDateTime.parse(value).atZone(zone).toInstant();
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("once 时刻必须是 ISO-8601: " + spec);
        }
    }

    static String normalizeKind(String kind) {
        String value = kind == null ? "" : kind.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case KIND_ONCE, KIND_INTERVAL, KIND_CRON -> value;
            default -> throw new IllegalArgumentException(
                    "kind 只能是 once、interval 或 cron，收到: " + kind);
        };
    }

    static String normalizeMissedPolicy(String policy) {
        String value = policy == null ? "" : policy.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "", MISSED_SKIP -> MISSED_SKIP;
            case MISSED_ONCE -> MISSED_ONCE;
            default -> throw new IllegalArgumentException("missedPolicy 只能是 skip 或 once");
        };
    }

    private static String runId(String scheduleId, Instant due) {
        return scheduleId + "@" + due.toEpochMilli();
    }
}
