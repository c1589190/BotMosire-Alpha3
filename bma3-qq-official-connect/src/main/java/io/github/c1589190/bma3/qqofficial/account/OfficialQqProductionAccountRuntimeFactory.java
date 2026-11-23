package io.github.c1589190.bma3.qqofficial.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqApiClient;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqBaseUri;
import io.github.c1589190.bma3.qqofficial.auth.OfficialQqTokenManager;
import io.github.c1589190.bma3.qqofficial.event.OfficialQqEventDecoder;
import io.github.c1589190.bma3.qqofficial.gateway.ExponentialJitterBackoff;
import io.github.c1589190.bma3.qqofficial.gateway.GatewayBackoff;
import io.github.c1589190.bma3.qqofficial.gateway.GatewayOptions;
import io.github.c1589190.bma3.qqofficial.gateway.GatewayPermanentException;
import io.github.c1589190.bma3.qqofficial.gateway.GatewayState;
import io.github.c1589190.bma3.qqofficial.gateway.GatewayStatus;
import io.github.c1589190.bma3.qqofficial.gateway.JdkWebSocketGatewayTransport;
import io.github.c1589190.bma3.qqofficial.gateway.OfficialQqGatewayClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Production composition for one official account runtime.
 *
 * <p>This factory hard-wires the official OpenAPI base and validates both the base URI handed over
 * by the registry and the gateway URL returned by {@code GET /gateway/bot}. It is deliberately not
 * parameterised with a caller-supplied base URI: tests inject a fake
 * {@link OfficialQqAccountClientFactory} instead of weakening production endpoint policy.</p>
 *
 * <p>Each invocation builds a fresh token manager, API client, decoder and Gateway client. No
 * identity-bearing collaborator is shared between accounts. Construction performs no network I/O;
 * {@link OfficialQqAccountClient#start()} begins the gateway connection.</p>
 */
public final class OfficialQqProductionAccountRuntimeFactory
        implements OfficialQqAccountClientFactory {

    private static final Duration HEARTBEAT_ACK_TIMEOUT = Duration.ofSeconds(30);

    private final Function<Duration, HttpClient> httpClientFactory;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OfficialQqProductionAccountRuntimeFactory() {
        this(timeout -> HttpClient.newBuilder().connectTimeout(timeout).build(),
                new ObjectMapper(), Clock.systemUTC());
    }

    /** Injectable transport/clock for higher-level tests; endpoint policy remains fixed. */
    public OfficialQqProductionAccountRuntimeFactory(
            HttpClient httpClient, ObjectMapper objectMapper, Clock clock) {
        this(timeout -> Objects.requireNonNull(httpClient, "httpClient"),
                objectMapper, clock);
    }

    /** Package-visible seam proving runtime options reach the HTTP/WebSocket transport setup. */
    OfficialQqProductionAccountRuntimeFactory(
            Function<Duration, HttpClient> httpClientFactory,
            ObjectMapper objectMapper,
            Clock clock) {
        this.httpClientFactory = Objects.requireNonNull(httpClientFactory, "httpClientFactory");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public OfficialQqAccountClient create(OfficialQqAccountClientContext context) {
        Objects.requireNonNull(context, "context");
        if (!OfficialQqAccountEndpoints.isOfficialApiBaseUri(context.apiBaseUri())) {
            throw new IllegalArgumentException(
                    "生产官方账号 runtime 只允许固定官方 API base URI");
        }
        OfficialQqBaseUri.requireProduction(context.apiBaseUri());
        String accountId = context.config().accountId();
        OfficialQqAccountRuntimeOptions runtimeOptions = context.config().runtimeOptions();
        HttpClient httpClient = httpClientFactory.apply(runtimeOptions.httpConnectTimeout());
        if (httpClient == null) {
            throw new IllegalStateException("httpClientFactory returned null");
        }
        OfficialQqTokenManager tokenManager = OfficialQqTokenManager.production(
                accountId,
                context.credential(),
                httpClient,
                objectMapper,
                clock,
                runtimeOptions.httpRequestTimeout());
        OfficialQqApiClient apiClient = OfficialQqApiClient.production(
                accountId,
                tokenManager,
                httpClient,
                objectMapper,
                runtimeOptions.httpRequestTimeout());
        OfficialQqEventDecoder decoder = new OfficialQqEventDecoder(clock);
        GatewayOptions options = GatewayOptions.defaults(accountId)
                .withShard(context.config().shardIndex(), context.config().shardCount())
                .withHeartbeatAckTimeout(HEARTBEAT_ACK_TIMEOUT);
        OfficialQqGatewayClient gateway = OfficialQqGatewayClient.builder(
                        options,
                        tokenManager::accessToken,
                        () -> gatewayUri(apiClient))
                .eventSink(decoder.gatewayEventSink(context.dispatchSink()::accept))
                .statusListener(status -> context.statusSink().accept(toRuntimeStatus(status)))
                .clock(() -> clock.instant())
                .transportFactory(JdkWebSocketGatewayTransport.factory(
                        runtimeOptions.httpConnectTimeout()))
                .backoff(backoffFor(runtimeOptions))
                .build();
        return new Runtime(accountId, gateway, tokenManager, apiClient);
    }

    /** Deterministic when jitter is disabled, randomized only when the config requests it. */
    static GatewayBackoff backoffFor(OfficialQqAccountRuntimeOptions options) {
        Objects.requireNonNull(options, "options");
        return options.reconnectJitter()
                ? new ExponentialJitterBackoff(
                        options.reconnectInitial(), options.reconnectMax())
                : new ExponentialJitterBackoff(
                        options.reconnectInitial(), options.reconnectMax(), () -> 1.0d);
    }

    /**
     * Parses and validates the gateway discovery response. Package-visible so the endpoint and
     * shard invariants can be tested without any network.
     */
    static URI gatewayUri(OfficialQqApiClient apiClient) throws Exception {
        return gatewayUriFromResponse(apiClient.gatewayBot());
    }

    static URI gatewayUriFromResponse(JsonNode response) {
        JsonNode data = response != null && response.path("data").isObject()
                ? response.path("data")
                : response;
        if (data == null || !data.isObject()) {
            throw new GatewayPermanentException("INVALID_GATEWAY_RESPONSE");
        }
        int shards = data.path("shards").asInt(-1);
        if (shards != 1) {
            throw new GatewayPermanentException("UNSUPPORTED_SHARD");
        }
        String rawUrl = data.path("url").asText("");
        if (rawUrl.isBlank()) {
            throw new GatewayPermanentException("INVALID_GATEWAY_RESPONSE");
        }
        return OfficialQqAccountEndpoints.requireOfficialGatewayUri(URI.create(rawUrl));
    }

    private static OfficialQqAccountRuntimeStatus toRuntimeStatus(GatewayStatus status) {
        OfficialQqAccountState state = switch (status.state()) {
            case STOPPED -> OfficialQqAccountState.STOPPED;
            case CONNECTING -> OfficialQqAccountState.CONNECTING;
            case HELLO -> OfficialQqAccountState.HELLO;
            case IDENTIFYING -> OfficialQqAccountState.IDENTIFYING;
            case RESUME -> OfficialQqAccountState.RESUME;
            case READY -> OfficialQqAccountState.READY;
            case RECONNECTING -> OfficialQqAccountState.RECONNECTING;
            case CLOSED -> OfficialQqAccountState.CLOSED;
            case DOWN -> OfficialQqAccountState.DOWN;
        };
        String code = status.lastErrorCode().isBlank()
                ? status.terminalReason()
                : status.lastErrorCode();
        return new OfficialQqAccountRuntimeStatus(
                state,
                status.connected(),
                status.terminal(),
                status.updatedAt(),
                status.lastConnectedAt(),
                status.lastEventAt(),
                status.lastSequence(),
                status.reconnectAttempts(),
                classifyGatewayError(code, state),
                code,
                status.lastErrorType(),
                "");
    }

    private static OfficialQqAccountErrorKind classifyGatewayError(
            String code, OfficialQqAccountState state) {
        String normalized = code == null ? "" : code.toUpperCase(Locale.ROOT);
        if (normalized.contains("AUTH")
                || normalized.contains("TOKEN")
                || normalized.contains("4003")
                || normalized.contains("4004")) {
            return OfficialQqAccountErrorKind.AUTHENTICATION;
        }
        if (normalized.contains("SHARD")
                || normalized.contains("INVALID_GATEWAY_RESPONSE")
                || normalized.contains("GATEWAY_URI")) {
            return OfficialQqAccountErrorKind.CONFIGURATION;
        }
        if (normalized.contains("PERMISSION")
                || normalized.contains("INTENT")
                || normalized.contains("BANNED")
                || normalized.contains("OFFLINE")
                || normalized.contains("4014")
                || normalized.contains("4914")
                || normalized.contains("4915")) {
            return OfficialQqAccountErrorKind.PERMISSION;
        }
        if (normalized.contains("TIMEOUT")) {
            return OfficialQqAccountErrorKind.TIMEOUT;
        }
        if (normalized.contains("RATE")) {
            return OfficialQqAccountErrorKind.RATE_LIMITED;
        }
        if (!normalized.isBlank()) {
            return OfficialQqAccountErrorKind.PROTOCOL;
        }
        return state == OfficialQqAccountState.DOWN
                ? OfficialQqAccountErrorKind.PROTOCOL
                : OfficialQqAccountErrorKind.NONE;
    }

    private static final class Runtime implements OfficialQqAccountClient {
        private final String accountId;
        private final OfficialQqGatewayClient gateway;
        private final OfficialQqTokenManager tokenManager;
        private final OfficialQqApiClient apiClient;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Runtime(
                String accountId,
                OfficialQqGatewayClient gateway,
                OfficialQqTokenManager tokenManager,
                OfficialQqApiClient apiClient) {
            this.accountId = accountId;
            this.gateway = gateway;
            this.tokenManager = tokenManager;
            this.apiClient = apiClient;
        }

        @Override
        public String accountId() {
            return accountId;
        }

        @Override
        public void start() {
            if (closed.get()) {
                throw new IllegalStateException("official account runtime is closed");
            }
            gateway.start();
        }

        @Override
        public OfficialQqAccountRuntimeStatus status() {
            return toRuntimeStatus(gateway.status());
        }

        @Override
        public Optional<OfficialQqApiClient> apiClient() {
            return closed.get() ? Optional.empty() : Optional.of(apiClient);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                gateway.close();
            } finally {
                tokenManager.close();
            }
        }
    }
}
