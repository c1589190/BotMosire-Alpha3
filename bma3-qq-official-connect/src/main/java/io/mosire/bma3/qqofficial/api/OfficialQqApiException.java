package io.mosire.bma3.qqofficial.api;

import java.util.Locale;

/**
 * Sanitized failure from an official QQ HTTP exchange.
 *
 * <p>The exception deliberately contains only classification metadata. It never carries a
 * request/response body. That prevents AppSecret, AccessToken, Authorization values, user text and
 * echoed request fields from reaching logs through an exception message.</p>
 */
public final class OfficialQqApiException extends Exception {

    private static final long serialVersionUID = 1L;
    private static final int MAX_TOKEN_LENGTH = 96;
    private static final int MAX_TRACE_LENGTH = 160;

    private final OfficialQqErrorKind kind;
    private final int httpStatus;
    private final String accountId;
    private final String endpoint;
    private final String errorCode;
    private final String traceId;
    private final boolean retryable;

    public OfficialQqApiException(
            OfficialQqErrorKind kind,
            int httpStatus,
            String accountId,
            String endpoint,
            String errorCode,
            String traceId,
            boolean retryable,
            String safeDetail,
            Throwable cause) {
        super(message(kind, httpStatus, accountId, endpoint, errorCode, traceId, safeDetail), cause);
        this.kind = kind == null ? OfficialQqErrorKind.BUSINESS : kind;
        this.httpStatus = httpStatus;
        this.accountId = safe(accountId, 128);
        this.endpoint = safe(endpoint, 64);
        this.errorCode = safe(errorCode, MAX_TOKEN_LENGTH);
        this.traceId = safe(traceId, MAX_TRACE_LENGTH);
        this.retryable = retryable;
    }

    public OfficialQqErrorKind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String accountId() {
        return accountId;
    }

    public String endpoint() {
        return endpoint;
    }

    public String errorCode() {
        return errorCode;
    }

    public String traceId() {
        return traceId;
    }

    public boolean retryable() {
        return retryable;
    }

    /** True only for responses that explicitly indicate the bearer token was rejected. */
    public boolean tokenRejected() {
        return kind == OfficialQqErrorKind.AUTHENTICATION;
    }

    private static String message(
            OfficialQqErrorKind kind,
            int httpStatus,
            String accountId,
            String endpoint,
            String errorCode,
            String traceId,
            String safeDetail) {
        StringBuilder text = new StringBuilder("QQ 官方 API 调用失败");
        append(text, "accountId", accountId);
        append(text, "endpoint", endpoint);
        append(text, "kind", kind == null ? "UNKNOWN" : kind.name());
        if (httpStatus > 0) {
            append(text, "httpStatus", Integer.toString(httpStatus));
        }
        append(text, "errorCode", errorCode);
        append(text, "traceId", traceId);
        append(text, "detail", safeDetail);
        return text.toString();
    }

    private static void append(StringBuilder target, String key, String raw) {
        String value = safe(raw, key.equals("traceId") ? MAX_TRACE_LENGTH : MAX_TOKEN_LENGTH);
        if (!value.isEmpty()) {
            target.append(' ').append(key).append('=').append(value);
        }
    }

    /**
     * Remove caller-known secrets before applying the diagnostic character filter. This protects
     * against a broken server echoing an Authorization value or credential into trace metadata.
     */
    public static String redact(String raw, String... secrets) {
        if (raw == null) {
            return "";
        }
        String value = raw;
        if (secrets != null) {
            for (String secret : secrets) {
                if (secret != null && !secret.isEmpty()) {
                    value = value.replace(secret, "<redacted>");
                }
            }
        }
        return value;
    }

    /**
     * Keeps diagnostics single-line and bounded. Replacing unusual bytes also prevents log
     * injection when a platform echoes an unexpected trace id.
     */
    public static String safe(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        String value = raw.strip();
        StringBuilder safe = new StringBuilder(Math.min(value.length(), maxLength));
        for (int i = 0; i < value.length() && safe.length() < maxLength; i++) {
            char ch = value.charAt(i);
            if (Character.isLetterOrDigit(ch)
                    || ch == ' ' || ch == '.' || ch == '_' || ch == '-'
                    || ch == ':' || ch == '/' || ch == '@') {
                safe.append(ch);
            } else {
                safe.append('_');
            }
        }
        return safe.toString();
    }
}
