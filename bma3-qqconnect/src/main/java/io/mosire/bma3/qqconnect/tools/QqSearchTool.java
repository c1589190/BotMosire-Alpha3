package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.qqconnect.directory.QqDirectoryStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generic local search across messages, people, groups and sources.
 *
 * <p>It is the replacement for the retired {@code qq_name/} priority key: resolve a nickname or
 * group name here, then configure the stable {@code qq_person_id/<qq>} or {@code source} weight.
 * Results are summaries; use {@code qq_conversation_history} / {@code qq_message_get} for bodies.</p>
 */
public final class QqSearchTool implements AgentTool {

    private static final int SNIPPET = 200;

    private final QqToolContext context;

    public QqSearchTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_search";
    }

    @Override
    public String description() {
        return "本地通用搜索：消息正文/发送人昵称/群名片、群名、成员昵称/名片/头衔。"
                + "scope=all|messages|people|groups|sources。消息按 local_seq 倒序分页（cursor=上一页最小 seq），"
                + "只返回摘要；人/群结果带 source 与 qq_person_id/<qq>，可直接用于 set_config 配置权重。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "query", Map.of("type", "string", "minLength", 1),
                "scope", Map.of("type", "string",
                        "enum", List.of("all", "messages", "people", "groups", "sources")),
                "sourcePrefix", Map.of("type", "string"),
                "from", Map.of("type", "string",
                        "description", "ISO instant 或 yyyy-MM-dd（UTC 当天 00:00）"),
                "to", Map.of("type", "string"),
                "accountId", Map.of("type", "string"),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100),
                "cursor", Map.of("type", "integer", "minimum", 0,
                        "description", "消息分页：只返回 local_seq < cursor 的行")),
                "required", List.of("query"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            Object rawQuery = toolContext.arguments().get("query");
            if (!(rawQuery instanceof String query) || query.isBlank()) {
                throw new IllegalArgumentException("query 不能为空");
            }
            String scope = string(toolContext.arguments().get("scope"), "all");
            if (!List.of("all", "messages", "people", "groups", "sources").contains(scope)) {
                throw new IllegalArgumentException("scope 非法: " + scope);
            }
            int limit = Math.max(1, Math.min(100,
                    QqToolSupport.integer(toolContext, "limit", 20)));
            String sourcePrefix = string(toolContext.arguments().get("sourcePrefix"), "");
            String accountId = string(toolContext.arguments().get("accountId"), "");
            Instant from = instant(toolContext.arguments().get("from"));
            Instant to = instant(toolContext.arguments().get("to"));
            long cursor = toolContext.arguments().get("cursor") instanceof Number number
                    ? number.longValue() : 0L;

            var root = QqToolSupport.object();
            root.put("query", query);
            root.put("scope", scope);
            boolean truncated = false;

            if (scope.equals("all") || scope.equals("messages")) {
                List<Message> messages = context.board().search(query, sourcePrefix, from, to,
                        cursor, limit);
                var rows = root.putArray("messages");
                long nextCursor = 0L;
                for (Message message : messages) {
                    var row = rows.addObject();
                    row.put("seq", message.localSeq());
                    row.put("at", message.inbound().at().toString());
                    row.put("source", message.inbound().source().format());
                    row.put("externalMessageId", message.externalMessageId());
                    var sender = row.putObject("sender");
                    sender.put("id", message.inbound().senderId());
                    sender.put("name", message.inbound().senderName());
                    sender.put("card", message.inbound().senderCard());
                    sender.put("role", message.inbound().senderRole());
                    row.put("text", snippet(QqToolSupport.messageText(message)));
                    nextCursor = message.localSeq();
                }
                root.put("nextCursor", nextCursor);
                truncated |= messages.size() >= limit;
            }

            QqDirectoryStore directory = context.directory();
            if (directory != null && (scope.equals("all") || scope.equals("people")
                    || scope.equals("sources"))) {
                var rows = root.putArray("people");
                List<QqDirectoryStore.Member> members = directory.searchMembers(query, limit);
                for (QqDirectoryStore.Member member : members) {
                    if (!accountId.isBlank() && !accountId.equals(member.accountId())) {
                        continue;
                    }
                    rows.add(QqToolSupport.JSON.valueToTree(
                            QqGroupMembersTool.memberNode(member)));
                }
                truncated |= members.size() >= limit;
            }
            if (directory != null && (scope.equals("all") || scope.equals("groups")
                    || scope.equals("sources"))) {
                var rows = root.putArray("groups");
                List<QqDirectoryStore.Group> groups = directory.searchGroups(query, limit);
                for (QqDirectoryStore.Group group : groups) {
                    if (!accountId.isBlank() && !accountId.equals(group.accountId())) {
                        continue;
                    }
                    var row = rows.addObject();
                    row.put("accountId", group.accountId());
                    row.put("groupId", group.groupId());
                    row.put("name", group.name());
                    row.put("memberCount", group.memberCount());
                    row.put("source",
                            "qq_group/" + group.accountId() + "/" + group.groupId());
                }
                truncated |= groups.size() >= limit;
            }
            if (directory != null && scope.equals("sources")) {
                Set<String> sources = new LinkedHashSet<>();
                for (QqDirectoryStore.Group group : directory.searchGroups(query, limit)) {
                    sources.add("qq_group/" + group.accountId() + "/" + group.groupId());
                }
                for (QqDirectoryStore.Member member : directory.searchMembers(query, limit)) {
                    sources.add("qq_group/" + member.accountId() + "/" + member.groupId());
                }
                var rows = root.putArray("sources");
                sources.forEach(rows::add);
            }
            root.put("truncated", truncated);
            if (directory == null && !scope.equals("messages")) {
                root.put("hint", "directory 不可用，只回了消息结果");
            }
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }

    private static String snippet(String text) {
        String value = text == null ? "" : text.replace('\n', ' ').strip();
        return value.length() <= SNIPPET ? value : value.substring(0, SNIPPET) + "…";
    }

    private static String string(Object raw, String fallback) {
        return raw instanceof String value && !value.isBlank() ? value.trim() : fallback;
    }

    private static Instant instant(Object raw) {
        if (!(raw instanceof String value) || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            return Instant.parse(text);
        } catch (RuntimeException ignored) {
            try {
                return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("时间格式应为 ISO instant 或 yyyy-MM-dd: " + text);
            }
        }
    }
}
