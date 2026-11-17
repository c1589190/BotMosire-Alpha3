package io.mosire.bma3.inbox.core;

import io.mosire.bma3.core.source.SourceView;
import java.time.Instant;
import java.util.Map;

/**
 * Item to append to one inbox.
 *
 * <p>{@code displayChannel} and {@code channelSummary} are optional projection metadata. Legacy
 * callers keep using the five-argument constructor/static factory and therefore produce exactly the
 * old single-channel behavior. A projected append may only carry body entries from its selected
 * display channel; Inbox itself does not know any platform-specific channel names.</p>
 */
public record AppendItem(
        SourceView source,
        InboxEntry entry,
        String summary,
        int priority,
        Instant availableAt,
        String displayChannel,
        Map<String, Object> channelSummary) {

    public AppendItem {
        if (source == null) {
            throw new IllegalArgumentException("source 不能为空");
        }
        summary = summary == null ? "" : summary;
        availableAt = availableAt == null ? Instant.now() : availableAt;
        displayChannel = displayChannel == null ? "" : displayChannel.trim();
        channelSummary = InboxEntry.immutableMap(channelSummary);
    }

    /** Backward-compatible legacy constructor: no channel projection metadata. */
    public AppendItem(SourceView source, InboxEntry entry, String summary, int priority,
                      Instant availableAt) {
        this(source, entry, summary, priority, availableAt, "", Map.of());
    }

    /** Adapter path: one structured entry; summary is rendered from it. */
    public static AppendItem of(SourceView source, InboxEntry entry, int priority,
                                Instant availableAt) {
        return new AppendItem(source, entry, entry == null ? "" : entry.render(),
                priority, availableAt == null ? Instant.now() : availableAt);
    }

    /**
     * Projected adapter path. The channel must be selected by the caller (preferred/fallback is an
     * app concern); Inbox only enforces that one open item keeps one display channel.
     */
    public static AppendItem projected(SourceView source, InboxEntry entry, int priority,
                                       Instant availableAt, String displayChannel,
                                       Map<String, Object> channelSummary) {
        // Fill a missing body channel from the selected display channel, but never overwrite an
        // explicit body channel: a selected/body mismatch must reach InboxService and fail closed
        // instead of silently labelling one channel's body as another.
        InboxEntry projectedEntry = entry;
        if (entry != null && displayChannel != null && !displayChannel.isBlank()
                && entry.channel().isEmpty()) {
            projectedEntry = entry.withChannelEvidence(displayChannel, entry.evidence());
        }
        return new AppendItem(source, projectedEntry,
                projectedEntry == null ? "" : projectedEntry.render(), priority,
                availableAt == null ? Instant.now() : availableAt, displayChannel,
                channelSummary);
    }

    /** Legacy path used by old tests/due; summary is already rendered. */
    public AppendItem(String source, String summary, int priority, Instant availableAt) {
        this(SourceView.of(source, source), null, summary, priority, availableAt);
    }
}
