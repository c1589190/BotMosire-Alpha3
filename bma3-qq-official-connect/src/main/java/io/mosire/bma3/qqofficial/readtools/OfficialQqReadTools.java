package io.mosire.bma3.qqofficial.readtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.qqofficial.account.OfficialQqAccountStatus;
import io.mosire.bma3.qqofficial.api.OfficialQqApiException;
import io.mosire.bma3.qqofficial.directory.OfficialQqDirectoryStore;
import io.mosire.bma3.qqofficial.tools.QqOfficialSourceKeyer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only QQ official AgentTool factory.
 *
 * <p>This class only assembles tools and shared schemas. It does not wire MCP, tokens, gateway or
 * HTTP; the app root injects an {@link OfficialQqReadContext}. Local tools read the message board
 * and observed directory only. API-backed tools require a READY account and an injected
 * account-scoped {@link OfficialQqReadApiProvider}.</p>
 */
public final class OfficialQqReadTools {

    public static final String ACCOUNT_LIST = "qq_official_account_list";
    public static final String ACCOUNT_STATUS = "qq_official_account_status";
    public static final String GROUP_LIST = "qq_official_group_list";
    public static final String GROUP_INFO = "qq_official_group_info";
    public static final String BOT_STATE = "qq_official_bot_state";
    public static final String USER_INFO = "qq_official_user_info";
    public static final String CONVERSATION_LIST = "qq_official_conversation_list";
    public static final String CONVERSATION_HISTORY = "qq_official_conversation_history";
    public static final String MESSAGE_GET = "qq_official_message_get";

    private OfficialQqReadTools() {
    }

    public static List<AgentTool> all(OfficialQqReadContext context) {
        Objects.requireNonNull(context, "context");
        return List.of(
                new AccountListTool(context),
                new AccountStatusTool(context),
                new GroupListTool(context),
                new GroupInfoTool(context),
                new BotStateTool(context),
                new UserInfoTool(context),
                new ConversationListTool(context),
                new ConversationHistoryTool(context),
                new MessageGetTool(context));
    }

    private static Map<String, Object> schema(Map<String, Object> properties,
                                              List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties == null ? Map.of() : properties);
        schema.put("required", required == null ? List.of() : List.copyOf(required));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> accountProperty() {
        return Map.of("type", "string",
                "description", "官方账号 ID；单账号可省略，多账号必须显式指定");
    }

    private static Map<String, Object> sourceProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> limitProperty(int max) {
        return Map.of("type", "integer", "minimum", 1, "maximum", max);
    }

    private static Map<String, Object> includeSegmentsProperty() {
        return Map.of("type", "boolean",
                "description", "true 时返回结构化 segments（含媒体 URL/rkey）；默认 false");
    }

    private static final class AccountListTool implements AgentTool {
        private final OfficialQqReadContext context;

        private AccountListTool(OfficialQqReadContext context) {
            this.context = context;
        }

        @Override
        public String name() {
            return ACCOUNT_LIST;
        }

        @Override
        public String description() {
            return "列出已配置/已知的 QQ 官方账号及脱敏连接状态；不返回 token、secret 或 Authorization。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return schema(Map.of(), List.of());
        }

        @Override
        public ToolResult execute(ToolContext toolContext) {
            return OfficialQqReadSupport.execute(() -> {
                List<OfficialQqAccountStatus> statuses = context.accountResolver().statuses();
                ObjectNode root = OfficialQqReadSupport.JSON.createObjectNode();
                root.put("source", OfficialQqReadSupport.LOCAL_SOURCE);
                root.put("count", statuses.size());
                ArrayNode accounts = root.putArray("accounts");
                for (OfficialQqAccountStatus status : statuses) {
                    if (status != null) {
                        accounts.add(OfficialQqReadSupport.statusNode(status));
                    }
                }
                return root.toString();
            });
        }
    }

    private static final class AccountStatusTool implements AgentTool {
        private final OfficialQqReadContext context;

        private AccountStatusTool(OfficialQqReadContext context) {
            this.context = context;
        }

        @Override
        public String name() {
            return ACCOUNT_STATUS;
        }

        @Override
        public String description() {
            return "读取一个 QQ 官方账号的脱敏状态；accountId 单账号可省略，多账号缺失即拒绝。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return schema(Map.of("accountId", accountProperty()), List.of());
        }

        @Override
        public ToolResult execute(ToolContext toolContext) {
            return OfficialQqReadSupport.execute(() -> {
                OfficialQqAccountResolver.ResolvedAccount resolved =
                        context.accountResolver().resolve(toolContext, null);
                ObjectNode root = OfficialQqReadSupport.statusNode(resolved.status());
                root.put("source", OfficialQqReadSupport.LOCAL_SOURCE);
                return root.toString();
            });
        }
    }

    private static final class GroupListTool implements AgentTool {
        private final OfficialQqReadContext context;

        private GroupListTool(OfficialQqReadContext context) {
            this.context = context;
        }

        @Override
        public String name() {
            return GROUP_LIST;
        }

        @Override
        public String description() {
            return "列出本地观察到的 QQ 官方群与别名；platformComplete=false，不代表平台全量群列表。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("accountId", accountProperty());
            props.put("limit", limitProperty(500));
            return schema(props, List.of());
        }

        @Override
        public ToolResult execute(ToolContext toolContext) {
            return OfficialQqReadSupport.execute(() -> {
                OfficialQqAccountResolver.ResolvedAccount resolved =
                        context.accountResolver().resolve(toolContext, null);
                int limit = OfficialQqReadSupport.boundedLimit(toolContext, 100, 500);
                List<OfficialQqDirectoryStore.Group> groups =
                        context.directory().groups(resolved.accountId(), limit);
                ObjectNode root = OfficialQqReadSupport.JSON.createObjectNode();
                root.put("accountId", resolved.accountId());
                root.put("source", "local_directory");
                root.put("localOnly", true);
                root.put("platformComplete", false);
                root.put("found", !groups.isEmpty());
                root.put("count", groups.size());
                root.put("hint", OfficialQqReadSupport.PLATFORM_COMPLETE_FALSE_HINT);
                ArrayNode array = root.putArray("groups");
                for (OfficialQqDirectoryStore.Group group : groups) {
                    ObjectNode row = array.addObject();
                    row.put("source", QqOfficialSourceKeyer.GROUP_PLATFORM + "/"
                            + resolved.accountId() + "/" + group.groupOpenid());
                    row.put("accountId", resolved.accountId());
                    row.put("groupOpenid", group.groupOpenid());
                    row.put("name", OfficialQqReadSupport.safeText(group.name(), 256));
                    row.put("alias", OfficialQqReadSupport.safeText(group.alias(), 256));
                    row.put("label", OfficialQqReadSupport.safeText(group.label(), 160));
                    row.put("observed", group.observed());
                    row.put("joined", group.joined().name());
                    row.put("receiveMessages", group.receiveMessages().name());
                    row.put("lastObservedAt", group.lastObservedAt().toString());
                }
                return root.toString();
            });
        }
    }

    private static final class GroupInfoTool implements AgentTool {
        private final OfficialQqReadContext context;

        private GroupInfoTool(OfficialQqReadContext context) {
            this.context = context;
        }

        @Override
        public String name() {
            return GROUP_INFO;
        }

        @Override
        public String description() {
            return "按官方群 raw source 查询群基本信息；无权限/能力不足返回固定错误码，不伪造空数据。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("source", sourceProperty(
                    "qq_official_group/<accountId>/<group_openid>"));
            props.put("accountId", accountProperty());
            return schema(props, List.of("source"));
        }

        @Override
        public ToolResult execute(ToolContext toolContext) {
            return OfficialQqReadSupport.execute(() -> {
                SourceAddress source = OfficialQqReadSupport.officialGroupSource(toolContext);
                OfficialQqAccountResolver.ResolvedAccount resolved =
                        context.accountResolver().resolveApiCapable(toolContext, source);
                JsonNode response;
                try {
                    response = OfficialQqReadSupport.api(context, resolved.accountId(),
                            OfficialQqReadException.Code.CAPABILITY_UNAVAILABLE)
                            .groupInfo(source.lastSegment());
                } catch (OfficialQqApiException failure) {
                    throw OfficialQqReadSupport.mapApiFailure(failure,
                            OfficialQqReadException.Code.CAPABILITY_UNAVAILABLE);
                }
                ObjectNode payload = OfficialQqReadSupport.apiPayload(response);
                ObjectNode root = OfficialQqReadSupport.JSON.createObjectNode();
                root.put("source", source.format());
                root.put("accountId", resolved.accountId());
                root.put("groupOpenid", source.lastSegment());
                root.put("found", true);
                root.set("data", payload);
                copyText(payload, root, "group_name", "groupName");
                copyIntegral(payload, root, "member_count", "memberCount");
                copyIntegral(payload, root, "max_member_count", "maxMemberCount");
                return root.toString();
            });
        }
    }

    private static final class BotStateTool implements AgentTool {
        private final OfficialQqReadContext context;

        private BotStateTool(OfficialQqReadContext context) {
            this.context = context;
        }

        @Override
        public String name() {
            return BOT_STATE;
        }

        @Override
        public String description() {
            return "按官方群 raw source 查询机器人在群内状态、主动消息开关和接收范围；无权限返回固定错误码。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("source", sourceProperty(
                    "qq_official_group/<accountId>/<group_openid>"));
            props.put("accountId", accountProperty());
            return schema(props, List.of("source"));
        }

        @Override
        public ToolResult execute(ToolContext toolContext) {
            return OfficialQqReadSupport.execute(() -> {
                SourceAddress source = OfficialQqReadSupport.officialGroupSource(toolContext);
                OfficialQqAccountResolver.ResolvedAccount resolved =
                        context.accountResolver().resolveApiCapable(toolContext, source);
                JsonNode response;
                try {
                    response = OfficialQqReadSupport.api(context, resolved.accountId(),
                            OfficialQqReadException.Code.CAPABILITY_UNAVAILABLE)
                            .groupBotState(source.lastSegment());
                } catch (OfficialQqApiException failure) {
                    throw OfficialQqReadSupport.mapApiFailure(failure,
                            OfficialQqReadException.Code.CAPABILITY_UNAVAILABLE);
                }
                ObjectNode payload = OfficialQqReadSupport.apiPayload(response);
                ObjectNode root = OfficialQqReadSupport.JSON.createObjectNode();
                root.put("source", source.format());
                root.put("accountId", resolved.accountId());
                root.put("groupOpenid", source.lastSegment());
                root.put("found", true);
                root.set("data", payload);
                copyScalar(payload, root, "bot_state");
                copyScalar(payload, root, "receive_messages");
                copyScalar(payload, root, "message_receive_range");
                return root.toString();
            });
        }
    }

    private static final class UserInfoTool implements AgentTool {
        private final OfficialQqReadContext context;

        private UserInfoTool(OfficialQqReadContext context) {
            this.context = context;
        }

        @Override
        public String name() {
            return USER_INFO;
        }

        @Override
        public String description() {
            return "按官方单聊 raw source 查询用户信息；无好友/无可见关系返回 NOT_FRIEND，不伪造空对象。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("source", sourceProperty(
                    "qq_official_private/<accountId>/<user_openid>"));
            props.put("accountId", accountProperty());
            return schema(props, List.of("source"));
        }

        @Override
        public ToolResult execute(ToolContext toolContext) {
            return OfficialQqReadSupport.execute(() -> {
                SourceAddress source = OfficialQqReadSupport.officialPrivateSource(toolContext);
                OfficialQqAccountResolver.ResolvedAccount resolved =
                        context.accountResolver().resolveApiCapable(toolContext, source);
                JsonNode response;
                try {
                    response = OfficialQqReadSupport.api(context, resolved.accountId(),
                            OfficialQqReadException.Code.NOT_FRIEND)
                            .userInfo(source.lastSegment());
                } catch (OfficialQqApiException failure) {
                    throw OfficialQqReadSupport.mapApiFailure(failure,
                            OfficialQqReadException.Code.NOT_FRIEND);
                }
                ObjectNode payload = OfficialQqReadSupport.apiPayload(response);
                ObjectNode root = OfficialQqReadSupport.JSON.createObjectNode();
                root.put("source", source.format());
                root.put("accountId", resolved.accountId());
                root.put("userOpenid", source.lastSegment());
                root.put("found", true);
                root.set("data", payload);
                copyText(payload, root, "nickname", "nickname");
                copyText(payload, root, "username", "username");
                return root.toString();
            });
        }
    }

    private static final class ConversationListTool implements AgentTool {
        private final OfficialQqReadContext context;

        private ConversationListTool(OfficialQqReadContext context) {
            this.context = context;
        }

        @Override
        public String name() {
            return CONVERSATION_LIST;
        }

        @Override
        public String description() {
            return "列出消息板中本地观察到的 QQ 官方群/单聊会话；只返回元数据，不返回正文。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("accountId", accountProperty());
            props.put("limit", limitProperty(200));
            return schema(props, List.of());
        }

        @Override
        public ToolResult execute(ToolContext toolContext) {
            return OfficialQqReadSupport.execute(() -> {
                OfficialQqAccountResolver.ResolvedAccount resolved =
                        context.accountResolver().resolve(toolContext, null);
                int limit = OfficialQqReadSupport.boundedLimit(toolContext, 50, 200);
                ObjectNode root = OfficialQqReadSupport.JSON.createObjectNode();
                root.put("accountId", resolved.accountId());
                root.put("source", "local_message_board");
                root.put("localOnly", true);
                root.put("platformComplete", false);
                ArrayNode threads = root.putArray("threads");
                int emitted = 0;
                for (var thread : context.board().threads(1000)) {
                    if (emitted >= limit) {
                        break;
                    }
                    if (!resolved.accountId().equals(thread.accountId())) {
                        continue;
                    }
                    ObjectNode node = OfficialQqReadSupport.threadNode(thread, context.directory());
                    if (node != null) {
                        threads.add(node);
                        emitted++;
                    }
                }
                root.put("count", emitted);
                root.put("found", emitted > 0);
                root.put("hint", "会话列表只来自 BMA3 本地消息板，不代表平台全量会话");
                return root.toString();
            });
        }
    }

    private static final class ConversationHistoryTool implements AgentTool {
        private final OfficialQqReadContext context;

        private ConversationHistoryTool(OfficialQqReadContext context) {
            this.context = context;
        }

        @Override
        public String name() {
            return CONVERSATION_HISTORY;
        }

        @Override
        public String description() {
            return "按官方 raw source 分页读取本地消息板历史；默认不返回 segments，"
                    + "includeSegments=true 时才包含结构化 URL/rkey。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("source", sourceProperty(
                    "qq_official_group/<accountId>/<group_openid> 或 "
                            + "qq_official_private/<accountId>/<user_openid>"));
            props.put("accountId", accountProperty());
            props.put("afterSeq", Map.of("type", "integer", "minimum", 0));
            props.put("limit", limitProperty(500));
            props.put("includeSegments", includeSegmentsProperty());
            return schema(props, List.of("source"));
        }

        @Override
        public ToolResult execute(ToolContext toolContext) {
            return OfficialQqReadSupport.execute(() -> {
                SourceAddress source = OfficialQqReadSupport.officialSource(toolContext);
                OfficialQqAccountResolver.ResolvedAccount resolved =
                        context.accountResolver().resolve(toolContext, source);
                long afterSeq = OfficialQqReadSupport.nonNegativeLong(toolContext, "afterSeq", 0L);
                int limit = OfficialQqReadSupport.boundedLimit(toolContext, 50, 500);
                boolean includeSegments = OfficialQqReadSupport.flag(toolContext, "includeSegments");
                var rows = context.board().read(source, afterSeq, Math.min(1000, limit + 1));
                boolean hasMore = rows.size() > limit;
                List<? extends io.mosire.bma3.core.message.Message> page = hasMore
                        ? rows.subList(0, limit) : rows;
                ObjectNode root = OfficialQqReadSupport.JSON.createObjectNode();
                root.put("source", source.format());
                root.put("accountId", resolved.accountId());
                root.put("afterSeq", afterSeq);
                root.put("limit", limit);
                root.put("includeSegments", includeSegments);
                root.put("hasMore", hasMore);
                root.put("nextAfterSeq", page.isEmpty() ? afterSeq
                        : page.getLast().localSeq());
                root.put("found", !page.isEmpty());
                root.put("sourceKind", "message_board");
                root.put("platformComplete", false);
                root.put("hint", OfficialQqReadSupport.LOCAL_HISTORY_HINT);
                ArrayNode messages = root.putArray("messages");
                for (var message : page) {
                    messages.add(OfficialQqReadSupport.messageNode(message, includeSegments));
                }
                return root.toString();
            });
        }
    }

    private static final class MessageGetTool implements AgentTool {
        private final OfficialQqReadContext context;

        private MessageGetTool(OfficialQqReadContext context) {
            this.context = context;
        }

        @Override
        public String name() {
            return MESSAGE_GET;
        }

        @Override
        public String description() {
            return "按官方 raw source + 本地 seq 精确读取一条消息；默认不返回结构化 URL。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("source", sourceProperty(
                    "qq_official_group/<accountId>/<group_openid> 或 "
                            + "qq_official_private/<accountId>/<user_openid>"));
            props.put("accountId", accountProperty());
            props.put("seq", Map.of("type", "integer", "minimum", 1));
            props.put("includeSegments", includeSegmentsProperty());
            return schema(props, List.of("source", "seq"));
        }

        @Override
        public ToolResult execute(ToolContext toolContext) {
            return OfficialQqReadSupport.execute(() -> {
                SourceAddress source = OfficialQqReadSupport.officialSource(toolContext);
                OfficialQqAccountResolver.ResolvedAccount resolved =
                        context.accountResolver().resolve(toolContext, source);
                long seq = OfficialQqReadSupport.positiveLong(toolContext, "seq");
                boolean includeSegments = OfficialQqReadSupport.flag(toolContext, "includeSegments");
                var rows = context.board().read(source, seq - 1, 1);
                if (rows.isEmpty() || rows.getFirst().localSeq() != seq) {
                    throw new OfficialQqReadException(
                            OfficialQqReadException.Code.ITEM_NOT_FOUND,
                            "未发现指定官方消息");
                }
                ObjectNode root = OfficialQqReadSupport.messageNode(rows.getFirst(), includeSegments);
                root.put("source", source.format());
                root.put("accountId", resolved.accountId());
                return root.toString();
            });
        }
    }

    private static void copyText(ObjectNode source, ObjectNode target, String sourceKey,
                                 String targetKey) {
        JsonNode value = source.get(sourceKey);
        if (value != null && value.isTextual()) {
            target.put(targetKey, OfficialQqReadSupport.safeText(value.asText(""), 256));
        }
    }

    private static void copyIntegral(ObjectNode source, ObjectNode target, String sourceKey,
                                     String targetKey) {
        JsonNode value = source.get(sourceKey);
        if (value != null && value.isIntegralNumber()) {
            target.put(targetKey, value.longValue());
        }
    }

    private static void copyScalar(ObjectNode source, ObjectNode target, String key) {
        JsonNode value = source.get(key);
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isTextual()) {
            target.put(key, OfficialQqReadSupport.safeText(value.asText(""), 256));
        } else if (value.isIntegralNumber()) {
            target.put(key, value.longValue());
        } else if (value.isBoolean()) {
            target.put(key, value.booleanValue());
        } else if (value.isFloatingPointNumber()) {
            target.put(key, value.doubleValue());
        }
    }
}
