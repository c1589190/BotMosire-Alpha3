package io.mosire.bma3.inbox.core;

/**
 * Hot-reloadable inbox runtime knobs.
 *
 * @param maxMessagesPerItem entries kept per item (4..8 by config)
 * @param maxItemsPerPanel   items rendered per panel (4..8 by config)
 * @param includeOmittedCount whether the panel prints omission counters
 * @param pushMinPriority    items scoring below this are never pushed (they stay visible to tools)
 * @param retentionMaxAgeDays ACKED items older than this are purged
 * @param retentionMaxItems  hard cap on stored items; oldest ACKED rows go first
 */
public record InboxRuntime(
        int maxMessagesPerItem,
        int maxItemsPerPanel,
        boolean includeOmittedCount,
        int pushMinPriority,
        int retentionMaxAgeDays,
        int retentionMaxItems) {

    public InboxRuntime {
        maxMessagesPerItem = Math.max(1, maxMessagesPerItem);
        maxItemsPerPanel = Math.max(1, maxItemsPerPanel);
        retentionMaxAgeDays = Math.max(1, retentionMaxAgeDays);
        retentionMaxItems = Math.max(1, retentionMaxItems);
    }

    public static InboxRuntime defaults() {
        return new InboxRuntime(6, 6, true, -1000, 30, 5000);
    }

    public InboxRuntime(int maxMessagesPerItem, int maxItemsPerPanel, boolean includeOmittedCount) {
        this(maxMessagesPerItem, maxItemsPerPanel, includeOmittedCount, -1000, 30, 5000);
    }
}
