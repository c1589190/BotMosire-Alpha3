package io.mosire.bma3.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The jar's own rolling copy of the DSH session context.
 *
 * <p>It is a cache, not a fact source: losing it costs nothing, and it can be cleared and replayed
 * from the plugin. The only semantic it must never lose is the <em>watermark</em> — which event
 * sequence numbers arrived, and when — because that is what makes the watchdog a watchdog rather
 * than {@code ps} (D-079 ④).</p>
 *
 * <p>Writes are append-only and idempotent on {@code (session_id, event_seq)}. There is no delete:
 * the copy is bounded by eviction of the oldest rows, and {@code disabled} sessions simply stop
 * being pushed to.</p>
 */
public interface ContextDigestStore extends AutoCloseable {

    /** Kinds the copy accepts; anything else is rejected rather than stored. */
    List<String> KINDS = List.of("user", "assistant", "tool");

    /**
     * Outcome of one push.
     *
     * @param stored     rows written (inserted or overwritten)
     * @param truncated  rows whose body was cut at {@code memory.context.maxEntryChars}
     * @param evicted    rows dropped to honour {@code memory.context.maxEntries}
     * @param maxEventSeq highest event sequence now held for the session
     */
    record AppendResult(int stored, int truncated, int evicted, long maxEventSeq) {
    }

    AppendResult append(String sessionId, long capturedThroughSeq, List<ContextEntry> entries,
            Instant now);

    /** Rows with {@code event_seq > afterEventSeq}, oldest first. */
    List<ContextDigest> crawl(String sessionId, long afterEventSeq, int limit);

    /** The newest {@code limit} rows of the session, oldest first. */
    List<ContextDigest> recent(String sessionId, int limit);

    Optional<ContextDigest> view(String sessionId, long eventSeq);

    boolean exists(String sessionId, long eventSeq);

    List<ContextGap> gaps(String sessionId);

    OptionalLong maxEventSeq(String sessionId);

    /** Session id of the most recent push, whatever the current binding is. */
    Optional<String> lastSession();

    Optional<Instant> lastPushedAt();

    boolean ping();
}
