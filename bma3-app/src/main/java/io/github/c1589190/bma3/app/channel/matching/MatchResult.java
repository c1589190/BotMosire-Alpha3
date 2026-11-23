package io.github.c1589190.bma3.app.channel.matching;

import java.util.List;
import java.util.Objects;

/**
 * Complete result of one pure recomputation.
 *
 * <p>The result never retains or renders input {@code Segment}s. Every field is safe for MCP
 * summaries, logs, evidence tables and persistence: IDs, source/account, timestamps, states and
 * counts only. No body-derived hash is emitted.</p>
 */
public record MatchResult(
        String threadId,
        MatchingMode mode,
        List<ObservationResult> observations,
        List<MatchPair> pairs,
        MatchCounts counts) {

    public MatchResult {
        threadId = Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(mode, "mode");
        observations = List.copyOf(observations);
        pairs = List.copyOf(pairs);
        Objects.requireNonNull(counts, "counts");
    }
}
