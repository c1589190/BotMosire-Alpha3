package io.mosire.bma3.inbox.core;

import java.time.Instant;
import java.util.Map;

/**
 * One heartbeat from the DSH plugin. It is the only way the server learns whether the wake path is
 * actually attached, because the plugin is the client in the SSE relationship.
 *
 * <p>Only ids/status/counters are carried: never a token, never a message body. The optional
 * {@code diagnostics} map carries sanitized plugin-side summaries such as tool registration,
 * deferred push and fresh verification state.</p>
 */
public record PluginHeartbeat(
        String sessionId,
        boolean bound,
        String sse,
        long lastPushRevision,
        String lastMcpStartId,
        String lastError,
        String appliedControlId,
        String tokenSource,
        boolean tokenOk,
        Instant at,
        Map<String, Object> diagnostics) {

    public PluginHeartbeat {
        sessionId = sessionId == null ? "" : sessionId.trim();
        sse = sse == null || sse.isBlank() ? "unknown" : sse.trim();
        lastMcpStartId = lastMcpStartId == null ? "" : lastMcpStartId;
        lastError = lastError == null ? "" : lastError;
        appliedControlId = appliedControlId == null ? "" : appliedControlId;
        tokenSource = tokenSource == null || tokenSource.isBlank() ? "unknown" : tokenSource.trim();
        at = at == null ? Instant.now() : at;
        diagnostics = diagnostics == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(diagnostics));
    }

    /** Compatibility constructor: heartbeats sent before tokenSource/tokenOk existed. */
    public PluginHeartbeat(String sessionId, boolean bound, String sse, long lastPushRevision,
                           String lastMcpStartId, String lastError, String appliedControlId,
                           Instant at) {
        this(sessionId, bound, sse, lastPushRevision, lastMcpStartId, lastError, appliedControlId,
                "unknown", true, at, Map.of());
    }

    /** Compatibility constructor: heartbeats sent before diagnostics existed. */
    public PluginHeartbeat(String sessionId, boolean bound, String sse, long lastPushRevision,
                           String lastMcpStartId, String lastError, String appliedControlId,
                           String tokenSource, boolean tokenOk, Instant at) {
        this(sessionId, bound, sse, lastPushRevision, lastMcpStartId, lastError, appliedControlId,
                tokenSource, tokenOk, at, Map.of());
    }

    public boolean healthy() {
        return bound && !"closed".equals(sse) && !"retrying".equals(sse);
    }
}
