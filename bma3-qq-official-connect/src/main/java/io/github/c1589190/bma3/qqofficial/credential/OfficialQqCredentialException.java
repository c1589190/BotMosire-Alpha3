package io.github.c1589190.bma3.qqofficial.credential;

/** Fail-closed credential lookup error. The message never contains the secret value. */
public final class OfficialQqCredentialException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        CONFIGURATION,
        MISSING,
        PERMISSION,
        FORBIDDEN_LOCATION,
        IO
    }

    private final Reason reason;
    private final String accountId;
    private final String source;

    public OfficialQqCredentialException(
            Reason reason, String accountId, String source, String safeDetail, Throwable cause) {
        super(buildMessage(reason, accountId, source, safeDetail), cause);
        this.reason = reason;
        this.accountId = safe(accountId);
        this.source = safe(source);
    }

    public Reason reason() {
        return reason;
    }

    public String accountId() {
        return accountId;
    }

    public String source() {
        return source;
    }

    private static String buildMessage(
            Reason reason, String accountId, String source, String safeDetail) {
        StringBuilder message = new StringBuilder("QQ 官方凭据不可用");
        String account = safe(accountId);
        String sourceName = safe(source);
        String detail = safe(safeDetail);
        if (!account.isEmpty()) {
            message.append(" accountId=").append(account);
        }
        if (!sourceName.isEmpty()) {
            message.append(" source=").append(sourceName);
        }
        message.append(" reason=").append(reason == null ? "UNKNOWN" : reason.name());
        if (!detail.isEmpty()) {
            message.append(" detail=").append(detail);
        }
        return message.toString();
    }

    private static String safe(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.strip();
        StringBuilder out = new StringBuilder(Math.min(value.length(), 128));
        for (int i = 0; i < value.length() && out.length() < 128; i++) {
            char ch = value.charAt(i);
            if (Character.isLetterOrDigit(ch)
                    || ch == ' ' || ch == '.' || ch == '_' || ch == '-' || ch == ':' || ch == '/' || ch == '@') {
                out.append(ch);
            } else {
                out.append('_');
            }
        }
        return out.toString();
    }
}
