package io.github.c1589190.bma3.inbox.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hot-reloadable source-scoped quiet-window policy for the one Inbox push scheduler.
 *
 * <p>The policy never creates a second scheduler. A source is matched by a prefix with
 * {@code /} boundary semantics, and the longest matching prefix wins. An unconfigured source
 * falls back to the factory default, which is deliberately quiet=0 so existing deployments keep
 * their current push cadence until an Agent explicitly enables a wait.</p>
 */
public record InboxWaitPolicy(
        boolean enabled,
        int defaultQuietSeconds,
        int defaultMaxWaitSeconds,
        List<Rule> rules) {

    public static final int MAX_QUIET_SECONDS = 300;
    public static final int MAX_WAIT_SECONDS = 600;

    public InboxWaitPolicy {
        if (defaultQuietSeconds < 0 || defaultQuietSeconds > MAX_QUIET_SECONDS) {
            throw new IllegalArgumentException(
                    "inbox.wait.defaultQuietSeconds 必须在 0.." + MAX_QUIET_SECONDS);
        }
        if (defaultMaxWaitSeconds < 1 || defaultMaxWaitSeconds > MAX_WAIT_SECONDS) {
            throw new IllegalArgumentException(
                    "inbox.wait.defaultMaxWaitSeconds 必须在 1.." + MAX_WAIT_SECONDS);
        }
        if (defaultQuietSeconds > 0 && defaultMaxWaitSeconds < defaultQuietSeconds) {
            throw new IllegalArgumentException(
                    "inbox.wait.defaultMaxWaitSeconds 不能小于 defaultQuietSeconds");
        }
        rules = rules == null ? List.of() : List.copyOf(rules);
        Set<String> seen = new HashSet<>();
        for (Rule rule : rules) {
            if (!seen.add(rule.source())) {
                throw new IllegalArgumentException(
                        "inbox.wait.sources 重复来源: " + rule.source());
            }
        }
    }

    /** One source-prefix override. */
    public record Rule(String source, int quietSeconds, int maxWaitSeconds) {

        public Rule {
            source = source == null ? "" : source.trim();
            if (source.isEmpty()) {
                throw new IllegalArgumentException("inbox.wait.sources[].source 不能为空");
            }
            if (source.startsWith("/")) {
                throw new IllegalArgumentException(
                        "inbox.wait.sources[].source 不能以 / 开头");
            }
            if (quietSeconds < 0 || quietSeconds > MAX_QUIET_SECONDS) {
                throw new IllegalArgumentException(
                        "inbox.wait.sources[].quietSeconds 必须在 0.." + MAX_QUIET_SECONDS);
            }
            if (maxWaitSeconds < 1 || maxWaitSeconds > MAX_WAIT_SECONDS) {
                throw new IllegalArgumentException(
                        "inbox.wait.sources[].maxWaitSeconds 必须在 1.." + MAX_WAIT_SECONDS);
            }
            if (quietSeconds > 0 && maxWaitSeconds < quietSeconds) {
                throw new IllegalArgumentException(
                        "inbox.wait.sources[].maxWaitSeconds 不能小于 quietSeconds");
            }
        }
    }

    /** Effective values for one source. */
    public record Resolution(int quietSeconds, int maxWaitSeconds, String ruleSource) {

        public Resolution {
            quietSeconds = Math.max(0, quietSeconds);
            maxWaitSeconds = Math.max(1, maxWaitSeconds);
            ruleSource = ruleSource == null ? "" : ruleSource;
        }

        public boolean waiting() {
            return quietSeconds > 0;
        }
    }

    public static InboxWaitPolicy defaults() {
        return new InboxWaitPolicy(true, 0, 20, List.of());
    }

    /** Resolve the longest matching source prefix, or the factory default. */
    public Resolution resolve(String source) {
        if (!enabled) {
            return new Resolution(0, 1, "");
        }
        String key = source == null ? "" : source;
        Rule best = null;
        for (Rule rule : rules) {
            if (!applies(rule.source(), key)) {
                continue;
            }
            if (best == null || rule.source().length() > best.source().length()) {
                best = rule;
            }
        }
        if (best != null) {
            return new Resolution(best.quietSeconds(), best.maxWaitSeconds(), best.source());
        }
        return new Resolution(defaultQuietSeconds, defaultMaxWaitSeconds, "");
    }

    /** Same boundary rule as source-priority rules: prefix must end at a slash boundary. */
    static boolean applies(String prefix, String source) {
        if (prefix == null || prefix.isBlank() || source == null) {
            return false;
        }
        return source.equals(prefix)
                || (source.length() > prefix.length()
                    && source.startsWith(prefix)
                    && source.charAt(prefix.length()) == '/');
    }
}
