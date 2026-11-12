package io.mosire.bma3.qqconnect.directory;

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
import java.util.Optional;

/**
 * Local QQ directory: group names, member cards, roles and titles.
 *
 * <p>This is a cache, not the fact source: the message board remains authoritative for messages.
 * The directory exists so the inbox can label a group by name and a sender by their group card
 * without an API call per message, and so {@code qq_search} can resolve a nickname to the stable
 * QQ number that priority rules use.</p>
 */
public final class QqDirectoryStore implements AutoCloseable {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("SQLite JDBC 驱动缺失", failure);
        }
    }

    public record Group(String accountId, String groupId, String name, int memberCount,
                        Instant updatedAt) {
    }

    public record Member(String accountId, String groupId, String qq, String nickname,
                         String card, String role, String title, Instant titleExpireAt,
                         Instant updatedAt) {

        public Member {
            nickname = nickname == null ? "" : nickname;
            card = card == null ? "" : card;
            role = role == null ? "" : role;
            title = title == null ? "" : title;
        }

        public String displayName() {
            return card.isBlank() ? nickname : card;
        }
    }

    private final Connection connection;
    private boolean closed;

    public QqDirectoryStore(Path databaseFile) {
        try {
            Path absolute = databaseFile.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS qq_group (
                          account_id TEXT NOT NULL,
                          group_id TEXT NOT NULL,
                          name TEXT NOT NULL,
                          member_count INTEGER NOT NULL DEFAULT 0,
                          updated_at TEXT NOT NULL,
                          PRIMARY KEY (account_id, group_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS qq_member (
                          account_id TEXT NOT NULL,
                          group_id TEXT NOT NULL,
                          qq TEXT NOT NULL,
                          nickname TEXT NOT NULL DEFAULT '',
                          card TEXT NOT NULL DEFAULT '',
                          role TEXT NOT NULL DEFAULT '',
                          title TEXT NOT NULL DEFAULT '',
                          title_expire_at TEXT,
                          updated_at TEXT NOT NULL,
                          PRIMARY KEY (account_id, group_id, qq)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_qq_group_name ON qq_group(name)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_qq_member_name ON qq_member(nickname, card)
                        """);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("打开 QQ directory 失败", failure);
        }
    }

    public synchronized void upsertGroup(String accountId, String groupId, String name,
                                         int memberCount) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO qq_group (account_id, group_id, name, member_count, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(account_id, group_id) DO UPDATE SET
                  name = excluded.name,
                  member_count = excluded.member_count,
                  updated_at = excluded.updated_at
                """)) {
            statement.setString(1, text(accountId));
            statement.setString(2, text(groupId));
            statement.setString(3, text(name));
            statement.setInt(4, Math.max(0, memberCount));
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("写入 QQ 群目录失败", failure);
        }
    }

    public synchronized void upsertMember(Member member) {
        if (member == null) {
            return;
        }
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO qq_member (account_id, group_id, qq, nickname, card, role, title,
                  title_expire_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(account_id, group_id, qq) DO UPDATE SET
                  nickname = excluded.nickname,
                  card = excluded.card,
                  role = excluded.role,
                  title = excluded.title,
                  title_expire_at = excluded.title_expire_at,
                  updated_at = excluded.updated_at
                """)) {
            bindMember(statement, member);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("写入 QQ 成员目录失败", failure);
        }
    }

    public synchronized void upsertMembers(List<Member> members) {
        if (members == null || members.isEmpty()) {
            return;
        }
        ensureOpen();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO qq_member (account_id, group_id, qq, nickname, card, role, title,
                      title_expire_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(account_id, group_id, qq) DO UPDATE SET
                      nickname = excluded.nickname,
                      card = excluded.card,
                      role = excluded.role,
                      title = excluded.title,
                      title_expire_at = excluded.title_expire_at,
                      updated_at = excluded.updated_at
                    """)) {
                for (Member member : members) {
                    if (member == null) {
                        continue;
                    }
                    bindMember(statement, member);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        } catch (SQLException failure) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // keep original failure
            }
            throw new IllegalStateException("批量写入 QQ 成员目录失败", failure);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // best effort
            }
        }
    }

    public synchronized Optional<Group> group(String accountId, String groupId) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM qq_group WHERE account_id = ? AND group_id = ?
                """)) {
            statement.setString(1, text(accountId));
            statement.setString(2, text(groupId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapGroup(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 QQ 群目录失败", failure);
        }
    }

    public synchronized List<Group> groups(String accountId, int limit) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM qq_group WHERE account_id = ?
                ORDER BY updated_at DESC LIMIT ?
                """)) {
            statement.setString(1, text(accountId));
            statement.setInt(2, clamp(limit));
            return listGroups(statement);
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 QQ 群目录失败", failure);
        }
    }

    public synchronized List<Group> searchGroups(String query, int limit) {
        ensureOpen();
        String needle = "%" + text(query) + "%";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM qq_group
                WHERE name LIKE ? OR group_id LIKE ?
                ORDER BY updated_at DESC LIMIT ?
                """)) {
            statement.setString(1, needle);
            statement.setString(2, needle);
            statement.setInt(3, clamp(limit));
            return listGroups(statement);
        } catch (SQLException failure) {
            throw new IllegalStateException("搜索 QQ 群目录失败", failure);
        }
    }

    public synchronized Optional<Member> member(String accountId, String groupId, String qq) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM qq_member
                WHERE account_id = ? AND group_id = ? AND qq = ?
                """)) {
            statement.setString(1, text(accountId));
            statement.setString(2, text(groupId));
            statement.setString(3, text(qq));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapMember(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 QQ 成员目录失败", failure);
        }
    }

    public synchronized List<Member> members(String accountId, String groupId, int offset,
                                             int limit) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM qq_member
                WHERE account_id = ? AND group_id = ?
                ORDER BY CASE role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END, updated_at DESC
                LIMIT ? OFFSET ?
                """)) {
            statement.setString(1, text(accountId));
            statement.setString(2, text(groupId));
            statement.setInt(3, clamp(limit));
            statement.setInt(4, Math.max(0, offset));
            return listMembers(statement);
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 QQ 成员目录失败", failure);
        }
    }

    public synchronized int memberCount(String accountId, String groupId) {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM qq_member WHERE account_id = ? AND group_id = ?
                """)) {
            statement.setString(1, text(accountId));
            statement.setString(2, text(groupId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("统计 QQ 成员目录失败", failure);
        }
    }

    public synchronized List<Member> searchMembers(String query, int limit) {
        ensureOpen();
        String needle = "%" + text(query) + "%";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM qq_member
                WHERE nickname LIKE ? OR card LIKE ? OR qq LIKE ? OR title LIKE ?
                ORDER BY updated_at DESC LIMIT ?
                """)) {
            statement.setString(1, needle);
            statement.setString(2, needle);
            statement.setString(3, needle);
            statement.setString(4, needle);
            statement.setInt(5, clamp(limit));
            return listMembers(statement);
        } catch (SQLException failure) {
            throw new IllegalStateException("搜索 QQ 成员目录失败", failure);
        }
    }

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
            throw new IllegalStateException("关闭 QQ directory 失败", failure);
        }
    }

    private static void bindMember(PreparedStatement statement, Member member)
            throws SQLException {
        statement.setString(1, text(member.accountId()));
        statement.setString(2, text(member.groupId()));
        statement.setString(3, text(member.qq()));
        statement.setString(4, text(member.nickname()));
        statement.setString(5, text(member.card()));
        statement.setString(6, text(member.role()));
        statement.setString(7, text(member.title()));
        statement.setString(8, member.titleExpireAt() == null
                ? null : member.titleExpireAt().toString());
        statement.setString(9, (member.updatedAt() == null
                ? Instant.now() : member.updatedAt()).toString());
    }

    private static List<Group> listGroups(PreparedStatement statement) throws SQLException {
        List<Group> groups = new ArrayList<>();
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                groups.add(mapGroup(result));
            }
        }
        return List.copyOf(groups);
    }

    private static List<Member> listMembers(PreparedStatement statement) throws SQLException {
        List<Member> members = new ArrayList<>();
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                members.add(mapMember(result));
            }
        }
        return List.copyOf(members);
    }

    private static Group mapGroup(ResultSet result) throws SQLException {
        return new Group(result.getString("account_id"), result.getString("group_id"),
                result.getString("name"), result.getInt("member_count"),
                Instant.parse(result.getString("updated_at")));
    }

    private static Member mapMember(ResultSet result) throws SQLException {
        String expire = result.getString("title_expire_at");
        return new Member(result.getString("account_id"), result.getString("group_id"),
                result.getString("qq"), result.getString("nickname"), result.getString("card"),
                result.getString("role"), result.getString("title"),
                expire == null || expire.isBlank() ? null : Instant.parse(expire),
                Instant.parse(result.getString("updated_at")));
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(500, limit));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("QQ directory 已关闭");
        }
    }
}
