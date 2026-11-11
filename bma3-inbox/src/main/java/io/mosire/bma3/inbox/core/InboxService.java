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
        consolidateSources();
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
                ItemStatus.AVAILABLE, request.availableAt(), Instant.now(), Instant.now(), 1, 0L, false);
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
            // 同一来源只保留一条：如果有"已推送但还没被确认"的旧条目，就把新消息续到它上面
            // （解冻 + 续标），而不是在面板里再开一条同来源的。这样 Agent 看到的永远是
            // "这个来源到目前为止的完整内容"，且条目变了会被重新推送。
            var reopenable = store.reopenableForSource(request.source().raw());
            if (reopenable.isPresent()) {
                return mergeInto(reopenable.orElseThrow(), request, revision, true);
            }
            InboxEntry entry = request.entry();
            List<InboxEntry> entries = entry == null ? List.of() : List.of(entry);
            String summary = entry == null ? request.summary() : entry.render();
            InboxItem created = new InboxItem(
                    "itm-" + UUID.randomUUID(), request.source().raw(), request.source().label(),
                    summary, entries, 1, revision, request.priority(),
                    ItemStatus.AVAILABLE, request.availableAt(), Instant.now(), Instant.now(), 1, 0L, false);
            store.append(created);
            afterAppend(created);
            return created;
        }

        return mergeInto(active.orElseThrow(), request, revision, false);
    }

    /**
     * Appends one message to an existing item.
     *
     * @param continuation true when the target had already been delivered: the item is reopened
     *                     (unfrozen) and the new entry carries the {@value #CONTINUATION_MARKER}
     *                     marker, so a reader can tell "this item grew after you saw it".
     */
    private InboxItem mergeInto(InboxItem target, AppendItem request, long revision,
                                boolean continuation) {
        InboxItem item = continuation ? target.withFrozen(0L).withContinued(true) : target;
        InboxEntry incoming = request.entry();
        if (continuation && incoming != null) {
            incoming = incoming.withMarker(CONTINUATION_MARKER);
        }
        List<InboxEntry> entries = new ArrayList<>(item.entries());
        String summary;
        if (incoming != null) {
            entries.add(incoming);
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

    /** Marker appended to a message that arrived after its item had already been delivered. */
    public static final String CONTINUATION_MARKER = "续";

    /**
     * Unified consolidation: one unacked item per source.
     *
     * <p>Live traffic already continues a source into its delivered item ({@link #mergeInto}), but
     * data written before that rule — or by an older build — can still hold several unacked items
     * for one source. This folds them into the newest one: older entries first (chronological), the
     * survivor's own entries last, the join point marked {@value #CONTINUATION_MARKER}, and the
     * absorbed rows dropped (the message board remains the fact source).</p>
     *
     * @return number of items absorbed
     */
    public synchronized int consolidateSources() {
        Map<String, List<InboxItem>> bySource = new HashMap<>();
        for (InboxItem item : visibleItems()) {
            bySource.computeIfAbsent(item.source(), key -> new ArrayList<>()).add(item);
        }
        int absorbed = 0;
        for (List<InboxItem> group : bySource.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(Comparator.comparing(InboxItem::createdAt));
            InboxItem survivor = group.remove(group.size() - 1);
            int max = Math.max(1, runtime.get().maxMessagesPerItem());
            List<InboxEntry> entries = new ArrayList<>();
            int total = 0;
            for (InboxItem older : group) {
                entries.addAll(older.entries());
                total += Math.max(1, older.totalMessages());
            }
            List<InboxEntry> own = new ArrayList<>(survivor.entries());
            if (!own.isEmpty() && !entries.isEmpty()) {
                own.set(0, own.getFirst().withMarker(CONTINUATION_MARKER));
            }
            entries.addAll(own);
            total += Math.max(0, survivor.totalMessages());
            if (entries.size() > max) {
                entries = new ArrayList<>(entries.subList(entries.size() - max, entries.size()));
            }
            InboxItem merged = survivor.withPanelState(render(entries), List.copyOf(entries), total,
                    nextRevision(), survivor.priority(), Instant.now(), survivor.revision() + 1)
                    .withContinued(true);
            store.update(merged);
            List<String> absorbedIds = group.stream().map(InboxItem::id).toList();
            store.delete(absorbedIds);
            absorbedIds.forEach(id -> {
                pushedItems.remove(id);
                pendingSince.remove(id);
            });
            absorbed += absorbedIds.size();
        }
        return absorbed;
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
    /**
     * Acks what a client <b>was actually shown</b>, up to the revision it reported.
     *
     * <p>This is deliberately narrower than "every item with {@code eventRevision <= revision}":
     * a panel only renders the top {@code maxItemsPerPanel} items, so a revision-based ack would
     * silently acknowledge items that never made it into the window. Only items currently in the
     * delivered state ({@code pushedItems[id] == revision}) are eligible, which after a flush is
     * exactly the panel that was handed out.</p>
     */
    public synchronized InboxAck ackPanel(long requestedRevision) {
        long target = Math.min(Math.max(0L, requestedRevision), panelRevision);
        if (target <= ackedRevision) {
            return new InboxAck(ackedRevision, status().unread(), panelRevision);
        }
        int acked = 0;
        for (InboxItem item : store.items()) {
            if (item.status() == ItemStatus.ACKED) {
                continue;
            }
            Integer pushed = pushedItems.get(item.id());
            if (pushed == null || pushed != item.revision()) {
                // Never handed to a client (window overflow), or changed after the push.
                continue;
            }
            if (item.eventRevision() > target) {
                continue;
            }
            store.update(item.withStatus(ItemStatus.ACKED));
            pushedItems.remove(item.id());
            pendingSince.remove(item.id());
            acked++;
        }
        ackedRevision = Math.max(ackedRevision, target);
        store.saveAckedRevision(ackedRevision);
        if (acked > 0) {
            nextRevision();
        }
        notifyAll();
        return new InboxAck(ackedRevision, status().unread(), panelRevision);
    }

    /** Explicit "ack everything visible" (tool path); requires no push state. */
    public synchronized InboxAck ackPanel() {
        int acked = 0;
        for (InboxItem item : store.items()) {
            if (item.status() == ItemStatus.ACKED) {
                continue;
            }
            store.update(item.withStatus(ItemStatus.ACKED));
            pushedItems.remove(item.id());
            pendingSince.remove(item.id());
            acked++;
        }
        ackedRevision = panelRevision;
        store.saveAckedRevision(ackedRevision);
        if (acked > 0) {
            nextRevision();
        }
        notifyAll();
        return new InboxAck(ackedRevision, status().unread(), panelRevision);
    }

    /**
     * Acks exactly the given items. Per-item ack is the authoritative path; the global cursor is
     * advanced to the newest revision covered by the acked items purely for observability.
     */
    public synchronized InboxAck ackItems(Set<String> itemIds, Long expectedRevision) {
        if (itemIds == null || itemIds.isEmpty()) {
            throw new IllegalArgumentException("ITEM_IDS_EMPTY");
        }
        checkItemRevision(itemIds, expectedRevision);
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

    /**
     * Strict check for the index path: a 1-based index is resolved against the <b>current</b> panel,
     * so a stale revision could acknowledge the wrong item.
     */
    private void checkRevision(Long expectedRevision) {
        if (expectedRevision != null && expectedRevision != panelRevision) {
            throw new IllegalArgumentException(
                    "REVISION_CONFLICT: expected=" + expectedRevision + " current=" + panelRevision);
        }
    }

    /**
     * Item-scoped check for the itemId path.
     *
     * <p>An itemId names exactly what the client was shown, so unrelated traffic must not reject the
     * ack: while an agent is being woken up the inbox routinely advances (every append and every ack
     * bumps {@code panelRevision}), and requiring equality made the wake-up ack fail with 409 almost
     * every time on a busy account — the acknowledged items then stayed pending and were injected
     * again. Only an item that <b>itself</b> changed after the client's revision is a real conflict,
     * because acking it would drop content the client never saw.</p>
     */
    private void checkItemRevision(Set<String> itemIds, Long expectedRevision) {
        if (expectedRevision == null) {
            return;
        }
        if (expectedRevision > panelRevision) {
            throw new IllegalArgumentException(
                    "REVISION_CONFLICT: expected=" + expectedRevision + " current=" + panelRevision);
        }
        for (InboxItem item : store.items()) {
            if (itemIds.contains(item.id()) && item.eventRevision() > expectedRevision) {
                throw new IllegalArgumentException("REVISION_CONFLICT: expected=" + expectedRevision
                        + " item=" + item.id() + " itemRevision=" + item.eventRevision());
            }
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

    /**
     * Earliest due instant across items that can actually be delivered now; null when nothing is.
     *
     * <p>Delivery is bounded by the panel window: only the top {@code maxItemsPerPanel} visible
     * items can appear in one push. An item ranked below the window is <b>not</b> "due" yet — it
     * has to wait for the client to ack something and free a slot. Without that rule a pending item
     * that can never fit would keep the flush loop spinning forever, re-sending a panel that does
     * not contain it.</p>
     */
    private Instant nextDue() {
        InboxRuntime current = runtime.get();
        List<InboxItem> visible = visibleItems();
        int capacity = Math.max(1, current.maxItemsPerPanel());
        java.util.Set<String> window = new java.util.HashSet<>();
        for (int index = 0; index < Math.min(capacity, visible.size()); index++) {
            window.add(visible.get(index).id());
        }
        Instant earliest = null;
        for (InboxItem item : visible) {
            if (item.priority() < current.pushMinPriority()) {
                continue;
            }
            Integer pushed = pushedItems.get(item.id());
            if (pushed != null && pushed == item.revision()) {
                continue;                       // already handed out, waiting for ack
            }
            if (!window.contains(item.id())) {
                continue;                       // no slot until something is acked
            }
            Instant anchor = pendingSince.computeIfAbsent(item.id(), id -> item.updatedAt());
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
        List<InboxItem> visible = visibleItems();
        int capacity = Math.max(1, current.maxItemsPerPanel());
        // Only the items that this panel actually renders are marked delivered. Anything ranked
        // below the window keeps its pending state and is pushed in a later round, once acks free
        // a slot — never silently marked delivered.
        List<String> frozen = new ArrayList<>();
        for (int index = 0; index < Math.min(capacity, visible.size()); index++) {
            InboxItem item = visible.get(index);
            if (item.priority() < current.pushMinPriority()) {
                continue;
            }
            pushedItems.put(item.id(), item.revision());
            pendingSince.remove(item.id());
            frozen.add(item.id());
        }
        pushRevision++;
        // 推送即冻结：这些条目从此不再吸同来源的新消息，后续消息另开新条目。
        // 这样"被 ack 的内容"永远等于"被推送的内容"，ack 不会因条目自身变化而冲突。
        if (!frozen.isEmpty()) {
            store.freeze(frozen, pushRevision);
        }
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
                        item.priority(), item.status(), item.availableAt(), item.revision(),
                        item.continued()))
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
        consolidateSources();
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
                entries, item.totalMessages(), omitted, item.status(), item.updatedAt(),
                item.continued());
    }
}
