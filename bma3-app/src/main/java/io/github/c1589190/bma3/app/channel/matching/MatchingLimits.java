package io.github.c1589190.bma3.app.channel.matching;

import java.time.Duration;

/** Hard resource bounds for one pure recomputation snapshot. */
public final class MatchingLimits {

    /** Maximum observations accepted in one thread snapshot. */
    public static final int MAX_OBSERVATIONS = 1024;
    /** Maximum explicit identity mappings accepted in one snapshot. */
    public static final int MAX_IDENTITIES = 1024;
    /** Maximum active channel bindings; a logical thread has at most one per channel. */
    public static final int MAX_BINDINGS = 2;
    /** Maximum segments accepted for one message. */
    public static final int MAX_SEGMENTS_PER_OBSERVATION = 512;
    /** Maximum total text code points accepted for one message. */
    public static final int MAX_TEXT_CODE_POINTS_PER_OBSERVATION = 100_000;
    /** Maximum accepted matching tolerance. */
    public static final Duration MAX_TIME_TOLERANCE = Duration.ofSeconds(300);

    private MatchingLimits() {
    }
}
