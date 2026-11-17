package io.mosire.bma3.qqofficial.api;

/**
 * Redacted HTTP diagnostics. This is the complete set of fields allowed to reach logs: account,
 * method, endpoint category, status, business code, sanitized trace id and elapsed time.
 */
public record OfficialQqHttpEvent(
        String accountId,
        String method,
        OfficialQqEndpoint endpoint,
        int httpStatus,
        String errorCode,
        String traceId,
        long elapsedMillis,
        boolean success) {

    public OfficialQqHttpEvent {
        accountId = OfficialQqApiException.safe(accountId, 128);
        method = OfficialQqApiException.safe(method, 16).toUpperCase(java.util.Locale.ROOT);
        endpoint = endpoint == null ? OfficialQqEndpoint.TOKEN : endpoint;
        errorCode = OfficialQqApiException.safe(errorCode, 96);
        traceId = OfficialQqApiException.safe(traceId, 160);
        elapsedMillis = Math.max(0L, elapsedMillis);
    }
}
