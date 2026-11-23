package io.github.c1589190.bma3.qqofficial.gateway;

import java.time.Instant;

/**
 * Public, credential-free Gateway snapshot.
 *
 * <p>Deliberately excludes the access token, session id and raw close reason. Those values are
 * protocol/session internals; callers only need to know whether a Resume is possible. The exposed
 * sequence is the last <em>committed</em> sequence (sink success), not merely the last received
 * frame sequence.</p>
 */
public record GatewayStatus(
        String accountId,
        GatewayState state,
        boolean connected,
        Instant updatedAt,
        Instant lastConnectedAt,
        Instant lastEventAt,
        Long lastSequence,
        boolean resumable,
        int reconnectAttempts,
        Integer lastCloseCode,
        String lastErrorCode,
        String lastErrorType,
        String terminalReason) {

    public GatewayStatus {
        accountId = accountId == null ? "" : accountId;
        state = state == null ? GatewayState.STOPPED : state;
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
        lastErrorCode = lastErrorCode == null ? "" : lastErrorCode;
        lastErrorType = lastErrorType == null ? "" : lastErrorType;
        terminalReason = terminalReason == null ? "" : terminalReason;
    }

    public boolean terminal() {
        return state == GatewayState.DOWN;
    }
}
