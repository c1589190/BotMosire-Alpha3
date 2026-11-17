package io.mosire.bma3.qqofficial.send;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable, atomic {@code msg_seq} allocator for passive official replies.
 *
 * <p>The key is exactly {@code (source, msg_id)}. Allocation commits before the provider send and
 * is never rolled back when the send fails or its outcome is ambiguous. An allocation is also
 * linked to the already-persisted outbound board row through {@code local_seq}, so the exact
 * sequence used for that attempt remains auditable after restart.</p>
 */
public final class OfficialQqReplySequenceStore implements AutoCloseable {

    /** One allocated reply sequence and its outbound-board association. */
    public record ReplyAttempt(long localSeq, String source, String msgId, String eventId,
                               int msgSeq, String operationId, int partIndex,
                               Instant allocatedAt) {
    }

    private static final int MAX_KEY_CHARS = 512;

    private final Connection connection;
    private final Clock clock;
    private boolean closed;

    public OfficialQqReplySequenceStore(Path file) {
        this(file, Clock.systemUTC());
    }

    public OfficialQqReplySequenceStore(Path file, Clock clock) {
        Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
        try {
            Path absolute = file.toAbsolutePath().normalize();
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS official_reply_sequence (
                          source TEXT NOT NULL,
                          msg_id TEXT NOT NULL,
                          event_id TEXT NOT NULL DEFAULT '',
                          last_seq INTEGER NOT NULL,
                          updated_at TEXT NOT NULL,
                          PRIMARY KEY(source, msg_id))
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS official_reply_attempt (
                          local_seq INTEGER PRIMARY KEY,
                          source TEXT NOT NULL,
                          msg_id TEXT NOT NULL,
                          event_id TEXT NOT NULL DEFAULT '',
                          msg_seq INTEGER NOT NULL,
                          operation_id TEXT NOT NULL,
                          part_index INTEGER NOT NULL,
                          allocated_at TEXT NOT NULL)
                        """);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("打开官方回复序号存储失败", failure);
        }
    }

    /**
     * Atomically allocates the next sequence and links it to one outbound board row.
     *
     * <p>If the same {@code (source, msg_id)} was already seen with a different non-blank
     * {@code event_id}, no sequence is consumed and {@code REPLY_REFERENCE_CONFLICT} is raised.</p>
     */
    public synchronized ReplyAttempt allocate(long localSeq, String operationId, int partIndex,
                                               String source, String msgId, String eventId)
            throws OfficialQqSendException {
        if (localSeq < 1) {
            throw new IllegalArgumentException("localSeq 必须为正");
        }
        String safeOperation = requireKey(operationId, "operationId");
        String safeSource = requireKey(source, "source");
        String safeMsgId = requireKey(msgId, "msgId");
        String safeEventId = optionalKey(eventId, "eventId");
        if (partIndex < 1) {
            throw new IllegalArgumentException("partIndex 必须为正");
        }
        ensureOpen();
        Instant now = clock.instant();
        try {
            connection.setAutoCommit(false);
            Existing existing = existing(safeSource, safeMsgId);
            int nextSeq;
            String storedEventId;
            if (existing == null) {
                nextSeq = 1;
                storedEventId = safeEventId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO official_reply_sequence(
                          source, msg_id, event_id, last_seq, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, safeSource);
                    statement.setString(2, safeMsgId);
                    statement.setString(3, storedEventId);
                    statement.setInt(4, nextSeq);
                    statement.setString(5, now.toString());
                    statement.executeUpdate();
                }
            } else {
                if (!existing.eventId().isBlank() && !safeEventId.isBlank()
                        && !existing.eventId().equals(safeEventId)) {
                    throw new OfficialQqSendException(
                            "REPLY_REFERENCE_CONFLICT",
                            "同一 msg_id 已关联不同 event_id，拒绝分配新的 msg_seq");
                }
                storedEventId = existing.eventId().isBlank() ? safeEventId : existing.eventId();
                nextSeq = existing.lastSeq() + 1;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE official_reply_sequence
                        SET event_id = ?, last_seq = ?, updated_at = ?
                        WHERE source = ? AND msg_id = ?
                        """)) {
                    statement.setString(1, storedEventId);
                    statement.setInt(2, nextSeq);
                    statement.setString(3, now.toString());
                    statement.setString(4, safeSource);
                    statement.setString(5, safeMsgId);
                    statement.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO official_reply_attempt(
                      local_seq, source, msg_id, event_id, msg_seq, operation_id,
                      part_index, allocated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setLong(1, localSeq);
                statement.setString(2, safeSource);
                statement.setString(3, safeMsgId);
                statement.setString(4, storedEventId);
                statement.setInt(5, nextSeq);
                statement.setString(6, safeOperation);
                statement.setInt(7, partIndex);
                statement.setString(8, now.toString());
                statement.executeUpdate();
            }
            connection.commit();
            return new ReplyAttempt(localSeq, safeSource, safeMsgId, storedEventId, nextSeq,
                    safeOperation, partIndex, now);
        } catch (OfficialQqSendException failure) {
            rollbackQuietly();
            throw failure;
        } catch (SQLException failure) {
            rollbackQuietly();
            throw new IllegalStateException("分配官方回复 msg_seq 失败", failure);
        } finally {
            resetAutoCommit();
        }
    }

    /** The durable association for one already-persisted outbound row. */
    public synchronized Optional<ReplyAttempt> attempt(long localSeq) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT local_seq, source, msg_id, event_id, msg_seq, operation_id,
                       part_index, allocated_at
                FROM official_reply_attempt WHERE local_seq = ?
                """)) {
            statement.setLong(1, localSeq);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ReplyAttempt(
                        result.getLong("local_seq"),
                        result.getString("source"),
                        result.getString("msg_id"),
                        result.getString("event_id"),
                        result.getInt("msg_seq"),
                        result.getString("operation_id"),
                        result.getInt("part_index"),
                        Instant.parse(result.getString("allocated_at"))));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取官方回复序号关联失败", failure);
        }
    }

    private Existing existing(String source, String msgId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event_id, last_seq FROM official_reply_sequence
                WHERE source = ? AND msg_id = ?
                """)) {
            statement.setString(1, source);
            statement.setString(2, msgId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new Existing(result.getString("event_id"), result.getInt("last_seq"))
                        : null;
            }
        }
    }

    private static String requireKey(String value, String field) {
        String normalized = optionalKey(value, field);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }

    private static String optionalKey(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException(field + " 过长");
        }
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException(field + " 含非法字符");
            }
            offset += Character.charCount(codePoint);
        }
        return normalized;
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure remains the reported one.
        }
    }

    private void resetAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // Closing the store will surface a broken connection on the next operation.
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("官方回复序号存储已关闭");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.close();
        } catch (SQLException failure) {
            throw new IllegalStateException("关闭官方回复序号存储失败", failure);
        }
    }

    private record Existing(String eventId, int lastSeq) {
    }
}
