package io.github.c1589190.bma3.qqofficial.media;

import java.util.Objects;

/**
 * Typed upload-boundary failure.
 *
 * <p>{@code NOT_DISPATCHED} is only used when the request provably never reached the platform.
 * {@code REJECTED} is an explicit platform refusal. {@code UNKNOWN} means the upload may have
 * completed but no usable result was obtained; the service must not continue to the message send
 * and must not automatically retry.</p>
 */
public final class OfficialQqMediaTransportException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        NOT_DISPATCHED,
        REJECTED,
        UNKNOWN
    }

    private final String errorCode;
    private final Kind kind;

    private OfficialQqMediaTransportException(String errorCode, Kind kind) {
        super(requireCode(errorCode));
        this.errorCode = errorCode;
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public static OfficialQqMediaTransportException notDispatched(String errorCode) {
        return new OfficialQqMediaTransportException(errorCode, Kind.NOT_DISPATCHED);
    }

    public static OfficialQqMediaTransportException rejected(String errorCode) {
        return new OfficialQqMediaTransportException(errorCode, Kind.REJECTED);
    }

    public static OfficialQqMediaTransportException unknown(String errorCode) {
        return new OfficialQqMediaTransportException(errorCode, Kind.UNKNOWN);
    }

    public String errorCode() {
        return errorCode;
    }

    public Kind kind() {
        return kind;
    }

    private static String requireCode(String code) {
        Objects.requireNonNull(code, "errorCode");
        if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("错误码必须是有界大写标识");
        }
        return code;
    }
}
