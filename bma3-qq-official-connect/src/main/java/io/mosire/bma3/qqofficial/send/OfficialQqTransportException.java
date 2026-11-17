package io.mosire.bma3.qqofficial.send;

import java.util.Objects;

/**
 * Typed provider-boundary failure.
 *
 * <p>{@code dispatched=false} is only allowed when the transport can prove the request never left
 * the process. Any post-dispatch ambiguity must use {@code dispatched=true} and becomes
 * {@code UNKNOWN}. Error codes are bounded identifiers; messages never contain request bodies,
 * tokens or arbitrary upstream text.</p>
 */
public final class OfficialQqTransportException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final boolean dispatched;

    private OfficialQqTransportException(String errorCode, boolean dispatched,
                                         String safeMessage) {
        super(safeMessage == null || safeMessage.isBlank() ? errorCode : safeMessage);
        this.errorCode = requireCode(errorCode);
        this.dispatched = dispatched;
    }

    public static OfficialQqTransportException notDispatched(String errorCode) {
        return new OfficialQqTransportException(errorCode, false, null);
    }

    public static OfficialQqTransportException unknown(String errorCode) {
        return new OfficialQqTransportException(errorCode, true, null);
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean dispatched() {
        return dispatched;
    }

    private static String requireCode(String code) {
        Objects.requireNonNull(code, "errorCode");
        if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("错误码必须是有界大写标识");
        }
        return code;
    }
}
