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

    /** The current unread item for one thread/source, if any. */
    Optional<InboxItem> activeForSource(String source);

    /** Drops items that were merged into another item of the same source. */
    void delete(List<String> itemIds);

    /** Newest pushed, still-unacked item for one source; the one a new message should continue. */
    Optional<InboxItem> reopenableForSource(String source);

    List<InboxItem> items();

    /**
     * Housekeeping: drops ACKED items older than {@code cutoff}, then trims the oldest ACKED rows
     * until at most {@code maxItems} are stored. Unacked items are never deleted.
     *
     * @return number of rows removed
     */
    int purge(java.time.Instant cutoff, int maxItems);

    /** Monotonic panel revision, persisted across restarts. */
    long panelRevision();

    void savePanelRevision(long revision);

    /** Last panel revision acknowledged by the Agent. */
    long ackedRevision();

    void saveAckedRevision(long revision);

    @Override
    void close();
}
