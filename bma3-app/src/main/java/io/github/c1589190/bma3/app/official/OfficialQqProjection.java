package io.github.c1589190.bma3.app.official;

import io.github.c1589190.bma3.app.priority.PriorityDecision;
import io.github.c1589190.bma3.core.source.SourceView;
import io.github.c1589190.bma3.core.store.AppendResult;
import io.github.c1589190.bma3.inbox.core.InboxEntry;
import io.github.c1589190.bma3.qqofficial.event.OfficialQqEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One successfully persisted official message offered to the app-side projection layer.
 *
 * <p>The message board append is included even when the row already existed so a future channel
 * router can recover a projection after a prior process died between board append and Inbox
 * projection. {@link #duplicateOnBoard()} is an explicit signal, not a reason to discard evidence.
 * The router supplied to {@link OfficialQqProjectionSink} decides whether, and on which canonical
 * source/channel, this observation becomes an Inbox body or evidence-only update.</p>
 *
 * <p>{@code requestKey} is stable for {@code (raw source, official message id)}. A sink that
 * writes Inbox must use it with the server-side idempotent append path (for example
 * {@code InboxService.appendProjectedOnce}). A successful sink is then never pushed twice by a
 * duplicate/replayed dispatch.</p>
 */
public record OfficialQqProjection(
        String accountId,
        OfficialQqEvent rawEvent,
        AppendResult boardAppend,
        SourceView source,
        PriorityDecision priority,
        InboxEntry entry,
        String requestKey,
        Instant availableAt,
        String displayChannel,
        Map<String, Object> channelSummary) {

    public OfficialQqProjection {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        accountId = accountId.strip();
        Objects.requireNonNull(rawEvent, "rawEvent");
        Objects.requireNonNull(boardAppend, "boardAppend");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(entry, "entry");
        if (requestKey == null || requestKey.isBlank()) {
            throw new IllegalArgumentException("requestKey 不能为空");
        }
        requestKey = requestKey.strip();
        availableAt = availableAt == null ? Instant.now() : availableAt;
        displayChannel = displayChannel == null ? "" : displayChannel.strip();
        channelSummary = channelSummary == null || channelSummary.isEmpty()
                ? Map.of()
                : Map.copyOf(new java.util.LinkedHashMap<>(channelSummary));
    }

    /** True when the board row was already present and this dispatch is recovery/replay. */
    public boolean duplicateOnBoard() {
        return !boardAppend.created();
    }

    /** The canonical board row, including its stable local sequence for the Inbox entry. */
    public io.github.c1589190.bma3.core.message.Message boardMessage() {
        return boardAppend.message();
    }
}
