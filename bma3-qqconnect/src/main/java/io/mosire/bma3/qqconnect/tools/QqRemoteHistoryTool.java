package io.mosire.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.qqconnect.onebot.OneBot11Codec;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Reads the provider's own message history as <b>cursor pages</b> (read-through, never written to
 * the board).
 *
 * <p>{@code get_group_msg_history} / {@code get_friend_msg_history} are extensions, not core
 * OneBot 11. Real LLBot (8.3.2 + pmhq 9.0.6) implements them by fanning a single call out into
 * fixed <b>30-message windows fetched 5-at-a-time</b>: {@code count=1000} becomes 34
 * {@code SsoGetGroupMsg} round-trips, which wedges the QQ client's CMD-PIPE and makes every
 * subsequent protocol command time out. So the page size is capped at {@link #MAX_COUNT} and there
 * is <b>no "guess a smaller page" fallback</b> — a timeout is a timeout.</p>
 *
 * <p>Paging is done with the provider's real cursor, {@code message_seq}, whose semantics are
 * "take up to {@code count} messages ending at this seq and walking backwards". The returned
 * {@code nextCursor} is therefore {@code oldestSeq - 1}; feeding it back yields the previous page
 * with no overlap.</p>
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-11-13-remote-history-paging-design.md}.</p>
 */
public final class QqRemoteHistoryTool implements AgentTool {

    /** One provider RPC per 30 messages; 30 keeps a call at exactly one RPC. */
    static final int PROVIDER_WINDOW = 30;
    static final int DEFAULT_COUNT = 30;
    static final int MAX_COUNT = 100;
    static final int MIN_TIMEOUT_MS = 2_000;
    static final int DEFAULT_TIMEOUT_MS = 10_000;
    static final int MAX_TIMEOUT_MS = 60_000;
    /** Consecutive timeouts before the per-account breaker opens. */
    static final int CIRCUIT_THRESHOLD = 3;
    static final long CIRCUIT_COOLDOWN_MS = 60_000L;

    private final QqToolContext context;
    private final Map<String, AtomicBoolean> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public QqRemoteHistoryTool(QqToolContext context) {
        this(context, System::currentTimeMillis);
    }

    QqRemoteHistoryTool(QqToolContext context, LongSupplier clock) {
        this.context = context;
        this.clock = clock;
    }

    /** Per-account state: how many timeouts in a row, and until when we refuse to call. */
    static final class Breaker {
        int consecutiveTimeouts;
        long openUntilMs;
    }

    @Override
    public String name() {
        return "qq_remote_history";
    }

    @Override
    public String description() {
        return "只读读取 provider 历史（get_group_msg_history / get_friend_msg_history），"
                + "不写入消息板。**按游标分页，一次一页**：count 1.." + MAX_COUNT
                + "（默认 " + DEFAULT_COUNT + "，provider 内部每 30 条一个 RPC 窗口）；"
                + "cursor=上一页返回的 nextCursor。返回 messages/nextCursor/prevCursor/hasMore/count。"
                + "下一页（更旧）传 nextCursor，上一页（更新）传 prevCursor。"
                + "同账号同时只允许一个请求；连续 " + CIRCUIT_THRESHOLD
                + " 次超时会熔断 " + (CIRCUIT_COOLDOWN_MS / 1000) + " 秒。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "accountId", Map.of("type", "string"),
                "chatType", Map.of("type", "string", "enum", List.of("GROUP", "PRIVATE")),
                "targetId", Map.of("type", "string"),
                "cursor", Map.of("type", "string",
                        "description", "message_seq 游标；省略=最新一页。下一页用上一页的 nextCursor"),
                "count", Map.of("type", "integer", "minimum", 1, "maximum", MAX_COUNT,
                        "description", "默认 " + DEFAULT_COUNT + "；≤30 只花 1 个 provider RPC"),
                "reverseOrder", Map.of("type", "boolean",
                        "description", "仅控制显示顺序：false=旧→新（默认），true=新→旧；不影响游标"),
                "includeSegments", Map.of("type", "boolean"),
                "timeoutMs", Map.of("type", "integer", "minimum", MIN_TIMEOUT_MS,
                        "maximum", MAX_TIMEOUT_MS,
                        "description", "单次请求超时，默认 " + DEFAULT_TIMEOUT_MS)),
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
        long targetNumeric;
        try {
            targetNumeric = Long.parseLong(targetId);
        } catch (NumberFormatException invalid) {
            return QqToolSupport.invalid(new IllegalArgumentException("targetId 必须是数字"));
        }
        int count = QqToolSupport.integer(toolContext, "count", DEFAULT_COUNT);
        if (count < 1 || count > MAX_COUNT) {
            return QqToolSupport.invalid(new IllegalArgumentException(
                    "count 必须在 1.." + MAX_COUNT + "（provider 内部每 " + PROVIDER_WINDOW
                            + " 条一个 RPC，大页会打死协议管道；要更多请翻页）"));
        }
        String cursor = toolContext.arguments().get("cursor") instanceof String value
                ? value.trim() : "";
        boolean reverseOrder = Boolean.TRUE.equals(toolContext.arguments().get("reverseOrder"));
        boolean includeSegments = QqToolSupport.includeSegments(toolContext);
        int timeoutMs = Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS,
                QqToolSupport.integer(toolContext, "timeoutMs", DEFAULT_TIMEOUT_MS)));
        String accountId = toolContext.arguments().get("accountId") instanceof String value
                ? value : "";

        String action = chatType.equals("GROUP") ? "get_group_msg_history" : "get_friend_msg_history";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(chatType.equals("GROUP") ? "group_id" : "user_id", targetNumeric);
        if (!cursor.isBlank()) {
            params.put("message_seq", cursor);
        }
        params.put("count", count);
        // provider schema uses camelCase reverseOrder; reverse_order/parse_mult_msg/disable_get_url
        // are NapCat parameters and are silently dropped by LLBot, so we no longer send them.
        params.put("reverseOrder", reverseOrder);

        OneBot11Client client;
        try {
            client = QqToolSupport.client(context, accountId);
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
        String accountKey = accountId.isBlank() ? "-" : accountId;

        Breaker breaker = breakers.computeIfAbsent(accountKey, key -> new Breaker());
        long now = clock.getAsLong();
        synchronized (breaker) {
            if (breaker.openUntilMs > now) {
                return circuitOpen(targetId, chatType, breaker.openUntilMs - now);
            }
        }

        AtomicBoolean gate = inFlight.computeIfAbsent(accountKey, key -> new AtomicBoolean());
        if (!gate.compareAndSet(false, true)) {
            return busy(targetId, chatType, accountKey);
        }
        long started = System.nanoTime();
        try {
            JsonNode response = client.sendAction(action, params)
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .join();
            int retcode = response.path("retcode").asInt(-1);
            JsonNode data = response.path("data");
            JsonNode array = data.path("messages");
            int size = array.isArray() ? array.size() : -1;
            if (retcode == 1200) {
                // retcode 1200 is an empty page, not an error.
                reset(breaker);
                log(action, count, retcode, started, 0, "retcode1200");
                return emptyPage(targetId, chatType, count, retcode);
            }
            if (retcode != 0 || !array.isArray()) {
                log(action, count, retcode, started, size, "failed");
                return failure("HISTORY_FAILED", targetId, chatType, count,
                        "PROVIDER_ERROR", retcode, started,
                        "provider 返回 retcode=" + retcode + " 或响应缺少 data.messages；"
                                + "用 qq_group_info 确认机器人是否在群/群是否可读。");
            }
            reset(breaker);
            log(action, count, retcode, started, size, "ok");
            return page(array, chatType, targetId, count, includeSegments, retcode, started);
        } catch (Exception failure) {
            // TimeoutException is checked, so the catch type must be Exception (not RuntimeException)
            // for the instanceof test below to be legal.
            boolean timeout = failure instanceof TimeoutException
                    || failure instanceof CompletionException
                       && failure.getCause() instanceof TimeoutException
                    || failure.getCause() instanceof TimeoutException;
            if (timeout) {
                int streak;
                synchronized (breaker) {
                    breaker.consecutiveTimeouts++;
                    streak = breaker.consecutiveTimeouts;
                    if (streak >= CIRCUIT_THRESHOLD) {
                        breaker.openUntilMs = clock.getAsLong() + CIRCUIT_COOLDOWN_MS;
                    }
                }
                log(action, count, -1, started, -1, "TIMEOUT");
                return failure("HISTORY_TIMEOUT", targetId, chatType, count, "TIMEOUT", -1, started,
                        "provider 在 " + timeoutMs + " ms 内没有回应（不重试、不降级）。"
                                + (streak >= CIRCUIT_THRESHOLD
                                ? "已连续超时 " + streak + " 次 ⇒ 对该账号熔断 "
                                  + (CIRCUIT_COOLDOWN_MS / 1000) + " 秒，之后直接拒答；"
                                : "已连续超时 " + streak + "/" + CIRCUIT_THRESHOLD + " 次；")
                                + " 若刚传过很大的 count，协议管道可能已被打死 —— 改用小 count（≤30）翻页，"
                                + "或用 qq_conversation_history 读本地消息板。");
            }
            log(action, count, -1, started, -1, "failed");
            return failure("HISTORY_FAILED", targetId, chatType, count,
                    failure.getClass().getSimpleName(), -1, started,
                    "请求失败：" + failure.getMessage());
        } finally {
            gate.set(false);
        }
    }

    private void reset(Breaker breaker) {
        synchronized (breaker) {
            breaker.consecutiveTimeouts = 0;
            breaker.openUntilMs = 0L;
        }
    }

    private static ToolResult page(JsonNode array, String chatType, String targetId, int requested,
                                   boolean includeSegments, int retcode, long startedNanos) {
        var root = QqToolSupport.object();
        root.put("chatType", chatType);
        root.put("targetId", targetId);
        root.put("requestedCount", requested);
        var rows = root.putArray("messages");
        int messagesRead = 0;
        long minSeq = Long.MAX_VALUE;
        long maxSeq = Long.MIN_VALUE;
        for (JsonNode node : array) {
            var row = rows.addObject();
            row.put("messageId", node.path("message_id").asText(""));
            String seq = node.path("message_seq").asText("");
            row.put("messageSeq", seq);
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
            if (!seq.isBlank()) {
                try {
                    long value = Long.parseLong(seq.trim());
                    minSeq = Math.min(minSeq, value);
                    maxSeq = Math.max(maxSeq, value);
                } catch (NumberFormatException ignored) {
                    // Non-numeric seq: leave cursors empty rather than inventing one.
                }
            }
            messagesRead++;
        }
        // Cursors are derived from min/max seq, so they are independent of reverseOrder.
        // The provider reads "count messages ENDING at seq N", so:
        //   backwards -> N = oldest - 1            (window ends just below this page)
        //   forwards  -> N = newest + count        (window ends just above this page)
        // Using newest + 1 for forwards would return this page shifted by one.
        String nextCursor = minSeq <= 1 ? "" : Long.toString(minSeq - 1);
        String prevCursor = maxSeq == Long.MIN_VALUE
                ? "" : Long.toString(maxSeq + requested);
        root.put("count", messagesRead);
        root.put("fetchedCount", messagesRead);
        root.put("nextCursor", nextCursor);
        root.put("prevCursor", prevCursor);
        root.put("hasMore", messagesRead >= requested);
        var attempt = root.putObject("attempt");
        attempt.put("count", requested);
        attempt.put("retcode", retcode);
        attempt.put("elapsedMs", (System.nanoTime() - startedNanos) / 1_000_000L);
        attempt.put("messages", messagesRead);
        return ToolResult.ok(root.toString());
    }

    private static ToolResult emptyPage(String targetId, String chatType, int requested,
                                        int retcode) {
        var root = QqToolSupport.object();
        root.put("chatType", chatType);
        root.put("targetId", targetId);
        root.put("requestedCount", requested);
        root.put("fetchedCount", 0);
        root.putArray("messages");
        root.put("count", 0);
        root.put("nextCursor", "");
        root.put("prevCursor", "");
        root.put("hasMore", false);
        var attempt = root.putObject("attempt");
        attempt.put("count", requested);
        attempt.put("retcode", retcode);
        attempt.put("messages", 0);
        root.put("hint", "provider 返回空页：可能真的没有更多历史，也可能机器人不在该群/被禁言/权限不足。"
                + "用 qq_group_info 确认机器人是否在群里、群是否可读。");
        return ToolResult.ok(root.toString());
    }

    private static ToolResult failure(String code, String targetId, String chatType, int count,
                                      String outcome, int retcode, long startedNanos, String hint) {
        var root = QqToolSupport.object();
        root.put("chatType", chatType);
        root.put("targetId", targetId);
        root.put("requestedCount", count);
        root.put("retcode", retcode);
        root.put("outcome", outcome);
        var attempt = root.putObject("attempt");
        attempt.put("count", count);
        attempt.put("outcome", outcome);
        attempt.put("retcode", retcode);
        attempt.put("elapsedMs", (System.nanoTime() - startedNanos) / 1_000_000L);
        root.put("hint", hint);
        return ToolResult.error(code, root.toString());
    }

    private static ToolResult circuitOpen(String targetId, String chatType, long remainingMs) {
        var root = QqToolSupport.object();
        root.put("chatType", chatType);
        root.put("targetId", targetId);
        root.put("outcome", "CIRCUIT_OPEN");
        root.put("retryAfterMs", Math.max(0L, remainingMs));
        root.put("hint", "该账号连续超时 " + CIRCUIT_THRESHOLD + " 次，已熔断；"
                + "冷却期内不发出请求（避免把 provider 的协议管道彻底打坏）。"
                + "改用 qq_conversation_history 读本地消息板，或冷却后重试。");
        return ToolResult.error("HISTORY_CIRCUIT_OPEN", root.toString());
    }

    private static ToolResult busy(String targetId, String chatType, String accountKey) {
        var root = QqToolSupport.object();
        root.put("chatType", chatType);
        root.put("targetId", targetId);
        root.put("accountId", accountKey);
        root.put("outcome", "BUSY");
        root.put("hint", "该账号已有历史请求在飞：历史接口在 provider 侧是重操作，"
                + "并发只会放大协议压力。等上一个返回再翻页。");
        return ToolResult.error("HISTORY_BUSY", root.toString());
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
