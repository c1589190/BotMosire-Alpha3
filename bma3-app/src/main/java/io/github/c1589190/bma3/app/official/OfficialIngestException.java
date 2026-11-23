package io.github.c1589190.bma3.app.official;

/**
 * Stable failure code for one official-event ingest step.
 *
 * <p>The exception deliberately keeps untrusted accounts, openids, message ids, event names, and
 * message bodies out of its message. Callers (especially the gateway event sink) can therefore
 * propagate/replay the event without turning a malformed or credential-bearing payload into a log
 * or tool-result leak.</p>
 */
public final class OfficialIngestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_ACCOUNT,
        ACCOUNT_LOOKUP_FAILED,
        UNKNOWN_ACCOUNT,
        INVALID_SOURCE,
        INVALID_MESSAGE_ID,
        INVALID_SEGMENT,
        DIRECTORY_FAILED,
        PROJECTION_FAILED,
        PROJECTION_RESULT_MISSING
    }

    private final Code code;

    public OfficialIngestException(Code code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public OfficialIngestException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
