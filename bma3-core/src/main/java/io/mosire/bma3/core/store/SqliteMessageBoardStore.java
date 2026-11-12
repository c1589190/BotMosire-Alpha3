package io.mosire.bma3.core.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.bma3.core.account.ConsumerId;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
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
import java.util.UUID;

/**
 * SQLite message board. One row per message; local_seq is allocated per database and messages
 * are read per source address.
 *
 * <p>{@code text} stays a flat, URL-free rendering (search/legacy friendly). The structured
 * payload — sender identity, decoded segments (image/reply/at …) and the platform message id —
 * lives in dedicated columns so tools can resolve media and relations without ever putting a
 * URL into {@code text} or the Inbox.</p>
 */
public final class SqliteMessageBoardStore
        implements MessageBoardStore, ConsumerConversationStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Segment>> SEGMENTS = new TypeReference<>() {
    };

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("SQLite JDBC 驱动缺失", failure);
        }
    }

    private final Connection connection;
    private boolean closed;

    public SqliteMessageBoardStore(Path databaseFile) {
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
                        CREATE TABLE IF NOT EXISTS chat_message (
                          local_seq INTEGER PRIMARY KEY AUTOINCREMENT,
                          message_key TEXT NOT NULL UNIQUE,
                          source TEXT NOT NULL,
                          account_id TEXT NOT NULL,
                          direction TEXT NOT NULL,
                          message_id TEXT NOT NULL,
                          at TEXT NOT NULL,
                          text TEXT NOT NULL
                        )
                        """);
                ensureColumn(statement, "chat_message", "sender_id", "TEXT");
                ensureColumn(statement, "chat_message", "sender_name", "TEXT");
                ensureColumn(statement, "chat_message", "sender_card", "TEXT");
                ensureColumn(statement, "chat_message", "sender_role", "TEXT");
                ensureColumn(statement, "chat_message", "segments_json", "TEXT");
                ensureColumn(statement, "chat_message", "external_message_id", "TEXT");
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_chat_message_source_seq
                        ON chat_message(source, local_seq)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_chat_message_external
                        ON chat_message(source, external_message_id)
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS consumer_thread_state (
                          consumer_id TEXT NOT NULL,
                          source TEXT NOT NULL,
                          read_seq INTEGER NOT NULL,
                          PRIMARY KEY (consumer_id, source)
                        )
                        """);
            }
        } catch (IOException | SQLException failure) {
            throw new IllegalStateException("打开 SQLite 消息板失败", failure);
        }
    }

    @Override
    public synchronized AppendResult append(InboundMessage inbound) {
        Objects.requireNonNull(inbound, "inbound");
        ensureOpen();
        String messageKey = messageKey(inbound);
        String text = inbound.segments().stream()
                .map(Segment::text)
                .filter(value -> value != null && !value.isEmpty())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        try {
            connection.setAutoCommit(false);
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO chat_message (
                      message_key, source, account_id, direction, message_id, at, text,
                      sender_id, sender_name, sender_card, sender_role, segments_json,
                      external_message_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(message_key) DO NOTHING
                    """)) {
                statement.setString(1, messageKey);
                statement.setString(2, inbound.source().format());
                statement.setString(3, inbound.source().accountId());
                statement.setString(4, inbound.direction().name());
                statement.setString(5, inbound.messageId());
                statement.setString(6, inbound.at().toString());
                statement.setString(7, text);
                statement.setString(8, inbound.senderId());
                statement.setString(9, inbound.senderName());
                statement.setString(10, inbound.senderCard());
                statement.setString(11, inbound.senderRole());
                statement.setString(12, segmentsJson(inbound.segments()));
                statement.setString(13, externalIdFor(inbound));
                inserted = statement.executeUpdate();
            }
            if (inserted == 0) {
                Message existing = findByKey(messageKey);
                if (existing == null) {
                    throw new IllegalStateException("重复消息未找到已有记录: key=" + messageKey);
                }
                connection.rollback();
                return new AppendResult(existing, false);
            }
            long seq;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) {
                seq = result.next() ? result.getLong(1) : 0L;
            }
            connection.commit();
            return new AppendResult(new Message(seq, inbound, externalIdFor(inbound)), true);
        } catch (SQLException failure) {
            rollbackQuietly();
            throw new IllegalStateException("写入 SQLite 消息板失败", failure);
        } finally {
            resetAutoCommit();
        }
    }

    @Override
    public synchronized List<Message> read(SourceAddress source, long afterSeq, int limit) {
        Objects.requireNonNull(source, "source");
        if (afterSeq < 0L) {
            throw new IllegalArgumentException("afterSeq 不能为负");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1..1000");
        }
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT local_seq, message_key, source, account_id, direction,
                       message_id, at, text, sender_id, sender_name, sender_card, sender_role,
                       segments_json, external_message_id
                FROM chat_message
                WHERE source = ? AND local_seq > ?
                ORDER BY local_seq ASC
                LIMIT ?
                """)) {
            statement.setString(1, source.format());
            statement.setLong(2, afterSeq);
            statement.setInt(3, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<Message> messages = new ArrayList<>();
                while (result.next()) {
                    messages.add(map(result));
                }
                return List.copyOf(messages);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 SQLite 消息板失败", failure);
        }
    }

    @Override
    public synchronized List<ThreadSummary> threads(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit 必须在 1..1000");
        }
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT source, account_id, MAX(local_seq) AS last_seq,
                       MAX(at) AS last_at, COUNT(*) AS message_count
                FROM chat_message
                GROUP BY source, account_id
                ORDER BY last_seq DESC
                LIMIT ?
                """)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<ThreadSummary> threads = new ArrayList<>();
                while (result.next()) {
                    threads.add(new ThreadSummary(
                            result.getString("source"),
                            result.getString("account_id"),
                            result.getLong("last_seq"),
                            Instant.parse(result.getString("last_at")),
                            result.getLong("message_count")));
                }
                return List.copyOf(threads);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取消息板 thread 失败", failure);
        }
    }

    @Override
    public synchronized void attachExternalMessageId(long localSeq, String externalMessageId) {
        if (externalMessageId == null || externalMessageId.isBlank()) {
            return;
        }
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat_message SET external_message_id = ? WHERE local_seq = ?
                """)) {
            statement.setString(1, externalMessageId);
            statement.setLong(2, localSeq);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("回写平台消息 id 失败", failure);
        }
    }

    @Override
    public synchronized Optional<Message> findByExternalMessageId(SourceAddress source,
                                                                  String externalMessageId) {
        Objects.requireNonNull(source, "source");
        if (externalMessageId == null || externalMessageId.isBlank()) {
            return Optional.empty();
        }
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT local_seq, message_key, source, account_id, direction,
                       message_id, at, text, sender_id, sender_name, sender_card, sender_role,
                       segments_json, external_message_id
                FROM chat_message
                WHERE source = ? AND external_message_id = ?
                LIMIT 1
                """)) {
            statement.setString(1, source.format());
            statement.setString(2, externalMessageId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("按平台消息 id 查询失败", failure);
        }
    }

    @Override
    public synchronized long readSeq(ConsumerId consumer, SourceAddress source) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(source, "source");
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT read_seq FROM consumer_thread_state
                WHERE consumer_id = ? AND source = ?
                """)) {
            statement.setString(1, consumer.value());
            statement.setString(2, source.format());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 consumer 游标失败", failure);
        }
    }

    @Override
    public synchronized void ack(ConsumerId consumer, SourceAddress source, long seq) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(source, "source");
        if (seq < 0L) {
            throw new IllegalArgumentException("seq 不能为负");
        }
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO consumer_thread_state (consumer_id, source, read_seq)
                VALUES (?, ?, ?)
                ON CONFLICT(consumer_id, source) DO UPDATE SET
                  read_seq = MAX(read_seq, excluded.read_seq)
                """)) {
            statement.setString(1, consumer.value());
            statement.setString(2, source.format());
            statement.setLong(3, seq);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("推进 consumer 游标失败", failure);
        }
    }

    @Override
    public synchronized List<Message> search(String query, String sourcePrefix,
                                             java.time.Instant from, java.time.Instant to,
                                             long beforeSeq, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit 必须在 1..500");
        }
        ensureOpen();
        StringBuilder sql = new StringBuilder("""
                SELECT local_seq, message_key, source, account_id, direction,
                       message_id, at, text, sender_id, sender_name, sender_card, sender_role,
                       segments_json, external_message_id
                FROM chat_message
                WHERE (text LIKE ? OR sender_name LIKE ? OR sender_card LIKE ?)
                """);
        List<Object> parameters = new ArrayList<>();
        String needle = "%" + query.trim() + "%";
        parameters.add(needle);
        parameters.add(needle);
        parameters.add(needle);
        if (sourcePrefix != null && !sourcePrefix.isBlank()) {
            sql.append(" AND source LIKE ?");
            parameters.add(sourcePrefix.trim() + "%");
        }
        if (from != null) {
            sql.append(" AND at >= ?");
            parameters.add(from.toString());
        }
        if (to != null) {
            sql.append(" AND at <= ?");
            parameters.add(to.toString());
        }
        if (beforeSeq > 0) {
            sql.append(" AND local_seq < ?");
            parameters.add(beforeSeq);
        }
        sql.append(" ORDER BY local_seq DESC LIMIT ?");
        parameters.add(limit);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                Object value = parameters.get(index);
                if (value instanceof Long number) {
                    statement.setLong(index + 1, number);
                } else if (value instanceof Integer number) {
                    statement.setInt(index + 1, number);
                } else {
                    statement.setString(index + 1, String.valueOf(value));
                }
            }
            try (ResultSet result = statement.executeQuery()) {
                List<Message> messages = new ArrayList<>();
                while (result.next()) {
                    messages.add(map(result));
                }
                return List.copyOf(messages);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("搜索 SQLite 消息板失败", failure);
        }
    }

    @Override
    public synchronized boolean ping() {
        ensureOpen();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            return result.next();
        } catch (Exception failure) {
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
            throw new IllegalStateException("关闭 SQLite 消息板失败", failure);
        }
    }

    private Message findByKey(String messageKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT local_seq, message_key, source, account_id, direction,
                       message_id, at, text, sender_id, sender_name, sender_card, sender_role,
                       segments_json, external_message_id
                FROM chat_message WHERE message_key = ?
                """)) {
            statement.setString(1, messageKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? map(result) : null;
            }
        }
    }

    private static Message map(ResultSet result) throws SQLException {
        String sourceValue = result.getString("source");
        List<Segment> segments = segments(result.getString("segments_json"),
                result.getString("text"));
        InboundMessage inbound = new InboundMessage(
                SourceAddress.parse(sourceValue),
                result.getString("message_id"),
                Instant.parse(result.getString("at")),
                segments,
                Direction.valueOf(result.getString("direction")),
                result.getString("sender_id"),
                result.getString("sender_name"),
                result.getString("sender_card"),
                result.getString("sender_role"));
        return new Message(result.getLong("local_seq"), inbound,
                result.getString("external_message_id"));
    }

    private static List<Segment> segments(String raw, String text) {
        if (raw != null && !raw.isBlank()) {
            try {
                List<Segment> decoded = JSON.readValue(raw, SEGMENTS);
                if (decoded != null && !decoded.isEmpty()) {
                    return List.copyOf(decoded);
                }
            } catch (Exception ignored) {
                // Fall through to the flattened text so a corrupt payload never loses the row.
            }
        }
        return text == null || text.isEmpty()
                ? List.of()
                : List.of(new Segment("text", text));
    }

    private static String segmentsJson(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(segments);
        } catch (Exception failure) {
            return null;
        }
    }

    private static String externalIdFor(InboundMessage inbound) {
        if (inbound.direction() == Direction.INBOUND && !inbound.messageId().isBlank()) {
            return inbound.messageId();
        }
        return null;
    }

    private static String messageKey(InboundMessage inbound) {
        String messageId = inbound.messageId();
        if (messageId == null || messageId.isBlank()) {
            messageId = UUID.randomUUID().toString();
        }
        return inbound.source().format() + "|" + messageId;
    }

    private static void ensureColumn(Statement statement, String table, String column,
                                     String type) throws SQLException {
        try (ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    return;
                }
            }
        }
        statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("消息板已关闭");
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    private void resetAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // best effort
        }
    }
}
