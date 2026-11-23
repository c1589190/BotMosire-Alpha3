package io.github.c1589190.bma3.app.channel;

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
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * QQ 双渠道的逻辑来源、绑定、身份映射、注入开关与审计 registry。
 *
 * <p>该类只持久化控制面事实，不保存消息正文、媒体、token 或任何凭据，不删除消息板或 Inbox
 * 事实。它使用独立 SQLite 数据库；每次写操作在事务内完成，并通过主键/唯一索引保证：</p>
 *
 * <ul>
 *   <li>一个 raw source 同一时刻最多属于一个 logical thread；</li>
 *   <li>一个 logical thread 每个 channel 同一时刻最多一个 binding；</li>
 *   <li>同 channel/scope 下身份映射不产生多义匹配。</li>
 * </ul>
 *
 * <p>Account 是否存在由 {@link KnownAccountVerifier} 注入，raw source 是否已经观察过由
 * {@link SourceObservationProvider} 注入。registry 不会用本地空目录或空表推断账号/来源存在。</p>
 */
public final class QqChannelRegistry implements AutoCloseable {

    public static final int SCHEMA_VERSION = 2;

    private static final Pattern THREAD_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final int MAX_ACCOUNT_CODE_POINTS = 128;
    private static final int MAX_LABEL_CODE_POINTS = 256;

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("SQLite JDBC 驱动缺失", failure);
        }
    }

    /** 两个原始渠道。持久化 wire name 与 Spec §7.1 一致。 */
    public enum Channel {
        ONEBOT("onebot"),
        QQ_OFFICIAL("qq_official");

        private final String wireName;

        Channel(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Channel fromWire(String value) {
            if (value == null) {
                throw new IllegalArgumentException("channel 不能为空");
            }
            String normalized = value.strip();
            for (Channel channel : values()) {
                if (channel.wireName.equalsIgnoreCase(normalized)
                        || channel.name().equalsIgnoreCase(normalized)) {
                    return channel;
                }
            }
            throw new IllegalArgumentException("未知 channel");
        }
    }

    /** 一致性校验模式；默认 STRICT。 */
    public enum MatchingMode {
        STRICT("strict"),
        LOOSE("loose"),
        OFF("off");

        private final String wireName;

        MatchingMode(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static MatchingMode fromWire(String value) {
            if (value == null) {
                throw new IllegalArgumentException("matching mode 不能为空");
            }
            String normalized = value.strip();
            for (MatchingMode mode : values()) {
                if (mode.wireName.equalsIgnoreCase(normalized)
                        || mode.name().equalsIgnoreCase(normalized)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("未知 matching mode");
        }
    }

    /** binding 当前状态。解绑后保留历史行并标记 UNBOUND。 */
    public enum BindingState {
        ACTIVE,
        PENDING_OBSERVATION,
        UNBOUND
    }

    public enum AuditResult {
        OK,
        REJECTED
    }

    /** 稳定错误码；调用方/审计可据此 fail-closed，不依赖异常文本。 */
    public enum ErrorCode {
        INVALID_THREAD_ID,
        INVALID_RAW_SOURCE,
        INVALID_ACCOUNT_ID,
        INVALID_LABEL,
        INVALID_IDENTITY,
        INVALID_MATCHING_MODE,
        CHANNEL_MISMATCH,
        ACCOUNT_MISMATCH,
        ACCOUNT_UNKNOWN,
        ACCOUNT_VERIFIER_FAILED,
        OBSERVATION_UNAVAILABLE,
        THREAD_NOT_FOUND,
        SOURCE_NOT_BOUND,
        SOURCE_ALREADY_BOUND,
        THREAD_CHANNEL_NOT_BOUND,
        THREAD_CHANNEL_ALREADY_BOUND,
        IDENTITY_CONFLICT,
        IDENTITY_SCOPE_MISMATCH,
        STORAGE_ERROR
    }

    public static final class RegistryException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final ErrorCode code;

        public RegistryException(ErrorCode code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public RegistryException(ErrorCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code");
        }

        public ErrorCode code() {
            return code;
        }
    }

    public record LogicalThread(
            String threadId,
            String label,
            Channel preferredChannel,
            MatchingMode matchingMode,
            boolean injectionEnabled,
            long revision,
            Instant createdAt,
            Instant updatedAt) {

        public LogicalThread {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(matchingMode, "matchingMode");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    public record ChannelBinding(
            String rawSource,
            String threadId,
            Channel channel,
            String accountId,
            boolean injectionEnabled,
            BindingState state,
            boolean active,
            long revision,
            Instant createdAt,
            Instant updatedAt) {

        public ChannelBinding {
            Objects.requireNonNull(rawSource, "rawSource");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /** raw source 级注入 gate；绑定解绑后仍然独立存在。 */
    public record SourceGate(
            String rawSource,
            boolean injectionEnabled,
            long revision,
            Instant createdAt,
            Instant updatedAt) {

        public SourceGate {
            Objects.requireNonNull(rawSource, "rawSource");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /** 身份映射 scope：整个 logical thread，或某个具体 raw source。 */
    public record IdentityScope(Type type, String value) {
        public enum Type {
            THREAD,
            SOURCE
        }

        public IdentityScope {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
        }

        public static IdentityScope thread(String threadId) {
            return new IdentityScope(Type.THREAD, threadId);
        }

        public static IdentityScope source(String rawSource) {
            return new IdentityScope(Type.SOURCE, rawSource);
        }
    }

    public record ChannelIdentityBinding(
            String threadId,
            Channel channel,
            String channelUserId,
            String canonicalUserId,
            IdentityScope scope,
            boolean active,
            long revision,
            Instant createdAt,
            Instant updatedAt) {

        public ChannelIdentityBinding {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(channelUserId, "channelUserId");
            Objects.requireNonNull(canonicalUserId, "canonicalUserId");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    public record AuditEntry(
            long id,
            String action,
            String threadId,
            String rawSource,
            Channel channel,
            Boolean enabled,
            Boolean effectiveInjection,
            AuditResult result,
            String errorCode,
            boolean changed,
            Long revision,
            Instant createdAt) {

        public AuditEntry {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record BindResult(LogicalThread thread, ChannelBinding binding, boolean changed) {
        public BindResult {
            Objects.requireNonNull(thread, "thread");
            Objects.requireNonNull(binding, "binding");
        }
    }

    public record UnbindResult(LogicalThread thread, boolean changed) {
        public UnbindResult {
            Objects.requireNonNull(thread, "thread");
        }
    }

    public record ThreadUpdate(LogicalThread thread, boolean changed) {
        public ThreadUpdate {
            Objects.requireNonNull(thread, "thread");
        }
    }

    public record BindingUpdate(ChannelBinding binding, LogicalThread thread, boolean changed) {
        public BindingUpdate {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(thread, "thread");
        }
    }

    public record SourceInjectionUpdate(
            SourceGate gate,
            Optional<ChannelBinding> binding,
            Optional<LogicalThread> thread,
            boolean changed) {

        public SourceInjectionUpdate {
            Objects.requireNonNull(gate, "gate");
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(thread, "thread");
        }
    }

    public record IdentityBindResult(
            LogicalThread thread,
            ChannelIdentityBinding identity,
            boolean changed) {

        public IdentityBindResult {
            Objects.requireNonNull(thread, "thread");
            Objects.requireNonNull(identity, "identity");
        }
    }

    public record IdentityUnbindResult(LogicalThread thread, boolean changed) {
        public IdentityUnbindResult {
            Objects.requireNonNull(thread, "thread");
        }
    }

    public record DisplayChoice(
            Channel channel,
            boolean preferred,
            boolean fallback,
            List<Channel> enabledChannels) {

        public DisplayChoice {
            Objects.requireNonNull(channel, "channel");
            enabledChannels = List.copyOf(enabledChannels);
        }
    }

    public record ThreadStatus(
            LogicalThread thread,
            List<ChannelBinding> bindings,
            List<ChannelIdentityBinding> identities,
            boolean configuredInjection,
            boolean effectiveInjection,
            Optional<DisplayChoice> display) {

        public ThreadStatus {
            Objects.requireNonNull(thread, "thread");
            bindings = List.copyOf(bindings);
            identities = List.copyOf(identities);
            Objects.requireNonNull(display, "display");
        }
    }

    public record SourceStatus(
            ChannelBinding binding,
            LogicalThread thread,
            Optional<SourceGate> sourceGate,
            boolean configuredInjection,
            boolean effectiveInjection,
            Optional<DisplayChoice> display) {

        public SourceStatus {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(thread, "thread");
            Objects.requireNonNull(sourceGate, "sourceGate");
            Objects.requireNonNull(display, "display");
        }
    }

    /**
     * raw source 稳定 keyset 分页。{@code next} 是下一页的 {@code afterRawSource}，仅在
     * {@code hasMore=true} 时存在。
     */
    public record ActiveBindingPage(
            List<ChannelBinding> items,
            boolean hasMore,
            Optional<String> nextRawSource) {

        public ActiveBindingPage {
            items = List.copyOf(items);
            Objects.requireNonNull(nextRawSource, "nextRawSource");
        }

        /** 兼容别名。 */
        public List<ChannelBinding> bindings() {
            return items;
        }

        /** 兼容别名。 */
        public Optional<String> next() {
            return nextRawSource;
        }
    }

    private record ParsedRawSource(
            String rawSource,
            Channel channel,
            String platform,
            String accountId,
            String target) {
    }

    private record Mutation<T>(
            T value,
            long revision,
            boolean changed,
            String auditThreadId,
            Channel auditChannel,
            Boolean effectiveInjection,
            boolean auditSuccess) {
    }

    private final Connection connection;
    private final KnownAccountVerifier accountVerifier;
    private final SourceObservationProvider observationProvider;
    private final ChannelAvailabilityProvider availabilityProvider;
    private final Clock clock;
    private final Supplier<MatchingMode> defaultMatchingMode;
    private boolean closed;
    private boolean quarantined;

    public QqChannelRegistry(Path databaseFile,
                             KnownAccountVerifier accountVerifier,
                             SourceObservationProvider observationProvider) {
        this(databaseFile, accountVerifier, observationProvider, Clock.systemUTC(),
                MatchingMode.STRICT, ChannelAvailabilityProvider.unknownUnavailable());
    }

    /** 显式注入连接可用性 provider；未知可用性默认不可用。 */
    public QqChannelRegistry(Path databaseFile,
                             KnownAccountVerifier accountVerifier,
                             SourceObservationProvider observationProvider,
                             ChannelAvailabilityProvider availabilityProvider) {
        this(databaseFile, accountVerifier, observationProvider, Clock.systemUTC(),
                MatchingMode.STRICT, availabilityProvider);
    }

    /** 供配置层指定“新建 thread”的默认 matching mode；已有 thread 不会被覆盖。 */
    public QqChannelRegistry(Path databaseFile,
                             KnownAccountVerifier accountVerifier,
                             SourceObservationProvider observationProvider,
                             MatchingMode defaultMatchingMode) {
        this(databaseFile, accountVerifier, observationProvider, Clock.systemUTC(),
                defaultMatchingMode, ChannelAvailabilityProvider.unknownUnavailable());
    }

    /** 配置层默认 matching mode + 真实连接可用性 provider。 */
    public QqChannelRegistry(Path databaseFile,
                             KnownAccountVerifier accountVerifier,
                             SourceObservationProvider observationProvider,
                             MatchingMode defaultMatchingMode,
                             ChannelAvailabilityProvider availabilityProvider) {
        this(databaseFile, accountVerifier, observationProvider, Clock.systemUTC(),
                defaultMatchingMode, availabilityProvider);
    }

    /**
     * @param clock 可注入时钟；生产用系统 UTC，测试可固定时间
     */
    public QqChannelRegistry(Path databaseFile,
                             KnownAccountVerifier accountVerifier,
                             SourceObservationProvider observationProvider,
                             Clock clock) {
        this(databaseFile, accountVerifier, observationProvider, clock, MatchingMode.STRICT,
                ChannelAvailabilityProvider.unknownUnavailable());
    }

    /**
     * @param defaultMatchingMode 只用于创建新 thread；已有 thread 的持久值是唯一真源
     */
    public QqChannelRegistry(Path databaseFile,
                             KnownAccountVerifier accountVerifier,
                             SourceObservationProvider observationProvider,
                             Clock clock,
                             MatchingMode defaultMatchingMode) {
        this(databaseFile, accountVerifier, observationProvider, clock, defaultMatchingMode,
                ChannelAvailabilityProvider.unknownUnavailable());
    }

    /**
     * @param defaultMatchingMode 只用于创建新 thread；已有 thread 的持久值是唯一真源
     * @param availabilityProvider 真实连接可用性；provider 异常按不可用处理（fail-closed）
     */
    public QqChannelRegistry(Path databaseFile,
                             KnownAccountVerifier accountVerifier,
                             SourceObservationProvider observationProvider,
                             Clock clock,
                             MatchingMode defaultMatchingMode,
                             ChannelAvailabilityProvider availabilityProvider) {
        this(databaseFile, accountVerifier, observationProvider, clock,
                fixedDefaultMatchingMode(defaultMatchingMode), availabilityProvider);
    }

    /** Read the configured default at thread creation; existing persisted choices never change. */
    public QqChannelRegistry(Path databaseFile,
                             KnownAccountVerifier accountVerifier,
                             SourceObservationProvider observationProvider,
                             Clock clock,
                             Supplier<MatchingMode> defaultMatchingMode,
                             ChannelAvailabilityProvider availabilityProvider) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        this.accountVerifier = Objects.requireNonNull(accountVerifier, "accountVerifier");
        this.observationProvider = Objects.requireNonNull(observationProvider,
                "observationProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.defaultMatchingMode = Objects.requireNonNull(defaultMatchingMode,
                "defaultMatchingMode");
        this.availabilityProvider = Objects.requireNonNull(availabilityProvider,
                "availabilityProvider");

        Path absolute = databaseFile.toAbsolutePath().normalize();
        Connection opened = null;
        try {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            opened = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            this.connection = opened;
            initialize();
        } catch (Exception failure) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw new IllegalStateException("打开 QQ 渠道 registry 失败", failure);
        }
    }

    private static Supplier<MatchingMode> fixedDefaultMatchingMode(MatchingMode mode) {
        Objects.requireNonNull(mode, "defaultMatchingMode");
        return () -> mode;
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=10000");
            statement.execute("PRAGMA foreign_keys=ON");
        }

        boolean autoCommit = connection.getAutoCommit();
        if (autoCommit) {
            connection.setAutoCommit(false);
        }
        try {
            migrate();
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            if (autoCommit) {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException restoreFailure) {
                    // Constructor will close the connection; do not mask the migration failure.
                }
            }
        }
    }

    /**
     * 单事务完成建表、补齐旧版列和索引，最后才提升 user_version。任何一步失败都回滚，
     * 因此旧库不会出现“版本已升但列没补齐”的半迁移状态。
     */
    private void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            int version;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                version = result.next() ? result.getInt(1) : 0;
            }
            if (version > SCHEMA_VERSION) {
                throw new IllegalStateException("QQ 渠道 registry schema 版本过新: " + version);
            }

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS logical_thread (
                      thread_id TEXT PRIMARY KEY,
                      label TEXT NOT NULL DEFAULT '',
                      preferred_channel TEXT,
                      matching_mode TEXT NOT NULL DEFAULT 'STRICT',
                      injection_enabled INTEGER NOT NULL DEFAULT 1,
                      revision INTEGER NOT NULL,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      CHECK (length(thread_id) BETWEEN 1 AND 128),
                      CHECK (matching_mode IN ('STRICT', 'LOOSE', 'OFF')),
                      CHECK (injection_enabled IN (0, 1)),
                      CHECK (preferred_channel IS NULL
                             OR preferred_channel IN ('onebot', 'qq_official'))
                    )
                    """);
            ensureColumn(statement, "logical_thread", "injection_enabled",
                    "INTEGER NOT NULL DEFAULT 1");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS channel_binding (
                      raw_source TEXT PRIMARY KEY,
                      thread_id TEXT NOT NULL,
                      channel TEXT NOT NULL,
                      account_id TEXT NOT NULL,
                      injection_enabled INTEGER NOT NULL DEFAULT 1,
                      state TEXT NOT NULL,
                      active INTEGER NOT NULL DEFAULT 1,
                      revision INTEGER NOT NULL,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      CHECK (channel IN ('onebot', 'qq_official')),
                      CHECK (state IN ('ACTIVE', 'PENDING_OBSERVATION', 'UNBOUND')),
                      CHECK (injection_enabled IN (0, 1)),
                      CHECK (active IN (0, 1)),
                      FOREIGN KEY (thread_id) REFERENCES logical_thread(thread_id)
                    )
                    """);
            ensureColumn(statement, "channel_binding", "injection_enabled",
                    "INTEGER NOT NULL DEFAULT 1");
            ensureColumn(statement, "channel_binding", "state",
                    "TEXT NOT NULL DEFAULT 'ACTIVE'");
            ensureColumn(statement, "channel_binding", "active",
                    "INTEGER NOT NULL DEFAULT 1");
            ensureColumn(statement, "channel_binding", "revision",
                    "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(statement, "channel_binding", "created_at",
                    "TEXT NOT NULL DEFAULT ''");
            ensureColumn(statement, "channel_binding", "updated_at",
                    "TEXT NOT NULL DEFAULT ''");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS source_injection_gate (
                      raw_source TEXT PRIMARY KEY,
                      injection_enabled INTEGER NOT NULL DEFAULT 1,
                      revision INTEGER NOT NULL,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      CHECK (injection_enabled IN (0, 1))
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS channel_identity_binding (
                      thread_id TEXT NOT NULL,
                      channel TEXT NOT NULL,
                      channel_user_id TEXT NOT NULL,
                      canonical_user_id TEXT NOT NULL,
                      scope TEXT NOT NULL,
                      active INTEGER NOT NULL DEFAULT 1,
                      revision INTEGER NOT NULL,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY (thread_id, channel, channel_user_id, scope),
                      CHECK (channel IN ('onebot', 'qq_official')),
                      CHECK (active IN (0, 1)),
                      FOREIGN KEY (thread_id) REFERENCES logical_thread(thread_id)
                    )
                    """);
            ensureColumn(statement, "channel_identity_binding", "active",
                    "INTEGER NOT NULL DEFAULT 1");
            ensureColumn(statement, "channel_identity_binding", "revision",
                    "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(statement, "channel_identity_binding", "created_at",
                    "TEXT NOT NULL DEFAULT ''");
            ensureColumn(statement, "channel_identity_binding", "updated_at",
                    "TEXT NOT NULL DEFAULT ''");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS channel_audit (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      action TEXT NOT NULL,
                      thread_id TEXT,
                      raw_source TEXT,
                      channel TEXT,
                      enabled INTEGER,
                      effective_injection INTEGER,
                      result TEXT NOT NULL,
                      error_code TEXT,
                      changed INTEGER NOT NULL DEFAULT 0,
                      revision INTEGER,
                      created_at TEXT NOT NULL,
                      CHECK (result IN ('OK', 'REJECTED')),
                      CHECK (changed IN (0, 1))
                    )
                    """);
            // Spec 示例 v1 只有 id/action/thread_id/raw_source/result/error_code/created_at。
            // 全部旧列一起补齐，并在同一事务里验证成功后才升 user_version。
            ensureColumn(statement, "channel_audit", "channel", "TEXT");
            ensureColumn(statement, "channel_audit", "enabled", "INTEGER");
            ensureColumn(statement, "channel_audit", "effective_injection", "INTEGER");
            ensureColumn(statement, "channel_audit", "changed",
                    "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(statement, "channel_audit", "revision", "INTEGER");

            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS ux_channel_binding_thread_channel_active
                    ON channel_binding(thread_id, channel)
                    WHERE active = 1
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_channel_binding_thread
                    ON channel_binding(thread_id, active)
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS ux_channel_identity_user_active
                    ON channel_identity_binding(thread_id, channel, channel_user_id)
                    WHERE active = 1
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS ux_channel_identity_canonical_active
                    ON channel_identity_binding(thread_id, channel, canonical_user_id, scope)
                    WHERE active = 1
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS ux_channel_identity_canonical_user_active
                    ON channel_identity_binding(thread_id, channel, canonical_user_id)
                    WHERE active = 1
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_channel_identity_thread
                    ON channel_identity_binding(thread_id, active)
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_channel_audit_thread
                    ON channel_audit(thread_id, id DESC)
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_channel_audit_source
                    ON channel_audit(raw_source, id DESC)
                    """);
            statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }

    // ---------------------------------------------------------------------
    // Binding
    // ---------------------------------------------------------------------

    public synchronized BindResult bind(String threadId, String rawSource) {
        return bind(threadId, rawSource, null, "");
    }

    public synchronized BindResult bind(String threadId, String rawSource, Channel expectedChannel) {
        return bind(threadId, rawSource, expectedChannel, "");
    }

    /**
     * 绑定 raw source 到 logical thread；thread 不存在时创建。重复绑定同一对是幂等 no-op。
     *
     * @param expectedChannel 非 null 时必须与 raw source 前缀推导出的 channel 完全一致
     */
    public synchronized BindResult bind(String threadId, String rawSource, Channel expectedChannel,
                           String label) {
        return mutation("qq_thread_bind", threadId, rawSource, expectedChannel, null, () -> {
            validateThreadId(threadId);
            ParsedRawSource parsed = parseRawSource(rawSource);
            if (expectedChannel != null && expectedChannel != parsed.channel) {
                throw failure(ErrorCode.CHANNEL_MISMATCH, "显式 channel 与 raw source 前缀不一致");
            }
            String normalizedLabel = normalizeLabel(label);
            requireKnownAccount(parsed);
            boolean observed = observe(rawSource);

            Instant now = clock.instant();
            boolean changed = false;
            LogicalThread thread = findThread(threadId).orElse(null);
            if (thread == null) {
                insertThread(threadId, normalizedLabel, now);
                changed = true;
            } else if (!normalizedLabel.isEmpty() && !normalizedLabel.equals(thread.label())) {
                updateThreadLabelOnly(threadId, normalizedLabel, now);
                changed = true;
            }

            ChannelBinding existing = findBinding(rawSource).orElse(null);
            ChannelBinding binding;
            if (existing != null && existing.active()) {
                if (!existing.threadId().equals(threadId)) {
                    throw failure(ErrorCode.SOURCE_ALREADY_BOUND,
                            "raw source 已绑定到另一个 logical thread");
                }
                if (existing.channel() != parsed.channel
                        || !existing.accountId().equals(parsed.accountId)) {
                    throw failure(ErrorCode.ACCOUNT_MISMATCH,
                            "raw source 的 channel/account 与已有绑定冲突");
                }
                binding = existing;
            } else {
                Optional<ChannelBinding> occupant = findActiveBinding(threadId, parsed.channel);
                if (occupant.isPresent()) {
                    throw failure(ErrorCode.THREAD_CHANNEL_ALREADY_BOUND,
                            "该 logical thread 的该 channel 已有 active binding");
                }

                SourceGate gate = findSourceGate(rawSource).orElse(null);
                if (gate == null) {
                    insertSourceGate(rawSource, true, now);
                    gate = requireSourceGate(rawSource);
                }
                BindingState state = observed ? BindingState.ACTIVE : BindingState.PENDING_OBSERVATION;
                if (existing == null) {
                    insertBinding(rawSource, threadId, parsed.channel, parsed.accountId,
                            gate.injectionEnabled(), state, now);
                } else {
                    reactivateBinding(rawSource, threadId, parsed.channel, parsed.accountId,
                            gate.injectionEnabled(), state, now);
                }
                changed = true;
                binding = requireBinding(rawSource);
            }

            SourceGate gate = findSourceGate(rawSource).orElse(null);
            if (gate == null && existing != null && existing.active()) {
                // Old/imported database row without a source gate: create the default gate.
                insertSourceGate(rawSource, binding.injectionEnabled(), now);
                gate = requireSourceGate(rawSource);
            } else if (gate != null && existing != null && existing.active()
                    && binding.injectionEnabled() != gate.injectionEnabled()) {
                updateBindingInjection(rawSource, gate.injectionEnabled(), now);
                changed = true;
                binding = requireBinding(rawSource);
            }

            if (changed) {
                bumpThread(threadId, now);
            }
            thread = requireThread(threadId);
            binding = requireBinding(rawSource);
            return new Mutation<>(new BindResult(thread, binding, changed), thread.revision(),
                    changed, threadId, parsed.channel, effectiveInjection(thread, binding), true);
        });
    }

    public synchronized UnbindResult unbind(String threadId, String rawSource) {
        return mutation("qq_thread_unbind", threadId, rawSource, null, null, () -> {
            validateThreadId(threadId);
            ParsedRawSource parsed = parseRawSource(rawSource);
            LogicalThread thread = requireThread(threadId);
            ChannelBinding binding = findBinding(rawSource).orElse(null);
            boolean changed = false;
            Instant now = clock.instant();

            if (binding != null && binding.active()) {
                if (!binding.threadId().equals(threadId)) {
                    throw failure(ErrorCode.SOURCE_ALREADY_BOUND,
                            "raw source 已绑定到另一个 logical thread");
                }
                deactivateBinding(rawSource, threadId, now);
                changed = true;
            }
            // source-scoped identity 依赖具体 raw source；source 解绑后必须失活，防止重绑后串号。
            if (deactivateSourceScopedIdentities(threadId, rawSource, now) > 0) {
                changed = true;
            }
            if (changed) {
                bumpThread(threadId, now);
            }
            thread = requireThread(threadId);
            return new Mutation<>(new UnbindResult(thread, changed), thread.revision(), changed,
                    threadId, parsed.channel, false, true);
        });
    }

    /** 显式把已绑定 source 标记为已观察（ingest 可使用）；未绑定则返回空 Optional。 */
    public synchronized Optional<BindingUpdate> markSourceObserved(String rawSource) {
        return observationMutation("qq_source_observed", null, rawSource, null, null, () -> {
            ParsedRawSource parsed = parseRawSource(rawSource);
            ChannelBinding binding = findBinding(rawSource).filter(ChannelBinding::active)
                    .orElse(null);
            if (binding == null) {
                return new Mutation<>(Optional.empty(), 0, false, null, parsed.channel, null, false);
            }
            boolean changed = binding.state() == BindingState.PENDING_OBSERVATION;
            if (changed) {
                Instant now = clock.instant();
                updateBindingState(rawSource, BindingState.ACTIVE, now);
                bumpThread(binding.threadId(), now);
            }
            LogicalThread thread = requireThread(binding.threadId());
            ChannelBinding refreshed = requireBinding(rawSource);
            return new Mutation<>(Optional.of(new BindingUpdate(refreshed, thread, changed)),
                    thread.revision(), changed, thread.threadId(), parsed.channel,
                    effectiveInjection(thread, refreshed), changed);
        });
    }

    /**
     * 查询注入的观察 provider；只有 provider 明确返回 true 时才把 PENDING_OBSERVATION 升级为
     * ACTIVE。未绑定 source 返回空 Optional，不凭空创建 thread。
     */
    public synchronized Optional<BindingUpdate> refreshObservation(String rawSource) {
        return observationMutation("qq_source_refresh_observation", null, rawSource, null,
                null, () -> {
            ParsedRawSource parsed = parseRawSource(rawSource);
            if (findBinding(rawSource).filter(ChannelBinding::active).isEmpty()) {
                return new Mutation<>(Optional.empty(), 0, false, null, parsed.channel, null, false);
            }
            if (!observe(rawSource)) {
                ChannelBinding binding = requireBinding(rawSource);
                LogicalThread thread = requireThread(binding.threadId());
                return new Mutation<>(Optional.of(new BindingUpdate(binding, thread, false)),
                        thread.revision(), false, thread.threadId(), parsed.channel,
                        effectiveInjection(thread, binding), false);
            }
            if (findBinding(rawSource).orElseThrow().state() == BindingState.PENDING_OBSERVATION) {
                Instant now = clock.instant();
                updateBindingState(rawSource, BindingState.ACTIVE, now);
                ChannelBinding binding = requireBinding(rawSource);
                bumpThread(binding.threadId(), now);
                LogicalThread thread = requireThread(binding.threadId());
                ChannelBinding refreshed = requireBinding(rawSource);
                return new Mutation<>(Optional.of(new BindingUpdate(refreshed, thread, true)),
                        thread.revision(), true, thread.threadId(), parsed.channel,
                        effectiveInjection(thread, refreshed), true);
            }
            ChannelBinding binding = requireBinding(rawSource);
            LogicalThread thread = requireThread(binding.threadId());
            return new Mutation<>(Optional.of(new BindingUpdate(binding, thread, false)),
                    thread.revision(), false, thread.threadId(), parsed.channel,
                    effectiveInjection(thread, binding), false);
        });
    }

    // ---------------------------------------------------------------------
    // Injection / thread controls
    // ---------------------------------------------------------------------

    /** raw source 级 gate；source 未绑定时也可以独立持久化。 */
    public synchronized SourceInjectionUpdate setSourceInjection(String rawSource, boolean enabled) {
        return mutation("qq_thread_set_injection", null, rawSource, null, enabled, () -> {
            ParsedRawSource parsed = parseRawSource(rawSource);
            Instant now = clock.instant();
            boolean changed = false;
            SourceGate gate = findSourceGate(rawSource).orElse(null);
            if (gate == null) {
                insertSourceGate(rawSource, enabled, now);
                changed = true;
            } else if (gate.injectionEnabled() != enabled) {
                updateSourceGate(rawSource, enabled, now);
                changed = true;
            }
            gate = requireSourceGate(rawSource);

            ChannelBinding activeBinding = findBinding(rawSource).filter(ChannelBinding::active)
                    .orElse(null);
            Optional<LogicalThread> thread = Optional.empty();
            if (activeBinding != null) {
                if (activeBinding.injectionEnabled() != enabled) {
                    updateBindingInjection(rawSource, enabled, now);
                    bumpThread(activeBinding.threadId(), now);
                    changed = true;
                }
                LogicalThread refreshedThread = requireThread(activeBinding.threadId());
                thread = Optional.of(refreshedThread);
                activeBinding = requireBinding(rawSource);
            }
            long revision = thread.map(LogicalThread::revision).orElse(gate.revision());
            Boolean effective = thread.isPresent()
                    ? effectiveInjection(thread.get(), activeBinding)
                    : false;
            return new Mutation<>(new SourceInjectionUpdate(gate,
                    Optional.ofNullable(activeBinding), thread, changed), revision, changed,
                    thread.map(LogicalThread::threadId).orElse(null), parsed.channel, effective,
                    true);
        });
    }

    /** logical thread 级 gate；与 raw source gate 同时存在时以更严格者生效。 */
    public synchronized ThreadUpdate setThreadInjection(String threadId, boolean enabled) {
        return mutation("qq_thread_set_injection", threadId, null, null, enabled, () -> {
            validateThreadId(threadId);
            LogicalThread thread = requireThread(threadId);
            boolean changed = thread.injectionEnabled() != enabled;
            if (changed) {
                Instant now = clock.instant();
                updateThreadInjection(threadId, enabled, now);
                thread = requireThread(threadId);
            }
            boolean effective = effectiveThreadInjection(thread, activeBindings(threadId));
            return new Mutation<>(new ThreadUpdate(thread, changed), thread.revision(), changed,
                    threadId, null, effective, true);
        });
    }

    public synchronized ThreadUpdate setPreferredChannel(String threadId, Channel preferredChannel) {
        return mutation("qq_thread_set_preferred_channel", threadId, null, preferredChannel, null,
                () -> {
                    validateThreadId(threadId);
                    LogicalThread thread = requireThread(threadId);
                    boolean changed = thread.preferredChannel() != preferredChannel;
                    if (changed) {
                        Instant now = clock.instant();
                        updatePreferredChannel(threadId, preferredChannel, now);
                        thread = requireThread(threadId);
                    }
                    return new Mutation<>(new ThreadUpdate(thread, changed), thread.revision(),
                            changed, threadId, preferredChannel, null, true);
                });
    }

    public synchronized ThreadUpdate setMatchingMode(String threadId, MatchingMode mode) {
        return mutation("qq_thread_set_matching", threadId, null, null, null, () -> {
            validateThreadId(threadId);
            if (mode == null) {
                throw failure(ErrorCode.INVALID_MATCHING_MODE, "matching mode 不能为空");
            }
            LogicalThread thread = requireThread(threadId);
            boolean changed = thread.matchingMode() != mode;
            if (changed) {
                Instant now = clock.instant();
                updateMatchingMode(threadId, mode, now);
                thread = requireThread(threadId);
            }
            return new Mutation<>(new ThreadUpdate(thread, changed), thread.revision(), changed,
                    threadId, null, null, true);
        });
    }

    public synchronized ThreadUpdate setThreadLabel(String threadId, String label) {
        return mutation("qq_thread_set_label", threadId, null, null, null, () -> {
            validateThreadId(threadId);
            String normalized = normalizeLabel(label);
            LogicalThread thread = requireThread(threadId);
            boolean changed = !thread.label().equals(normalized);
            if (changed) {
                Instant now = clock.instant();
                updateThreadLabel(threadId, normalized, now);
                thread = requireThread(threadId);
            }
            return new Mutation<>(new ThreadUpdate(thread, changed), thread.revision(), changed,
                    threadId, null, null, true);
        });
    }

    // ---------------------------------------------------------------------
    // Identity mapping
    // ---------------------------------------------------------------------

    public synchronized IdentityBindResult bindIdentity(String threadId,
                                           Channel channel,
                                           String channelUserId,
                                           String canonicalUserId,
                                           IdentityScope scope) {
        return mutation("qq_thread_identity_bind", threadId,
                scope != null && scope.type() == IdentityScope.Type.SOURCE ? scope.value() : null,
                channel, null, () -> {
                    validateThreadId(threadId);
                    validateIdentityArguments(channel, channelUserId, canonicalUserId, scope, true);
                    LogicalThread thread = requireThread(threadId);
                    validateScopeShape(threadId, channel, scope);
                    if (findActiveBinding(threadId, channel).isEmpty()) {
                        throw failure(ErrorCode.THREAD_CHANNEL_NOT_BOUND,
                                "该 logical thread 尚无此 channel 的 active binding");
                    }
                    if (scope.type() == IdentityScope.Type.SOURCE) {
                        ParsedRawSource source = parseRawSource(scope.value());
                        ChannelBinding sourceBinding = findBinding(source.rawSource())
                                .filter(ChannelBinding::active).orElse(null);
                        if (sourceBinding == null
                                || !sourceBinding.threadId().equals(threadId)
                                || sourceBinding.channel() != channel) {
                            throw failure(ErrorCode.IDENTITY_SCOPE_MISMATCH,
                                    "source scope 不匹配该 thread/channel 的 active binding");
                        }
                    }

                    String scopeStorage = scopeStorage(scope);
                    ChannelIdentityBinding existing = findIdentity(threadId, channel,
                            channelUserId, scopeStorage).orElse(null);
                    boolean changed = false;
                    if (existing != null && existing.active()) {
                        if (!existing.canonicalUserId().equals(canonicalUserId)) {
                            throw failure(ErrorCode.IDENTITY_CONFLICT,
                                    "同一 channel 用户已有不同的 canonical 身份映射");
                        }
                    } else {
                        // scope 只决定映射适用范围，不允许同一 channel 用户/同一 canonical 在
                        // thread scope 与 source scope 之间形成互相矛盾的映射。
                        Optional<ChannelIdentityBinding> userOwner =
                                findActiveIdentityByUser(threadId, channel, channelUserId);
                        if (userOwner.isPresent()) {
                            throw failure(ErrorCode.IDENTITY_CONFLICT,
                                    "同一 channel 用户已在其他 scope 有 active canonical 映射");
                        }
                        Optional<ChannelIdentityBinding> canonicalOwner =
                                findActiveIdentityByCanonical(threadId, channel, canonicalUserId);
                        if (canonicalOwner.isPresent()) {
                            throw failure(ErrorCode.IDENTITY_CONFLICT,
                                    "同一 canonical 身份已映射到另一个 channel 用户");
                        }
                        Instant now = clock.instant();
                        if (existing == null) {
                            insertIdentity(threadId, channel, channelUserId, canonicalUserId,
                                    scopeStorage, now);
                        } else {
                            reactivateIdentity(threadId, channel, channelUserId, canonicalUserId,
                                    scopeStorage, now);
                        }
                        bumpThread(threadId, now);
                        changed = true;
                    }
                    thread = requireThread(threadId);
                    ChannelIdentityBinding identity = requireIdentity(threadId, channel,
                            channelUserId, scopeStorage);
                    return new Mutation<>(new IdentityBindResult(thread, identity, changed),
                            thread.revision(), changed, threadId, channel, null, true);
                });
    }

    public synchronized IdentityUnbindResult unbindIdentity(String threadId,
                                               Channel channel,
                                               String channelUserId,
                                               IdentityScope scope) {
        String raw = scope != null && scope.type() == IdentityScope.Type.SOURCE
                ? scope.value() : null;
        return mutation("qq_thread_identity_unbind", threadId, raw, channel, null, () -> {
            validateThreadId(threadId);
            validateIdentityArguments(channel, channelUserId, "", scope, false);
            LogicalThread thread = requireThread(threadId);
            validateScopeShape(threadId, channel, scope);
            String scopeStorage = scopeStorage(scope);
            ChannelIdentityBinding existing = findIdentity(threadId, channel, channelUserId,
                    scopeStorage).orElse(null);
            boolean changed = false;
            if (existing != null && existing.active()) {
                Instant now = clock.instant();
                deactivateIdentity(threadId, channel, channelUserId, scopeStorage, now);
                bumpThread(threadId, now);
                changed = true;
            }
            thread = requireThread(threadId);
            return new Mutation<>(new IdentityUnbindResult(thread, changed), thread.revision(),
                    changed, threadId, channel, null, true);
        });
    }

    // ---------------------------------------------------------------------
    // Reads / status
    // ---------------------------------------------------------------------

    public synchronized Optional<LogicalThread> thread(String threadId) {
        validateThreadId(threadId);
        ensureOpen();
        return findThread(threadId);
    }

    public synchronized Optional<ChannelBinding> binding(String rawSource) {
        ParsedRawSource parsed = parseRawSource(rawSource);
        ensureOpen();
        return findBinding(parsed.rawSource());
    }

    public synchronized Optional<SourceGate> sourceGate(String rawSource) {
        ParsedRawSource parsed = parseRawSource(rawSource);
        ensureOpen();
        return findSourceGate(parsed.rawSource());
    }

    /** raw source 自己的 gate；不存在时按默认 true 处理。 */
    public synchronized boolean sourceInjectionEnabled(String rawSource) {
        return sourceGate(rawSource).map(SourceGate::injectionEnabled).orElse(true);
    }

    /**
     * 只读 keyset 分页读取全部 active binding，不依赖 logical thread catalog。
     *
     * <p>按 raw_source 字典序稳定排序；{@code afterRawSource} 为上一页 {@code next}。返回完整
     * binding tuple（含 raw/thread/channel/account/gate/state/revision/时间），不包含消息正文或
     * 凭据。cursor 是原样 raw source，不做 offset/时间窗口推断，因此不会因解绑或并发更新漏行。</p>
     *
     * @param afterRawSource null/空表示第一页；否则必须是合法 raw source
     * @param limit 每页上限 500
     */
    public synchronized ActiveBindingPage activeBindings(String afterRawSource, int limit) {
        ensureOpen();
        String cursor = null;
        if (afterRawSource != null && !afterRawSource.isBlank()) {
            cursor = parseRawSource(afterRawSource).rawSource();
        }
        int bounded = clamp(limit, 500);
        int fetch = bounded + 1;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM channel_binding
                WHERE active = 1 AND raw_source > ?
                ORDER BY raw_source ASC
                LIMIT ?
                """)) {
            statement.setString(1, cursor == null ? "" : cursor);
            statement.setInt(2, fetch);
            List<ChannelBinding> fetched = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    fetched.add(mapBinding(rows));
                }
            }
            boolean hasMore = fetched.size() > bounded;
            List<ChannelBinding> page = hasMore
                    ? List.copyOf(fetched.subList(0, bounded))
                    : List.copyOf(fetched);
            Optional<String> next = hasMore
                    ? Optional.of(page.getLast().rawSource())
                    : Optional.empty();
            return new ActiveBindingPage(page, hasMore, next);
        } catch (SQLException failure) {
            throw storageFailure("分页读取 active channel binding 失败", failure);
        }
    }

    public synchronized List<LogicalThread> listThreads(int limit) {
        ensureOpen();
        int bounded = clamp(limit, 500);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM logical_thread
                ORDER BY updated_at DESC, thread_id ASC
                LIMIT ?
                """)) {
            statement.setInt(1, bounded);
            List<LogicalThread> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(mapThread(rows));
                }
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw storageFailure("读取 logical thread 列表失败", failure);
        }
    }

    public synchronized Optional<ThreadStatus> status(String threadId) {
        validateThreadId(threadId);
        ensureOpen();
        return inTransaction(() -> {
            LogicalThread thread = findThread(threadId).orElse(null);
            if (thread == null) {
                return Optional.empty();
            }
            List<ChannelBinding> bindings = activeBindings(threadId);
            List<ChannelIdentityBinding> identities = activeIdentities(threadId);
            return Optional.of(new ThreadStatus(thread, bindings, identities,
                    configuredThreadInjection(thread, bindings),
                    effectiveThreadInjection(thread, bindings),
                    chooseDisplay(thread, bindings)));
        });
    }

    public synchronized Optional<SourceStatus> sourceStatus(String rawSource) {
        ParsedRawSource parsed = parseRawSource(rawSource);
        ensureOpen();
        return inTransaction(() -> {
            ChannelBinding binding = findBinding(parsed.rawSource())
                    .filter(ChannelBinding::active).orElse(null);
            if (binding == null) {
                return Optional.empty();
            }
            LogicalThread thread = findThread(binding.threadId()).orElseThrow(
                    () -> failure(ErrorCode.THREAD_NOT_FOUND, "binding 指向的 thread 不存在"));
            Optional<SourceGate> gate = findSourceGate(parsed.rawSource());
            List<ChannelBinding> bindings = activeBindings(thread.threadId());
            return Optional.of(new SourceStatus(binding, thread, gate,
                    configuredInjection(thread, binding), effectiveInjection(thread, binding),
                    chooseDisplay(thread, bindings)));
        });
    }

    public synchronized List<ChannelBinding> bindings(String threadId) {
        validateThreadId(threadId);
        ensureOpen();
        requireThread(threadId);
        return activeBindings(threadId);
    }

    public synchronized List<ChannelIdentityBinding> identities(String threadId) {
        validateThreadId(threadId);
        ensureOpen();
        requireThread(threadId);
        return activeIdentities(threadId);
    }

    public synchronized List<AuditEntry> audits(int limit) {
        ensureOpen();
        int bounded = clamp(limit, 1000);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM channel_audit ORDER BY id DESC LIMIT ?
                """)) {
            statement.setInt(1, bounded);
            return readAudits(statement);
        } catch (SQLException failure) {
            throw storageFailure("读取 channel 审计失败", failure);
        }
    }

    public synchronized List<AuditEntry> auditsForThread(String threadId, int limit) {
        validateThreadId(threadId);
        ensureOpen();
        int bounded = clamp(limit, 1000);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM channel_audit WHERE thread_id = ? ORDER BY id DESC LIMIT ?
                """)) {
            statement.setString(1, threadId);
            statement.setInt(2, bounded);
            return readAudits(statement);
        } catch (SQLException failure) {
            throw storageFailure("读取 thread 审计失败", failure);
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
            throw new IllegalStateException("关闭 QQ 渠道 registry 失败", failure);
        }
    }

    // ---------------------------------------------------------------------
    // Mutation / audit plumbing
    // ---------------------------------------------------------------------

    private <T> T mutation(String action,
                           String threadId,
                           String rawSource,
                           Channel channel,
                           Boolean enabled,
                           java.util.function.Supplier<Mutation<T>> work) {
        return mutation(action, threadId, rawSource, channel, enabled, true, work);
    }

    /**
     * ingest/observation 状态同步不是 Agent 控制面操作；无实际状态变化时不写审计，
     * 避免每条重复观察都造成审计无限增长。
     */
    private <T> T observationMutation(String action,
                                      String threadId,
                                      String rawSource,
                                      Channel channel,
                                      Boolean enabled,
                                      java.util.function.Supplier<Mutation<T>> work) {
        return mutation(action, threadId, rawSource, channel, enabled, false, work);
    }

    private <T> T mutation(String action,
                           String threadId,
                           String rawSource,
                           Channel channel,
                           Boolean enabled,
                           boolean auditFailure,
                           java.util.function.Supplier<Mutation<T>> work) {
        try {
            return inTransaction(() -> {
                Mutation<T> outcome = work.get();
                if (outcome.auditSuccess()) {
                    insertAudit(action,
                            outcome.auditThreadId() != null ? outcome.auditThreadId()
                                    : safeThreadId(threadId),
                            safeRawSource(rawSource),
                            outcome.auditChannel() != null ? outcome.auditChannel() : channel,
                            enabled, outcome.effectiveInjection(), AuditResult.OK, null,
                            outcome.changed(), outcome.revision());
                }
                return outcome.value();
            });
        } catch (RegistryException failure) {
            if (auditFailure) {
                rejectAudit(action, threadId, rawSource, channel, enabled, failure.code(), failure);
            }
            throw failure;
        } catch (RuntimeException failure) {
            RegistryException wrapped = new RegistryException(ErrorCode.STORAGE_ERROR,
                    "通道 registry 操作失败", failure);
            if (auditFailure) {
                rejectAudit(action, threadId, rawSource, channel, enabled, wrapped.code(),
                        wrapped);
            }
            throw wrapped;
        }
    }

    private void rejectAudit(String action, String threadId, String rawSource, Channel channel,
                             Boolean enabled, ErrorCode errorCode, Throwable original) {
        try {
            inTransaction(() -> {
                insertAudit(action, safeThreadId(threadId), safeRawSource(rawSource), channel,
                        enabled, null, AuditResult.REJECTED, errorCode.name(), false, null);
                return null;
            });
        } catch (RuntimeException auditFailure) {
            original.addSuppressed(auditFailure);
        }
    }

    private void insertAudit(String action, String threadId, String rawSource, Channel channel,
                             Boolean enabled, Boolean auditEffectiveInjection,
                             AuditResult result, String errorCode,
                             boolean changed, Long revision) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO channel_audit(
                  action, thread_id, raw_source, channel, enabled, effective_injection,
                  result, error_code, changed, revision, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, action);
            statement.setString(2, threadId);
            statement.setString(3, rawSource);
            statement.setString(4, channel == null ? null : channel.wireName());
            if (enabled == null) {
                statement.setNull(5, java.sql.Types.INTEGER);
            } else {
                statement.setInt(5, enabled ? 1 : 0);
            }
            if (auditEffectiveInjection == null) {
                statement.setNull(6, java.sql.Types.INTEGER);
            } else {
                statement.setInt(6, auditEffectiveInjection ? 1 : 0);
            }
            statement.setString(7, result.name());
            statement.setString(8, errorCode);
            statement.setInt(9, changed ? 1 : 0);
            if (revision == null) {
                statement.setNull(10, java.sql.Types.INTEGER);
            } else {
                statement.setLong(10, revision);
            }
            statement.setString(11, clock.instant().toString());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw storageFailure("写入 channel 审计失败", failure);
        }
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        ensureOpen();
        boolean autoCommit;
        try {
            autoCommit = connection.getAutoCommit();
        } catch (SQLException failure) {
            throw storageFailure("读取事务状态失败", failure);
        }
        if (!autoCommit) {
            return work.get();
        }
        try {
            connection.setAutoCommit(false);
        } catch (SQLException failure) {
            throw storageFailure("开启事务失败", failure);
        }

        T result;
        try {
            result = work.get();
        } catch (RuntimeException | Error failure) {
            rollbackOrQuarantine(failure);
            throw failure;
        }

        try {
            connection.commit();
        } catch (SQLException commitFailure) {
            rollbackOrQuarantine(commitFailure);
            throw storageFailure("提交事务失败", commitFailure);
        }

        Throwable restoreFailure = restoreAutoCommit();
        if (restoreFailure != null) {
            quarantine();
            throw storageFailure("恢复自动提交失败，连接已隔离", restoreFailure);
        }
        return result;
    }

    private void rollbackOrQuarantine(Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException | RuntimeException rollbackFailure) {
            if (rollbackFailure != failure) {
                failure.addSuppressed(rollbackFailure);
            }
            quarantine();
            return;
        }
        Throwable restoreFailure = restoreAutoCommit();
        if (restoreFailure != null) {
            failure.addSuppressed(restoreFailure);
            quarantine();
        }
    }

    private Throwable restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
            return null;
        } catch (SQLException | RuntimeException failure) {
            return failure;
        }
    }

    private void quarantine() {
        quarantined = true;
        closed = true;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // The connection is already unusable; the original failure remains authoritative.
        }
    }

    private void ensureOpen() {
        if (closed || quarantined) {
            throw failure(ErrorCode.STORAGE_ERROR, "通道 registry 已关闭或连接已隔离");
        }
    }

    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------

    private static void validateThreadId(String threadId) {
        if (threadId == null || !THREAD_ID.matcher(threadId).matches()) {
            throw failure(ErrorCode.INVALID_THREAD_ID,
                    "threadId 只允许 [A-Za-z0-9._-]，长度 1..128");
        }
    }

    /**
     * 与 OfficialQqSourceIds/AppConfig 对齐的“安全单段”验证：非空，且不得包含 /、\\、|、
     * 空白/空格、ISO 控制、FORMAT、代理字符、行/段分隔符。不限制 ASCII，也不对普通 ID 另设
     * 数字长度规则。拒绝 pipe 是因为消息板使用 {@code source + '|' + messageId} 作为唯一键。
     */
    private static boolean isSafeSegment(String value) {
        if (value == null || value.isEmpty() || value.isBlank()) {
            return false;
        }
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            if (codePoint == '/' || codePoint == '\\' || codePoint == '|') {
                return false;
            }
            int type = Character.getType(codePoint);
            if (Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isSafeAccountSegment(String accountId) {
        return isSafeSegment(accountId)
                && accountId.codePointCount(0, accountId.length()) <= MAX_ACCOUNT_CODE_POINTS;
    }

    /** OneBot 数字 ID：只要求正数且可由 long 表示，不要求固定位数/前导零规则。 */
    private static boolean isPositiveLong(String value) {
        if (value == null || !DIGITS.matcher(value).matches()) {
            return false;
        }
        try {
            return Long.parseLong(value) > 0L;
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    private static ParsedRawSource parseRawSource(String rawSource) {
        if (rawSource == null) {
            throw failure(ErrorCode.INVALID_RAW_SOURCE, "raw source 不能为空");
        }
        String[] parts = rawSource.split("/", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank()
                || parts[2].isBlank()) {
            throw failure(ErrorCode.INVALID_RAW_SOURCE,
                    "raw source 必须是 platform/account/id 三段");
        }
        String platform = parts[0];
        String accountId = parts[1];
        String target = parts[2];
        Channel channel;
        boolean oneBotNumericTarget;
        switch (platform) {
            case "qq_group", "qq_private" -> {
                channel = Channel.ONEBOT;
                oneBotNumericTarget = true;
            }
            case "qq_system" -> {
                channel = Channel.ONEBOT;
                oneBotNumericTarget = false;
            }
            case "qq_official_group", "qq_official_private" -> {
                channel = Channel.QQ_OFFICIAL;
                oneBotNumericTarget = false;
            }
            case "qq_official_system" -> {
                channel = Channel.QQ_OFFICIAL;
                oneBotNumericTarget = false;
            }
            default -> throw failure(ErrorCode.INVALID_RAW_SOURCE, "未知的 QQ raw source 平台");
        }
        if (!isSafeAccountSegment(accountId)) {
            throw failure(ErrorCode.INVALID_ACCOUNT_ID, "raw source accountId 格式非法");
        }
        boolean validTarget = oneBotNumericTarget
                ? isPositiveLong(target)
                : isSafeSegment(target);
        if (!validTarget) {
            throw failure(ErrorCode.INVALID_RAW_SOURCE, "raw source 目标 ID 格式非法");
        }
        return new ParsedRawSource(rawSource, channel, platform, accountId, target);
    }

    private static String normalizeLabel(String label) {
        String normalized = label == null ? "" : label.strip();
        if (normalized.codePointCount(0, normalized.length()) > MAX_LABEL_CODE_POINTS
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw failure(ErrorCode.INVALID_LABEL, "label 过长或包含控制字符");
        }
        return normalized;
    }

    private static void validateIdentityArguments(Channel channel,
                                                 String channelUserId,
                                                 String canonicalUserId,
                                                 IdentityScope scope,
                                                 boolean canonicalRequired) {
        if (channel == null) {
            throw failure(ErrorCode.INVALID_IDENTITY, "channel 不能为空");
        }
        if (channelUserId == null || channelUserId.isBlank()) {
            throw failure(ErrorCode.INVALID_IDENTITY, "channelUserId 不能为空");
        }
        if (channel == Channel.ONEBOT) {
            if (!isPositiveLong(channelUserId)) {
                throw failure(ErrorCode.INVALID_IDENTITY,
                        "OneBot channelUserId 必须是 long 范围内的正整数字符串");
            }
        } else if (!isSafeSegment(channelUserId)) {
            throw failure(ErrorCode.INVALID_IDENTITY, "官方 channelUserId 格式非法");
        }
        if (canonicalRequired && (canonicalUserId == null || !isSafeSegment(canonicalUserId))) {
            throw failure(ErrorCode.INVALID_IDENTITY, "canonicalUserId 格式非法");
        }
        if (!canonicalRequired && canonicalUserId != null && !canonicalUserId.isEmpty()
                && !isSafeSegment(canonicalUserId)) {
            throw failure(ErrorCode.INVALID_IDENTITY, "canonicalUserId 格式非法");
        }
        if (scope == null) {
            throw failure(ErrorCode.INVALID_IDENTITY, "identity scope 不能为空");
        }
    }

    private static void validateScopeShape(String threadId, Channel channel, IdentityScope scope) {
        if (scope.type() == IdentityScope.Type.THREAD) {
            if (!threadId.equals(scope.value())) {
                throw failure(ErrorCode.IDENTITY_SCOPE_MISMATCH,
                        "thread scope 必须指向当前 logical thread");
            }
            return;
        }
        ParsedRawSource raw = parseRawSource(scope.value());
        if (raw.channel() != channel) {
            throw failure(ErrorCode.IDENTITY_SCOPE_MISMATCH,
                    "source scope 的 channel 与映射 channel 不一致");
        }
    }

    private void requireKnownAccount(ParsedRawSource parsed) {
        boolean known;
        try {
            known = accountVerifier.isKnown(parsed.channel(), parsed.accountId());
        } catch (RuntimeException failure) {
            throw new RegistryException(ErrorCode.ACCOUNT_VERIFIER_FAILED,
                    "known account 校验失败", failure);
        }
        if (!known) {
            throw failure(ErrorCode.ACCOUNT_UNKNOWN, "渠道账号未知，拒绝绑定");
        }
    }

    private boolean observe(String rawSource) {
        try {
            return observationProvider.hasObserved(rawSource);
        } catch (RuntimeException failure) {
            throw new RegistryException(ErrorCode.OBSERVATION_UNAVAILABLE,
                    "raw source 观察状态不可用", failure);
        }
    }

    private static String safeThreadId(String threadId) {
        try {
            validateThreadId(threadId);
            return threadId;
        } catch (RegistryException ignored) {
            return null;
        }
    }

    private static String safeRawSource(String rawSource) {
        try {
            return parseRawSource(rawSource).rawSource();
        } catch (RegistryException ignored) {
            return null;
        }
    }

    private static String scopeStorage(IdentityScope scope) {
        return (scope.type() == IdentityScope.Type.THREAD ? "thread:" : "source:") + scope.value();
    }

    private static IdentityScope parseScopeStorage(String storage) {
        if (storage.startsWith("thread:")) {
            return IdentityScope.thread(storage.substring("thread:".length()));
        }
        if (storage.startsWith("source:")) {
            return IdentityScope.source(storage.substring("source:".length()));
        }
        throw failure(ErrorCode.INVALID_IDENTITY, "未知 identity scope 存储格式");
    }

    private static RegistryException failure(ErrorCode code, String message) {
        return new RegistryException(code, message);
    }

    private static RegistryException storageFailure(String message, Throwable cause) {
        return new RegistryException(ErrorCode.STORAGE_ERROR, message, cause);
    }

    private static int clamp(int limit, int max) {
        return Math.max(1, Math.min(max, limit));
    }

    // ---------------------------------------------------------------------
    // SQL reads
    // ---------------------------------------------------------------------

    private Optional<LogicalThread> findThread(String threadId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM logical_thread WHERE thread_id = ?
                """)) {
            statement.setString(1, threadId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapThread(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw storageFailure("读取 logical thread 失败", failure);
        }
    }

    private LogicalThread requireThread(String threadId) {
        return findThread(threadId).orElseThrow(
                () -> failure(ErrorCode.THREAD_NOT_FOUND, "logical thread 不存在"));
    }

    private Optional<ChannelBinding> findBinding(String rawSource) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM channel_binding WHERE raw_source = ?
                """)) {
            statement.setString(1, rawSource);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapBinding(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw storageFailure("读取 channel binding 失败", failure);
        }
    }

    private ChannelBinding requireBinding(String rawSource) {
        return findBinding(rawSource).orElseThrow(
                () -> failure(ErrorCode.SOURCE_NOT_BOUND, "raw source 没有 binding 行"));
    }

    private Optional<ChannelBinding> findActiveBinding(String threadId, Channel channel) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM channel_binding
                WHERE thread_id = ? AND channel = ? AND active = 1
                """)) {
            statement.setString(1, threadId);
            statement.setString(2, channel.wireName());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapBinding(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw storageFailure("读取 active channel binding 失败", failure);
        }
    }

    private List<ChannelBinding> activeBindings(String threadId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM channel_binding
                WHERE thread_id = ? AND active = 1
                ORDER BY CASE channel WHEN 'onebot' THEN 0 ELSE 1 END
                """)) {
            statement.setString(1, threadId);
            List<ChannelBinding> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(mapBinding(rows));
                }
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw storageFailure("读取 thread bindings 失败", failure);
        }
    }

    private Optional<SourceGate> findSourceGate(String rawSource) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM source_injection_gate WHERE raw_source = ?
                """)) {
            statement.setString(1, rawSource);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapGate(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw storageFailure("读取 raw source 注入 gate 失败", failure);
        }
    }

    private SourceGate requireSourceGate(String rawSource) {
        return findSourceGate(rawSource).orElseThrow(
                () -> failure(ErrorCode.SOURCE_NOT_BOUND, "raw source 没有注入 gate 行"));
    }

    private Optional<ChannelIdentityBinding> findIdentity(String threadId, Channel channel,
                                                          String channelUserId, String scope) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM channel_identity_binding
                WHERE thread_id = ? AND channel = ? AND channel_user_id = ? AND scope = ?
                """)) {
            statement.setString(1, threadId);
            statement.setString(2, channel.wireName());
            statement.setString(3, channelUserId);
            statement.setString(4, scope);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapIdentity(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw storageFailure("读取 channel identity binding 失败", failure);
        }
    }

    private ChannelIdentityBinding requireIdentity(String threadId, Channel channel,
                                                   String channelUserId, String scope) {
        return findIdentity(threadId, channel, channelUserId, scope).orElseThrow(
                () -> failure(ErrorCode.IDENTITY_CONFLICT, "identity binding 不存在"));
    }

    private Optional<ChannelIdentityBinding> findActiveIdentityByUser(
            String threadId, Channel channel, String channelUserId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM channel_identity_binding
                WHERE thread_id = ? AND channel = ? AND channel_user_id = ? AND active = 1
                """)) {
            statement.setString(1, threadId);
            statement.setString(2, channel.wireName());
            statement.setString(3, channelUserId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapIdentity(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw storageFailure("读取 channel 用户 identity 失败", failure);
        }
    }

    private Optional<ChannelIdentityBinding> findActiveIdentityByCanonical(
            String threadId, Channel channel, String canonicalUserId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM channel_identity_binding
                WHERE thread_id = ? AND channel = ? AND canonical_user_id = ? AND active = 1
                """)) {
            statement.setString(1, threadId);
            statement.setString(2, channel.wireName());
            statement.setString(3, canonicalUserId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapIdentity(result)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw storageFailure("读取 canonical identity 失败", failure);
        }
    }

    private List<ChannelIdentityBinding> activeIdentities(String threadId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM channel_identity_binding
                WHERE thread_id = ? AND active = 1
                ORDER BY channel, channel_user_id, scope
                """)) {
            statement.setString(1, threadId);
            List<ChannelIdentityBinding> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(mapIdentity(rows));
                }
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw storageFailure("读取 thread identities 失败", failure);
        }
    }

    private List<AuditEntry> readAudits(PreparedStatement statement) throws SQLException {
        List<AuditEntry> result = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(mapAudit(rows));
            }
        }
        return List.copyOf(result);
    }

    private static LogicalThread mapThread(ResultSet result) throws SQLException {
        String preferred = result.getString("preferred_channel");
        return new LogicalThread(
                result.getString("thread_id"),
                result.getString("label"),
                preferred == null ? null : Channel.fromWire(preferred),
                MatchingMode.fromWire(result.getString("matching_mode")),
                result.getInt("injection_enabled") != 0,
                result.getLong("revision"),
                Instant.parse(result.getString("created_at")),
                Instant.parse(result.getString("updated_at")));
    }

    private static ChannelBinding mapBinding(ResultSet result) throws SQLException {
        return new ChannelBinding(
                result.getString("raw_source"),
                result.getString("thread_id"),
                Channel.fromWire(result.getString("channel")),
                result.getString("account_id"),
                result.getInt("injection_enabled") != 0,
                BindingState.valueOf(result.getString("state")),
                result.getInt("active") != 0,
                result.getLong("revision"),
                Instant.parse(result.getString("created_at")),
                Instant.parse(result.getString("updated_at")));
    }

    private static SourceGate mapGate(ResultSet result) throws SQLException {
        return new SourceGate(
                result.getString("raw_source"),
                result.getInt("injection_enabled") != 0,
                result.getLong("revision"),
                Instant.parse(result.getString("created_at")),
                Instant.parse(result.getString("updated_at")));
    }

    private static ChannelIdentityBinding mapIdentity(ResultSet result) throws SQLException {
        return new ChannelIdentityBinding(
                result.getString("thread_id"),
                Channel.fromWire(result.getString("channel")),
                result.getString("channel_user_id"),
                result.getString("canonical_user_id"),
                parseScopeStorage(result.getString("scope")),
                result.getInt("active") != 0,
                result.getLong("revision"),
                Instant.parse(result.getString("created_at")),
                Instant.parse(result.getString("updated_at")));
    }

    private static AuditEntry mapAudit(ResultSet result) throws SQLException {
        int enabledValue = result.getInt("enabled");
        Boolean enabled = result.wasNull() ? null : enabledValue != 0;
        int effectiveValue = result.getInt("effective_injection");
        Boolean effectiveInjection = result.wasNull() ? null : effectiveValue != 0;
        long revisionValue = result.getLong("revision");
        Long revision = result.wasNull() ? null : revisionValue;
        String channel = result.getString("channel");
        return new AuditEntry(
                result.getLong("id"),
                result.getString("action"),
                result.getString("thread_id"),
                result.getString("raw_source"),
                channel == null ? null : Channel.fromWire(channel),
                enabled,
                effectiveInjection,
                auditResult(result.getString("result")),
                result.getString("error_code"),
                result.getInt("changed") != 0,
                revision,
                parseInstant(result.getString("created_at")));
    }

    private static AuditResult auditResult(String stored) {
        if (stored != null) {
            String normalized = stored.strip();
            if (normalized.equalsIgnoreCase("OK") || normalized.equalsIgnoreCase("SUCCESS")
                    || normalized.equalsIgnoreCase("SUCCEEDED")) {
                return AuditResult.OK;
            }
        }
        return AuditResult.REJECTED;
    }

    private static Instant parseInstant(String stored) {
        return stored == null || stored.isBlank() ? Instant.EPOCH : Instant.parse(stored);
    }

    /**
     * 可用性 provider 只接收 channel/accountId；异常按不可用处理，避免把未知连接状态当成
     * 可展示账号。该检查只影响选择/回退，不改写持久化 binding 或注入开关。
     */
    private boolean accountAvailable(ChannelBinding binding) {
        try {
            return availabilityProvider.isAvailable(binding.channel(), binding.accountId());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** 仅看持久化 gate，不考虑 pending/availability。 */
    private boolean configuredInjection(LogicalThread thread, ChannelBinding binding) {
        if (thread == null || binding == null) {
            return false;
        }
        boolean gateEnabled = findSourceGate(binding.rawSource())
                .map(SourceGate::injectionEnabled).orElse(true);
        return thread.injectionEnabled() && gateEnabled && binding.injectionEnabled();
    }

    /** gate 全开、binding 已 ACTIVE，且该渠道账号当前可用，才实际可注入。 */
    private boolean effectiveInjection(LogicalThread thread, ChannelBinding binding) {
        return configuredInjection(thread, binding)
                && binding.active()
                && binding.state() == BindingState.ACTIVE
                && accountAvailable(binding);
    }

    /** thread 汇总：至少一个 active source 的持久化 gate 全开。 */
    private boolean configuredThreadInjection(LogicalThread thread,
                                              List<ChannelBinding> bindings) {
        return bindings.stream().anyMatch(binding ->
                binding.active() && configuredInjection(thread, binding));
    }

    /** thread 汇总：至少一个渠道当前实际可注入；无任何可用渠道时为 false。 */
    private boolean effectiveThreadInjection(LogicalThread thread,
                                             List<ChannelBinding> bindings) {
        return bindings.stream().anyMatch(binding ->
                binding.active() && effectiveInjection(thread, binding));
    }

    private Optional<DisplayChoice> chooseDisplay(LogicalThread thread,
                                                  List<ChannelBinding> bindings) {
        List<Channel> enabledChannels = new ArrayList<>();
        for (ChannelBinding binding : bindings) {
            if (effectiveInjection(thread, binding)) {
                enabledChannels.add(binding.channel());
            }
        }
        if (enabledChannels.isEmpty()) {
            return Optional.empty();
        }
        Channel preferred = thread.preferredChannel();
        if (preferred != null && enabledChannels.contains(preferred)) {
            return Optional.of(new DisplayChoice(preferred, true, false,
                    List.copyOf(enabledChannels)));
        }
        boolean fallback = preferred != null;
        return Optional.of(new DisplayChoice(enabledChannels.getFirst(), false, fallback,
                List.copyOf(enabledChannels)));
    }

    // ---------------------------------------------------------------------
    // SQL writes
    // ---------------------------------------------------------------------

    private void insertThread(String threadId, String label, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO logical_thread(
                  thread_id, label, preferred_channel, matching_mode, injection_enabled,
                  revision, created_at, updated_at
                ) VALUES (?, ?, NULL, ?, 1, 1, ?, ?)
                """)) {
            statement.setString(1, threadId);
            statement.setString(2, label);
            MatchingMode mode = Objects.requireNonNull(defaultMatchingMode.get(),
                    "defaultMatchingMode supplier returned null");
            statement.setString(3, mode.name());
            statement.setString(4, now.toString());
            statement.setString(5, now.toString());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw storageFailure("创建 logical thread 失败", failure);
        }
    }

    private void updateThreadLabelOnly(String threadId, String label, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE logical_thread SET label = ?, updated_at = ? WHERE thread_id = ?
                """)) {
            statement.setString(1, label);
            statement.setString(2, now.toString());
            statement.setString(3, threadId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw storageFailure("更新 logical thread label 失败", failure);
        }
    }

    private void updateThreadLabel(String threadId, String label, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE logical_thread
                SET label = ?, revision = revision + 1, updated_at = ?
                WHERE thread_id = ?
                """)) {
            statement.setString(1, label);
            statement.setString(2, now.toString());
            statement.setString(3, threadId);
            requireOneRow(statement.executeUpdate(), "更新 logical thread label 失败");
        } catch (SQLException failure) {
            throw storageFailure("更新 logical thread label 失败", failure);
        }
    }

    private void updateThreadInjection(String threadId, boolean enabled, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE logical_thread
                SET injection_enabled = ?, revision = revision + 1, updated_at = ?
                WHERE thread_id = ?
                """)) {
            statement.setInt(1, enabled ? 1 : 0);
            statement.setString(2, now.toString());
            statement.setString(3, threadId);
            requireOneRow(statement.executeUpdate(), "更新 thread 注入开关失败");
        } catch (SQLException failure) {
            throw storageFailure("更新 thread 注入开关失败", failure);
        }
    }

    private void updatePreferredChannel(String threadId, Channel channel, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE logical_thread
                SET preferred_channel = ?, revision = revision + 1, updated_at = ?
                WHERE thread_id = ?
                """)) {
            statement.setString(1, channel == null ? null : channel.wireName());
            statement.setString(2, now.toString());
            statement.setString(3, threadId);
            requireOneRow(statement.executeUpdate(), "更新首选渠道失败");
        } catch (SQLException failure) {
            throw storageFailure("更新首选渠道失败", failure);
        }
    }

    private void updateMatchingMode(String threadId, MatchingMode mode, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE logical_thread
                SET matching_mode = ?, revision = revision + 1, updated_at = ?
                WHERE thread_id = ?
                """)) {
            statement.setString(1, mode.name());
            statement.setString(2, now.toString());
            statement.setString(3, threadId);
            requireOneRow(statement.executeUpdate(), "更新 matching mode 失败");
        } catch (SQLException failure) {
            throw storageFailure("更新 matching mode 失败", failure);
        }
    }

    private void bumpThread(String threadId, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE logical_thread
                SET revision = revision + 1, updated_at = ?
                WHERE thread_id = ?
                """)) {
            statement.setString(1, now.toString());
            statement.setString(2, threadId);
            requireOneRow(statement.executeUpdate(), "更新 logical thread revision 失败");
        } catch (SQLException failure) {
            throw storageFailure("更新 logical thread revision 失败", failure);
        }
    }

    private void insertBinding(String rawSource, String threadId, Channel channel,
                               String accountId, boolean injectionEnabled, BindingState state,
                               Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO channel_binding(
                  raw_source, thread_id, channel, account_id, injection_enabled, state, active,
                  revision, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 1, 1, ?, ?)
                """)) {
            statement.setString(1, rawSource);
            statement.setString(2, threadId);
            statement.setString(3, channel.wireName());
            statement.setString(4, accountId);
            statement.setInt(5, injectionEnabled ? 1 : 0);
            statement.setString(6, state.name());
            statement.setString(7, now.toString());
            statement.setString(8, now.toString());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw storageFailure("创建 channel binding 失败", failure);
        }
    }

    private void reactivateBinding(String rawSource, String threadId, Channel channel,
                                   String accountId, boolean injectionEnabled, BindingState state,
                                   Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE channel_binding
                SET thread_id = ?, channel = ?, account_id = ?, injection_enabled = ?,
                    state = ?, active = 1, revision = revision + 1, updated_at = ?
                WHERE raw_source = ?
                """)) {
            statement.setString(1, threadId);
            statement.setString(2, channel.wireName());
            statement.setString(3, accountId);
            statement.setInt(4, injectionEnabled ? 1 : 0);
            statement.setString(5, state.name());
            statement.setString(6, now.toString());
            statement.setString(7, rawSource);
            requireOneRow(statement.executeUpdate(), "重新激活 channel binding 失败");
        } catch (SQLException failure) {
            throw storageFailure("重新激活 channel binding 失败", failure);
        }
    }

    private void deactivateBinding(String rawSource, String threadId, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE channel_binding
                SET state = 'UNBOUND', active = 0, revision = revision + 1, updated_at = ?
                WHERE raw_source = ? AND thread_id = ? AND active = 1
                """)) {
            statement.setString(1, now.toString());
            statement.setString(2, rawSource);
            statement.setString(3, threadId);
            requireOneRow(statement.executeUpdate(), "解绑 channel binding 失败");
        } catch (SQLException failure) {
            throw storageFailure("解绑 channel binding 失败", failure);
        }
    }

    private void updateBindingInjection(String rawSource, boolean enabled, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE channel_binding
                SET injection_enabled = ?, revision = revision + 1, updated_at = ?
                WHERE raw_source = ? AND active = 1
                """)) {
            statement.setInt(1, enabled ? 1 : 0);
            statement.setString(2, now.toString());
            statement.setString(3, rawSource);
            requireOneRow(statement.executeUpdate(), "更新 binding 注入开关失败");
        } catch (SQLException failure) {
            throw storageFailure("更新 binding 注入开关失败", failure);
        }
    }

    private void updateBindingState(String rawSource, BindingState state, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE channel_binding
                SET state = ?, revision = revision + 1, updated_at = ?
                WHERE raw_source = ? AND active = 1
                """)) {
            statement.setString(1, state.name());
            statement.setString(2, now.toString());
            statement.setString(3, rawSource);
            requireOneRow(statement.executeUpdate(), "更新 binding 状态失败");
        } catch (SQLException failure) {
            throw storageFailure("更新 binding 状态失败", failure);
        }
    }

    private void insertSourceGate(String rawSource, boolean enabled, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO source_injection_gate(
                  raw_source, injection_enabled, revision, created_at, updated_at
                ) VALUES (?, ?, 1, ?, ?)
                """)) {
            statement.setString(1, rawSource);
            statement.setInt(2, enabled ? 1 : 0);
            statement.setString(3, now.toString());
            statement.setString(4, now.toString());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw storageFailure("创建 raw source 注入 gate 失败", failure);
        }
    }

    private void updateSourceGate(String rawSource, boolean enabled, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE source_injection_gate
                SET injection_enabled = ?, revision = revision + 1, updated_at = ?
                WHERE raw_source = ?
                """)) {
            statement.setInt(1, enabled ? 1 : 0);
            statement.setString(2, now.toString());
            statement.setString(3, rawSource);
            requireOneRow(statement.executeUpdate(), "更新 raw source 注入 gate 失败");
        } catch (SQLException failure) {
            throw storageFailure("更新 raw source 注入 gate 失败", failure);
        }
    }

    private void insertIdentity(String threadId, Channel channel, String channelUserId,
                                String canonicalUserId, String scope, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO channel_identity_binding(
                  thread_id, channel, channel_user_id, canonical_user_id, scope, active,
                  revision, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 1, 1, ?, ?)
                """)) {
            statement.setString(1, threadId);
            statement.setString(2, channel.wireName());
            statement.setString(3, channelUserId);
            statement.setString(4, canonicalUserId);
            statement.setString(5, scope);
            statement.setString(6, now.toString());
            statement.setString(7, now.toString());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw storageFailure("创建 identity binding 失败", failure);
        }
    }

    private void reactivateIdentity(String threadId, Channel channel, String channelUserId,
                                    String canonicalUserId, String scope, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE channel_identity_binding
                SET canonical_user_id = ?, active = 1, revision = revision + 1, updated_at = ?
                WHERE thread_id = ? AND channel = ? AND channel_user_id = ? AND scope = ?
                """)) {
            statement.setString(1, canonicalUserId);
            statement.setString(2, now.toString());
            statement.setString(3, threadId);
            statement.setString(4, channel.wireName());
            statement.setString(5, channelUserId);
            statement.setString(6, scope);
            requireOneRow(statement.executeUpdate(), "重新激活 identity binding 失败");
        } catch (SQLException failure) {
            throw storageFailure("重新激活 identity binding 失败", failure);
        }
    }

    private void deactivateIdentity(String threadId, Channel channel, String channelUserId,
                                    String scope, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE channel_identity_binding
                SET active = 0, revision = revision + 1, updated_at = ?
                WHERE thread_id = ? AND channel = ? AND channel_user_id = ?
                  AND scope = ? AND active = 1
                """)) {
            statement.setString(1, now.toString());
            statement.setString(2, threadId);
            statement.setString(3, channel.wireName());
            statement.setString(4, channelUserId);
            statement.setString(5, scope);
            requireOneRow(statement.executeUpdate(), "解绑 identity binding 失败");
        } catch (SQLException failure) {
            throw storageFailure("解绑 identity binding 失败", failure);
        }
    }

    private int deactivateSourceScopedIdentities(String threadId, String rawSource, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE channel_identity_binding
                SET active = 0, revision = revision + 1, updated_at = ?
                WHERE thread_id = ? AND scope = ? AND active = 1
                """)) {
            statement.setString(1, now.toString());
            statement.setString(2, threadId);
            statement.setString(3, scopeStorage(IdentityScope.source(rawSource)));
            return statement.executeUpdate();
        } catch (SQLException failure) {
            throw storageFailure("失活 source-scoped identity 失败", failure);
        }
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

    private static void requireOneRow(int count, String message) {
        if (count != 1) {
            throw storageFailure(message, new SQLException("unexpected affected rows: " + count));
        }
    }
}
