package io.mosire.bma3.inbox.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Single-inbox state machine. It owns delivery timing, not message content. */
public final class InboxService {

    private final InboxStore store;
    private final DeliveryPolicy policy;

    public InboxService(InboxStore store, DeliveryPolicy policy) {
        this.store = store;
        this.policy = policy;
    }

    public InboxItem append(AppendItem request) {
        Instant now = Instant.now();
        InboxItem item = new InboxItem(
                "itm-" + UUID.randomUUID(),
                request.source(),
                request.summary(),
                request.priority(),
                ItemStatus.AVAILABLE,
                request.availableAt(),
                now,
                1);
        store.append(item);
        return item;
    }

    public InboxStatus status() {
        int available = 0;
        int delivered = 0;
        int acked = 0;
        int undelivered = 0;
        int remainingImportant = 0;
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
        }
        return new InboxStatus(available, delivered, acked, undelivered,
                available + delivered, remainingImportant);
    }

    public List<ItemSummary> list() {
        return store.items().stream()
                .filter(item -> item.status() != ItemStatus.ACKED)
                .map(item -> new ItemSummary(item.id(), item.source(), item.summary(),
                        item.priority(), item.status(), item.availableAt(), item.revision()))
                .toList();
    }

    /**
     * Returns deliveries that became due at {@code now}. If an unacked delivery exists, only
     * that delivery is retried; no second batch is created until it is acked or exhausted.
     */
    public List<Delivery> due(Instant now) {
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
            if (item.status() != ItemStatus.AVAILABLE) {
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
        // iibot priority model: deliver the strongest level first; lower-priority items wait
        // for the next batch instead of being mixed into an important wake.
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

    public DeliveryAck ack(String deliveryId) {
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
        return new DeliveryAck(deliveryId, true, status().remaining());
    }
}
