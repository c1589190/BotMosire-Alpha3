package io.mosire.bma3.inbox.core;

import java.time.Instant;
import java.util.List;

/** One delivery (one rendered text covering one or more items). */
public record Delivery(
        String id,
        List<String> itemIds,
        String text,
        boolean important,
        Instant createdAt,
        Instant ackedAt,
        int attempts,
        DeliveryState state) {

    public Delivery {
        itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
    }

    public Delivery withAttempts(int nextAttempts) {
        return new Delivery(id, itemIds, text, important, createdAt, ackedAt,
                nextAttempts, state);
    }

    public Delivery withState(DeliveryState nextState) {
        return new Delivery(id, itemIds, text, important, createdAt, ackedAt,
                attempts, nextState);
    }

    public Delivery acked(Instant at) {
        return new Delivery(id, itemIds, text, important, createdAt, at,
                attempts, DeliveryState.ACKED);
    }
}
