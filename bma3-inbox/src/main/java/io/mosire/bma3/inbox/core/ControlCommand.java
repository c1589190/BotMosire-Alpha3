package io.mosire.bma3.inbox.core;

import java.time.Instant;

/**
 * A command the server wants the DSH plugin to apply on its next heartbeat.
 *
 * <p>{@code action=restore} asks the plugin to bind/resume the given session and start the SSE;
 * {@code action=remind} only injects a short text into the already-bound session. The plugin echoes
 * {@code id} back as {@code appliedControlId} once it has applied the command.</p>
 */
public record ControlCommand(
        String id,
        String action,
        String sessionId,
        String remind,
        Instant createdAt) {

    public ControlCommand {
        id = id == null ? "" : id;
        action = action == null || action.isBlank() ? "restore" : action.trim();
        sessionId = sessionId == null ? "" : sessionId.trim();
        remind = remind == null ? "" : remind;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
