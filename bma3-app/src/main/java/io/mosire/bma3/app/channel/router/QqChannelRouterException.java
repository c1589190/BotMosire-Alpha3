package io.mosire.bma3.app.channel.router;

import java.util.Objects;

/** Stable fail-closed router error; messages contain identifiers/status only, never message bodies. */
public final class QqChannelRouterException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Code code;

    public QqChannelRouterException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public QqChannelRouterException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_INPUT,
        CONTROL_UNAVAILABLE,
        BINDING_LEDGER_MISMATCH,
        ORPHAN_BOUND_BOARD_ROW,
        CORRELATION_APPLY_FAILED,
        RECOVERY_FAILED,
        STORAGE_FAILED
    }
}
