package io.github.c1589190.bma3.app.channel.matching;

/**
 * Reliability of the message timestamp used for the time-window criterion.
 *
 * <p>Compatibility constructors default to {@link #UNKNOWN}. Official events that used a local
 * receive-time fallback must be marked {@link #FALLBACK}; neither fallback nor unknown time may
 * produce a positive match.</p>
 */
public enum TimeReliability {
    /** Source timestamp was parsed and is trusted. */
    RELIABLE,
    /** Source timestamp was missing/invalid and a receive-time fallback was used. */
    FALLBACK,
    /** Caller cannot establish timestamp reliability. */
    UNKNOWN;

    public boolean reliable() {
        return this == RELIABLE;
    }
}
