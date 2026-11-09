package io.mosire.bma3.qqconnect.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Small SQLite store for per-user notes and per-source weights. */
public final class QqExtrasStore implements AutoCloseable {

    public record UserNote(String accountId, String userId, String displayName,
                           List<String> tags, String note, int priority, int revision) {
    }

    public record SourceWeight(String source, int weight, int notifyThreshold, int revision) {
    }

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("SQLite JDBC 驱动缺失", failure);
        }
    }

    private final Connection connection;
    private boolean closed;

    public QqExtrasStore(Path file) {
        try {
            Path parent = file.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS qq_user_note (
                          account_id TEXT NOT NULL,
                          user_id TEXT NOT NULL,
                          display_name TEXT NOT NULL,
                          tags TEXT NOT NULL,
                          note TEXT NOT NULL,
                          priority INTEGER NOT NULL,
                          revision INTEGER NOT NULL,
                          PRIMARY KEY (account_id, user_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS qq_source_weight (
                          source TEXT PRIMARY KEY,
                          weight INTEGER NOT NULL,
                          notify_threshold INTEGER NOT NULL,
                          revision INTEGER NOT NULL
                        )
                        """);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("打开 QQ 扩展表失败", failure);
        }
    }

    public synchronized Optional<UserNote> note(String accountId, String userId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM qq_user_note WHERE account_id = ? AND user_id = ?")) {
            statement.setString(1, accountId);
            statement.setString(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapNote(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取用户备注失败", failure);
        }
    }

    public synchronized List<UserNote> notes(String accountId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM qq_user_note WHERE account_id = ? ORDER BY priority DESC, user_id")) {
            statement.setString(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                List<UserNote> notes = new ArrayList<>();
                while (result.next()) {
                    notes.add(mapNote(result));
                }
                return List.copyOf(notes);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取用户备注列表失败", failure);
        }
    }

    public synchronized UserNote upsertNote(String accountId, String userId, String displayName,
                                             List<String> tags, String note, int priority,
                                             Integer expectedRevision) {
        UserNote current = note(accountId, userId).orElse(null);
        int revision = current == null ? 0 : current.revision();
        if (expectedRevision != null && expectedRevision != revision) {
            throw new IllegalArgumentException("REVISION_CONFLICT");
        }
        int next = revision + 1;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO qq_user_note
                  (account_id, user_id, display_name, tags, note, priority, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(account_id, user_id) DO UPDATE SET
                  display_name=excluded.display_name, tags=excluded.tags,
                  note=excluded.note, priority=excluded.priority, revision=excluded.revision
                """)) {
            statement.setString(1, accountId);
            statement.setString(2, userId);
            statement.setString(3, displayName == null ? "" : displayName);
            statement.setString(4, String.join(",", tags == null ? List.of() : tags));
            statement.setString(5, note == null ? "" : note);
            statement.setInt(6, priority);
            statement.setInt(7, next);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("写入用户备注失败", failure);
        }
        return new UserNote(accountId, userId, displayName == null ? "" : displayName,
                tags == null ? List.of() : List.copyOf(tags), note == null ? "" : note,
                priority, next);
    }

    public synchronized Optional<SourceWeight> sourceWeight(String source) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM qq_source_weight WHERE source = ?")) {
            statement.setString(1, source);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapWeight(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取来源权重失败", failure);
        }
    }

    public synchronized List<SourceWeight> weights() {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM qq_source_weight ORDER BY source")) {
            List<SourceWeight> weights = new ArrayList<>();
            while (result.next()) {
                weights.add(mapWeight(result));
            }
            return List.copyOf(weights);
        } catch (SQLException failure) {
            throw new IllegalStateException("读取来源权重列表失败", failure);
        }
    }

    public synchronized SourceWeight upsertWeight(String source, int weight,
                                                   int notifyThreshold, Integer expectedRevision) {
        SourceWeight current = sourceWeight(source).orElse(null);
        int revision = current == null ? 0 : current.revision();
        if (expectedRevision != null && expectedRevision != revision) {
            throw new IllegalArgumentException("REVISION_CONFLICT");
        }
        int next = revision + 1;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO qq_source_weight (source, weight, notify_threshold, revision)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(source) DO UPDATE SET
                  weight=excluded.weight, notify_threshold=excluded.notify_threshold,
                  revision=excluded.revision
                """)) {
            statement.setString(1, source);
            statement.setInt(2, weight);
            statement.setInt(3, notifyThreshold);
            statement.setInt(4, next);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("写入来源权重失败", failure);
        }
        return new SourceWeight(source, weight, notifyThreshold, next);
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
            throw new IllegalStateException("关闭 QQ 扩展表失败", failure);
        }
    }

    private static UserNote mapNote(ResultSet result) throws SQLException {
        String rawTags = result.getString("tags");
        return new UserNote(result.getString("account_id"), result.getString("user_id"),
                result.getString("display_name"),
                rawTags == null || rawTags.isBlank() ? List.of() : Arrays.asList(rawTags.split(",")),
                result.getString("note"), result.getInt("priority"), result.getInt("revision"));
    }

    private static SourceWeight mapWeight(ResultSet result) throws SQLException {
        return new SourceWeight(result.getString("source"), result.getInt("weight"),
                result.getInt("notify_threshold"), result.getInt("revision"));
    }
}
