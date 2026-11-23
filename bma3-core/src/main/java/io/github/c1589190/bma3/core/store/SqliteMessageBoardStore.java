package io.github.c1589190.bma3.core.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.bma3.core.account.ConsumerId;
import io.github.c1589190.bma3.core.message.Delivery;
import io.github.c1589190.bma3.core.message.DeliveryStatus;
import io.github.c1589190.bma3.core.message.Direction;
import io.github.c1589190.bma3.core.message.InboundMessage;
import io.github.c1589190.bma3.core.message.Message;
import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.source.SourceAddress;
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
                ensureColumn(statement, "chat_message", "delivery_status", "TEXT");
                ensureColumn(statement, "chat_message", "delivery_operation_id", "TEXT");
                ensureColumn(statement, "chat_message", "delivery_part_index", "INTEGER");
                ensureColumn(statement, "chat_message", "delivery_part_total", "INTEGER");
                ensureColumn(statement, "chat_message", "delivery_error_code", "TEXT");
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_chat_message_source_seq
                        ON chat_message(source, local_seq)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_chat_message_external
                        ON chat_message(source, external_message_id)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_chat_message_delivery
                        ON chat_message(direction, delivery_status)
                        """);
                // Repeatable legacy backfill: only rows that never had a delivery state (NULL) are
                // labelled uncertain. local_seq, external ids and every other column stay intact.
                // PENDING rows are NOT recovery candidates here; recovery is an explicit call.
                statement.execute("""
                        UPDATE chat_message
                        SET delivery_status = 'LEGACY_UNKNOWN'
                        WHERE direction = 'OUTBOUND' AND delivery_status IS NULL
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
        String text = textOf(inbound);
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
    public synchronized AppendResult beginOutbound(InboundMessage message, String operationId,
                                                   int partIndex, int partTotal) {
        Objects.requireNonNull(message, "message");
        if (message.direction() != Direction.OUTBOUND) {
            throw new IllegalArgumentException("只有出站消息可以开始发送");
        }
        // Validates the operation id and part metadata before touching the database.
        Delivery delivery = new Delivery(operationId, partIndex, partTotal,
                DeliveryStatus.PENDING, null);
        ensureOpen();
        String messageKey = messageKey(message);
        String text = textOf(message);
        try {
            connection.setAutoCommit(false);
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO chat_message (
                      message_key, source, account_id, direction, message_id, at, text,
                      sender_id, sender_name, sender_card, sender_role, segments_json,
                      external_message_id, delivery_status, delivery_operation_id,
                      delivery_part_index, delivery_part_total, delivery_error_code
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(message_key) DO NOTHING
                    """)) {
                statement.setString(1, messageKey);
                statement.setString(2, message.source().format());
                statement.setString(3, message.source().accountId());
                statement.setString(4, message.direction().name());
                statement.setString(5, message.messageId());
                statement.setString(6, message.at().toString());
                statement.setString(7, text);
                statement.setString(8, message.senderId());
                statement.setString(9, message.senderName());
                statement.setString(10, message.senderCard());
                statement.setString(11, message.senderRole());
                statement.setString(12, segmentsJson(message.segments()));
                statement.setString(13, externalIdFor(message));
                statement.setString(14, delivery.status().name());
                statement.setString(15, delivery.operationId());
                statement.setInt(16, delivery.partIndex());
                statement.setInt(17, delivery.partTotal());
                statement.setString(18, delivery.errorCode());
                inserted = statement.executeUpdate();
            }
            if (inserted == 0) {
                // Fail closed: returning the existing row would let this caller finalize another
                // operation's delivery state. Task 2 generates a fresh message key per send.
                connection.rollback();
                throw new IllegalStateException("出站消息键重复，拒绝开始发送: key=" + messageKey);
            }
            long seq;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) {
                seq = result.next() ? result.getLong(1) : 0L;
            }
            connection.commit();
            return new AppendResult(new Message(seq, message, "", delivery), true);
        } catch (SQLException failure) {
            rollbackQuietly();
            throw new IllegalStateException("写入 SQLite 出站消息失败", failure);
        } finally {
            resetAutoCommit();
        }
    }

    @Override
    public synchronized boolean finishOutbound(long localSeq, DeliveryStatus status,
                                               String externalMessageId, String errorCode) {
        if (localSeq < 0) {
            throw new IllegalArgumentException("localSeq 不能为负");
        }
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        if (!status.terminal()) {
            throw new IllegalArgumentException("只能终止 PENDING 出站行: " + status);
        }
        String normalizedExternalId = externalMessageId == null || externalMessageId.isBlank()
                ? null : externalMessageId.trim();
        String normalizedErrorCode = Delivery.normalizeErrorCode(errorCode);
        if (normalizedExternalId != null && status != DeliveryStatus.SENT) {
            throw new IllegalArgumentException("只有 SENT 可以携带外部消息 id");
        }
        if (normalizedErrorCode != null && status == DeliveryStatus.SENT) {
            throw new IllegalArgumentException("SENT 不能携带错误类别");
        }
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat_message
                SET delivery_status = ?,
                    external_message_id = COALESCE(?, external_message_id),
                    delivery_error_code = ?
                WHERE local_seq = ? AND direction = ? AND delivery_status = ?
                """)) {
            statement.setString(1, status.name());
            statement.setString(2, normalizedExternalId);
            statement.setString(3, normalizedErrorCode);
            statement.setLong(4, localSeq);
            statement.setString(5, Direction.OUTBOUND.name());
            statement.setString(6, DeliveryStatus.PENDING.name());
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new IllegalStateException("回写出站发送状态失败", failure);
        }
    }

    @Override
    public synchronized int recoverPendingOutbound() {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat_message
                SET delivery_status = ?, delivery_error_code = ?
                WHERE direction = ? AND delivery_status = ?
                """)) {
            statement.setString(1, DeliveryStatus.UNKNOWN.name());
            statement.setString(2, "PROCESS_INTERRUPTED");
            statement.setString(3, Direction.OUTBOUND.name());
            statement.setString(4, DeliveryStatus.PENDING.name());
            return statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("恢复未决出站状态失败", failure);
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
                       segments_json, external_message_id,
                       delivery_status, delivery_operation_id, delivery_part_index,
                       delivery_part_total, delivery_error_code
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
                       segments_json, external_message_id,
                       delivery_status, delivery_operation_id, delivery_part_index,
                       delivery_part_total, delivery_error_code
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
    public synchronized Optional<Message> findInbound(SourceAddress source, String messageId) {
        Objects.requireNonNull(source, "source");
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT local_seq, message_key, source, account_id, direction,
                       message_id, at, text, sender_id, sender_name, sender_card, sender_role,
                       segments_json, external_message_id,
                       delivery_status, delivery_operation_id, delivery_part_index,
                       delivery_part_total, delivery_error_code
                FROM chat_message
                WHERE source = ? AND direction = ? AND message_id = ?
                ORDER BY local_seq DESC
                LIMIT 1
                """)) {
            statement.setString(1, source.format());
            statement.setString(2, Direction.INBOUND.name());
            statement.setString(3, messageId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("按入站消息 id 查询失败", failure);
        }
    }

    @Override
    public synchronized boolean hasInbound(SourceAddress source) {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM chat_message
                WHERE source = ? AND direction = ?
                LIMIT 1
                """)) {
            statement.setString(1, source.format());
            statement.setString(2, Direction.INBOUND.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("查写入站观察失败", failure);
        }
    }

    @Override
    public synchronized long maxLocalSeq(SourceAddress source) {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(local_seq), 0) FROM chat_message WHERE source = ?")) {
            statement.setString(1, source.format());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 source 最大 local seq 失败", failure);
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
                       segments_json, external_message_id,
                       delivery_status, delivery_operation_id, delivery_part_index,
                       delivery_part_total, delivery_error_code
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
    public synchronized boolean hasLocalSeq(long localSeq) {
        ensureOpen();
        if (localSeq <= 0) {
            return false;
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT 1 FROM chat_message WHERE local_seq = ?")) {
            select.setLong(1, localSeq);
            try (ResultSet result = select.executeQuery()) {
                return result.next();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("查询消息板 local_seq 失败", failure);
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
                       segments_json, external_message_id,
                       delivery_status, delivery_operation_id, delivery_part_index,
                       delivery_part_total, delivery_error_code
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
        Direction direction = Direction.valueOf(result.getString("direction"));
        List<Segment> segments = segments(result.getString("segments_json"),
                result.getString("text"));
        InboundMessage inbound = new InboundMessage(
                SourceAddress.parse(sourceValue),
                result.getString("message_id"),
                Instant.parse(result.getString("at")),
                segments,
                direction,
                result.getString("sender_id"),
                result.getString("sender_name"),
                result.getString("sender_card"),
                result.getString("sender_role"));
        return new Message(result.getLong("local_seq"), inbound,
                result.getString("external_message_id"), delivery(result, direction));
    }

    /**
     * Reads the delivery metadata of an outbound row. Inbound rows are not applicable, legacy
     * outbound rows are already labelled by the constructor backfill, and a SQL {@code NULL} status
     * is the supported legacy-append intermediate state. Any other unreadable value is corruption
     * and fails closed with the row sequence only, so no body text can leak.
     */
    private static Delivery delivery(ResultSet result, Direction direction)
            throws SQLException {
        if (direction != Direction.OUTBOUND) {
            return null;
        }
        String status = result.getString("delivery_status");
        if (status == null) {
            // SQL NULL is the still-supported legacy-append intermediate state: no delivery state
            // was ever written for this row. An empty/unknown value is corruption, not absence.
            return null;
        }
        long localSeq = result.getLong("local_seq");
        try {
            int partIndex = result.getInt("delivery_part_index");
            if (result.wasNull()) {
                partIndex = 0;
            }
            int partTotal = result.getInt("delivery_part_total");
            if (result.wasNull()) {
                partTotal = 0;
            }
            return new Delivery(result.getString("delivery_operation_id"), partIndex, partTotal,
                    DeliveryStatus.valueOf(status), result.getString("delivery_error_code"));
        } catch (IllegalArgumentException corrupt) {
            throw new IllegalStateException("消息板出站状态元数据损坏: seq=" + localSeq);
        }
    }

    private static String textOf(InboundMessage inbound) {
        return inbound.segments().stream()
                .map(Segment::text)
                .filter(value -> value != null && !value.isEmpty())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
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
