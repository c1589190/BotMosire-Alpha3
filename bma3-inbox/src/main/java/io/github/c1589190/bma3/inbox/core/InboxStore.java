package io.github.c1589190.bma3.inbox.core;

import java.util.List;
import java.util.Optional;

/** Persistence port for one inbox. */
public interface InboxStore extends AutoCloseable {

    void append(InboxItem item);

    void update(InboxItem item);

    /**
     * Freezes the given items at {@code pushRevision}: from now on the same source opens a new item
     * instead of merging into these. Freezing never changes item content.
     */
    void freeze(List<String> itemIds, long pushRevision);

    Optional<InboxItem> item(String id);

    /** The current open (not yet pushed) item for one thread/source, if any. */
    Optional<InboxItem> activeForSource(String source);

    /**
     * Newest stored item for one source, regardless of status (open, frozen or ACKED).
     *
     * <p>SQL stores should implement this as a source-filtered {@code ORDER BY ... LIMIT 1} query
     * so callers do not deserialize the whole inbox. The default keeps third-party stores
     * source-compatible.</p>
     */
    default Optional<InboxItem> latestForSource(String source) {
        return items().stream()
                .filter(item -> item.source().equals(source))
                .max(java.util.Comparator.comparing(InboxItem::createdAt)
                        .thenComparing(InboxItem::updatedAt));
    }

    /**
     * Whether this source already has a pushed-but-unacked item. A new item created in that state
     * is marked {@code continued} so the panel can say "（消息更新活跃）".
     */
    boolean hasUnackedFrozenForSource(String source);

    /** Drops rows (used by retention/explicit cleanup only). */
    void delete(List<String> itemIds);

    List<InboxItem> items();

    /**
     * Housekeeping: drops ACKED items older than {@code cutoff}, then trims the oldest ACKED rows
     * until at most {@code maxItems} are stored. Unacked items are never deleted.
     *
     * @return number of rows removed
     */
    int purge(java.time.Instant cutoff, int maxItems);

    /**
     * Opaque, durable identity of the database behind this store.
     *
     * <p>It is created on first open and survives ordinary service restarts; a separately created
     * database has a different identity. A snapshot restore or clone keeps the original identity, so
     * this value cannot by itself detect a rollback — an operator must rotate it for a restored
     * copy (see {@link #rotateIdentity()}).</p>
     */
    String identity();

    /**
     * Replaces the durable identity and returns the new value. Only meaningful while no other
     * process uses the data directory (the app's single-instance lock enforces that); the plugin
     * must treat a changed identity as a different inbox and stop automatic ACK.
     *
     * <p>Fails closed: when the write cannot be confirmed this throws instead of returning a
     * value, because silently keeping the old identity would let a restored copy inherit pending
     * ACKs that belong to the original database.</p>
     */
    String rotateIdentity();

    /**
     * Runs {@code work} as one database transaction: every write done through this store commits
     * together, or is rolled back when {@code work} throws. A store that cannot offer transactional
     * writes must throw rather than run the work unprotected.
     */
    void inTransaction(Runnable work);

    /** Monotonic panel revision, persisted across restarts. */
    long panelRevision();

    void savePanelRevision(long revision);

    /** Last panel revision acknowledged by the Agent. */
    long ackedRevision();

    void saveAckedRevision(long revision);

    /** Monotonic push revision, persisted so a restart does not replay frozen items. */
    long pushRevision();

    void savePushRevision(long revision);

    /** Durable request receipt including the binding fields used for conflict checks. */
    record Receipt(String requestKey, String itemId, String kind, String source, String channel) { }

    /**
     * Atomically claims a request idempotency receipt in the same database as the inbox data.
     *
     * <p>The default fails closed for stores that do not implement durable receipts; the SQLite
     * store implements it. Returning {@code false} means the key was already committed by an
     * earlier call and the caller must not write an item or advance a revision again.</p>
     */
    default boolean claimReceipt(String requestKey, String itemId, String kind) {
        throw new UnsupportedOperationException("该 InboxStore 不支持持久请求回执");
    }

    /**
     * Atomically claims a receipt bound to source/channel. Returning {@code false} means the key
     * already exists; the caller must read {@link #receipt(String)} and validate the binding.
     * The default delegates to the legacy three-argument hook for third-party stores.
     */
    default boolean claimReceipt(String requestKey, String itemId, String kind,
                                 String source, String channel) {
        return claimReceipt(requestKey, itemId, kind);
    }

    /** Returns the durable receipt, including binding fields, if the key exists. */
    default Optional<Receipt> receipt(String requestKey) {
        return Optional.empty();
    }

    /** Atomically claims an evidence dedupe key scoped to one item. */
    default boolean claimEvidenceKey(String itemId, String evidenceKey, String observedAt) {
        throw new UnsupportedOperationException("该 InboxStore 不支持持久证据去重键");
    }

    /** Updates the item associated with an already-claimed receipt (inside the same transaction). */
    default void setReceiptItemId(String requestKey, String itemId) {
        throw new UnsupportedOperationException("该 InboxStore 不支持持久请求回执");
    }

    /** Returns the item id stored with a receipt, or empty when the key was never claimed. */
    default java.util.Optional<String> receiptItemId(String requestKey) {
        return java.util.Optional.empty();
    }

    /** Free-form text state (plugin session id, desired control id, …). */
    String state(String key);

    void setState(String key, String value);

    /** Cheap liveness check used by the watchdog. */
    boolean ping();

    @Override
    void close();
}
