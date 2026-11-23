package io.github.c1589190.bma3.app.channel.binding;

import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable operation journal for cross-store binding coordination.
 *
 * <p>It is intentionally a separate SQLite file: the registry and observation ledger cannot share
 * a transaction, and adding coordinator tables to either owner's schema would imply an atomicity
 * that does not exist. Journal rows contain IDs/tuples/phases only.</p>
 */
final class BindingJournal implements AutoCloseable {

    private static final int DEFAULT_BUSY_TIMEOUT_MS = 10_000;
    private static final int MAX_PAGE = 500;

    enum Operation {
        BIND,
        UNBIND
    }

    enum Phase {
        BIND_PREPARED,
        LEDGER_BOUND,
        REGISTRY_BOUND,
        UNBIND_PREPARED,
        REGISTRY_UNBOUND,
        LEDGER_UNBOUND,
        COMPLETE,
        COMPENSATED,
        FAILED,
        SUPERSEDED
    }

    record Entry(
            long id,
            Operation operation,
            Phase phase,
            String rawSource,
            String threadId,
            String channel,
            String accountId,
            Optional<Long> startBoardSeq,
            Optional<String> errorCode,
            Instant createdAt,
            Instant updatedAt) {

        Entry {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(rawSource, "rawSource");
            Objects.requireNonNull(threadId, "threadId");
            channel = channel == null ? "" : channel;
            accountId = accountId == null ? "" : accountId;
            startBoardSeq = startBoardSeq == null ? Optional.empty() : startBoardSeq;
            errorCode = errorCode == null ? Optional.empty() : errorCode;
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }

        Entry withPhase(Phase next, Optional<String> error, Instant at) {
            return new Entry(id, operation, next, rawSource, threadId, channel, accountId,
                    startBoardSeq, error, createdAt, at);
        }
    }

    record Page(List<Entry> items, boolean hasMore, Optional<Long> nextAfterId) {
        Page {
            items = List.copyOf(items);
            nextAfterId = nextAfterId == null ? Optional.empty() : nextAfterId;
        }
    }

    record AuditEntry(
            long id,
            Operation operation,
            Phase phase,
            String rawSource,
            String threadId,
            String channel,
            String accountId,
            Optional<Long> startBoardSeq,
            Optional<String> errorCode,
            Instant createdAt,
            Instant updatedAt) {
        AuditEntry {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(phase, "phase");
            rawSource = rawSource == null ? "" : rawSource;
            threadId = threadId == null ? "" : threadId;
            channel = channel == null ? "" : channel;
            accountId = accountId == null ? "" : accountId;
            startBoardSeq = startBoardSeq == null ? Optional.empty() : startBoardSeq;
            errorCode = errorCode == null ? Optional.empty() : errorCode;
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    private final Connection connection;
    private final Clock clock;
    private boolean closed;

    BindingJournal(Path databaseFile, Clock clock) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        this.clock = Objects.requireNonNull(clock, "clock");
        Path absolute = databaseFile.toAbsolutePath().normalize();
        try {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=" + DEFAULT_BUSY_TIMEOUT_MS);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS qq_binding_coordination_journal(
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          operation TEXT NOT NULL,
                          phase TEXT NOT NULL,
                          raw_source TEXT NOT NULL,
                          thread_id TEXT NOT NULL,
                          channel TEXT NOT NULL,
                          account_id TEXT NOT NULL,
                          start_board_seq INTEGER,
                          error_code TEXT,
                          created_at TEXT NOT NULL,
                          updated_at TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_binding_journal_open
                        ON qq_binding_coordination_journal(phase, id)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_binding_journal_source
                        ON qq_binding_coordination_journal(raw_source, id DESC)
                        """);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("初始化 binding coordination journal 失败", failure);
        }
    }

    synchronized long create(Operation operation,
                             String rawSource,
                             String threadId,
                             QqChannelRegistry.Channel channel,
                             String accountId,
                             Long startBoardSeq) {
        ensureOpen();
        Objects.requireNonNull(operation, "operation");
        String now = clock.instant().toString();
        String phase = operation == Operation.BIND
                ? Phase.BIND_PREPARED.name() : Phase.UNBIND_PREPARED.name();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO qq_binding_coordination_journal(
                  operation, phase, raw_source, thread_id, channel, account_id,
                  start_board_seq, error_code, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, operation.name());
            statement.setString(2, phase);
            statement.setString(3, rawSource);
            statement.setString(4, threadId);
            statement.setString(5, channel.wireName());
            statement.setString(6, accountId);
            if (startBoardSeq == null) {
                statement.setNull(7, java.sql.Types.INTEGER);
            } else {
                statement.setLong(7, startBoardSeq);
            }
            statement.setString(8, now);
            statement.setString(9, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("journal insert 未返回 id");
                }
                return keys.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("写入 binding coordination journal 失败", failure);
        }
    }

    synchronized Entry update(long id, Phase phase, String errorCode) {
        ensureOpen();
        Objects.requireNonNull(phase, "phase");
        String now = clock.instant().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE qq_binding_coordination_journal
                SET phase = ?, error_code = ?, updated_at = ?
                WHERE id = ?
                """)) {
            statement.setString(1, phase.name());
            if (errorCode == null || errorCode.isBlank()) {
                statement.setNull(2, java.sql.Types.VARCHAR);
            } else {
                statement.setString(2, errorCode);
            }
            statement.setString(3, now);
            statement.setLong(4, id);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("binding coordination journal 行不存在");
            }
            return entryUnsafe(id).orElseThrow(
                    () -> new IllegalStateException("binding coordination journal 行丢失"));
        } catch (SQLException failure) {
            throw new IllegalStateException("更新 binding coordination journal 失败", failure);
        }
    }

    synchronized Optional<Entry> latestOpen(String rawSource) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM qq_binding_coordination_journal
                WHERE raw_source = ? AND phase NOT IN (?, ?, ?, ?)
                ORDER BY id DESC LIMIT 1
                """)) {
            statement.setString(1, rawSource);
            statement.setString(2, Phase.COMPLETE.name());
            statement.setString(3, Phase.COMPENSATED.name());
            statement.setString(4, Phase.FAILED.name());
            statement.setString(5, Phase.SUPERSEDED.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 binding coordination journal 失败", failure);
        }
    }

    synchronized Page openPage(long afterId, int limit) {
        ensureOpen();
        int bounded = Math.max(1, Math.min(MAX_PAGE, limit));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM qq_binding_coordination_journal
                WHERE id > ? AND phase NOT IN (?, ?, ?, ?)
                ORDER BY id ASC LIMIT ?
                """)) {
            statement.setLong(1, afterId);
            statement.setString(2, Phase.COMPLETE.name());
            statement.setString(3, Phase.COMPENSATED.name());
            statement.setString(4, Phase.FAILED.name());
            statement.setString(5, Phase.SUPERSEDED.name());
            statement.setInt(6, bounded + 1);
            List<Entry> fetched = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    fetched.add(map(result));
                }
            }
            boolean hasMore = fetched.size() > bounded;
            List<Entry> items = hasMore
                    ? List.copyOf(fetched.subList(0, bounded)) : List.copyOf(fetched);
            Optional<Long> next = hasMore && !items.isEmpty()
                    ? Optional.of(items.getLast().id()) : Optional.empty();
            return new Page(items, hasMore, next);
        } catch (SQLException failure) {
            throw new IllegalStateException("分页读取 binding coordination journal 失败", failure);
        }
    }

    synchronized void closeOpenForSource(String rawSource, Phase terminalPhase, String errorCode) {
        ensureOpen();
        Objects.requireNonNull(terminalPhase, "terminalPhase");
        String now = clock.instant().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE qq_binding_coordination_journal
                SET phase = ?, error_code = ?, updated_at = ?
                WHERE raw_source = ? AND phase NOT IN (?, ?, ?, ?)
                """)) {
            statement.setString(1, terminalPhase.name());
            if (errorCode == null || errorCode.isBlank()) {
                statement.setNull(2, java.sql.Types.VARCHAR);
            } else {
                statement.setString(2, errorCode);
            }
            statement.setString(3, now);
            statement.setString(4, rawSource);
            statement.setString(5, Phase.COMPLETE.name());
            statement.setString(6, Phase.COMPENSATED.name());
            statement.setString(7, Phase.FAILED.name());
            statement.setString(8, Phase.SUPERSEDED.name());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("关闭 binding coordination journal 行失败", failure);
        }
    }

    synchronized void recordError(long id, String errorCode) {
        ensureOpen();
        String now = clock.instant().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE qq_binding_coordination_journal
                SET error_code = ?, updated_at = ? WHERE id = ?
                """)) {
            statement.setString(1, errorCode);
            statement.setString(2, now);
            statement.setLong(3, id);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("记录 binding coordination journal 错误失败", failure);
        }
    }

    synchronized List<AuditEntry> audits(int limit) {
        ensureOpen();
        int bounded = Math.max(1, Math.min(1000, limit));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM qq_binding_coordination_journal
                ORDER BY id DESC LIMIT ?
                """)) {
            statement.setInt(1, bounded);
            List<AuditEntry> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Entry entry = map(rows);
                    result.add(new AuditEntry(entry.id(), entry.operation(), entry.phase(),
                            entry.rawSource(), entry.threadId(), entry.channel(),
                            entry.accountId(), entry.startBoardSeq(), entry.errorCode(),
                            entry.createdAt(), entry.updatedAt()));
                }
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 binding coordination audit 失败", failure);
        }
    }

    private Optional<Entry> entryUnsafe(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM qq_binding_coordination_journal WHERE id = ?
                """)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private Entry map(ResultSet result) throws SQLException {
        long start = result.getLong("start_board_seq");
        Optional<Long> startBoardSeq = result.wasNull() ? Optional.empty() : Optional.of(start);
        String error = result.getString("error_code");
        return new Entry(
                result.getLong("id"),
                Operation.valueOf(result.getString("operation")),
                Phase.valueOf(result.getString("phase")),
                result.getString("raw_source"),
                result.getString("thread_id"),
                result.getString("channel"),
                result.getString("account_id"),
                startBoardSeq,
                Optional.ofNullable(error),
                Instant.parse(result.getString("created_at")),
                Instant.parse(result.getString("updated_at")));
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("binding coordination journal 已关闭");
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
        } catch (SQLException ignored) {
            // Close is best-effort; no business fact is written here.
        }
    }
}
