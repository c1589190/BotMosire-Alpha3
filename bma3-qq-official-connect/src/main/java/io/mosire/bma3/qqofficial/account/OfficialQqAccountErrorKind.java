package io.mosire.bma3.qqofficial.account;

/**
 * Non-sensitive, stable classification of an official account failure.
 *
 * <p>No enum constant ever contains a message copied from a platform response, exception or
 * credential source. This is the only failure classification the registry places in public
 * status/log fields.</p>
 */
public enum OfficialQqAccountErrorKind {
    NONE,
    CONFIGURATION,
    CREDENTIAL_MISSING,
    CREDENTIAL_PERMISSION,
    CREDENTIAL_FORBIDDEN_LOCATION,
    CREDENTIAL_IO,
    AUTHENTICATION,
    PERMISSION,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER,
    NETWORK,
    TIMEOUT,
    HTTP,
    MALFORMED_RESPONSE,
    BUSINESS,
    PROTOCOL,
    ROUTING,
    INTERNAL
}
