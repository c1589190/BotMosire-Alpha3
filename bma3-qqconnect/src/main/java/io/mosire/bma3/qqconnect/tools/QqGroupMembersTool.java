package io.mosire.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.qqconnect.directory.QqDirectoryStore;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Lists one group's members with group card, role and title (群名片 / 角色 / 头衔).
 *
 * <p>The OneBot member list is cached in {@code qq-directory.db}; a second call reads the cache
 * unless {@code refresh=true}. If the platform call fails and a cache exists, the stale cache is
 * returned with {@code stale=true} instead of failing the whole read.</p>
 */
public final class QqGroupMembersTool implements AgentTool {

    private final QqToolContext context;

    public QqGroupMembersTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_group_members";
    }

    @Override
    public String description() {
        return "列出群成员（QQ/昵称/群名片/角色/头衔），优先读本地目录缓存，refresh=true 时重新拉取 "
                + "get_group_member_list。source 可写 qq_group/<account>/<group>；也可用 groupId+accountId。"
                + "返回 total/shown/truncated/stale，不返回凭据。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "source", Map.of("type", "string",
                        "description", "群来源，例如 qq_group/bot-1/955432692"),
                "groupId", Map.of("type", "string"),
                "accountId", Map.of("type", "string"),
                "refresh", Map.of("type", "boolean", "description", "true=重新拉取平台成员列表"),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200),
                "offset", Map.of("type", "integer", "minimum", 0)));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            GroupRef ref = groupRef(context, toolContext);
            boolean refresh = Boolean.TRUE.equals(toolContext.arguments().get("refresh"));
            int limit = QqToolSupport.integer(toolContext, "limit", 50);
            int offset = Math.max(0, QqToolSupport.integer(toolContext, "offset", 0));
            boolean stale = false;
            int cached = directory().memberCount(ref.accountId(), ref.groupId());
            if (refresh || cached == 0) {
                boolean refreshed = refreshFromPlatform(ref, toolContext);
                stale = !refreshed;
                if (!refreshed && cached == 0) {
                    return ToolResult.error("MEMBER_LIST_FAILED",
                            "平台成员列表拉取失败且没有缓存");
                }
            }
            List<QqDirectoryStore.Member> members = directory()
                    .members(ref.accountId(), ref.groupId(), offset, limit);
            int total = directory().memberCount(ref.accountId(), ref.groupId());
            var root = QqToolSupport.object();
            root.put("source", ref.source());
            root.put("groupId", ref.groupId());
            root.put("total", total);
            root.put("shown", members.size());
            root.put("offset", offset);
            root.put("truncated", offset + members.size() < total);
            root.put("stale", stale);
            var rows = root.putArray("members");
            for (QqDirectoryStore.Member member : members) {
                var row = rows.addObject();
                row.put("qq", member.qq());
                row.put("nickname", member.nickname());
                row.put("card", member.card());
                row.put("role", member.role());
                row.put("title", member.title());
                row.put("titleExpireAt",
                        member.titleExpireAt() == null ? "" : member.titleExpireAt().toString());
                row.put("updatedAt", member.updatedAt().toString());
            }
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }

    private QqDirectoryStore directory() {
        if (context.directory() == null) {
            throw new IllegalArgumentException("DIRECTORY_UNAVAILABLE");
        }
        return context.directory();
    }

    private boolean refreshFromPlatform(GroupRef ref, ToolContext toolContext) {
        try {
            OneBot11Client client = QqToolSupport.client(context, ref.accountId());
            long groupId = Long.parseLong(ref.groupId());
            JsonNode response = client.sendAction("get_group_member_list",
                            Map.of("group_id", groupId))
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();
            JsonNode data = response.path("data");
            if (!data.isArray()) {
                return false;
            }
            List<QqDirectoryStore.Member> members = new ArrayList<>();
            for (JsonNode node : data) {
                QqDirectoryStore.Member member = member(ref.accountId(), ref.groupId(), node);
                if (!member.qq().isBlank()) {
                    members.add(member);
                }
            }
            directory().upsertMembers(members);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    static QqDirectoryStore.Member member(String accountId, String groupId, JsonNode node) {
        long expireEpoch = node.path("title_expire_time").asLong(0L);
        Instant expire = expireEpoch > 0 ? Instant.ofEpochSecond(expireEpoch) : null;
        return new QqDirectoryStore.Member(
                accountId,
                groupId,
                node.path("user_id").asText(""),
                node.path("nickname").asText(""),
                node.path("card").asText(""),
                node.path("role").asText(""),
                node.path("title").asText(""),
                expire,
                Instant.now());
    }

    record GroupRef(String accountId, String groupId) {

        String source() {
            return "qq_group/" + accountId + "/" + groupId;
        }
    }

    static GroupRef groupRef(QqToolContext context, ToolContext toolContext) {
        Object rawSource = toolContext.arguments().get("source");
        if (rawSource instanceof String source && !source.isBlank()) {
            SourceAddress address = SourceAddress.parse(source);
            if (!"qq_group".equals(address.platform())) {
                throw new IllegalArgumentException("source 必须是 qq_group/<account>/<group>");
            }
            return new GroupRef(address.accountId(), address.lastSegment());
        }
        Object rawGroup = toolContext.arguments().get("groupId");
        if (!(rawGroup instanceof String groupId) || groupId.isBlank()) {
            throw new IllegalArgumentException("source 或 groupId 必须提供");
        }
        return new GroupRef(QqToolSupport.accountId(context, toolContext), groupId.trim());
    }

    static Map<String, Object> memberNode(QqDirectoryStore.Member member) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("qq", member.qq());
        row.put("nickname", member.nickname());
        row.put("card", member.card());
        row.put("role", member.role());
        row.put("title", member.title());
        row.put("group", member.groupId());
        row.put("accountId", member.accountId());
        row.put("source", "qq_group/" + member.accountId() + "/" + member.groupId());
        row.put("personId", "qq_person_id/" + member.qq());
        return row;
    }
}
