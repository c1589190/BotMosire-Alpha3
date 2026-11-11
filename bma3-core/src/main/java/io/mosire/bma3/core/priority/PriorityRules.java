package io.mosire.bma3.core.priority;

import java.util.Map;
import java.util.Optional;

/**
 * Most-specific-wins lookup over configuration priority rules.
 *
 * <p>Rule keys are the same shape as a source key ({@code qq_group}) or a longer prefix of it
 * ({@code qq_group/955432692}); {@code *} is the global fallback. A key matches when it equals
 * the rule or when the source continues it after a {@code /} boundary, so {@code qq_group} never
 * matches {@code qq_group2}. The most specific match is the one with the most {@code /}-separated
 * tokens; ties are broken by the longer literal key and then lexicographically, which keeps the
 * result deterministic for tests.</p>
 */
public final class PriorityRules {

    public static final String FALLBACK = "*";

    private PriorityRules() {
    }

    public record Match(int value, String ruleKey) {
    }

    public static Optional<Match> match(Map<String, Integer> rules, String sourceKey) {
        if (rules == null || rules.isEmpty() || sourceKey == null || sourceKey.isBlank()) {
            return Optional.empty();
        }
        Match best = null;
        for (Map.Entry<String, Integer> entry : rules.entrySet()) {
            String ruleKey = entry.getKey();
            Integer value = entry.getValue();
            if (ruleKey == null || value == null || !matches(ruleKey, sourceKey)) {
                continue;
            }
            Match candidate = new Match(value, ruleKey);
            if (best == null || isMoreSpecific(candidate, best)) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    static boolean matches(String ruleKey, String sourceKey) {
        if (FALLBACK.equals(ruleKey)) {
            return true;
        }
        if (ruleKey.length() > sourceKey.length()) {
            return false;
        }
        if (!sourceKey.startsWith(ruleKey)) {
            return false;
        }
        return sourceKey.length() == ruleKey.length()
                || sourceKey.charAt(ruleKey.length()) == '/';
    }

    private static boolean isMoreSpecific(Match candidate, Match current) {
        int candidateDepth = depth(candidate.ruleKey());
        int currentDepth = depth(current.ruleKey());
        if (candidateDepth != currentDepth) {
            return candidateDepth > currentDepth;
        }
        int candidateLength = candidate.ruleKey().length();
        int currentLength = current.ruleKey().length();
        if (candidateLength != currentLength) {
            return candidateLength > currentLength;
        }
        return candidate.ruleKey().compareTo(current.ruleKey()) < 0;
    }

    private static int depth(String ruleKey) {
        if (FALLBACK.equals(ruleKey)) {
            return 0;
        }
        int depth = 1;
        for (int index = 0; index < ruleKey.length(); index++) {
            if (ruleKey.charAt(index) == '/') {
                depth++;
            }
        }
        return depth;
    }
}
