package io.mosire.bma3.scheduler;

import java.time.Instant;

/** One due schedule, ready to be turned into an Inbox notification. */
public record ScheduleFired(String scheduleId, Instant dueAt, Instant firedAt, String text,
                            int priority) {
}
