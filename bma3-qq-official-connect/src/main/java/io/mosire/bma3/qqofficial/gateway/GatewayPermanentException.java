package io.mosire.bma3.qqofficial.gateway;

import java.util.Objects;

/**
 * A caller-visible, non-retryable Gateway setup/authentication failure.
 *
 * <p>The code is diagnostic metadata only and is normalized to a bounded identifier. The cause is
 * retained for programmatic inspection but is never rendered into status or logs by this package.</p>
 */
public class GatewayPermanentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public GatewayPermanentException(String code) {
        this(code, null);
    }

    public GatewayPermanentException(String code, Throwable cause) {
        super(safeCode(code), cause, false, false);
        this.code = safeCode(code);
    }

    public String code() {
        return code;
    }

    private static String safeCode(String raw) {
        String value = Objects.requireNonNullElse(raw, "").strip();
        if (value.isEmpty()) {
            return "PERMANENT";
        }
        StringBuilder safe = new StringBuilder(Math.min(value.length(), 96));
        for (int index = 0; index < value.length() && safe.length() < 96; index++) {
            char ch = value.charAt(index);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '-' || ch == '.' || ch == ':') {
                safe.append(ch);
            } else {
                safe.append('_');
            }
        }
        return safe.toString();
    }
}
