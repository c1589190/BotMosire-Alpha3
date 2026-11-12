package io.mosire.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.qqconnect.directory.QqDirectoryStore;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One group member's info, including the group title (头衔).
 *
 * <p>The message event only carries nickname/card/role; the title needs
 * {@code get_group_member_info}, so this tool fills the directory cache and answers from it on
 * subsequent calls unless {@code refresh=true}.</p>
 */
public final class QqGroupMemberInfoTool implements AgentTool {

    private final QqToolContext context;

    public QqGroupMemberInfoTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_group_member_info";
    }

    @Override
    public String description() {
        return "查单个群成员：QQ/昵称/群名片/角色/头衔（title/titleExpireAt），优先读本地目录缓存，"
                + "refresh=true 时调 get_group_member_info 刷新。source 或 groupId+accountId 定位群，qq 必填。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "source", Map.of("type", "string"),
                "groupId", Map.of("type", "string"),
                "accountId", Map.of("type", "string"),
                "qq", Map.of("type", "string"),
                "refresh", Map.of("type", "boolean")));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            QqGroupMembersTool.GroupRef ref = QqGroupMembersTool.groupRef(context, toolContext);
            Object rawQq = toolContext.arguments().get("qq");
            if (!(rawQq instanceof String qq) || qq.isBlank()) {
                throw new IllegalArgumentException("qq 不能为空");
            }
            boolean refresh = Boolean.TRUE.equals(toolContext.arguments().get("refresh"));
            QqDirectoryStore directory = directory();
            QqDirectoryStore.Member cached = directory
                    .member(ref.accountId(), ref.groupId(), qq.trim()).orElse(null);
            if (cached != null && !refresh) {
                return result(cached, false);
            }
            try {
                OneBot11Client client = QqToolSupport.client(context, ref.accountId());
                JsonNode response = client.sendAction("get_group_member_info", Map.of(
                                "group_id", Long.parseLong(ref.groupId()),
                                "user_id", Long.parseLong(qq.trim())))
                        .orTimeout(5, TimeUnit.SECONDS)
                        .join();
                JsonNode data = response.path("data");
                if (!data.isObject()) {
                    throw new IllegalArgumentException("MEMBER_NOT_FOUND");
                }
                QqDirectoryStore.Member fresh = QqGroupMembersTool.member(
                        ref.accountId(), ref.groupId(), data);
                directory.upsertMember(fresh);
                return result(fresh, false);
            } catch (RuntimeException failure) {
                if (cached != null) {
                    return result(cached, true);
                }
                return ToolResult.error("MEMBER_INFO_FAILED",
                        failure.getClass().getSimpleName());
            }
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }

    private ToolResult result(QqDirectoryStore.Member member, boolean stale) {
        var root = QqToolSupport.object();
        root.put("stale", stale);
        QqGroupMembersTool.memberNode(member).forEach((key, value) -> {
            if (value instanceof String text) {
                root.put(key, text);
            } else {
                root.put(key, String.valueOf(value));
            }
        });
        root.put("titleExpireAt",
                member.titleExpireAt() == null ? "" : member.titleExpireAt().toString());
        root.put("updatedAt", member.updatedAt().toString());
        return ToolResult.ok(root.toString());
    }

    private QqDirectoryStore directory() {
        if (context.directory() == null) {
            throw new IllegalArgumentException("DIRECTORY_UNAVAILABLE");
        }
        return context.directory();
    }
}
