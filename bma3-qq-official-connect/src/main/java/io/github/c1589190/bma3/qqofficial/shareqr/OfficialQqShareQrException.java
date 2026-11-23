package io.github.c1589190.bma3.qqofficial.shareqr;

import io.github.c1589190.bma3.qqofficial.api.OfficialQqApiException;
import java.util.Objects;

/**
 * Sanitized failure for the runtime share-QR feature.
 *
 * <p>The exception carries only a stable machine code and a fixed, bounded human-readable
 * description. It deliberately does not retain a cause: an upstream parser or filesystem
 * exception could otherwise carry response text, a token, or a filesystem path into a caller log.
 * The share URL itself is a business result and is never put in this exception.</p>
 */
public final class OfficialQqShareQrException extends Exception {

    private static final long serialVersionUID = 1L;

    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
    public static final String ACCOUNT_RESOLUTION_FAILED = "ACCOUNT_RESOLUTION_FAILED";
    public static final String UPSTREAM_HTTP = "UPSTREAM_HTTP";
    public static final String UPSTREAM_NETWORK = "UPSTREAM_NETWORK";
    public static final String UPSTREAM_TIMEOUT = "UPSTREAM_TIMEOUT";
    public static final String UPSTREAM_AUTHENTICATION = "UPSTREAM_AUTHENTICATION";
    public static final String CAPABILITY_UNAVAILABLE = "CAPABILITY_UNAVAILABLE";
    public static final String UPSTREAM_NOT_FOUND = "UPSTREAM_NOT_FOUND";
    public static final String UPSTREAM_RATE_LIMITED = "UPSTREAM_RATE_LIMITED";
    public static final String UPSTREAM_SERVER = "UPSTREAM_SERVER";
    public static final String UPSTREAM_BUSINESS = "UPSTREAM_BUSINESS";
    public static final String MALFORMED_RESPONSE = "MALFORMED_RESPONSE";
    public static final String INVALID_SHARE_URL = "INVALID_SHARE_URL";
    public static final String QR_RENDER_FAILED = "QR_RENDER_FAILED";
    public static final String QR_IO_FAILURE = "QR_IO_FAILURE";
    public static final String CONFIGURATION = "CONFIGURATION";

    private final String code;
    private final String safeMessage;

    public OfficialQqShareQrException(String code, String safeMessage) {
        super(requireSafeMessage(safeMessage));
        this.code = requireSafeCode(code);
        this.safeMessage = safeMessage;
    }

    public String code() {
        return code;
    }

    public String safeMessage() {
        return safeMessage;
    }

    /**
     * Convert an upstream API failure into a stable, non-sensitive share-QR failure.
     *
     * <p>Only classification metadata is copied. In particular the upstream exception's message,
     * detail, response body, request body and Authorization value are not copied.</p>
     */
    public static OfficialQqShareQrException fromUpstream(OfficialQqApiException failure) {
        Objects.requireNonNull(failure, "failure");
        String code = switch (failure.kind()) {
            case AUTHENTICATION -> UPSTREAM_AUTHENTICATION;
            case PERMISSION -> CAPABILITY_UNAVAILABLE;
            case NOT_FOUND -> UPSTREAM_NOT_FOUND;
            case RATE_LIMITED -> UPSTREAM_RATE_LIMITED;
            case SERVER -> UPSTREAM_SERVER;
            case NETWORK -> UPSTREAM_NETWORK;
            case TIMEOUT -> UPSTREAM_TIMEOUT;
            case MALFORMED_RESPONSE -> MALFORMED_RESPONSE;
            case BUSINESS -> UPSTREAM_BUSINESS;
            case CONFIGURATION -> CONFIGURATION;
            case HTTP -> UPSTREAM_HTTP;
        };
        StringBuilder message = new StringBuilder("QQ 官方分享接口失败");
        append(message, "accountId", failure.accountId(), 128);
        append(message, "kind", failure.kind().name(), 64);
        if (failure.httpStatus() > 0) {
            append(message, "httpStatus", Integer.toString(failure.httpStatus()), 16);
        }
        append(message, "errorCode", failure.errorCode(), 96);
        append(message, "traceId", failure.traceId(), 160);
        return new OfficialQqShareQrException(code, message.toString());
    }

    private static void append(StringBuilder target, String key, String raw, int max) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String value = sanitize(raw, max);
        if (!value.isEmpty()) {
            target.append(' ').append(key).append('=').append(value);
        }
    }

    /** Removes controls and unusual text so diagnostics cannot inject log lines or echo bodies. */
    private static String sanitize(String raw, int maxLength) {
        String value = raw.strip();
        StringBuilder safe = new StringBuilder(Math.min(value.length(), maxLength));
        for (int index = 0; index < value.length() && safe.length() < maxLength; index++) {
            char ch = value.charAt(index);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '.' || ch == '_' || ch == '-' || ch == ':' || ch == '/' || ch == '@') {
                safe.append(ch);
            } else {
                safe.append('_');
            }
        }
        return safe.toString();
    }

    private static String requireSafeCode(String value) {
        if (value == null || value.isBlank() || value.length() > 64
                || !value.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("code 必须是稳定的大写错误码");
        }
        return value;
    }

    private static String requireSafeMessage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("safeMessage 不能为空");
        }
        return value;
    }
}
