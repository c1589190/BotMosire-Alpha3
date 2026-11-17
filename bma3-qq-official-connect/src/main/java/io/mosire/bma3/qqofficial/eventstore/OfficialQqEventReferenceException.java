package io.mosire.bma3.qqofficial.eventstore;

import java.util.Objects;

/**
 * Stable, non-sensitive failure from the official event-reference store.
 *
 * <p>The message is fixed per code and never includes event ids, openids, message bodies, raw
 * payloads, tokens, Authorization values or SQL exception text.</p>
 */
public final class OfficialQqEventReferenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_ACCOUNT,
        INVALID_SOURCE,
        INVALID_EVENT_ID,
        INVALID_MESSAGE_ID,
        ACCOUNT_SOURCE_MISMATCH,
        UNSUPPORTED_EVENT,
        NOT_INBOUND,
        NOT_REPLYABLE,
        EVENT_REFERENCE_CONFLICT,
        UNKNOWN_EVENT_REFERENCE,
        ACCOUNT_MISMATCH,
        SOURCE_MISMATCH,
        STORAGE_ERROR
    }

    private final Code code;

    public OfficialQqEventReferenceException(Code code, String safeMessage) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"));
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
