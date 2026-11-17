package io.mosire.bma3.qqofficial.account;

import io.mosire.bma3.qqofficial.api.OfficialQqApiException;
import java.time.Instant;

/**
 * Credential-free snapshot returned by one account runtime.
 *
 * <p>The registry never copies arbitrary exception messages or response bodies into status. This
 * record therefore carries only bounded diagnostic identifiers and the stable error
 * classification. Factories adapting
 * {@link io.mosire.bma3.qqofficial.gateway.GatewayStatus} should map the gateway state directly and
 * map close/error codes to an {@link OfficialQqAccountErrorKind}.</p>
 */
public record OfficialQqAccountRuntimeStatus(
        OfficialQqAccountState state,
        boolean connected,
        boolean terminal,
        Instant updatedAt,
        Instant lastConnectedAt,
        Instant lastEventAt,
        Long lastSequence,
        int reconnectAttempts,
        OfficialQqAccountErrorKind errorKind,
        String errorCode,
        String errorType,
        String traceId) {

    public OfficialQqAccountRuntimeStatus {
        state = state == null ? OfficialQqAccountState.STOPPED : state;
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
        reconnectAttempts = Math.max(0, reconnectAttempts);
        errorKind = errorKind == null ? OfficialQqAccountErrorKind.NONE : errorKind;
        errorCode = safe(errorCode);
        errorType = safe(errorType);
        traceId = safe(traceId);
        terminal = terminal || state.terminal();
    }

    public static OfficialQqAccountRuntimeStatus starting() {
        return new OfficialQqAccountRuntimeStatus(
                OfficialQqAccountState.CONNECTING, false, false, Instant.now(), null, null,
                null, 0, OfficialQqAccountErrorKind.NONE, "", "", "");
    }

    public static OfficialQqAccountRuntimeStatus down(
            OfficialQqAccountErrorKind kind, String code, String errorType) {
        return new OfficialQqAccountRuntimeStatus(
                OfficialQqAccountState.DOWN, false, true, Instant.now(), null, null,
                null, 0, kind, code, errorType, "");
    }

    public static OfficialQqAccountRuntimeStatus closed() {
        return new OfficialQqAccountRuntimeStatus(
                OfficialQqAccountState.CLOSED, false, true, Instant.now(), null, null,
                null, 0, OfficialQqAccountErrorKind.NONE, "", "", "");
    }

    public boolean healthy() {
        return !terminal && connected && state == OfficialQqAccountState.READY;
    }

    /**
     * Bounded filter shared with the HTTP layer. It removes whitespace/control characters and
     * unexpected punctuation, which also prevents log/source injection through platform echoes.
     */
    public static String safe(String value) {
        return OfficialQqApiException.safe(value, 160);
    }

    @Override
    public String toString() {
        return "OfficialQqAccountRuntimeStatus[state=" + state
                + ", connected=" + connected
                + ", terminal=" + terminal
                + ", updatedAt=" + updatedAt
                + ", lastConnectedAt=" + lastConnectedAt
                + ", lastEventAt=" + lastEventAt
                + ", lastSequence=" + lastSequence
                + ", reconnectAttempts=" + reconnectAttempts
                + ", errorKind=" + errorKind
                + ", errorCode=" + errorCode
                + ", errorType=" + errorType
                + ", traceId=" + traceId + "]";
    }

}
