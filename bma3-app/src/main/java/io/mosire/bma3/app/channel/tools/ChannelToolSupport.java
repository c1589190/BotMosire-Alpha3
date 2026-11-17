package io.mosire.bma3.app.channel.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.app.channel.QqChannelRegistry;
import io.mosire.bma3.app.channel.QqChannelRegistry.Channel;
import io.mosire.bma3.app.channel.QqChannelRegistry.ChannelBinding;
import io.mosire.bma3.app.channel.QqChannelRegistry.ChannelIdentityBinding;
import io.mosire.bma3.app.channel.QqChannelRegistry.DisplayChoice;
import io.mosire.bma3.app.channel.QqChannelRegistry.ErrorCode;
import io.mosire.bma3.app.channel.QqChannelRegistry.IdentityScope;
import io.mosire.bma3.app.channel.QqChannelRegistry.LogicalThread;
import io.mosire.bma3.app.channel.QqChannelRegistry.MatchingMode;
import io.mosire.bma3.app.channel.QqChannelRegistry.RegistryException;
import io.mosire.bma3.app.channel.QqChannelRegistry.SourceGate;
import io.mosire.bma3.app.channel.binding.BindingCoordinatorException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** 渠道 AgentTool 的参数校验、固定错误映射与 JSON 视图。 */
final class ChannelToolSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    static final String INVALID_CHANNEL = "INVALID_CHANNEL";
    static final String INVALID_SCOPE = "INVALID_SCOPE";
    static final String SOURCE_CATALOG_INVALID = "SOURCE_CATALOG_INVALID";
    static final String SOURCE_CATALOG_UNAVAILABLE = "SOURCE_CATALOG_UNAVAILABLE";
    static final String SERIALIZATION_FAILED = "SERIALIZATION_FAILED";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    static final String THREAD_NOT_FOUND = "THREAD_NOT_FOUND";

    private ChannelToolSupport() {
    }

    static ToolResult execute(Supplier<ToolResult> action) {
        try {
            return action.get();
        } catch (ToolFailure failure) {
            return ToolResult.error(failure.code, failure.getMessage());
        } catch (RegistryException failure) {
            return registryFailure(failure.code());
        } catch (BindingCoordinatorException failure) {
            return coordinatorFailure(failure.code());
        } catch (RuntimeException failure) {
            return ToolResult.error(INTERNAL_ERROR, "渠道控制操作失败");
        }
    }

    static ToolFailure invalid(String message) {
        return new ToolFailure(INVALID_ARGUMENT, message);
    }

    static ToolFailure failure(String code, String message) {
        return new ToolFailure(code, message);
    }

    static ToolResult registryFailure(ErrorCode code) {
        String message = switch (code) {
            case INVALID_THREAD_ID -> "threadId 格式非法";
            case INVALID_RAW_SOURCE -> "raw source 格式非法；必须是受支持的 platform/account/id 三段格式";
            case INVALID_ACCOUNT_ID -> "账号 ID 格式非法";
            case INVALID_LABEL -> "label 格式非法";
            case INVALID_IDENTITY -> "身份映射参数格式非法";
            case INVALID_MATCHING_MODE -> "matching mode 非法";
            case CHANNEL_MISMATCH -> "显式 channel 与 raw source 前缀不一致";
            case ACCOUNT_MISMATCH -> "channel/account 与已有绑定冲突";
            case ACCOUNT_UNKNOWN -> "渠道账号未知，拒绝操作";
            case ACCOUNT_VERIFIER_FAILED -> "账号校验暂不可用，操作已拒绝";
            case OBSERVATION_UNAVAILABLE -> "来源观察状态暂不可用，操作已拒绝";
            case THREAD_NOT_FOUND -> "logical thread 不存在";
            case SOURCE_NOT_BOUND -> "raw source 未绑定到该 logical thread";
            case SOURCE_ALREADY_BOUND -> "raw source 已绑定到另一个 logical thread";
            case THREAD_CHANNEL_NOT_BOUND -> "该 logical thread 尚未绑定该渠道";
            case THREAD_CHANNEL_ALREADY_BOUND -> "该 logical thread 的该渠道已有 active binding";
            case IDENTITY_CONFLICT -> "身份映射与已有映射冲突";
            case IDENTITY_SCOPE_MISMATCH -> "身份映射 scope 与 thread/channel/source 不匹配";
            case STORAGE_ERROR -> "通道 registry 暂不可用，操作已拒绝";
        };
        return ToolResult.error(code.name(), message);
    }

    /** coordinator/blocker 只映射固定错误码与固定安全文案，不回显任意异常字符串。 */
    static ToolResult coordinatorFailure(BindingCoordinatorException.Code code) {
        String resultCode = "BINDING_COORDINATOR_FAILED";
        String message = "binding coordinator 操作失败";
        switch (code) {
            case INVALID_ARGUMENT -> {
                resultCode = INVALID_ARGUMENT;
                message = "binding coordinator 参数非法";
            }
            case STORAGE_ERROR -> {
                resultCode = "BINDING_STORAGE_UNAVAILABLE";
                message = "binding coordinator 存储暂不可用";
            }
            case JOURNAL_FAILED -> {
                resultCode = "BINDING_JOURNAL_FAILED";
                message = "binding journal 写入失败，操作未确认";
            }
            case BOARD_WATERMARK_UNAVAILABLE -> {
                resultCode = "BINDING_BOARD_WATERMARK_UNAVAILABLE";
                message = "无法读取 board 水位，绑定未执行";
            }
            case SOURCE_ALREADY_BOUND -> {
                resultCode = "SOURCE_ALREADY_BOUND";
                message = "raw source 已绑定到另一个 logical thread";
            }
            case THREAD_CHANNEL_ALREADY_BOUND -> {
                resultCode = "THREAD_CHANNEL_ALREADY_BOUND";
                message = "该 logical thread 的该渠道已有 active binding";
            }
            case LEDGER_BIND_FAILED -> {
                resultCode = "LEDGER_BIND_FAILED";
                message = "ledger 绑定失败，操作已拒绝";
            }
            case REGISTRY_BIND_FAILED -> {
                resultCode = "REGISTRY_BIND_FAILED";
                message = "registry 绑定失败，操作已拒绝";
            }
            case BIND_COMPENSATION_FAILED -> {
                resultCode = "BIND_COMPENSATION_FAILED";
                message = "绑定补偿失败，请先运行 coordinator repair";
            }
            case BIND_RECOVERY_REQUIRED -> {
                resultCode = "BIND_RECOVERY_REQUIRED";
                message = "绑定结果未确认，请先运行 coordinator repair";
            }
            case UNBIND_RECOVERY_REQUIRED -> {
                resultCode = "UNBIND_RECOVERY_REQUIRED";
                message = "解绑结果未确认，请先运行 coordinator repair";
            }
            case REGISTRY_ACTIVE_LEDGER_MISSING, REGISTRY_ACTIVE_LEDGER_INACTIVE,
                 REGISTRY_LEDGER_TUPLE_CONFLICT, LEDGER_HISTORY_UNPROVABLE -> {
                resultCode = "BINDING_LEDGER_BLOCKED";
                message = "registry/ledger 状态不可证明，操作已拒绝";
            }
            case SOURCE_ENUMERATION_FAILED -> {
                resultCode = "BINDING_SOURCE_ENUMERATION_FAILED";
                message = "binding source 枚举失败";
            }
            case REPAIR_BLOCKED -> {
                resultCode = "BINDING_REPAIR_BLOCKED";
                message = "binding repair 未就绪，操作已拒绝";
            }
            default -> {
                resultCode = "BINDING_COORDINATOR_FAILED";
                message = "binding coordinator 操作失败";
            }
        }
        return ToolResult.error(resultCode, message);
    }

    static Map<String, Object> arguments(ToolContext toolContext) {
        Map<String, Object> arguments = toolContext.arguments();
        if (arguments == null) {
            throw invalid("参数对象不能为空");
        }
        return arguments;
    }

    static void requireOnly(ToolContext toolContext, String... allowed) {
        Set<String> accepted = Set.of(allowed);
        for (String key : arguments(toolContext).keySet()) {
            if (!accepted.contains(key)) {
                throw invalid("参数不受支持；请只使用 schema 中声明的字段");
            }
        }
    }

    static String requiredString(ToolContext toolContext, String name) {
        Object raw = arguments(toolContext).get(name);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw invalid("必填字符串参数缺失或格式非法");
        }
        return value;
    }

    static String optionalString(ToolContext toolContext, String name) {
        Object raw = arguments(toolContext).get(name);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value) || value.isBlank()) {
            throw invalid("可选字符串参数格式非法");
        }
        return value;
    }

    static boolean requiredBoolean(ToolContext toolContext, String name) {
        Object raw = arguments(toolContext).get(name);
        if (!(raw instanceof Boolean value)) {
            throw invalid("必填布尔参数缺失或类型非法");
        }
        return value;
    }

    static int optionalInt(ToolContext toolContext, String name, int defaultValue, int min, int max) {
        Object raw = arguments(toolContext).get(name);
        if (raw == null) {
            return defaultValue;
        }
        long value;
        if (raw instanceof Integer integer) {
            value = integer;
        } else if (raw instanceof Long length) {
            value = length;
        } else {
            throw invalid("可选整数参数类型非法");
        }
        if (value < min || value > max) {
            throw invalid("可选整数参数超出允许范围");
        }
        return Math.toIntExact(value);
    }

    static Channel requiredChannel(ToolContext toolContext, String name) {
        String value = requiredString(toolContext, name);
        try {
            return Channel.fromWire(value);
        } catch (IllegalArgumentException failure) {
            throw failure(INVALID_CHANNEL, "channel 只允许 onebot 或 qq_official");
        }
    }

    static Channel optionalChannel(ToolContext toolContext, String name) {
        String value = optionalString(toolContext, name);
        if (value == null) {
            return null;
        }
        try {
            return Channel.fromWire(value);
        } catch (IllegalArgumentException failure) {
            throw failure(INVALID_CHANNEL, "channel 只允许 onebot 或 qq_official");
        }
    }

    /**
     * 在调用 coordinator 前做显式 channel 校验。registry.sourceGate 只读解析 raw source
     * 平台/账号/目标，不写绑定；不匹配时 coordinator 完全不会被调用。
     */
    static void requireChannelMatchesRawSource(QqChannelRegistry registry,
                                               String rawSource,
                                               Channel expectedChannel) {
        if (expectedChannel == null) {
            return;
        }
        registry.sourceGate(rawSource);
        String[] parts = rawSource.split("/", -1);
        Channel actual = switch (parts[0]) {
            case "qq_group", "qq_private", "qq_system" -> Channel.ONEBOT;
            case "qq_official_group", "qq_official_private", "qq_official_system" ->
                    Channel.QQ_OFFICIAL;
            default -> throw failure("INVALID_RAW_SOURCE", "raw source 平台非法");
        };
        if (actual != expectedChannel) {
            throw failure("CHANNEL_MISMATCH", "显式 channel 与 raw source 前缀不一致");
        }
    }

    static MatchingMode requiredMatchingMode(ToolContext toolContext, String name) {
        String value = requiredString(toolContext, name);
        try {
            return MatchingMode.fromWire(value);
        } catch (IllegalArgumentException failure) {
            throw invalid("matchingMode 只允许 strict、loose 或 off");
        }
    }

    static IdentityScope requiredScope(ToolContext toolContext) {
        Object raw = arguments(toolContext).get("scope");
        if (!(raw instanceof Map<?, ?> scope)) {
            throw failure(INVALID_SCOPE, "scope 必须是 {type, value} 对象");
        }
        if (!scope.keySet().equals(Set.of("type", "value"))) {
            throw failure(INVALID_SCOPE, "scope 只允许 type 与 value 两个字段");
        }
        if (!(scope.get("type") instanceof String type)
                || !(scope.get("value") instanceof String value)
                || type.isBlank() || value.isBlank()) {
            throw failure(INVALID_SCOPE, "scope.type 与 scope.value 必须是非空字符串");
        }
        return switch (type) {
            case "thread" -> IdentityScope.thread(value);
            case "source" -> IdentityScope.source(value);
            default -> throw failure(INVALID_SCOPE, "scope.type 只允许 thread 或 source");
        };
    }

    static ObjectNode object() {
        return JSON.createObjectNode();
    }

    static ArrayNode array() {
        return JSON.createArrayNode();
    }

    static ToolResult ok(ObjectNode node) {
        try {
            return ToolResult.ok(JSON.writeValueAsString(node));
        } catch (Exception failure) {
            return ToolResult.error(SERIALIZATION_FAILED, "无法序列化渠道控制结果");
        }
    }

    static ObjectNode threadNode(LogicalThread thread) {
        ObjectNode node = object();
        node.put("threadId", thread.threadId());
        node.put("label", thread.label());
        node.put("preferredChannel", thread.preferredChannel() == null
                ? null : thread.preferredChannel().wireName());
        node.put("matchingMode", thread.matchingMode().wireName());
        node.put("injectionEnabled", thread.injectionEnabled());
        node.put("revision", thread.revision());
        node.put("createdAt", thread.createdAt().toString());
        node.put("updatedAt", thread.updatedAt().toString());
        return node;
    }

    static ObjectNode bindingNode(ChannelBinding binding) {
        ObjectNode node = object();
        node.put("rawSource", binding.rawSource());
        node.put("threadId", binding.threadId());
        node.put("channel", binding.channel().wireName());
        node.put("accountId", binding.accountId());
        node.put("injectionEnabled", binding.injectionEnabled());
        node.put("state", binding.state().name());
        node.put("active", binding.active());
        node.put("revision", binding.revision());
        node.put("createdAt", binding.createdAt().toString());
        node.put("updatedAt", binding.updatedAt().toString());
        return node;
    }

    static ObjectNode identityNode(ChannelIdentityBinding identity) {
        ObjectNode node = object();
        node.put("threadId", identity.threadId());
        node.put("channel", identity.channel().wireName());
        node.put("channelUserId", identity.channelUserId());
        node.put("canonicalUserId", identity.canonicalUserId());
        node.put("scopeType", identity.scope().type() == IdentityScope.Type.THREAD
                ? "thread" : "source");
        node.put("scopeValue", identity.scope().value());
        node.put("active", identity.active());
        node.put("revision", identity.revision());
        node.put("createdAt", identity.createdAt().toString());
        node.put("updatedAt", identity.updatedAt().toString());
        return node;
    }

    static ObjectNode sourceGateNode(SourceGate gate) {
        ObjectNode node = object();
        node.put("rawSource", gate.rawSource());
        node.put("injectionEnabled", gate.injectionEnabled());
        node.put("revision", gate.revision());
        node.put("createdAt", gate.createdAt().toString());
        node.put("updatedAt", gate.updatedAt().toString());
        return node;
    }

    static ObjectNode displayNode(DisplayChoice display) {
        if (display == null) {
            return null;
        }
        ObjectNode node = object();
        node.put("channel", display.channel().wireName());
        node.put("preferred", display.preferred());
        node.put("fallback", display.fallback());
        ArrayNode enabled = node.putArray("enabledChannels");
        for (Channel channel : display.enabledChannels()) {
            enabled.add(channel.wireName());
        }
        return node;
    }

    static ObjectNode consistencyNode() {
        ObjectNode node = object();
        node.put("available", false);
        node.put("reason", "phase10_not_wired");
        return node;
    }

    static String sourceChannel(String rawSource) {
        String platform = sourceParts(rawSource)[0];
        return switch (platform) {
            case "qq_group", "qq_private", "qq_system" -> Channel.ONEBOT.wireName();
            case "qq_official_group", "qq_official_private", "qq_official_system" ->
                    Channel.QQ_OFFICIAL.wireName();
            default -> throw new ToolFailure(SOURCE_CATALOG_INVALID,
                    "source catalog 含未知 raw source 平台");
        };
    }

    static String sourceAccountId(String rawSource) {
        return sourceParts(rawSource)[1];
    }

    private static String[] sourceParts(String rawSource) {
        String[] parts = rawSource.split("/", -1);
        if (parts.length != 3) {
            throw new ToolFailure(SOURCE_CATALOG_INVALID, "source catalog 含非法 raw source");
        }
        return parts;
    }

    static String timestamp(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    static Map<String, Object> property(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    static Map<String, Object> enumProperty(String description, String... values) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        property.put("enum", List.of(values));
        return property;
    }

    static Map<String, Object> schema(Map<String, Object> properties,
                                      List<String> required,
                                      String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", description);
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    static final class ToolFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        ToolFailure(String code, String message) {
            super(Objects.requireNonNull(message, "message"));
            this.code = Objects.requireNonNull(code, "code");
        }

        String code() {
            return code;
        }
    }
}
