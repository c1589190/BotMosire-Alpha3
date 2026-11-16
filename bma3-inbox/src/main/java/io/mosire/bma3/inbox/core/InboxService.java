package io.mosire.bma3.inbox.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Single-inbox aggregate panel. It owns display windows and revisions, not message fact.
 *
 * <p>0.0.4 delivery model (D-043..D-045):</p>
 * <ul>
 *   <li><b>One open item per source</b>; once an item is pushed it is frozen and a later message
 *       opens a new item. Two items of the same source are legal and expected.</li>
 *   <li><b>The push revision is durable</b>: {@code pushRevision} and {@code frozen_revision} are
 *       persisted, so a Java restart does not replay items that were already handed out.</li>
 *   <li><b>Ack is idempotent</b>: acking an already-acked item is a no-op 200, not an error.</li>
 *   <li><b>Dismiss is the "clear without reading" path</b>: filter-based bulk ack that never
 *       deletes rows and protects important items by default.</li>
 * </ul>
 */
public final class InboxService {

    private static final int PURGE_EVERY_APPENDS = 100;
    private static final int MAX_RECENT_ERRORS = 20;
    private static final int MAX_DISMISS_PREVIEW = 50;

    private final InboxStore store;
    private final Supplier<DeliveryPolicy> policy;
    private final String inboxId;
    private final Supplier<InboxRuntime> runtime;
    private final String processId = "inbox-" + UUID.randomUUID().toString().substring(0, 8);
    private final long startedAtEpochMillis = System.currentTimeMillis();

    private long panelRevision;
    private long pushRevision;
    private long ackedRevision;
    private boolean closed;
    private int appendsSincePurge;

    /**
     * itemId -> when this item last became due. A same-source append <b>resets</b> it (D-044);
     * different items still race on the earliest deadline.
     */
    private final Map<String, Instant> pendingSince = new HashMap<>();
    /** itemId -> first time this item owed a push; used by the first-push hard cap. */
    private final Map<String, Instant> pendingFirstSeen = new HashMap<>();

    private final AtomicLong ackConflicts;
    private final AtomicLong pushes;
    private final AtomicLong repushes;
    private final AtomicLong dismissed;
    private final Deque<String> recentErrors = new ArrayDeque<>();

    private volatile String mcpStartId = "";
    private PluginHeartbeat lastHeartbeat;
    private ControlCommand pendingControl;
    private long controlSequence;

    public InboxService(InboxStore store, DeliveryPolicy policy) {
        this(store, () -> policy, "bma3", InboxRuntime::defaults);
    }

    public InboxService(InboxStore store, DeliveryPolicy policy, String inboxId) {
        this(store, () -> policy, inboxId, InboxRuntime::defaults);
    }

    public InboxService(InboxStore store, DeliveryPolicy policy, String inboxId,
                        Supplier<InboxRuntime> runtime) {
        this(store, () -> policy, inboxId, runtime);
    }

    public InboxService(InboxStore store, Supplier<DeliveryPolicy> policy, String inboxId,
                        Supplier<InboxRuntime> runtime) {
        this.store = store;
        this.policy = policy == null ? DeliveryPolicy::defaults : policy;
        this.inboxId = inboxId == null || inboxId.isBlank() ? "bma3" : inboxId;
        this.runtime = runtime == null ? InboxRuntime::defaults : runtime;
        this.panelRevision = store.panelRevision();
        this.ackedRevision = store.ackedRevision();
        this.pushRevision = store.pushRevision();
        this.ackConflicts = metric("metric_ack_conflicts");
        this.pushes = metric("metric_pushes");
        this.repushes = metric("metric_repushes");
        this.dismissed = metric("metric_dismissed");
        loadErrors();
        purgeNow();
    }

    private DeliveryPolicy policy() {
        DeliveryPolicy current = policy.get();
        return current == null ? DeliveryPolicy.defaults() : current;
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
                ItemStatus.AVAILABLE, request.availableAt(), Instant.now(), Instant.now(), 1,
                0L, false);
        store.append(item);
        afterAppend(item);
        return item;
    }

    /**
     * Append one message to the source's open window, or start a new item when the previous one was
     * already pushed. A pushed item is never reopened: the new message becomes a second item of the
     * same source (D-043), marked {@code continued} so the panel can say the source is still active.
     */
    public synchronized InboxItem appendOrMerge(AppendItem request) {
        long revision = nextRevision();
        var active = store.activeForSource(request.source().raw());
        if (active.isEmpty()) {
            boolean continued = store.hasUnackedFrozenForSource(request.source().raw());
            InboxEntry entry = request.entry();
            List<InboxEntry> entries = entry == null ? List.of() : List.of(entry);
            String summary = entry == null ? request.summary() : entry.render();
            InboxItem created = new InboxItem(
                    "itm-" + UUID.randomUUID(), request.source().raw(), request.source().label(),
                    summary, entries, 1, revision, request.priority(),
                    ItemStatus.AVAILABLE, request.availableAt(), Instant.now(), Instant.now(), 1,
                    0L, continued);
            store.append(created);
            afterAppend(created);
            return created;
        }
        return mergeInto(active.orElseThrow(), request, revision);
    }

    /** Appends one message to the source's open (not yet pushed) item. */
    private InboxItem mergeInto(InboxItem target, AppendItem request, long revision) {
        InboxEntry incoming = request.entry();
        List<InboxEntry> entries = new ArrayList<>(target.entries());
        String summary;
        if (incoming != null) {
            entries.add(incoming);
            entries = trim(entries);
            summary = renderCapped(entries);
        } else {
            summary = target.summary().isBlank() ? request.summary()
                    : target.summary() + "\n" + request.summary();
        }
        InboxItem updated = target.withPanelState(
                summary, entries, target.totalMessages() + 1, revision,
                Math.max(target.priority(), request.priority()), Instant.now(),
                target.revision() + 1);
        store.update(updated);
        afterAppend(updated);
        return updated;
    }

    public synchronized InboxPanel panel() {
        InboxRuntime current = runtime.get();
        boolean includeOmitted = current.includeOmittedCount();
        List<InboxItem> eligible = visibleItems();
        int maxItems = Math.max(1, current.maxItemsPerPanel());
        boolean summarized = switch (current.displayMode()) {
            case "summary" -> true;
            case "full" -> false;
            default -> eligible.size() > current.summaryAboveItems();
        };
        int maxEntries = summarized ? 1 : current.maxMessagesPerItem();
        int maxChars = summarized ? current.summaryMaxCharsPerItem()
                : current.maxCharsPerItem();
        Instant panelNow = Instant.now();
        List<PanelItem> items = new ArrayList<>();
        for (int index = 0; index < Math.min(maxItems, eligible.size()); index++) {
            InboxItem item = eligible.get(index);
            items.add(panelItem(item, maxEntries, maxChars, includeOmitted, index + 1,
                    heldForQuiet(item, panelNow)));
        }
        return new InboxPanel(inboxId, panelRevision, pushRevision, ackedRevision, Instant.now(),
                items, eligible.size(),
                includeOmitted ? Math.max(0, eligible.size() - items.size()) : 0, includeOmitted,
                store.identity());
    }

    /**
     * Panel shape for a push/SSE frame: only items frozen by the current push revision.
     * A full {@link #panel()} may legitimately contain items still held by a source quiet window
     * (frozenRevision=0); those must not poison a push batch's exact frozen proof.
     */
    public synchronized InboxPanel pushPanel() {
        InboxPanel full = panel();
        if (pushRevision <= 0) {
            return full;
        }
        List<PanelItem> items = full.items().stream()
                .filter(item -> item.frozenRevision() == pushRevision)
                .toList();
        int omitted = full.includeOmittedCount()
                ? Math.max(0, full.totalItems() - items.size()) : 0;
        return new InboxPanel(full.inboxId(), full.revision(), full.pushRevision(),
                full.ackedRevision(), full.generatedAt(), items, full.totalItems(), omitted,
                full.includeOmittedCount(), full.inboxIdentity());
    }

    /** Durable identity of the inbox database; stable across ordinary restarts. */
    public String inboxIdentity() {
        return store.identity();
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

    /**
     * Acks what a client <b>was actually shown</b>, up to the revision it reported. Only frozen
     * (delivered) items are eligible, so a window-overflow row is never silently acknowledged.
     */
    public synchronized InboxAck ackPanel(long requestedRevision) {
        long target = Math.min(Math.max(0L, requestedRevision), panelRevision);
        if (target <= ackedRevision) {
            return new InboxAck(ackedRevision, status().unread(), panelRevision, List.of(),
                    List.of(), List.of(), store.identity(), true);
        }
        List<InboxItem> toAck = new ArrayList<>();
        long covered = ackedRevision;
        for (InboxItem item : store.items()) {
            if (item.status() == ItemStatus.ACKED || !item.frozen()) {
                continue;
            }
            if (item.eventRevision() > target) {
                continue;
            }
            toAck.add(item);
            covered = Math.max(covered, item.eventRevision());
        }
        List<String> ackedIds = toAck.isEmpty() ? List.of() : commitAckBatch(toAck, covered);
        notifyAll();
        return new InboxAck(ackedRevision, status().unread(), panelRevision, ackedIds, List.of(),
                List.of(), store.identity(), true);
    }

    /** Explicit "ack everything visible" (tool path); requires no push state. */
    public synchronized InboxAck ackPanel() {
        List<InboxItem> toAck = new ArrayList<>();
        long covered = ackedRevision;
        for (InboxItem item : store.items()) {
            if (item.status() == ItemStatus.ACKED) {
                continue;
            }
            toAck.add(item);
            covered = Math.max(covered, item.eventRevision());
        }
        List<String> ackedIds = toAck.isEmpty() ? List.of() : commitAckBatch(toAck, covered);
        notifyAll();
        return new InboxAck(ackedRevision, status().unread(), panelRevision, ackedIds, List.of(),
                List.of(), store.identity(), true);
    }

    /**
     * Acks exactly the given items, tolerating ids that no longer exist (legacy manual path).
     *
     * <p>Per-item ack is the authoritative path; the global cursor is advanced to the newest
     * revision covered by the acked items purely for observability.</p>
     *
     * <p>Idempotent (D-043): asking again for items that are already ACKED is a successful no-op.
     * The result always reports per-item proof ({@code ackedItemIds}, {@code alreadyAckedItemIds},
     * {@code missingItemIds}); a request that named an unknown id returns {@code complete=false}
     * instead of pretending the batch succeeded. Only ids that do not exist at all fail.</p>
     */
    public synchronized InboxAck ackItems(Set<String> itemIds, Long expectedRevision) {
        if (itemIds == null || itemIds.isEmpty()) {
            throw new IllegalArgumentException("ITEM_IDS_EMPTY");
        }
        checkItemRevision(itemIds, expectedRevision);
        Map<String, InboxItem> requested = new LinkedHashMap<>();
        for (InboxItem item : store.items()) {
            if (itemIds.contains(item.id())) {
                requested.put(item.id(), item);
            }
        }
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("ITEM_NOT_FOUND: " + itemIds);
        }
        List<String> missing = itemIds.stream()
                .filter(id -> !requested.containsKey(id)).toList();
        List<InboxItem> toAck = new ArrayList<>();
        List<String> alreadyAcked = new ArrayList<>();
        long covered = ackedRevision;
        for (String id : itemIds) {
            InboxItem item = requested.get(id);
            if (item == null) {
                continue;
            }
            if (item.status() == ItemStatus.ACKED) {
                alreadyAcked.add(id);
                continue;
            }
            toAck.add(item);
            covered = Math.max(covered, item.eventRevision());
        }
        if (toAck.isEmpty()) {
            // Idempotent re-ack: everything requested was already acknowledged.
            return new InboxAck(ackedRevision, status().unread(), panelRevision, List.of(),
                    alreadyAcked, missing, store.identity(), missing.isEmpty());
        }
        List<String> ackedIds = commitAckBatch(toAck, covered);
        return new InboxAck(ackedRevision, status().unread(), panelRevision, ackedIds,
                alreadyAcked, missing, store.identity(), missing.isEmpty());
    }

    /**
     * Identity-guarded ACK for the DSH plugin: the caller proves which database and which items it
     * actually saw.
     *
     * <p>Everything is validated before anything is written, in this one serialized operation:</p>
     * <ol>
     *   <li>the presented identity must be non-empty and equal to this database's durable
     *       identity, otherwise the whole request is rejected with a distinct reason;</li>
     *   <li>the caller's panel revision must not be newer than this inbox's (and no requested item
     *       may have changed after it);</li>
     *   <li>every requested id must still exist and must have been handed to a push
     *       ({@link InboxItem#frozen()}). Unknown, cleaned-up or never-pushed ids reject the whole
     *       request.</li>
     * </ol>
     *
     * <p>Item status changes and the ACK cursor / panel revision commit in one database
     * transaction; a write failure rolls all of them back, so a rejected or failed call changes
     * nothing. Repeating the call after a lost response is safe: already-ACKED items are reported
     * in {@code alreadyAckedItemIds} rather than confirmed twice.</p>
     */
    public synchronized InboxAck ackItemsGuarded(Set<String> itemIds, Long expectedRevision,
                                                 String requestedIdentity) {
        String currentIdentity = store.identity();
        String presented = requestedIdentity == null ? "" : requestedIdentity.trim();
        if (presented.isEmpty()) {
            bump(ackConflicts, "metric_ack_conflicts");
            recordError("INBOX_IDENTITY_REQUIRED");
            throw new InboxAckRejectedException(
                    InboxAckRejectedException.Reason.INBOX_IDENTITY_REQUIRED, List.of());
        }
        if (!presented.equals(currentIdentity)) {
            bump(ackConflicts, "metric_ack_conflicts");
            recordError("INBOX_IDENTITY_MISMATCH");
            throw new InboxAckRejectedException(
                    InboxAckRejectedException.Reason.INBOX_IDENTITY_MISMATCH, List.of());
        }
        // The original panel revision is part of the proof. A caller must not be able to skip the
        // version check by passing null, so a missing or negative revision rejects the whole batch.
        if (expectedRevision == null) {
            bump(ackConflicts, "metric_ack_conflicts");
            recordError("REVISION_REQUIRED");
            throw new InboxAckRejectedException(
                    InboxAckRejectedException.Reason.REVISION_REQUIRED, List.of());
        }
        if (expectedRevision < 0L) {
            bump(ackConflicts, "metric_ack_conflicts");
            recordError("REVISION_INVALID");
            throw new InboxAckRejectedException(
                    InboxAckRejectedException.Reason.REVISION_INVALID, List.of());
        }
        if (itemIds == null || itemIds.isEmpty()) {
            throw new IllegalArgumentException("ITEM_IDS_EMPTY");
        }
        checkItemRevision(itemIds, expectedRevision);

        Map<String, InboxItem> requested = new LinkedHashMap<>();
        for (InboxItem item : store.items()) {
            if (itemIds.contains(item.id())) {
                requested.put(item.id(), item);
            }
        }
        List<String> missing = new ArrayList<>();
        List<String> unpushed = new ArrayList<>();
        List<String> alreadyAcked = new ArrayList<>();
        List<InboxItem> toAck = new ArrayList<>();
        for (String id : itemIds) {
            InboxItem item = requested.get(id);
            if (item == null) {
                missing.add(id);
            } else if (item.status() == ItemStatus.ACKED) {
                alreadyAcked.add(id);
            } else if (!item.frozen()) {
                unpushed.add(id);
            } else {
                toAck.add(item);
            }
        }
        if (!missing.isEmpty()) {
            recordError("ITEM_MISSING count=" + missing.size());
            throw new InboxAckRejectedException(
                    InboxAckRejectedException.Reason.ITEM_MISSING, missing);
        }
        if (!unpushed.isEmpty()) {
            recordError("ITEM_NOT_PUSHED count=" + unpushed.size());
            throw new InboxAckRejectedException(
                    InboxAckRejectedException.Reason.ITEM_NOT_PUSHED, unpushed);
        }
        if (toAck.isEmpty()) {
            return new InboxAck(ackedRevision, status().unread(), panelRevision, List.of(),
                    alreadyAcked, List.of(), currentIdentity, true);
        }

        long covered = ackedRevision;
        for (InboxItem item : toAck) {
            covered = Math.max(covered, item.eventRevision());
        }
        List<String> ackedIds = commitAckBatch(toAck, covered);
        return new InboxAck(ackedRevision, status().unread(), panelRevision, ackedIds,
                alreadyAcked, List.of(), currentIdentity, true);
    }

    /**
     * Marks the batch ACKED and writes the ACK cursor plus the next panel revision in <b>one</b>
     * database transaction. In-memory state is updated only after the commit, so a database write
     * failure (thrown to the caller) leaves both the rows and the observable revisions untouched.
     */
    private List<String> commitAckBatch(List<InboxItem> toAck, long covered) {
        long nextAckedRevision = Math.max(ackedRevision, Math.min(covered, panelRevision));
        long nextPanelRevision = panelRevision + 1;
        try {
            store.inTransaction(() -> {
                for (InboxItem item : toAck) {
                    store.update(item.withStatus(ItemStatus.ACKED));
                }
                store.saveAckedRevision(nextAckedRevision);
                store.savePanelRevision(nextPanelRevision);
            });
        } catch (RuntimeException failure) {
            bump(ackConflicts, "metric_ack_conflicts");
            recordError("ACK_WRITE_FAILED " + failure.getClass().getSimpleName());
            throw failure;
        }
        for (InboxItem item : toAck) {
            pendingSince.remove(item.id());
            pendingFirstSeen.remove(item.id());
        }
        ackedRevision = nextAckedRevision;
        panelRevision = nextPanelRevision;
        notifyAll();
        return toAck.stream().map(InboxItem::id).toList();
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
     * Bulk "clear without reading" (D-042). Marks rows ACKED; never deletes message content.
     * Important items are skipped unless {@code allowImportant} is set.
     */
    public synchronized DismissResult dismiss(DismissRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("DISMISS_REQUEST_EMPTY");
        }
        InboxRuntime current = runtime.get();
        List<InboxItem> visible = visibleItems();
        List<InboxItem> scoped;
        if ("ids".equals(request.scope())) {
            scoped = visible.stream().filter(item -> request.itemIds().contains(item.id())).toList();
        } else if ("visible".equals(request.scope())) {
            int capacity = Math.min(current.maxItemsPerPanel(), visible.size());
            scoped = visible.subList(0, capacity);
        } else {
            scoped = visible;
        }
        Instant cutoff = request.olderThanSeconds() == null ? null
                : Instant.now().minusSeconds(request.olderThanSeconds());
        int importantThreshold = policy().importantThreshold();
        List<InboxItem> matched = new ArrayList<>();
        int matchedBeforeLimit = 0;
        int skippedImportant = 0;
        for (InboxItem item : scoped) {
            if (!request.sourcePrefix().isEmpty()
                    && !item.source().startsWith(request.sourcePrefix())) {
                continue;
            }
            if (request.priorityBelow() != null && item.priority() >= request.priorityBelow()) {
                continue;
            }
            if (request.priorityAtLeast() != null && item.priority() < request.priorityAtLeast()) {
                continue;
            }
            if (cutoff != null && !item.updatedAt().isBefore(cutoff)) {
                continue;
            }
            if (!request.allowImportant() && item.priority() >= importantThreshold) {
                skippedImportant++;
                continue;
            }
            matchedBeforeLimit++;
            if (matched.size() < request.limit()) {
                matched.add(item);
            }
        }
        boolean truncated = matchedBeforeLimit > matched.size();
        int dismissedCount = 0;
        if (!request.dryRun() && !matched.isEmpty()) {
            long covered = ackedRevision;
            for (InboxItem item : matched) {
                covered = Math.max(covered, item.eventRevision());
            }
            commitAckBatch(matched, covered);
            dismissedCount = matched.size();
            add(dismissed, "metric_dismissed", dismissedCount);
            org.slf4j.LoggerFactory.getLogger(InboxService.class).info(
                    "event=inbox_dismiss reason={} matched={} dismissed={} skippedImportant={} dryRun={}",
                    safeReason(request.reason()), matchedBeforeLimit, dismissedCount,
                    skippedImportant, request.dryRun());
        }
        List<DismissResult.DismissedItem> preview = matched.stream()
                .limit(MAX_DISMISS_PREVIEW)
                .map(item -> new DismissResult.DismissedItem(item.id(), item.source(),
                        item.sourceLabel(), item.priority(), item.totalMessages(), item.updatedAt(),
                        item.status().name(), item.continued()))
                .toList();
        return new DismissResult(matchedBeforeLimit, dismissedCount, skippedImportant,
                status().unread(), truncated, request.reason(), preview);
    }

    /** Watchdog maintenance entry: applies the configured auto-dismiss policy, if enabled. */
    public synchronized DismissResult autoDismiss() {
        InboxRuntime current = runtime.get();
        if (!current.autoDismissEnabled()) {
            return new DismissResult(0, 0, 0, status().unread(), false, "auto-disabled", List.of());
        }
        DismissRequest request = new DismissRequest("filter", Set.of(), "",
                current.autoDismissBelowPriority(), null,
                (long) current.autoDismissAfterSeconds(), current.autoDismissMaxPerRound(),
                false, false, "auto");
        return dismiss(request);
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
     * Item-scoped check for the itemId path. Unrelated traffic must not reject the ack; only an item
     * that itself changed after the client's revision is a real conflict.
     */
    private void checkItemRevision(Set<String> itemIds, Long expectedRevision) {
        if (expectedRevision == null) {
            return;
        }
        if (expectedRevision > panelRevision) {
            bump(ackConflicts, "metric_ack_conflicts");
            recordError("REVISION_CONFLICT expected>panel current=" + panelRevision);
            throw new IllegalArgumentException(
                    "REVISION_CONFLICT: expected=" + expectedRevision + " current=" + panelRevision);
        }
        for (InboxItem item : store.items()) {
            if (itemIds.contains(item.id()) && item.eventRevision() > expectedRevision) {
                bump(ackConflicts, "metric_ack_conflicts");
                recordError("REVISION_CONFLICT item=" + item.id());
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
     * <p>An item at/above {@code importantThreshold} waits the short delay, everything else the
     * batch delay; a same-source append resets its own deadline (D-044), while different items keep
     * racing on the earliest deadline. An open item that keeps being merged is still pushed once
     * {@code maxFirstPushDelaySeconds} has elapsed. Items already handed to a push are not pushed
     * again until they are acked or a new item is created.</p>
     */
    public synchronized InboxPanel awaitPush(long lastPushRevision, Duration timeout) {
        long deadline = System.nanoTime() + Math.max(0, timeout.toNanos());
        while (!closed) {
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
            flushPending(Instant.now());
            return pushPanel();
        }
        return null;
    }

    /**
     * Earliest due instant across items that can actually be delivered now; null when nothing is.
     * Delivery is bounded by the panel window; an item ranked below the window must wait for an ack
     * to free a slot.
     */
    private Instant nextDue() {
        InboxRuntime current = runtime.get();
        List<InboxItem> visible = visibleItems();
        int capacity = Math.max(1, current.maxItemsPerPanel());
        Set<String> window = new HashSet<>();
        for (int index = 0; index < Math.min(capacity, visible.size()); index++) {
            window.add(visible.get(index).id());
        }
        Instant earliest = null;
        for (InboxItem item : visible) {
            // D-044 follow-up: every item that survived dropBelow must eventually push. The old
            // pushMinPriority hard mute is deliberately gone; low-priority items simply ride the
            // normalDelay batch.
            if (item.frozen()) {
                continue;                       // already handed out, waiting for ack
            }
            if (!window.contains(item.id())) {
                continue;                       // no slot until something is acked
            }
            Instant due = dueFor(item);
            if (earliest == null || due.isBefore(earliest)) {
                earliest = due;
            }
        }
        return earliest;
    }

    /** The item's first observed pending time; kept only in memory, as before. */
    private Instant firstSeen(InboxItem item) {
        return pendingFirstSeen.computeIfAbsent(item.id(), id -> item.updatedAt());
    }

    /** The last append/reset time for this item; same-source appends move this forward. */
    private Instant lastActivity(InboxItem item) {
        return pendingSince.computeIfAbsent(item.id(), id -> item.updatedAt());
    }

    private InboxWaitPolicy.Resolution waitFor(InboxItem item) {
        return runtime.get().waitPolicy().resolve(item.source());
    }

    /**
     * Effective due instant for one item. The existing important/normal delay remains the base;
     * a configured source quiet window can only hold it later, never earlier, until the per-source
     * hard cap releases it.
     */
    private Instant dueFor(InboxItem item) {
        InboxRuntime current = runtime.get();
        Instant first = firstSeen(item);
        Instant anchor = lastActivity(item);
        Duration delay = item.priority() >= policy().importantThreshold()
                ? policy().importantDelay() : policy().normalDelay();
        Instant due = anchor.plus(delay);
        InboxWaitPolicy.Resolution wait = waitFor(item);
        if (wait.waiting()) {
            Instant quietDue = anchor.plusSeconds(wait.quietSeconds());
            if (quietDue.isAfter(due)) {
                due = quietDue;
            }
            Instant waitHard = first.plusSeconds(wait.maxWaitSeconds());
            if (waitHard.isBefore(due)) {
                due = waitHard;
            }
        }
        if (current.maxFirstPushDelaySeconds() > 0) {
            Instant hard = first.plusSeconds(current.maxFirstPushDelaySeconds());
            if (hard.isBefore(due)) {
                due = hard;
            }
        }
        return due;
    }

    /** True while this item is intentionally held by its source quiet window. */
    private boolean heldForQuiet(InboxItem item, Instant now) {
        if (item.frozen()) {
            return false;
        }
        InboxWaitPolicy.Resolution wait = waitFor(item);
        if (!wait.waiting()) {
            return false;
        }
        Instant quietDue = lastActivity(item).plusSeconds(wait.quietSeconds());
        Instant waitHard = firstSeen(item).plusSeconds(wait.maxWaitSeconds());
        return quietDue.isAfter(now) && waitHard.isAfter(now);
    }

    private Instant nextQuietAt() {
        Instant earliest = null;
        Instant now = Instant.now();
        for (InboxItem item : visibleItems()) {
            if (item.frozen() || !heldForQuiet(item, now)) {
                continue;
            }
            InboxWaitPolicy.Resolution wait = waitFor(item);
            Instant quietDue = lastActivity(item).plusSeconds(wait.quietSeconds());
            if (earliest == null || quietDue.isBefore(earliest)) {
                earliest = quietDue;
            }
        }
        return earliest;
    }

    /**
     * Hands every pending item in the current window to one push and advances the durable push
     * revision. Only items that this panel actually renders are frozen; window-overflow items keep
     * their pending state and are pushed in a later round. Items still held by a source quiet
     * window are deliberately excluded even when another item triggers the batch.
     */
    private void flushPending(Instant now) {
        InboxRuntime current = runtime.get();
        List<InboxItem> visible = visibleItems();
        int capacity = Math.max(1, current.maxItemsPerPanel());
        List<String> frozen = new ArrayList<>();
        int repushCount = 0;
        for (int index = 0; index < Math.min(capacity, visible.size()); index++) {
            InboxItem item = visible.get(index);
            if (item.frozen() || heldForQuiet(item, now)) {
                continue;
            }
            frozen.add(item.id());
            pendingSince.remove(item.id());
            pendingFirstSeen.remove(item.id());
            if (item.continued()) {
                repushCount++;
            }
        }
        if (frozen.isEmpty()) {
            return;
        }
        pushRevision++;
        store.savePushRevision(pushRevision);
        store.freeze(frozen, pushRevision);
        bump(pushes, "metric_pushes");
        add(repushes, "metric_repushes", repushCount);
        notifyAll();
    }

    public synchronized long pushRevision() {
        return pushRevision;
    }

    /**
     * Wait until all matching source items have been quiet for {@code quiet}, or until
     * {@code maxWait} expires. This is a read-only observer: it never ACKs or freezes an item.
     */
    public synchronized WaitQuietResult awaitQuiet(String source, String sourcePrefix,
                                                   Duration quiet, Duration maxWait) {
        String exact = source == null ? "" : source.trim();
        String prefix = sourcePrefix == null ? "" : sourcePrefix.trim();
        if (exact.isEmpty() && prefix.isEmpty()) {
            throw new IllegalArgumentException("SOURCE_REQUIRED");
        }
        String matchedBy = exact.isEmpty() ? "sourcePrefix" : "source";
        String selector = exact.isEmpty() ? prefix : exact;
        Duration quietDuration = quiet == null ? Duration.ofSeconds(2) : quiet;
        Duration maxDuration = maxWait == null ? Duration.ofSeconds(20) : maxWait;
        if (quietDuration.isNegative() || quietDuration.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("quietMs 必须在 0..60000");
        }
        if (maxDuration.isNegative() || maxDuration.isZero()
                || maxDuration.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("maxWaitMs 必须在 1..120000");
        }
        if (quietDuration.compareTo(maxDuration) > 0) {
            throw new IllegalArgumentException("quietMs 不能大于 maxWaitMs");
        }
        Instant started = Instant.now();
        Instant deadline = started.plus(maxDuration);
        List<InboxItem> matching = matchingItems(exact, prefix);
        Instant lastActivity = latestActivity(matching, started);
        while (!closed) {
            Instant now = Instant.now();
            matching = matchingItems(exact, prefix);
            // A new message can have updated or created an item since the previous iteration.
            // An empty source uses the call start as its anchor, so quiet=2s still waits once.
            lastActivity = latestActivity(matching, started);
            if (!now.isBefore(lastActivity.plus(quietDuration))) {
                return new WaitQuietResult(true, "quiet", selector, matchedBy,
                        Duration.between(started, now).toMillis(), lastActivity,
                        unackedNewestFirst(matching));
            }
            if (!now.isBefore(deadline)) {
                return new WaitQuietResult(false, "max_wait", selector, matchedBy,
                        Duration.between(started, now).toMillis(), lastActivity,
                        unackedNewestFirst(matching));
            }
            Instant next = lastActivity.plus(quietDuration);
            if (next.isAfter(deadline)) {
                next = deadline;
            }
            long millis = Math.max(1, Duration.between(now, next).toMillis());
            try {
                wait(Math.min(millis, 1000));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new WaitQuietResult(false, "interrupted", selector, matchedBy,
                        Duration.between(started, Instant.now()).toMillis(), lastActivity,
                        unackedNewestFirst(matching));
            }
        }
        return new WaitQuietResult(false, "interrupted", selector, matchedBy,
                Duration.between(started, Instant.now()).toMillis(), lastActivity,
                unackedNewestFirst(matching));
    }

    private List<InboxItem> matchingItems(String exact, String prefix) {
        return store.items().stream()
                .filter(item -> exact.isEmpty()
                        ? InboxWaitPolicy.applies(prefix, item.source())
                        : item.source().equals(exact))
                .toList();
    }

    private static Instant latestActivity(List<InboxItem> items, Instant fallback) {
        Instant latest = fallback;
        for (InboxItem item : items) {
            if (item.updatedAt().isAfter(latest)) {
                latest = item.updatedAt();
            }
        }
        return latest;
    }

    private static List<InboxItem> unackedNewestFirst(List<InboxItem> items) {
        return items.stream()
                .filter(item -> item.status() != ItemStatus.ACKED)
                .sorted(Comparator.comparing(InboxItem::updatedAt).reversed())
                .limit(20)
                .toList();
    }

    /** Content-free view of the effective source-wait policy and current held items. */
    public synchronized Map<String, Object> waitPolicyView() {
        InboxRuntime current = runtime.get();
        InboxWaitPolicy policy = current.waitPolicy();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", policy.enabled());
        body.put("defaultQuietSeconds", policy.defaultQuietSeconds());
        body.put("defaultMaxWaitSeconds", policy.defaultMaxWaitSeconds());
        List<Map<String, Object>> rules = new ArrayList<>();
        for (InboxWaitPolicy.Rule rule : policy.rules()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", rule.source());
            row.put("quietSeconds", rule.quietSeconds());
            row.put("maxWaitSeconds", rule.maxWaitSeconds());
            rules.add(row);
        }
        body.put("rules", rules);
        Instant now = Instant.now();
        int waiting = 0;
        for (InboxItem item : visibleItems()) {
            if (!item.frozen() && heldForQuiet(item, now)) {
                waiting++;
            }
        }
        body.put("waitingForQuiet", waiting);
        Instant next = nextQuietAt();
        body.put("nextQuietAt", next == null ? "" : next.toString());
        return body;
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
            if (item.priority() >= policy().importantThreshold()) {
                remainingImportant++;
            }
        }
        int pending = 0;
        for (InboxItem item : store.items()) {
            if (item.status() != ItemStatus.ACKED && !item.frozen()) {
                pending++;
            }
        }
        List<InboxItem> visible = visibleItems();
        int capacity = Math.max(1, runtime.get().maxItemsPerPanel());
        int windowSize = Math.min(capacity, visible.size());
        int frozenInWindow = 0;
        for (int index = 0; index < windowSize; index++) {
            if (visible.get(index).frozen()) {
                frozenInWindow++;
            }
        }
        int pendingBlockedByFrozen = 0;
        if (windowSize >= capacity && frozenInWindow == windowSize) {
            // Every slot is held by an already-pushed item that nobody acked; anything newer can
            // only be delivered after an ack frees a slot. Observation only, no reordering.
            for (int index = windowSize; index < visible.size(); index++) {
                if (!visible.get(index).frozen()) {
                    pendingBlockedByFrozen++;
                }
            }
        }
        Instant now = Instant.now();
        int waitingForQuiet = 0;
        for (InboxItem item : visible) {
            if (!item.frozen() && heldForQuiet(item, now)) {
                waitingForQuiet++;
            }
        }
        Instant next = nextDue();
        Instant nextQuiet = nextQuietAt();
        return new InboxStatus(available, acked, remainingImportant, pending,
                next == null ? "" : next.toString(), panelRevision, ackedRevision, unread,
                pushRevision, ackConflicts.get(), pushes.get(), repushes.get(), dismissed.get(),
                List.copyOf(recentErrors), frozenInWindow, pendingBlockedByFrozen > 0,
                pendingBlockedByFrozen, waitingForQuiet,
                nextQuiet == null ? "" : nextQuiet.toString());
    }

    public synchronized List<ItemSummary> list() {
        return store.items().stream()
                .filter(item -> item.status() != ItemStatus.ACKED)
                .map(item -> new ItemSummary(item.id(), item.source(), item.summary(),
                        item.priority(), item.status(), item.availableAt(), item.revision(),
                        item.continued()))
                .toList();
    }

    // ---------------------------------------------------------------- plugin bridge

    /** Records the DSH plugin heartbeat; returns the command it should apply next, if any. */
    public synchronized ControlCommand pluginHeartbeat(PluginHeartbeat heartbeat) {
        if (heartbeat == null) {
            throw new IllegalArgumentException("HEARTBEAT_EMPTY");
        }
        this.lastHeartbeat = heartbeat;
        if (!heartbeat.sessionId().isBlank()) {
            store.setState("plugin_session_id", heartbeat.sessionId());
        }
        if (pendingControl != null
                && pendingControl.id().equals(heartbeat.appliedControlId())) {
            pendingControl = null;
        }
        notifyAll();
        return pendingControl;
    }

    public synchronized PluginHeartbeat lastHeartbeat() {
        return lastHeartbeat;
    }

    public synchronized ControlCommand pendingControl() {
        return pendingControl;
    }

    /** Sets the desired binding and queues a control command for the plugin. */
    public synchronized ControlCommand requestControl(String action, String sessionId,
                                                      String remind) {
        String target = sessionId == null ? "" : sessionId.trim();
        if (target.isEmpty()) {
            target = store.state("plugin_session_id");
        }
        if (pendingControl != null && pendingControl.action().equals(action)
                && pendingControl.sessionId().equals(target)) {
            return pendingControl;
        }
        ControlCommand command = new ControlCommand(
                "ctl-" + (++controlSequence) + "-" + UUID.randomUUID().toString().substring(0, 8),
                action, target, remind, Instant.now());
        pendingControl = command;
        if (!target.isBlank()) {
            store.setState("plugin_session_id", target);
        }
        notifyAll();
        return command;
    }

    public synchronized String pluginSessionId() {
        return store.state("plugin_session_id");
    }

    public synchronized InboxRuntimeInfo runtimeInfo() {
        return new InboxRuntimeInfo(processId, mcpStartId, startedAtEpochMillis,
                panelRevision, pushRevision, ackedRevision, store.identity());
    }

    public void setMcpStartId(String mcpStartId) {
        this.mcpStartId = mcpStartId == null ? "" : mcpStartId;
    }

    public String inboxId() {
        return inboxId;
    }

    // ---------------------------------------------------------------- lifecycle

    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    private void afterAppend(InboxItem item) {
        // D-044: a same-source append resets this item's deadline; the first-seen anchor is kept
        // so a continuously active source still gets its first push after the hard cap.
        Instant now = Instant.now();
        pendingSince.put(item.id(), now);
        pendingFirstSeen.putIfAbsent(item.id(), now);
        pendingSince.keySet().removeIf(id -> store.item(id).isEmpty());
        pendingFirstSeen.keySet().removeIf(id -> store.item(id).isEmpty());
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
            recordError("PURGE_FAILED " + failure.getClass().getSimpleName());
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

    private String renderCapped(List<InboxEntry> entries) {
        int cap = Math.max(2000, runtime.get().maxCharsPerItem() * 4);
        String rendered = render(entries);
        if (cap <= 0 || rendered.length() <= cap) {
            return rendered;
        }
        int end = rendered.offsetByCodePoints(0,
                Math.min(rendered.codePointCount(0, rendered.length()), cap));
        return rendered.substring(0, end) + "…";
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

    private static PanelItem panelItem(InboxItem item, int maxMessages, int maxChars,
                                       boolean includeOmitted, int index,
                                       boolean waitingForQuiet) {
        List<InboxEntry> entries = item.entries();
        if (entries.isEmpty() && !item.summary().isBlank()) {
            entries = List.of(new InboxEntry(0, item.updatedAt(), "", item.summary(), List.of()));
        }
        int max = Math.max(1, maxMessages);
        if (entries.size() > max) {
            entries = List.copyOf(entries.subList(entries.size() - max, entries.size()));
        }
        int renderedBefore = entries.stream().mapToInt(entry -> entry.render().length()).sum();
        if (maxChars > 0 && renderedBefore > maxChars) {
            entries = capEntries(entries, maxChars);
        }
        int renderedAfter = entries.stream().mapToInt(entry -> entry.render().length()).sum();
        int truncatedChars = Math.max(0, renderedBefore - renderedAfter);
        int omitted = includeOmitted ? Math.max(0, item.totalMessages() - entries.size()) : 0;
        return new PanelItem(index, item.id(), item.source(), item.sourceLabel(), item.priority(),
                entries, item.totalMessages(), omitted, item.status(), item.updatedAt(),
                item.continued(), truncatedChars, item.frozenRevision(), waitingForQuiet);
    }

    /** Keeps the newest entries that fit; a single over-long entry is truncated. */
    private static List<InboxEntry> capEntries(List<InboxEntry> entries, int maxChars) {
        List<InboxEntry> kept = new ArrayList<>();
        int used = 0;
        for (int index = entries.size() - 1; index >= 0; index--) {
            InboxEntry entry = entries.get(index);
            int length = entry.render().length() + (kept.isEmpty() ? 0 : 1);
            if (kept.isEmpty() && length > maxChars) {
                kept.add(0, truncateEntry(entry, maxChars));
                break;
            }
            if (used + length > maxChars) {
                break;
            }
            kept.add(0, entry);
            used += length;
        }
        return List.copyOf(kept);
    }

    private static InboxEntry truncateEntry(InboxEntry entry, int maxChars) {
        String prefix = entry.render().substring(0,
                Math.max(0, entry.render().length() - entry.text().length()));
        int budget = Math.max(8, maxChars - prefix.length() - 2);
        String text = entry.text();
        int points = text.codePointCount(0, text.length());
        if (points > budget) {
            text = text.substring(0, text.offsetByCodePoints(0, budget));
        }
        List<String> markers = new ArrayList<>(entry.markers());
        if (!markers.contains("…")) {
            markers.add("…");
        }
        return new InboxEntry(entry.localSeq(), entry.at(), entry.sender(), text, markers);
    }

    private void recordError(String code) {
        recentErrors.addLast(Instant.now() + " " + code);
        while (recentErrors.size() > MAX_RECENT_ERRORS) {
            recentErrors.removeFirst();
        }
        try {
            store.setState("recent_errors", String.join("\n", recentErrors));
        } catch (RuntimeException ignored) {
            // Observability must never break the delivery path.
        }
    }

    private AtomicLong metric(String key) {
        try {
            String raw = store.state(key);
            return new AtomicLong(raw.isBlank() ? 0L : Long.parseLong(raw.trim()));
        } catch (RuntimeException failure) {
            return new AtomicLong();
        }
    }

    private void bump(AtomicLong counter, String key) {
        counter.incrementAndGet();
        persistMetric(counter, key);
    }

    private void add(AtomicLong counter, String key, long delta) {
        if (delta <= 0) {
            return;
        }
        counter.addAndGet(delta);
        persistMetric(counter, key);
    }

    private void persistMetric(AtomicLong counter, String key) {
        try {
            store.setState(key, Long.toString(counter.get()));
        } catch (RuntimeException ignored) {
            // Same: metrics are best effort.
        }
    }

    private void loadErrors() {
        try {
            String raw = store.state("recent_errors");
            if (raw == null || raw.isBlank()) {
                return;
            }
            for (String line : raw.split("\n")) {
                if (!line.isBlank()) {
                    recentErrors.addLast(line);
                }
            }
            while (recentErrors.size() > MAX_RECENT_ERRORS) {
                recentErrors.removeFirst();
            }
        } catch (RuntimeException ignored) {
            recentErrors.clear();
        }
    }

    private static String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "-";
        }
        return reason.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }
}
