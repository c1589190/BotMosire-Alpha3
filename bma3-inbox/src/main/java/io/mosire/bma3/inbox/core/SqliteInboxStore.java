package io.mosire.bma3.inbox.core;

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

/** SQLite persistence for one inbox. */
public final class SqliteInboxStore implements InboxStore {

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
                          summary TEXT NOT NULL,
                          priority INTEGER NOT NULL,
                          status TEXT NOT NULL,
                          available_at TEXT NOT NULL,
                          created_at TEXT NOT NULL,
                          revision INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS inbox_delivery (
                          id TEXT PRIMARY KEY,
                          item_ids TEXT NOT NULL,
                          text TEXT NOT NULL,
                          important INTEGER NOT NULL,
                          created_at TEXT NOT NULL,
                          acked_at TEXT,
                          attempts INTEGER NOT NULL,
                          state TEXT NOT NULL
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
                INSERT INTO inbox_item (id, source, summary, priority, status, available_at,
                  created_at, revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, item.id());
            statement.setString(2, item.source());
            statement.setString(3, item.summary());
            statement.setInt(4, item.priority());
            statement.setString(5, item.status().name());
            statement.setString(6, item.availableAt().toString());
            statement.setString(7, item.createdAt().toString());
            statement.setInt(8, item.revision());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("写入 Inbox item 失败", failure);
        }
    }

    @Override
    public synchronized void update(InboxItem item) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE inbox_item SET source = ?, summary = ?, priority = ?, status = ?,
                  available_at = ?, created_at = ?, revision = ? WHERE id = ?
                """)) {
            statement.setString(1, item.source());
            statement.setString(2, item.summary());
            statement.setInt(3, item.priority());
            statement.setString(4, item.status().name());
            statement.setString(5, item.availableAt().toString());
            statement.setString(6, item.createdAt().toString());
            statement.setInt(7, item.revision());
            statement.setString(8, item.id());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("更新 Inbox item 失败", failure);
        }
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
                WHERE source = ? AND status = 'AVAILABLE'
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
    public synchronized List<InboxItem> items() {
        ensureOpen();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM inbox_item ORDER BY created_at")) {
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
    public synchronized void saveDelivery(Delivery delivery) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO inbox_delivery (id, item_ids, text, important, created_at,
                  acked_at, attempts, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET item_ids=excluded.item_ids, text=excluded.text,
                  important=excluded.important, created_at=excluded.created_at,
                  acked_at=excluded.acked_at, attempts=excluded.attempts, state=excluded.state
                """)) {
            statement.setString(1, delivery.id());
            statement.setString(2, String.join(",", delivery.itemIds()));
            statement.setString(3, delivery.text());
            statement.setInt(4, delivery.important() ? 1 : 0);
            statement.setString(5, delivery.createdAt().toString());
            statement.setString(6, delivery.ackedAt() == null ? null : delivery.ackedAt().toString());
            statement.setInt(7, delivery.attempts());
            statement.setString(8, delivery.state().name());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("写入 Inbox delivery 失败", failure);
        }
    }

    @Override
    public synchronized Optional<Delivery> delivery(String id) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM inbox_delivery WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapDelivery(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 Inbox delivery 失败", failure);
        }
    }

    @Override
    public synchronized List<Delivery> deliveries() {
        ensureOpen();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM inbox_delivery ORDER BY created_at")) {
            List<Delivery> deliveries = new ArrayList<>();
            while (result.next()) {
                deliveries.add(mapDelivery(result));
            }
            return List.copyOf(deliveries);
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 Inbox deliveries 失败", failure);
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

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Inbox store 已关闭");
        }
    }

    private static InboxItem mapItem(ResultSet result) throws SQLException {
        return new InboxItem(
                result.getString("id"),
                result.getString("source"),
                result.getString("summary"),
                result.getInt("priority"),
                ItemStatus.valueOf(result.getString("status")),
                Instant.parse(result.getString("available_at")),
                Instant.parse(result.getString("created_at")),
                result.getInt("revision"));
    }

    private static Delivery mapDelivery(ResultSet result) throws SQLException {
        String rawIds = result.getString("item_ids");
        List<String> ids = rawIds == null || rawIds.isBlank()
                ? List.of()
                : Arrays.asList(rawIds.split(","));
        String ackedAt = result.getString("acked_at");
        return new Delivery(
                result.getString("id"),
                ids,
                result.getString("text"),
                result.getInt("important") != 0,
                Instant.parse(result.getString("created_at")),
                ackedAt == null ? null : Instant.parse(ackedAt),
                result.getInt("attempts"),
                DeliveryState.valueOf(result.getString("state")));
    }
}
