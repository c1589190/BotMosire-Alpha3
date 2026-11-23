package io.github.c1589190.bma3.app.channel.matching;

/**
 * Explicit evidence that the adapter decoded the complete comparable message content.
 *
 * <p>Compatibility constructors default to {@link #UNKNOWN} so callers must opt in with positive
 * evidence. Only {@link #COMPLETE} may participate in a positive match.</p>
 */
public enum ContentIntegrity {
    /** Adapter confirmed the full content was decoded without truncation. */
    COMPLETE,
    /** Adapter knows parsing did not complete. */
    INCOMPLETE,
    /** Adapter had to truncate content. */
    TRUNCATED,
    /** Adapter cannot state whether content is complete. */
    UNKNOWN;

    public boolean reliable() {
        return this == COMPLETE;
    }
}
