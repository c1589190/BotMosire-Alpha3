package io.mosire.bma3.qqofficial.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.bma3.qqofficial.api.OfficialQqApiException;
import io.mosire.bma3.qqofficial.api.OfficialQqBaseUri;
import io.mosire.bma3.qqofficial.api.OfficialQqEndpoint;
import io.mosire.bma3.qqofficial.api.OfficialQqErrorKind;
import io.mosire.bma3.qqofficial.api.OfficialQqHttpEvent;
import io.mosire.bma3.qqofficial.api.OfficialQqHttpObserver;
import io.mosire.bma3.qqofficial.api.OfficialQqHttpTransport;
import io.mosire.bma3.qqofficial.api.OfficialQqResponseBody;
import io.mosire.bma3.qqofficial.credential.OfficialQqCredential;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches, caches and refreshes official QQ AccessTokens.
 *
 * <p>The cache is memory-only. A refresh is started roughly {@code refreshAhead} before expiry and
 * is single-flight: concurrent callers share one HTTP request. A rejected token can be replaced at
 * most once by the caller; this class itself never retries in a loop. Logs and observer events carry
 * only account/endpoint/status/code/trace/timing metadata, never token material.</p>
 */
public final class OfficialQqTokenManager implements AutoCloseable {

    public static final URI DEFAULT_BASE_URI = OfficialQqBaseUri.PRODUCTION;
    public static final Duration DEFAULT_REFRESH_AHEAD = Duration.ofSeconds(60);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final Logger LOG = LoggerFactory.getLogger(OfficialQqTokenManager.class);
    private static final int MAX_RESPONSE_BYTES = OfficialQqResponseBody.MAX_RESPONSE_BYTES;

    private final String accountId;
    private final OfficialQqCredential credential;
    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration requestTimeout;
    private final Duration refreshAhead;
    private final OfficialQqHttpObserver observer;

    private final Object lock = new Object();
    private CachedToken cached;
    private CompletableFuture<CachedToken> refreshInFlight;
    /** Guarded by {@link #lock}; once true no token may be produced or returned. */
    private boolean closed;

    /**
     * Legacy constructor retained for source compatibility. It is production-only: an alternate
     * base URI is rejected. Tests must use {@link #forLoopbackTesting}.
     */
    public OfficialQqTokenManager(
            String accountId,
            OfficialQqCredential credential,
            URI baseUri,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock,
            Duration requestTimeout,
            Duration refreshAhead,
            OfficialQqHttpObserver observer) {
        this(
                accountId,
                credential,
                OfficialQqBaseUri.requireProduction(baseUri),
                httpClient,
                objectMapper,
                clock,
                requestTimeout,
                refreshAhead,
                observer,
                true);
    }

    public OfficialQqTokenManager(
            String accountId,
            OfficialQqCredential credential,
            URI baseUri,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock,
            Duration requestTimeout,
            Duration refreshAhead) {
        this(
                accountId,
                credential,
                baseUri,
                httpClient,
                objectMapper,
                clock,
                requestTimeout,
                refreshAhead,
                event -> { });
    }

    public OfficialQqTokenManager(
            String accountId,
            OfficialQqCredential credential,
            URI baseUri,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock,
            Duration requestTimeout) {
        this(
                accountId,
                credential,
                baseUri,
                httpClient,
                objectMapper,
                clock,
                requestTimeout,
                DEFAULT_REFRESH_AHEAD,
                event -> { });
    }

    /** Production factory: always the fixed official HTTPS origin. */
    public static OfficialQqTokenManager production(
            String accountId,
            OfficialQqCredential credential,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock,
            Duration requestTimeout) {
        return production(
                accountId,
                credential,
                httpClient,
                objectMapper,
                clock,
                requestTimeout,
                DEFAULT_REFRESH_AHEAD,
                event -> { });
    }

    /** Production factory: always the fixed official HTTPS origin. */
    public static OfficialQqTokenManager production(
            String accountId,
            OfficialQqCredential credential,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock,
            Duration requestTimeout,
            Duration refreshAhead) {
        return production(
                accountId,
                credential,
                httpClient,
                objectMapper,
                clock,
                requestTimeout,
                refreshAhead,
                event -> { });
    }

    /** Production factory: always the fixed official HTTPS origin. */
    public static OfficialQqTokenManager production(
            String accountId,
            OfficialQqCredential credential,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock,
            Duration requestTimeout,
            Duration refreshAhead,
            OfficialQqHttpObserver observer) {
        return new OfficialQqTokenManager(
                accountId,
                credential,
                OfficialQqBaseUri.PRODUCTION,
                httpClient,
                objectMapper,
                clock,
                requestTimeout,
                refreshAhead,
                observer,
                true);
    }

    /**
     * Explicit test-only factory. The URI must be a literal loopback address; this cannot be used
     * by normal app wiring. The AppSecret is still never logged.
     */
    public static OfficialQqTokenManager forLoopbackTesting(
            String accountId,
            OfficialQqCredential credential,
            URI loopbackBaseUri,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock,
            Duration requestTimeout,
            Duration refreshAhead,
            OfficialQqHttpObserver observer) {
        return new OfficialQqTokenManager(
                accountId,
                credential,
                OfficialQqBaseUri.requireLoopbackTesting(loopbackBaseUri),
                httpClient,
                objectMapper,
                clock,
                requestTimeout,
                refreshAhead,
                observer,
                true);
    }

    /** Convenience overload for loopback tests. */
    public static OfficialQqTokenManager forLoopbackTesting(
            String accountId,
            OfficialQqCredential credential,
            URI loopbackBaseUri,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock,
            Duration requestTimeout) {
        return forLoopbackTesting(
                accountId,
                credential,
                loopbackBaseUri,
                httpClient,
                objectMapper,
                clock,
                requestTimeout,
                DEFAULT_REFRESH_AHEAD,
                event -> { });
    }

    private OfficialQqTokenManager(
            String accountId,
            OfficialQqCredential credential,
            URI validatedBaseUri,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock,
            Duration requestTimeout,
            Duration refreshAhead,
            OfficialQqHttpObserver observer,
            boolean validated) {
        this.accountId = requireText(accountId, "accountId");
        this.credential = Objects.requireNonNull(credential, "credential");
        this.baseUri = Objects.requireNonNull(validatedBaseUri, "validatedBaseUri");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        OfficialQqHttpTransport.requireNoRedirects(this.httpClient);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.refreshAhead = requireNonNegative(refreshAhead, "refreshAhead");
        this.observer = observer == null ? event -> { } : observer;
    }

    /** Read-only account identity; never returns credential or token material. */
    public String accountId() {
        return accountId;
    }

    /**
     * Return a usable {@code Authorization} value, refreshing first when the cache is missing or
     * within the refresh-ahead window.
     */
    public String authorizationHeader() throws OfficialQqApiException {
        return obtain(false, null);
    }

    /**
     * Raw token for protocol handshakes (Gateway Identify/Resume), where the surrounding protocol
     * itself requires the {@code QQBot } prefix to be added by the transport. Never log or persist
     * the returned value. HTTP clients should use {@link #authorizationHeader()} instead.
     */
    public String accessToken() throws OfficialQqApiException {
        String header = obtain(false, null);
        return header.substring("QQBot ".length());
    }

    /**
     * Called after an OpenAPI request was rejected as unauthenticated. If another caller already
     * installed a different token, that token is reused; otherwise exactly one refresh is started.
     * The rejected value must be the complete {@code QQBot <token>} header used for the failed call.
     */
    public String authorizationHeaderAfterRejected(String rejectedAuthorization)
            throws OfficialQqApiException {
        return obtain(true, rejectedAuthorization);
    }

    private String obtain(boolean afterRejection, String rejectedAuthorization)
            throws OfficialQqApiException {
        CompletableFuture<CachedToken> pending;
        boolean owner = false;
        Instant now = clock.instant();

        synchronized (lock) {
            requireOpenLocked();
            CachedToken current = cached;
            if (current != null) {
                if (afterRejection) {
                    if (!current.authorizationHeader().equals(rejectedAuthorization)
                            && current.usableAt(now)) {
                        return current.authorizationHeader();
                    }
                    if (current.authorizationHeader().equals(rejectedAuthorization)) {
                        cached = null;
                        current = null;
                    }
                } else if (current.usableAt(now) && !current.needsRefreshAt(now)) {
                    return current.authorizationHeader();
                }
            }

            pending = refreshInFlight;
            if (pending == null) {
                pending = new CompletableFuture<>();
                refreshInFlight = pending;
                owner = true;
            }
        }

        if (owner) {
            return performRefresh(pending);
        }
        return awaitRefresh(pending);
    }

    private String performRefresh(CompletableFuture<CachedToken> pending)
            throws OfficialQqApiException {
        try {
            synchronized (lock) {
                requireOpenLocked();
            }
            CachedToken fetched = fetchToken();
            synchronized (lock) {
                requireOpenLocked();
                cached = fetched;
                pending.complete(fetched);
                return fetched.authorizationHeader();
            }
        } catch (OfficialQqApiException failure) {
            pending.completeExceptionally(failure);
            throw failure;
        } catch (RuntimeException failure) {
            OfficialQqApiException wrapped = isClosed() ? closedFailure() : new OfficialQqApiException(
                    OfficialQqErrorKind.CONFIGURATION,
                    -1,
                    accountId,
                    OfficialQqEndpoint.TOKEN.category(),
                    "",
                    "",
                    false,
                    "token 刷新发生内部错误",
                    failure);
            pending.completeExceptionally(wrapped);
            throw wrapped;
        } finally {
            synchronized (lock) {
                if (refreshInFlight == pending) {
                    refreshInFlight = null;
                }
            }
        }
    }

    private String awaitRefresh(CompletableFuture<CachedToken> pending)
            throws OfficialQqApiException {
        long waitMillis = Math.max(1L, requestTimeout.toMillis() + 1_000L);
        try {
            CachedToken fetched = pending.get(waitMillis, TimeUnit.MILLISECONDS);
            synchronized (lock) {
                requireOpenLocked();
                return fetched.authorizationHeader();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new OfficialQqApiException(
                    OfficialQqErrorKind.NETWORK,
                    -1,
                    accountId,
                    OfficialQqEndpoint.TOKEN.category(),
                    "",
                    "",
                    true,
                    "等待 token 刷新时线程被中断",
                    interrupted);
        } catch (TimeoutException timeout) {
            throw new OfficialQqApiException(
                    OfficialQqErrorKind.TIMEOUT,
                    -1,
                    accountId,
                    OfficialQqEndpoint.TOKEN.category(),
                    "",
                    "",
                    true,
                    "等待并发 token 刷新超时",
                    timeout);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof OfficialQqApiException apiFailure) {
                throw apiFailure;
            }
            throw new OfficialQqApiException(
                    OfficialQqErrorKind.NETWORK,
                    -1,
                    accountId,
                    OfficialQqEndpoint.TOKEN.category(),
                    "",
                    "",
                    true,
                    "token 刷新失败",
                    cause);
        }
    }

    private CachedToken fetchToken() throws OfficialQqApiException {
        synchronized (lock) {
            requireOpenLocked();
        }
        long startedNanos = System.nanoTime();
        URI uri = endpoint(baseUri, OfficialQqEndpoint.TOKEN.pathTemplate());
        String requestBody;
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("appId", credential.appId());
            char[] secret = credential.copySecret();
            try {
                body.put("clientSecret", new String(secret));
                requestBody = objectMapper.writeValueAsString(body);
            } finally {
                Arrays.fill(secret, '\0');
                body.remove("clientSecret");
            }
        } catch (IOException | RuntimeException failure) {
            if (isClosed()) {
                throw closedFailure();
            }
            throw new OfficialQqApiException(
                    OfficialQqErrorKind.CONFIGURATION,
                    -1,
                    accountId,
                    OfficialQqEndpoint.TOKEN.category(),
                    "",
                    "",
                    false,
                    "无法构造 token 请求",
                    failure);
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "bma3-qq-official-connect")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        OfficialQqHttpTransport.Response response;
        try {
            response = OfficialQqHttpTransport.send(
                    httpClient, request, requestTimeout, MAX_RESPONSE_BYTES);
        } catch (OfficialQqHttpTransport.DeadlineExceededException timeout) {
            publish(startedNanos, OfficialQqEndpoint.TOKEN, -1, "", "", false);
            throw new OfficialQqApiException(
                    OfficialQqErrorKind.TIMEOUT,
                    -1,
                    accountId,
                    OfficialQqEndpoint.TOKEN.category(),
                    "",
                    "",
                    true,
                    "获取 token 请求超时",
                    timeout);
        } catch (OfficialQqResponseBody.TooLargeException tooLarge) {
            publish(startedNanos, OfficialQqEndpoint.TOKEN, -1, "", "", false);
            throw malformed(-1, "", "token 响应体超过上限", tooLarge);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            publish(startedNanos, OfficialQqEndpoint.TOKEN, -1, "", "", false);
            throw new OfficialQqApiException(
                    OfficialQqErrorKind.NETWORK,
                    -1,
                    accountId,
                    OfficialQqEndpoint.TOKEN.category(),
                    "",
                    "",
                    true,
                    "获取 token 请求被中断",
                    interrupted);
        } catch (IOException failure) {
            if (isClosed()) {
                throw closedFailure();
            }
            publish(startedNanos, OfficialQqEndpoint.TOKEN, -1, "", "", false);
            throw new OfficialQqApiException(
                    OfficialQqErrorKind.NETWORK,
                    -1,
                    accountId,
                    OfficialQqEndpoint.TOKEN.category(),
                    "",
                    "",
                    true,
                    "获取 token 网络失败",
                    failure);
        }

        int status = response.statusCode();
        byte[] bytes = response.body();
        JsonNode body = parseJson(bytes);
        String errorCode = errorCode(body);
        String traceId = traceId(response.headers(), body);
        if (status < 200 || status >= 300) {
            publish(startedNanos, OfficialQqEndpoint.TOKEN, status, errorCode, traceId, false);
            OfficialQqErrorKind kind = kindForHttpStatus(status);
            throw new OfficialQqApiException(
                    kind,
                    status,
                    accountId,
                    OfficialQqEndpoint.TOKEN.category(),
                    errorCode,
                    traceId,
                    kind.potentiallyRetryable(),
                    "token 端点返回 HTTP 错误",
                    null);
        }
        if (errorCode != null) {
            publish(startedNanos, OfficialQqEndpoint.TOKEN, status, errorCode, traceId, false);
            OfficialQqErrorKind kind = kindForBusinessCode(errorCode);
            throw new OfficialQqApiException(
                    kind,
                    status,
                    accountId,
                    OfficialQqEndpoint.TOKEN.category(),
                    errorCode,
                    traceId,
                    kind.potentiallyRetryable(),
                    "token 端点返回业务错误",
                    null);
        }
        if (body == null || !body.isObject()) {
            publish(startedNanos, OfficialQqEndpoint.TOKEN, status, "", traceId, false);
            throw malformed(status, traceId, "token 响应不是 JSON 对象");
        }
        String token = text(body, "access_token");
        if (token.isEmpty()) {
            publish(startedNanos, OfficialQqEndpoint.TOKEN, status, "", traceId, false);
            throw malformed(status, traceId, "token 响应缺少 access_token");
        }
        // A broken server could echo the freshly minted token in trace metadata. Redact it
        // before it can reach an observer, log line or exception.
        traceId = OfficialQqApiException.safe(
                OfficialQqApiException.redact(traceId, token), 160);
        long expiresIn;
        try {
            expiresIn = positiveLong(body.get("expires_in"));
        } catch (IllegalArgumentException invalidExpiry) {
            publish(startedNanos, OfficialQqEndpoint.TOKEN, status, "", traceId, false);
            throw malformed(status, traceId, "token 响应 expires_in 非法", invalidExpiry);
        }

        Instant issuedAt = clock.instant();
        Instant expiresAt;
        try {
            expiresAt = issuedAt.plusSeconds(expiresIn);
        } catch (RuntimeException invalidExpiry) {
            publish(startedNanos, OfficialQqEndpoint.TOKEN, status, "", traceId, false);
            throw malformed(status, traceId, "token 有效期越界", invalidExpiry);
        }
        Instant refreshAt = expiresAt.minus(refreshAhead);
        if (refreshAt.isBefore(issuedAt)) {
            refreshAt = issuedAt;
        }
        publish(startedNanos, OfficialQqEndpoint.TOKEN, status, "", traceId, true);
        LOG.debug(
                "qqofficial.token.refreshed accountId={} status={} traceId={} expiresInSeconds={}",
                accountId,
                status,
                traceId,
                expiresIn);
        return new CachedToken(token, expiresAt, refreshAt);
    }

    private OfficialQqApiException malformed(int status, String traceId, String detail) {
        return malformed(status, traceId, detail, null);
    }

    private OfficialQqApiException malformed(
            int status, String traceId, String detail, Throwable cause) {
        return new OfficialQqApiException(
                OfficialQqErrorKind.MALFORMED_RESPONSE,
                status,
                accountId,
                OfficialQqEndpoint.TOKEN.category(),
                "",
                traceId,
                false,
                detail,
                cause);
    }

    private void publish(
            long startedNanos,
            OfficialQqEndpoint endpoint,
            int status,
            String errorCode,
            String traceId,
            boolean success) {
        long elapsedMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        OfficialQqHttpEvent event = new OfficialQqHttpEvent(
                accountId,
                endpoint.method(),
                endpoint,
                status,
                errorCode,
                traceId,
                elapsedMillis,
                success);
        try {
            observer.onHttp(event);
        } catch (RuntimeException observerFailure) {
            LOG.debug(
                    "qqofficial.observer.failed event=token type={}",
                    observerFailure.getClass().getSimpleName());
        }
        if (success) {
            LOG.debug("qqofficial.http {}", event);
        } else {
            LOG.warn("qqofficial.http {}", event);
        }
    }

    private JsonNode parseJson(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(bytes);
        } catch (IOException invalidJson) {
            return null;
        }
    }

    private static String errorCode(JsonNode body) {
        if (body == null || !body.isObject()) {
            return null;
        }
        for (String field : new String[] {"err_code", "code"}) {
            JsonNode value = body.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.asText("").strip();
            if (!text.isEmpty() && !isZero(text)) {
                // A stable business error code is numeric. Refuse to echo arbitrary server
                // strings here because a broken response could otherwise smuggle a token into
                // logs/exception output via `message` or `trace_id`.
                return text.matches("[0-9]{1,12}") ? text : "NON_NUMERIC";
            }
        }
        return null;
    }

    private static boolean isZero(String value) {
        try {
            return Long.parseLong(value) == 0L;
        } catch (NumberFormatException ignored) {
            return "0.0".equals(value) || "0.00".equals(value);
        }
    }

    private String traceId(HttpHeaders headers, JsonNode body) {
        String raw = headers.firstValue("X-Tps-trace-ID").orElse(null);
        if (raw == null && body != null && body.isObject()) {
            JsonNode node = body.get("trace_id");
            if (node == null || node.isNull()) {
                node = body.get("traceId");
            }
            if (node != null && !node.isNull()) {
                raw = node.asText(null);
            }
        }
        char[] secret;
        try {
            secret = credential.copySecret();
        } catch (IllegalStateException alreadyClosed) {
            secret = new char[0];
        }
        try {
            return OfficialQqApiException.safe(
                    OfficialQqApiException.redact(raw, new String(secret)), 160);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private static String text(JsonNode object, String field) {
        if (object == null) {
            return "";
        }
        JsonNode node = object.get(field);
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }

    private static long positiveLong(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("missing");
        }
        long value;
        if (node.isIntegralNumber()) {
            value = node.asLong();
        } else {
            String text = node.asText("").strip();
            if (text.isEmpty()) {
                throw new IllegalArgumentException("blank");
            }
            try {
                value = Long.parseLong(text);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("not a number", invalid);
            }
        }
        if (value <= 0L) {
            throw new IllegalArgumentException("not positive");
        }
        return value;
    }

    private static OfficialQqErrorKind kindForHttpStatus(int status) {
        if (status == 401) {
            return OfficialQqErrorKind.AUTHENTICATION;
        }
        if (status == 403) {
            return OfficialQqErrorKind.PERMISSION;
        }
        if (status == 404) {
            return OfficialQqErrorKind.NOT_FOUND;
        }
        if (status == 429) {
            return OfficialQqErrorKind.RATE_LIMITED;
        }
        if (status >= 500) {
            return OfficialQqErrorKind.SERVER;
        }
        return OfficialQqErrorKind.HTTP;
    }

    private static OfficialQqErrorKind kindForBusinessCode(String code) {
        return switch (code) {
            case "401", "11241", "11242" -> OfficialQqErrorKind.AUTHENTICATION;
            case "403", "11253", "4014", "4914", "4915" -> OfficialQqErrorKind.PERMISSION;
            case "404" -> OfficialQqErrorKind.NOT_FOUND;
            case "429", "11257" -> OfficialQqErrorKind.RATE_LIMITED;
            default -> OfficialQqErrorKind.BUSINESS;
        };
    }

    private static URI endpoint(URI baseUri, String path) {
        String base = baseUri.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " 必须为正");
        }
        return duration;
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " 不能为负");
        }
        return duration;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.strip();
    }

    private void requireOpenLocked() throws OfficialQqApiException {
        if (closed) {
            throw closedFailure();
        }
    }

    private boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    private OfficialQqApiException closedFailure() {
        return new OfficialQqApiException(
                OfficialQqErrorKind.CONFIGURATION,
                -1,
                accountId,
                OfficialQqEndpoint.TOKEN.category(),
                "",
                "",
                false,
                "token manager 已关闭",
                null);
    }

    /**
     * Idempotently closes the manager and wakes any in-flight refresh waiters with a closed failure.
     * A response that arrives later is discarded and its caller cannot receive the token.
     */
    @Override
    public void close() {
        CompletableFuture<CachedToken> pending;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            cached = null;
            pending = refreshInFlight;
        }
        if (pending != null) {
            pending.completeExceptionally(closedFailure());
        }
        credential.close();
    }

    private static final class CachedToken {
        private final String token;
        private final Instant expiresAt;
        private final Instant refreshAt;

        private CachedToken(String token, Instant expiresAt, Instant refreshAt) {
            this.token = token;
            this.expiresAt = expiresAt;
            this.refreshAt = refreshAt;
        }

        private String authorizationHeader() {
            return "QQBot " + token;
        }

        private boolean usableAt(Instant now) {
            return now.isBefore(expiresAt);
        }

        private boolean needsRefreshAt(Instant now) {
            return !now.isBefore(refreshAt);
        }

        @Override
        public String toString() {
            return "CachedToken[<redacted>]";
        }
    }
}
