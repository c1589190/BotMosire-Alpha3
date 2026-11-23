package io.github.c1589190.bma3.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * SQLite implementation of the rolling context copy: {@code context_digest} + {@code context_gap}.
 *
 * <p>Every body lands after the redactor and after truncation, so a credential cannot survive a
 * push by being long. Eviction keeps the newest {@code maxEntries} rows of a session; a hole in the
 * pushed sequence is recorded in {@code context_gap} instead of being silently filled.</p>
 */
public final class SqliteContextDigestStore implements ContextDigestStore {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("SQLite JDBC 驱动缺失", failure);
        }
    }

    private final Connection connection;
    private final Supplier<ContextOptions> options;
    private final UnaryOperator<String> sanitizer;
    private boolean closed;

    public SqliteContextDigestStore(Path databaseFile, Supplier<ContextOptions> options,
            UnaryOperator<String> sanitizer) {
        this.options = Objects.requireNonNull(options, "options");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        Objects.requireNonNull(databaseFile, "databaseFile");
        Path absolute = databaseFile.toAbsolutePath().normalize();
        try {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS context_digest (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          session_id TEXT NOT NULL,
                          event_seq INTEGER NOT NULL,
                          kind TEXT NOT NULL,
                          speaker TEXT,
                          at TEXT,
                          body TEXT NOT NULL,
                          chars INTEGER NOT NULL,
                          truncated INTEGER NOT NULL DEFAULT 0,
                          pushed_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS idx_context_digest_event
                        ON context_digest(session_id, event_seq)
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_context_digest_session ON "
                        + "context_digest(session_id, pushed_at)");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS context_gap (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          session_id TEXT NOT NULL,
                          from_seq INTEGER NOT NULL,
                          to_seq INTEGER NOT NULL,
                          detected_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS idx_context_gap_range
                        ON context_gap(session_id, from_seq, to_seq)
                        """);
            }
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("打开 SQLite 上下文副本失败", failure);
        }
    }

    @Override
    public synchronized AppendResult append(String sessionId, long capturedThroughSeq,
            List<ContextEntry> entries, Instant now) {
        ensureOpen();
        String session = require(sessionId);
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(now, "now");
        ContextOptions settings = options.get();
        Map<Long, ContextEntry> unique = new LinkedHashMap<>();
        for (ContextEntry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (!KINDS.contains(entry.kind())) {
                throw new IllegalArgumentException(
                        "kind 必须是 " + String.join("/", KINDS) + " 之一，收到 " + entry.kind());
            }
            if (entry.eventSeq() <= 0) {
                throw new IllegalArgumentException("eventSeq 必须为正，收到 " + entry.eventSeq());
            }
            unique.put(entry.eventSeq(), entry);
        }
        try {
            long previousMax = maxEventSeqLocked(session);
            if (!unique.isEmpty() && previousMax > 0) {
                long lowest = unique.keySet().stream().mapToLong(Long::longValue).min()
                        .orElse(previousMax);
                if (lowest > previousMax + 1) {
                    // The endpoints are the last sequence we hold and the first one the plugin
                    // has now; the hole is strictly between them (A10 asserts from=2,to=5).
                    recordGap(session, previousMax, lowest, now);
                }
            }
            int truncated = 0;
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO context_digest(session_id, event_seq, kind, speaker, at, body,
                                               chars, truncated, pushed_at)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(session_id, event_seq) DO UPDATE SET
                      kind = excluded.kind,
                      speaker = excluded.speaker,
                      at = excluded.at,
                      body = excluded.body,
                      chars = excluded.chars,
                      truncated = excluded.truncated,
                      pushed_at = excluded.pushed_at
                    """)) {
                for (ContextEntry entry : unique.values()) {
                    String body = sanitizer.apply(entry.body()).strip();
                    int length = body.codePointCount(0, body.length());
                    boolean cut = length > settings.maxEntryChars();
                    if (cut) {
                        body = cutCodePoints(body, settings.maxEntryChars());
                        length = body.codePointCount(0, body.length());
                        truncated++;
                    }
                    upsert.setString(1, session);
                    upsert.setLong(2, entry.eventSeq());
                    upsert.setString(3, entry.kind());
                    upsert.setString(4, entry.speaker());
                    upsert.setString(5, entry.at() == null ? null : entry.at().toString());
                    upsert.setString(6, body);
                    upsert.setInt(7, length);
                    upsert.setBoolean(8, cut);
                    upsert.setString(9, now.toString());
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
            if (unique.isEmpty()) {
                // A silent push still proves the plugin is alive: move the watermark forward
                // without inventing a row.
                try (PreparedStatement touch = connection.prepareStatement("""
                        UPDATE context_digest SET pushed_at = ?
                        WHERE session_id = ? AND event_seq <= ?
                        """)) {
                    touch.setString(1, now.toString());
                    touch.setString(2, session);
                    touch.setLong(3, Math.max(capturedThroughSeq, 0L));
                    touch.executeUpdate();
                }
            }
            int evicted = evict(session, settings.maxEntries());
            return new AppendResult(unique.size(), truncated, evicted, maxEventSeqLocked(session));
        } catch (SQLException failure) {
            throw new IllegalStateException("写入上下文副本失败", failure);
        }
    }

    @Override
    public synchronized List<ContextDigest> crawl(String sessionId, long afterEventSeq,
            int limit) {
        ensureOpen();
        return select("SELECT id, session_id, event_seq, kind, speaker, at, body, chars, truncated, "
                + "pushed_at FROM context_digest WHERE session_id = ? AND event_seq > ? "
                + "ORDER BY event_seq ASC LIMIT ?", require(sessionId), afterEventSeq, limit);
    }

    @Override
    public synchronized List<ContextDigest> recent(String sessionId, int limit) {
        ensureOpen();
        List<ContextDigest> rows = selectSession("SELECT id, session_id, event_seq, kind, "
                + "speaker, at, body, chars, truncated, pushed_at FROM context_digest "
                + "WHERE session_id = ? ORDER BY event_seq DESC LIMIT ?", require(sessionId), limit);
        List<ContextDigest> ascending = new ArrayList<>(rows);
        java.util.Collections.reverse(ascending);
        return ascending;
    }

    @Override
    public synchronized Optional<ContextDigest> view(String sessionId, long eventSeq) {
        ensureOpen();
        List<ContextDigest> rows = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id, session_id, event_seq, kind, speaker, at, body, chars, truncated, "
                        + "pushed_at FROM context_digest WHERE session_id = ? AND event_seq = ?")) {
            query.setString(1, require(sessionId));
            query.setLong(2, eventSeq);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    rows.add(read(result));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取上下文副本失败", failure);
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public synchronized boolean exists(String sessionId, long eventSeq) {
        ensureOpen();
        if (sessionId == null || sessionId.isBlank() || eventSeq <= 0) {
            return false;
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM context_digest WHERE session_id = ? AND event_seq = ?")) {
            query.setString(1, sessionId.trim());
            query.setLong(2, eventSeq);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("查询上下文副本失败", failure);
        }
    }

    @Override
    public synchronized List<ContextGap> gaps(String sessionId) {
        ensureOpen();
        String session = require(sessionId);
        List<ContextGap> gaps = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id, session_id, from_seq, to_seq, detected_at FROM context_gap "
                        + "WHERE session_id = ? ORDER BY from_seq ASC")) {
            query.setString(1, session);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    gaps.add(new ContextGap(rows.getLong(1), rows.getString(2), rows.getLong(3),
                            rows.getLong(4), instant(rows.getString(5))));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("查询上下文断层失败", failure);
        }
        return gaps;
    }

    @Override
    public synchronized OptionalLong maxEventSeq(String sessionId) {
        ensureOpen();
        String session = require(sessionId);
        long value = maxEventSeqLocked(session);
        return value > 0 ? OptionalLong.of(value) : OptionalLong.empty();
    }

    @Override
    public synchronized Optional<String> lastSession() {
        ensureOpen();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT session_id FROM context_digest ORDER BY pushed_at DESC, id DESC "
                                + "LIMIT 1")) {
            return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
        } catch (SQLException failure) {
            throw new IllegalStateException("查询上下文副本会话失败", failure);
        }
    }

    @Override
    public synchronized Optional<Instant> lastPushedAt() {
        ensureOpen();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT MAX(pushed_at) FROM context_digest")) {
            if (!rows.next()) {
                return Optional.empty();
            }
            return Optional.ofNullable(instant(rows.getString(1)));
        } catch (SQLException failure) {
            throw new IllegalStateException("查询上下文副本水位失败", failure);
        }
    }

    @Override
    public synchronized boolean ping() {
        if (closed) {
            return false;
        }
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT 1")) {
            return rows.next();
        } catch (SQLException failure) {
            return false;
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
            throw new IllegalStateException("关闭上下文副本失败", failure);
        }
    }

    private List<ContextDigest> select(String sql, String session, long eventSeq, int limit) {
        List<ContextDigest> rows = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, session);
            query.setLong(2, eventSeq);
            query.setInt(3, Math.max(1, limit));
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    rows.add(read(result));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取上下文副本失败", failure);
        }
        return rows;
    }

    private List<ContextDigest> selectSession(String sql, String session, int limit) {
        List<ContextDigest> rows = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, session);
            query.setInt(2, Math.max(1, limit));
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    rows.add(read(result));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取上下文副本失败", failure);
        }
        return rows;
    }

    private static ContextDigest read(ResultSet result) throws SQLException {
        return new ContextDigest(result.getLong(1), result.getString(2), result.getLong(3),
                result.getString(4), result.getString(5), instant(result.getString(6)),
                result.getString(7), result.getInt(8), result.getBoolean(9),
                instant(result.getString(10)));
    }

    private long maxEventSeqLocked(String session) {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT MAX(event_seq) FROM context_digest WHERE session_id = ?")) {
            query.setString(1, session);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("查询上下文水位失败", failure);
        }
    }

    private void recordGap(String session, long fromSeq, long toSeq, Instant now)
            throws SQLException {
        if (toSeq < fromSeq) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO context_gap(session_id, from_seq, to_seq, detected_at) "
                        + "VALUES(?,?,?,?)")) {
            insert.setString(1, session);
            insert.setLong(2, fromSeq);
            insert.setLong(3, toSeq);
            insert.setString(4, now.toString());
            insert.executeUpdate();
        }
    }

    private int evict(String session, int maxEntries) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM context_digest
                WHERE session_id = ? AND id NOT IN (
                  SELECT id FROM context_digest WHERE session_id = ?
                  ORDER BY event_seq DESC LIMIT ?
                )
                """)) {
            delete.setString(1, session);
            delete.setString(2, session);
            delete.setInt(3, maxEntries);
            return delete.executeUpdate();
        }
    }

    private String require(String sessionId) {
        String session = sessionId == null ? "" : sessionId.trim();
        if (session.isEmpty()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return session;
    }

    private static String cutCodePoints(String text, int max) {
        int end = text.offsetByCodePoints(0, Math.min(max, text.codePointCount(0, text.length())));
        return text.substring(0, end);
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException failure) {
            return null;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("上下文副本已关闭");
        }
    }
}
