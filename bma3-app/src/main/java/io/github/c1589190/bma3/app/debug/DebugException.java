package io.github.c1589190.bma3.app.debug;

/** HTTP-visible failure of the debug surface; {@code status} is the HTTP status to return. */
public final class DebugException extends RuntimeException {

    private final int status;
    private final String code;

    public DebugException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
