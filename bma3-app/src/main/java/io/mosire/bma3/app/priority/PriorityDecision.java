package io.mosire.bma3.app.priority;

import java.util.List;

/**
 * Outcome of one priority evaluation.
 *
 * <p>{@code reasons} is the human-readable arithmetic ("source=qq_group/955432692 → 200",
 * "qq_at_bot +100 …"). It is what turns "why did this wake me?" from guesswork into a fact the
 * agent can read.</p>
 *
 * @param priority final score after clamping
 * @param dropped  true when the score is below {@code weighting.dropBelow}: the message is not
 *                 even written to the message board
 * @param immediate true when the score reaches {@code weighting.immediateThreshold}: push after
 *                 the short delay, otherwise wait for the batch delay
 */
public record PriorityDecision(int priority, boolean dropped, boolean immediate,
                               List<String> reasons) {

    public PriorityDecision {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
