package io.github.c1589190.bma3.scheduler;

/** What a due schedule does: hand one notification to the composition root. */
@FunctionalInterface
public interface ScheduleSink {

    /**
     * Deliver one fired schedule.
     *
     * @param fire the fired event (id, due time, notification text, priority)
     * @throws RuntimeException when delivery failed; the scheduler records {@code FAILED} and does
     *                          not retry (invariant I-C1: no notification storm)
     */
    void fire(ScheduleFired fire);
}
