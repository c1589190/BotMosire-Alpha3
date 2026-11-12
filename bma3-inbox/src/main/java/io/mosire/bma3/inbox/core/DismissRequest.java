package io.mosire.bma3.inbox.core;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * One bulk-dismiss request: "confirm without reading" (D-042).
 *
 * <p>Dismiss is the counterpart of {@code ack}: both make an item invisible, but ack names what a
 * reader actually saw, while dismiss selects by filter and is meant for backlog cleanup. It never
 * deletes rows; retention removes ACKED rows later.</p>
 *
 * @param scope            {@code visible} (top panel window), {@code filter} (all matching), or
 *                         {@code ids}
 * @param itemIds          explicit ids for {@code scope=ids}
 * @param sourcePrefix     source prefix filter, e.g. {@code qq_group/955432692}
 * @param priorityBelow    strict {@code priority < value}
 * @param priorityAtLeast  inclusive {@code priority >= value}
 * @param olderThanSeconds only items whose {@code updatedAt} is older than this
 * @param limit            hard cap on dismissed rows (1..500)
 * @param dryRun           report matches without changing state
 * @param allowImportant   allow dismissing items at/above the immediate threshold
 * @param reason           short operator/agent reason, logged without message content
 */
public record DismissRequest(
        String scope,
        Set<String> itemIds,
        String sourcePrefix,
        Integer priorityBelow,
        Integer priorityAtLeast,
        Long olderThanSeconds,
        int limit,
        boolean dryRun,
        boolean allowImportant,
        String reason) {

    public static final int MAX_LIMIT = 500;

    public DismissRequest {
        scope = scope == null || scope.isBlank() ? "visible" : scope.trim().toLowerCase(java.util.Locale.ROOT);
        if (!scope.equals("visible") && !scope.equals("filter") && !scope.equals("ids")) {
            throw new IllegalArgumentException("scope 只能是 visible/filter/ids");
        }
        itemIds = itemIds == null ? Set.of() : Set.copyOf(itemIds);
        sourcePrefix = sourcePrefix == null ? "" : sourcePrefix.trim();
        limit = Math.max(1, Math.min(MAX_LIMIT, limit));
        dryRun = dryRun;
        reason = reason == null ? "" : reason.trim();
        if (scope.equals("ids") && itemIds.isEmpty()) {
            throw new IllegalArgumentException("scope=ids 时 itemIds 不能为空");
        }
        if (scope.equals("filter") && sourcePrefix.isEmpty()
                && priorityBelow == null && priorityAtLeast == null && olderThanSeconds == null) {
            throw new IllegalArgumentException(
                    "scope=filter 至少需要一个过滤条件（sourcePrefix/priorityBelow/priorityAtLeast/olderThanSeconds）");
        }
        if (priorityBelow != null && priorityAtLeast != null
                && priorityAtLeast > priorityBelow) {
            throw new IllegalArgumentException("priorityAtLeast 不能大于 priorityBelow");
        }
        if (olderThanSeconds != null && olderThanSeconds < 0) {
            throw new IllegalArgumentException("olderThanSeconds 不能为负");
        }
    }

    public static DismissRequest visible(int limit, boolean dryRun) {
        return new DismissRequest("visible", Set.of(), "", null, null, null, limit, dryRun,
                false, "");
    }

    public static DismissRequest ids(Set<String> ids, boolean dryRun) {
        return new DismissRequest("ids", ids, "", null, null, null, MAX_LIMIT, dryRun,
                false, "");
    }

    public static DismissRequest filterBelow(int priorityBelow, long olderThanSeconds,
                                             int limit, boolean dryRun) {
        return new DismissRequest("filter", Set.of(), "", priorityBelow, null,
                olderThanSeconds, limit, dryRun, false, "");
    }
}
