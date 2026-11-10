package io.mosire.bma3.app.priority;

/** The resolved priority and whether the item should enter the wake panel. */
public record PriorityDecision(int priority, boolean notified, int threshold, int signalBoost) {
}
