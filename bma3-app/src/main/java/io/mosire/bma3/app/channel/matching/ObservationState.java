package io.mosire.bma3.app.channel.matching;

/** Per-observation correlation result. */
public enum ObservationState {
    /** Strict, explicit-identity, unique 1:1 match. */
    MATCHED,
    /** Loose-mode unique content match; never a claim of proven identity. */
    UNVERIFIED_MATCH,
    /** A temporal peer exists, but identity/comparison/ambiguity prevents a match. */
    UNVERIFIED,
    /** Same explicit identity and time window, but text/media disagree. */
    CONFLICT,
    /** No opposite-channel temporal peer that could be the same logical message. */
    CHANNEL_ONLY
}
