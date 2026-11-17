package io.mosire.bma3.qqofficial.send;

import java.util.Objects;

/** Safe, bounded failure raised before an official send is handed to the provider. */
public final class OfficialQqSendException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String code;

    public OfficialQqSendException(String code, String safeMessage) {
        super(safeMessage == null || safeMessage.isBlank() ? code : safeMessage);
        this.code = Objects.requireNonNull(code, "code");
        if (code.isBlank() || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("错误码必须是有界大写标识");
        }
    }

    public String code() {
        return code;
    }

    public String safeMessage() {
        return getMessage();
    }
}
