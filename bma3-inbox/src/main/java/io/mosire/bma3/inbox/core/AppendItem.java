package io.mosire.bma3.inbox.core;

import io.mosire.bma3.core.source.SourceView;
import java.time.Instant;

/** Item to append to one inbox. */
public record AppendItem(
        SourceView source,
        InboxEntry entry,
        String summary,
        int priority,
        Instant availableAt) {

    public AppendItem {
        if (source == null) {
            throw new IllegalArgumentException("source 不能为空");
        }
        summary = summary == null ? "" : summary;
        availableAt = availableAt == null ? Instant.now() : availableAt;
    }

    /** Adapter path: one structured entry; summary is rendered from it. */
    public static AppendItem of(SourceView source, InboxEntry entry, int priority,
                                Instant availableAt) {
        return new AppendItem(source, entry, entry == null ? "" : entry.render(),
                priority, availableAt == null ? Instant.now() : availableAt);
    }

    /** Legacy path used by old tests/due; summary is already rendered. */
    public AppendItem(String source, String summary, int priority, Instant availableAt) {
        this(SourceView.of(source, source), null, summary, priority, availableAt);
    }
}
