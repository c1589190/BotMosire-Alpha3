package io.github.c1589190.bma3.qqofficial.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateful QQ official Gateway client for one bot account.
 *
 * <p>The class owns the protocol state machine (Hello, Identify/Resume, heartbeats, ACK timeout,
 * sequence/session bookkeeping, close-code policy and backoff) but none of the platform-specific
 * inputs: token acquisition, {@code /gateway/bot} URI acquisition, transport, event dispatch,
 * clock, scheduler, failure classification and backoff are all caller-supplied collaborators.</p>
 *
 * <p>One instance admits exactly one {@link #start()} call and one live transport. Duplicate start
 * is rejected. {@link #close()} is idempotent, waits for an in-flight dispatch to leave the event
 * sink, stops all timers and suppresses future token calls, connections and event delivery.</p>
 *
 * <p>Sequence bookkeeping distinguishes the latest <em>received</em> {@code s} (used by heartbeat)
 * from the latest <em>committed</em> {@code s} (used by Resume). A dispatch is committed only after
 * the event sink returns successfully. Sink failure closes the current transport and reconnects
 * from the last committed sequence so the gateway can replay the uncommitted event.</p>
 *
 * <p>Security: frame contents (which contain the access token in Identify/Resume) and message
 * bodies are never logged or exposed by status. Status contains only account id, state, committed
 * sequence, counters, close/error codes and terminal reason.</p>
 */
public final class OfficialQqGatewayClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OfficialQqGatewayClient.class);

    private final GatewayOptions options;
    private final GatewayTokenProvider tokenProvider;
    private final GatewayUriProvider uriProvider;
    private final OfficialQqGatewayTransport.Factory transportFactory;
    private final GatewayEventSink eventSink;
    private final GatewayStatusListener statusListener;
    private final GatewayClock clock;
    private final GatewayScheduler scheduler;
    private final GatewayBackoff backoff;
    private final GatewayFailureClassifier failureClassifier;
    private final boolean ownsScheduler;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<GatewayState> state = new AtomicReference<>(GatewayState.STOPPED);
    private final AtomicReference<Connection> current = new AtomicReference<>();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final Object lifecycleLock = new Object();
    private final Object heartbeatLock = new Object();
    private final Object eventDeliveryLock = new Object();

    private volatile Instant updatedAt = Instant.EPOCH;
    private volatile Instant lastConnectedAt;
    private volatile Instant lastEventAt;
    /** Latest sequence observed on a Dispatch frame; safe for heartbeat acknowledgement. */
    private volatile Long receivedSequence;
    /** Latest sequence whose event sink call returned successfully; safe for Resume. */
    private volatile Long committedSequence;
    private volatile String sessionId;
    private volatile Integer lastCloseCode;
    private volatile String lastErrorCode = "";
    private volatile String lastErrorType = "";
    private volatile String terminalReason = "";
    private volatile boolean terminal;
    private GatewayScheduler.ScheduledTask heartbeatTask;
    private GatewayScheduler.ScheduledTask heartbeatAckTask;
    private GatewayScheduler.ScheduledTask reconnectTask;
    private Duration heartbeatInterval;
    private boolean heartbeatAckPending;

    private OfficialQqGatewayClient(Builder builder) {
        this.options = builder.options;
        this.tokenProvider = builder.tokenProvider;
        this.uriProvider = builder.uriProvider;
        this.transportFactory = builder.transportFactory;
        this.eventSink = builder.eventSink;
        this.statusListener = builder.statusListener;
        this.clock = builder.clock;
        this.backoff = builder.backoff;
        this.failureClassifier = builder.failureClassifier;
        this.ownsScheduler = builder.scheduler == null;
        this.scheduler = builder.scheduler == null
                ? GatewayScheduler.daemonScheduler("qq-official-gateway-" + options.accountId())
                : builder.scheduler;
        this.updatedAt = clock.now();
    }

    public static Builder builder(GatewayOptions options, GatewayTokenProvider tokenProvider,
                                  GatewayUriProvider uriProvider) {
        return new Builder(options, tokenProvider, uriProvider);
    }

    /** Begins the first connection. A second call on the same instance is rejected. */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("gateway client is closed");
        }
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("gateway client already started; one account allows one active connection");
        }
        connectAttempt();
    }

    /** Credential-free snapshot for status tools and watchdog probes. */
    public GatewayStatus status() {
        GatewayState stateValue = state.get();
        Connection connection = current.get();
        boolean connected = stateValue == GatewayState.READY
                && connection != null && !connection.terminated.get();
        Long committed = committedSequence;
        return new GatewayStatus(
                options.accountId(),
                stateValue,
                connected,
                updatedAt,
                lastConnectedAt,
                lastEventAt,
                committed,
                sessionId != null && committed != null,
                consecutiveFailures.get(),
                lastCloseCode,
                lastErrorCode,
                lastErrorType,
                terminalReason);
    }

    public GatewayState state() {
        return state.get();
    }

    public boolean connected() {
        return status().connected();
    }

    /** Stops timers, abandons the current connection and suppresses all future reconnects. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (eventDeliveryLock) {
            stopHeartbeat();
            GatewayScheduler.ScheduledTask reconnect;
            synchronized (lifecycleLock) {
                reconnect = reconnectTask;
                reconnectTask = null;
            }
            if (reconnect != null) {
                reconnect.cancel();
            }
            Connection connection = current.getAndSet(null);
            if (connection != null) {
                connection.terminate();
                connection.closeTransport();
            }
        }
        transition(GatewayState.CLOSED);
        if (ownsScheduler) {
            scheduler.close();
        }
    }

    private void connectAttempt() {
        if (closed.get() || terminal) {
            return;
        }
        reconnectScheduled.set(false);
        Connection connection = new Connection();
        if (!current.compareAndSet(null, connection)) {
            // Defensive: a duplicate reconnect timer must not create a second live socket.
            connection.terminate();
            return;
        }
        transition(GatewayState.CONNECTING);
        if (closed.get()) {
            abortConnection(connection);
            return;
        }

        URI uri;
        try {
            uri = uriProvider.gatewayUri();
            if (uri == null) {
                throw new GatewayPermanentException("GATEWAY_URI_MISSING");
            }
        } catch (Exception failure) {
            handleProviderFailure(connection, failure);
            return;
        }
        if (closed.get() || terminal) {
            abortConnection(connection);
            return;
        }

        try {
            OfficialQqGatewayTransport transport = transportFactory.open(uri, connection);
            if (transport == null) {
                throw new GatewayPermanentException("TRANSPORT_FACTORY_NULL");
            }
            connection.attach(transport);
            if (closed.get() || terminal) {
                abortConnection(connection);
            }
        } catch (Exception failure) {
            handleProviderFailure(connection, failure);
        }
    }

    private void abortConnection(Connection connection) {
        connection.terminate();
        current.compareAndSet(connection, null);
        connection.closeTransport();
    }

    private void onOpen(Connection connection) {
        if (!isCurrent(connection)) {
            return;
        }
        // Hello is the next protocol milestone; no state transition is needed yet.
    }

    private void onText(Connection connection, String text) {
        if (!isCurrent(connection)) {
            return;
        }
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (Exception failure) {
            transportEnded(connection, null, "MALFORMED_FRAME", errorType(failure));
            return;
        }
        if (root == null || !root.isObject()) {
            transportEnded(connection, null, "MALFORMED_FRAME", "NonObjectFrame");
            return;
        }
        int op = root.path("op").asInt(Integer.MIN_VALUE);
        switch (op) {
            case 10 -> handleHello(connection, root);
            case 11 -> acknowledgeHeartbeat();
            case 7 -> transportEnded(connection, null, "RECONNECT_OPCODE", "");
            case 9 -> handleInvalidSession(connection, root);
            case 0 -> handleDispatch(connection, root);
            default -> transportEnded(connection, null, "UNEXPECTED_OPCODE_" + op, "");
        }
    }

    private void handleHello(Connection connection, JsonNode root) {
        if (!isCurrent(connection)) {
            return;
        }
        long intervalMillis = root.path("d").path("heartbeat_interval").asLong(-1L);
        if (intervalMillis <= 0L) {
            transportEnded(connection, null, "INVALID_HELLO", "");
            return;
        }
        transition(GatewayState.HELLO);
        startHeartbeat(Duration.ofMillis(intervalMillis));
        sendAuthentication(connection);
    }

    private void sendAuthentication(Connection connection) {
        if (!isCurrent(connection)) {
            return;
        }
        String accessToken;
        try {
            accessToken = tokenProvider.accessToken();
        } catch (Exception failure) {
            handleProviderFailure(connection, failure);
            return;
        }
        if (!isCurrent(connection)) {
            return;
        }
        if (accessToken == null || accessToken.isBlank()) {
            handleProviderFailure(connection, new GatewayPermanentException("TOKEN_BLANK"));
            return;
        }
        String authValue = accessToken.trim();
        if (!authValue.regionMatches(true, 0, "QQBot ", 0, 6)) {
            authValue = "QQBot " + authValue;
        }

        String savedSession = sessionId;
        Long savedSequence = committedSequence;
        ObjectNode frame = mapper.createObjectNode();
        if (savedSession != null && savedSequence != null) {
            transition(GatewayState.RESUME);
            frame.put("op", 6);
            ObjectNode data = frame.putObject("d");
            data.put("token", authValue);
            data.put("session_id", savedSession);
            data.put("seq", savedSequence);
        } else {
            // A previous session without a committed sequence cannot be resumed safely. Start a
            // fresh session rather than pretending a partially delivered session is resumable.
            if (savedSession != null) {
                clearSession();
            }
            transition(GatewayState.IDENTIFYING);
            frame.put("op", 2);
            ObjectNode data = frame.putObject("d");
            data.put("token", authValue);
            data.put("intents", options.intents());
            var shard = data.putArray("shard");
            shard.add(options.shardIndex());
            shard.add(options.shardCount());
            ObjectNode properties = data.putObject("properties");
            properties.put("$os", options.os());
            properties.put("$browser", options.browser());
            properties.put("$device", options.device());
        }
        send(connection, frame.toString(), "AUTH_SEND_FAILED");
    }

    private void handleDispatch(Connection connection, JsonNode root) {
        if (!isCurrent(connection)) {
            return;
        }
        JsonNode sequenceNode = root.get("s");
        Long incomingSequence = sequenceNode != null && sequenceNode.isIntegralNumber()
                ? sequenceNode.asLong()
                : null;
        String eventType = root.path("t").asText("");
        String eventId = root.path("id").asText("");
        JsonNode data = root.get("d");
        Instant receivedAt = clock.now();

        synchronized (eventDeliveryLock) {
            if (!isCurrent(connection)) {
                return;
            }
            if (incomingSequence != null) {
                receivedSequence = incomingSequence;
            }
            lastEventAt = receivedAt;

            boolean ready = "READY".equals(eventType);
            boolean resumed = "RESUMED".equals(eventType);
            if (ready) {
                String readySession = data == null ? "" : data.path("session_id").asText("");
                if (readySession.isBlank()) {
                    transportEnded(connection, null, "READY_WITHOUT_SESSION", "");
                    return;
                }
                // READY starts a new session. Old committed sequence state is not valid for this
                // session; it becomes committed only after the READY event sink succeeds.
                sessionId = readySession;
                committedSequence = null;
            }

            long sequence = incomingSequence != null
                    ? incomingSequence
                    : (receivedSequence == null ? 0L : receivedSequence);
            GatewayDispatch dispatch = new GatewayDispatch(
                    eventId, eventType, sequence, data, receivedAt);
            try {
                eventSink.onDispatch(dispatch);
            } catch (RuntimeException failure) {
                // Do not advance committedSequence and do not keep using this connection: the
                // event was received but not consumed, so it must be replayed after Resume.
                lastErrorCode = "EVENT_SINK_FAILED";
                lastErrorType = errorType(failure);
                LOG.warn("event=qq_official_gateway_event_sink_failed accountId={} type={}",
                        options.accountId(), lastErrorType);
                transportEnded(connection, null, "EVENT_SINK_FAILED", lastErrorType);
                return;
            }

            if (incomingSequence != null) {
                committedSequence = incomingSequence;
            }
            if (ready || resumed) {
                consecutiveFailures.set(0);
                lastConnectedAt = receivedAt;
                transition(GatewayState.READY);
            }
        }
    }

    private void handleInvalidSession(Connection connection, JsonNode root) {
        boolean resumable = root.path("d").asBoolean(false);
        if (!resumable) {
            clearSession();
        }
        transportEnded(connection, null,
                resumable ? "INVALID_SESSION_RESUMABLE" : "INVALID_SESSION_IDENTIFY", "");
    }

    private void startHeartbeat(Duration interval) {
        GatewayScheduler.ScheduledTask previous;
        synchronized (heartbeatLock) {
            previous = heartbeatTask;
            heartbeatTask = null;
            heartbeatAckPending = false;
            cancelLocked(heartbeatAckTask);
            heartbeatAckTask = null;
            heartbeatInterval = interval;
        }
        cancel(previous);
        scheduleNextHeartbeat(interval);
    }

    private void scheduleNextHeartbeat(Duration delay) {
        GatewayScheduler.ScheduledTask task;
        try {
            task = scheduler.schedule(delay, this::heartbeatTick);
        } catch (RuntimeException failure) {
            Connection connection = current.get();
            if (connection != null) {
                transportEnded(connection, null, "SCHEDULER_UNAVAILABLE", errorType(failure));
            }
            return;
        }
        synchronized (heartbeatLock) {
            if (closed.get() || heartbeatInterval == null) {
                task.cancel();
            } else {
                heartbeatTask = task;
            }
        }
    }

    private void heartbeatTick() {
        Connection connection = current.get();
        if (connection == null || connection.terminated.get() || closed.get()) {
            return;
        }

        String heartbeatJson;
        synchronized (heartbeatLock) {
            heartbeatTask = null;
            if (heartbeatInterval == null || closed.get()) {
                return;
            }
            if (heartbeatAckPending) {
                // The dedicated ACK timer normally fires first; this guards a scheduler that
                // invokes the next heartbeat exactly at the timeout boundary.
                heartbeatJson = null;
            } else {
                ObjectNode heartbeat = mapper.createObjectNode();
                heartbeat.put("op", 1);
                Long sequence = receivedSequence;
                if (sequence == null) {
                    heartbeat.putNull("d");
                } else {
                    heartbeat.put("d", sequence);
                }
                heartbeatJson = heartbeat.toString();
                heartbeatAckPending = true;
                Duration ackTimeout = options.heartbeatAckTimeout() == null
                        ? heartbeatInterval
                        : options.heartbeatAckTimeout();
                try {
                    heartbeatAckTask = scheduler.schedule(ackTimeout,
                            () -> heartbeatAckTimedOut(connection));
                } catch (RuntimeException failure) {
                    heartbeatJson = null;
                    heartbeatAckPending = false;
                }
                scheduleNextHeartbeat(heartbeatInterval);
            }
        }

        if (heartbeatJson == null) {
            if (heartbeatAckPending) {
                transportEnded(connection, null, "HEARTBEAT_ACK_TIMEOUT", "");
            } else {
                transportEnded(connection, null, "SCHEDULER_UNAVAILABLE", "");
            }
            return;
        }
        send(connection, heartbeatJson, "HEARTBEAT_SEND_FAILED");
    }

    private void heartbeatAckTimedOut(Connection connection) {
        if (!isCurrent(connection)) {
            return;
        }
        boolean pending;
        synchronized (heartbeatLock) {
            heartbeatAckTask = null;
            pending = heartbeatAckPending;
        }
        if (pending) {
            transportEnded(connection, null, "HEARTBEAT_ACK_TIMEOUT", "");
        }
    }

    private void acknowledgeHeartbeat() {
        synchronized (heartbeatLock) {
            heartbeatAckPending = false;
            cancelLocked(heartbeatAckTask);
            heartbeatAckTask = null;
        }
    }

    private void stopHeartbeat() {
        GatewayScheduler.ScheduledTask heartbeat;
        GatewayScheduler.ScheduledTask ack;
        synchronized (heartbeatLock) {
            heartbeat = heartbeatTask;
            ack = heartbeatAckTask;
            heartbeatTask = null;
            heartbeatAckTask = null;
            heartbeatInterval = null;
            heartbeatAckPending = false;
        }
        cancel(heartbeat);
        cancel(ack);
    }

    private void send(Connection connection, String text, String failureCode) {
        if (!isCurrent(connection)) {
            return;
        }
        OfficialQqGatewayTransport transport = connection.transport;
        if (transport == null) {
            // A transport factory may synchronously deliver Hello from open(). Queue the resulting
            // Identify/Resume frame until the transport reference is attached.
            connection.pendingSends.add(new PendingSend(text, failureCode));
            return;
        }
        sendNow(connection, transport, text, failureCode);
    }

    private void sendNow(Connection connection, OfficialQqGatewayTransport transport,
                         String text, String failureCode) {
        if (!isCurrent(connection) || connection.transport != transport) {
            return;
        }
        CompletionStage<Void> sent;
        try {
            sent = transport.sendText(text);
        } catch (RuntimeException failure) {
            handleProviderFailure(connection, failure);
            return;
        }
        if (sent == null) {
            transportEnded(connection, null, failureCode, "NullCompletionStage");
            return;
        }
        sent.whenComplete((ignored, failure) -> {
            if (failure != null) {
                transportEnded(connection, null, failureCode, errorType(failure));
            }
        });
    }

    private void handleProviderFailure(Connection connection, Throwable failure) {
        GatewayFailureClassifier.Classification classification = classify(failure);
        if (classification.permanent()) {
            permanentFailure(connection, classification.code(), errorType(failure));
        } else {
            transportEnded(connection, null, classification.code(), errorType(failure));
        }
    }

    private void permanentFailure(Connection connection, String code, String errorType) {
        if (!connection.terminate()) {
            return;
        }
        if (!current.compareAndSet(connection, null)) {
            connection.closeTransport();
            return;
        }
        connection.closeTransport();
        stopHeartbeat();
        if (closed.get()) {
            return;
        }
        lastCloseCode = null;
        lastErrorCode = code == null ? "PERMANENT_FAILURE" : code;
        lastErrorType = errorType == null ? "" : errorType;
        terminal = true;
        terminalReason = lastErrorCode;
        LOG.warn("event=qq_official_gateway_terminal accountId={} code={} terminal={}",
                options.accountId(), lastErrorCode, terminalReason);
        transition(GatewayState.DOWN);
    }

    private void transportEnded(Connection connection, Integer closeCode, String errorCode,
                                String errorType) {
        if (!connection.terminate()) {
            return;
        }
        if (!current.compareAndSet(connection, null)) {
            connection.closeTransport();
            return;
        }
        connection.closeTransport();
        stopHeartbeat();
        if (closed.get()) {
            return;
        }

        lastCloseCode = closeCode;
        GatewayCloseCode.Classification classification = closeCode == null
                ? null
                : GatewayCloseCode.classify(closeCode);
        lastErrorCode = errorCode == null || errorCode.isBlank()
                ? (classification == null ? "" : classification.errorCode())
                : errorCode;
        lastErrorType = errorType == null ? "" : errorType;

        if (classification != null && classification.terminal()) {
            terminal = true;
            terminalReason = classification.terminalReason();
            LOG.warn("event=qq_official_gateway_terminal accountId={} code={} terminal={}",
                    options.accountId(), closeCode, terminalReason);
            transition(GatewayState.DOWN);
            return;
        }
        if (classification != null && classification.clearSession()) {
            clearSession();
        }
        int attempt = consecutiveFailures.incrementAndGet();
        transition(GatewayState.RECONNECTING);
        scheduleReconnect(attempt);
    }

    private GatewayFailureClassifier.Classification classify(Throwable failure) {
        try {
            GatewayFailureClassifier.Classification classification =
                    failureClassifier.classify(failure);
            return classification == null
                    ? new GatewayFailureClassifier.Classification(
                            GatewayFailureClassifier.Disposition.RETRYABLE, "CLASSIFIER_EMPTY")
                    : classification;
        } catch (RuntimeException classifierFailure) {
            return new GatewayFailureClassifier.Classification(
                    GatewayFailureClassifier.Disposition.RETRYABLE, "CLASSIFIER_FAILED");
        }
    }

    private void scheduleReconnect(int attempt) {
        if (closed.get() || terminal || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        Duration delay;
        try {
            delay = backoff.delayForAttempt(attempt);
        } catch (RuntimeException failure) {
            delay = Duration.ofSeconds(1);
        }
        if (delay == null || delay.isNegative()) {
            delay = Duration.ZERO;
        }
        GatewayScheduler.ScheduledTask task;
        try {
            task = scheduler.schedule(delay, () -> {
                synchronized (lifecycleLock) {
                    reconnectTask = null;
                }
                connectAttempt();
            });
        } catch (RuntimeException failure) {
            terminal = true;
            terminalReason = "SCHEDULER_UNAVAILABLE";
            lastErrorType = errorType(failure);
            transition(GatewayState.DOWN);
            return;
        }
        synchronized (lifecycleLock) {
            if (closed.get()) {
                task.cancel();
                return;
            }
            reconnectTask = task;
        }
    }

    private void clearSession() {
        sessionId = null;
        receivedSequence = null;
        committedSequence = null;
    }

    private boolean isCurrent(Connection connection) {
        return connection != null && !closed.get()
                && current.get() == connection && !connection.terminated.get();
    }

    private void transition(GatewayState next) {
        synchronized (lifecycleLock) {
            if (closed.get() && next != GatewayState.CLOSED) {
                return;
            }
            state.set(next);
            updatedAt = clock.now();
        }
        GatewayStatus snapshot = status();
        LOG.info("event=qq_official_gateway_state accountId={} state={} connected={} " +
                        "reconnects={} closeCode={} errorCode={}",
                snapshot.accountId(), snapshot.state(), snapshot.connected(),
                snapshot.reconnectAttempts(), snapshot.lastCloseCode(),
                snapshot.lastErrorCode());
        try {
            statusListener.onStatus(snapshot);
        } catch (RuntimeException failure) {
            LOG.warn("event=qq_official_gateway_status_sink_failed accountId={} type={}",
                    options.accountId(), errorType(failure));
        }
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "";
        }
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String simple = current.getClass().getSimpleName();
        return simple.isBlank() ? current.getClass().getName() : simple;
    }

    private static void cancel(GatewayScheduler.ScheduledTask task) {
        if (task != null) {
            try {
                task.cancel();
            } catch (RuntimeException ignored) {
                // Cancellation is best effort.
            }
        }
    }

    private static void cancelLocked(GatewayScheduler.ScheduledTask task) {
        cancel(task);
    }

    private record PendingSend(String text, String failureCode) {
    }

    private final class Connection implements OfficialQqGatewayTransport.Listener {
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final ConcurrentLinkedQueue<PendingSend> pendingSends = new ConcurrentLinkedQueue<>();
        private volatile OfficialQqGatewayTransport transport;

        private void attach(OfficialQqGatewayTransport value) {
            this.transport = value;
            if (terminated.get() || closed.get()) {
                pendingSends.clear();
                closeTransport();
                return;
            }
            PendingSend pending;
            while (!terminated.get() && (pending = pendingSends.poll()) != null) {
                sendNow(this, value, pending.text(), pending.failureCode());
            }
        }

        private boolean terminate() {
            return terminated.compareAndSet(false, true);
        }

        private void closeTransport() {
            OfficialQqGatewayTransport value = transport;
            transport = null;
            if (value != null) {
                try {
                    value.close();
                } catch (RuntimeException ignored) {
                    // Close is best effort; state has already moved on.
                }
            }
        }

        @Override
        public void onOpen() {
            OfficialQqGatewayClient.this.onOpen(this);
        }

        @Override
        public void onText(String text) {
            OfficialQqGatewayClient.this.onText(this, text);
        }

        @Override
        public void onClose(int statusCode, String reason) {
            transportEnded(this, statusCode, "", "");
        }

        @Override
        public void onError(Throwable failure) {
            handleProviderFailure(this, failure);
        }
    }

    /** Builder with production defaults; all collaborators remain injectable for tests/app. */
    public static final class Builder {
        private final GatewayOptions options;
        private final GatewayTokenProvider tokenProvider;
        private final GatewayUriProvider uriProvider;
        private OfficialQqGatewayTransport.Factory transportFactory =
                JdkWebSocketGatewayTransport.factory();
        private GatewayEventSink eventSink = GatewayEventSink.noop();
        private GatewayStatusListener statusListener = GatewayStatusListener.noop();
        private GatewayClock clock = GatewayClock.system();
        private GatewayScheduler scheduler;
        private GatewayBackoff backoff = ExponentialJitterBackoff.defaults();
        private GatewayFailureClassifier failureClassifier = GatewayFailureClassifier.defaults();

        private Builder(GatewayOptions options, GatewayTokenProvider tokenProvider,
                        GatewayUriProvider uriProvider) {
            this.options = Objects.requireNonNull(options, "options");
            this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
            this.uriProvider = Objects.requireNonNull(uriProvider, "uriProvider");
        }

        public Builder transportFactory(OfficialQqGatewayTransport.Factory value) {
            this.transportFactory = Objects.requireNonNull(value, "transportFactory");
            return this;
        }

        public Builder eventSink(GatewayEventSink value) {
            this.eventSink = Objects.requireNonNull(value, "eventSink");
            return this;
        }

        public Builder statusListener(GatewayStatusListener value) {
            this.statusListener = Objects.requireNonNull(value, "statusListener");
            return this;
        }

        public Builder clock(GatewayClock value) {
            this.clock = Objects.requireNonNull(value, "clock");
            return this;
        }

        /** Injected scheduler is owned by the caller; the builder-created default is owned here. */
        public Builder scheduler(GatewayScheduler value) {
            this.scheduler = Objects.requireNonNull(value, "scheduler");
            return this;
        }

        public Builder backoff(GatewayBackoff value) {
            this.backoff = Objects.requireNonNull(value, "backoff");
            return this;
        }

        /**
         * Overrides map of external credential/HTTP/config failures to retryable/permanent.
         * The default already understands {@link GatewayPermanentException} and Phase-2
         * {@code OfficialQqApiException} categories.
         */
        public Builder failureClassifier(GatewayFailureClassifier value) {
            this.failureClassifier = Objects.requireNonNull(value, "failureClassifier");
            return this;
        }

        public OfficialQqGatewayClient build() {
            return new OfficialQqGatewayClient(this);
        }
    }
}
