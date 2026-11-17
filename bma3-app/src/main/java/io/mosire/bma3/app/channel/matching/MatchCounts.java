package io.mosire.bma3.app.channel.matching;

/** Per-state observation counts and terminal pair counts for persistence summaries. */
public record MatchCounts(
        int observations,
        int matchedObservations,
        int unverifiedMatchObservations,
        int unverifiedObservations,
        int conflictObservations,
        int channelOnlyObservations,
        int matchedPairs,
        int unverifiedMatchPairs,
        int conflictPairs) {

    public MatchCounts {
        if (observations < 0 || matchedObservations < 0 || unverifiedMatchObservations < 0
                || unverifiedObservations < 0 || conflictObservations < 0
                || channelOnlyObservations < 0 || matchedPairs < 0
                || unverifiedMatchPairs < 0 || conflictPairs < 0) {
            throw new IllegalArgumentException("count 不能为负");
        }
        if (observations != matchedObservations + unverifiedMatchObservations
                + unverifiedObservations + conflictObservations + channelOnlyObservations) {
            throw new IllegalArgumentException("observation counts 不自洽");
        }
    }

    public int pairs() {
        return matchedPairs + unverifiedMatchPairs + conflictPairs;
    }
}
