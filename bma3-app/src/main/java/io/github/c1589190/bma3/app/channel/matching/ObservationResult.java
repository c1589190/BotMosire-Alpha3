package io.github.c1589190.bma3.app.channel.matching;

import java.util.Objects;
import java.util.Optional;

/** Persistable status for one observation in a completed recomputation. */
public record ObservationResult(
        ObservationRef observation,
        ObservationState state,
        Optional<ObservationRef> peer) {

    public ObservationResult {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(state, "state");
        peer = peer == null ? Optional.empty() : peer;
        if (state == ObservationState.MATCHED
                || state == ObservationState.UNVERIFIED_MATCH
                || state == ObservationState.CONFLICT) {
            if (peer.isEmpty()) {
                throw new IllegalArgumentException("匹配/冲突状态必须带 peer");
            }
        } else if (peer.isPresent()) {
            throw new IllegalArgumentException("未匹配状态不能带 peer");
        }
    }
}
