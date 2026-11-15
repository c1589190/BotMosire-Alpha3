package io.mosire.bma3.inbox.core;

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

    /** Free-form text state (plugin session id, desired control id, …). */
    String state(String key);

    void setState(String key, String value);

    /** Cheap liveness check used by the watchdog. */
    boolean ping();

    @Override
    void close();
}
