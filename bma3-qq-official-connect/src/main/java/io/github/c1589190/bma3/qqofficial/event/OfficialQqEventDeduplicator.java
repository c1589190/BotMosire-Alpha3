package io.github.c1589190.bma3.qqofficial.event;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, thread-safe duplicate hint for official message pushes.
 *
 * <p>All keys are explicitly scoped: event ids are scoped by account, while message ids are
 * scoped by both account and raw source. This matters because the official namespace can reuse
 * an id-shaped value across accounts or conversations; a global message-id gate would wrongly
 * drop an unrelated message and lose a durable observation.</p>
 *
 * <p><strong>Never use a {@code false} result to skip durable observation or message-board
 * recovery.</strong> This class is an in-memory optimization only. The durable store must still
 * receive the event and enforce its own {@code (raw_source, message_id)} uniqueness/idempotency,
 * so restart/replay can recover all observations.</p>
 */
public final class OfficialQqEventDeduplicator {

    public static final int DEFAULT_CAPACITY = 10_000;

    private final int capacity;
    private final Map<String, Boolean> seen = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > capacity;
        }
    };

    public OfficialQqEventDeduplicator() {
        this(DEFAULT_CAPACITY);
    }

    public OfficialQqEventDeduplicator(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须为正");
        }
        this.capacity = capacity;
    }

    /**
     * Records one scoped duplicate check.
     *
     * @param accountId official account scope; required, single source segment
     * @param rawSource full raw source such as {@code qq_official_group/account/group_openid};
     *     required
     * @param eventId envelope event id; blank allowed, otherwise scoped by account
     * @param messageId official message id; required for message events
     * @return true when this scoped event/message key was not seen before
     */
    public synchronized boolean firstSeen(
            String accountId, String rawSource, String eventId, String messageId) {
        String safeAccount = OfficialQqSourceIds.requireSegment(accountId, "accountId");
        String safeRawSource = OfficialQqSourceIds.requireRawSource(rawSource);
        String safeMessageId = OfficialQqSourceIds.requireMessageId(messageId);
        String eventKey = eventId == null || eventId.isBlank()
                ? ""
                : "account:" + safeAccount + ":event:" + eventId;
        String messageKey = "account:" + safeAccount
                + ":source:" + safeRawSource
                + ":message:" + safeMessageId;
        if ((!eventKey.isEmpty() && seen.containsKey(eventKey))
                || seen.containsKey(messageKey)) {
            return false;
        }
        if (!eventKey.isEmpty()) {
            seen.put(eventKey, Boolean.TRUE);
        }
        seen.put(messageKey, Boolean.TRUE);
        return true;
    }

    /**
     * Convenience overload deriving the raw source from the event and account.
     *
     * <p>This validates both ids through {@link OfficialQqEvent#source(String)} and therefore
     * cannot silently fall back to a global/cross-conversation key.</p>
     */
    public synchronized boolean firstSeen(OfficialQqEvent event, String accountId) {
        if (event == null) {
            throw new IllegalArgumentException("event 不能为空");
        }
        return firstSeen(
                accountId, event.source(accountId).format(), event.eventId(), event.messageId());
    }

    public synchronized int size() {
        return seen.size();
    }

    public synchronized void clear() {
        seen.clear();
    }
}
