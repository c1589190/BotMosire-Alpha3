package io.github.c1589190.bma3.app.channel.binding;

import java.util.Objects;

/** Typed, body-free coordinator failure. */
public final class BindingCoordinatorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_ARGUMENT,
        STORAGE_ERROR,
        JOURNAL_FAILED,
        BOARD_WATERMARK_UNAVAILABLE,
        SOURCE_ALREADY_BOUND,
        THREAD_CHANNEL_ALREADY_BOUND,
        LEDGER_BIND_FAILED,
        REGISTRY_BIND_FAILED,
        BIND_COMPENSATION_FAILED,
        BIND_RECOVERY_REQUIRED,
        UNBIND_RECOVERY_REQUIRED,
        REGISTRY_ACTIVE_LEDGER_MISSING,
        REGISTRY_ACTIVE_LEDGER_INACTIVE,
        REGISTRY_LEDGER_TUPLE_CONFLICT,
        LEDGER_HISTORY_UNPROVABLE,
        SOURCE_ENUMERATION_FAILED,
        REPAIR_BLOCKED
    }

    private final Code code;

    public BindingCoordinatorException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public BindingCoordinatorException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
