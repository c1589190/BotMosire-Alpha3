package io.mosire.bma3.inbox.core;

/**
 * Hot-reloadable inbox runtime knobs.
 *
 * @param maxMessagesPerItem entries kept per item (1..50 by config)
 * @param maxItemsPerPanel   items rendered per panel (1..50 by config)
 * @param includeOmittedCount whether the panel prints omission counters
 * @param pushMinPriority    deprecated/ignored: every non-dropped item now pushes after its
 *                           delay. Kept only so old configs still load.
 * @param retentionMaxAgeDays ACKED items older than this are purged
 * @param retentionMaxItems  hard cap on stored items; oldest ACKED rows go first
 * @param maxCharsPerItem    rendered characters kept per item; {@code 0} disables the cap
 * @param maxFirstPushDelaySeconds hard cap on the first push of an open item; {@code 0} disables
 * @param autoDismissBelowPriority auto-dismiss items strictly below this priority;
 *                                 {@link Integer#MIN_VALUE} disables the policy
 * @param autoDismissAfterSeconds  auto-dismiss only items older than this
 * @param autoDismissMaxPerRound   max rows auto-dismissed per watchdog round
 */
public record InboxRuntime(
        int maxMessagesPerItem,
        int maxItemsPerPanel,
        boolean includeOmittedCount,
        int pushMinPriority,
        int retentionMaxAgeDays,
        int retentionMaxItems,
        int maxCharsPerItem,
        int maxFirstPushDelaySeconds,
        int autoDismissBelowPriority,
        int autoDismissAfterSeconds,
        int autoDismissMaxPerRound) {

    public InboxRuntime {
        maxMessagesPerItem = Math.max(1, maxMessagesPerItem);
        maxItemsPerPanel = Math.max(1, maxItemsPerPanel);
        retentionMaxAgeDays = Math.max(1, retentionMaxAgeDays);
        retentionMaxItems = Math.max(1, retentionMaxItems);
        maxCharsPerItem = Math.max(0, maxCharsPerItem);
        maxFirstPushDelaySeconds = Math.max(0, maxFirstPushDelaySeconds);
        autoDismissAfterSeconds = Math.max(0, autoDismissAfterSeconds);
        autoDismissMaxPerRound = Math.max(1, autoDismissMaxPerRound);
    }

    public static InboxRuntime defaults() {
        return new InboxRuntime(6, 6, true, -1000, 30, 5000, 1200, 60,
                Integer.MIN_VALUE, 1800, 200);
    }

    public InboxRuntime(int maxMessagesPerItem, int maxItemsPerPanel,
                        boolean includeOmittedCount) {
        this(maxMessagesPerItem, maxItemsPerPanel, includeOmittedCount, -1000, 30, 5000,
                1200, 60, Integer.MIN_VALUE, 1800, 200);
    }

    public InboxRuntime(int maxMessagesPerItem, int maxItemsPerPanel, boolean includeOmittedCount,
                        int pushMinPriority, int retentionMaxAgeDays, int retentionMaxItems) {
        this(maxMessagesPerItem, maxItemsPerPanel, includeOmittedCount, pushMinPriority,
                retentionMaxAgeDays, retentionMaxItems, 1200, 60,
                Integer.MIN_VALUE, 1800, 200);
    }

    public boolean autoDismissEnabled() {
        return autoDismissBelowPriority != Integer.MIN_VALUE;
    }
}
