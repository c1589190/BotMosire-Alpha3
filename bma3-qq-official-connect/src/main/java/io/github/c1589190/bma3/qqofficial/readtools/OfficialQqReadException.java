package io.github.c1589190.bma3.qqofficial.readtools;

import java.util.Objects;

/**
 * Stable, non-sensitive failure for the official read tools.
 *
 * <p>Only a fixed machine code and a fixed safe message cross the AgentTool boundary. Upstream
 * exception text, response bodies, business error messages, tokens, Authorization values and
 * auth_token values are deliberately not retained.</p>
 */
public final class OfficialQqReadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** ToolResult error codes; these are intentionally stable API surface. */
    public enum Code {
        INVALID_ARGUMENT,
        AMBIGUOUS_ACCOUNT,
        ACCOUNT_NOT_FOUND,
        ACCOUNT_UNAVAILABLE,
        ACCOUNT_SOURCE_MISMATCH,
        INVALID_SOURCE,
        ITEM_NOT_FOUND,
        CAPABILITY_UNAVAILABLE,
        PERMISSION_DENIED,
        NOT_FRIEND,
        AUTHENTICATION_FAILED,
        RATE_LIMITED,
        UPSTREAM_UNAVAILABLE,
        MALFORMED_RESPONSE,
        DATA_UNAVAILABLE,
        INTERNAL_ERROR
    }

    private final Code code;
    private final String safeMessage;

    public OfficialQqReadException(Code code, String safeMessage) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"));
        this.code = Objects.requireNonNull(code, "code");
        this.safeMessage = safeMessage;
    }

    public Code code() {
        return code;
    }

    public String safeMessage() {
        return safeMessage;
    }
}
