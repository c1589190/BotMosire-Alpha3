package io.github.c1589190.bma3.qqofficial.api;

/** Stable, non-sensitive classification for failures from the official QQ API. */
public enum OfficialQqErrorKind {
    CONFIGURATION,
    NETWORK,
    TIMEOUT,
    HTTP,
    AUTHENTICATION,
    PERMISSION,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER,
    MALFORMED_RESPONSE,
    BUSINESS;

    /** Whether a caller may consider a later, separate attempt at all. */
    public boolean potentiallyRetryable() {
        return this == NETWORK || this == TIMEOUT || this == RATE_LIMITED || this == SERVER;
    }
}
