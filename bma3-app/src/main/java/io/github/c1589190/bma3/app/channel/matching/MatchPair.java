package io.github.c1589190.bma3.app.channel.matching;

import java.util.Objects;

/**
 * A 1:1 pair that reached a terminal relationship in this recomputation.
 *
 * <p>{@code CONFLICT} pairs are included for persistence/audit, but they never represent
 * corroboration. {@code UNVERIFIED_MATCH} is emitted only in loose mode.</p>
 */
public record MatchPair(
        ObservationState state,
        ObservationRef first,
        ObservationRef second) {

    public MatchPair {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (state != ObservationState.MATCHED
                && state != ObservationState.UNVERIFIED_MATCH
                && state != ObservationState.CONFLICT) {
            throw new IllegalArgumentException("pair state 必须是匹配或冲突状态");
        }
        if (first.channel() == second.channel()) {
            throw new IllegalArgumentException("cross-channel pair 的两个 observation 不能同渠道");
        }
    }
}
