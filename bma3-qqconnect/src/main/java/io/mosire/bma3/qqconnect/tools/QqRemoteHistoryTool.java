package io.mosire.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.qqconnect.onebot.OneBot11Codec;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reads the provider's own message history (read-through, never written to the board).
 *
 * <p>{@code get_group_msg_history} / {@code get_friend_msg_history} are extensions, not core
 * OneBot 11. Real LLBot evidence (2026-11-12) shows {@code count=100} and {@code count=300} work,
 * while {@code count=1000} fails/times out. The tool therefore accepts up to 1000 but <b>degrades
 * on failure or an empty page</b>: 1000 → 300 → 100, logging every attempt. A retcode 1200 is an
 * empty page, not an error.</p>
 */
public final class QqRemoteHistoryTool implements AgentTool {

    private static final int MAX_COUNT = 1000;
    private static final int MID_COUNT = 300;
    private static final int SAFE_COUNT = 100;

    private final QqToolContext context;

    public QqRemoteHistoryTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_remote_history";
    }

    @Override
    public String description() {
        return "只读读取 OneBot/LLBot 平台历史（get_group_msg_history / get_friend_msg_history），"
                + "不写入消息板。chatType=GROUP|PRIVATE，targetId=群号/QQ，cursor=message_seq，"
                + "count 1..1000（真实 LLBot 对 1000 不稳定：失败/空页会自动降级 1000→300→100 并记录 attempts）。"
                + "返回 messages/nextCursor/hasMore/truncated/requestedCount/fetchedCount/degradedFrom。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "accountId", Map.of("type", "string"),
                "chatType", Map.of("type", "string", "enum", List.of("GROUP", "PRIVATE")),
                "targetId", Map.of("type", "string"),
                "cursor", Map.of("type", "string",
                        "description", "上一页的 message_seq；省略表示最新"),
                "count", Map.of("type", "integer", "minimum", 1, "maximum", MAX_COUNT),
                "reverseOrder", Map.of("type", "boolean",
                        "description", "默认 true=向过去翻页"),
                "includeSegments", Map.of("type", "boolean"),
                "timeoutMs", Map.of("type", "integer", "minimum", 2000, "maximum", 120000,
                        "description", "单次尝试超时，默认 20000")),
                "required", List.of("chatType", "targetId"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        Object rawType = toolContext.arguments().get("chatType");
        String chatType = rawType instanceof String value ? value.toUpperCase() : "GROUP";
        if (!chatType.equals("GROUP") && !chatType.equals("PRIVATE")) {
            return QqToolSupport.invalid(new IllegalArgumentException(
                    "chatType 只能是 GROUP/PRIVATE"));
        }
        String targetId;
        try {
            targetId = QqToolSupport.text(toolContext, "targetId").trim();
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
        int requested = Math.max(1, Math.min(MAX_COUNT,
                QqToolSupport.integer(toolContext, "count", 20)));
        String cursor = toolContext.arguments().get("cursor") instanceof String value
                ? value.trim() : "";
        boolean reverse = !(toolContext.arguments().get("reverseOrder") instanceof Boolean b) || b;
        boolean includeSegments = Boolean.TRUE.equals(
                toolContext.arguments().get("includeSegments"));
        String accountId = toolContext.arguments().get("accountId") instanceof String value
                ? value : "";

        String action;
        Map<String, Object> base = new LinkedHashMap<>();
        try {
            if (chatType.equals("GROUP")) {
                action = "get_group_msg_history";
                base.put("group_id", Long.parseLong(targetId));
            } else {
                action = "get_friend_msg_history";
                base.put("user_id", Long.parseLong(targetId));
            }
        } catch (NumberFormatException invalid) {
            return QqToolSupport.invalid(new IllegalArgumentException("targetId 必须是数字"));
        }
        if (!cursor.isBlank()) {
            base.put("message_seq", cursor);
        }
        base.put("reverse_order", reverse);
        base.put("parse_mult_msg", true);
        base.put("disable_get_url", false);

        LinkedHashSet<Integer> attempts = new LinkedHashSet<>();
        attempts.add(requested);
        if (requested > MID_COUNT) {
            attempts.add(MID_COUNT);
        }
        if (requested > SAFE_COUNT) {
            attempts.add(SAFE_COUNT);
        }

        OneBot11Client client;
        try {
            client = QqToolSupport.client(context, accountId);
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
        String lastError = "";
        int lastRetcode = -1;
        List<Integer> attempted = new ArrayList<>();
        List<Map<String, Object>> details = new ArrayList<>();
        long timeoutMs = Math.max(2_000L, Math.min(120_000L,
                QqToolSupport.integer(toolContext, "timeoutMs", 20_000)));
        for (int attemptCount : attempts) {
            attempted.add(attemptCount);
            long started = System.nanoTime();
            Map<String, Object> params = new LinkedHashMap<>(base);
            params.put("count", attemptCount);
            try {
                JsonNode response = client.sendAction(action, params)
                        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .join();
                int retcode = response.path("retcode").asInt(-1);
                lastRetcode = retcode;
                JsonNode data = response.path("data");
                JsonNode array = data.path("messages");
                int size = array.isArray() ? array.size() : -1;
                String outcome = retcode == 1200 ? "retcode1200" : "ok";
                log(action, attemptCount, retcode, started, size, outcome);
                details.add(attemptDetail(attemptCount, outcome, retcode, started, size));
                if (retcode == 1200) {
                    if (attemptCount > SAFE_COUNT) {
                        // Real LLBot returned retcode 1200 (empty) for count=1000 while count=300
                        // works. Treat a large-page 1200 as "too large", not as "no history".
                        lastRetcode = 1200;
                        lastError = "RETCODE_1200";
                        continue;
                    }
                    return empty(targetId, chatType, requested, attemptCount, attempted, details);
                }
                if (!array.isArray()) {
                    lastError = "INVALID_HISTORY_RESPONSE";
                    continue;
                }
                if (size == 0 && attemptCount > SAFE_COUNT) {
                    // A provider that silently returns an empty page for a large request is
                    // exactly the failure the operator reported; retry with a smaller page.
                    lastError = "EMPTY_PAGE";
                    continue;
                }
                return page(array, chatType, targetId, requested, attemptCount,
                        includeSegments, attempted, details);
            } catch (RuntimeException failure) {
                // 超时和普通失败要分开：一个说明"页太大/网络慢"，一个说明"provider 出错"
                boolean timeout = ((Throwable) failure) instanceof java.util.concurrent.TimeoutException
                        || failure.getCause() instanceof java.util.concurrent.TimeoutException;
                lastError = timeout ? "TIMEOUT" : failure.getClass().getSimpleName();
                log(action, attemptCount, -1, started, -1,
                        timeout ? "TIMEOUT" : "failed:" + lastError);
                details.add(attemptDetail(attemptCount, lastError, -1, started, -1));
            }
        }
        var failure = QqToolSupport.object();
        failure.put("requested", requested);
        var attemptedArray = failure.putArray("attempted");
        attempted.forEach(attemptedArray::add);
        failure.put("lastRetcode", lastRetcode);
        failure.put("lastError", lastError);
        var detailArray = failure.putArray("attempts");
        for (Map<String, Object> detail : details) {
            detailArray.add(QqToolSupport.JSON.valueToTree(detail));
        }
        boolean allTimedOut = !details.isEmpty() && details.stream()
                .allMatch(detail -> "TIMEOUT".equals(detail.get("outcome")));
        failure.put("hint", "全部降级尝试都失败。TIMEOUT 表示该页大小下 provider 不响应"
                + (allTimedOut
                        ? "；★ 连 count=100 都超时 ⇒ 这台 provider 很可能根本没实现/禁用了 "
                          + "get_group_msg_history，再降页也没用：改用 qq_conversation_history 读本地消息板，"
                          + "或用 bash 直接连 QQ 机查。"
                        : "（通常页太大或网络慢）")
                + " retcode1200 表示 provider 认为这页为空。可以用 qq_group_info 确认机器人还在群里。");
        return ToolResult.error("HISTORY_FAILED", failure.toString());
    }

    private static void putDetails(com.fasterxml.jackson.databind.node.ObjectNode root,
                                   List<Map<String, Object>> details) {
        var array = root.putArray("attemptDetails");
        for (Map<String, Object> detail : details) {
            array.add(QqToolSupport.JSON.valueToTree(detail));
        }
    }

    private static Map<String, Object> attemptDetail(int count, String outcome, int retcode,
                                                     long startedNanos, int messages) {
        var row = new LinkedHashMap<String, Object>();
        row.put("count", count);
        row.put("outcome", outcome);
        row.put("retcode", retcode);
        row.put("elapsedMs", (System.nanoTime() - startedNanos) / 1_000_000L);
        row.put("messages", messages);
        return row;
    }

    private static ToolResult page(JsonNode array, String chatType, String targetId,
                                   int requested, int fetched, boolean includeSegments,
                                   List<Integer> attempted, List<Map<String, Object>> details) {
        var root = QqToolSupport.object();
        root.put("chatType", chatType);
        root.put("targetId", targetId);
        root.put("requestedCount", requested);
        root.put("fetchedCount", fetched);
        root.put("degradedFrom", fetched < requested ? requested : 0);
        var attemptArray = root.putArray("attempts");
        attempted.forEach(attemptArray::add);
        putDetails(root, details);
        var rows = root.putArray("messages");
        String nextCursor = "";
        int messagesRead = 0;
        for (JsonNode node : array) {
            var row = rows.addObject();
            row.put("messageId", node.path("message_id").asText(""));
            row.put("messageSeq", node.path("message_seq").asText(""));
            long time = node.path("time").asLong(0L);
            row.put("at", time > 0 ? Instant.ofEpochSecond(time).toString() : "");
            JsonNode sender = node.path("sender");
            var who = row.putObject("sender");
            who.put("id", sender.path("user_id").asText(""));
            who.put("name", sender.path("nickname").asText(""));
            who.put("card", sender.path("card").asText(""));
            who.put("role", sender.path("role").asText(""));
            JsonNode content = node.path("content");
            if (!content.isArray()) {
                content = node.path("message");
            }
            var segments = OneBot11Codec.parseSegments(content);
            StringBuilder text = new StringBuilder();
            for (var segment : segments) {
                String piece = "forward".equals(segment.type())
                        ? "[合并转发]" : segment.text();
                if (piece == null || piece.isBlank()) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(piece);
            }
            row.put("text", text.toString());
            if (includeSegments) {
                var arrayOut = row.putArray("segments");
                for (var segment : segments) {
                    var item = arrayOut.addObject();
                    item.put("type", segment.type());
                    item.put("text", segment.text());
                    var dataOut = item.putObject("data");
                    segment.data().forEach(dataOut::put);
                }
            }
            String seq = node.path("message_seq").asText("");
            if (!seq.isBlank()) {
                nextCursor = seq;
            }
            messagesRead++;
        }
        root.put("count", messagesRead);
        root.put("nextCursor", nextCursor);
        root.put("hasMore", messagesRead >= fetched);
        root.put("truncated", messagesRead >= fetched);
        return ToolResult.ok(root.toString());
    }

    private static ToolResult empty(String targetId, String chatType, int requested,
                                    int fetched, List<Integer> attempted,
                                    List<Map<String, Object>> details) {
        var root = QqToolSupport.object();
        root.put("chatType", chatType);
        root.put("targetId", targetId);
        root.put("requestedCount", requested);
        root.put("fetchedCount", fetched);
        root.put("degradedFrom", fetched < requested ? requested : 0);
        var attemptArray = root.putArray("attempts");
        attempted.forEach(attemptArray::add);
        putDetails(root, details);
        var rows = root.putArray("messages");
        root.put("count", 0);
        root.put("nextCursor", "");
        root.put("hasMore", false);
        root.put("truncated", false);
        root.put("hint", "provider 返回空页：可能真的没有更多历史，也可能机器人不在该群/被禁言/权限不足。"
                + "用 qq_group_info 确认机器人是否在群里、群是否可读；确认后再换 cursor 或更小 count 重试。");
        return ToolResult.ok(root.toString());
    }

    private static void log(String action, int count, int retcode, long startedNanos,
                            int messages, String outcome) {
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        org.slf4j.LoggerFactory.getLogger(QqRemoteHistoryTool.class).info(
                "event=qq_remote_history action={} count={} retcode={} elapsedMs={} messages={} outcome={}",
                action.isBlank() ? "-" : action, count, retcode, elapsedMs, messages,
                outcome == null || outcome.isBlank() ? "-" : outcome);
    }
}
