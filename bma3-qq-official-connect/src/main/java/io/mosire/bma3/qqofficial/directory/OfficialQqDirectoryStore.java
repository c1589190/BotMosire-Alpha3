package io.mosire.bma3.qqofficial.directory;

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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Account-scoped local directory of observed QQ official conversations.
 *
 * <p>This is not a platform-wide group or friend list. It contains only observations,
 * explicit aliases and state supplied by events or successful API calls. Openids are opaque and
 * are never interpreted as QQ numbers. No message bodies, tokens or raw events are stored.</p>
 */
public final class OfficialQqDirectoryStore implements AutoCloseable {
    public enum State { UNKNOWN, YES, NO }

    public record Group(String accountId, String groupOpenid, String name, String alias,
                        boolean observed, State joined, State receiveMessages, Instant lastObservedAt) {
        /** Operator alias wins, followed by a known name and an intentionally short fallback. */
        public String label() {
            if (!alias.isBlank()) return alias;
            if (!name.isBlank()) return name;
            return "QQ群 · " + abbreviate(groupOpenid);
        }
    }

    public record User(String accountId, String userOpenid, String name, State friend,
                       Instant lastObservedAt) {
        public String label() {
            return name.isBlank() ? "QQ用户 · " + abbreviate(userOpenid) : name;
        }
    }

    private final Connection connection;
    private boolean closed;

    public OfficialQqDirectoryStore(Path file) {
        Objects.requireNonNull(file, "file");
        try {
            Path absolute = file.toAbsolutePath().normalize();
            if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS official_group (
                      account_id TEXT NOT NULL, group_openid TEXT NOT NULL,
                      name TEXT NOT NULL DEFAULT '', alias TEXT NOT NULL DEFAULT '',
                      observed INTEGER NOT NULL DEFAULT 0,
                      joined TEXT NOT NULL DEFAULT 'UNKNOWN', joined_at INTEGER,
                      receive_messages TEXT NOT NULL DEFAULT 'UNKNOWN', receive_at INTEGER,
                      last_observed_at INTEGER NOT NULL,
                      PRIMARY KEY(account_id, group_openid))
                    """);
                s.execute("""
                    CREATE TABLE IF NOT EXISTS official_user (
                      account_id TEXT NOT NULL, user_openid TEXT NOT NULL,
                      name TEXT NOT NULL DEFAULT '', name_at INTEGER,
                      friend TEXT NOT NULL DEFAULT 'UNKNOWN', friend_at INTEGER,
                      last_observed_at INTEGER NOT NULL,
                      PRIMARY KEY(account_id, user_openid))
                    """);
            }
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("打开官方 QQ 本地目录失败", failure);
        }
    }

    /** Remember a group seen in an event; never overwrite alias or platform state. */
    public synchronized void observeGroup(String account, String group, Instant at) {
        validateIds(account, group);
        long time = timestamp(at);
        update("""
            INSERT INTO official_group(account_id, group_openid, last_observed_at, observed)
            VALUES (?, ?, ?, 1)
            ON CONFLICT(account_id, group_openid) DO UPDATE SET
              last_observed_at = CASE WHEN observed = 0 THEN excluded.last_observed_at
                ELSE max(last_observed_at, excluded.last_observed_at) END, observed = 1
            """, account, group, time);
    }

    /** Store an operator-chosen alias without asserting the bot has actually joined the group. */
    public synchronized void setGroupAlias(String account, String group, String alias, Instant at) {
        validateIds(account, group);
        update("""
            INSERT INTO official_group(account_id, group_openid, alias, last_observed_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(account_id, group_openid) DO UPDATE SET alias = excluded.alias
            """, account, group, text(alias), timestamp(at));
    }

    /** Cache a name returned by an authorized official API call. */
    public synchronized void setGroupName(String account, String group, String name, Instant at) {
        observeGroup(account, group, at);
        update("UPDATE official_group SET name = ? WHERE account_id = ? AND group_openid = ?",
                text(name), account, group);
    }

    public synchronized void setGroupJoined(String account, String group, boolean joined, Instant at) {
        setGroupState(account, group, joined, at, true);
    }

    public synchronized void setGroupReceiveMessages(String account, String group, boolean enabled,
                                                      Instant at) {
        setGroupState(account, group, enabled, at, false);
    }

    private void setGroupState(String account, String group, boolean value, Instant at,
                               boolean membership) {
        observeGroup(account, group, at);
        String field = membership ? "joined" : "receive_messages";
        String timeField = membership ? "joined_at" : "receive_at";
        long timestamp = timestamp(at);
        update("UPDATE official_group SET " + field + " = ?, " + timeField + " = ? "
                        + "WHERE account_id = ? AND group_openid = ? AND (" + timeField
                        + " IS NULL OR " + timeField + " <= ?)",
                state(value), timestamp, account, group, timestamp);
    }

    /** Remember a C2C user; stale events may not overwrite a later known display name. */
    public synchronized void observeUser(String account, String user, String name, Instant at) {
        validateIds(account, user);
        long time = timestamp(at);
        update("""
            INSERT INTO official_user(account_id, user_openid, name, name_at, last_observed_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(account_id, user_openid) DO UPDATE SET
              name = CASE WHEN excluded.name <> '' AND
                (name_at IS NULL OR name_at <= excluded.name_at) THEN excluded.name ELSE name END,
              name_at = CASE WHEN excluded.name <> '' AND
                (name_at IS NULL OR name_at <= excluded.name_at) THEN excluded.name_at ELSE name_at END,
              last_observed_at = max(last_observed_at, excluded.last_observed_at)
            """, account, user, text(name), time, time);
    }

    public synchronized void setFriend(String account, String user, boolean friend, Instant at) {
        observeUser(account, user, "", at);
        long time = timestamp(at);
        update("""
            UPDATE official_user SET friend = ?, friend_at = ?
            WHERE account_id = ? AND user_openid = ? AND (friend_at IS NULL OR friend_at <= ?)
            """, state(friend), time, account, user, time);
    }

    public synchronized Optional<Group> group(String account, String group) {
        validateIds(account, group);
        List<Group> groups = queryGroups("SELECT * FROM official_group WHERE account_id = ? AND group_openid = ?",
                account, group);
        return groups.stream().findFirst();
    }

    /** Results are local observations/aliases, never a claim of platform-wide completeness. */
    public synchronized List<Group> groups(String account, int limit) {
        validateId(account);
        return queryGroups("SELECT * FROM official_group WHERE account_id = ? "
                + "ORDER BY last_observed_at DESC, group_openid LIMIT ?", account, limit(limit));
    }

    public synchronized Optional<User> user(String account, String user) {
        validateIds(account, user);
        List<User> users = queryUsers("SELECT * FROM official_user WHERE account_id = ? AND user_openid = ?",
                account, user);
        return users.stream().findFirst();
    }

    public synchronized List<User> users(String account, int limit) {
        validateId(account);
        return queryUsers("SELECT * FROM official_user WHERE account_id = ? "
                + "ORDER BY last_observed_at DESC, user_openid LIMIT ?", account, limit(limit));
    }

    private List<Group> queryGroups(String sql, Object... args) {
        ensureOpen();
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            bind(s, args);
            try (ResultSet rs = s.executeQuery()) {
                List<Group> out = new ArrayList<>();
                while (rs.next()) out.add(new Group(rs.getString("account_id"),
                        rs.getString("group_openid"), rs.getString("name"), rs.getString("alias"),
                        rs.getInt("observed") != 0, State.valueOf(rs.getString("joined")),
                        State.valueOf(rs.getString("receive_messages")),
                        Instant.ofEpochMilli(rs.getLong("last_observed_at"))));
                return List.copyOf(out);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取官方 QQ 群目录失败", failure);
        }
    }

    private List<User> queryUsers(String sql, Object... args) {
        ensureOpen();
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            bind(s, args);
            try (ResultSet rs = s.executeQuery()) {
                List<User> out = new ArrayList<>();
                while (rs.next()) out.add(new User(rs.getString("account_id"),
                        rs.getString("user_openid"), rs.getString("name"),
                        State.valueOf(rs.getString("friend")),
                        Instant.ofEpochMilli(rs.getLong("last_observed_at"))));
                return List.copyOf(out);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取官方 QQ 用户目录失败", failure);
        }
    }

    private void update(String sql, Object... args) {
        ensureOpen();
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            bind(s, args);
            s.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("写入官方 QQ 本地目录失败", failure);
        }
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
    }

    private static void validateIds(String account, String id) {
        validateId(account);
        validateId(id);
    }

    private static void validateId(String id) {
        if (id == null || id.isBlank() || id.length() > 512 || id.indexOf('/') >= 0
                || id.indexOf('\\') >= 0 || id.indexOf('|') >= 0
                || id.codePoints().anyMatch(c -> Character.isISOControl(c)
                    || Character.isWhitespace(c) || Character.isSpaceChar(c)
                    || Character.getType(c) == Character.FORMAT)) {
            throw new IllegalArgumentException("账号或 openid 格式无效");
        }
    }

    private static String text(String value) { return value == null ? "" : value; }
    private static long timestamp(Instant at) { return Objects.requireNonNull(at, "at").toEpochMilli(); }
    private static String state(boolean value) { return value ? "YES" : "NO"; }
    private static int limit(int limit) { return Math.max(1, Math.min(limit, 500)); }
    private static String abbreviate(String id) { return id.length() <= 8 ? id : id.substring(0, 8) + "…"; }
    private void ensureOpen() { if (closed) throw new IllegalStateException("目录已关闭"); }

    public synchronized boolean ping() {
        if (closed) return false;
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (SQLException failure) {
            return false;
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { connection.close(); } catch (SQLException failure) {
            throw new IllegalStateException("关闭官方 QQ 本地目录失败", failure);
        }
    }
}
