package io.mosire.bma3.qqofficial.eventstore;

import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.qqofficial.event.OfficialQqEvent;
import io.mosire.bma3.qqofficial.event.OfficialQqEventType;
import io.mosire.bma3.qqofficial.event.OfficialQqSystemEvent;
import io.mosire.bma3.qqofficial.tools.QqOfficialSourceKeyer;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Durable index of observed official inbound event envelopes.
 *
 * <p>Only routing facts are stored: account id, official event id, exact raw source, optional
 * official message id, known event type, direction and observation time. Message bodies, segments,
 * attachments, credentials, auth_token values and raw payloads are never columns or values in this
 * store.</p>
 *
 * <p>Storing a reference does not automatically make it replyable. {@link Reference#replyable()}
 * and {@link #requireReplyable(String, SourceAddress, String)} apply the current official
 * scenario/event-type allowlist. Message envelope ids and observed events from other system types
 * stay available as audit facts but cannot authorize an {@code event_id} reply.</p>
 *
 * <p>The primary key is exactly {@code (account_id, event_id)}. A replay of the same event is
 * idempotent. Reusing one account/event id with a different source, message id or event type is a
 * conflict. The row is only considered replyable when it was recorded as a known inbound event
 * with a concrete official group/private raw source; unknown/blank sources are rejected instead of
 * being stored as fake replyability.</p>
 *
 * <p>This phase only provides the store. App ingest must record into it before Inbox/projection and
 * send must call {@link #requireReplyable(String, SourceAddress, String)} before accepting an
 * {@code event_id} reply. Until that wiring is complete, event-id replies must remain unavailable.</p>
 */
public final class OfficialQqEventReferenceStore implements AutoCloseable {

    private static final int MAX_ACCOUNT_CODE_POINTS = 128;
    private static final int MAX_ID_CODE_POINTS = 512;
    private static final int MAX_EVENT_TYPE_CHARS = 64;
    /**
     * Upstream event_id reply allowlist for scenarios we can parse reliably today. The official
     * docs also list INTERACTION_CREATE / C2C_MSG_RECEIVE, but this release has no reliable model
     * for them yet and therefore deliberately does not mark them replyable.
     */
    private static final Set<String> GROUP_EVENT_ID_REPLY_TYPES =
            Set.of("GROUP_ADD_ROBOT", "GROUP_MSG_RECEIVE");
    private static final Set<String> PRIVATE_EVENT_ID_REPLY_TYPES =
            Set.of("FRIEND_ADD");

    private final Connection connection;
    private final Clock clock;
    private boolean closed;

    public OfficialQqEventReferenceStore(Path file) {
        this(file, Clock.systemUTC());
    }

    public OfficialQqEventReferenceStore(Path file, Clock clock) {
        Objects.requireNonNull(file, "file");
        this.clock = Objects.requireNonNull(clock, "clock");
        try {
            Class.forName("org.sqlite.JDBC");
            Path absolute = file.toAbsolutePath().normalize();
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS official_event_reference (
                          account_id TEXT NOT NULL,
                          event_id TEXT NOT NULL,
                          source TEXT NOT NULL,
                          message_id TEXT NOT NULL DEFAULT '',
                          event_type TEXT NOT NULL,
                          direction TEXT NOT NULL,
                          observed_at TEXT NOT NULL,
                          PRIMARY KEY(account_id, event_id),
                          CHECK(direction = 'INBOUND')
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_official_event_reference_source
                        ON official_event_reference(account_id, source, event_id)
                        """);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("打开官方事件引用存储失败");
        }
    }

    /** Persists one normalized official message event. The body/segments are never read. */
    public synchronized RecordResult recordMessage(String accountId, OfficialQqEvent event) {
        ensureOpen();
        if (event == null) {
            throw failure(OfficialQqEventReferenceException.Code.UNSUPPORTED_EVENT,
                    "不能记录空的官方消息事件");
        }
        String safeAccount = requireAccountId(accountId);
        OfficialQqEventType type = requireEventType(event.eventType());
        if (!type.isMessage()) {
            throw failure(OfficialQqEventReferenceException.Code.UNSUPPORTED_EVENT,
                    "该事件类型不是官方入站消息事件");
        }
        SourceAddress source;
        try {
            source = event.source(safeAccount);
        } catch (RuntimeException invalid) {
            throw failure(OfficialQqEventReferenceException.Code.INVALID_SOURCE,
                    "官方消息事件不能构造安全 raw source");
        }
        String safeMessageId = requireMessageId(event.messageId(), true);
        return record(safeAccount, event.eventId(), type.name(), source, safeMessageId,
                Direction.INBOUND, event.receivedAt());
    }

    /**
     * Persists one official system event only when it has a concrete group/user openid that maps to
     * a raw group/private source. READY/RESUMED and unknown system events are not replyable.
     */
    public synchronized RecordResult recordSystem(String accountId,
                                                  OfficialQqSystemEvent event) {
        ensureOpen();
        if (event == null) {
            throw failure(OfficialQqEventReferenceException.Code.UNSUPPORTED_EVENT,
                    "不能记录空的官方系统事件");
        }
        String safeAccount = requireAccountId(accountId);
        OfficialQqEventType type = requireEventType(event.eventType());
        if (!type.isSystem()) {
            throw failure(OfficialQqEventReferenceException.Code.UNSUPPORTED_EVENT,
                    "该事件类型不是官方系统事件");
        }
        if (type == OfficialQqEventType.READY || type == OfficialQqEventType.RESUMED) {
            throw failure(OfficialQqEventReferenceException.Code.NOT_REPLYABLE,
                    "连接生命周期事件没有可回复的 raw source");
        }
        String target;
        String platform;
        switch (type) {
            case GROUP_ADD_ROBOT, GROUP_DEL_ROBOT, GROUP_MSG_RECEIVE, GROUP_MSG_REJECT -> {
                target = requireIdentifier(event.groupOpenid(), "groupOpenid",
                        OfficialQqEventReferenceException.Code.INVALID_SOURCE,
                        MAX_ID_CODE_POINTS);
                platform = QqOfficialSourceKeyer.GROUP_PLATFORM;
            }
            case FRIEND_ADD, FRIEND_DEL -> {
                target = requireIdentifier(event.userOpenid(), "userOpenid",
                        OfficialQqEventReferenceException.Code.INVALID_SOURCE,
                        MAX_ID_CODE_POINTS);
                platform = QqOfficialSourceKeyer.PRIVATE_PLATFORM;
            }
            default -> throw failure(OfficialQqEventReferenceException.Code.UNSUPPORTED_EVENT,
                    "该官方系统事件没有可记录的群/单聊来源");
        }
        SourceAddress source = SourceAddress.parse(
                platform + "/" + safeAccount + "/" + target);
        return record(safeAccount, event.eventId(), type.name(), source, "",
                Direction.INBOUND, event.receivedAt());
    }

    /**
     * Low-level reliable-ingest seam. It rejects non-inbound observations, unknown event types,
     * blank/unknown sources and source/channel/account mismatches before writing.
     */
    public synchronized RecordResult record(String accountId, String eventId, String eventType,
                                            SourceAddress source, String messageId,
                                            Direction direction, Instant observedAt) {
        ensureOpen();
        String safeAccount = requireAccountId(accountId);
        String safeEventId = requireEventId(eventId);
        OfficialQqEventType type = requireEventType(eventType);
        if (type == OfficialQqEventType.READY || type == OfficialQqEventType.RESUMED) {
            throw failure(OfficialQqEventReferenceException.Code.NOT_REPLYABLE,
                    "连接生命周期事件没有可回复的 raw source");
        }
        if (direction != Direction.INBOUND) {
            throw failure(OfficialQqEventReferenceException.Code.NOT_INBOUND,
                    "只能记录真实入站官方事件引用");
        }
        SourceAddress safeSource = requireSource(source, safeAccount, type);
        String safeMessageId = requireMessageId(messageId, type.isMessage());
        if (type.isSystem() && !safeMessageId.isEmpty()) {
            throw failure(OfficialQqEventReferenceException.Code.INVALID_MESSAGE_ID,
                    "官方系统事件不应带 messageId");
        }
        Instant at = observedAt == null ? clock.instant() : observedAt;
        try {
            connection.setAutoCommit(false);
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO official_event_reference(
                      account_id, event_id, source, message_id, event_type,
                      direction, observed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(account_id, event_id) DO NOTHING
                    """)) {
                statement.setString(1, safeAccount);
                statement.setString(2, safeEventId);
                statement.setString(3, safeSource.format());
                statement.setString(4, safeMessageId);
                statement.setString(5, type.name());
                statement.setString(6, Direction.INBOUND.name());
                statement.setString(7, at.toString());
                inserted = statement.executeUpdate();
            }
            Stored stored = selectExact(safeAccount, safeEventId);
            if (stored == null) {
                connection.rollback();
                throw failure(OfficialQqEventReferenceException.Code.STORAGE_ERROR,
                        "官方事件引用写入后未能读回");
            }
            if (inserted == 0 && !stored.matches(safeSource.format(), safeMessageId, type.name())) {
                connection.rollback();
                throw failure(OfficialQqEventReferenceException.Code.EVENT_REFERENCE_CONFLICT,
                        "同一账号/eventId 已被不同来源、消息或事件类型占用");
            }
            connection.commit();
            return new RecordResult(stored.toReference(), inserted != 0);
        } catch (OfficialQqEventReferenceException failure) {
            rollbackQuietly();
            throw failure;
        } catch (SQLException failure) {
            rollbackQuietly();
            throw failure(OfficialQqEventReferenceException.Code.STORAGE_ERROR,
                    "官方事件引用存储失败");
        } finally {
            resetAutoCommit();
        }
    }

    /**
     * Exact safe lookup. Invalid input is rejected. If the account/event exists but asks for a
     * different source or a non-inbound row, it fails closed instead of returning empty.
     */
    public synchronized Optional<Reference> find(String accountId, SourceAddress source,
                                                 String eventId) {
        ensureOpen();
        String safeAccount = requireAccountId(accountId);
        String safeEventId = requireEventId(eventId);
        if (source == null) {
            throw failure(OfficialQqEventReferenceException.Code.INVALID_SOURCE,
                    "source 不能为空");
        }
        SourceAddress safeSource = requireSource(source, safeAccount, null);
        Stored stored = selectExact(safeAccount, safeEventId);
        if (stored == null) {
            return Optional.empty();
        }
        if (!Direction.INBOUND.name().equals(stored.direction())) {
            throw failure(OfficialQqEventReferenceException.Code.NOT_INBOUND,
                    "该事件引用不是真实入站事件");
        }
        if (!safeSource.format().equals(stored.source())) {
            throw failure(OfficialQqEventReferenceException.Code.SOURCE_MISMATCH,
                    "事件引用不属于该 raw source");
        }
        return Optional.of(stored.toReference());
    }

    /**
     * Send-service guard for an {@code event_id} reply. It returns the durable reference only for
     * the exact account/source/event triple and only when the official scenario/event-type
     * allowlist permits it; unknown, cross-account, cross-source, non-inbound and non-whitelisted
     * references are rejected.
     */
    public Reference requireReplyable(String accountId, SourceAddress source, String eventId) {
        Optional<Reference> exact = find(accountId, source, eventId);
        if (exact.isPresent()) {
            Reference reference = exact.get();
            if (!reference.replyable()) {
                throw failure(OfficialQqEventReferenceException.Code.NOT_REPLYABLE,
                        "该官方事件类型/场景不允许 event_id 回复");
            }
            return reference;
        }
        String safeEventId = requireEventId(eventId);
        Stored other = selectAnyAccountByEventId(safeEventId);
        if (other != null) {
            throw failure(OfficialQqEventReferenceException.Code.ACCOUNT_MISMATCH,
                    "eventId 属于其他官方账号，拒绝跨账号回复");
        }
        throw failure(OfficialQqEventReferenceException.Code.UNKNOWN_EVENT_REFERENCE,
                "未观察到该官方入站 eventId");
    }

    public synchronized int count() {
        ensureOpen();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM official_event_reference")) {
            return result.next() ? result.getInt(1) : 0;
        } catch (SQLException failure) {
            throw failure(OfficialQqEventReferenceException.Code.STORAGE_ERROR,
                    "读取官方事件引用计数失败");
        }
    }

    public synchronized boolean ping() {
        if (closed) {
            return false;
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            return result.next();
        } catch (SQLException failure) {
            return false;
        }
    }

    private Stored selectExact(String accountId, String eventId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account_id, event_id, source, message_id, event_type,
                       direction, observed_at
                FROM official_event_reference
                WHERE account_id = ? AND event_id = ?
                """)) {
            statement.setString(1, accountId);
            statement.setString(2, eventId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? stored(result) : null;
            }
        } catch (SQLException failure) {
            throw failure(OfficialQqEventReferenceException.Code.STORAGE_ERROR,
                    "读取官方事件引用失败");
        }
    }

    private Stored selectAnyAccountByEventId(String eventId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account_id, event_id, source, message_id, event_type,
                       direction, observed_at
                FROM official_event_reference
                WHERE event_id = ?
                LIMIT 1
                """)) {
            statement.setString(1, eventId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? stored(result) : null;
            }
        } catch (SQLException failure) {
            throw failure(OfficialQqEventReferenceException.Code.STORAGE_ERROR,
                    "读取官方事件引用失败");
        }
    }

    private static Stored stored(ResultSet result) throws SQLException {
        return new Stored(
                result.getString("account_id"),
                result.getString("event_id"),
                result.getString("source"),
                result.getString("message_id"),
                result.getString("event_type"),
                result.getString("direction"),
                Instant.parse(result.getString("observed_at")));
    }

    private static OfficialQqEventType requireEventType(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_EVENT_TYPE_CHARS
                || !raw.equals(raw.strip())) {
            throw failure(OfficialQqEventReferenceException.Code.UNSUPPORTED_EVENT,
                    "官方事件类型无效");
        }
        OfficialQqEventType type = OfficialQqEventType.fromWire(raw);
        if (type == null) {
            throw failure(OfficialQqEventReferenceException.Code.UNSUPPORTED_EVENT,
                    "未支持的官方事件类型");
        }
        return type;
    }

    private static SourceAddress requireSource(SourceAddress source, String accountId,
                                                OfficialQqEventType type) {
        if (source == null) {
            throw failure(OfficialQqEventReferenceException.Code.INVALID_SOURCE,
                    "source 不能为空");
        }
        try {
            QqOfficialSourceKeyer.validateOfficialSource(source);
        } catch (RuntimeException invalid) {
            throw failure(OfficialQqEventReferenceException.Code.INVALID_SOURCE,
                    "source 必须是官方群/单聊 raw source");
        }
        String platform = source.platform();
        if (!QqOfficialSourceKeyer.GROUP_PLATFORM.equals(platform)
                && !QqOfficialSourceKeyer.PRIVATE_PLATFORM.equals(platform)) {
            throw failure(OfficialQqEventReferenceException.Code.INVALID_SOURCE,
                    "source 必须是官方群/单聊 raw source");
        }
        requireIdentifier(source.accountId(), "accountId",
                OfficialQqEventReferenceException.Code.INVALID_SOURCE,
                MAX_ACCOUNT_CODE_POINTS);
        requireIdentifier(source.lastSegment(), "targetOpenid",
                OfficialQqEventReferenceException.Code.INVALID_SOURCE,
                MAX_ID_CODE_POINTS);
        if (!accountId.equals(source.accountId())) {
            throw failure(OfficialQqEventReferenceException.Code.ACCOUNT_SOURCE_MISMATCH,
                    "accountId 与 source 中的账号不一致");
        }
        if (type != null && !platformFor(type).equals(platform)) {
            throw failure(OfficialQqEventReferenceException.Code.INVALID_SOURCE,
                    "事件类型与 raw source 渠道不匹配");
        }
        return source;
    }

    private static String platformFor(OfficialQqEventType type) {
        return switch (type) {
            case C2C_MESSAGE_CREATE, FRIEND_ADD, FRIEND_DEL ->
                    QqOfficialSourceKeyer.PRIVATE_PLATFORM;
            case GROUP_AT_MESSAGE_CREATE, GROUP_MESSAGE_CREATE, GROUP_ADD_ROBOT,
                    GROUP_DEL_ROBOT, GROUP_MSG_RECEIVE, GROUP_MSG_REJECT ->
                    QqOfficialSourceKeyer.GROUP_PLATFORM;
            case READY, RESUMED -> "";
        };
    }

    private static String requireAccountId(String value) {
        return requireIdentifier(value, "accountId",
                OfficialQqEventReferenceException.Code.INVALID_ACCOUNT,
                MAX_ACCOUNT_CODE_POINTS);
    }

    private static String requireEventId(String value) {
        return requireIdentifier(value, "eventId",
                OfficialQqEventReferenceException.Code.INVALID_EVENT_ID,
                MAX_ID_CODE_POINTS);
    }

    private static String requireMessageId(String value, boolean required) {
        String normalized = value == null ? "" : value;
        if (normalized.isEmpty() && !required) {
            return "";
        }
        return requireIdentifier(normalized, "messageId",
                OfficialQqEventReferenceException.Code.INVALID_MESSAGE_ID,
                MAX_ID_CODE_POINTS);
    }

    /**
     * Exact string validator. It never trims or normalizes the returned id; whitespace, controls,
     * format chars, separators and slash-like source delimiters are rejected. The caller supplies
     * the fixed code so errors never echo the value.
     */
    private static String requireIdentifier(String value, String field,
                                            OfficialQqEventReferenceException.Code code,
                                            int maxCodePoints) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw failure(code, field + " 格式无效");
        }
        if (value.codePointCount(0, value.length()) > maxCodePoints) {
            throw failure(code, field + " 格式无效");
        }
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            int type = Character.getType(codePoint);
            if (codePoint == '/' || codePoint == '\\' || codePoint == '|'
                    || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw failure(code, field + " 格式无效");
            }
            index += Character.charCount(codePoint);
        }
        return value;
    }

    private static OfficialQqEventReferenceException failure(
            OfficialQqEventReferenceException.Code code, String message) {
        return new OfficialQqEventReferenceException(code, message);
    }

    private void ensureOpen() {
        if (closed) {
            throw failure(OfficialQqEventReferenceException.Code.STORAGE_ERROR,
                    "官方事件引用存储已关闭");
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Keep the original stable failure.
        }
    }

    private void resetAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // The next operation will report a stable storage error.
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
            throw failure(OfficialQqEventReferenceException.Code.STORAGE_ERROR,
                    "关闭官方事件引用存储失败");
        }
    }

    /** Immutable routing fact returned by lookups. It never includes message content. */
    public record Reference(
            String accountId,
            String eventId,
            String source,
            String messageId,
            String eventType,
            Direction direction,
            Instant observedAt) {

        public Reference {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(source, "source");
            messageId = messageId == null ? "" : messageId;
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(observedAt, "observedAt");
        }

        /**
         * Only inbound references in the official scenario/event-type allowlist may authorize an
         * {@code event_id} reply. Message envelope ids are audit evidence only; inbound messages
         * are replied to by their real {@code msg_id}. GROUP_DEL_ROBOT, GROUP_MSG_REJECT and
         * FRIEND_DEL are also retained for audit but are not replyable.
         */
        public boolean replyable() {
            if (direction != Direction.INBOUND) {
                return false;
            }
            String platform;
            try {
                platform = SourceAddress.parse(source).platform();
            } catch (RuntimeException invalid) {
                return false;
            }
            return switch (eventType) {
                case "GROUP_ADD_ROBOT", "GROUP_MSG_RECEIVE" ->
                        GROUP_EVENT_ID_REPLY_TYPES.contains(eventType)
                                && QqOfficialSourceKeyer.GROUP_PLATFORM.equals(platform);
                case "FRIEND_ADD" ->
                        PRIVATE_EVENT_ID_REPLY_TYPES.contains(eventType)
                                && QqOfficialSourceKeyer.PRIVATE_PLATFORM.equals(platform);
                default -> false;
            };
        }
    }

    public record RecordResult(Reference reference, boolean created) {
        public RecordResult {
            Objects.requireNonNull(reference, "reference");
        }
    }

    private record Stored(String accountId, String eventId, String source, String messageId,
                          String eventType, String direction, Instant observedAt) {
        private boolean matches(String expectedSource, String expectedMessageId,
                                String expectedEventType) {
            return source.equals(expectedSource)
                    && messageId.equals(expectedMessageId)
                    && eventType.equals(expectedEventType)
                    && Direction.INBOUND.name().equals(direction);
        }

        private Reference toReference() {
            return new Reference(accountId, eventId, source, messageId, eventType,
                    Direction.valueOf(direction), observedAt);
        }
    }
}
