package io.mosire.bma3.qqofficial.account;

import java.time.Instant;

/**
 * Public account status returned by {@link OfficialQqAccountRegistry}.
 *
 * <p>No credential, token, Authorization value, secret file path or arbitrary exception message
 * is present. The error classification and bounded identifiers are sufficient for tools and
 * watchdog probes while keeping logs and MCP results safe.</p>
 */
public record OfficialQqAccountStatus(
        String accountId,
        OfficialQqAccountState state,
        boolean enabled,
        boolean connected,
        boolean terminal,
        int shardIndex,
        int shardCount,
        Instant updatedAt,
        Instant lastConnectedAt,
        Instant lastEventAt,
        Long lastSequence,
        int reconnectAttempts,
        OfficialQqAccountErrorKind errorKind,
        String errorCode,
        String errorType,
        String traceId) {

    public OfficialQqAccountStatus {
        accountId = accountId == null ? "" : accountId;
        state = state == null ? OfficialQqAccountState.STOPPED : state;
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
        reconnectAttempts = Math.max(0, reconnectAttempts);
        errorKind = errorKind == null ? OfficialQqAccountErrorKind.NONE : errorKind;
        errorCode = OfficialQqAccountRuntimeStatus.safe(errorCode);
        errorType = OfficialQqAccountRuntimeStatus.safe(errorType);
        traceId = OfficialQqAccountRuntimeStatus.safe(traceId);
        terminal = terminal || state.terminal();
    }

    public boolean healthy() {
        return enabled && !terminal && connected && state == OfficialQqAccountState.READY;
    }

    @Override
    public String toString() {
        return "OfficialQqAccountStatus[accountId=" + accountId
                + ", state=" + state
                + ", enabled=" + enabled
                + ", connected=" + connected
                + ", terminal=" + terminal
                + ", shardIndex=" + shardIndex
                + ", shardCount=" + shardCount
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
