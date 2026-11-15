package io.mosire.bma3.scheduler;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQLite persistence for schedules and their run history ({@code schedule.db}).
 *
 * <p>The {@code (schedule_id, due_at)} unique index is what makes "at most one notification per due
 * time" true across restarts and double ticks (invariant I-C2).</p>
 */
public final class ScheduleStore implements AutoCloseable {

    private final Connection connection;

    public ScheduleStore(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS schedule (
                          id TEXT PRIMARY KEY,
                          kind TEXT NOT NULL,
                          spec TEXT NOT NULL,
                          text TEXT NOT NULL,
                          priority INTEGER NOT NULL,
                          enabled INTEGER NOT NULL,
                          next_run_at INTEGER,
                          last_run_at INTEGER,
                          missed_policy TEXT NOT NULL,
                          created_at INTEGER NOT NULL,
                          updated_at INTEGER NOT NULL
                        )""");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS run (
                          id TEXT PRIMARY KEY,
                          schedule_id TEXT NOT NULL,
                          due_at INTEGER NOT NULL,
                          fired_at INTEGER,
                          status TEXT NOT NULL,
                          detail TEXT NOT NULL
                        )""");
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS run_unique
                        ON run(schedule_id, due_at)""");
            }
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("打开 schedule.db 失败: " + file, failure);
        }
    }

    public synchronized void save(Schedule schedule) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO schedule (id, kind, spec, text, priority, enabled, next_run_at,
                  last_run_at, missed_policy, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                  kind=excluded.kind, spec=excluded.spec, text=excluded.text,
                  priority=excluded.priority, enabled=excluded.enabled,
                  next_run_at=excluded.next_run_at, last_run_at=excluded.last_run_at,
                  missed_policy=excluded.missed_policy, updated_at=excluded.updated_at""")) {
            statement.setString(1, schedule.id());
            statement.setString(2, schedule.kind());
            statement.setString(3, schedule.spec());
            statement.setString(4, schedule.text());
            statement.setInt(5, schedule.priority());
            statement.setInt(6, schedule.enabled() ? 1 : 0);
            statement.setObject(7, epochMillis(schedule.nextRunAt()));
            statement.setObject(8, epochMillis(schedule.lastRunAt()));
            statement.setString(9, schedule.missedPolicy());
            statement.setLong(10, schedule.createdAt().toEpochMilli());
            statement.setLong(11, schedule.updatedAt().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("保存 schedule 失败: " + schedule.id(), failure);
        }
    }

    public synchronized Optional<Schedule> get(String id) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM schedule WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 schedule 失败: " + id, failure);
        }
    }

    public synchronized List<Schedule> all() {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM schedule ORDER BY created_at")) {
            List<Schedule> schedules = new ArrayList<>();
            while (rows.next()) {
                schedules.add(read(rows));
            }
            return schedules;
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 schedule 列表失败", failure);
        }
    }

    public synchronized void delete(String id) {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM schedule WHERE id=?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("删除 schedule 失败: " + id, failure);
        }
    }

    /** @return true when this {@code (scheduleId, dueAt)} pair is newly recorded */
    public synchronized boolean recordRun(String runId, String scheduleId, Instant dueAt,
                                          Instant firedAt) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO run (id, schedule_id, due_at, fired_at, status, detail)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT(schedule_id, due_at) DO NOTHING""")) {
            statement.setString(1, runId);
            statement.setString(2, scheduleId);
            statement.setLong(3, dueAt.toEpochMilli());
            statement.setLong(4, firedAt.toEpochMilli());
            statement.setString(5, "FIRED");
            statement.setString(6, "");
            return statement.executeUpdate() > 0;
        } catch (SQLException failure) {
            throw new IllegalStateException("记录 schedule run 失败: " + scheduleId, failure);
        }
    }

    public synchronized void updateRun(String runId, String status, String detail) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE run SET status=?, detail=? WHERE id=?")) {
            statement.setString(1, status);
            statement.setString(2, detail == null ? "" : detail);
            statement.setString(3, runId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("更新 schedule run 失败: " + runId, failure);
        }
    }

    public synchronized List<Map<String, Object>> runs(int limit) {
        int capped = Math.max(1, Math.min(500, limit));
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM run ORDER BY fired_at DESC LIMIT ?")) {
            statement.setInt(1, capped);
            try (ResultSet rows = statement.executeQuery()) {
                List<Map<String, Object>> runs = new ArrayList<>();
                while (rows.next()) {
                    Map<String, Object> run = new LinkedHashMap<>();
                    run.put("runId", rows.getString("id"));
                    run.put("scheduleId", rows.getString("schedule_id"));
                    run.put("dueAt", Instant.ofEpochMilli(rows.getLong("due_at")).toString());
                    long firedAt = rows.getLong("fired_at");
                    run.put("firedAt", Instant.ofEpochMilli(firedAt).toString());
                    run.put("status", rows.getString("status"));
                    run.put("detail", rows.getString("detail"));
                    runs.add(run);
                }
                return runs;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 schedule run 失败", failure);
        }
    }

    public synchronized boolean ping() {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT 1")) {
            return rows.next();
        } catch (SQLException failure) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Best effort on shutdown.
        }
    }

    private static Schedule read(ResultSet rows) {
        return new Schedule(
                text(rows, "id"),
                text(rows, "kind"),
                text(rows, "spec"),
                text(rows, "text"),
                integer(rows, "priority"),
                integer(rows, "enabled") != 0,
                instant(rows, "next_run_at"),
                instant(rows, "last_run_at"),
                text(rows, "missed_policy"),
                instant(rows, "created_at"),
                instant(rows, "updated_at"));
    }

    private static String text(ResultSet rows, String column) {
        try {
            String value = rows.getString(column);
            return value == null ? "" : value;
        } catch (SQLException failure) {
            throw new IllegalStateException("读取列失败: " + column, failure);
        }
    }

    private static int integer(ResultSet rows, String column) {
        try {
            return rows.getInt(column);
        } catch (SQLException failure) {
            throw new IllegalStateException("读取列失败: " + column, failure);
        }
    }

    private static Instant instant(ResultSet rows, String column) {
        try {
            long value = rows.getLong(column);
            return rows.wasNull() ? null : Instant.ofEpochMilli(value);
        } catch (SQLException failure) {
            throw new IllegalStateException("读取列失败: " + column, failure);
        }
    }

    private static Long epochMillis(Instant value) {
        return value == null ? null : value.toEpochMilli();
    }
}
