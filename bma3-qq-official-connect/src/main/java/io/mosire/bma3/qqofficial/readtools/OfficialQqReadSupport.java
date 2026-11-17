package io.mosire.bma3.qqofficial.readtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.ThreadSummary;
import io.mosire.bma3.qqofficial.account.OfficialQqAccountStatus;
import io.mosire.bma3.qqofficial.api.OfficialQqApiException;
import io.mosire.bma3.qqofficial.api.OfficialQqErrorKind;
import io.mosire.bma3.qqofficial.directory.OfficialQqDirectoryStore;
import io.mosire.bma3.qqofficial.event.OfficialQqSecretRedactor;
import io.mosire.bma3.qqofficial.tools.QqOfficialSourceKeyer;
import java.util.Objects;
import java.util.regex.Pattern;

/** Shared validation, safe JSON and result folding for official read tools. */
final class OfficialQqReadSupport {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String LOCAL_SOURCE = "local";
    static final String PLATFORM_COMPLETE_FALSE_HINT =
            "QQ 官方未提供机器人可读取的平台全量群列表；这里仅返回 BMA3 本地观察/别名。";
    static final String LOCAL_HISTORY_HINT =
            "QQ 官方未提供首版所需的平台历史消息读取能力；这里只返回 BMA3 消息板中的本地事实。";

    private static final Pattern SAFE_TYPE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern SAFE_JSON_KEY = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final int MAX_SOURCE_SEGMENT_CODE_POINTS = 512;
    private static final int MAX_SEGMENT_DATA_VALUE_CHARS = 16_384;
    private static final int MAX_API_TEXT_CHARS = 4_096;
    private static final int MAX_API_DEPTH = 8;
    private static final int MAX_API_NODES = 512;
    private static final int MAX_API_ARRAY = 100;

    private OfficialQqReadSupport() {
    }

    @FunctionalInterface
    interface ReadAction {
        String run() throws OfficialQqReadException;
    }

    static ToolResult execute(ReadAction action) {
        try {
            return ToolResult.ok(action.run());
        } catch (OfficialQqReadException failure) {
            return ToolResult.error(failure.code().name(), failure.safeMessage());
        } catch (RuntimeException failure) {
            // Never surface database/parser/third-party exception text.
            return ToolResult.error("INTERNAL_ERROR", "官方只读工具发生内部错误");
        }
    }

    static SourceAddress officialSource(ToolContext context) {
        Object raw = context.arguments().get("source");
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new OfficialQqReadException(OfficialQqReadException.Code.INVALID_ARGUMENT,
                    "source 必须是非空字符串");
        }
        SourceAddress source;
        try {
            source = SourceAddress.parse(value);
        } catch (RuntimeException failure) {
            throw new OfficialQqReadException(OfficialQqReadException.Code.INVALID_SOURCE,
                    "source 格式无效");
        }
        validateOfficialSource(source);
        return source;
    }

    static SourceAddress officialGroupSource(ToolContext context) {
        SourceAddress source = officialSource(context);
        if (!QqOfficialSourceKeyer.GROUP_PLATFORM.equals(source.platform())) {
            throw new OfficialQqReadException(OfficialQqReadException.Code.INVALID_SOURCE,
                    "该工具要求 qq_official_group/<accountId>/<group_openid> source");
        }
        return source;
    }

    static SourceAddress officialPrivateSource(ToolContext context) {
        SourceAddress source = officialSource(context);
        if (!QqOfficialSourceKeyer.PRIVATE_PLATFORM.equals(source.platform())) {
            throw new OfficialQqReadException(OfficialQqReadException.Code.INVALID_SOURCE,
                    "该工具要求 qq_official_private/<accountId>/<user_openid> source");
        }
        return source;
    }

    static void validateOfficialSource(SourceAddress source) {
        Objects.requireNonNull(source, "source");
        if (!QqOfficialSourceKeyer.supportsPlatform(source.platform())
                || QqOfficialSourceKeyer.SYSTEM_PLATFORM.equals(source.platform())
                || source.segments().size() != 2) {
            throw new OfficialQqReadException(OfficialQqReadException.Code.INVALID_SOURCE,
                    "只接受官方群/单聊 raw source");
        }
        requireSafeSegment(source.accountId(), "accountId");
        requireSafeSegment(source.lastSegment(), "targetOpenid");
    }

    static String requireSafeSegment(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 1024
                || !value.equals(value.strip())) {
            throw new OfficialQqReadException(OfficialQqReadException.Code.INVALID_SOURCE,
                    field + " 必须是安全单段");
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints > MAX_SOURCE_SEGMENT_CODE_POINTS) {
            throw new OfficialQqReadException(OfficialQqReadException.Code.INVALID_SOURCE,
                    field + " 必须是安全单段");
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
                throw new OfficialQqReadException(OfficialQqReadException.Code.INVALID_SOURCE,
                        field + " 必须是安全单段");
            }
            index += Character.charCount(codePoint);
        }
        return value;
    }

    static int integer(ToolContext context, String name, int fallback) {
        Object raw = context.arguments().get(name);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String value && !value.isBlank()) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException failure) {
                throw invalid(name + " 必须是整数");
            }
        }
        throw invalid(name + " 必须是整数");
    }

    static long nonNegativeLong(ToolContext context, String name, long fallback) {
        Object raw = context.arguments().get(name);
        if (raw == null) {
            return fallback;
        }
        long value;
        if (raw instanceof Number number) {
            value = number.longValue();
        } else if (raw instanceof String text && !text.isBlank()) {
            try {
                value = Long.parseLong(text);
            } catch (NumberFormatException failure) {
                throw invalid(name + " 必须是非负整数");
            }
        } else {
            throw invalid(name + " 必须是非负整数");
        }
        if (value < 0L) {
            throw invalid(name + " 必须是非负整数");
        }
        return value;
    }

    static long positiveLong(ToolContext context, String name) {
        long value = nonNegativeLong(context, name, -1L);
        if (value <= 0L) {
            throw invalid(name + " 必须是正整数");
        }
        return value;
    }

    static boolean flag(ToolContext context, String name) {
        Object raw = context.arguments().get(name);
        if (raw == null) {
            return false;
        }
        if (!(raw instanceof Boolean value)) {
            throw invalid(name + " 必须是 boolean");
        }
        return value;
    }

    static int boundedLimit(ToolContext context, int fallback, int max) {
        int limit = integer(context, "limit", fallback);
        if (limit < 1 || limit > max) {
            throw invalid("limit 必须在 1.." + max);
        }
        return limit;
    }

    static OfficialQqReadApi api(OfficialQqReadContext context, String accountId,
                                 OfficialQqReadException.Code permissionCode)
            throws OfficialQqReadException {
        try {
            OfficialQqReadApi api = context.api().forAccount(accountId);
            if (api == null) {
                throw new OfficialQqReadException(
                        OfficialQqReadException.Code.CAPABILITY_UNAVAILABLE,
                        "官方读 API 当前不可用");
            }
            return api;
        } catch (OfficialQqApiException failure) {
            throw mapApiFailure(failure, permissionCode);
        }
    }

    static OfficialQqReadException mapApiFailure(OfficialQqApiException failure,
                                                  OfficialQqReadException.Code permissionCode) {
        OfficialQqErrorKind kind = failure == null || failure.kind() == null
                ? OfficialQqErrorKind.BUSINESS : failure.kind();
        OfficialQqReadException.Code code = switch (kind) {
            case PERMISSION -> permissionCode;
            case NOT_FOUND -> OfficialQqReadException.Code.ITEM_NOT_FOUND;
            case AUTHENTICATION -> OfficialQqReadException.Code.AUTHENTICATION_FAILED;
            case RATE_LIMITED -> OfficialQqReadException.Code.RATE_LIMITED;
            case NETWORK, TIMEOUT, HTTP, SERVER -> OfficialQqReadException.Code.UPSTREAM_UNAVAILABLE;
            case MALFORMED_RESPONSE -> OfficialQqReadException.Code.MALFORMED_RESPONSE;
            case CONFIGURATION -> OfficialQqReadException.Code.CAPABILITY_UNAVAILABLE;
            case BUSINESS -> OfficialQqReadException.Code.CAPABILITY_UNAVAILABLE;
        };
        String message = switch (code) {
            case CAPABILITY_UNAVAILABLE -> "官方接口/能力当前不可用或该账号无权限";
            case PERMISSION_DENIED -> "官方接口权限不足";
            case NOT_FRIEND -> "官方未返回该用户信息（可能没有好友/可见关系）";
            case ITEM_NOT_FOUND -> "官方未发现指定对象";
            case AUTHENTICATION_FAILED -> "官方账号认证失败";
            case RATE_LIMITED -> "官方接口触发限流，请稍后重试";
            case UPSTREAM_UNAVAILABLE -> "官方接口暂时不可用";
            case MALFORMED_RESPONSE -> "官方接口返回了无法安全解析的响应";
            default -> "官方接口调用失败";
        };
        return new OfficialQqReadException(code, message);
    }

    static ObjectNode apiPayload(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            throw new OfficialQqReadException(
                    OfficialQqReadException.Code.MALFORMED_RESPONSE,
                    "官方接口返回了空响应");
        }
        JsonNode data = root;
        if (root.isObject() && root.has("data")) {
            data = root.get("data");
        }
        if (data == null || data.isNull() || data.isMissingNode() || !data.isObject()) {
            throw new OfficialQqReadException(
                    OfficialQqReadException.Code.MALFORMED_RESPONSE,
                    "官方接口返回了无法安全解析的响应");
        }
        return sanitizeNode(data).deepCopy();
    }

    static JsonNode sanitizeNode(JsonNode node) {
        return sanitizeNode(node, 0, new int[] {MAX_API_NODES});
    }

    private static JsonNode sanitizeNode(JsonNode node, int depth, int[] remainingNodes) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return JSON.nullNode();
        }
        if (depth > MAX_API_DEPTH || remainingNodes[0]-- <= 0) {
            return JSON.getNodeFactory().textNode("[TRUNCATED]");
        }
        if (node.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            var fields = node.properties().iterator();
            while (fields.hasNext() && remainingNodes[0] > 0) {
                var field = fields.next();
                String key = field.getKey();
                if (key == null || !SAFE_JSON_KEY.matcher(key).matches()
                        || OfficialQqSecretRedactor.sensitiveKey(key)) {
                    continue;
                }
                out.set(key, sanitizeNode(field.getValue(), depth + 1, remainingNodes));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            int count = 0;
            for (JsonNode child : node) {
                if (count++ >= MAX_API_ARRAY || remainingNodes[0] <= 0) {
                    break;
                }
                out.add(sanitizeNode(child, depth + 1, remainingNodes));
            }
            return out;
        }
        if (node.isTextual()) {
            return JSON.getNodeFactory().textNode(safeText(node.asText(""), MAX_API_TEXT_CHARS));
        }
        return node.deepCopy();
    }

    static ObjectNode messageNode(Message message, boolean includeSegments) {
        ObjectNode node = JSON.createObjectNode();
        node.put("seq", message.localSeq());
        node.put("messageId", safeText(message.inbound().messageId(), 512));
        node.put("externalMessageId", safeText(message.externalMessageId(), 512));
        node.put("at", message.inbound().at().toString());
        node.put("direction", message.inbound().direction().name());
        node.put("self", message.self());
        ObjectNode sender = node.putObject("sender");
        sender.put("id", safeText(message.inbound().senderId(), 512));
        sender.put("name", safeText(message.inbound().senderName(), 256));
        sender.put("card", safeText(message.inbound().senderCard(), 256));
        sender.put("role", safeText(message.inbound().senderRole(), 128));
        node.put("text", OfficialQqSecretRedactor.redactText(messageText(message)));
        if (message.delivery() != null) {
            ObjectNode delivery = node.putObject("delivery");
            delivery.put("status", message.delivery().status().name());
            delivery.put("operationId", safeText(message.delivery().operationId(), 256));
            delivery.put("partIndex", message.delivery().partIndex());
            delivery.put("partTotal", message.delivery().partTotal());
            if (message.delivery().errorCode() != null) {
                delivery.put("errorCode", safeText(message.delivery().errorCode(), 128));
            }
        }
        if (includeSegments) {
            ArrayNode segments = node.putArray("segments");
            for (Segment segment : message.inbound().segments()) {
                if (segment == null) {
                    continue;
                }
                ObjectNode item = segments.addObject();
                item.put("type", safeType(segment.type()));
                item.put("text", safeText(segment.text(), MAX_SEGMENT_DATA_VALUE_CHARS));
                ObjectNode data = item.putObject("data");
                segment.data().forEach((key, value) -> {
                    if (key == null || value == null || value.isBlank()
                            || !SAFE_JSON_KEY.matcher(key).matches()
                            || OfficialQqSecretRedactor.sensitiveKey(key)) {
                        return;
                    }
                    data.put(key, safeText(value, MAX_SEGMENT_DATA_VALUE_CHARS));
                });
            }
        }
        return node;
    }

    static ObjectNode threadNode(ThreadSummary thread, OfficialQqDirectoryStore directory) {
        SourceAddress source;
        try {
            source = SourceAddress.parse(thread.source());
            validateOfficialSource(source);
        } catch (RuntimeException invalid) {
            return null;
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("source", source.format());
        node.put("accountId", source.accountId());
        node.put("platform", source.platform());
        node.put("kind", QqOfficialSourceKeyer.GROUP_PLATFORM.equals(source.platform())
                ? "GROUP" : "PRIVATE");
        node.put("targetOpenid", source.lastSegment());
        node.put("label", sourceLabel(source, directory));
        node.put("lastSeq", thread.lastSeq());
        node.put("lastAt", thread.lastAt().toString());
        node.put("messageCount", thread.messageCount());
        return node;
    }

    static String sourceLabel(SourceAddress source, OfficialQqDirectoryStore directory) {
        if (directory != null) {
            try {
                if (QqOfficialSourceKeyer.GROUP_PLATFORM.equals(source.platform())) {
                    String label = directory.group(source.accountId(), source.lastSegment())
                            .map(OfficialQqDirectoryStore.Group::label)
                            .orElse("");
                    if (!label.isBlank()) {
                        return safeText(label, 160);
                    }
                } else if (QqOfficialSourceKeyer.PRIVATE_PLATFORM.equals(source.platform())) {
                    String label = directory.user(source.accountId(), source.lastSegment())
                            .map(OfficialQqDirectoryStore.User::label)
                            .orElse("");
                    if (!label.isBlank()) {
                        return safeText(label, 160);
                    }
                }
            } catch (RuntimeException ignored) {
                // Label lookup is best effort; the raw source remains authoritative.
            }
        }
        return (QqOfficialSourceKeyer.GROUP_PLATFORM.equals(source.platform())
                ? "QQ群 " : "QQ私聊 ") + shortId(source.lastSegment());
    }

    static ObjectNode statusNode(OfficialQqAccountStatus status) {
        ObjectNode node = JSON.createObjectNode();
        node.put("accountId", status.accountId());
        node.put("state", status.state().name());
        node.put("enabled", status.enabled());
        node.put("connected", status.connected());
        node.put("terminal", status.terminal());
        node.put("shardIndex", status.shardIndex());
        node.put("shardCount", status.shardCount());
        node.put("updatedAt", status.updatedAt().toString());
        if (status.lastConnectedAt() != null) {
            node.put("lastConnectedAt", status.lastConnectedAt().toString());
        }
        if (status.lastEventAt() != null) {
            node.put("lastEventAt", status.lastEventAt().toString());
        }
        if (status.lastSequence() != null) {
            node.put("lastSequence", status.lastSequence());
        }
        node.put("reconnectAttempts", status.reconnectAttempts());
        node.put("errorKind", status.errorKind().name());
        node.put("errorCode", status.errorCode());
        node.put("errorType", status.errorType());
        node.put("traceId", status.traceId());
        return node;
    }

    static OfficialQqReadException invalid(String message) {
        return new OfficialQqReadException(OfficialQqReadException.Code.INVALID_ARGUMENT,
                message);
    }

    static String messageText(Message message) {
        StringBuilder text = new StringBuilder();
        for (Segment segment : message.inbound().segments()) {
            if (segment == null || segment.text() == null || segment.text().isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(segment.text());
        }
        return text.toString();
    }

    static String safeText(String value, int maxChars) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String redacted = OfficialQqSecretRedactor.redactText(value);
        StringBuilder out = new StringBuilder(Math.min(redacted.length(), maxChars));
        for (int index = 0; index < redacted.length() && out.length() < maxChars; ) {
            int codePoint = redacted.codePointAt(index);
            int type = Character.getType(codePoint);
            boolean unsafe = Character.isISOControl(codePoint)
                    || type == Character.FORMAT
                    || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR;
            if (!unsafe) {
                out.appendCodePoint(codePoint);
            }
            index += Character.charCount(codePoint);
        }
        return out.toString().strip();
    }

    private static String safeType(String value) {
        return value != null && SAFE_TYPE.matcher(value).matches() ? value : "unknown";
    }

    private static String shortId(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        int count = id.codePointCount(0, id.length());
        if (count <= 8) {
            return id;
        }
        return id.substring(0, id.offsetByCodePoints(0, 8)) + "…";
    }
}
