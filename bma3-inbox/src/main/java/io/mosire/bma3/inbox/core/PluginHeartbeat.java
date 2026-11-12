package io.mosire.bma3.inbox.core;

import java.time.Instant;

/**
 * One heartbeat from the DSH plugin. It is the only way the server learns whether the wake path is
 * actually attached, because the plugin is the client in the SSE relationship.
 *
 * <p>Only ids/status/counters are carried: never a token, never a message body.</p>
 */
public record PluginHeartbeat(
        String sessionId,
        boolean bound,
        String sse,
        long lastPushRevision,
        String lastMcpStartId,
        String lastError,
        String appliedControlId,
        Instant at) {

    public PluginHeartbeat {
        sessionId = sessionId == null ? "" : sessionId.trim();
        sse = sse == null || sse.isBlank() ? "unknown" : sse.trim();
        lastMcpStartId = lastMcpStartId == null ? "" : lastMcpStartId;
        lastError = lastError == null ? "" : lastError;
        appliedControlId = appliedControlId == null ? "" : appliedControlId;
        at = at == null ? Instant.now() : at;
    }

    public boolean healthy() {
        return bound && !"closed".equals(sse) && !"retrying".equals(sse);
    }
}
