package io.mosire.bma3.inbox.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Single-inbox aggregate panel. It owns display windows and revisions, not message fact.
 *
 * <p>Two rules from the operator shape everything here:</p>
 * <ul>
 *   <li><b>Every item is acked independently.</b> The global revision cursor is only an
 *       observation ("how far the panel has been read"), never a visibility switch.</li>
 *   <li><b>Pushes are batched by score.</b> Items at/above {@code importantThreshold} push after
 *       the short delay, everything else after the batch delay, anchored on the earliest pending
 *       item; after a push round the clock resets and the remaining/new items are re-sorted and
 *       re-scheduled from that moment.</li>
 * </ul>
 */
public final class InboxService {

    private static final int PURGE_EVERY_APPENDS = 100;

    private final InboxStore store;
    private final DeliveryPolicy policy;
    private final String inboxId;
    private final Supplier<InboxRuntime> runtime;
    private long panelRevision;
    private long pushRevision;
    private long ackedRevision;
    private boolean closed;
    private int appendsSincePurge;
    /** itemId -> the item revision that was already handed to a push. */
    private final Map<String, Integer> pushedItems = new HashMap<>();
    /**
     * itemId -> when this item first started owing a push. Anchoring on the <b>first</b> message is
     * what makes "a busy source cannot postpone its own batch" true: merging only refreshes
     * {@code updatedAt}, so that field must not be the anchor.
     */
    private final Map<String, Instant> pendingSince = new HashMap<>();
    /** When the last push round happened; pending items are re-scheduled from here. */
    private Instant lastFlushAt = Instant.EPOCH;

    public InboxService(InboxStore store, DeliveryPolicy policy) {
        this(store, policy, "bma3");
    }

    public InboxService(InboxStore store, DeliveryPolicy policy, String inboxId) {
        this(store, policy, inboxId, InboxRuntime::defaults);
    }

    public InboxService(InboxStore store, DeliveryPolicy policy, String inboxId,
                        Supplier<InboxRuntime> runtime) {
        this.store = store;
        this.policy = policy;
        this.inboxId = inboxId == null || inboxId.isBlank() ? "bma3" : inboxId;
        this.runtime = runtime == null ? InboxRuntime::defaults : runtime;
        this.panelRevision = store.panelRevision();
        this.ackedRevision = store.ackedRevision();
        purgeNow();
    }

    /** Legacy append: one new item, no merge. */
    public synchronized InboxItem append(AppendItem request) {
        long revision = nextRevision();
        InboxEntry entry = request.entry();
        List<InboxEntry> entries = entry == null ? List.of() : List.of(entry);
        String summary = entry == null ? request.summary() : entry.render();
        InboxItem item = new InboxItem(
                "itm-" + UUID.randomUUID(), request.source().raw(), request.source().label(),
                summary, entries, 1, revision, request.priority(),
                ItemStatus.AVAILABLE, request.availableAt(), Instant.now(), Instant.now(), 1);
        store.append(item);
        afterAppend(item);
        return item;
    }

    /**
     * Append one rendered message to the source's active window, or start a new item when the
     * previous one was delivered/acked. The item keeps only the last N entries.
     */
    public synchronized InboxItem appendOrMerge(AppendItem request) {
        long revision = nextRevision();
        var active = store.activeForSource(request.source().raw());
        if (active.isEmpty()) {
            InboxEntry entry = request.entry();
            List<InboxEntry> entries = entry == null ? List.of() : List.of(entry);
            String summary = entry == null ? request.summary() : entry.render();
            InboxItem created = new InboxItem(
                    "itm-" + UUID.randomUUID(), request.source().raw(), request.source().label(),
                    summary, entries, 1, revision, request.priority(),
                    ItemStatus.AVAILABLE, request.availableAt(), Instant.now(), Instant.now(), 1);
            store.append(created);
            afterAppend(created);
            return created;
        }

        InboxItem item = active.orElseThrow();
        List<InboxEntry> entries = new ArrayList<>(item.entries());
        String summary;
        if (request.entry() != null) {
            entries.add(request.entry());
            entries = trim(entries);
            summary = render(entries);
        } else {
            summary = item.summary().isBlank() ? request.summary()
                    : item.summary() + "\n" + request.summary();
        }
        InboxItem updated = item.withPanelState(
                summary, entries, item.totalMessages() + 1, revision,
                Math.max(item.priority(), request.priority()), Instant.now(),
                item.revision() + 1);
        store.update(updated);
        afterAppend(updated);
        return updated;
    }

    public synchronized InboxPanel panel() {
        InboxRuntime current = runtime.get();
        boolean includeOmitted = current.includeOmittedCount();
        List<InboxItem> eligible = visibleItems();
        int maxItems = Math.max(1, current.maxItemsPerPanel());
        List<PanelItem> items = new ArrayList<>();
        for (int index = 0; index < Math.min(maxItems, eligible.size()); index++) {
            items.add(panelItem(eligible.get(index), current.maxMessagesPerItem(),
                    includeOmitted, index + 1));
        }
        return new InboxPanel(inboxId, panelRevision, pushRevision, ackedRevision, Instant.now(),
                items, eligible.size(),
                includeOmitted ? Math.max(0, eligible.size() - items.size()) : 0, includeOmitted);
    }

    /** Unacked items in panel order (priority desc, then most recently active). */
    private List<InboxItem> visibleItems() {
        return store.items().stream()
                .filter(item -> item.status() != ItemStatus.ACKED)
                .sorted(Comparator.comparingInt(InboxItem::priority).reversed()
                        .thenComparing(InboxItem::updatedAt, Comparator.reverseOrder()))
                .toList();
    }

    public synchronized List<InboxItem> items() {
        return List.copyOf(visibleItems());
    }

    public synchronized InboxItem item(String itemId) {
        return store.item(itemId)
                .orElseThrow(() -> new IllegalArgumentException("ITEM_NOT_FOUND"));
    }

    /** Acks every visible item at or below the revision the caller actually saw. */
    public synchronized InboxAck ackPanel(long requestedRevision) {
        long target = Math.min(Math.max(0L, requestedRevision), panelRevision);
        if (target <= ackedRevision) {
            return new InboxAck(ackedRevision, status().unread(), panelRevision);
        }
        for (InboxItem item : store.items()) {
            if (item.status() != ItemStatus.ACKED && item.eventRevision() <= target) {
                store.update(item.withStatus(ItemStatus.ACKED));
                pushedItems.remove(item.id());
                pendingSince.remove(item.id());
            }
        }
        ackedRevision = target;
        store.saveAckedRevision(target);
        long next = nextRevision();
        notifyAll();
        return new InboxAck(target, status().unread(), next);
    }

    public synchronized InboxAck ackPanel() {
        return ackPanel(panelRevision);
    }

    /**
     * Acks exactly the given items. Per-item ack is the authoritative path; the global cursor is
     * advanced to the newest revision covered by the acked items purely for observability.
     */
    public synchronized InboxAck ackItems(Set<String> itemIds, Long expectedRevision) {
        if (itemIds == null || itemIds.isEmpty()) {
            throw new IllegalArgumentException("ITEM_IDS_EMPTY");
        }
        checkRevision(expectedRevision);
        long covered = ackedRevision;
        int acked = 0;
        for (InboxItem item : store.items()) {
            if (!itemIds.contains(item.id()) || item.status() == ItemStatus.ACKED) {
                continue;
            }
            store.update(item.withStatus(ItemStatus.ACKED));
            pushedItems.remove(item.id());
            pendingSince.remove(item.id());
            covered = Math.max(covered, item.eventRevision());
            acked++;
        }
        if (acked == 0) {
            throw new IllegalArgumentException("ITEM_NOT_FOUND: " + itemIds);
        }
        ackedRevision = Math.max(ackedRevision, Math.min(covered, panelRevision));
        store.saveAckedRevision(ackedRevision);
        nextRevision();
        notifyAll();
        return new InboxAck(ackedRevision, status().unread(), panelRevision);
    }

    /** Acks panel items by their 1-based display index (optionally a contiguous range). */
    public synchronized InboxAck ackIndices(List<Integer> indices, Long expectedRevision) {
        checkRevision(expectedRevision);
        List<InboxItem> visible = visibleItems();
        Set<String> ids = new LinkedHashSet<>();
        for (Integer index : indices) {
            if (index == null || index < 1 || index > visible.size()) {
                throw new IllegalArgumentException("INDEX_OUT_OF_RANGE: " + index);
            }
            ids.add(visible.get(index - 1).id());
        }
        return ackItems(ids, null);
    }

    private void checkRevision(Long expectedRevision) {
        if (expectedRevision != null && expectedRevision != panelRevision) {
            throw new IllegalArgumentException(
                    "REVISION_CONFLICT: expected=" + expectedRevision + " current=" + panelRevision);
        }
    }

    /** Waits for a panel revision newer than {@code lastRevision}; null on timeout. */
    public synchronized InboxPanel awaitPanel(long lastRevision, Duration timeout) {
        long deadline = System.nanoTime() + Math.max(0, timeout.toNanos());
        while (!closed && panelRevision <= lastRevision) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return null;
            }
            try {
                wait(Math.max(1, Duration.ofNanos(remaining).toMillis()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return closed ? null : panel();
    }

    /**
     * Blocks until the pending batch is due, then returns the panel that must be pushed.
     *
     * <p>Timing rule: an item scored at or above {@code policy.importantThreshold} is pushed after
     * the short delay, everything else waits for the batch delay; the batch is anchored on the
     * <b>earliest</b> pending item (a later arrival never postpones it), and when any immediate item
     * becomes due the whole pending set flushes together. <b>After a push round the clock resets</b>
     * — items that changed later are re-sorted and re-scheduled from that flush. Items already
     * handed to a push are not pushed again until they change or are acked.</p>
     *
     * <p>Returns {@code null} on timeout (heartbeat/no change).</p>
     */
    public synchronized InboxPanel awaitPush(long lastPushRevision, Duration timeout) {
        long deadline = System.nanoTime() + Math.max(0, timeout.toNanos());
        while (!closed) {
            // A push that another thread performed while we were waiting is still ours to deliver:
            // delivery is a cursor (pushRevision), not a race between waiters.
            if (pushRevision > lastPushRevision) {
                return panel();
            }
            Instant due = nextDue();
            if (due == null) {
                long remaining = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
                if (remaining <= 0) {
                    return null;
                }
                try {
                    wait(Math.max(1, remaining));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                continue;
            }
            long remaining = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
            long untilDue = Duration.between(Instant.now(), due).toMillis();
            if (untilDue > 0) {
                if (remaining <= 0) {
                    return null;
                }
                try {
                    wait(Math.max(1, Math.min(remaining, untilDue + 1)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                continue;
            }
            flushPending();
            return panel();
        }
        return null;
    }

    /** Earliest due instant across items that still owe a push; null when nothing is pending. */
    private Instant nextDue() {
        InboxRuntime current = runtime.get();
        Instant earliest = null;
        for (InboxItem item : store.items()) {
            if (item.status() == ItemStatus.ACKED) {
                pushedItems.remove(item.id());
                continue;
            }
            if (item.priority() < current.pushMinPriority()) {
                continue;
            }
            Integer pushed = pushedItems.get(item.id());
            if (pushed != null && pushed == item.revision()) {
                continue;
            }
            // Anchor = when this item first owed a push (never its latest message), but never
            // earlier than the last push round.
            Instant firstPending = pendingSince.computeIfAbsent(item.id(),
                    id -> item.updatedAt().isAfter(lastFlushAt) ? item.updatedAt() : lastFlushAt);
            Instant anchor = firstPending.isAfter(lastFlushAt) ? firstPending : lastFlushAt;
            Duration delay = item.priority() >= policy.importantThreshold()
                    ? policy.importantDelay() : policy.normalDelay();
            Instant due = anchor.plus(delay);
            if (earliest == null || due.isBefore(earliest)) {
                earliest = due;
            }
        }
        return earliest;
    }

    /**
     * Hands every pending item to one push and resets the batch clock. Called when the earliest due
     * item fires, which is why a low-priority item that arrived one second ago still rides along
     * with an immediate one.
     */
    private void flushPending() {
        InboxRuntime current = runtime.get();
        for (InboxItem item : store.items()) {
            if (item.status() == ItemStatus.ACKED || item.priority() < current.pushMinPriority()) {
                continue;
            }
            pushedItems.put(item.id(), item.revision());
            pendingSince.remove(item.id());
        }
        lastFlushAt = Instant.now();
        pushRevision++;
        notifyAll();
    }

    public synchronized long pushRevision() {
        return pushRevision;
    }

    public synchronized InboxStatus status() {
        int available = 0;
        int acked = 0;
        int remainingImportant = 0;
        int unread = 0;
        for (InboxItem item : store.items()) {
            if (item.status() == ItemStatus.ACKED) {
                acked++;
                continue;
            }
            available++;
            unread++;
            if (item.priority() >= policy.importantThreshold()) {
                remainingImportant++;
            }
        }
        int pending = 0;
        for (InboxItem item : store.items()) {
            Integer pushed = pushedItems.get(item.id());
            if (item.status() != ItemStatus.ACKED
                    && (pushed == null || pushed != item.revision())) {
                pending++;
            }
        }
        Instant next = nextDue();
        return new InboxStatus(available, acked, remainingImportant, pending,
                next == null ? "" : next.toString(), panelRevision, ackedRevision, unread);
    }

    public synchronized List<ItemSummary> list() {
        return store.items().stream()
                .filter(item -> item.status() != ItemStatus.ACKED)
                .map(item -> new ItemSummary(item.id(), item.source(), item.summary(),
                        item.priority(), item.status(), item.availableAt(), item.revision()))
                .toList();
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    private void afterAppend(InboxItem item) {
        // The batch clock starts at the FIRST message that owes a push; merging more messages into
        // the same window must not move it (operator ruling: 不重置时间). The entry is dropped when
        // the item is flushed or acked, so a later message starts a fresh clock.
        pendingSince.putIfAbsent(item.id(), Instant.now());
        pendingSince.keySet().removeIf(id -> store.item(id).isEmpty());
        appendsSincePurge++;
        if (appendsSincePurge >= PURGE_EVERY_APPENDS) {
            purgeNow();
        }
        notifyAll();
    }

    private void purgeNow() {
        appendsSincePurge = 0;
        try {
            InboxRuntime current = runtime.get();
            Instant cutoff = Instant.now().minus(Duration.ofDays(current.retentionMaxAgeDays()));
            store.purge(cutoff, current.retentionMaxItems());
        } catch (RuntimeException failure) {
            // Retention is housekeeping: never let it break an append.
            org.slf4j.LoggerFactory.getLogger(InboxService.class)
                    .warn("event=inbox_purge_failed errorType={}", failure.getClass().getSimpleName());
        }
    }

    private long nextRevision() {
        panelRevision++;
        store.savePanelRevision(panelRevision);
        return panelRevision;
    }

    private List<InboxEntry> trim(List<InboxEntry> entries) {
        int max = Math.max(1, runtime.get().maxMessagesPerItem());
        if (entries.size() <= max) {
            return List.copyOf(entries);
        }
        return List.copyOf(entries.subList(entries.size() - max, entries.size()));
    }

    private static String render(List<InboxEntry> entries) {
        StringBuilder text = new StringBuilder();
        for (InboxEntry entry : entries) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(entry.render());
        }
        return text.toString();
    }

    private static PanelItem panelItem(InboxItem item, int maxMessages, boolean includeOmitted,
                                       int index) {
        List<InboxEntry> entries = item.entries();
        if (entries.isEmpty() && !item.summary().isBlank()) {
            entries = List.of(new InboxEntry(0, item.updatedAt(), "", item.summary(), List.of()));
        }
        int max = Math.max(1, maxMessages);
        if (entries.size() > max) {
            entries = List.copyOf(entries.subList(entries.size() - max, entries.size()));
        }
        int omitted = includeOmitted ? Math.max(0, item.totalMessages() - entries.size()) : 0;
        return new PanelItem(index, item.id(), item.source(), item.sourceLabel(), item.priority(),
                entries, item.totalMessages(), omitted, item.status(), item.updatedAt());
    }
}
