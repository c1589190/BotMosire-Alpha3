package io.mosire.bma3.inbox.core;

import java.time.Instant;

/** Item to append to one inbox. */
public record AppendItem(String source, String summary, int priority, Instant availableAt) {

    public AppendItem {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source 不能为空");
        }
        summary = summary == null ? "" : summary;
        availableAt = availableAt == null ? Instant.now() : availableAt;
    }
}
