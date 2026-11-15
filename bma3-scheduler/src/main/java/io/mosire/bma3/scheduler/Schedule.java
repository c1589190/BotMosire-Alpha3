package io.mosire.bma3.scheduler;

import java.time.Instant;

/**
 * One durable schedule. {@code kind} is {@code once}, {@code interval} or {@code cron}:
 *
 * <ul>
 *   <li>{@code once} — {@code spec} is an ISO-8601 instant or offset date-time;</li>
 *   <li>{@code interval} — {@code spec} is a whole number of seconds (≥ 1);</li>
 *   <li>{@code cron} — {@code spec} is a five-field crontab line.</li>
 * </ul>
 *
 * <p>{@code text} is what the Agent is told when the schedule fires; the scheduler never executes
 * work itself (invariant I-C4).</p>
 */
public record Schedule(
        String id,
        String kind,
        String spec,
        String text,
        int priority,
        boolean enabled,
        Instant nextRunAt,
        Instant lastRunAt,
        String missedPolicy,
        Instant createdAt,
        Instant updatedAt) {

    public Schedule {
        id = id == null ? "" : id;
        kind = kind == null ? "" : kind;
        spec = spec == null ? "" : spec;
        text = text == null ? "" : text;
        missedPolicy = missedPolicy == null || missedPolicy.isBlank()
                ? ScheduleService.MISSED_SKIP : missedPolicy;
    }

    public Schedule withNext(Instant next, Instant last) {
        return new Schedule(id, kind, spec, text, priority, enabled, next, last, missedPolicy,
                createdAt, last == null ? updatedAt : last);
    }

    public Schedule withEnabled(boolean value, Instant touchedAt) {
        return new Schedule(id, kind, spec, text, priority, value, nextRunAt, lastRunAt,
                missedPolicy, createdAt, touchedAt);
    }
}
