package io.mosire.bma3.inbox.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Single-inbox aggregate panel. It owns display windows and revisions, not message fact. */
public final class InboxService {

    private final InboxStore store;
    private final DeliveryPolicy policy;
    private final String inboxId;
    private final IntSupplier maxMessagesPerItem;
    private final IntSupplier maxItemsPerPanel;
    private final BooleanSupplier includeOmittedCount;
    private long panelRevision;
    private long ackedRevision;
    private boolean closed;

    public InboxService(InboxStore store, DeliveryPolicy policy) {
        this(store, policy, "bma3");
    }

    public InboxService(InboxStore store, DeliveryPolicy policy, String inboxId) {
        this(store, policy, inboxId, () -> 6, () -> 6);
    }

    public InboxService(InboxStore store, DeliveryPolicy policy, String inboxId,
                        IntSupplier maxMessagesPerItem, IntSupplier maxItemsPerPanel) {
        this(store, policy, inboxId, maxMessagesPerItem, maxItemsPerPanel, () -> true);
    }

    public InboxService(InboxStore store, DeliveryPolicy policy, String inboxId,
                        IntSupplier maxMessagesPerItem, IntSupplier maxItemsPerPanel,
                        BooleanSupplier includeOmittedCount) {
        this.store = store;
        this.policy = policy;
        this.inboxId = inboxId == null || inboxId.isBlank() ? "bma3" : inboxId;
        this.maxMessagesPerItem = maxMessagesPerItem == null ? () -> 6 : maxMessagesPerItem;
        this.maxItemsPerPanel = maxItemsPerPanel == null ? () -> 6 : maxItemsPerPanel;
        this.includeOmittedCount = includeOmittedCount == null ? () -> true : includeOmittedCount;
        this.panelRevision = store.panelRevision();
        this.ackedRevision = store.ackedRevision();
    }

    /** Legacy append: one new item, no merge. */
    public synchronized InboxItem append(AppendItem request) {
        long revision = nextRevision();
        InboxEntry entry = request.entry();
        List<InboxEntry> entries = entry == null ? List.of() : List.of(entry);
        String summary = entry == null ? request.summary() : entry.render();
        InboxItem item = new InboxItem(
                "itm-" + UUID.randomUUID(), request.source().raw(), request.source().label(),
                summary, entries, entry == null ? 1 : 1, revision, request.priority(),
                ItemStatus.AVAILABLE, request.availableAt(), Instant.now(), Instant.now(), 1);
        store.append(item);
        notifyAll();
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
            notifyAll();
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
        notifyAll();
        return updated;
    }

    public synchronized InboxPanel panel() {
        long ack = ackedRevision;
        List<InboxItem> eligible = store.items().stream()
                .filter(item -> item.status() != ItemStatus.ACKED)
                .filter(item -> item.eventRevision() > ack)
                .sorted(Comparator.comparingInt(InboxItem::priority).reversed()
                        .thenComparing(InboxItem::updatedAt, Comparator.reverseOrder()))
                .toList();
        int maxItems = Math.max(1, maxItemsPerPanel.getAsInt());
        boolean includeOmitted = includeOmittedCount.getAsBoolean();
        List<PanelItem> items = eligible.stream().limit(maxItems)
                .map(item -> panelItem(item, maxMessagesPerItem.getAsInt(), includeOmitted))
                .toList();
        return new InboxPanel(inboxId, panelRevision, ack, Instant.now(), items,
                eligible.size(), includeOmitted ? Math.max(0, eligible.size() - items.size()) : 0,
                includeOmitted);
    }

    public synchronized InboxAck ackPanel() {
        return ackPanel(panelRevision);
    }

    /**
     * Ack at most the panel revision the client actually saw. A newer revision stays unread;
     * this is what prevents an auto-ack from silently consuming messages that arrived during
     * injection.
     */
    public synchronized InboxAck ackPanel(long requestedRevision) {
        long target = Math.min(Math.max(0L, requestedRevision), panelRevision);
        if (target <= ackedRevision) {
            return new InboxAck(ackedRevision, status().unread(), panelRevision);
        }
        for (InboxItem item : store.items()) {
            if (item.status() != ItemStatus.ACKED && item.eventRevision() <= target) {
                store.update(item.withStatus(ItemStatus.ACKED));
            }
        }
        ackedRevision = target;
        store.saveAckedRevision(target);
        long next = nextRevision();
        notifyAll();
        return new InboxAck(target, status().unread(), next);
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

    public synchronized InboxStatus status() {
        int available = 0;
        int delivered = 0;
        int acked = 0;
        int undelivered = 0;
        int remainingImportant = 0;
        int unread = 0;
        for (InboxItem item : store.items()) {
            switch (item.status()) {
                case AVAILABLE -> available++;
                case DELIVERED -> delivered++;
                case ACKED -> acked++;
                case UNDELIVERED -> undelivered++;
            }
            if (item.priority() >= policy.importantThreshold()
                    && (item.status() == ItemStatus.AVAILABLE
                    || item.status() == ItemStatus.DELIVERED)) {
                remainingImportant++;
            }
            if (item.status() != ItemStatus.ACKED && item.eventRevision() > ackedRevision) {
                unread++;
            }
        }
        return new InboxStatus(available, delivered, acked, undelivered,
                available + delivered, remainingImportant, panelRevision, ackedRevision, unread);
    }

    public synchronized List<ItemSummary> list() {
        return store.items().stream()
                .filter(item -> item.status() != ItemStatus.ACKED)
                .map(item -> new ItemSummary(item.id(), item.source(), item.summary(),
                        item.priority(), item.status(), item.availableAt(), item.revision()))
                .toList();
    }

    /**
     * Legacy delivery path. It remains for compatibility; the panel/SSE path does not use it.
     */
    public synchronized List<Delivery> due(Instant now) {
        return due(now, Integer.MIN_VALUE);
    }

    public synchronized List<Delivery> due(Instant now, int minPriority) {
        List<Delivery> result = new ArrayList<>();
        boolean open = false;
        for (Delivery delivery : store.deliveries()) {
            if (delivery.state() != DeliveryState.DELIVERED) {
                continue;
            }
            Instant nextAttempt = delivery.createdAt()
                    .plus(policy.retryTtl().multipliedBy(delivery.attempts()));
            if (now.isBefore(nextAttempt)) {
                open = true;
                continue;
            }
            if (delivery.attempts() >= policy.maxAttempts()) {
                store.saveDelivery(delivery.withState(DeliveryState.UNDELIVERED));
                for (String itemId : delivery.itemIds()) {
                    store.item(itemId).ifPresent(item ->
                            store.update(item.withStatus(ItemStatus.UNDELIVERED)));
                }
                continue;
            }
            Delivery retry = delivery.withAttempts(delivery.attempts() + 1);
            store.saveDelivery(retry);
            result.add(retry);
            open = true;
        }
        if (open || !result.isEmpty()) {
            return List.copyOf(result);
        }

        List<InboxItem> eligible = new ArrayList<>();
        for (InboxItem item : store.items()) {
            if (item.status() != ItemStatus.AVAILABLE || item.priority() < minPriority) {
                continue;
            }
            boolean important = item.priority() >= policy.importantThreshold();
            Instant dueAt = item.availableAt().plus(policy.delayFor(important));
            if (!now.isBefore(dueAt)) {
                eligible.add(item);
            }
        }
        if (eligible.isEmpty()) {
            return List.of();
        }
        int maxPriority = eligible.stream().mapToInt(InboxItem::priority).max().orElse(0);
        List<InboxItem> selected = eligible.stream()
                .filter(item -> item.priority() == maxPriority)
                .toList();
        boolean important = maxPriority >= policy.importantThreshold();
        String text = selected.stream()
                .map(InboxItem::summary)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
        Delivery delivery = new Delivery(
                "dlv-" + UUID.randomUUID(),
                selected.stream().map(InboxItem::id).toList(),
                text,
                important,
                now,
                null,
                1,
                DeliveryState.DELIVERED);
        store.saveDelivery(delivery);
        for (InboxItem item : selected) {
            store.update(item.withStatus(ItemStatus.DELIVERED));
        }
        return List.of(delivery);
    }

    public synchronized DeliveryAck ack(String deliveryId) {
        Delivery delivery = store.delivery(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("DELIVERY_NOT_FOUND"));
        if (delivery.state() == DeliveryState.ACKED) {
            return new DeliveryAck(deliveryId, false, status().remaining());
        }
        store.saveDelivery(delivery.acked(Instant.now()));
        for (String itemId : delivery.itemIds()) {
            store.item(itemId).ifPresent(item ->
                    store.update(item.withStatus(ItemStatus.ACKED)));
        }
        nextRevision();
        notifyAll();
        return new DeliveryAck(deliveryId, true, status().remaining());
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    private long nextRevision() {
        panelRevision++;
        store.savePanelRevision(panelRevision);
        return panelRevision;
    }

    private List<InboxEntry> trim(List<InboxEntry> entries) {
        int max = Math.max(1, maxMessagesPerItem.getAsInt());
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

    private static PanelItem panelItem(InboxItem item, int maxMessages, boolean includeOmitted) {
        List<InboxEntry> entries = item.entries();
        if (entries.isEmpty() && !item.summary().isBlank()) {
            entries = List.of(new InboxEntry(0, item.updatedAt(), "", item.summary(),
                    List.of()));
        }
        int max = Math.max(1, maxMessages);
        if (entries.size() > max) {
            entries = List.copyOf(entries.subList(entries.size() - max, entries.size()));
        }
        int omitted = includeOmitted ? Math.max(0, item.totalMessages() - entries.size()) : 0;
        return new PanelItem(item.id(), item.source(), item.sourceLabel(), item.priority(),
                entries, item.totalMessages(), omitted, item.status(), item.updatedAt());
    }
}
