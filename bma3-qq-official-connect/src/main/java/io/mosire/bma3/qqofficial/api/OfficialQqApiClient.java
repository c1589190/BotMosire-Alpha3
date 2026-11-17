package io.mosire.bma3.qqofficial.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.bma3.qqofficial.auth.OfficialQqTokenManager;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small, strict OpenAPI client for the endpoints required by the official QQ channel.
 *
 * <p>Every request is authenticated with {@code Authorization: QQBot <token>}. A token rejection
 * may be retried exactly once, and only for an endpoint marked safe to repeat. Ordinary business
 * errors, HTTP errors and transport failures are never retried here. Nothing from a request or
 * response body is placed in logs or exceptions.</p>
 */
public final class OfficialQqApiClient {

    public static final URI DEFAULT_BASE_URI = OfficialQqBaseUri.PRODUCTION;
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final Logger LOG = LoggerFactory.getLogger(OfficialQqApiClient.class);
    private static final int MAX_RESPONSE_BYTES = OfficialQqResponseBody.MAX_RESPONSE_BYTES;
    private static final int MAX_OPENID_LENGTH = 256;
    private static final int MAX_FILE_INFO_CHARS = 1_048_576;
    private static final int MAX_FILE_NAME_CHARS = 1_024;
    private static final int MAX_BASE64_CHARS = 7_100_000;

    private final String accountId;
    private final OfficialQqTokenManager tokenManager;
    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    private final OfficialQqHttpObserver observer;

    /**
     * Legacy constructor retained for source compatibility. It is production-only: an alternate
     * base URI is rejected. Tests must use {@link #forLoopbackTesting}.
     */
    public OfficialQqApiClient(
            String accountId,
            OfficialQqTokenManager tokenManager,
            URI baseUri,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration requestTimeout,
            OfficialQqHttpObserver observer) {
        this(
                accountId,
                tokenManager,
                OfficialQqBaseUri.requireProduction(baseUri),
                httpClient,
                objectMapper,
                requestTimeout,
                observer,
                true);
    }

    public OfficialQqApiClient(
            String accountId,
            OfficialQqTokenManager tokenManager,
            URI baseUri,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration requestTimeout) {
        this(
                accountId,
                tokenManager,
                baseUri,
                httpClient,
                objectMapper,
                requestTimeout,
                event -> { });
    }

    public OfficialQqApiClient(
            String accountId,
            OfficialQqTokenManager tokenManager,
            URI baseUri,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        this(
                accountId,
                tokenManager,
                baseUri,
                httpClient,
                objectMapper,
                DEFAULT_REQUEST_TIMEOUT);
    }

    /** Production factory: always the fixed official HTTPS origin. */
    public static OfficialQqApiClient production(
            String accountId,
            OfficialQqTokenManager tokenManager,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        return production(
                accountId,
                tokenManager,
                httpClient,
                objectMapper,
                DEFAULT_REQUEST_TIMEOUT,
                event -> { });
    }

    /** Production factory: always the fixed official HTTPS origin. */
    public static OfficialQqApiClient production(
            String accountId,
            OfficialQqTokenManager tokenManager,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration requestTimeout) {
        return production(
                accountId,
                tokenManager,
                httpClient,
                objectMapper,
                requestTimeout,
                event -> { });
    }

    /** Production factory: always the fixed official HTTPS origin. */
    public static OfficialQqApiClient production(
            String accountId,
            OfficialQqTokenManager tokenManager,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration requestTimeout,
            OfficialQqHttpObserver observer) {
        return new OfficialQqApiClient(
                accountId,
                tokenManager,
                OfficialQqBaseUri.PRODUCTION,
                httpClient,
                objectMapper,
                requestTimeout,
                observer,
                true);
    }

    /**
     * Explicit test-only factory. The URI must be a literal loopback address; normal app wiring
     * must not use it.
     */
    public static OfficialQqApiClient forLoopbackTesting(
            String accountId,
            OfficialQqTokenManager tokenManager,
            URI loopbackBaseUri,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration requestTimeout,
            OfficialQqHttpObserver observer) {
        return new OfficialQqApiClient(
                accountId,
                tokenManager,
                OfficialQqBaseUri.requireLoopbackTesting(loopbackBaseUri),
                httpClient,
                objectMapper,
                requestTimeout,
                observer,
                true);
    }

    /** Convenience overload for loopback tests. */
    public static OfficialQqApiClient forLoopbackTesting(
            String accountId,
            OfficialQqTokenManager tokenManager,
            URI loopbackBaseUri,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration requestTimeout) {
        return forLoopbackTesting(
                accountId,
                tokenManager,
                loopbackBaseUri,
                httpClient,
                objectMapper,
                requestTimeout,
                event -> { });
    }

    private OfficialQqApiClient(
            String accountId,
            OfficialQqTokenManager tokenManager,
            URI validatedBaseUri,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration requestTimeout,
            OfficialQqHttpObserver observer,
            boolean validated) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        this.accountId = accountId.strip();
        this.tokenManager = Objects.requireNonNull(tokenManager, "tokenManager");
        if (!this.accountId.equals(this.tokenManager.accountId())) {
            throw new IllegalArgumentException("官方 API accountId 与 tokenManager 不一致");
        }
        this.baseUri = Objects.requireNonNull(validatedBaseUri, "validatedBaseUri");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        OfficialQqHttpTransport.requireNoRedirects(this.httpClient);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.observer = observer == null ? event -> { } : observer;
    }

    /** Read-only account identity for transport binding checks. */
    public String accountId() {
        return accountId;
    }

    /** {@code GET /users/@me}. */
    public JsonNode currentUser() throws OfficialQqApiException {
        return execute(OfficialQqEndpoint.USERS_ME, OfficialQqEndpoint.USERS_ME.pathTemplate(), null);
    }

    /** Alias that reads naturally at call sites. */
    public JsonNode me() throws OfficialQqApiException {
        return currentUser();
    }

    /** Alias for the documented {@code /users/@me} endpoint. */
    public JsonNode usersMe() throws OfficialQqApiException {
        return currentUser();
    }

    /** {@code GET /gateway/bot}. */
    public JsonNode gatewayBot() throws OfficialQqApiException {
        return execute(
                OfficialQqEndpoint.GATEWAY_BOT,
                OfficialQqEndpoint.GATEWAY_BOT.pathTemplate(),
                null);
    }

    /** Alias for the gateway discovery endpoint. */
    public JsonNode gateway() throws OfficialQqApiException {
        return gatewayBot();
    }

    /** {@code GET /v2/groups/{group_openid}/info}. */
    public JsonNode groupInfo(String groupOpenid) throws OfficialQqApiException {
        return execute(
                OfficialQqEndpoint.GROUP_INFO,
                pathWithId(OfficialQqEndpoint.GROUP_INFO, groupOpenid),
                null);
    }

    /** {@code GET /v2/groups/{group_openid}/bot_state}. */
    public JsonNode groupBotState(String groupOpenid) throws OfficialQqApiException {
        return execute(
                OfficialQqEndpoint.GROUP_BOT_STATE,
                pathWithId(OfficialQqEndpoint.GROUP_BOT_STATE, groupOpenid),
                null);
    }

    /** {@code GET /v2/users/{user_openid}/info}. */
    public JsonNode userInfo(String userOpenid) throws OfficialQqApiException {
        return execute(
                OfficialQqEndpoint.USER_INFO,
                pathWithId(OfficialQqEndpoint.USER_INFO, userOpenid),
                null);
    }

    /** Active text send to one official group. Package-private transport-only capability. */
    JsonNode sendGroupText(String groupOpenid, String content)
            throws OfficialQqApiException {
        return sendGroupText(groupOpenid, content, "", "", null);
    }

    /** Passive/active text send to one official group. Transport-only capability. */
    JsonNode sendGroupText(String groupOpenid, String content, String msgId,
                                  String eventId, Integer msgSeq)
            throws OfficialQqApiException {
        return execute(
                OfficialQqEndpoint.GROUP_MESSAGES,
                pathWithId(OfficialQqEndpoint.GROUP_MESSAGES, groupOpenid),
                textMessageBody(content, msgId, eventId, msgSeq));
    }

    /** Active text send to one official C2C user. Package-private transport-only capability. */
    JsonNode sendUserText(String userOpenid, String content)
            throws OfficialQqApiException {
        return sendUserText(userOpenid, content, "", "", null);
    }

    /** Passive/active text send to one official C2C user. Transport-only capability. */
    JsonNode sendUserText(String userOpenid, String content, String msgId,
                                 String eventId, Integer msgSeq)
            throws OfficialQqApiException {
        return execute(
                OfficialQqEndpoint.USER_MESSAGES,
                pathWithId(OfficialQqEndpoint.USER_MESSAGES, userOpenid),
                textMessageBody(content, msgId, eventId, msgSeq));
    }

    /**
     * Fixed-endpoint group media upload with {@code srv_send_msg=false}. Package-private so no
     * Agent tool can call the raw upload endpoint or handle {@code file_info} directly.
     */
    JsonNode uploadGroupMedia(String groupOpenid, int fileType, String fileName, String fileData)
            throws OfficialQqApiException {
        return execute(
                OfficialQqEndpoint.GROUP_FILES,
                pathWithId(OfficialQqEndpoint.GROUP_FILES, groupOpenid),
                mediaUploadBody(fileType, fileName, fileData));
    }

    /** Fixed-endpoint C2C media upload with {@code srv_send_msg=false}. */
    JsonNode uploadUserMedia(String userOpenid, int fileType, String fileName, String fileData)
            throws OfficialQqApiException {
        return execute(
                OfficialQqEndpoint.USER_FILES,
                pathWithId(OfficialQqEndpoint.USER_FILES, userOpenid),
                mediaUploadBody(fileType, fileName, fileData));
    }

    /** One fixed-endpoint group rich-media message ({@code msg_type=7}). */
    JsonNode sendGroupMedia(String groupOpenid, String fileInfo, String msgId,
                            String eventId, Integer msgSeq)
            throws OfficialQqApiException {
        return execute(
                OfficialQqEndpoint.GROUP_MESSAGES,
                pathWithId(OfficialQqEndpoint.GROUP_MESSAGES, groupOpenid),
                mediaMessageBody(fileInfo, msgId, eventId, msgSeq));
    }

    /** One fixed-endpoint C2C rich-media message ({@code msg_type=7}). */
    JsonNode sendUserMedia(String userOpenid, String fileInfo, String msgId,
                           String eventId, Integer msgSeq)
            throws OfficialQqApiException {
        return execute(
                OfficialQqEndpoint.USER_MESSAGES,
                pathWithId(OfficialQqEndpoint.USER_MESSAGES, userOpenid),
                mediaMessageBody(fileInfo, msgId, eventId, msgSeq));
    }

    /**
     * {@code POST /v2/generate_url_link}. This phase only calls a non-message operation; the retry
     * allowance is deliberately limited to this endpoint.
     */
    public JsonNode generateUrlLink(String callbackData) throws OfficialQqApiException {
        Objects.requireNonNull(callbackData, "callbackData");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("callback_data", callbackData);
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (IOException failure) {
            throw new OfficialQqApiException(
                    OfficialQqErrorKind.CONFIGURATION,
                    -1,
                    accountId,
                    OfficialQqEndpoint.GENERATE_URL_LINK.category(),
                    "",
                    "",
                    false,
                    "无法构造分享链接请求",
                    failure);
        }
        return execute(OfficialQqEndpoint.GENERATE_URL_LINK, OfficialQqEndpoint.GENERATE_URL_LINK.pathTemplate(), json);
    }

    /** Builds the fixed group/C2C direct-upload body. */
    private String mediaUploadBody(int fileType, String fileName, String fileData) {
        if (fileType < 1 || fileType > 4) {
            throw new IllegalArgumentException("file_type 必须是 1..4");
        }
        String safeName = mediaFileName(fileName);
        String safeData = mediaBase64(fileData);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("file_type", fileType);
        body.put("file_name", safeName);
        body.put("file_data", safeData);
        body.put("srv_send_msg", false);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException failure) {
            throw new IllegalArgumentException("无法构造官方媒体上传请求", failure);
        }
    }

    /**
     * Builds the fixed rich-media message payload. {@code file_info} is opaque and is passed
     * through without parsing.
     */
    private String mediaMessageBody(String fileInfo, String msgId, String eventId, Integer msgSeq) {
        String safeFileInfo = mediaFileInfo(fileInfo);
        String normalizedMsgId = textId(msgId, "msg_id");
        String normalizedEventId = textId(eventId, "event_id");
        if (!normalizedMsgId.isEmpty() && !normalizedEventId.isEmpty()) {
            throw new IllegalArgumentException("msg_id 与 event_id 只能二选一");
        }
        if (msgSeq != null && normalizedMsgId.isEmpty()) {
            throw new IllegalArgumentException("msg_seq 只能与 msg_id 一起使用");
        }
        if (msgSeq != null && msgSeq < 1) {
            throw new IllegalArgumentException("msg_seq 必须为正整数");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("msg_type", 7);
        ObjectNode media = body.putObject("media");
        media.put("file_info", safeFileInfo);
        if (!normalizedMsgId.isEmpty()) {
            body.put("msg_id", normalizedMsgId);
            if (msgSeq != null) {
                body.put("msg_seq", msgSeq);
            }
        } else if (!normalizedEventId.isEmpty()) {
            body.put("event_id", normalizedEventId);
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException failure) {
            throw new IllegalArgumentException("无法构造官方媒体消息请求", failure);
        }
    }

    private static String mediaFileInfo(String raw) {
        String value = raw == null ? "" : raw;
        if (value.isBlank() || value.length() > MAX_FILE_INFO_CHARS) {
            throw new IllegalArgumentException("file_info 无效");
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException("file_info 含非法字符");
            }
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    private static String mediaFileName(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isBlank() || value.length() > MAX_FILE_NAME_CHARS
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("file_name 无效");
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException("file_name 含非法字符");
            }
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    private static String mediaBase64(String raw) {
        String value = raw == null ? "" : raw;
        if (value.isBlank() || value.length() > MAX_BASE64_CHARS || value.length() % 4 != 0) {
            throw new IllegalArgumentException("file_data 不是标准 Base64");
        }
        int padding = 0;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '=') {
                padding++;
                if (padding > 2) {
                    throw new IllegalArgumentException("file_data 不是标准 Base64");
                }
            } else {
                if (padding > 0) {
                    throw new IllegalArgumentException("file_data 不是标准 Base64");
                }
                if (!((ch >= 'A' && ch <= 'Z')
                        || (ch >= 'a' && ch <= 'z')
                        || (ch >= '0' && ch <= '9')
                        || ch == '+' || ch == '/')) {
                    throw new IllegalArgumentException("file_data 不是标准 Base64");
                }
            }
        }
        return value;
    }

    /**
     * Builds the fixed text-message payload. Official message IDs and event IDs are mutually
     * exclusive, and {@code msg_seq} is meaningful only together with {@code msg_id}.
     */
    private String textMessageBody(String content, String msgId, String eventId, Integer msgSeq) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        String normalizedMsgId = textId(msgId, "msg_id");
        String normalizedEventId = textId(eventId, "event_id");
        if (!normalizedMsgId.isEmpty() && !normalizedEventId.isEmpty()) {
            throw new IllegalArgumentException("msg_id 与 event_id 只能二选一");
        }
        if (msgSeq != null && normalizedMsgId.isEmpty()) {
            throw new IllegalArgumentException("msg_seq 只能与 msg_id 一起使用");
        }
        if (msgSeq != null && msgSeq < 1) {
            throw new IllegalArgumentException("msg_seq 必须为正整数");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("msg_type", 0);
        body.put("content", content);
        if (!normalizedMsgId.isEmpty()) {
            body.put("msg_id", normalizedMsgId);
            if (msgSeq != null) {
                body.put("msg_seq", msgSeq);
            }
        } else if (!normalizedEventId.isEmpty()) {
            body.put("event_id", normalizedEventId);
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException failure) {
            throw new IllegalArgumentException("无法构造官方文本消息请求", failure);
        }
    }

    private static String textId(String raw, String field) {
        String value = raw == null ? "" : raw;
        if (!value.isEmpty() && !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " 不能含首尾空白");
        }
        if (value.length() > MAX_OPENID_LENGTH) {
            throw new IllegalArgumentException(field + " 过长");
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException(field + " 含非法字符");
            }
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    private JsonNode execute(OfficialQqEndpoint endpoint, String path, String jsonBody)
            throws OfficialQqApiException {
        String authorization = tokenManager.authorizationHeader();
        RawResponse first = send(endpoint, path, jsonBody, authorization);
        try {
            return interpret(endpoint, first, authorization);
        } catch (OfficialQqApiException firstFailure) {
            if (!firstFailure.tokenRejected() || !endpoint.safeToRepeatAfterTokenRefresh()) {
                throw firstFailure;
            }
            // This is the only retry in the entire client. A second failure is returned as-is.
            String replacement = tokenManager.authorizationHeaderAfterRejected(authorization);
            RawResponse retried = send(endpoint, path, jsonBody, replacement);
            return interpret(endpoint, retried, replacement);
        }
    }

    private RawResponse send(
            OfficialQqEndpoint endpoint, String path, String jsonBody, String authorization)
            throws OfficialQqApiException {
        long startedNanos = System.nanoTime();
        URI uri = endpoint(baseUri, path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .header("User-Agent", "bma3-qq-official-connect");
        if (endpoint.method().equals("GET")) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        }
        HttpRequest request = builder.build();

        try {
            OfficialQqHttpTransport.Response response = OfficialQqHttpTransport.send(
                    httpClient, request, requestTimeout, MAX_RESPONSE_BYTES);
            return new RawResponse(
                    response.statusCode(), response.headers(), response.body(), startedNanos);
        } catch (OfficialQqHttpTransport.DeadlineExceededException timeout) {
            publish(endpoint, startedNanos, -1, "", "", false);
            throw failure(
                    OfficialQqErrorKind.TIMEOUT,
                    -1,
                    endpoint,
                    "",
                    "",
                    true,
                    "官方 API 请求超时",
                    timeout);
        } catch (OfficialQqResponseBody.TooLargeException tooLarge) {
            publish(endpoint, startedNanos, -1, "", "", false);
            throw failure(
                    OfficialQqErrorKind.MALFORMED_RESPONSE,
                    -1,
                    endpoint,
                    "",
                    "",
                    false,
                    "响应体超过上限",
                    tooLarge);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            publish(endpoint, startedNanos, -1, "", "", false);
            throw failure(
                    OfficialQqErrorKind.NETWORK,
                    -1,
                    endpoint,
                    "",
                    "",
                    true,
                    "官方 API 请求被中断",
                    interrupted);
        } catch (IOException network) {
            publish(endpoint, startedNanos, -1, "", "", false);
            throw failure(
                    OfficialQqErrorKind.NETWORK,
                    -1,
                    endpoint,
                    "",
                    "",
                    true,
                    "官方 API 网络失败",
                    network);
        }
    }

    private JsonNode interpret(
            OfficialQqEndpoint endpoint, RawResponse raw, String authorization)
            throws OfficialQqApiException {
        int status = raw.status();
        byte[] bytes = raw.body();
        JsonNode body = parseJson(bytes);
        String errorCode = errorCode(body);
        String traceId = traceId(raw.headers(), body, authorization);
        if (status < 200 || status >= 300) {
            OfficialQqErrorKind kind = kindForHttpStatus(status, errorCode);
            publish(endpoint, raw.startedNanos(), status, errorCode, traceId, false);
            throw failure(
                    kind,
                    status,
                    endpoint,
                    errorCode,
                    traceId,
                    kind.potentiallyRetryable(),
                    "官方 API 返回 HTTP 错误",
                    null);
        }
        if (errorCode != null) {
            OfficialQqErrorKind kind = kindForBusinessCode(errorCode);
            publish(endpoint, raw.startedNanos(), status, errorCode, traceId, false);
            throw failure(
                    kind,
                    status,
                    endpoint,
                    errorCode,
                    traceId,
                    kind.potentiallyRetryable(),
                    "官方 API 返回业务错误",
                    null);
        }
        if (body == null || !body.isObject()) {
            publish(endpoint, raw.startedNanos(), status, "", traceId, false);
            throw failure(
                    OfficialQqErrorKind.MALFORMED_RESPONSE,
                    status,
                    endpoint,
                    "",
                    traceId,
                    false,
                    "响应不是 JSON 对象",
                    null);
        }
        publish(endpoint, raw.startedNanos(), status, "", traceId, true);
        return body;
    }

    private OfficialQqApiException failure(
            OfficialQqErrorKind kind,
            int status,
            OfficialQqEndpoint endpoint,
            String errorCode,
            String traceId,
            boolean retryable,
            String detail,
            Throwable cause) {
        return new OfficialQqApiException(
                kind,
                status,
                accountId,
                endpoint.category(),
                errorCode,
                traceId,
                retryable,
                detail,
                cause);
    }

    private void publish(
            OfficialQqEndpoint endpoint,
            long startedNanos,
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
                    "qqofficial.observer.failed event=api type={}",
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

    private static String traceId(
            HttpHeaders headers, JsonNode body, String authorization) {
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
        String token = "";
        if (authorization != null) {
            int space = authorization.indexOf(' ');
            token = space >= 0 ? authorization.substring(space + 1).strip() : authorization.strip();
        }
        return OfficialQqApiException.safe(
                OfficialQqApiException.redact(raw, authorization, token), 160);
    }

    private static OfficialQqErrorKind kindForHttpStatus(int status, String errorCode) {
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
        OfficialQqErrorKind business = kindForBusinessCode(errorCode);
        return business == OfficialQqErrorKind.BUSINESS
                ? OfficialQqErrorKind.HTTP
                : business;
    }

    private static OfficialQqErrorKind kindForBusinessCode(String code) {
        if (code == null) {
            return OfficialQqErrorKind.BUSINESS;
        }
        return switch (code) {
            case "401", "11241", "11242" -> OfficialQqErrorKind.AUTHENTICATION;
            case "403", "11253", "4014", "4914", "4915" -> OfficialQqErrorKind.PERMISSION;
            case "404" -> OfficialQqErrorKind.NOT_FOUND;
            case "429", "11257" -> OfficialQqErrorKind.RATE_LIMITED;
            default -> OfficialQqErrorKind.BUSINESS;
        };
    }

    private static String pathWithId(OfficialQqEndpoint endpoint, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(endpoint.category() + " 的 openid 不能为空");
        }
        if (id.codePointCount(0, id.length()) > MAX_OPENID_LENGTH) {
            throw new IllegalArgumentException(endpoint.category() + " 的 openid 过长");
        }
        for (int offset = 0; offset < id.length(); ) {
            int codePoint = id.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException(endpoint.category() + " 的 openid 含控制字符");
            }
            offset += Character.charCount(codePoint);
        }
        for (String segment : id.split("[/\\\\]", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(endpoint.category() + " 的 openid 含点段");
            }
        }
        String encoded = URLEncoder.encode(id, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%7E", "~");
        return endpoint.pathTemplate().formatted(encoded);
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

    private record RawResponse(int status, HttpHeaders headers, byte[] body, long startedNanos) {
    }
}
