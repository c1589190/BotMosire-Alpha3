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
                "includeSegments", Map.of("type", "boolean")),
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
        for (int attemptCount : attempts) {
            attempted.add(attemptCount);
            long started = System.nanoTime();
            Map<String, Object> params = new LinkedHashMap<>(base);
            params.put("count", attemptCount);
            try {
                JsonNode response = client.sendAction(action, params)
                        .orTimeout(20, TimeUnit.SECONDS)
                        .join();
                int retcode = response.path("retcode").asInt(-1);
                lastRetcode = retcode;
                JsonNode data = response.path("data");
                JsonNode array = data.path("messages");
                int size = array.isArray() ? array.size() : -1;
                log(action, attemptCount, retcode, started, size,
                        retcode == 1200 ? "retcode1200" : "ok");
                if (retcode == 1200) {
                    if (attemptCount > SAFE_COUNT) {
                        // Real LLBot returned retcode 1200 (empty) for count=1000 while count=300
                        // works. Treat a large-page 1200 as "too large", not as "no history".
                        lastRetcode = 1200;
                        lastError = "RETCODE_1200";
                        continue;
                    }
                    return empty(targetId, chatType, requested, attemptCount, attempted);
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
                        includeSegments, attempted);
            } catch (RuntimeException failure) {
                lastError = failure.getClass().getSimpleName();
                log(action, attemptCount, -1, started, -1, "failed:" + lastError);
            }
        }
        return ToolResult.error("HISTORY_FAILED",
                "requested=" + requested + " attempted=" + attempted
                        + " lastRetcode=" + lastRetcode
                        + (lastError.isBlank() ? "" : " lastError=" + lastError));
    }

    private static ToolResult page(JsonNode array, String chatType, String targetId,
                                   int requested, int fetched, boolean includeSegments,
                                   List<Integer> attempted) {
        var root = QqToolSupport.object();
        root.put("chatType", chatType);
        root.put("targetId", targetId);
        root.put("requestedCount", requested);
        root.put("fetchedCount", fetched);
        root.put("degradedFrom", fetched < requested ? requested : 0);
        var attemptArray = root.putArray("attempts");
        attempted.forEach(attemptArray::add);
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
                                    int fetched, List<Integer> attempted) {
        var root = QqToolSupport.object();
        root.put("chatType", chatType);
        root.put("targetId", targetId);
        root.put("requestedCount", requested);
        root.put("fetchedCount", fetched);
        root.put("degradedFrom", fetched < requested ? requested : 0);
        var attemptArray = root.putArray("attempts");
        attempted.forEach(attemptArray::add);
        var rows = root.putArray("messages");
        root.put("count", 0);
        root.put("nextCursor", "");
        root.put("hasMore", false);
        root.put("truncated", false);
        root.put("hint", "provider 返回 retcode 1200：没有更多历史或该页为空");
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
