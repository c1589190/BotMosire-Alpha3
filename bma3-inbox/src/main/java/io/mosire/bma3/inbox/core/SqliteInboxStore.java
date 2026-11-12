package io.mosire.bma3.inbox.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** SQLite persistence for one inbox: bounded item windows, panel metadata, legacy deliveries. */
public final class SqliteInboxStore implements InboxStore {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private static final TypeReference<List<InboxEntry>> ENTRIES = new TypeReference<>() { };

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("SQLite JDBC 驱动缺失", failure);
        }
    }

    private final Connection connection;
    private boolean closed;

    public SqliteInboxStore(Path databaseFile) {
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
                        CREATE TABLE IF NOT EXISTS inbox_item (
                          id TEXT PRIMARY KEY,
                          source TEXT NOT NULL,
                          source_label TEXT,
                          summary TEXT NOT NULL,
                          entries_json TEXT,
                          total_messages INTEGER,
                          event_revision INTEGER,
                          priority INTEGER NOT NULL,
                          status TEXT NOT NULL,
                          available_at TEXT NOT NULL,
                          created_at TEXT NOT NULL,
                          updated_at TEXT,
                          revision INTEGER NOT NULL,
                          frozen_revision INTEGER NOT NULL DEFAULT 0,
                          continued INTEGER NOT NULL DEFAULT 0
                        )
                        """);
                ensureColumn(statement, "inbox_item", "source_label", "TEXT");
                ensureColumn(statement, "inbox_item", "entries_json", "TEXT");
                ensureColumn(statement, "inbox_item", "total_messages", "INTEGER");
                ensureColumn(statement, "inbox_item", "event_revision", "INTEGER");
                ensureColumn(statement, "inbox_item", "updated_at", "TEXT");
                ensureColumn(statement, "inbox_item", "frozen_revision", "INTEGER NOT NULL DEFAULT 0");
                ensureColumn(statement, "inbox_item", "continued", "INTEGER NOT NULL DEFAULT 0");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS inbox_meta (
                          key TEXT PRIMARY KEY,
                          value INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS inbox_state (
                          key TEXT PRIMARY KEY,
                          value TEXT NOT NULL
                        )
                        """);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("打开 SQLite Inbox 失败", failure);
        }
    }

    @Override
    public synchronized void append(InboxItem item) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO inbox_item (id, source, source_label, summary, entries_json,
                  total_messages, event_revision, priority, status, available_at, created_at,
                  updated_at, revision, frozen_revision, continued) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindInsert(statement, item);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("写入 Inbox item 失败", failure);
        }
    }

    @Override
    public synchronized void update(InboxItem item) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE inbox_item SET source = ?, source_label = ?, summary = ?,
                  entries_json = ?, total_messages = ?, event_revision = ?, priority = ?,
                  status = ?, available_at = ?, created_at = ?, updated_at = ?, revision = ?,
                  frozen_revision = ?, continued = ?
                WHERE id = ?
                """)) {
            bindUpdate(statement, item);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("更新 Inbox item 失败", failure);
        }
    }

    @Override
    public synchronized void freeze(java.util.List<String> itemIds, long pushRevision) {
        ensureOpen();
        if (itemIds == null || itemIds.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE inbox_item SET frozen_revision = ? WHERE id = ?")) {
            for (String itemId : itemIds) {
                statement.setLong(1, pushRevision);
                statement.setString(2, itemId);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException failure) {
            throw new IllegalStateException("冻结 Inbox item 失败", failure);
        }
    }

    private void bindInsert(PreparedStatement statement, InboxItem item) throws SQLException {
        statement.setString(1, item.id());
        bindBody(statement, item, 2);
    }

    /** Binds the 14 UPDATE SET values, then the id at position 15. */
    private void bindUpdate(PreparedStatement statement, InboxItem item) throws SQLException {
        bindBody(statement, item, 1);
        statement.setString(15, item.id());
    }

    private void bindBody(PreparedStatement statement, InboxItem item, int offset)
            throws SQLException {
        statement.setString(offset, item.source());
        statement.setString(offset + 1, item.sourceLabel());
        statement.setString(offset + 2, item.summary());
        try {
            statement.setString(offset + 3, JSON.writeValueAsString(item.entries()));
        } catch (Exception failure) {
            throw new SQLException("序列化 Inbox entries 失败", failure);
        }
        statement.setInt(offset + 4, item.totalMessages());
        statement.setLong(offset + 5, item.eventRevision());
        statement.setInt(offset + 6, item.priority());
        statement.setString(offset + 7, item.status().name());
        statement.setString(offset + 8, item.availableAt().toString());
        statement.setString(offset + 9, item.createdAt().toString());
        statement.setString(offset + 10, item.updatedAt().toString());
        statement.setInt(offset + 11, item.revision());
        statement.setLong(offset + 12, item.frozenRevision());
        statement.setInt(offset + 13, item.continued() ? 1 : 0);
    }

    @Override
    public synchronized Optional<InboxItem> item(String id) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM inbox_item WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapItem(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 Inbox item 失败", failure);
        }
    }

    @Override
    public synchronized Optional<InboxItem> activeForSource(String source) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM inbox_item
                WHERE source = ? AND status = 'AVAILABLE' AND frozen_revision = 0
                ORDER BY created_at DESC LIMIT 1
                """)) {
            statement.setString(1, source);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapItem(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 active Inbox item 失败", failure);
        }
    }

    @Override
    public synchronized void delete(java.util.List<String> itemIds) {
        ensureOpen();
        if (itemIds == null || itemIds.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM inbox_item WHERE id = ?")) {
            for (String itemId : itemIds) {
                statement.setString(1, itemId);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException failure) {
            throw new IllegalStateException("删除 Inbox item 失败", failure);
        }
    }

    @Override
    public synchronized boolean hasUnackedFrozenForSource(String source) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM inbox_item
                WHERE source = ? AND status = 'AVAILABLE' AND frozen_revision > 0
                LIMIT 1
                """)) {
            statement.setString(1, source);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 Inbox item 失败", failure);
        }
    }

    @Override
    public synchronized List<InboxItem> items() {
        ensureOpen();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM inbox_item ORDER BY created_at")) {
            List<InboxItem> items = new ArrayList<>();
            while (result.next()) {
                items.add(mapItem(result));
            }
            return List.copyOf(items);
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 Inbox items 失败", failure);
        }
    }

    @Override
    public synchronized int purge(Instant cutoff, int maxItems) {
        ensureOpen();
        int removed = 0;
        try {
            if (cutoff != null) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM inbox_item WHERE status = 'ACKED' AND updated_at < ?
                        """)) {
                    statement.setString(1, cutoff.toString());
                    removed += statement.executeUpdate();
                }
            }
            int total = countItems();
            if (total > maxItems) {
                int excess = total - maxItems;
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM inbox_item WHERE id IN (
                          SELECT id FROM inbox_item WHERE status = 'ACKED'
                          ORDER BY updated_at ASC LIMIT ?
                        )
                        """)) {
                    statement.setInt(1, excess);
                    removed += statement.executeUpdate();
                }
            }
            return removed;
        } catch (SQLException failure) {
            throw new IllegalStateException("清理 Inbox item 失败", failure);
        }
    }

    private int countItems() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) AS c FROM inbox_item")) {
            return result.next() ? result.getInt("c") : 0;
        }
    }

    @Override
    public synchronized long panelRevision() {
        return meta("panel_revision");
    }

    @Override
    public synchronized void savePanelRevision(long revision) {
        setMeta("panel_revision", revision);
    }

    @Override
    public synchronized long ackedRevision() {
        return meta("acked_revision");
    }

    @Override
    public synchronized void saveAckedRevision(long revision) {
        setMeta("acked_revision", revision);
    }

    @Override
    public synchronized long pushRevision() {
        return meta("push_revision");
    }

    @Override
    public synchronized void savePushRevision(long revision) {
        setMeta("push_revision", revision);
    }

    @Override
    public synchronized String state(String key) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM inbox_state WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : "";
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 Inbox state 失败", failure);
        }
    }

    @Override
    public synchronized void setState(String key, String value) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO inbox_state (key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """)) {
            statement.setString(1, key);
            statement.setString(2, value == null ? "" : value);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("写入 Inbox state 失败", failure);
        }
    }

    @Override
    public synchronized boolean ping() {
        ensureOpen();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            return result.next();
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
            throw new IllegalStateException("关闭 SQLite Inbox 失败", failure);
        }
    }

    private long meta(String key) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM inbox_meta WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 Inbox meta 失败", failure);
        }
    }

    private void setMeta(String key, long value) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO inbox_meta (key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """)) {
            statement.setString(1, key);
            statement.setLong(2, value);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("写入 Inbox meta 失败", failure);
        }
    }

    private static void ensureColumn(Statement statement, String table, String column,
                                     String definition) throws SQLException {
        try (ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    return;
                }
            }
        }
        statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Inbox store 已关闭");
        }
    }

    private static InboxItem mapItem(ResultSet result) throws SQLException {
        String source = result.getString("source");
        String sourceLabel = result.getString("source_label");
        String entriesJson = result.getString("entries_json");
        List<InboxEntry> entries = List.of();
        if (entriesJson != null && !entriesJson.isBlank()) {
            try {
                entries = JSON.readValue(entriesJson, ENTRIES);
            } catch (Exception ignored) {
                entries = List.of();
            }
        }
        Instant createdAt = Instant.parse(result.getString("created_at"));
        String updatedRaw = result.getString("updated_at");
        return new InboxItem(
                result.getString("id"),
                source,
                sourceLabel == null || sourceLabel.isBlank() ? source : sourceLabel,
                result.getString("summary"),
                entries,
                result.getInt("total_messages"),
                result.getLong("event_revision"),
                result.getInt("priority"),
                ItemStatus.parse(result.getString("status")),
                Instant.parse(result.getString("available_at")),
                createdAt,
                updatedRaw == null ? createdAt : Instant.parse(updatedRaw),
                result.getInt("revision"),
                result.getLong("frozen_revision"),
                result.getInt("continued") != 0);
    }

}
