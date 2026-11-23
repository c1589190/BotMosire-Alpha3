package io.github.c1589190.bma3.inbox.core;

import java.time.Instant;
import java.util.Map;

/**
 * Evidence-only update for an existing inbox item.
 *
 * <p>This is deliberately a generic Inbox concern: the caller supplies opaque evidence metadata
 * and, optionally, a summary patch. Inbox never creates an item, appends body entries, advances the
 * panel/push revision, or schedules a wake from this object.</p>
 *
 * <p>Exactly one of {@code itemId} or {@code source} is normally set. A source resolves to the
 * open item for that source, falling back to its newest existing item; if neither resolves, the
 * update is rejected instead of creating a second item.</p>
 *
 * <p><b>Replay safety:</b> when {@code evidenceKey} is non-empty, the key is persisted inside the
 * item's {@code channelSummary.evidenceKeys}. Replaying the same key on the same item is a no-op,
 * including after a restart. Updates without a key are treated as an absolute evidence snapshot:
 * the evidence list/counters are replaced rather than incremented, so merely repeating a call can
 * never accumulate observations. Callers that need incremental late evidence should supply a
 * stable, unique {@code evidenceKey}.</p>
 *
 * <p>{@code matched} must be explicit for {@code matchedPriority} to participate. Even then the
 * priority is only raised while the item is still open (not frozen); a frozen item's priority is
 * immutable because the push already happened.</p>
 */
public record InboxEvidenceUpdate(
        String itemId,
        String source,
        String channel,
        Map<String, Object> evidence,
        Map<String, Object> channelSummary,
        Integer matchedPriority,
        boolean matched,
        Instant observedAt,
        String evidenceKey) {

    public InboxEvidenceUpdate {
        itemId = itemId == null ? "" : itemId.trim();
        source = source == null ? "" : source.trim();
        channel = channel == null ? "" : channel.trim();
        evidence = InboxEntry.immutableMap(evidence);
        channelSummary = InboxEntry.immutableMap(channelSummary);
        observedAt = observedAt == null ? Instant.now() : observedAt;
        evidenceKey = evidenceKey == null ? "" : evidenceKey.trim();
    }

    /** Backward-compatible constructor for the original Phase 9 API (snapshot semantics). */
    public InboxEvidenceUpdate(String itemId, String source, String channel,
                               Map<String, Object> evidence, Map<String, Object> channelSummary,
                               Integer matchedPriority, boolean matched, Instant observedAt) {
        this(itemId, source, channel, evidence, channelSummary, matchedPriority, matched,
                observedAt, "");
    }

    /** Convenience snapshot update addressed by item id. */
    public static InboxEvidenceUpdate forItem(String itemId, String channel,
                                              Map<String, Object> evidence) {
        return new InboxEvidenceUpdate(itemId, "", channel, evidence, Map.of(), null, false,
                Instant.now(), "");
    }

    /** Convenience snapshot update addressed by canonical/source id. */
    public static InboxEvidenceUpdate forSource(String source, String channel,
                                                Map<String, Object> evidence) {
        return new InboxEvidenceUpdate("", source, channel, evidence, Map.of(), null, false,
                Instant.now(), "");
    }

    /** Keyed update addressed by item id: replaying this key never accumulates again. */
    public static InboxEvidenceUpdate keyedItem(String itemId, String evidenceKey, String channel,
                                                Map<String, Object> evidence) {
        return new InboxEvidenceUpdate(itemId, "", channel, evidence, Map.of(), null, false,
                Instant.now(), evidenceKey);
    }

    /** Keyed update addressed by source id: replaying this key never accumulates again. */
    public static InboxEvidenceUpdate keyedSource(String source, String evidenceKey, String channel,
                                                  Map<String, Object> evidence) {
        return new InboxEvidenceUpdate("", source, channel, evidence, Map.of(), null, false,
                Instant.now(), evidenceKey);
    }

    /** Evidence update carrying explicit matched evidence and its priority (open items only). */
    public static InboxEvidenceUpdate matched(String itemId, String source, String channel,
                                              Map<String, Object> evidence,
                                              Map<String, Object> summaryPatch,
                                              int matchedPriority) {
        return new InboxEvidenceUpdate(itemId, source, channel, evidence, summaryPatch,
                matchedPriority, true, Instant.now(), "");
    }

    /** Keyed variant of {@link #matched}; repeated evidenceKey is a no-op. */
    public static InboxEvidenceUpdate matchedKeyed(String itemId, String source, String evidenceKey,
                                                   String channel, Map<String, Object> evidence,
                                                   Map<String, Object> summaryPatch,
                                                   int matchedPriority) {
        return new InboxEvidenceUpdate(itemId, source, channel, evidence, summaryPatch,
                matchedPriority, true, Instant.now(), evidenceKey);
    }

    public boolean hasTarget() {
        return !itemId.isEmpty() || !source.isEmpty();
    }
}
