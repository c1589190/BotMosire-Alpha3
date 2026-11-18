package io.mosire.bma3.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Append-only notebook. There is deliberately no delete: retiring an entry means
 * {@code rate(id, MUTE)}, after which it never appears in a search list again but stays readable
 * by id.
 *
 * <p>This store is not a source of truth: every row carries {@code sourceKind}/{@code sourceRef}
 * so a reader can go back to the message board or the DSH session log.</p>
 */
public interface MemoryStore extends AutoCloseable {

    /** How a rating moves the ranking bias. */
    enum Vote {
        USEFUL,
        USELESS,
        MUTE
    }

    /**
     * Appends one entry.
     *
     * @return the new id
     */
    long write(String text, String tags, String sourceKind, String sourceRef,
            String contextRef, Instant now);

    /** Tag-first, paged digest list. Muted rows never appear. */
    List<MemoryHit> search(String query, int limit, int offset);

    /** Full row, including muted ones. Records a use when {@code markUsed} is set. */
    Optional<MemoryItem> view(long id, Instant now, boolean markUsed);

    /** Applies a rating and returns the resulting weight. */
    double rate(long id, Vote vote, Instant now);

    boolean ping();

    void close();
}
