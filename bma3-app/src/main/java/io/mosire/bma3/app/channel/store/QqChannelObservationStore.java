package io.mosire.bma3.app.channel.store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Independent, SQLite-backed storage for QQ channel observations, route decisions, passive
 * evidence state and source-scan recovery metadata.
 *
 * <p>This class deliberately has no dependency on the app composition root, the registry, Inbox or
 * the matching kernel. It can share the existing {@code qq-channels.db} file: every write uses an
 * immediate transaction and a bounded busy timeout, so it coexists with the registry's independent
 * connection. It never changes {@code PRAGMA user_version}; its own schema version lives in
 * {@code qq_channel_observation_meta}.</p>
 *
 * <p><b>Content boundary.</b> The schema and API accept only message identifiers and safe routing
 * metadata. They do not accept or persist body text, segment text, segment URL/metadata, tokens,
 * credentials or any body-derived hash. {@code observationId} and {@code requestKey} are opaque
 * identifiers derived only from {@code (rawSource, channelMessageId)}.</p>
 *
 * <p><b>Recovery boundary.</b> {@link #beginBinding} creates a monotonically increasing binding
 * generation with an inclusive {@code startBoardSeq}. A scan starts at
 * {@code scanCursor + 1}, observations are inserted and the cursor is advanced in one transaction,
 * and failures roll both back. A stale generation can never advance the current cursor or attach
 * observations to the current binding. Replaying a completed range is a read-only no-op.</p>
 *
 * <p><b>Route boundary.</b> Route state is durable and transitions are intentionally narrow:
 * {@code PENDING_ROUTE -> DISPLAY_PENDING | EVIDENCE_ONLY | GATE_OFF}, then
 * {@code DISPLAY_PENDING -> DISPLAY_LINKED}. Terminal states and their item binding are never
 * overwritten by replay. Every successful transition is appended to an audit history; observation
 * replay never changes a prior decision.</p>
 *
 * <p>This storage is intentionally passive. It persists evidence state supplied by a future
 * matcher but does not compare messages, create Inbox items or wake anything.</p>
 */
public final class QqChannelObservationStore implements AutoCloseable {

    /** Version of this store's private schema, separate from QqChannelRegistry user_version. */
    public static final int SCHEMA_VERSION = 7;

    /** Conventional shared channel database name. */
    public static final String DATABASE_FILE_NAME = "qq-channels.db";

    public static final int DEFAULT_BUSY_TIMEOUT_MS = 10_000;
    private static final int MAX_PAGE_SIZE = 500;
    private static final int MAX_SCAN_BATCH = 5_000;
    private static final int MAX_ID_CODE_POINTS = 512;
    private static final int MIN_PRIORITY = -1_000;
    private static final int MAX_PRIORITY = 1_000;
    private static final int MAX_TIME_TOLERANCE_SECONDS = 300;

    /** Conservative default used only by compatibility constructors. */
    public static final int DEFAULT_STRICT_TIME_TOLERANCE_SECONDS = 5;

    private static final Pattern THREAD_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final DateTimeFormatter TIMESTAMP =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("SQLite JDBC 驱动缺失", failure);
        }
    }

    /** Channel wire names are stable lower-case values shared with the design. */
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

        static Channel fromWire(String value) {
            if (value == null) {
                throw new IllegalArgumentException("channel 不能为空");
            }
            for (Channel channel : values()) {
                if (channel.wireName.equalsIgnoreCase(value.strip())) {
                    return channel;
                }
            }
            throw new IllegalArgumentException("未知 channel");
        }
    }

    /** Direction of the observed message relative to the bot. */
    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    /** Explicit reliability of the source timestamp persisted with an observation. */
    public enum TimeReliability {
        RELIABLE,
        FALLBACK,
        UNKNOWN
    }

    /** Explicit completeness of the decoded content. Body text itself is never stored here. */
    public enum ContentIntegrity {
        COMPLETE,
        INCOMPLETE,
        TRUNCATED,
        UNKNOWN
    }

    /** Durable route decisions needed by the future router. */
    public enum RouteState {
        PENDING_ROUTE,
        DISPLAY_PENDING,
        DISPLAY_LINKED,
        EVIDENCE_ONLY,
        GATE_OFF
    }

    /** Passive correlation/evidence status supplied by a future matcher. */
    public enum CorrelationState {
        CHANNEL_ONLY,
        UNVERIFIED,
        UNVERIFIED_MATCH,
        MATCHED,
        CONFLICT,
        /**
         * A fallback-window opposite-channel candidate already has display history, but identity
         * or content evidence is not strong enough to claim a match. This state is evidence only:
         * it must not wake or assert MATCHED, independent of matching mode.
         */
        SUPPRESSED_UNVERIFIED
    }

    /** Snapshot of whether the preferred display channel was usable at intent time. */
    public enum ChannelAvailability {
        AVAILABLE,
        UNAVAILABLE,
        UNKNOWN
    }

    /** Matching mode snapshot captured with the pre-board intent. */
    public enum MatchingMode {
        STRICT,
        LOOSE,
        OFF
    }

    /**
     * Historical sender-identity resolution captured with the intent/observation. Recovery must
     * never substitute a current mapping for an old missing/ambiguous one.
     */
    public enum IdentityStatus {
        MAPPED,
        UNMAPPED,
        AMBIGUOUS
    }

    /** Stable, body-free route reasons supplied by the router. */
    public enum RouteReason {
        PENDING,
        PREFERRED_DISPLAY,
        FALLBACK_DISPLAY,
        NON_PREFERRED_EVIDENCE,
        LATE_EVIDENCE_FROZEN_ITEM,
        SUPPRESSED_FALLBACK_UNVERIFIED,
        INJECTION_GATE_OFF
    }

    /**
     * One explicit active-binding tuple used to derive a thread pairing epoch. This intentionally
     * keeps per-channel generations independent; callers must not compare the numeric generation
     * of two different raw sources.
     */
    public record ActiveBindingRef(
            Channel channel,
            String accountId,
            String rawSource,
            long generation) {

        public ActiveBindingRef {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(rawSource, "rawSource");
            if (generation < 1) {
                throw new IllegalArgumentException("generation 必须为正");
            }
        }
    }

    /** Append-only source binding lifecycle event kind. */
    public enum BindingEventKind {
        BOUND,
        UNBOUND
    }

    /** Append-only correlation pair audit action. */
    public enum CorrelationAuditAction {
        CREATE,
        STATE_CHANGE,
        RELEASE
    }

    /** Durable EVIDENCE_ONLY target application state. */
    public enum EvidenceOutboxState {
        PENDING,
        APPLIED,
        TARGET_MISSING
    }

    /**
     * Durable pre-board intent state. RECORDED means the intent exists but no board commit has
     * been confirmed; BOARD_COMMITTED means the local board sequence is known; OBSERVATION_COMMITTED
     * means the observation and its original route snapshot are durable.
     */
    public enum IngestIntentState {
        RECORDED,
        BOARD_COMMITTED,
        OBSERVATION_COMMITTED
    }

    public enum ErrorCode {
        INVALID_ARGUMENT,
        INVALID_THREAD_ID,
        INVALID_RAW_SOURCE,
        INVALID_CHANNEL,
        ACCOUNT_MISMATCH,
        OBSERVATION_NOT_FOUND,
        OBSERVATION_CONFLICT,
        ROUTE_CONFLICT,
        ILLEGAL_ROUTE_TRANSITION,
        ITEM_ID_REQUIRED,
        CORRELATION_CONFLICT,
        SOURCE_BINDING_NOT_FOUND,
        SOURCE_ALREADY_BOUND,
        SOURCE_NOT_BOUND,
        BINDING_MISMATCH,
        BINDING_CONFLICT,
        GENERATION_MISMATCH,
        CURSOR_REGRESSION,
        BOARD_SEQ_BEFORE_BINDING_BOUNDARY,
        INGEST_INTENT_CONFLICT,
        INGEST_INTENT_NOT_FOUND,
        INGEST_INTENT_STATE_CONFLICT,
        CORRELATION_PAIR_NOT_FOUND,
        EVIDENCE_OUTBOX_NOT_FOUND,
        EVIDENCE_OUTBOX_STATE_CONFLICT,
        CURSOR_EXHAUSTED,
        STORE_CLOSED,
        STORAGE_ERROR
    }

    /** Stable, safe failure category for callers; messages never contain body content/secrets. */
    public static final class StoreException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final ErrorCode code;

        public StoreException(ErrorCode code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public StoreException(ErrorCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code");
        }

        public ErrorCode code() {
            return code;
        }
    }

    /**
     * Safe input for an observation write. There is intentionally no text, segment or URL field.
     * No field is body-derived except IDs copied from the channel.
     */
    public record ObservationDraft(
            String threadId,
            Channel channel,
            String accountId,
            String rawSource,
            String channelMessageId,
            long boardLocalSeq,
            String senderId,
            Optional<String> canonicalSenderId,
            IdentityStatus identityStatus,
            long identityRevision,
            Direction direction,
            Instant messageAt,
            Instant observedAt,
            int priority,
            TimeReliability timeReliability,
            ContentIntegrity contentIntegrity,
            boolean structuredMentionsTrusted,
            int strictTimeToleranceSeconds,
            String bindingEpochId,
            RouteReason routeReason) {

        public ObservationDraft {
            senderId = normalizeSenderId(senderId);
            bindingEpochId = normalizeEpoch(bindingEpochId);
            Objects.requireNonNull(routeReason, "routeReason");
            requireTimeTolerance(strictTimeToleranceSeconds);
        }

        /** Compatibility constructor: no historical identity mapping snapshot is assumed. */
        public ObservationDraft(String threadId, Channel channel, String accountId,
                                 String rawSource, String channelMessageId, long boardLocalSeq,
                                 String senderId, Direction direction, Instant messageAt,
                                 Instant observedAt, int priority,
                                 TimeReliability timeReliability,
                                 ContentIntegrity contentIntegrity) {
            this(threadId, channel, accountId, rawSource, channelMessageId, boardLocalSeq,
                    senderId, Optional.empty(), IdentityStatus.UNMAPPED, 0,
                    direction, messageAt, observedAt, priority, timeReliability,
                    contentIntegrity, false, DEFAULT_STRICT_TIME_TOLERANCE_SECONDS,
                    "", RouteReason.PENDING);
        }

        /** Compatibility constructor before tolerance snapshot was added; defaults to 5s. */
        public ObservationDraft(String threadId, Channel channel, String accountId,
                                 String rawSource, String channelMessageId, long boardLocalSeq,
                                 String senderId, Optional<String> canonicalSenderId,
                                 IdentityStatus identityStatus, long identityRevision,
                                 Direction direction, Instant messageAt, Instant observedAt,
                                 int priority, TimeReliability timeReliability,
                                 ContentIntegrity contentIntegrity, String bindingEpochId,
                                 RouteReason routeReason) {
            this(threadId, channel, accountId, rawSource, channelMessageId, boardLocalSeq,
                    senderId, canonicalSenderId, identityStatus, identityRevision,
                    direction, messageAt, observedAt, priority, timeReliability,
                    contentIntegrity, false, DEFAULT_STRICT_TIME_TOLERANCE_SECONDS,
                    bindingEpochId, routeReason);
        }

        /** Compatibility constructor before structured-mention trust was added; defaults false. */
        public ObservationDraft(String threadId, Channel channel, String accountId,
                                 String rawSource, String channelMessageId, long boardLocalSeq,
                                 String senderId, Optional<String> canonicalSenderId,
                                 IdentityStatus identityStatus, long identityRevision,
                                 Direction direction, Instant messageAt, Instant observedAt,
                                 int priority, TimeReliability timeReliability,
                                 ContentIntegrity contentIntegrity,
                                 int strictTimeToleranceSeconds, String bindingEpochId,
                                 RouteReason routeReason) {
            this(threadId, channel, accountId, rawSource, channelMessageId, boardLocalSeq,
                    senderId, canonicalSenderId, identityStatus, identityRevision,
                    direction, messageAt, observedAt, priority, timeReliability,
                    contentIntegrity, false, strictTimeToleranceSeconds,
                    bindingEpochId, routeReason);
        }

        /** Return a diagnostic form containing IDs/state only, never message content. */
        @Override
        public String toString() {
            return "ObservationDraft[threadId=" + threadId
                    + ", channel=" + channel
                    + ", rawSource=" + rawSource
                    + ", channelMessageId=" + channelMessageId
                    + ", boardLocalSeq=" + boardLocalSeq
                    + ", identityStatus=" + identityStatus
                    + ", identityRevision=" + identityRevision
                    + ", direction=" + direction
                    + ", messageAt=" + messageAt
                    + ", priority=" + priority
                    + ", timeReliability=" + timeReliability
                    + ", contentIntegrity=" + contentIntegrity + "]";
        }
    }

    /** Persisted observation, including its current route and passive evidence state. */
    public record Observation(
            String observationId,
            String threadId,
            Channel channel,
            String accountId,
            String rawSource,
            String channelMessageId,
            long boardLocalSeq,
            String senderId,
            Optional<String> canonicalSenderId,
            IdentityStatus identityStatus,
            long identityRevision,
            long bindingGeneration,
            String bindingEpochId,
            Optional<String> ingestIntentKey,
            RouteReason routeReason,
            Direction direction,
            Instant messageAt,
            Instant observedAt,
            int priority,
            TimeReliability timeReliability,
            ContentIntegrity contentIntegrity,
            boolean structuredMentionsTrusted,
            int strictTimeToleranceSeconds,
            RouteState routeState,
            String requestKey,
            Optional<String> itemId,
            long routeRevision,
            Instant routeUpdatedAt,
            CorrelationState correlationState,
            Optional<String> peerObservationId,
            long correlationRevision,
            Instant correlationUpdatedAt) {

        public Observation {
            Objects.requireNonNull(observationId, "observationId");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(rawSource, "rawSource");
            Objects.requireNonNull(channelMessageId, "channelMessageId");
            senderId = normalizeSenderId(senderId);
            canonicalSenderId = canonicalSenderId == null
                    ? Optional.empty() : canonicalSenderId;
            Objects.requireNonNull(identityStatus, "identityStatus");
            if (identityRevision < 0) {
                throw new IllegalArgumentException("identityRevision 不能为负");
            }
            requireIdentityConsistency(canonicalSenderId, identityStatus);
            if (bindingGeneration < 0) {
                throw new IllegalArgumentException("bindingGeneration 不能为负");
            }
            bindingEpochId = normalizeEpoch(bindingEpochId);
            ingestIntentKey = ingestIntentKey == null ? Optional.empty() : ingestIntentKey;
            Objects.requireNonNull(routeReason, "routeReason");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(messageAt, "messageAt");
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(timeReliability, "timeReliability");
            Objects.requireNonNull(contentIntegrity, "contentIntegrity");
            requireTimeTolerance(strictTimeToleranceSeconds);
            Objects.requireNonNull(routeState, "routeState");
            Objects.requireNonNull(requestKey, "requestKey");
            itemId = itemId == null ? Optional.empty() : itemId;
            Objects.requireNonNull(routeUpdatedAt, "routeUpdatedAt");
            Objects.requireNonNull(correlationState, "correlationState");
            peerObservationId = peerObservationId == null ? Optional.empty() : peerObservationId;
            Objects.requireNonNull(correlationUpdatedAt, "correlationUpdatedAt");
        }

        /** Safe title for logs/errors: no sender, raw source or body-derived value. */
        @Override
        public String toString() {
            return "Observation[observationId=" + observationId
                    + ", threadId=" + threadId
                    + ", channel=" + channel.wireName()
                    + ", channelMessageId=" + channelMessageId
                    + ", boardLocalSeq=" + boardLocalSeq
                    + ", canonicalSenderId=" + canonicalSenderId
                    + ", identityStatus=" + identityStatus
                    + ", identityRevision=" + identityRevision
                    + ", bindingGeneration=" + bindingGeneration
                    + ", bindingEpochId=" + bindingEpochId
                    + ", ingestIntentKey=" + ingestIntentKey
                    + ", routeReason=" + routeReason
                    + ", routeState=" + routeState
                    + ", correlationState=" + correlationState + "]";
        }
    }

    public record ObservationWrite(Observation observation, boolean changed) {
        public ObservationWrite {
            Objects.requireNonNull(observation, "observation");
        }
    }

    /**
     * Stable keyset-pagination cursor. Ordering is {@code (messageAt, observationId)} ascending.
     */
    public record ObservationCursor(Instant messageAt, String observationId) {
        public ObservationCursor {
            Objects.requireNonNull(messageAt, "messageAt");
            if (observationId == null || observationId.isBlank()) {
                throw new IllegalArgumentException("observationId 不能为空");
            }
        }
    }

    public record ObservationPage(List<Observation> items, boolean hasMore,
                                  Optional<ObservationCursor> nextCursor) {
        public ObservationPage {
            items = List.copyOf(items);
            nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
        }
    }

    /** Stable keyset cursor ordered by {@code (observedAt, observationId)}. */
    public record ObservedAtCursor(Instant observedAt, String observationId) {
        public ObservedAtCursor {
            Objects.requireNonNull(observedAt, "observedAt");
            if (observationId == null || observationId.isBlank()) {
                throw new IllegalArgumentException("observationId 不能为空");
            }
        }
    }

    public record ObservedAtObservationPage(
            List<Observation> items,
            boolean hasMore,
            Optional<ObservedAtCursor> nextCursor) {

        public ObservedAtObservationPage {
            items = List.copyOf(items);
            nextCursor = nextCursor == null ? Optional.empty() : nextCursor;
        }
    }

    /** Global route-recovery page across all threads; independent of active bindings. */
    public record RouteObservationPage(
            List<Observation> items,
            boolean hasMore,
            Optional<String> nextObservationId) {

        public RouteObservationPage {
            items = List.copyOf(items);
            nextObservationId = nextObservationId == null
                    ? Optional.empty() : nextObservationId;
        }
    }

    public record RouteUpdate(Observation observation, RouteState previousState, boolean changed) {
        public RouteUpdate {
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(previousState, "previousState");
        }
    }

    public record RouteHistoryEntry(
            long id,
            String observationId,
            Optional<RouteState> fromState,
            RouteState toState,
            Optional<String> itemId,
            Instant changedAt) {

        public RouteHistoryEntry {
            Objects.requireNonNull(observationId, "observationId");
            fromState = fromState == null ? Optional.empty() : fromState;
            Objects.requireNonNull(toState, "toState");
            itemId = itemId == null ? Optional.empty() : itemId;
            Objects.requireNonNull(changedAt, "changedAt");
        }
    }

    public record SourceBinding(
            String rawSource,
            long generation,
            String threadId,
            Channel channel,
            String accountId,
            boolean active,
            long startBoardSeq,
            long scanCursor,
            Instant createdAt,
            Instant updatedAt) {

        public SourceBinding {
            Objects.requireNonNull(rawSource, "rawSource");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (generation < 1) {
                throw new IllegalArgumentException("generation 必须为正");
            }
            if (startBoardSeq < 0 || scanCursor < startBoardSeq - 1) {
                throw new IllegalArgumentException("board cursor 不自洽");
            }
        }

        /** Next board sequence to scan, or empty when the long range is exhausted. */
        public OptionalLong nextBoardSeq() {
            return scanCursor == Long.MAX_VALUE
                    ? OptionalLong.empty()
                    : OptionalLong.of(scanCursor + 1);
        }
    }

    public record BindingUpdate(SourceBinding binding, boolean changed) {
        public BindingUpdate {
            Objects.requireNonNull(binding, "binding");
        }
    }

    /** Global source-binding page; active and inactive rows are both included. */
    public record SourceBindingPage(
            List<SourceBinding> items,
            boolean hasMore,
            Optional<String> nextRawSource) {

        public SourceBindingPage {
            items = List.copyOf(items);
            nextRawSource = nextRawSource == null ? Optional.empty() : nextRawSource;
        }
    }

    public record BindingEvent(
            long id,
            String rawSource,
            long generation,
            String threadId,
            Channel channel,
            String accountId,
            long startBoardSeq,
            Optional<Long> endBoardSeq,
            BindingEventKind kind,
            Instant createdAt) {

        public BindingEvent {
            Objects.requireNonNull(rawSource, "rawSource");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(accountId, "accountId");
            endBoardSeq = endBoardSeq == null ? Optional.empty() : endBoardSeq;
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record ScanWindow(
            String rawSource,
            long generation,
            long startBoardSeq,
            long scanCursor,
            long fromBoardSeq) {

        public ScanWindow {
            Objects.requireNonNull(rawSource, "rawSource");
        }
    }

    public record ScanCommit(SourceBinding binding, int inserted, int unchanged, boolean advanced) {
        public ScanCommit {
            Objects.requireNonNull(binding, "binding");
            if (inserted < 0 || unchanged < 0) {
                throw new IllegalArgumentException("scan count 不能为负");
            }
        }
    }

    /**
     * One passive correlation result supplied by a future matcher. This store never derives it.
     */
    public record CorrelationUpdate(
            String rawSource,
            String channelMessageId,
            CorrelationState state,
            String peerRawSource,
            String peerChannelMessageId) {

        public CorrelationUpdate {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(rawSource, "rawSource");
            Objects.requireNonNull(channelMessageId, "channelMessageId");
        }

        public static CorrelationUpdate channelOnly(String rawSource, String messageId) {
            return new CorrelationUpdate(rawSource, messageId,
                    CorrelationState.CHANNEL_ONLY, null, null);
        }

        public static CorrelationUpdate unverified(String rawSource, String messageId) {
            return new CorrelationUpdate(rawSource, messageId,
                    CorrelationState.UNVERIFIED, null, null);
        }

        public static CorrelationUpdate withPeer(String rawSource, String messageId,
                                                 CorrelationState state,
                                                 String peerRawSource,
                                                 String peerMessageId) {
            return new CorrelationUpdate(rawSource, messageId, state,
                    peerRawSource, peerMessageId);
        }
    }

    /** Per-thread route/evidence counts; every enum value is present, including zeros. */
    public record StatusCounts(
            String threadId,
            long observations,
            Map<RouteState, Long> routeCounts,
            Map<CorrelationState, Long> correlationCounts) {

        public StatusCounts {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(routeCounts, "routeCounts");
            Objects.requireNonNull(correlationCounts, "correlationCounts");
            EnumMap<RouteState, Long> safeRoutes = new EnumMap<>(RouteState.class);
            safeRoutes.putAll(routeCounts);
            EnumMap<CorrelationState, Long> safeCorrelations =
                    new EnumMap<>(CorrelationState.class);
            safeCorrelations.putAll(correlationCounts);
            routeCounts = Collections.unmodifiableMap(safeRoutes);
            correlationCounts = Collections.unmodifiableMap(safeCorrelations);
        }
    }

    /**
     * Body-free snapshot written after the priority/drop gate and before the message board append.
     *
     * <p>The caller must hold the process-wide routing/binding lock while reading the active
     * binding and recording this intent, then keep that same lock through the board append and
     * {@link #markIngestIntentBoardCommitted}. The intent captures the binding generation and the
     * effective gate/route decision at decision time so recovery never re-evaluates an old message
     * against a newer binding or gate.</p>
     */
    public record IngestIntentDraft(
            String intentKey,
            String threadId,
            Channel channel,
            String accountId,
            String rawSource,
            String channelMessageId,
            String senderId,
            Optional<String> canonicalSenderId,
            IdentityStatus identityStatus,
            Direction direction,
            Instant messageAt,
            Instant observedAt,
            int priority,
            TimeReliability timeReliability,
            ContentIntegrity contentIntegrity,
            boolean structuredMentionsTrusted,
            long bindingGeneration,
            boolean gateEnabled,
            String bindingEpochId,
            RouteReason routeReason,
            Channel preferredChannel,
            ChannelAvailability preferredChannelAvailability,
            Channel selectedChannel,
            MatchingMode matchingMode,
            int strictTimeToleranceSeconds,
            long identityRevision,
            RouteState routeDecision,
            String routeItemId) {

        public IngestIntentDraft {
            senderId = normalizeSenderId(senderId);
            bindingEpochId = normalizeEpoch(bindingEpochId);
            Objects.requireNonNull(routeReason, "routeReason");
            requireTimeTolerance(strictTimeToleranceSeconds);
        }

        /**
         * Compatibility constructor for callers that do not yet supply display/identity
         * snapshots. New router code should use the full canonical constructor.
         */
        public IngestIntentDraft(String intentKey, String threadId, Channel channel,
                                 String accountId, String rawSource, String channelMessageId,
                                 String senderId, Direction direction, Instant messageAt,
                                 Instant observedAt, int priority,
                                 TimeReliability timeReliability,
                                 ContentIntegrity contentIntegrity, long bindingGeneration,
                                 boolean gateEnabled, RouteState routeDecision,
                                 String routeItemId) {
            this(intentKey, threadId, channel, accountId, rawSource, channelMessageId,
                    senderId, Optional.empty(), IdentityStatus.UNMAPPED,
                    direction, messageAt, observedAt, priority, timeReliability,
                    contentIntegrity, false, bindingGeneration, gateEnabled, "",
                    defaultRouteReason(routeDecision, routeItemId), null,
                    ChannelAvailability.UNKNOWN,
                    routeDecision == RouteState.DISPLAY_PENDING ? channel : null,
                    MatchingMode.STRICT, DEFAULT_STRICT_TIME_TOLERANCE_SECONDS, 0,
                    routeDecision, routeItemId);
        }

        /** Compatibility constructor before tolerance snapshot was added; defaults to 5s. */
        public IngestIntentDraft(String intentKey, String threadId, Channel channel,
                                 String accountId, String rawSource, String channelMessageId,
                                 String senderId, Optional<String> canonicalSenderId,
                                 IdentityStatus identityStatus, Direction direction,
                                 Instant messageAt, Instant observedAt, int priority,
                                 TimeReliability timeReliability,
                                 ContentIntegrity contentIntegrity, long bindingGeneration,
                                 boolean gateEnabled, String bindingEpochId,
                                 RouteReason routeReason, Channel preferredChannel,
                                 ChannelAvailability preferredChannelAvailability,
                                 Channel selectedChannel, MatchingMode matchingMode,
                                 long identityRevision, RouteState routeDecision,
                                 String routeItemId) {
            this(intentKey, threadId, channel, accountId, rawSource, channelMessageId,
                    senderId, canonicalSenderId, identityStatus, direction, messageAt,
                    observedAt, priority, timeReliability, contentIntegrity, false,
                    bindingGeneration, gateEnabled, bindingEpochId, routeReason,
                    preferredChannel, preferredChannelAvailability, selectedChannel,
                    matchingMode, DEFAULT_STRICT_TIME_TOLERANCE_SECONDS, identityRevision,
                    routeDecision, routeItemId);
        }

        /** Compatibility constructor before structured-mention trust was added; defaults false. */
        public IngestIntentDraft(String intentKey, String threadId, Channel channel,
                                 String accountId, String rawSource, String channelMessageId,
                                 String senderId, Optional<String> canonicalSenderId,
                                 IdentityStatus identityStatus, Direction direction,
                                 Instant messageAt, Instant observedAt, int priority,
                                 TimeReliability timeReliability,
                                 ContentIntegrity contentIntegrity, long bindingGeneration,
                                 boolean gateEnabled, String bindingEpochId,
                                 RouteReason routeReason, Channel preferredChannel,
                                 ChannelAvailability preferredChannelAvailability,
                                 Channel selectedChannel, MatchingMode matchingMode,
                                 int strictTimeToleranceSeconds, long identityRevision,
                                 RouteState routeDecision, String routeItemId) {
            this(intentKey, threadId, channel, accountId, rawSource, channelMessageId,
                    senderId, canonicalSenderId, identityStatus, direction, messageAt,
                    observedAt, priority, timeReliability, contentIntegrity, false,
                    bindingGeneration, gateEnabled, bindingEpochId, routeReason,
                    preferredChannel, preferredChannelAvailability, selectedChannel,
                    matchingMode, strictTimeToleranceSeconds, identityRevision,
                    routeDecision, routeItemId);
        }

        @Override
        public String toString() {
            return "IngestIntentDraft[intentKey=" + intentKey
                    + ", threadId=" + threadId
                    + ", channel=" + channel
                    + ", rawSource=" + rawSource
                    + ", channelMessageId=" + channelMessageId
                    + ", canonicalSenderId=" + canonicalSenderId
                    + ", identityStatus=" + identityStatus
                    + ", bindingGeneration=" + bindingGeneration
                    + ", gateEnabled=" + gateEnabled
                    + ", bindingEpochId=" + bindingEpochId
                    + ", routeReason=" + routeReason
                    + ", preferredChannel=" + preferredChannel
                    + ", preferredChannelAvailability=" + preferredChannelAvailability
                    + ", selectedChannel=" + selectedChannel
                    + ", matchingMode=" + matchingMode
                    + ", identityRevision=" + identityRevision
                    + ", routeDecision=" + routeDecision + "]";
        }
    }

    /** Persisted pre-board intent; contains no body, segment metadata, URL, token or body hash. */
    public record IngestIntent(
            long id,
            String intentKey,
            String observationId,
            String requestKey,
            String threadId,
            Channel channel,
            String accountId,
            String rawSource,
            String channelMessageId,
            String senderId,
            Optional<String> canonicalSenderId,
            IdentityStatus identityStatus,
            Direction direction,
            Instant messageAt,
            Instant observedAt,
            int priority,
            TimeReliability timeReliability,
            ContentIntegrity contentIntegrity,
            boolean structuredMentionsTrusted,
            long bindingGeneration,
            boolean gateEnabled,
            String bindingEpochId,
            RouteReason routeReason,
            Optional<Channel> preferredChannel,
            ChannelAvailability preferredChannelAvailability,
            Optional<Channel> selectedChannel,
            MatchingMode matchingMode,
            int strictTimeToleranceSeconds,
            long identityRevision,
            RouteState routeDecision,
            Optional<String> routeItemId,
            IngestIntentState state,
            Optional<Long> boardLocalSeq,
            Instant createdAt,
            Instant updatedAt) {

        public IngestIntent {
            Objects.requireNonNull(intentKey, "intentKey");
            Objects.requireNonNull(observationId, "observationId");
            Objects.requireNonNull(requestKey, "requestKey");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(rawSource, "rawSource");
            Objects.requireNonNull(channelMessageId, "channelMessageId");
            senderId = normalizeSenderId(senderId);
            canonicalSenderId = canonicalSenderId == null
                    ? Optional.empty() : canonicalSenderId;
            Objects.requireNonNull(identityStatus, "identityStatus");
            requireIdentityConsistency(canonicalSenderId, identityStatus);
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(messageAt, "messageAt");
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(timeReliability, "timeReliability");
            Objects.requireNonNull(contentIntegrity, "contentIntegrity");
            bindingEpochId = normalizeEpoch(bindingEpochId);
            Objects.requireNonNull(routeReason, "routeReason");
            preferredChannel = preferredChannel == null ? Optional.empty() : preferredChannel;
            Objects.requireNonNull(preferredChannelAvailability, "preferredChannelAvailability");
            selectedChannel = selectedChannel == null ? Optional.empty() : selectedChannel;
            Objects.requireNonNull(matchingMode, "matchingMode");
            requireTimeTolerance(strictTimeToleranceSeconds);
            if (identityRevision < 0) {
                throw new IllegalArgumentException("identityRevision 不能为负");
            }
            Objects.requireNonNull(routeDecision, "routeDecision");
            routeItemId = routeItemId == null ? Optional.empty() : routeItemId;
            Objects.requireNonNull(state, "state");
            boardLocalSeq = boardLocalSeq == null ? Optional.empty() : boardLocalSeq;
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }

        /** True until the observation is durable with its original route snapshot. */
        public boolean pendingObservation() {
            return state != IngestIntentState.OBSERVATION_COMMITTED;
        }

        @Override
        public String toString() {
            return "IngestIntent[intentKey=" + intentKey
                    + ", observationId=" + observationId
                    + ", requestKey=" + requestKey
                    + ", threadId=" + threadId
                    + ", channel=" + channel.wireName()
                    + ", channelMessageId=" + channelMessageId
                    + ", bindingGeneration=" + bindingGeneration
                    + ", gateEnabled=" + gateEnabled
                    + ", preferredChannel=" + preferredChannel
                    + ", preferredChannelAvailability=" + preferredChannelAvailability
                    + ", selectedChannel=" + selectedChannel
                    + ", matchingMode=" + matchingMode
                    + ", identityRevision=" + identityRevision
                    + ", routeDecision=" + routeDecision
                    + ", state=" + state
                    + ", boardLocalSeq=" + boardLocalSeq + "]";
        }
    }

    public record IngestIntentWrite(IngestIntent intent, boolean changed) {
        public IngestIntentWrite {
            Objects.requireNonNull(intent, "intent");
        }
    }

    public record IngestPromotion(
            IngestIntent intent,
            Observation observation,
            boolean observationCreated,
            boolean changed) {

        public IngestPromotion {
            Objects.requireNonNull(intent, "intent");
            Objects.requireNonNull(observation, "observation");
        }
    }

    public record IngestIntentCounts(
            String rawSource,
            long total,
            long recorded,
            long boardCommitted,
            long observationCommitted,
            long pending) {

        public IngestIntentCounts {
            Objects.requireNonNull(rawSource, "rawSource");
        }
    }

    /** Global recovery page across all raw sources, including currently unbound sources. */
    public record IngestIntentPage(
            List<IngestIntent> items,
            boolean hasMore,
            long nextAfterId) {

        public IngestIntentPage {
            items = List.copyOf(items);
            if (nextAfterId < 0) {
                throw new IllegalArgumentException("nextAfterId 不能为负");
            }
        }
    }

    /** Durable symmetric evidence pair; unique on each observation id. */
    public record CorrelationPair(
            String pairKey,
            String observationAId,
            String observationBId,
            CorrelationState state,
            long revision,
            Instant createdAt,
            Instant updatedAt) {

        public CorrelationPair {
            Objects.requireNonNull(pairKey, "pairKey");
            Objects.requireNonNull(observationAId, "observationAId");
            Objects.requireNonNull(observationBId, "observationBId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    public record CorrelationPairWrite(
            CorrelationPair pair,
            Observation first,
            Observation second,
            boolean changed) {

        public CorrelationPairWrite {
            Objects.requireNonNull(pair, "pair");
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
        }
    }

    public record CorrelationPairRelease(
            CorrelationPair pair,
            List<Observation> observations,
            boolean changed) {

        public CorrelationPairRelease {
            Objects.requireNonNull(pair, "pair");
            observations = List.copyOf(observations);
        }
    }

    /** Complete observations for both sides of one surviving pair. */
    public record CorrelationPairObservations(
            CorrelationPair pair,
            Observation first,
            Observation second) {

        public CorrelationPairObservations {
            Objects.requireNonNull(pair, "pair");
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
        }
    }

    /** Append-only, body-free correlation audit entry. Survives pair release. */
    public record CorrelationAuditEntry(
            long id,
            String pairKey,
            CorrelationAuditAction action,
            String observationAId,
            String observationBId,
            Optional<CorrelationState> fromState,
            Optional<CorrelationState> toState,
            Instant changedAt) {

        public CorrelationAuditEntry {
            Objects.requireNonNull(pairKey, "pairKey");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(observationAId, "observationAId");
            Objects.requireNonNull(observationBId, "observationBId");
            fromState = fromState == null ? Optional.empty() : fromState;
            toState = toState == null ? Optional.empty() : toState;
            Objects.requireNonNull(changedAt, "changedAt");
        }
    }

    /** One non-exclusive fallback candidate supplied by the router. */
    public record EvidenceCandidateDraft(
            String candidateRawSource,
            String candidateChannelMessageId,
            String candidateItemId) {

        public EvidenceCandidateDraft {
            Objects.requireNonNull(candidateRawSource, "candidateRawSource");
            Objects.requireNonNull(candidateChannelMessageId, "candidateChannelMessageId");
        }
    }

    /** Persisted non-exclusive fallback candidate. Never represented as a 1:1 correlation pair. */
    public record EvidenceCandidateRecord(
            long id,
            String sourceObservationId,
            String candidateObservationId,
            Optional<String> candidateItemId,
            RouteReason reason,
            String threadId,
            String bindingEpochId,
            Instant createdAt) {

        public EvidenceCandidateRecord {
            Objects.requireNonNull(sourceObservationId, "sourceObservationId");
            Objects.requireNonNull(candidateObservationId, "candidateObservationId");
            candidateItemId = candidateItemId == null ? Optional.empty() : candidateItemId;
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(bindingEpochId, "bindingEpochId");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record SuppressedFallbackWrite(
            Observation sourceObservation,
            List<EvidenceCandidateRecord> candidates,
            int inserted,
            int unchanged,
            boolean changed) {

        public SuppressedFallbackWrite {
            Objects.requireNonNull(sourceObservation, "sourceObservation");
            candidates = List.copyOf(candidates);
            if (inserted < 0 || unchanged < 0) {
                throw new IllegalArgumentException("candidate count 不能为负");
            }
        }
    }

    /** One durable EVIDENCE_ONLY target application outbox row. */
    public record EvidenceOutboxEntry(
            Observation observation,
            String targetItemId,
            EvidenceOutboxState state,
            long revision,
            Instant createdAt,
            Instant updatedAt) {

        public EvidenceOutboxEntry {
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(targetItemId, "targetItemId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /** Global outbox page; includes only EVIDENCE_ONLY observations with an explicit target. */
    public record EvidenceOutboxPage(
            List<EvidenceOutboxEntry> items,
            boolean hasMore,
            Optional<String> nextObservationId) {

        public EvidenceOutboxPage {
            items = List.copyOf(items);
            nextObservationId = nextObservationId == null
                    ? Optional.empty() : nextObservationId;
        }
    }

    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    private final Connection connection;
    private final Clock clock;
    private boolean closed;

    public QqChannelObservationStore(Path databaseFile) {
        this(databaseFile, Clock.systemUTC(), Duration.ofMillis(DEFAULT_BUSY_TIMEOUT_MS));
    }

    public QqChannelObservationStore(Path databaseFile, Clock clock) {
        this(databaseFile, clock, Duration.ofMillis(DEFAULT_BUSY_TIMEOUT_MS));
    }

    public QqChannelObservationStore(Path databaseFile, Clock clock, Duration busyTimeout) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(busyTimeout, "busyTimeout");
        long timeoutMs = busyTimeout.toMillis();
        if (timeoutMs < 1 || timeoutMs > 300_000) {
            throw new IllegalArgumentException("busyTimeout 必须在 1ms..300s 内");
        }

        Path absolute = databaseFile.toAbsolutePath().normalize();
        Connection opened = null;
        try {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            opened = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            this.connection = opened;
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = " + timeoutMs);
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA foreign_keys = ON");
            }
            initializeSchema();
        } catch (Exception failure) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw new IllegalStateException("打开 QQ 渠道 observation store 失败", failure);
        }
    }

    /**
     * Derives a stable pairing epoch from the explicit active-binding tuple. Each channel source
     * keeps its own generation; numeric generations from different raw sources are never compared.
     */
    public static String bindingEpochId(String threadId, List<ActiveBindingRef> activeBindings) {
        if (threadId == null || threadId.isBlank() || containsControl(threadId)) {
            throw new IllegalArgumentException("threadId 非法");
        }
        Objects.requireNonNull(activeBindings, "activeBindings");
        List<String> tuples = new ArrayList<>();
        for (ActiveBindingRef binding : activeBindings) {
            Objects.requireNonNull(binding, "binding");
            tuples.add(binding.channel().wireName() + "\0" + binding.accountId()
                    + "\0" + binding.rawSource() + "\0" + binding.generation());
        }
        Collections.sort(tuples);
        return "epoch-" + sha256Hex("qq-channel-binding-epoch-v1\0" + threadId
                + "\0" + String.join("\0", tuples));
    }

    public String observationIdFor(String rawSource, String channelMessageId) {
        requireIdentifier(rawSource, "rawSource");
        requireIdentifier(channelMessageId, "channelMessageId");
        return "obs-" + sha256Hex("qq-channel-observation-id-v1\0"
                + rawSource + "\0" + channelMessageId);
    }

    public String requestKeyFor(String rawSource, String channelMessageId) {
        requireIdentifier(rawSource, "rawSource");
        requireIdentifier(channelMessageId, "channelMessageId");
        return "qqroute-" + sha256Hex("qq-channel-route-request-v1\0"
                + rawSource + "\0" + channelMessageId);
    }

    private void ensureColumn(Statement statement,
                               String table,
                               String column,
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

    private void initializeSchema() throws SQLException {
        transaction("schema migration", () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS qq_channel_observation_meta (
                          meta_key TEXT PRIMARY KEY,
                          meta_value TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        INSERT OR IGNORE INTO qq_channel_observation_meta(meta_key, meta_value)
                        VALUES ('schema_version', '2')
                        """);
                try (ResultSet result = statement.executeQuery(
                        "SELECT meta_value FROM qq_channel_observation_meta"
                                + " WHERE meta_key = 'schema_version'")) {
                    if (!result.next()) {
                        throw new StoreException(ErrorCode.STORAGE_ERROR,
                                "observation schema version 缺失");
                    }
                    int found;
                    try {
                        found = Integer.parseInt(result.getString(1));
                    } catch (NumberFormatException failure) {
                        throw new StoreException(ErrorCode.STORAGE_ERROR,
                                "observation schema version 损坏", failure);
                    }
                    if (found > SCHEMA_VERSION) {
                        throw new StoreException(ErrorCode.STORAGE_ERROR,
                                "observation schema version 过新: " + found);
                    }
                }

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS channel_observation (
                          observation_id TEXT PRIMARY KEY,
                          thread_id TEXT NOT NULL,
                          channel TEXT NOT NULL,
                          account_id TEXT NOT NULL,
                          raw_source TEXT NOT NULL,
                          channel_message_id TEXT NOT NULL,
                          board_local_seq INTEGER NOT NULL,
                          sender_id TEXT NOT NULL,
                          canonical_sender_id TEXT,
                          identity_status TEXT NOT NULL,
                          identity_revision INTEGER NOT NULL,
                          binding_generation INTEGER NOT NULL,
                          binding_epoch_id TEXT NOT NULL,
                          ingest_intent_key TEXT,
                          route_reason TEXT NOT NULL,
                          direction TEXT NOT NULL,
                          message_at TEXT NOT NULL,
                          observed_at TEXT NOT NULL,
                          priority INTEGER NOT NULL,
                          time_reliability TEXT NOT NULL,
                          content_integrity TEXT NOT NULL,
                          structured_mentions_trusted INTEGER NOT NULL DEFAULT 0,
                          strict_time_tolerance_seconds INTEGER NOT NULL,
                          route_state TEXT NOT NULL,
                          route_request_key TEXT NOT NULL UNIQUE,
                          route_item_id TEXT,
                          route_revision INTEGER NOT NULL,
                          route_updated_at TEXT NOT NULL,
                          correlation_state TEXT NOT NULL,
                          correlation_peer_observation_id TEXT,
                          correlation_revision INTEGER NOT NULL,
                          correlation_updated_at TEXT NOT NULL,
                          UNIQUE(raw_source, channel_message_id),
                          CHECK (channel IN ('onebot', 'qq_official')),
                          CHECK (direction IN ('INBOUND', 'OUTBOUND')),
                          CHECK (board_local_seq >= 0),
                          CHECK (priority BETWEEN -1000 AND 1000),
                          CHECK (identity_status IN ('MAPPED', 'UNMAPPED', 'AMBIGUOUS')),
                          CHECK (identity_revision >= 0),
                          CHECK (binding_generation >= 0),
                          CHECK (route_reason IN
                                 ('PENDING', 'PREFERRED_DISPLAY', 'FALLBACK_DISPLAY',
                                  'NON_PREFERRED_EVIDENCE', 'LATE_EVIDENCE_FROZEN_ITEM',
                                  'SUPPRESSED_FALLBACK_UNVERIFIED',
                                  'INJECTION_GATE_OFF')),
                          CHECK ((identity_status = 'MAPPED' AND canonical_sender_id IS NOT NULL
                                  AND length(canonical_sender_id) > 0)
                                 OR (identity_status IN ('UNMAPPED', 'AMBIGUOUS')
                                  AND canonical_sender_id IS NULL)),
                          CHECK (time_reliability IN ('RELIABLE', 'FALLBACK', 'UNKNOWN')),
                          CHECK (content_integrity IN
                                 ('COMPLETE', 'INCOMPLETE', 'TRUNCATED', 'UNKNOWN')),
                          CHECK (structured_mentions_trusted IN (0, 1)),
                          CHECK (strict_time_tolerance_seconds BETWEEN 0 AND 300),
                          CHECK (route_state IN
                                 ('PENDING_ROUTE', 'DISPLAY_PENDING', 'DISPLAY_LINKED',
                                  'EVIDENCE_ONLY', 'GATE_OFF')),
                          CHECK (correlation_state IN
                                 ('CHANNEL_ONLY', 'UNVERIFIED', 'UNVERIFIED_MATCH',
                                  'MATCHED', 'CONFLICT', 'SUPPRESSED_UNVERIFIED')),
                          CHECK (route_revision >= 1),
                          CHECK (correlation_revision >= 1),
                          CHECK ((route_state IN ('DISPLAY_PENDING', 'DISPLAY_LINKED')
                                  AND route_item_id IS NOT NULL
                                  AND length(route_item_id) > 0)
                                 OR route_state = 'EVIDENCE_ONLY'
                                 OR (route_state IN ('PENDING_ROUTE', 'GATE_OFF')
                                  AND route_item_id IS NULL)),
                          CHECK ((correlation_state IN
                                  ('MATCHED', 'UNVERIFIED_MATCH', 'CONFLICT',
                                   'SUPPRESSED_UNVERIFIED')
                                  AND correlation_peer_observation_id IS NOT NULL)
                                 OR (correlation_state IN ('CHANNEL_ONLY', 'UNVERIFIED')
                                  AND correlation_peer_observation_id IS NULL))
                        )
                        """);
                ensureColumn(statement, "channel_observation",
                        "structured_mentions_trusted", "INTEGER NOT NULL DEFAULT 0");
                statement.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS
                        ux_channel_observation_source_message
                        ON channel_observation(raw_source, channel_message_id)
                        """);

                // Durable pre-board intent. It is intentionally separate from
                // channel_observation: an intent alone is not a board fact/observation.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS channel_ingest_intent (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          intent_key TEXT NOT NULL UNIQUE,
                          observation_id TEXT NOT NULL UNIQUE,
                          request_key TEXT NOT NULL UNIQUE,
                          thread_id TEXT NOT NULL,
                          channel TEXT NOT NULL,
                          account_id TEXT NOT NULL,
                          raw_source TEXT NOT NULL,
                          channel_message_id TEXT NOT NULL,
                          sender_id TEXT NOT NULL,
                          canonical_sender_id TEXT,
                          identity_status TEXT NOT NULL,
                          direction TEXT NOT NULL,
                          message_at TEXT NOT NULL,
                          observed_at TEXT NOT NULL,
                          priority INTEGER NOT NULL,
                          time_reliability TEXT NOT NULL,
                          content_integrity TEXT NOT NULL,
                          structured_mentions_trusted INTEGER NOT NULL DEFAULT 0,
                          binding_generation INTEGER NOT NULL,
                          gate_enabled INTEGER NOT NULL,
                          binding_epoch_id TEXT NOT NULL,
                          route_reason TEXT NOT NULL,
                          preferred_channel TEXT,
                          preferred_channel_availability TEXT NOT NULL,
                          selected_channel TEXT,
                          matching_mode TEXT NOT NULL,
                          strict_time_tolerance_seconds INTEGER NOT NULL,
                          identity_revision INTEGER NOT NULL,
                          route_decision TEXT NOT NULL,
                          route_item_id TEXT,
                          state TEXT NOT NULL,
                          board_local_seq INTEGER,
                          created_at TEXT NOT NULL,
                          updated_at TEXT NOT NULL,
                          UNIQUE(raw_source, channel_message_id),
                          CHECK (channel IN ('onebot', 'qq_official')),
                          CHECK (direction IN ('INBOUND', 'OUTBOUND')),
                          CHECK (priority BETWEEN -1000 AND 1000),
                          CHECK (identity_status IN ('MAPPED', 'UNMAPPED', 'AMBIGUOUS')),
                          CHECK ((identity_status = 'MAPPED' AND canonical_sender_id IS NOT NULL
                                  AND length(canonical_sender_id) > 0)
                                 OR (identity_status IN ('UNMAPPED', 'AMBIGUOUS')
                                  AND canonical_sender_id IS NULL)),
                          CHECK (time_reliability IN ('RELIABLE', 'FALLBACK', 'UNKNOWN')),
                          CHECK (content_integrity IN
                                 ('COMPLETE', 'INCOMPLETE', 'TRUNCATED', 'UNKNOWN')),
                          CHECK (structured_mentions_trusted IN (0, 1)),
                          CHECK (binding_generation >= 1),
                          CHECK (gate_enabled IN (0, 1)),
                          CHECK (route_reason IN
                                 ('PENDING', 'PREFERRED_DISPLAY', 'FALLBACK_DISPLAY',
                                  'NON_PREFERRED_EVIDENCE', 'LATE_EVIDENCE_FROZEN_ITEM',
                                  'SUPPRESSED_FALLBACK_UNVERIFIED',
                                  'INJECTION_GATE_OFF')),
                          CHECK (preferred_channel IS NULL
                                 OR preferred_channel IN ('onebot', 'qq_official')),
                          CHECK (preferred_channel_availability IN
                                 ('AVAILABLE', 'UNAVAILABLE', 'UNKNOWN')),
                          CHECK (selected_channel IS NULL
                                 OR selected_channel IN ('onebot', 'qq_official')),
                          CHECK (matching_mode IN ('STRICT', 'LOOSE', 'OFF')),
                          CHECK (strict_time_tolerance_seconds BETWEEN 0 AND 300),
                          CHECK (identity_revision >= 0),
                          CHECK (route_decision IN
                                 ('PENDING_ROUTE', 'DISPLAY_PENDING', 'EVIDENCE_ONLY',
                                  'GATE_OFF')),
                          CHECK (state IN
                                 ('RECORDED', 'BOARD_COMMITTED', 'OBSERVATION_COMMITTED')),
                          CHECK ((route_decision = 'DISPLAY_PENDING'
                                  AND route_item_id IS NOT NULL
                                  AND length(route_item_id) > 0)
                                 OR route_decision = 'EVIDENCE_ONLY'
                                 OR (route_decision IN ('PENDING_ROUTE', 'GATE_OFF')
                                  AND route_item_id IS NULL)),
                          CHECK ((gate_enabled = 0 AND route_decision = 'GATE_OFF')
                                 OR gate_enabled = 1),
                          CHECK (route_decision <> 'DISPLAY_PENDING'
                                 OR selected_channel IS NOT NULL),
                          CHECK (board_local_seq IS NULL OR board_local_seq >= 0),
                          CHECK (state = 'RECORDED' OR board_local_seq IS NOT NULL)
                        )
                        """);
                ensureColumn(statement, "channel_ingest_intent",
                        "structured_mentions_trusted", "INTEGER NOT NULL DEFAULT 0");
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_channel_ingest_intent_source_state
                        ON channel_ingest_intent(raw_source, state, id)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_channel_ingest_intent_source_message
                        ON channel_ingest_intent(raw_source, channel_message_id)
                        """);

                // Symmetric 1:1 evidence pairs. The two UNIQUE indexes are the database-level
                // guarantee that an observation can participate in at most one pair.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS channel_correlation_pair (
                          pair_key TEXT PRIMARY KEY,
                          observation_a_id TEXT NOT NULL,
                          observation_b_id TEXT NOT NULL,
                          state TEXT NOT NULL,
                          revision INTEGER NOT NULL,
                          created_at TEXT NOT NULL,
                          updated_at TEXT NOT NULL,
                          UNIQUE(observation_a_id),
                          UNIQUE(observation_b_id),
                          CHECK (observation_a_id < observation_b_id),
                          CHECK (revision >= 1),
                          CHECK (state IN
                                 ('MATCHED', 'UNVERIFIED_MATCH', 'CONFLICT',
                                  'SUPPRESSED_UNVERIFIED'))
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_channel_correlation_pair_observation
                        ON channel_correlation_pair(observation_a_id, observation_b_id)
                        """);
                // Cross-column uniqueness: one observation may be a member of at most one pair,
                // regardless of whether it appears as a-side or b-side.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS channel_correlation_pair_member (
                          observation_id TEXT PRIMARY KEY,
                          pair_key TEXT NOT NULL,
                          created_at TEXT NOT NULL,
                          FOREIGN KEY(pair_key)
                            REFERENCES channel_correlation_pair(pair_key) ON DELETE CASCADE
                        )
                        """);
                statement.execute("""
                        INSERT OR IGNORE INTO channel_correlation_pair_member(
                          observation_id, pair_key, created_at)
                        SELECT observation_a_id, pair_key, updated_at
                        FROM channel_correlation_pair
                        UNION
                        SELECT observation_b_id, pair_key, updated_at
                        FROM channel_correlation_pair
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_channel_correlation_member_pair
                        ON channel_correlation_pair_member(pair_key)
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS channel_correlation_audit (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          pair_key TEXT NOT NULL,
                          action TEXT NOT NULL,
                          observation_a_id TEXT NOT NULL,
                          observation_b_id TEXT NOT NULL,
                          from_state TEXT,
                          to_state TEXT,
                          changed_at TEXT NOT NULL,
                          CHECK (action IN ('CREATE', 'STATE_CHANGE', 'RELEASE')),
                          CHECK (from_state IS NULL
                                 OR from_state IN
                                 ('MATCHED', 'UNVERIFIED_MATCH', 'CONFLICT',
                                  'SUPPRESSED_UNVERIFIED')),
                          CHECK (to_state IS NULL
                                 OR to_state IN
                                 ('MATCHED', 'UNVERIFIED_MATCH', 'CONFLICT',
                                  'SUPPRESSED_UNVERIFIED', 'UNVERIFIED'))
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_channel_correlation_audit_observations
                        ON channel_correlation_audit(
                          observation_a_id, observation_b_id, id)
                        """);

                // Non-exclusive fallback candidates. These are deliberately separate from the
                // 1:1 pair tables and never change candidate observations or create a pair.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS channel_evidence_candidate (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          source_observation_id TEXT NOT NULL,
                          candidate_observation_id TEXT NOT NULL,
                          candidate_item_id TEXT NOT NULL,
                          reason TEXT NOT NULL,
                          thread_id TEXT NOT NULL,
                          binding_epoch_id TEXT NOT NULL,
                          created_at TEXT NOT NULL,
                          UNIQUE(source_observation_id, candidate_observation_id,
                                 candidate_item_id),
                          CHECK (source_observation_id <> candidate_observation_id),
                          CHECK (reason = 'SUPPRESSED_FALLBACK_UNVERIFIED')
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_channel_evidence_candidate_source
                        ON channel_evidence_candidate(source_observation_id, id)
                        """);

                // Durable completion outbox for EVIDENCE_ONLY observations that have an explicit
                // frozen target item. Deferred evidence without a target is deliberately excluded.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS channel_evidence_outbox (
                          observation_id TEXT PRIMARY KEY,
                          target_item_id TEXT NOT NULL,
                          state TEXT NOT NULL,
                          revision INTEGER NOT NULL,
                          created_at TEXT NOT NULL,
                          updated_at TEXT NOT NULL,
                          CHECK (length(target_item_id) > 0),
                          CHECK (state IN ('PENDING', 'APPLIED', 'TARGET_MISSING')),
                          CHECK (revision >= 1)
                        )
                        """);
                statement.execute("""
                        INSERT OR IGNORE INTO channel_evidence_outbox(
                          observation_id, target_item_id, state, revision,
                          created_at, updated_at)
                        SELECT observation_id, route_item_id, 'PENDING', 1,
                               route_updated_at, route_updated_at
                        FROM channel_observation
                        WHERE route_state = 'EVIDENCE_ONLY'
                          AND route_item_id IS NOT NULL
                          AND length(route_item_id) > 0
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_channel_evidence_outbox_pending
                        ON channel_evidence_outbox(state, observation_id)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_channel_observation_thread_time
                        ON channel_observation(thread_id, message_at, observation_id)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_channel_observation_thread_observed
                        ON channel_observation(thread_id, observed_at, observation_id)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_channel_observation_route
                        ON channel_observation(thread_id, route_state)
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_channel_observation_correlation
                        ON channel_observation(thread_id, correlation_state)
                        """);

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS channel_route_history (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          observation_id TEXT NOT NULL,
                          request_key TEXT NOT NULL,
                          from_state TEXT,
                          to_state TEXT NOT NULL,
                          item_id TEXT,
                          changed_at TEXT NOT NULL,
                          CHECK (from_state IS NULL
                                 OR from_state IN
                                 ('PENDING_ROUTE', 'DISPLAY_PENDING', 'DISPLAY_LINKED',
                                  'EVIDENCE_ONLY', 'GATE_OFF')),
                          CHECK (to_state IN
                                 ('PENDING_ROUTE', 'DISPLAY_PENDING', 'DISPLAY_LINKED',
                                  'EVIDENCE_ONLY', 'GATE_OFF'))
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_channel_route_history_observation
                        ON channel_route_history(observation_id, id)
                        """);

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS channel_source_scan_state (
                          raw_source TEXT PRIMARY KEY,
                          generation INTEGER NOT NULL,
                          thread_id TEXT NOT NULL,
                          channel TEXT NOT NULL,
                          account_id TEXT NOT NULL,
                          active INTEGER NOT NULL,
                          start_board_seq INTEGER NOT NULL,
                          scan_cursor INTEGER NOT NULL,
                          created_at TEXT NOT NULL,
                          updated_at TEXT NOT NULL,
                          CHECK (generation >= 1),
                          CHECK (active IN (0, 1)),
                          CHECK (channel IN ('onebot', 'qq_official')),
                          CHECK (start_board_seq >= 0),
                          CHECK (scan_cursor >= start_board_seq - 1)
                        )
                        """);

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS channel_source_binding_event (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          raw_source TEXT NOT NULL,
                          generation INTEGER NOT NULL,
                          thread_id TEXT NOT NULL,
                          channel TEXT NOT NULL,
                          account_id TEXT NOT NULL,
                          start_board_seq INTEGER NOT NULL,
                          end_board_seq INTEGER,
                          kind TEXT NOT NULL,
                          created_at TEXT NOT NULL,
                          CHECK (generation >= 1),
                          CHECK (channel IN ('onebot', 'qq_official')),
                          CHECK (kind IN ('BOUND', 'UNBOUND')),
                          CHECK (start_board_seq >= 0),
                          CHECK (end_board_seq IS NULL OR end_board_seq >= start_board_seq - 1)
                        )
                        """);
                statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_channel_binding_event_source
                        ON channel_source_binding_event(raw_source, id)
                        """);

                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE qq_channel_observation_meta SET meta_value = ?
                        WHERE meta_key = 'schema_version'
                        """)) {
                    update.setString(1, Integer.toString(SCHEMA_VERSION));
                    update.executeUpdate();
                }
            }
            return null;
        });
    }

    /** Idempotent observation insert. The first persisted evidence metadata remains authoritative. */
    public synchronized ObservationWrite observe(ObservationDraft draft) {
        Objects.requireNonNull(draft, "draft");
        validateDraft(draft);
        return transaction("observation insert", () -> {
            Observation existing = findObservationUnsafe(draft.rawSource(), draft.channelMessageId())
                    .orElse(null);
            if (existing != null) {
                verifySameObservation(existing, draft);
                return new ObservationWrite(existing, false);
            }

            SourceBinding binding = findBindingUnsafe(draft.rawSource()).orElse(null);
            long bindingGeneration = 0;
            if (binding != null && binding.active()) {
                verifyDraftMatchesBinding(binding, draft);
                bindingGeneration = binding.generation();
            }
            insertObservationUnsafe(draft, bindingGeneration, null, draft.routeReason());
            return new ObservationWrite(
                    requireObservationUnsafe(draft.rawSource(), draft.channelMessageId()), true);
        });
    }

    public synchronized Optional<Observation> findObservation(String rawSource,
                                                               String channelMessageId) {
        requireIdentifier(rawSource, "rawSource");
        requireIdentifier(channelMessageId, "channelMessageId");
        ensureOpen();
        try {
            return findObservationUnsafe(rawSource, channelMessageId);
        } catch (SQLException failure) {
            throw storageFailure("observation read", failure);
        }
    }

    /** Keyset page over a thread, ordered by message time then opaque observation ID. */
    public synchronized ObservationPage observationsForThread(
            String threadId,
            Instant fromInclusive,
            Instant toExclusive,
            ObservationCursor after,
            int limit) {
        validateThreadId(threadId);
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw invalid("limit 必须在 1.." + MAX_PAGE_SIZE + " 内");
        }
        if (fromInclusive != null && toExclusive != null
                && !fromInclusive.isBefore(toExclusive)) {
            throw invalid("fromInclusive 必须早于 toExclusive");
        }
        ensureOpen();

        StringBuilder sql = new StringBuilder("""
                SELECT observation_id, thread_id, channel, account_id, raw_source,
                       channel_message_id, board_local_seq, sender_id, canonical_sender_id,
                       identity_status, identity_revision, binding_generation,
                       binding_epoch_id, ingest_intent_key, route_reason,
                       direction, message_at, observed_at, priority, time_reliability,
                       content_integrity, structured_mentions_trusted,
                       strict_time_tolerance_seconds, route_state, route_request_key,
                       route_item_id, route_revision,
                       route_updated_at, correlation_state, correlation_peer_observation_id,
                       correlation_revision, correlation_updated_at
                FROM channel_observation WHERE thread_id = ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(threadId);
        if (fromInclusive != null) {
            sql.append(" AND message_at >= ?");
            parameters.add(timestamp(fromInclusive));
        }
        if (toExclusive != null) {
            sql.append(" AND message_at < ?");
            parameters.add(timestamp(toExclusive));
        }
        if (after != null) {
            String afterTime = timestamp(after.messageAt());
            sql.append(" AND (message_at > ? OR (message_at = ? AND observation_id > ?))");
            parameters.add(afterTime);
            parameters.add(afterTime);
            parameters.add(after.observationId());
        }
        sql.append(" ORDER BY message_at ASC, observation_id ASC LIMIT ?");
        parameters.add(limit + 1);

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                Object value = parameters.get(index);
                int sqlIndex = index + 1;
                if (value instanceof String text) {
                    statement.setString(sqlIndex, text);
                } else if (value instanceof Integer number) {
                    statement.setInt(sqlIndex, number);
                } else if (value instanceof Long number) {
                    statement.setLong(sqlIndex, number);
                } else {
                    throw new IllegalStateException("unsupported query parameter");
                }
            }
            List<Observation> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(mapObservation(result));
                }
            }
            boolean hasMore = rows.size() > limit;
            if (hasMore) {
                rows = new ArrayList<>(rows.subList(0, limit));
            }
            Optional<ObservationCursor> next = Optional.empty();
            if (hasMore && !rows.isEmpty()) {
                Observation last = rows.get(rows.size() - 1);
                next = Optional.of(new ObservationCursor(last.messageAt(), last.observationId()));
            }
            return new ObservationPage(rows, hasMore, next);
        } catch (SQLException failure) {
            throw storageFailure("observation page read", failure);
        }
    }

    public synchronized ObservationPage observationsForThread(
            String threadId, Instant fromInclusive, Instant toExclusive, int limit) {
        return observationsForThread(threadId, fromInclusive, toExclusive, null, limit);
    }

    /**
     * Independent observer-clock keyset query for fallback candidate prefiltering when source
     * timestamps are unreliable. It is deliberately separate from the message-time query so router
     * can union both result streams and deduplicate by observationId. Stable ordering is
     * {@code (observedAt, observationId)}; equal timestamps are paginated by observationId.
     */
    public synchronized ObservedAtObservationPage observationsForThreadByObservedAt(
            String threadId,
            Instant fromInclusive,
            Instant toExclusive,
            ObservedAtCursor after,
            int limit) {
        validateThreadId(threadId);
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw invalid("limit 必须在 1.." + MAX_PAGE_SIZE + " 内");
        }
        if (fromInclusive != null && toExclusive != null
                && !fromInclusive.isBefore(toExclusive)) {
            throw invalid("fromInclusive 必须早于 toExclusive");
        }
        ensureOpen();

        StringBuilder sql = new StringBuilder("""
                SELECT observation_id, thread_id, channel, account_id, raw_source,
                       channel_message_id, board_local_seq, sender_id, canonical_sender_id,
                       identity_status, identity_revision, binding_generation,
                       binding_epoch_id, ingest_intent_key, route_reason,
                       direction, message_at, observed_at, priority, time_reliability,
                       content_integrity, structured_mentions_trusted,
                       strict_time_tolerance_seconds, route_state, route_request_key,
                       route_item_id, route_revision, route_updated_at,
                       correlation_state, correlation_peer_observation_id,
                       correlation_revision, correlation_updated_at
                FROM channel_observation WHERE thread_id = ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(threadId);
        if (fromInclusive != null) {
            sql.append(" AND observed_at >= ?");
            parameters.add(timestamp(fromInclusive));
        }
        if (toExclusive != null) {
            sql.append(" AND observed_at < ?");
            parameters.add(timestamp(toExclusive));
        }
        if (after != null) {
            String afterTime = timestamp(after.observedAt());
            sql.append(" AND (observed_at > ? OR (observed_at = ? AND observation_id > ?))");
            parameters.add(afterTime);
            parameters.add(afterTime);
            parameters.add(after.observationId());
        }
        sql.append(" ORDER BY observed_at ASC, observation_id ASC LIMIT ?");
        parameters.add(limit + 1);

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                Object value = parameters.get(index);
                int sqlIndex = index + 1;
                if (value instanceof String text) {
                    statement.setString(sqlIndex, text);
                } else if (value instanceof Integer number) {
                    statement.setInt(sqlIndex, number);
                } else {
                    throw new IllegalStateException("unsupported query parameter");
                }
            }
            List<Observation> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(mapObservation(result));
                }
            }
            boolean hasMore = rows.size() > limit;
            if (hasMore) {
                rows = new ArrayList<>(rows.subList(0, limit));
            }
            Optional<ObservedAtCursor> next = Optional.empty();
            if (hasMore && !rows.isEmpty()) {
                Observation last = rows.get(rows.size() - 1);
                next = Optional.of(new ObservedAtCursor(
                        last.observedAt(), last.observationId()));
            }
            return new ObservedAtObservationPage(rows, hasMore, next);
        } catch (SQLException failure) {
            throw storageFailure("observed-at observation page read", failure);
        }
    }

    public synchronized ObservedAtObservationPage observationsForThreadByObservedAt(
            String threadId, Instant fromInclusive, Instant toExclusive, int limit) {
        return observationsForThreadByObservedAt(
                threadId, fromInclusive, toExclusive, null, limit);
    }

    /**
     * Global route-recovery keyset page. It intentionally does not join source bindings or filter
     * by current thread membership, so pending observations survive unbind/rebind and are never
     * truncated by a per-source limit.
     */
    public synchronized RouteObservationPage pendingRouteObservations(
            Set<RouteState> states, int limit, String afterObservationId) {
        Objects.requireNonNull(states, "states");
        if (states.isEmpty() || states.stream().anyMatch(Objects::isNull)) {
            throw invalid("states 不能为空且不能包含 null");
        }
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw invalid("limit 必须在 1.." + MAX_PAGE_SIZE + " 内");
        }
        String cursor = afterObservationId == null ? "" : afterObservationId;
        if (containsControl(cursor)) {
            throw invalid("afterObservationId 不能包含控制字符");
        }
        ensureOpen();

        List<RouteState> orderedStates = new ArrayList<>(states);
        orderedStates.sort(Enum::compareTo);
        StringBuilder sql = new StringBuilder("""
                SELECT observation_id, thread_id, channel, account_id, raw_source,
                       channel_message_id, board_local_seq, sender_id, canonical_sender_id,
                       identity_status, identity_revision, binding_generation,
                       binding_epoch_id, ingest_intent_key, route_reason,
                       direction, message_at, observed_at, priority, time_reliability,
                       content_integrity, structured_mentions_trusted,
                       strict_time_tolerance_seconds, route_state, route_request_key,
                       route_item_id, route_revision,
                       route_updated_at, correlation_state, correlation_peer_observation_id,
                       correlation_revision, correlation_updated_at
                FROM channel_observation
                WHERE observation_id > ? AND route_state IN (
                """);
        for (int i = 0; i < orderedStates.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append('?');
        }
        sql.append(") ORDER BY observation_id ASC LIMIT ?");

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, cursor);
            for (RouteState state : orderedStates) {
                statement.setString(index++, state.name());
            }
            statement.setInt(index, limit + 1);
            List<Observation> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(mapObservation(result));
                }
            }
            boolean hasMore = rows.size() > limit;
            if (hasMore) {
                rows = new ArrayList<>(rows.subList(0, limit));
            }
            Optional<String> next = hasMore && !rows.isEmpty()
                    ? Optional.of(rows.get(rows.size() - 1).observationId())
                    : Optional.empty();
            return new RouteObservationPage(rows, hasMore, next);
        } catch (SQLException failure) {
            throw storageFailure("global route observation page read", failure);
        }
    }

    /** Counts for both the router's durable route state and passive matcher evidence state. */
    public synchronized StatusCounts statusCounts(String threadId) {
        validateThreadId(threadId);
        ensureOpen();
        EnumMap<RouteState, Long> routeCounts = emptyRouteCounts();
        EnumMap<CorrelationState, Long> correlationCounts = emptyCorrelationCounts();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT route_state, correlation_state, COUNT(*) AS state_count
                FROM channel_observation
                WHERE thread_id = ?
                GROUP BY route_state, correlation_state
                """)) {
            statement.setString(1, threadId);
            long total = 0;
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    RouteState route = RouteState.valueOf(result.getString("route_state"));
                    CorrelationState correlation =
                            CorrelationState.valueOf(result.getString("correlation_state"));
                    long count = result.getLong("state_count");
                    routeCounts.merge(route, count, Long::sum);
                    correlationCounts.merge(correlation, count, Long::sum);
                    total += count;
                }
            }
            return new StatusCounts(threadId, total, routeCounts, correlationCounts);
        } catch (SQLException failure) {
            throw storageFailure("status count read", failure);
        }
    }

    /**
     * Atomically applies one route transition; replaying the same decision is a no-op.
     *
     * <p>{@code EVIDENCE_ONLY} may carry no target item (evidence arrived before a canonical item)
     * or a stable frozen item id (late evidence). Calling this method again with
     * {@code EVIDENCE_ONLY} and a previously-missing item safely attaches the item exactly once;
     * a different item id is rejected and the append-only history records the attach.</p>
     */
    public synchronized RouteUpdate decideRoute(String rawSource,
                                                 String channelMessageId,
                                                 RouteState target,
                                                 String itemId) {
        return decideRoute(rawSource, channelMessageId, target, itemId,
                defaultRouteReason(target, itemId));
    }

    /** Full route-decision API with an explicit body-free router reason. */
    public synchronized RouteUpdate decideRoute(String rawSource,
                                                 String channelMessageId,
                                                 RouteState target,
                                                 String itemId,
                                                 RouteReason reason) {
        requireIdentifier(rawSource, "rawSource");
        requireIdentifier(channelMessageId, "channelMessageId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reason, "reason");
        String requestedItem = normalizeOptionalIdentifier(itemId, "itemId");
        return transaction("route decision", () -> applyRouteDecisionUnsafe(
                rawSource, channelMessageId, target, requestedItem, reason));
    }

    /** Safely attach a frozen target item id to an existing EVIDENCE_ONLY observation. */
    public synchronized RouteUpdate attachEvidenceItem(String rawSource,
                                                        String channelMessageId,
                                                        String itemId) {
        String normalized = normalizeOptionalIdentifier(itemId, "itemId");
        if (normalized == null) {
            throw new StoreException(ErrorCode.ITEM_ID_REQUIRED,
                    "冻结 evidence itemId 不能为空");
        }
        return decideRoute(rawSource, channelMessageId, RouteState.EVIDENCE_ONLY, normalized,
                RouteReason.LATE_EVIDENCE_FROZEN_ITEM);
    }

    private RouteUpdate applyRouteDecisionUnsafe(String rawSource,
                                                  String channelMessageId,
                                                  RouteState target,
                                                  String requestedItem,
                                                  RouteReason reason) throws SQLException {
        Observation current = requireObservationUnsafe(rawSource, channelMessageId);
        RouteState previous = current.routeState();
        if (previous == target) {
            if (target == RouteState.EVIDENCE_ONLY) {
                if (requestedItem == null) {
                    return new RouteUpdate(current, previous, false);
                }
                if (current.itemId().isPresent()) {
                    if (current.itemId().orElseThrow().equals(requestedItem)) {
                        return new RouteUpdate(current, previous, false);
                    }
                    throw new StoreException(ErrorCode.ROUTE_CONFLICT,
                            "EVIDENCE_ONLY target itemId 已确定，重放不得覆盖");
                }
                return writeRouteStateUnsafe(current, previous, target, requestedItem, reason);
            }
            if (requiresItem(target)) {
                String bound = current.itemId().orElseThrow(() -> new StoreException(
                        ErrorCode.ROUTE_CONFLICT, "DISPLAY 状态缺少 itemId"));
                if (requestedItem != null && !requestedItem.equals(bound)) {
                    throw new StoreException(ErrorCode.ROUTE_CONFLICT,
                            "route itemId 已确定，重放不得覆盖");
                }
            } else if (requestedItem != null) {
                throw new StoreException(ErrorCode.ROUTE_CONFLICT,
                        "该 route 状态不得绑定 itemId");
            }
            return new RouteUpdate(current, previous, false);
        }

        validateRouteTransition(previous, target);
        String resolvedItem = null;
        if (requiresItem(target)) {
            resolvedItem = requestedItem != null
                    ? requestedItem
                    : current.itemId().orElse(null);
            if (resolvedItem == null) {
                throw new StoreException(ErrorCode.ITEM_ID_REQUIRED,
                        "DISPLAY 路由必须提供稳定 itemId");
            }
            if (current.itemId().isPresent()
                    && !current.itemId().orElseThrow().equals(resolvedItem)) {
                throw new StoreException(ErrorCode.ROUTE_CONFLICT,
                        "DISPLAY route itemId 已确定，重放不得覆盖");
            }
        } else if (target == RouteState.EVIDENCE_ONLY) {
            resolvedItem = requestedItem;
        } else if (requestedItem != null) {
            throw new StoreException(ErrorCode.ROUTE_CONFLICT,
                    "PENDING_ROUTE/GATE_OFF 状态不得绑定 itemId");
        }
        return writeRouteStateUnsafe(current, previous, target, resolvedItem, reason);
    }

    private RouteUpdate writeRouteStateUnsafe(Observation current,
                                               RouteState previous,
                                               RouteState target,
                                               String resolvedItem,
                                               RouteReason reason) throws SQLException {
        String now = timestamp(clock.instant());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE channel_observation
                SET route_state = ?, route_item_id = ?, route_reason = ?,
                    route_revision = route_revision + 1, route_updated_at = ?
                WHERE observation_id = ? AND route_state = ? AND route_revision = ?
                """)) {
            statement.setString(1, target.name());
            statement.setString(2, resolvedItem);
            statement.setString(3, reason.name());
            statement.setString(4, now);
            statement.setString(5, current.observationId());
            statement.setString(6, previous.name());
            statement.setLong(7, current.routeRevision());
            if (statement.executeUpdate() != 1) {
                throw new StoreException(ErrorCode.ROUTE_CONFLICT,
                        "route 状态已被并发修改，重放不得覆盖");
            }
        }
        appendRouteHistory(current.observationId(), current.requestKey(), previous,
                target, resolvedItem, now);
        if (target == RouteState.EVIDENCE_ONLY && resolvedItem != null) {
            upsertEvidenceOutboxUnsafe(current.observationId(), resolvedItem, now);
        }
        return new RouteUpdate(requireObservationUnsafe(
                current.rawSource(), current.channelMessageId()), previous, true);
    }

    /**
     * Persists the body-free routing snapshot after the priority gate and before
     * {@code board.append}. It does not create an observation or a board fact.
     *
     * <p>The router must call this while holding the same process-wide routing/binding lock that
     * protects binding generation and gate changes. The active binding is validated against the
     * supplied generation in this transaction; the actual cross-operation serialization of
     * {@code intent -> board.append -> mark committed/promote} belongs to that shared lock.</p>
     */
    public synchronized IngestIntentWrite recordIngestIntent(IngestIntentDraft draft) {
        Objects.requireNonNull(draft, "draft");
        validateIngestIntentDraft(draft);
        return transaction("ingest intent insert", () -> {
            IngestIntent existing = findIngestIntentUnsafe(draft.intentKey()).orElse(null);
            if (existing != null) {
                verifySameIngestIntent(existing, draft);
                return new IngestIntentWrite(existing, false);
            }
            IngestIntent sameMessage = findIngestIntentByMessageUnsafe(
                    draft.rawSource(), draft.channelMessageId()).orElse(null);
            if (sameMessage != null) {
                throw new StoreException(ErrorCode.INGEST_INTENT_CONFLICT,
                        "同一 rawSource/messageId 已有不同 intentKey 的 ingest intent");
            }

            SourceBinding binding = requireActiveBindingUnsafe(draft.rawSource());
            if (binding.generation() != draft.bindingGeneration()) {
                throw new StoreException(ErrorCode.GENERATION_MISMATCH,
                        "ingest intent binding generation 与 active binding 不一致");
            }
            if (!binding.threadId().equals(draft.threadId())
                    || binding.channel() != draft.channel()
                    || !binding.accountId().equals(draft.accountId())) {
                throw new StoreException(ErrorCode.BINDING_MISMATCH,
                        "ingest intent thread/channel/account 与 active binding 不一致");
            }

            String observationId = observationIdFor(
                    draft.rawSource(), draft.channelMessageId());
            String now = timestamp(clock.instant());
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO channel_ingest_intent(
                      intent_key, observation_id, request_key, thread_id, channel, account_id,
                      raw_source, channel_message_id, sender_id, canonical_sender_id,
                      identity_status, direction, message_at,
                      observed_at, priority, time_reliability, content_integrity,
                      structured_mentions_trusted, binding_generation, gate_enabled,
                      binding_epoch_id, route_reason, preferred_channel,
                      preferred_channel_availability, selected_channel, matching_mode,
                      strict_time_tolerance_seconds, identity_revision, route_decision,
                      route_item_id, state, board_local_seq, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECORDED', NULL, ?, ?)
                    """)) {
                statement.setString(1, draft.intentKey());
                statement.setString(2, observationId);
                statement.setString(3, requestKeyFor(
                        draft.rawSource(), draft.channelMessageId()));
                statement.setString(4, draft.threadId());
                statement.setString(5, draft.channel().wireName());
                statement.setString(6, draft.accountId());
                statement.setString(7, draft.rawSource());
                statement.setString(8, draft.channelMessageId());
                statement.setString(9, draft.senderId());
                statement.setString(10, draft.canonicalSenderId().orElse(null));
                statement.setString(11, draft.identityStatus().name());
                statement.setString(12, draft.direction().name());
                statement.setString(13, timestamp(draft.messageAt()));
                statement.setString(14, timestamp(draft.observedAt()));
                statement.setInt(15, draft.priority());
                statement.setString(16, draft.timeReliability().name());
                statement.setString(17, draft.contentIntegrity().name());
                statement.setInt(18, draft.structuredMentionsTrusted() ? 1 : 0);
                statement.setLong(19, draft.bindingGeneration());
                statement.setInt(20, draft.gateEnabled() ? 1 : 0);
                statement.setString(21, draft.bindingEpochId());
                statement.setString(22, draft.routeReason().name());
                statement.setString(23, draft.preferredChannel() == null
                        ? null : draft.preferredChannel().wireName());
                statement.setString(24, draft.preferredChannelAvailability().name());
                statement.setString(25, draft.selectedChannel() == null
                        ? null : draft.selectedChannel().wireName());
                statement.setString(26, draft.matchingMode().name());
                statement.setInt(27, draft.strictTimeToleranceSeconds());
                statement.setLong(28, draft.identityRevision());
                statement.setString(29, draft.routeDecision().name());
                statement.setString(30, normalizeOptionalIdentifier(
                        draft.routeItemId(), "routeItemId"));
                statement.setString(31, now);
                statement.setString(32, now);
                statement.executeUpdate();
            }
            return new IngestIntentWrite(
                    findIngestIntentUnsafe(draft.intentKey()).orElseThrow(), true);
        });
    }

    /** Marks the message board local sequence known without yet creating an observation. */
    public synchronized IngestIntentWrite markIngestIntentBoardCommitted(String intentKey,
                                                                          long boardLocalSeq) {
        requireIdentifier(intentKey, "intentKey");
        if (boardLocalSeq < 0) {
            throw invalid("boardLocalSeq 不能为负");
        }
        return transaction("ingest intent board commit", () -> {
            IngestIntent current = requireIngestIntentUnsafe(intentKey);
            if (current.state() == IngestIntentState.OBSERVATION_COMMITTED) {
                if (current.boardLocalSeq().orElseThrow() != boardLocalSeq) {
                    throw new StoreException(ErrorCode.INGEST_INTENT_STATE_CONFLICT,
                            "intent 已完成，boardLocalSeq 不得改写");
                }
                return new IngestIntentWrite(current, false);
            }
            if (current.state() == IngestIntentState.BOARD_COMMITTED) {
                if (current.boardLocalSeq().orElseThrow() != boardLocalSeq) {
                    throw new StoreException(ErrorCode.INGEST_INTENT_STATE_CONFLICT,
                            "intent boardLocalSeq 已确定，重放不得覆盖");
                }
                return new IngestIntentWrite(current, false);
            }

            String now = timestamp(clock.instant());
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE channel_ingest_intent
                    SET state = 'BOARD_COMMITTED', board_local_seq = ?, updated_at = ?
                    WHERE id = ? AND state = 'RECORDED'
                    """)) {
                statement.setLong(1, boardLocalSeq);
                statement.setString(2, now);
                statement.setLong(3, current.id());
                if (statement.executeUpdate() != 1) {
                    throw new StoreException(ErrorCode.INGEST_INTENT_STATE_CONFLICT,
                            "ingest intent 已被并发修改");
                }
            }
            return new IngestIntentWrite(
                    requireIngestIntentUnsafe(intentKey), true);
        });
    }

    /**
     * Atomically promotes a board-committed intent snapshot into an observation and its original
     * route decision. This works for a durable BOARD_COMMITTED intent and for a RECORDED intent
     * whose board row was discovered by recovery scanning after the process died before marking the
     * board commit. It never re-reads the current gate/binding generation.
     *
     * <p>The caller may only invoke this after observing the corresponding board row; this store
     * cannot verify an external message board and never fabricates content/facts.</p>
     */
    public synchronized IngestPromotion promoteIngestIntent(String intentKey,
                                                             long boardLocalSeq) {
        requireIdentifier(intentKey, "intentKey");
        if (boardLocalSeq < 0) {
            throw invalid("boardLocalSeq 不能为负");
        }
        return transaction("ingest intent promotion", () -> {
            IngestIntent intent = requireIngestIntentUnsafe(intentKey);
            if (intent.state() == IngestIntentState.OBSERVATION_COMMITTED) {
                if (intent.boardLocalSeq().orElseThrow() != boardLocalSeq) {
                    throw new StoreException(ErrorCode.INGEST_INTENT_STATE_CONFLICT,
                            "intent 已完成，boardLocalSeq 不得改写");
                }
                Observation observation = requireObservationUnsafe(
                        intent.rawSource(), intent.channelMessageId());
                return new IngestPromotion(intent, observation, false, false);
            }
            if (intent.state() == IngestIntentState.BOARD_COMMITTED
                    && intent.boardLocalSeq().orElseThrow() != boardLocalSeq) {
                throw new StoreException(ErrorCode.INGEST_INTENT_STATE_CONFLICT,
                        "intent boardLocalSeq 已确定，重放不得覆盖");
            }

            ObservationDraft draft = draftFromIntent(intent, boardLocalSeq);
            Observation existing = findObservationUnsafe(
                    draft.rawSource(), draft.channelMessageId()).orElse(null);
            boolean created = false;
            Observation observation;
            if (existing == null) {
                insertObservationUnsafe(draft, intent.bindingGeneration(),
                        intent.intentKey(), intent.routeReason());
                created = true;
                observation = requireObservationUnsafe(
                        draft.rawSource(), draft.channelMessageId());
            } else {
                verifySameObservation(existing, draft);
                observation = existing;
            }

            if (intent.routeDecision() != RouteState.PENDING_ROUTE) {
                if (observation.routeState() == RouteState.PENDING_ROUTE) {
                    RouteUpdate routed = applyRouteDecisionUnsafe(
                            intent.rawSource(), intent.channelMessageId(),
                            intent.routeDecision(), intent.routeItemId().orElse(null),
                            intent.routeReason());
                    observation = routed.observation();
                } else if (observation.routeState() == intent.routeDecision()
                        && sameItem(observation, intent)) {
                    // Already at the original decision.
                } else if (intent.routeDecision() == RouteState.DISPLAY_PENDING
                        && observation.routeState() == RouteState.DISPLAY_LINKED
                        && sameItem(observation, intent)) {
                    // A later legal display transition already completed; do not demote it.
                } else {
                    throw new StoreException(ErrorCode.INGEST_INTENT_STATE_CONFLICT,
                            "已有 observation route 与 intent 首次决策冲突，不得覆盖");
                }
            }

            String now = timestamp(clock.instant());
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE channel_ingest_intent
                    SET state = 'OBSERVATION_COMMITTED', board_local_seq = ?, updated_at = ?
                    WHERE id = ? AND state IN ('RECORDED', 'BOARD_COMMITTED')
                    """)) {
                statement.setLong(1, boardLocalSeq);
                statement.setString(2, now);
                statement.setLong(3, intent.id());
                if (statement.executeUpdate() != 1) {
                    throw new StoreException(ErrorCode.INGEST_INTENT_STATE_CONFLICT,
                            "ingest intent 已被并发修改");
                }
            }
            IngestIntent completed = requireIngestIntentUnsafe(intentKey);
            return new IngestPromotion(completed,
                    requireObservationUnsafe(intent.rawSource(), intent.channelMessageId()),
                    created, true);
        });
    }

    public synchronized Optional<IngestIntent> findIngestIntent(String intentKey) {
        requireIdentifier(intentKey, "intentKey");
        ensureOpen();
        try {
            return findIngestIntentUnsafe(intentKey);
        } catch (SQLException failure) {
            throw storageFailure("ingest intent read", failure);
        }
    }

    public synchronized Optional<IngestIntent> findIngestIntentByMessage(
            String rawSource, String channelMessageId) {
        requireIdentifier(rawSource, "rawSource");
        requireIdentifier(channelMessageId, "channelMessageId");
        ensureOpen();
        try {
            return findIngestIntentByMessageUnsafe(rawSource, channelMessageId);
        } catch (SQLException failure) {
            throw storageFailure("ingest intent read", failure);
        }
    }

    /** Recovery hook: durable intents that have not yet produced an observation. */
    public synchronized List<IngestIntent> pendingIngestIntents(String rawSource, int limit) {
        requireIdentifier(rawSource, "rawSource");
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw invalid("limit 必须在 1.." + MAX_PAGE_SIZE + " 内");
        }
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, intent_key, observation_id, request_key, thread_id, channel, account_id,
                       raw_source, channel_message_id, sender_id, canonical_sender_id,
                       identity_status, direction, message_at,
                       observed_at, priority, time_reliability, content_integrity,
                       structured_mentions_trusted, binding_generation, gate_enabled,
                       binding_epoch_id, route_reason,
                       preferred_channel, preferred_channel_availability, selected_channel,
                       matching_mode, strict_time_tolerance_seconds,
                       identity_revision, route_decision, route_item_id,
                       state, board_local_seq, created_at, updated_at
                FROM channel_ingest_intent
                WHERE raw_source = ? AND state <> 'OBSERVATION_COMMITTED'
                ORDER BY id ASC LIMIT ?
                """)) {
            statement.setString(1, rawSource);
            statement.setInt(2, limit);
            List<IngestIntent> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(mapIngestIntent(result));
                }
            }
            return List.copyOf(rows);
        } catch (SQLException failure) {
            throw storageFailure("pending ingest intent read", failure);
        }
    }

    /**
     * Global pre-observation intent keyset page across raw sources. This includes intents whose
     * source is currently unbound; no binding join is performed.
     */
    public synchronized IngestIntentPage pendingIngestIntents(long afterId, int limit) {
        if (afterId < 0) {
            throw invalid("afterId 不能为负");
        }
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw invalid("limit 必须在 1.." + MAX_PAGE_SIZE + " 内");
        }
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, intent_key, observation_id, request_key, thread_id, channel,
                       account_id, raw_source, channel_message_id, sender_id,
                       canonical_sender_id, identity_status, direction, message_at,
                       observed_at, priority, time_reliability, content_integrity,
                       structured_mentions_trusted, binding_generation, gate_enabled,
                       binding_epoch_id, route_reason,
                       preferred_channel, preferred_channel_availability, selected_channel,
                       matching_mode, strict_time_tolerance_seconds, identity_revision,
                       route_decision, route_item_id, state, board_local_seq, created_at,
                       updated_at
                FROM channel_ingest_intent
                WHERE state <> 'OBSERVATION_COMMITTED' AND id > ?
                ORDER BY id ASC LIMIT ?
                """)) {
            statement.setLong(1, afterId);
            statement.setInt(2, limit + 1);
            List<IngestIntent> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(mapIngestIntent(result));
                }
            }
            boolean hasMore = rows.size() > limit;
            if (hasMore) {
                rows = new ArrayList<>(rows.subList(0, limit));
            }
            long nextAfterId = rows.isEmpty() ? afterId : rows.get(rows.size() - 1).id();
            return new IngestIntentPage(rows, hasMore, nextAfterId);
        } catch (SQLException failure) {
            throw storageFailure("global pending intent page read", failure);
        }
    }

    public synchronized IngestIntentCounts ingestIntentCounts(String rawSource) {
        requireIdentifier(rawSource, "rawSource");
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT state, COUNT(*) AS state_count
                FROM channel_ingest_intent WHERE raw_source = ?
                GROUP BY state
                """)) {
            statement.setString(1, rawSource);
            long recorded = 0;
            long boardCommitted = 0;
            long observationCommitted = 0;
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long count = result.getLong("state_count");
                    switch (IngestIntentState.valueOf(result.getString("state"))) {
                        case RECORDED -> recorded += count;
                        case BOARD_COMMITTED -> boardCommitted += count;
                        case OBSERVATION_COMMITTED -> observationCommitted += count;
                    }
                }
            }
            long total = recorded + boardCommitted + observationCommitted;
            return new IngestIntentCounts(rawSource, total, recorded, boardCommitted,
                    observationCommitted, recorded + boardCommitted);
        } catch (SQLException failure) {
            throw storageFailure("ingest intent count read", failure);
        }
    }

    public synchronized List<RouteHistoryEntry> routeHistory(String rawSource,
                                                              String channelMessageId) {
        requireIdentifier(rawSource, "rawSource");
        requireIdentifier(channelMessageId, "channelMessageId");
        ensureOpen();
        try {
            Observation observation = requireObservationUnsafe(rawSource, channelMessageId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, observation_id, from_state, to_state, item_id, changed_at
                    FROM channel_route_history WHERE observation_id = ? ORDER BY id ASC
                    """)) {
                statement.setString(1, observation.observationId());
                List<RouteHistoryEntry> entries = new ArrayList<>();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        String from = result.getString("from_state");
                        String item = result.getString("item_id");
                        entries.add(new RouteHistoryEntry(
                                result.getLong("id"),
                                result.getString("observation_id"),
                                from == null ? Optional.empty()
                                        : Optional.of(RouteState.valueOf(from)),
                                RouteState.valueOf(result.getString("to_state")),
                                item == null ? Optional.empty() : Optional.of(item),
                                Instant.parse(result.getString("changed_at"))));
                    }
                }
                return List.copyOf(entries);
            }
        } catch (SQLException failure) {
            throw storageFailure("route history read", failure);
        }
    }

    /**
     * Atomically persists matcher-supplied correlation state. This method does not compare any
     * content; it only validates that peer references are safe and belong to the same thread.
     */
    public synchronized List<Observation> recordCorrelation(List<CorrelationUpdate> updates) {
        Objects.requireNonNull(updates, "updates");
        if (updates.size() > MAX_SCAN_BATCH) {
            throw invalid("correlation update 数量超过上限");
        }
        List<Observation> results = new ArrayList<>();
        if (updates.isEmpty()) {
            return results;
        }
        return transaction("correlation update", () -> {
            Set<String> unique = new HashSet<>();
            for (CorrelationUpdate update : updates) {
                Objects.requireNonNull(update, "update");
                requireIdentifier(update.rawSource(), "rawSource");
                requireIdentifier(update.channelMessageId(), "channelMessageId");
                if (!unique.add(update.rawSource() + "\0" + update.channelMessageId())) {
                    throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                            "同一 observation 在批次内重复");
                }
                results.add(applyCorrelationUnsafe(update));
            }
            return List.copyOf(results);
        });
    }

    public synchronized Observation recordCorrelation(CorrelationUpdate update) {
        Objects.requireNonNull(update, "update");
        return recordCorrelation(List.of(update)).get(0);
    }

    /**
     * Atomically records a symmetric 1:1 evidence pair and updates both observations.
     *
     * <p>{@code channel_correlation_pair_member.observation_id} is the cross-column PRIMARY KEY,
     * so a single observation can never be an a-side in one pair and a b-side in another. If either
     * side already belongs to a different pair this call fails closed; callers must explicitly
     * {@link #releaseCorrelationPair} before recomputing or changing a pair. Replaying the same
     * pair and state is a no-op.</p>
     */
    public synchronized CorrelationPairWrite recordCorrelationPair(
            String firstRawSource,
            String firstChannelMessageId,
            String secondRawSource,
            String secondChannelMessageId,
            CorrelationState state) {
        requireIdentifier(firstRawSource, "firstRawSource");
        requireIdentifier(firstChannelMessageId, "firstChannelMessageId");
        requireIdentifier(secondRawSource, "secondRawSource");
        requireIdentifier(secondChannelMessageId, "secondChannelMessageId");
        Objects.requireNonNull(state, "state");
        if (!correlationNeedsPeer(state)) {
            throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                    "双向 pair 只接受 MATCHED/UNVERIFIED_MATCH/CONFLICT/"
                            + "SUPPRESSED_UNVERIFIED");
        }
        return transaction("correlation pair update", () -> {
            Observation first = requireObservationUnsafe(
                    firstRawSource, firstChannelMessageId);
            Observation second = requireObservationUnsafe(
                    secondRawSource, secondChannelMessageId);
            return upsertCorrelationPairUnsafe(first, second, state);
        });
    }

    public synchronized Optional<CorrelationPair> findCorrelationPair(
            String rawSource, String channelMessageId) {
        requireIdentifier(rawSource, "rawSource");
        requireIdentifier(channelMessageId, "channelMessageId");
        ensureOpen();
        try {
            Observation observation = requireObservationUnsafe(rawSource, channelMessageId);
            return findCorrelationPairByObservationUnsafe(observation.observationId());
        } catch (SQLException failure) {
            throw storageFailure("correlation pair read", failure);
        }
    }

    /** Resolves both sides of a surviving pair to complete observations for safe peer recovery. */
    public synchronized Optional<CorrelationPairObservations> correlationPairObservations(
            String rawSource, String channelMessageId) {
        requireIdentifier(rawSource, "rawSource");
        requireIdentifier(channelMessageId, "channelMessageId");
        ensureOpen();
        try {
            Observation observation = requireObservationUnsafe(rawSource, channelMessageId);
            Optional<CorrelationPair> pair = findCorrelationPairByObservationUnsafe(
                    observation.observationId());
            if (pair.isEmpty()) {
                return Optional.empty();
            }
            CorrelationPair current = pair.orElseThrow();
            return Optional.of(new CorrelationPairObservations(
                    current,
                    requireObservationByIdUnsafe(current.observationAId()),
                    requireObservationByIdUnsafe(current.observationBId())));
        } catch (SQLException failure) {
            throw storageFailure("correlation pair observation read", failure);
        }
    }

    /**
     * Explicitly breaks one pair and demotes both observations to UNVERIFIED with no peer. The
     * pair row is deleted and both observation revisions advance in the same transaction. A later
     * recomputation may create a new pair only after this release.
     */
    public synchronized CorrelationPairRelease releaseCorrelationPair(
            String rawSource, String channelMessageId) {
        requireIdentifier(rawSource, "rawSource");
        requireIdentifier(channelMessageId, "channelMessageId");
        return transaction("correlation pair release", () -> {
            Observation observation = requireObservationUnsafe(rawSource, channelMessageId);
            CorrelationPair pair = findCorrelationPairByObservationUnsafe(
                    observation.observationId()).orElseThrow(() ->
                    new StoreException(ErrorCode.CORRELATION_PAIR_NOT_FOUND,
                            "observation 不属于任何 correlation pair"));
            String now = timestamp(clock.instant());
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM channel_correlation_pair_member WHERE pair_key = ?")) {
                statement.setString(1, pair.pairKey());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM channel_correlation_pair WHERE pair_key = ?")) {
                statement.setString(1, pair.pairKey());
                if (statement.executeUpdate() != 1) {
                    throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                            "correlation pair 已被并发修改");
                }
            }
            appendCorrelationAuditUnsafe(pair, CorrelationAuditAction.RELEASE,
                    pair.state(), CorrelationState.UNVERIFIED, now);
            List<Observation> released = new ArrayList<>();
            for (String observationId : List.of(
                    pair.observationAId(), pair.observationBId())) {
                Observation side = requireObservationByIdUnsafe(observationId);
                writeCorrelationSideUnsafe(side, CorrelationState.UNVERIFIED, null, now);
                released.add(requireObservationByIdUnsafe(observationId));
            }
            return new CorrelationPairRelease(pair, released, true);
        });
    }

    /** Append-only correlation audit for all pairs ever involving this observation. */
    public synchronized List<CorrelationAuditEntry> correlationHistory(
            String rawSource, String channelMessageId) {
        requireIdentifier(rawSource, "rawSource");
        requireIdentifier(channelMessageId, "channelMessageId");
        ensureOpen();
        try {
            Observation observation = requireObservationUnsafe(rawSource, channelMessageId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, pair_key, action, observation_a_id, observation_b_id,
                           from_state, to_state, changed_at
                    FROM channel_correlation_audit
                    WHERE observation_a_id = ? OR observation_b_id = ?
                    ORDER BY id ASC
                    """)) {
                statement.setString(1, observation.observationId());
                statement.setString(2, observation.observationId());
                return readCorrelationAudit(statement);
            }
        } catch (SQLException failure) {
            throw storageFailure("correlation audit read", failure);
        }
    }

    public synchronized List<CorrelationAuditEntry> correlationHistoryForPair(String pairKey) {
        requireIdentifier(pairKey, "pairKey");
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, pair_key, action, observation_a_id, observation_b_id,
                       from_state, to_state, changed_at
                FROM channel_correlation_audit WHERE pair_key = ? ORDER BY id ASC
                """)) {
            statement.setString(1, pairKey);
            return readCorrelationAudit(statement);
        } catch (SQLException failure) {
            throw storageFailure("correlation audit read", failure);
        }
    }

    private List<CorrelationAuditEntry> readCorrelationAudit(PreparedStatement statement)
            throws SQLException {
        List<CorrelationAuditEntry> entries = new ArrayList<>();
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String from = result.getString("from_state");
                String to = result.getString("to_state");
                entries.add(new CorrelationAuditEntry(
                        result.getLong("id"),
                        result.getString("pair_key"),
                        CorrelationAuditAction.valueOf(result.getString("action")),
                        result.getString("observation_a_id"),
                        result.getString("observation_b_id"),
                        from == null ? Optional.empty()
                                : Optional.of(CorrelationState.valueOf(from)),
                        to == null ? Optional.empty()
                                : Optional.of(CorrelationState.valueOf(to)),
                        Instant.parse(result.getString("changed_at"))));
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Router-facing non-exclusive fallback suppression path.
     *
     * <p>Atomically records zero-or-more candidate edges, moves the source observation to
     * EVIDENCE_ONLY with reason SUPPRESSED_FALLBACK_UNVERIFIED, and sets only the source
     * observation's correlation status to UNVERIFIED. It never creates a correlation pair,
     * never changes a candidate observation and never wakes anything. Recording the same candidate
     * edge again is idempotent; different candidate observations/items are preserved as separate
     * candidates.</p>
     */
    public synchronized SuppressedFallbackWrite recordSuppressedFallbackCandidates(
            String sourceRawSource,
            String sourceChannelMessageId,
            List<EvidenceCandidateDraft> candidates) {
        requireIdentifier(sourceRawSource, "sourceRawSource");
        requireIdentifier(sourceChannelMessageId, "sourceChannelMessageId");
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            throw invalid("suppressed fallback 至少需要一个候选");
        }
        if (candidates.size() > MAX_SCAN_BATCH) {
            throw invalid("candidate 数量超过上限");
        }
        return transaction("suppressed fallback candidates", () -> {
            Observation source = requireObservationUnsafe(
                    sourceRawSource, sourceChannelMessageId);
            if (findCorrelationPairByObservationUnsafe(source.observationId()).isPresent()) {
                throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                        "source observation 已属于 1:1 correlation pair，不能写 fallback candidates");
            }

            int inserted = 0;
            int unchanged = 0;
            String now = timestamp(clock.instant());
            for (EvidenceCandidateDraft draft : candidates) {
                Objects.requireNonNull(draft, "candidate");
                requireIdentifier(draft.candidateRawSource(), "candidateRawSource");
                requireIdentifier(draft.candidateChannelMessageId(),
                        "candidateChannelMessageId");
                String itemId = normalizeOptionalIdentifier(
                        draft.candidateItemId(), "candidateItemId");
                Observation candidate = requireObservationUnsafe(
                        draft.candidateRawSource(), draft.candidateChannelMessageId());
                if (candidate.observationId().equals(source.observationId())) {
                    throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                            "source 不能作为自己的 fallback candidate");
                }
                if (!candidate.threadId().equals(source.threadId())) {
                    throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                            "fallback candidate 必须属于同一 logical thread");
                }
                if (candidate.channel() == source.channel()) {
                    throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                            "fallback candidate 必须来自另一渠道");
                }
                if (source.bindingEpochId().isBlank()
                        || !source.bindingEpochId().equals(candidate.bindingEpochId())) {
                    throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                            "fallback candidate 必须属于同一显式 bindingEpoch");
                }

                String itemKey = itemId == null ? "" : itemId;
                EvidenceCandidateRecord existing = findEvidenceCandidateUnsafe(
                        source.observationId(), candidate.observationId(), itemKey)
                        .orElse(null);
                if (existing != null) {
                    if (!existing.threadId().equals(source.threadId())
                            || !existing.bindingEpochId().equals(source.bindingEpochId())
                            || existing.reason()
                            != RouteReason.SUPPRESSED_FALLBACK_UNVERIFIED) {
                        throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                                "已有 fallback candidate 的 thread/epoch/reason 不一致");
                    }
                    unchanged++;
                } else {
                    insertEvidenceCandidateUnsafe(source, candidate, itemId, now);
                    inserted++;
                }
            }

            boolean changed = inserted > 0;
            Observation current = requireObservationByIdUnsafe(source.observationId());
            if (current.routeState() == RouteState.PENDING_ROUTE) {
                current = applyRouteDecisionUnsafe(sourceRawSource, sourceChannelMessageId,
                        RouteState.EVIDENCE_ONLY, null,
                        RouteReason.SUPPRESSED_FALLBACK_UNVERIFIED).observation();
                changed = true;
            } else if (current.routeState() == RouteState.EVIDENCE_ONLY) {
                if (current.routeReason() != RouteReason.SUPPRESSED_FALLBACK_UNVERIFIED) {
                    current = updateEvidenceRouteReasonUnsafe(current,
                            RouteReason.SUPPRESSED_FALLBACK_UNVERIFIED, now);
                    changed = true;
                }
            } else {
                throw new StoreException(ErrorCode.ROUTE_CONFLICT,
                        "source route 必须为 PENDING_ROUTE 或 EVIDENCE_ONLY 才能记录 fallback suppression");
            }

            if (current.correlationState() != CorrelationState.UNVERIFIED
                    || current.peerObservationId().isPresent()) {
                if (current.correlationState() != CorrelationState.CHANNEL_ONLY
                        && current.correlationState() != CorrelationState.UNVERIFIED) {
                    throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                            "source correlation 状态不允许转为 fallback UNVERIFIED");
                }
                writeCorrelationSideUnsafe(current, CorrelationState.UNVERIFIED, null, now);
                changed = true;
            }

            return new SuppressedFallbackWrite(
                    requireObservationByIdUnsafe(source.observationId()),
                    evidenceCandidatesForSourceUnsafe(source.observationId()),
                    inserted, unchanged, changed);
        });
    }

    public synchronized List<EvidenceCandidateRecord> evidenceCandidatesForSource(
            String rawSource, String channelMessageId) {
        requireIdentifier(rawSource, "rawSource");
        requireIdentifier(channelMessageId, "channelMessageId");
        ensureOpen();
        try {
            Observation source = requireObservationUnsafe(rawSource, channelMessageId);
            return evidenceCandidatesForSourceUnsafe(source.observationId());
        } catch (SQLException failure) {
            throw storageFailure("evidence candidate read", failure);
        }
    }

    /** Global keyset page of EVIDENCE_ONLY targets still awaiting Inbox application. */
    public synchronized EvidenceOutboxPage pendingEvidenceOutbox(
            int limit, String afterObservationId) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw invalid("limit 必须在 1.." + MAX_PAGE_SIZE + " 内");
        }
        String cursor = afterObservationId == null ? "" : afterObservationId;
        if (containsControl(cursor)) {
            throw invalid("afterObservationId 不能包含控制字符");
        }
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT o.observation_id, o.thread_id, o.channel, o.account_id, o.raw_source,
                       o.channel_message_id, o.board_local_seq, o.sender_id,
                       o.canonical_sender_id, o.identity_status, o.identity_revision,
                       o.binding_generation, o.binding_epoch_id, o.ingest_intent_key,
                       o.route_reason, o.direction, o.message_at, o.observed_at,
                       o.priority, o.time_reliability, o.content_integrity,
                       o.structured_mentions_trusted, o.strict_time_tolerance_seconds,
                       o.route_state, o.route_request_key,
                       o.route_item_id, o.route_revision, o.route_updated_at,
                       o.correlation_state, o.correlation_peer_observation_id,
                       o.correlation_revision, o.correlation_updated_at,
                       e.target_item_id AS outbox_target_item_id,
                       e.state AS outbox_state, e.revision AS outbox_revision,
                       e.created_at AS outbox_created_at, e.updated_at AS outbox_updated_at
                FROM channel_evidence_outbox e
                JOIN channel_observation o ON o.observation_id = e.observation_id
                WHERE e.state = 'PENDING' AND e.observation_id > ?
                ORDER BY e.observation_id ASC LIMIT ?
                """)) {
            statement.setString(1, cursor);
            statement.setInt(2, limit + 1);
            List<EvidenceOutboxEntry> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(mapEvidenceOutbox(result));
                }
            }
            boolean hasMore = rows.size() > limit;
            if (hasMore) {
                rows = new ArrayList<>(rows.subList(0, limit));
            }
            Optional<String> next = hasMore && !rows.isEmpty()
                    ? Optional.of(rows.get(rows.size() - 1).observation().observationId())
                    : Optional.empty();
            return new EvidenceOutboxPage(rows, hasMore, next);
        } catch (SQLException failure) {
            throw storageFailure("evidence outbox page read", failure);
        }
    }

    public synchronized Optional<EvidenceOutboxEntry> evidenceOutboxEntry(
            String rawSource, String channelMessageId) {
        requireIdentifier(rawSource, "rawSource");
        requireIdentifier(channelMessageId, "channelMessageId");
        ensureOpen();
        try {
            Observation observation = requireObservationUnsafe(rawSource, channelMessageId);
            return readEvidenceOutboxUnsafe(observation.observationId());
        } catch (SQLException failure) {
            throw storageFailure("evidence outbox read", failure);
        }
    }

    /** Marks a pending EVIDENCE_ONLY target as successfully applied; terminal replay is a no-op. */
    public synchronized EvidenceOutboxEntry markEvidenceOutboxApplied(
            String rawSource, String channelMessageId) {
        return completeEvidenceOutbox(rawSource, channelMessageId, EvidenceOutboxState.APPLIED);
    }

    /** Marks a pending EVIDENCE_ONLY target as gone; terminal replay is a no-op. */
    public synchronized EvidenceOutboxEntry markEvidenceOutboxTargetMissing(
            String rawSource, String channelMessageId) {
        return completeEvidenceOutbox(
                rawSource, channelMessageId, EvidenceOutboxState.TARGET_MISSING);
    }

    private EvidenceOutboxEntry completeEvidenceOutbox(String rawSource,
                                                        String channelMessageId,
                                                        EvidenceOutboxState terminalState) {
        requireIdentifier(rawSource, "rawSource");
        requireIdentifier(channelMessageId, "channelMessageId");
        Objects.requireNonNull(terminalState, "terminalState");
        if (terminalState == EvidenceOutboxState.PENDING) {
            throw invalid("terminalState 必须为 APPLIED 或 TARGET_MISSING");
        }
        return transaction("evidence outbox complete", () -> {
            Observation observation = requireObservationUnsafe(rawSource, channelMessageId);
            EvidenceOutboxEntry current = readEvidenceOutboxUnsafe(observation.observationId())
                    .orElseThrow(() -> new StoreException(ErrorCode.EVIDENCE_OUTBOX_NOT_FOUND,
                            "observation 没有 evidence outbox row"));
            if (current.state() == terminalState) {
                return current;
            }
            if (current.state() != EvidenceOutboxState.PENDING) {
                throw new StoreException(ErrorCode.EVIDENCE_OUTBOX_STATE_CONFLICT,
                        "evidence outbox 已处于另一终态，不得改写");
            }
            String now = timestamp(clock.instant());
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE channel_evidence_outbox
                    SET state = ?, revision = revision + 1, updated_at = ?
                    WHERE observation_id = ? AND state = 'PENDING' AND revision = ?
                    """)) {
                statement.setString(1, terminalState.name());
                statement.setString(2, now);
                statement.setString(3, observation.observationId());
                statement.setLong(4, current.revision());
                if (statement.executeUpdate() != 1) {
                    throw new StoreException(ErrorCode.EVIDENCE_OUTBOX_STATE_CONFLICT,
                            "evidence outbox 已被并发修改");
                }
            }
            return readEvidenceOutboxUnsafe(observation.observationId()).orElseThrow();
        });
    }

    private Optional<EvidenceOutboxEntry> readEvidenceOutboxUnsafe(String observationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT o.observation_id, o.thread_id, o.channel, o.account_id, o.raw_source,
                       o.channel_message_id, o.board_local_seq, o.sender_id,
                       o.canonical_sender_id, o.identity_status, o.identity_revision,
                       o.binding_generation, o.binding_epoch_id, o.ingest_intent_key,
                       o.route_reason, o.direction, o.message_at, o.observed_at,
                       o.priority, o.time_reliability, o.content_integrity,
                       o.structured_mentions_trusted, o.strict_time_tolerance_seconds,
                       o.route_state, o.route_request_key,
                       o.route_item_id, o.route_revision, o.route_updated_at,
                       o.correlation_state, o.correlation_peer_observation_id,
                       o.correlation_revision, o.correlation_updated_at,
                       e.target_item_id AS outbox_target_item_id,
                       e.state AS outbox_state, e.revision AS outbox_revision,
                       e.created_at AS outbox_created_at, e.updated_at AS outbox_updated_at
                FROM channel_evidence_outbox e
                JOIN channel_observation o ON o.observation_id = e.observation_id
                WHERE e.observation_id = ?
                """)) {
            statement.setString(1, observationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapEvidenceOutbox(result))
                        : Optional.empty();
            }
        }
    }

    private EvidenceOutboxEntry mapEvidenceOutbox(ResultSet result) throws SQLException {
        return new EvidenceOutboxEntry(
                mapObservation(result),
                result.getString("outbox_target_item_id"),
                EvidenceOutboxState.valueOf(result.getString("outbox_state")),
                result.getLong("outbox_revision"),
                Instant.parse(result.getString("outbox_created_at")),
                Instant.parse(result.getString("outbox_updated_at")));
    }

    /**
     * Establishes or repeats one active source binding generation.
     *
     * <p>{@code startBoardSeq} is inclusive and is converted to the initial cursor
     * {@code startBoardSeq - 1}. A repeated exact call is a no-op. A conflicting active call does
     * not move the boundary. Rebinding an unbound source creates a new generation with a new
     * boundary; prior observations and route/item history remain untouched.</p>
     */
    public synchronized BindingUpdate beginBinding(String rawSource,
                                                    String threadId,
                                                    Channel channel,
                                                    String accountId,
                                                    long startBoardSeq) {
        requireIdentifier(rawSource, "rawSource");
        validateThreadId(threadId);
        Objects.requireNonNull(channel, "channel");
        requireIdentifier(accountId, "accountId");
        validateRawSource(rawSource, channel, accountId);
        if (startBoardSeq < 0) {
            throw invalid("startBoardSeq 不能为负");
        }
        return transaction("source binding", () -> {
            SourceBinding existing = findBindingUnsafe(rawSource).orElse(null);
            Instant now = clock.instant();
            String nowText = timestamp(now);
            if (existing != null && existing.active()) {
                if (!existing.threadId().equals(threadId)
                        || existing.channel() != channel
                        || !existing.accountId().equals(accountId)) {
                    throw new StoreException(ErrorCode.SOURCE_ALREADY_BOUND,
                            "raw source 已绑定到另一 thread/channel/account");
                }
                if (existing.startBoardSeq() != startBoardSeq) {
                    throw new StoreException(ErrorCode.BINDING_CONFLICT,
                            "active binding 的 startBoardSeq 已确定，重放不得移动边界");
                }
                return new BindingUpdate(existing, false);
            }

            long generation = existing == null ? 1 : existing.generation() + 1;
            long scanCursor = startBoardSeq - 1;
            if (existing == null) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO channel_source_scan_state(
                          raw_source, generation, thread_id, channel, account_id, active,
                          start_board_seq, scan_cursor, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, rawSource);
                    statement.setLong(2, generation);
                    statement.setString(3, threadId);
                    statement.setString(4, channel.wireName());
                    statement.setString(5, accountId);
                    statement.setLong(6, startBoardSeq);
                    statement.setLong(7, scanCursor);
                    statement.setString(8, nowText);
                    statement.setString(9, nowText);
                    statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE channel_source_scan_state
                        SET generation = ?, thread_id = ?, channel = ?, account_id = ?,
                            active = 1, start_board_seq = ?, scan_cursor = ?, updated_at = ?
                        WHERE raw_source = ? AND active = 0 AND generation = ?
                        """)) {
                    statement.setLong(1, generation);
                    statement.setString(2, threadId);
                    statement.setString(3, channel.wireName());
                    statement.setString(4, accountId);
                    statement.setLong(5, startBoardSeq);
                    statement.setLong(6, scanCursor);
                    statement.setString(7, nowText);
                    statement.setString(8, rawSource);
                    statement.setLong(9, existing.generation());
                    if (statement.executeUpdate() != 1) {
                        throw new StoreException(ErrorCode.BINDING_CONFLICT,
                                "source binding 已被并发修改");
                    }
                }
            }
            appendBindingEvent(rawSource, generation, threadId, channel, accountId,
                    startBoardSeq, null, BindingEventKind.BOUND, nowText);
            return new BindingUpdate(findBindingUnsafe(rawSource).orElseThrow(), true);
        });
    }

    /** Marks a source unbound, appends history, and leaves every observation intact. */
    public synchronized BindingUpdate unbindSource(String rawSource) {
        requireIdentifier(rawSource, "rawSource");
        return transaction("source unbind", () -> {
            SourceBinding existing = findBindingUnsafe(rawSource).orElseThrow(() ->
                    new StoreException(ErrorCode.SOURCE_BINDING_NOT_FOUND,
                            "raw source 尚无 binding 记录"));
            if (!existing.active()) {
                return new BindingUpdate(existing, false);
            }
            String now = timestamp(clock.instant());
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE channel_source_scan_state
                    SET active = 0, updated_at = ?
                    WHERE raw_source = ? AND active = 1 AND generation = ?
                    """)) {
                statement.setString(1, now);
                statement.setString(2, rawSource);
                statement.setLong(3, existing.generation());
                if (statement.executeUpdate() != 1) {
                    throw new StoreException(ErrorCode.BINDING_CONFLICT,
                            "source binding 已被并发修改");
                }
            }
            appendBindingEvent(rawSource, existing.generation(), existing.threadId(),
                    existing.channel(), existing.accountId(), existing.startBoardSeq(),
                    existing.scanCursor(), BindingEventKind.UNBOUND, now);
            return new BindingUpdate(findBindingUnsafe(rawSource).orElseThrow(), true);
        });
    }

    public synchronized Optional<SourceBinding> sourceBinding(String rawSource) {
        requireIdentifier(rawSource, "rawSource");
        ensureOpen();
        try {
            return findBindingUnsafe(rawSource);
        } catch (SQLException failure) {
            throw storageFailure("source binding read", failure);
        }
    }

    /**
     * Global source-binding keyset page independent of registry/observed/thread state. It returns
     * both active and inactive generations, so a crash between ledger beginBinding and registry
     * binding remains discoverable.
     */
    public synchronized SourceBindingPage sourceBindings(String afterRawSource, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw invalid("limit 必须在 1.." + MAX_PAGE_SIZE + " 内");
        }
        String cursor = afterRawSource == null ? "" : afterRawSource;
        if (containsControl(cursor)) {
            throw invalid("afterRawSource 不能包含控制字符");
        }
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT raw_source, generation, thread_id, channel, account_id, active,
                       start_board_seq, scan_cursor, created_at, updated_at
                FROM channel_source_scan_state
                WHERE raw_source > ?
                ORDER BY raw_source ASC LIMIT ?
                """)) {
            statement.setString(1, cursor);
            statement.setInt(2, limit + 1);
            List<SourceBinding> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(mapBinding(result));
                }
            }
            boolean hasMore = rows.size() > limit;
            if (hasMore) {
                rows = new ArrayList<>(rows.subList(0, limit));
            }
            Optional<String> next = hasMore && !rows.isEmpty()
                    ? Optional.of(rows.get(rows.size() - 1).rawSource())
                    : Optional.empty();
            return new SourceBindingPage(rows, hasMore, next);
        } catch (SQLException failure) {
            throw storageFailure("global source binding page read", failure);
        }
    }

    public synchronized List<BindingEvent> bindingHistory(String rawSource) {
        requireIdentifier(rawSource, "rawSource");
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, raw_source, generation, thread_id, channel, account_id,
                       start_board_seq, end_board_seq, kind, created_at
                FROM channel_source_binding_event WHERE raw_source = ? ORDER BY id ASC
                """)) {
            statement.setString(1, rawSource);
            List<BindingEvent> events = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long end = result.getLong("end_board_seq");
                    Optional<Long> endSeq = result.wasNull() ? Optional.empty()
                            : Optional.of(end);
                    events.add(new BindingEvent(
                            result.getLong("id"),
                            result.getString("raw_source"),
                            result.getLong("generation"),
                            result.getString("thread_id"),
                            Channel.fromWire(result.getString("channel")),
                            result.getString("account_id"),
                            result.getLong("start_board_seq"),
                            endSeq,
                            BindingEventKind.valueOf(result.getString("kind")),
                            Instant.parse(result.getString("created_at"))));
                }
            }
            return List.copyOf(events);
        } catch (SQLException failure) {
            throw storageFailure("binding history read", failure);
        }
    }

    /** Current active scan window. The caller reads from {@code fromBoardSeq} inclusively. */
    public synchronized ScanWindow scanWindow(String rawSource) {
        requireIdentifier(rawSource, "rawSource");
        ensureOpen();
        try {
            SourceBinding binding = requireActiveBindingUnsafe(rawSource);
            if (binding.scanCursor() == Long.MAX_VALUE) {
                throw new StoreException(ErrorCode.CURSOR_EXHAUSTED,
                        "source scan cursor 已到上限");
            }
            return new ScanWindow(rawSource, binding.generation(), binding.startBoardSeq(),
                    binding.scanCursor(), binding.scanCursor() + 1);
        } catch (SQLException failure) {
            throw storageFailure("scan window read", failure);
        }
    }

    /**
     * Inserts all observations for one scanned board range and advances the cursor in the same
     * transaction. A duplicate/replayed completed range is verified read-only. Any observation
     * conflict, generation mismatch, stale board position or SQL failure rolls back both the batch
     * and the cursor.
     */
    public synchronized ScanCommit ingestScanBatch(String rawSource,
                                                    long expectedGeneration,
                                                    long throughBoardSeq,
                                                    List<ObservationDraft> drafts) {
        requireIdentifier(rawSource, "rawSource");
        Objects.requireNonNull(drafts, "drafts");
        if (drafts.size() > MAX_SCAN_BATCH) {
            throw invalid("scan batch 数量超过上限");
        }
        if (expectedGeneration < 1) {
            throw invalid("expectedGeneration 必须为正");
        }
        if (throughBoardSeq < 0) {
            throw invalid("throughBoardSeq 不能为负");
        }
        return transaction("scan batch", () -> {
            SourceBinding binding = requireActiveBindingUnsafe(rawSource);
            if (binding.generation() != expectedGeneration) {
                throw new StoreException(ErrorCode.GENERATION_MISMATCH,
                        "source binding generation 已变化，旧扫描结果不得写入");
            }
            if (throughBoardSeq < binding.scanCursor()) {
                throw new StoreException(ErrorCode.CURSOR_REGRESSION,
                        "scan cursor 不可倒退");
            }

            if (throughBoardSeq == binding.scanCursor()) {
                for (ObservationDraft draft : drafts) {
                    validateDraftForBinding(draft, rawSource, binding);
                    Observation existing = findObservationUnsafe(
                            draft.rawSource(), draft.channelMessageId()).orElseThrow(() ->
                            new StoreException(ErrorCode.CURSOR_REGRESSION,
                                    "cursor 已推进但重放 observation 不存在"));
                    verifySameObservation(existing, draft);
                }
                return new ScanCommit(binding, 0, drafts.size(), false);
            }

            int inserted = 0;
            int unchanged = 0;
            for (ObservationDraft draft : drafts) {
                validateDraftForBinding(draft, rawSource, binding);
                if (draft.boardLocalSeq() <= binding.scanCursor()) {
                    throw new StoreException(ErrorCode.BOARD_SEQ_BEFORE_BINDING_BOUNDARY,
                            "observation boardLocalSeq 早于当前扫描边界");
                }
                if (draft.boardLocalSeq() > throughBoardSeq) {
                    throw new StoreException(ErrorCode.INVALID_ARGUMENT,
                            "observation boardLocalSeq 超出本次扫描上界");
                }
                Observation existing = findObservationUnsafe(
                        draft.rawSource(), draft.channelMessageId()).orElse(null);
                if (existing != null) {
                    verifySameObservation(existing, draft);
                    unchanged++;
                } else {
                    insertObservationUnsafe(draft);
                    inserted++;
                }
            }

            String now = timestamp(clock.instant());
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE channel_source_scan_state
                    SET scan_cursor = ?, updated_at = ?
                    WHERE raw_source = ? AND active = 1 AND generation = ?
                      AND scan_cursor = ?
                    """)) {
                statement.setLong(1, throughBoardSeq);
                statement.setString(2, now);
                statement.setString(3, rawSource);
                statement.setLong(4, expectedGeneration);
                statement.setLong(5, binding.scanCursor());
                if (statement.executeUpdate() != 1) {
                    throw new StoreException(ErrorCode.CURSOR_REGRESSION,
                            "scan cursor 已被并发修改");
                }
            }
            SourceBinding updated = requireActiveBindingUnsafe(rawSource);
            return new ScanCommit(updated, inserted, unchanged, true);
        });
    }

    private Observation applyCorrelationUnsafe(CorrelationUpdate update) throws SQLException {
        Observation current = requireObservationUnsafe(
                update.rawSource(), update.channelMessageId());
        CorrelationState target = Objects.requireNonNull(update.state(), "state");
        String peerRaw = normalizeOptionalIdentifier(update.peerRawSource(), "peerRawSource");
        String peerMessage = normalizeOptionalIdentifier(
                update.peerChannelMessageId(), "peerChannelMessageId");
        if ((peerRaw == null) != (peerMessage == null)) {
            throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                    "peerRawSource 与 peerChannelMessageId 必须同时提供");
        }

        boolean needsPeer = correlationNeedsPeer(target);
        if (needsPeer) {
            if (peerRaw == null) {
                throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                        "匹配/冲突 correlation 必须带 peer");
            }
            Observation peer = requireObservationUnsafe(peerRaw, peerMessage);
            return upsertCorrelationPairUnsafe(current, peer, target).first();
        }
        if (peerRaw != null) {
            throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                    "CHANNEL_ONLY/UNVERIFIED 不得带 peer");
        }
        if (findCorrelationPairByObservationUnsafe(current.observationId()).isPresent()) {
            throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                    "observation 已属于 correlation pair；须先显式 releaseCorrelationPair");
        }
        if (current.correlationState() == target && current.peerObservationId().isEmpty()) {
            return current;
        }
        String now = timestamp(clock.instant());
        writeCorrelationSideUnsafe(current, target, null, now);
        return requireObservationUnsafe(update.rawSource(), update.channelMessageId());
    }

    private CorrelationPairWrite upsertCorrelationPairUnsafe(Observation first,
                                                              Observation second,
                                                              CorrelationState state)
            throws SQLException {
        if (first.observationId().equals(second.observationId())) {
            throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                    "observation 不能与自己配对");
        }
        if (!first.threadId().equals(second.threadId())) {
            throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                    "cross-thread peer 不允许写入 correlation");
        }
        if (first.channel() == second.channel()) {
            throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                    "匹配/冲突 peer 必须来自另一渠道");
        }
        String firstEpoch = first.bindingEpochId();
        String secondEpoch = second.bindingEpochId();
        if (firstEpoch.isBlank() || !firstEpoch.equals(secondEpoch)) {
            throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                    "pair 必须属于同一显式 bindingEpoch，禁止比较不同 source 的 generation");
        }
        if (state == CorrelationState.MATCHED) {
            boolean firstMapped = first.identityStatus() == IdentityStatus.MAPPED
                    && first.canonicalSenderId().isPresent()
                    && !first.senderId().isBlank();
            boolean secondMapped = second.identityStatus() == IdentityStatus.MAPPED
                    && second.canonicalSenderId().isPresent()
                    && !second.senderId().isBlank();
            if (!firstMapped || !secondMapped
                    || !first.canonicalSenderId().orElseThrow()
                    .equals(second.canonicalSenderId().orElseThrow())) {
                throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                        "MATCHED 需要两侧可靠的同一 canonicalSenderId；空/未知 sender 不得标 MATCHED");
            }
        }
        if (!correlationNeedsPeer(state)) {
            throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                    "pair state 必须带 peer");
        }

        String observationA = first.observationId().compareTo(second.observationId()) < 0
                ? first.observationId() : second.observationId();
        String observationB = observationA.equals(first.observationId())
                ? second.observationId() : first.observationId();
        String pairKey = correlationPairKey(observationA, observationB);
        List<CorrelationPair> overlaps = findCorrelationPairsForObservationsUnsafe(
                first.observationId(), second.observationId());
        CorrelationPair samePair = null;
        for (CorrelationPair pair : overlaps) {
            if (!pair.pairKey().equals(pairKey)) {
                throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                        "observation 已属于另一 correlation pair；须先显式 releaseCorrelationPair");
            }
            samePair = pair;
        }

        String now = timestamp(clock.instant());
        boolean changed = false;
        CorrelationPair pair;
        if (samePair == null) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO channel_correlation_pair(
                      pair_key, observation_a_id, observation_b_id, state, revision,
                      created_at, updated_at)
                    VALUES (?, ?, ?, ?, 1, ?, ?)
                    """)) {
                statement.setString(1, pairKey);
                statement.setString(2, observationA);
                statement.setString(3, observationB);
                statement.setString(4, state.name());
                statement.setString(5, now);
                statement.setString(6, now);
                statement.executeUpdate();
            }
            try (PreparedStatement member = connection.prepareStatement("""
                    INSERT INTO channel_correlation_pair_member(
                      observation_id, pair_key, created_at)
                    VALUES (?, ?, ?)
                    """)) {
                member.setString(1, observationA);
                member.setString(2, pairKey);
                member.setString(3, now);
                member.executeUpdate();
                member.setString(1, observationB);
                member.setString(2, pairKey);
                member.setString(3, now);
                member.executeUpdate();
            }
            changed = true;
            pair = requireCorrelationPairUnsafe(pairKey);
            appendCorrelationAuditUnsafe(pair, CorrelationAuditAction.CREATE,
                    null, state, now);
        } else if (samePair.state() != state) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE channel_correlation_pair
                    SET state = ?, revision = revision + 1, updated_at = ?
                    WHERE pair_key = ? AND revision = ?
                    """)) {
                statement.setString(1, state.name());
                statement.setString(2, now);
                statement.setString(3, samePair.pairKey());
                statement.setLong(4, samePair.revision());
                if (statement.executeUpdate() != 1) {
                    throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                            "correlation pair 已被并发修改");
                }
            }
            changed = true;
            pair = requireCorrelationPairUnsafe(pairKey);
            appendCorrelationAuditUnsafe(pair, CorrelationAuditAction.STATE_CHANGE,
                    samePair.state(), state, now);
        } else {
            pair = samePair;
        }

        Observation updatedFirst = updatePairSideIfNeeded(first, second.observationId(),
                state, now);
        Observation updatedSecond = updatePairSideIfNeeded(second, first.observationId(),
                state, now);
        if (updatedFirst.correlationRevision() != first.correlationRevision()
                || updatedSecond.correlationRevision() != second.correlationRevision()) {
            changed = true;
        }
        // Keep caller order in the result even though the pair row is canonically ordered.
        if (first.observationId().equals(updatedFirst.observationId())) {
            return new CorrelationPairWrite(pair, updatedFirst, updatedSecond, changed);
        }
        return new CorrelationPairWrite(pair, updatedSecond, updatedFirst, changed);
    }

    private Observation updatePairSideIfNeeded(Observation observation,
                                                String peerObservationId,
                                                CorrelationState state,
                                                String now) throws SQLException {
        if (observation.correlationState() == state
                && observation.peerObservationId().orElse("").equals(peerObservationId)) {
            return observation;
        }
        writeCorrelationSideUnsafe(observation, state, peerObservationId, now);
        return requireObservationByIdUnsafe(observation.observationId());
    }

    private void writeCorrelationSideUnsafe(Observation observation,
                                             CorrelationState state,
                                             String peerObservationId,
                                             String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE channel_observation
                SET correlation_state = ?, correlation_peer_observation_id = ?,
                    correlation_revision = correlation_revision + 1,
                    correlation_updated_at = ?
                WHERE observation_id = ? AND correlation_revision = ?
                """)) {
            statement.setString(1, state.name());
            statement.setString(2, peerObservationId);
            statement.setString(3, now);
            statement.setString(4, observation.observationId());
            statement.setLong(5, observation.correlationRevision());
            if (statement.executeUpdate() != 1) {
                throw new StoreException(ErrorCode.CORRELATION_CONFLICT,
                        "correlation 状态已被并发修改");
            }
        }
    }

    private void appendCorrelationAuditUnsafe(CorrelationPair pair,
                                               CorrelationAuditAction action,
                                               CorrelationState fromState,
                                               CorrelationState toState,
                                               String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO channel_correlation_audit(
                  pair_key, action, observation_a_id, observation_b_id,
                  from_state, to_state, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, pair.pairKey());
            statement.setString(2, action.name());
            statement.setString(3, pair.observationAId());
            statement.setString(4, pair.observationBId());
            statement.setString(5, fromState == null ? null : fromState.name());
            statement.setString(6, toState == null ? null : toState.name());
            statement.setString(7, now);
            statement.executeUpdate();
        }
    }

    private void upsertEvidenceOutboxUnsafe(String observationId,
                                             String targetItemId,
                                             String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT target_item_id, state, revision
                FROM channel_evidence_outbox WHERE observation_id = ?
                """)) {
            statement.setString(1, observationId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    String existingTarget = result.getString("target_item_id");
                    if (!existingTarget.equals(targetItemId)) {
                        throw new StoreException(ErrorCode.ROUTE_CONFLICT,
                                "evidence outbox targetItemId 已确定，不得覆盖");
                    }
                    return;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO channel_evidence_outbox(
                  observation_id, target_item_id, state, revision, created_at, updated_at)
                VALUES (?, ?, 'PENDING', 1, ?, ?)
                """)) {
            statement.setString(1, observationId);
            statement.setString(2, targetItemId);
            statement.setString(3, now);
            statement.setString(4, now);
            statement.executeUpdate();
        }
    }

    private Observation updateEvidenceRouteReasonUnsafe(Observation current,
                                                         RouteReason reason,
                                                         String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE channel_observation
                SET route_reason = ?, route_revision = route_revision + 1,
                    route_updated_at = ?
                WHERE observation_id = ? AND route_state = 'EVIDENCE_ONLY'
                  AND route_revision = ?
                """)) {
            statement.setString(1, reason.name());
            statement.setString(2, now);
            statement.setString(3, current.observationId());
            statement.setLong(4, current.routeRevision());
            if (statement.executeUpdate() != 1) {
                throw new StoreException(ErrorCode.ROUTE_CONFLICT,
                        "evidence route reason 已被并发修改");
            }
        }
        appendRouteHistory(current.observationId(), current.requestKey(),
                RouteState.EVIDENCE_ONLY, RouteState.EVIDENCE_ONLY, null, now);
        return requireObservationByIdUnsafe(current.observationId());
    }

    private void insertEvidenceCandidateUnsafe(Observation source,
                                               Observation candidate,
                                               String candidateItemId,
                                               String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO channel_evidence_candidate(
                  source_observation_id, candidate_observation_id, candidate_item_id,
                  reason, thread_id, binding_epoch_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, source.observationId());
            statement.setString(2, candidate.observationId());
            statement.setString(3, candidateItemId == null ? "" : candidateItemId);
            statement.setString(4, RouteReason.SUPPRESSED_FALLBACK_UNVERIFIED.name());
            statement.setString(5, source.threadId());
            statement.setString(6, source.bindingEpochId());
            statement.setString(7, now);
            statement.executeUpdate();
        }
    }

    private Optional<EvidenceCandidateRecord> findEvidenceCandidateUnsafe(
            String sourceObservationId,
            String candidateObservationId,
            String candidateItemKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, source_observation_id, candidate_observation_id,
                       candidate_item_id, reason, thread_id, binding_epoch_id, created_at
                FROM channel_evidence_candidate
                WHERE source_observation_id = ? AND candidate_observation_id = ?
                  AND candidate_item_id = ?
                """)) {
            statement.setString(1, sourceObservationId);
            statement.setString(2, candidateObservationId);
            statement.setString(3, candidateItemKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapEvidenceCandidate(result))
                        : Optional.empty();
            }
        }
    }

    private List<EvidenceCandidateRecord> evidenceCandidatesForSourceUnsafe(
            String sourceObservationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, source_observation_id, candidate_observation_id,
                       candidate_item_id, reason, thread_id, binding_epoch_id, created_at
                FROM channel_evidence_candidate
                WHERE source_observation_id = ? ORDER BY id ASC
                """)) {
            statement.setString(1, sourceObservationId);
            List<EvidenceCandidateRecord> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(mapEvidenceCandidate(result));
                }
            }
            return List.copyOf(rows);
        }
    }

    private EvidenceCandidateRecord mapEvidenceCandidate(ResultSet result) throws SQLException {
        String item = result.getString("candidate_item_id");
        return new EvidenceCandidateRecord(
                result.getLong("id"),
                result.getString("source_observation_id"),
                result.getString("candidate_observation_id"),
                item == null || item.isEmpty() ? Optional.empty() : Optional.of(item),
                RouteReason.valueOf(result.getString("reason")),
                result.getString("thread_id"),
                result.getString("binding_epoch_id"),
                Instant.parse(result.getString("created_at")));
    }

    private List<CorrelationPair> findCorrelationPairsForObservationsUnsafe(
            String firstObservationId, String secondObservationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pair_key, observation_a_id, observation_b_id, state, revision,
                       created_at, updated_at
                FROM channel_correlation_pair
                WHERE observation_a_id IN (?, ?) OR observation_b_id IN (?, ?)
                """)) {
            statement.setString(1, firstObservationId);
            statement.setString(2, secondObservationId);
            statement.setString(3, firstObservationId);
            statement.setString(4, secondObservationId);
            List<CorrelationPair> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(mapCorrelationPair(result));
                }
            }
            return rows;
        }
    }

    private Optional<CorrelationPair> findCorrelationPairByObservationUnsafe(
            String observationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pair_key, observation_a_id, observation_b_id, state, revision,
                       created_at, updated_at
                FROM channel_correlation_pair
                WHERE observation_a_id = ? OR observation_b_id = ?
                """)) {
            statement.setString(1, observationId);
            statement.setString(2, observationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapCorrelationPair(result))
                        : Optional.empty();
            }
        }
    }

    private CorrelationPair requireCorrelationPairUnsafe(String pairKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pair_key, observation_a_id, observation_b_id, state, revision,
                       created_at, updated_at
                FROM channel_correlation_pair WHERE pair_key = ?
                """)) {
            statement.setString(1, pairKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new StoreException(ErrorCode.CORRELATION_PAIR_NOT_FOUND,
                            "correlation pair 不存在");
                }
                return mapCorrelationPair(result);
            }
        }
    }

    private CorrelationPair mapCorrelationPair(ResultSet result) throws SQLException {
        return new CorrelationPair(
                result.getString("pair_key"),
                result.getString("observation_a_id"),
                result.getString("observation_b_id"),
                CorrelationState.valueOf(result.getString("state")),
                result.getLong("revision"),
                Instant.parse(result.getString("created_at")),
                Instant.parse(result.getString("updated_at")));
    }

    private Observation requireObservationByIdUnsafe(String observationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT observation_id, thread_id, channel, account_id, raw_source,
                       channel_message_id, board_local_seq, sender_id, canonical_sender_id,
                       identity_status, identity_revision, binding_generation,
                       binding_epoch_id, ingest_intent_key, route_reason,
                       direction, message_at, observed_at, priority, time_reliability,
                       content_integrity, structured_mentions_trusted,
                       strict_time_tolerance_seconds, route_state, route_request_key,
                       route_item_id, route_revision,
                       route_updated_at, correlation_state, correlation_peer_observation_id,
                       correlation_revision, correlation_updated_at
                FROM channel_observation WHERE observation_id = ?
                """)) {
            statement.setString(1, observationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new StoreException(ErrorCode.OBSERVATION_NOT_FOUND,
                            "observation 不存在");
                }
                return mapObservation(result);
            }
        }
    }

    private void insertObservationUnsafe(ObservationDraft draft) throws SQLException {
        SourceBinding binding = findBindingUnsafe(draft.rawSource())
                .filter(SourceBinding::active).orElse(null);
        long generation = binding == null ? 0 : binding.generation();
        insertObservationUnsafe(draft, generation, null, draft.routeReason());
    }

    private void insertObservationUnsafe(ObservationDraft draft,
                                         long bindingGeneration,
                                         String ingestIntentKey,
                                         RouteReason routeReason) throws SQLException {
        if (bindingGeneration < 0) {
            throw invalid("bindingGeneration 不能为负");
        }
        Objects.requireNonNull(routeReason, "routeReason");
        String observationId = observationIdFor(draft.rawSource(), draft.channelMessageId());
        String requestKey = requestKeyFor(draft.rawSource(), draft.channelMessageId());
        String now = timestamp(clock.instant());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO channel_observation(
                  observation_id, thread_id, channel, account_id, raw_source,
                  channel_message_id, board_local_seq, sender_id, canonical_sender_id,
                  identity_status, identity_revision, binding_generation, binding_epoch_id,
                  ingest_intent_key, route_reason, direction, message_at,
                  observed_at, priority, time_reliability, content_integrity,
                  structured_mentions_trusted, strict_time_tolerance_seconds,
                  route_state, route_request_key, route_item_id, route_revision,
                  route_updated_at, correlation_state, correlation_peer_observation_id,
                  correlation_revision, correlation_updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, 'PENDING_ROUTE', ?, NULL, 1, ?, 'CHANNEL_ONLY', NULL, 1, ?)
                """)) {
            statement.setString(1, observationId);
            statement.setString(2, draft.threadId());
            statement.setString(3, draft.channel().wireName());
            statement.setString(4, draft.accountId());
            statement.setString(5, draft.rawSource());
            statement.setString(6, draft.channelMessageId());
            statement.setLong(7, draft.boardLocalSeq());
            statement.setString(8, draft.senderId());
            statement.setString(9, draft.canonicalSenderId().orElse(null));
            statement.setString(10, draft.identityStatus().name());
            statement.setLong(11, draft.identityRevision());
            statement.setLong(12, bindingGeneration);
            statement.setString(13, draft.bindingEpochId());
            statement.setString(14, ingestIntentKey);
            statement.setString(15, routeReason.name());
            statement.setString(16, draft.direction().name());
            statement.setString(17, timestamp(draft.messageAt()));
            statement.setString(18, timestamp(draft.observedAt()));
            statement.setInt(19, draft.priority());
            statement.setString(20, draft.timeReliability().name());
            statement.setString(21, draft.contentIntegrity().name());
            statement.setInt(22, draft.structuredMentionsTrusted() ? 1 : 0);
            statement.setInt(23, draft.strictTimeToleranceSeconds());
            statement.setString(24, requestKey);
            statement.setString(25, now);
            statement.setString(26, now);
            statement.executeUpdate();
        }
        appendRouteHistory(observationId, requestKey, null, RouteState.PENDING_ROUTE,
                null, now);
    }

    private void appendRouteHistory(String observationId,
                                    String requestKey,
                                    RouteState from,
                                    RouteState to,
                                    String itemId,
                                    String changedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO channel_route_history(
                  observation_id, request_key, from_state, to_state, item_id, changed_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, observationId);
            statement.setString(2, requestKey);
            statement.setString(3, from == null ? null : from.name());
            statement.setString(4, to.name());
            statement.setString(5, itemId);
            statement.setString(6, changedAt);
            statement.executeUpdate();
        }
    }

    private void appendBindingEvent(String rawSource,
                                    long generation,
                                    String threadId,
                                    Channel channel,
                                    String accountId,
                                    long startBoardSeq,
                                    Long endBoardSeq,
                                    BindingEventKind kind,
                                    String createdAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO channel_source_binding_event(
                  raw_source, generation, thread_id, channel, account_id,
                  start_board_seq, end_board_seq, kind, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, rawSource);
            statement.setLong(2, generation);
            statement.setString(3, threadId);
            statement.setString(4, channel.wireName());
            statement.setString(5, accountId);
            statement.setLong(6, startBoardSeq);
            if (endBoardSeq == null) {
                statement.setNull(7, java.sql.Types.INTEGER);
            } else {
                statement.setLong(7, endBoardSeq);
            }
            statement.setString(8, kind.name());
            statement.setString(9, createdAt);
            statement.executeUpdate();
        }
    }

    private Optional<Observation> findObservationUnsafe(String rawSource,
                                                         String channelMessageId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT observation_id, thread_id, channel, account_id, raw_source,
                       channel_message_id, board_local_seq, sender_id, canonical_sender_id,
                       identity_status, identity_revision, binding_generation,
                       binding_epoch_id, ingest_intent_key, route_reason,
                       direction, message_at, observed_at, priority, time_reliability,
                       content_integrity, structured_mentions_trusted,
                       strict_time_tolerance_seconds, route_state, route_request_key,
                       route_item_id, route_revision,
                       route_updated_at, correlation_state, correlation_peer_observation_id,
                       correlation_revision, correlation_updated_at
                FROM channel_observation WHERE raw_source = ? AND channel_message_id = ?
                """)) {
            statement.setString(1, rawSource);
            statement.setString(2, channelMessageId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapObservation(result)) : Optional.empty();
            }
        }
    }

    private Observation requireObservationUnsafe(String rawSource, String channelMessageId)
            throws SQLException {
        return findObservationUnsafe(rawSource, channelMessageId).orElseThrow(() ->
                new StoreException(ErrorCode.OBSERVATION_NOT_FOUND,
                        "observation 不存在"));
    }

    private Observation mapObservation(ResultSet result) throws SQLException {
        String itemId = result.getString("route_item_id");
        String peer = result.getString("correlation_peer_observation_id");
        return new Observation(
                result.getString("observation_id"),
                result.getString("thread_id"),
                Channel.fromWire(result.getString("channel")),
                result.getString("account_id"),
                result.getString("raw_source"),
                result.getString("channel_message_id"),
                result.getLong("board_local_seq"),
                result.getString("sender_id"),
                result.getString("canonical_sender_id") == null ? Optional.empty()
                        : Optional.of(result.getString("canonical_sender_id")),
                IdentityStatus.valueOf(result.getString("identity_status")),
                result.getLong("identity_revision"),
                result.getLong("binding_generation"),
                result.getString("binding_epoch_id"),
                result.getString("ingest_intent_key") == null ? Optional.empty()
                        : Optional.of(result.getString("ingest_intent_key")),
                RouteReason.valueOf(result.getString("route_reason")),
                Direction.valueOf(result.getString("direction")),
                Instant.parse(result.getString("message_at")),
                Instant.parse(result.getString("observed_at")),
                result.getInt("priority"),
                TimeReliability.valueOf(result.getString("time_reliability")),
                ContentIntegrity.valueOf(result.getString("content_integrity")),
                result.getInt("structured_mentions_trusted") != 0,
                result.getInt("strict_time_tolerance_seconds"),
                RouteState.valueOf(result.getString("route_state")),
                result.getString("route_request_key"),
                itemId == null ? Optional.empty() : Optional.of(itemId),
                result.getLong("route_revision"),
                Instant.parse(result.getString("route_updated_at")),
                CorrelationState.valueOf(result.getString("correlation_state")),
                peer == null ? Optional.empty() : Optional.of(peer),
                result.getLong("correlation_revision"),
                Instant.parse(result.getString("correlation_updated_at")));
    }

    private Optional<IngestIntent> findIngestIntentUnsafe(String intentKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, intent_key, observation_id, request_key, thread_id, channel, account_id,
                       raw_source, channel_message_id, sender_id, canonical_sender_id,
                       identity_status, direction, message_at,
                       observed_at, priority, time_reliability, content_integrity,
                       structured_mentions_trusted, binding_generation, gate_enabled,
                       binding_epoch_id, route_reason,
                       preferred_channel, preferred_channel_availability, selected_channel,
                       matching_mode, strict_time_tolerance_seconds,
                       identity_revision, route_decision, route_item_id,
                       state, board_local_seq, created_at, updated_at
                FROM channel_ingest_intent WHERE intent_key = ?
                """)) {
            statement.setString(1, intentKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapIngestIntent(result)) : Optional.empty();
            }
        }
    }

    private Optional<IngestIntent> findIngestIntentByMessageUnsafe(
            String rawSource, String channelMessageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, intent_key, observation_id, request_key, thread_id, channel, account_id,
                       raw_source, channel_message_id, sender_id, canonical_sender_id,
                       identity_status, direction, message_at,
                       observed_at, priority, time_reliability, content_integrity,
                       structured_mentions_trusted, binding_generation, gate_enabled,
                       binding_epoch_id, route_reason,
                       preferred_channel, preferred_channel_availability, selected_channel,
                       matching_mode, strict_time_tolerance_seconds,
                       identity_revision, route_decision, route_item_id,
                       state, board_local_seq, created_at, updated_at
                FROM channel_ingest_intent
                WHERE raw_source = ? AND channel_message_id = ?
                """)) {
            statement.setString(1, rawSource);
            statement.setString(2, channelMessageId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapIngestIntent(result)) : Optional.empty();
            }
        }
    }

    private IngestIntent requireIngestIntentUnsafe(String intentKey) throws SQLException {
        return findIngestIntentUnsafe(intentKey).orElseThrow(() ->
                new StoreException(ErrorCode.INGEST_INTENT_NOT_FOUND,
                        "ingest intent 不存在"));
    }

    private IngestIntent mapIngestIntent(ResultSet result) throws SQLException {
        String routeItem = result.getString("route_item_id");
        long boardSeq = result.getLong("board_local_seq");
        Optional<Long> boardLocalSeq = result.wasNull()
                ? Optional.empty() : Optional.of(boardSeq);
        return new IngestIntent(
                result.getLong("id"),
                result.getString("intent_key"),
                result.getString("observation_id"),
                result.getString("request_key"),
                result.getString("thread_id"),
                Channel.fromWire(result.getString("channel")),
                result.getString("account_id"),
                result.getString("raw_source"),
                result.getString("channel_message_id"),
                result.getString("sender_id"),
                result.getString("canonical_sender_id") == null ? Optional.empty()
                        : Optional.of(result.getString("canonical_sender_id")),
                IdentityStatus.valueOf(result.getString("identity_status")),
                Direction.valueOf(result.getString("direction")),
                Instant.parse(result.getString("message_at")),
                Instant.parse(result.getString("observed_at")),
                result.getInt("priority"),
                TimeReliability.valueOf(result.getString("time_reliability")),
                ContentIntegrity.valueOf(result.getString("content_integrity")),
                result.getInt("structured_mentions_trusted") != 0,
                result.getLong("binding_generation"),
                result.getInt("gate_enabled") != 0,
                result.getString("binding_epoch_id"),
                RouteReason.valueOf(result.getString("route_reason")),
                result.getString("preferred_channel") == null ? Optional.empty()
                        : Optional.of(Channel.fromWire(result.getString("preferred_channel"))),
                ChannelAvailability.valueOf(
                        result.getString("preferred_channel_availability")),
                result.getString("selected_channel") == null ? Optional.empty()
                        : Optional.of(Channel.fromWire(result.getString("selected_channel"))),
                MatchingMode.valueOf(result.getString("matching_mode")),
                result.getInt("strict_time_tolerance_seconds"),
                result.getLong("identity_revision"),
                RouteState.valueOf(result.getString("route_decision")),
                routeItem == null ? Optional.empty() : Optional.of(routeItem),
                IngestIntentState.valueOf(result.getString("state")),
                boardLocalSeq,
                Instant.parse(result.getString("created_at")),
                Instant.parse(result.getString("updated_at")));
    }

    private void verifySameIngestIntent(IngestIntent existing, IngestIntentDraft draft) {
        String requestedItem = normalizeOptionalIdentifier(draft.routeItemId(), "routeItemId");
        boolean same = existing.intentKey().equals(draft.intentKey())
                && existing.threadId().equals(draft.threadId())
                && existing.channel() == draft.channel()
                && existing.accountId().equals(draft.accountId())
                && existing.rawSource().equals(draft.rawSource())
                && existing.channelMessageId().equals(draft.channelMessageId())
                && existing.senderId().equals(draft.senderId())
                && existing.canonicalSenderId().equals(draft.canonicalSenderId())
                && existing.identityStatus() == draft.identityStatus()
                && existing.direction() == draft.direction()
                && existing.messageAt().equals(draft.messageAt())
                && existing.observedAt().equals(draft.observedAt())
                && existing.priority() == draft.priority()
                && existing.timeReliability() == draft.timeReliability()
                && existing.contentIntegrity() == draft.contentIntegrity()
                && existing.structuredMentionsTrusted()
                        == draft.structuredMentionsTrusted()
                && existing.bindingGeneration() == draft.bindingGeneration()
                && existing.gateEnabled() == draft.gateEnabled()
                && existing.bindingEpochId().equals(draft.bindingEpochId())
                && existing.routeReason() == draft.routeReason()
                && existing.preferredChannel().orElse(null) == draft.preferredChannel()
                && existing.preferredChannelAvailability()
                        == draft.preferredChannelAvailability()
                && existing.selectedChannel().orElse(null) == draft.selectedChannel()
                && existing.matchingMode() == draft.matchingMode()
                && existing.strictTimeToleranceSeconds()
                        == draft.strictTimeToleranceSeconds()
                && existing.identityRevision() == draft.identityRevision()
                && existing.routeDecision() == draft.routeDecision()
                && Objects.equals(existing.routeItemId().orElse(null), requestedItem);
        if (!same) {
            throw new StoreException(ErrorCode.INGEST_INTENT_CONFLICT,
                    "同一 intentKey 的已有 ingest intent 不可覆盖");
        }
    }

    private void validateIngestIntentDraft(IngestIntentDraft draft) {
        requireIdentifier(draft.intentKey(), "intentKey");
        validateThreadId(draft.threadId());
        Objects.requireNonNull(draft.channel(), "channel");
        requireIdentifier(draft.accountId(), "accountId");
        requireIdentifier(draft.rawSource(), "rawSource");
        requireIdentifier(draft.channelMessageId(), "channelMessageId");
        validateRawSource(draft.rawSource(), draft.channel(), draft.accountId());
        validateSenderId(draft.senderId());
        validateIdentitySnapshot(draft.canonicalSenderId(), draft.identityStatus(),
                draft.identityRevision());
        Objects.requireNonNull(draft.direction(), "direction");
        Objects.requireNonNull(draft.messageAt(), "messageAt");
        Objects.requireNonNull(draft.observedAt(), "observedAt");
        if (draft.priority() < MIN_PRIORITY || draft.priority() > MAX_PRIORITY) {
            throw invalid("priority 必须在 " + MIN_PRIORITY + ".." + MAX_PRIORITY + " 内");
        }
        Objects.requireNonNull(draft.timeReliability(), "timeReliability");
        Objects.requireNonNull(draft.contentIntegrity(), "contentIntegrity");
        if (draft.bindingGeneration() < 1) {
            throw invalid("bindingGeneration 必须为正");
        }
        validateEpoch(draft.bindingEpochId());
        Objects.requireNonNull(draft.preferredChannelAvailability(),
                "preferredChannelAvailability");
        Objects.requireNonNull(draft.matchingMode(), "matchingMode");
        if (draft.identityRevision() < 0) {
            throw invalid("identityRevision 不能为负");
        }
        if (draft.preferredChannelAvailability() == ChannelAvailability.AVAILABLE
                && draft.preferredChannel() == null) {
            throw invalid("preferredChannelAvailable=AVAILABLE 时必须指定 preferredChannel");
        }
        RouteState route = Objects.requireNonNull(draft.routeDecision(), "routeDecision");
        if (route == RouteState.DISPLAY_LINKED) {
            throw invalid("pre-board intent 不能直接声明 DISPLAY_LINKED");
        }
        String item = normalizeOptionalIdentifier(draft.routeItemId(), "routeItemId");
        if (route == RouteState.DISPLAY_PENDING) {
            if (item == null) {
                throw invalid("DISPLAY_PENDING intent 必须提供稳定 itemId");
            }
            if (draft.selectedChannel() == null || draft.selectedChannel() != draft.channel()) {
                throw invalid("DISPLAY_PENDING intent 的 selectedChannel 必须等于来源 channel");
            }
        } else if (route != RouteState.EVIDENCE_ONLY && item != null) {
            throw invalid("仅 DISPLAY_PENDING/EVIDENCE_ONLY intent 可以提供 itemId");
        }
        if (draft.gateEnabled() != (route != RouteState.GATE_OFF)) {
            throw invalid("gateEnabled 与 GATE_OFF intent 决策不一致");
        }
        RouteReason reason = Objects.requireNonNull(draft.routeReason(), "routeReason");
        validateRouteReason(route, reason, item);
    }

    private void validateRouteReason(RouteState route, RouteReason reason, String itemId) {
        boolean valid = switch (route) {
            case PENDING_ROUTE -> reason == RouteReason.PENDING;
            case DISPLAY_PENDING -> reason == RouteReason.PREFERRED_DISPLAY
                    || reason == RouteReason.FALLBACK_DISPLAY;
            case EVIDENCE_ONLY -> reason == RouteReason.NON_PREFERRED_EVIDENCE
                    || (reason == RouteReason.LATE_EVIDENCE_FROZEN_ITEM && itemId != null)
                    || reason == RouteReason.SUPPRESSED_FALLBACK_UNVERIFIED;
            case GATE_OFF -> reason == RouteReason.INJECTION_GATE_OFF;
            case DISPLAY_LINKED -> false;
        };
        if (!valid) {
            throw invalid("routeReason 与 routeDecision/itemId 不一致");
        }
    }

    private ObservationDraft draftFromIntent(IngestIntent intent, long boardLocalSeq) {
        return new ObservationDraft(
                intent.threadId(),
                intent.channel(),
                intent.accountId(),
                intent.rawSource(),
                intent.channelMessageId(),
                boardLocalSeq,
                intent.senderId(),
                intent.canonicalSenderId(),
                intent.identityStatus(),
                intent.identityRevision(),
                intent.direction(),
                intent.messageAt(),
                intent.observedAt(),
                intent.priority(),
                intent.timeReliability(),
                intent.contentIntegrity(),
                intent.structuredMentionsTrusted(),
                intent.strictTimeToleranceSeconds(),
                intent.bindingEpochId(),
                intent.routeReason());
    }

    private static boolean sameItem(Observation observation, IngestIntent intent) {
        return observation.itemId().equals(intent.routeItemId());
    }

    private Optional<SourceBinding> findBindingUnsafe(String rawSource) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT raw_source, generation, thread_id, channel, account_id, active,
                       start_board_seq, scan_cursor, created_at, updated_at
                FROM channel_source_scan_state WHERE raw_source = ?
                """)) {
            statement.setString(1, rawSource);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapBinding(result));
            }
        }
    }

    private SourceBinding requireActiveBindingUnsafe(String rawSource) throws SQLException {
        SourceBinding binding = findBindingUnsafe(rawSource).orElseThrow(() ->
                new StoreException(ErrorCode.SOURCE_NOT_BOUND,
                        "raw source 没有 binding 记录"));
        if (!binding.active()) {
            throw new StoreException(ErrorCode.SOURCE_NOT_BOUND,
                    "raw source binding 已解绑");
        }
        return binding;
    }

    private SourceBinding mapBinding(ResultSet result) throws SQLException {
        return new SourceBinding(
                result.getString("raw_source"),
                result.getLong("generation"),
                result.getString("thread_id"),
                Channel.fromWire(result.getString("channel")),
                result.getString("account_id"),
                result.getInt("active") != 0,
                result.getLong("start_board_seq"),
                result.getLong("scan_cursor"),
                Instant.parse(result.getString("created_at")),
                Instant.parse(result.getString("updated_at")));
    }

    private void verifySameObservation(Observation existing, ObservationDraft draft) {
        boolean same = existing.threadId().equals(draft.threadId())
                && existing.channel() == draft.channel()
                && existing.accountId().equals(draft.accountId())
                && existing.rawSource().equals(draft.rawSource())
                && existing.channelMessageId().equals(draft.channelMessageId())
                && existing.boardLocalSeq() == draft.boardLocalSeq()
                && existing.senderId().equals(draft.senderId())
                && existing.canonicalSenderId().equals(draft.canonicalSenderId())
                && existing.identityStatus() == draft.identityStatus()
                && existing.identityRevision() == draft.identityRevision()
                && existing.bindingEpochId().equals(draft.bindingEpochId())
                && existing.direction() == draft.direction()
                && existing.messageAt().equals(draft.messageAt())
                && existing.timeReliability() == draft.timeReliability()
                && existing.contentIntegrity() == draft.contentIntegrity()
                && existing.structuredMentionsTrusted()
                        == draft.structuredMentionsTrusted()
                && existing.strictTimeToleranceSeconds()
                        == draft.strictTimeToleranceSeconds();
        if (!same) {
            throw new StoreException(ErrorCode.OBSERVATION_CONFLICT,
                    "同一 (rawSource, channelMessageId) 的已有 observation 不可覆盖");
        }
    }

    private void verifyDraftMatchesBinding(SourceBinding binding, ObservationDraft draft) {
        if (!binding.threadId().equals(draft.threadId())
                || binding.channel() != draft.channel()
                || !binding.accountId().equals(draft.accountId())) {
            throw new StoreException(ErrorCode.BINDING_MISMATCH,
                    "observation thread/channel/account 与 active binding 不一致");
        }
    }

    private void validateDraftForBinding(ObservationDraft draft,
                                         String rawSource,
                                         SourceBinding binding) {
        if (draft == null) {
            throw invalid("scan draft 不能为空");
        }
        validateDraft(draft);
        if (!draft.rawSource().equals(rawSource)) {
            throw new StoreException(ErrorCode.BINDING_MISMATCH,
                    "scan batch observation 不属于该 raw source");
        }
        verifyDraftMatchesBinding(binding, draft);
        if (draft.boardLocalSeq() < binding.startBoardSeq()) {
            throw new StoreException(ErrorCode.BOARD_SEQ_BEFORE_BINDING_BOUNDARY,
                    "observation boardLocalSeq 早于 binding start boundary");
        }
    }

    private void validateIdentitySnapshot(Optional<String> canonicalSenderId,
                                          IdentityStatus identityStatus,
                                          long identityRevision) {
        Objects.requireNonNull(identityStatus, "identityStatus");
        if (identityRevision < 0) {
            throw invalid("identityRevision 不能为负");
        }
        String canonical = canonicalSenderId == null ? null : canonicalSenderId.orElse(null);
        if (canonical != null) {
            requireIdentifier(canonical, "canonicalSenderId");
        }
        if (identityStatus == IdentityStatus.MAPPED && canonical == null) {
            throw invalid("MAPPED identity 必须提供 canonicalSenderId");
        }
        if (identityStatus != IdentityStatus.MAPPED && canonical != null) {
            throw invalid("非 MAPPED identity 不得提供 canonicalSenderId");
        }
    }

    private void validateDraft(ObservationDraft draft) {
        validateThreadId(draft.threadId());
        Objects.requireNonNull(draft.channel(), "channel");
        requireIdentifier(draft.accountId(), "accountId");
        requireIdentifier(draft.rawSource(), "rawSource");
        requireIdentifier(draft.channelMessageId(), "channelMessageId");
        validateRawSource(draft.rawSource(), draft.channel(), draft.accountId());
        if (draft.boardLocalSeq() < 0) {
            throw invalid("boardLocalSeq 不能为负");
        }
        validateSenderId(draft.senderId());
        validateIdentitySnapshot(draft.canonicalSenderId(), draft.identityStatus(),
                draft.identityRevision());
        validateEpoch(draft.bindingEpochId());
        Objects.requireNonNull(draft.direction(), "direction");
        Objects.requireNonNull(draft.messageAt(), "messageAt");
        Objects.requireNonNull(draft.observedAt(), "observedAt");
        if (draft.priority() < MIN_PRIORITY || draft.priority() > MAX_PRIORITY) {
            throw invalid("priority 必须在 " + MIN_PRIORITY + ".." + MAX_PRIORITY + " 内");
        }
        Objects.requireNonNull(draft.timeReliability(), "timeReliability");
        Objects.requireNonNull(draft.contentIntegrity(), "contentIntegrity");
        Objects.requireNonNull(draft.routeReason(), "routeReason");
    }

    private void validateThreadId(String threadId) {
        if (threadId == null || !THREAD_ID.matcher(threadId).matches()) {
            throw new StoreException(ErrorCode.INVALID_THREAD_ID,
                    "threadId 只允许 [A-Za-z0-9._-]{1,128}");
        }
    }

    private void validateRawSource(String rawSource, Channel channel, String accountId) {
        requireIdentifier(rawSource, "rawSource");
        requireIdentifier(accountId, "accountId");
        String[] parts = rawSource.split("/", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank()
                || parts[2].isBlank()) {
            throw new StoreException(ErrorCode.INVALID_RAW_SOURCE,
                    "raw source 必须为 platform/account/target");
        }
        Channel parsed;
        switch (parts[0]) {
            case "qq_group", "qq_private", "qq_system" -> parsed = Channel.ONEBOT;
            case "qq_official_group", "qq_official_private", "qq_official_system" ->
                    parsed = Channel.QQ_OFFICIAL;
            default -> throw new StoreException(ErrorCode.INVALID_RAW_SOURCE,
                    "未知 raw source platform");
        }
        if (parsed != channel) {
            throw new StoreException(ErrorCode.INVALID_CHANNEL,
                    "channel 与 raw source 前缀不一致");
        }
        for (int i = 1; i < parts.length; i++) {
            if (containsControl(parts[i])) {
                throw new StoreException(ErrorCode.INVALID_RAW_SOURCE,
                        "raw source 不能包含控制字符");
            }
        }
        if (!parts[1].equals(accountId)) {
            throw new StoreException(ErrorCode.ACCOUNT_MISMATCH,
                    "accountId 与 raw source 中的账号不一致");
        }
    }

    private void requireIdentifier(String value, String field) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length())
                > MAX_ID_CODE_POINTS || containsControl(value)) {
            throw invalid(field + " 非法");
        }
    }

    private String normalizeOptionalIdentifier(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        requireIdentifier(value, field);
        return value;
    }

    private void validateRouteTransition(RouteState from, RouteState to) {
        boolean legal = switch (from) {
            case PENDING_ROUTE -> to == RouteState.DISPLAY_PENDING
                    || to == RouteState.EVIDENCE_ONLY
                    || to == RouteState.GATE_OFF;
            case DISPLAY_PENDING -> to == RouteState.DISPLAY_LINKED;
            case DISPLAY_LINKED, EVIDENCE_ONLY, GATE_OFF -> false;
        };
        if (!legal) {
            throw new StoreException(ErrorCode.ILLEGAL_ROUTE_TRANSITION,
                    "非法 route 状态转换: " + from + " -> " + to);
        }
    }

    private static boolean requiresItem(RouteState state) {
        return state == RouteState.DISPLAY_PENDING || state == RouteState.DISPLAY_LINKED;
    }

    private static boolean correlationNeedsPeer(CorrelationState state) {
        return state == CorrelationState.MATCHED
                || state == CorrelationState.UNVERIFIED_MATCH
                || state == CorrelationState.CONFLICT
                || state == CorrelationState.SUPPRESSED_UNVERIFIED;
    }

    private static EnumMap<RouteState, Long> emptyRouteCounts() {
        EnumMap<RouteState, Long> counts = new EnumMap<>(RouteState.class);
        for (RouteState state : RouteState.values()) {
            counts.put(state, 0L);
        }
        return counts;
    }

    private static EnumMap<CorrelationState, Long> emptyCorrelationCounts() {
        EnumMap<CorrelationState, Long> counts = new EnumMap<>(CorrelationState.class);
        for (CorrelationState state : CorrelationState.values()) {
            counts.put(state, 0L);
        }
        return counts;
    }

    private <T> T transaction(String operation, SqlWork<T> work) {
        ensureOpen();
        boolean began = false;
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                began = true;
            }
            T result = work.run();
            try (Statement statement = connection.createStatement()) {
                statement.execute("COMMIT");
            }
            began = false;
            return result;
        } catch (StoreException failure) {
            rollback(began, failure);
            throw failure;
        } catch (SQLException failure) {
            rollback(began, failure);
            throw storageFailure(operation, failure);
        } catch (RuntimeException failure) {
            rollback(began, failure);
            throw failure;
        }
    }

    private void rollback(boolean began, Throwable failure) {
        if (!began) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private StoreException storageFailure(String operation, SQLException failure) {
        return new StoreException(ErrorCode.STORAGE_ERROR, "SQLite " + operation + " 失败",
                failure);
    }

    private StoreException invalid(String message) {
        return new StoreException(ErrorCode.INVALID_ARGUMENT, message);
    }

    private void ensureOpen() {
        if (closed) {
            throw new StoreException(ErrorCode.STORE_CLOSED, "observation store 已关闭");
        }
    }

    private static String normalizeSenderId(String senderId) {
        return senderId == null ? "" : senderId;
    }

    private static String normalizeEpoch(String bindingEpochId) {
        return bindingEpochId == null ? "" : bindingEpochId;
    }

    private static void requireTimeTolerance(int seconds) {
        if (seconds < 0 || seconds > MAX_TIME_TOLERANCE_SECONDS) {
            throw new IllegalArgumentException(
                    "strictTimeToleranceSeconds 必须在 0.." + MAX_TIME_TOLERANCE_SECONDS + " 内");
        }
    }

    private static RouteReason defaultRouteReason(RouteState route, String itemId) {
        return switch (route) {
            case PENDING_ROUTE -> RouteReason.PENDING;
            case DISPLAY_PENDING -> RouteReason.PREFERRED_DISPLAY;
            case EVIDENCE_ONLY -> itemId == null || itemId.isBlank()
                    ? RouteReason.NON_PREFERRED_EVIDENCE
                    : RouteReason.LATE_EVIDENCE_FROZEN_ITEM;
            case GATE_OFF -> RouteReason.INJECTION_GATE_OFF;
            case DISPLAY_LINKED -> RouteReason.PREFERRED_DISPLAY;
        };
    }

    private void validateEpoch(String bindingEpochId) {
        String epoch = normalizeEpoch(bindingEpochId);
        if (epoch.codePointCount(0, epoch.length()) > MAX_ID_CODE_POINTS
                || containsControl(epoch)) {
            throw invalid("bindingEpochId 非法");
        }
    }

    private void validateSenderId(String senderId) {
        if (senderId == null || senderId.codePointCount(0, senderId.length())
                > MAX_ID_CODE_POINTS || containsControl(senderId)) {
            throw invalid("senderId 非法");
        }
    }

    private static void requireIdentityConsistency(Optional<String> canonicalSenderId,
                                                     IdentityStatus status) {
        if (status == IdentityStatus.MAPPED && canonicalSenderId.isEmpty()) {
            throw new IllegalArgumentException("MAPPED identity 必须提供 canonicalSenderId");
        }
        if (status != IdentityStatus.MAPPED && canonicalSenderId.isPresent()) {
            throw new IllegalArgumentException("非 MAPPED identity 不得提供 canonicalSenderId");
        }
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static String timestamp(Instant value) {
        return TIMESTAMP.format(Objects.requireNonNull(value, "instant"));
    }

    private static String correlationPairKey(String observationAId, String observationBId) {
        return "qp-" + sha256Hex("qq-channel-correlation-pair-v1\0"
                + observationAId + "\0" + observationBId);
    }

    private static String sha256Hex(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(value & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
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
            throw storageFailure("close", failure);
        }
    }
}
