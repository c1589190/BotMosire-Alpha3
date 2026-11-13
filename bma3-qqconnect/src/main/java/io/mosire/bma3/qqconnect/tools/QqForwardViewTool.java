package io.mosire.bma3.qqconnect.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.qqconnect.onebot.OneBot11Codec;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Expands one merged-forward message through {@code get_forward_msg}.
 *
 * <p>One level is expanded; a nested forward is reported as {@code [嵌套合并转发]} and not
 * recursed into. The view is paginated and never written to the message board: the board remains
 * the fact source for messages BMA3 actually received.</p>
 */
public final class QqForwardViewTool implements AgentTool {

    /** 一次调用里最多再展开几条嵌套转发（防止一条转发里塞了 50 层把 provider 打爆）。 */
    private static final int MAX_NESTED_FETCHES = 10;

    /** 每条嵌套转发最多回多少条消息。 */
    private static final int MAX_NESTED_MESSAGES = 50;

    private final QqToolContext context;

    public QqForwardViewTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_forward_view";
    }

    @Override
    public String description() {
        return "展开一条合并转发（get_forward_msg，展开一层）：fileId 来自消息板读取工具，"
                + "或直接传 forwardId（例如 qq_remote_history 的 segments[].data.id）。"
                + "返回发送者/时间/文本/摘要，offset/limit 分页。depth=2 时把嵌套的那层也展开到 nested 里"
                + "（默认 depth=1，只标 [嵌套合并转发]）。"
                + "includeSegments=true 才返回结构化段。只读，不写消息板。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "fileId", Map.of("type", "string"),
                "forwardId", Map.of("type", "string",
                        "description", "合并转发的 id（segment.data.id）；与 fileId 二选一"),
                "accountId", Map.of("type", "string",
                        "description", "直接传 forwardId 时用于选择账号；单账号可省略"),
                "offset", Map.of("type", "integer", "minimum", 0),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200),
                "includeSegments", Map.of("type", "boolean"),
                "depth", Map.of("type", "integer", "minimum", 1, "maximum", 2,
                        "description", "展开层数：1=只展开这一层（默认），2=嵌套转发也展开一层")));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String fileId = textArg(toolContext, "fileId");
            String forwardId = textArg(toolContext, "forwardId");
            String accountId = textArg(toolContext, "accountId");
            int offset = Math.max(0, QqToolSupport.integer(toolContext, "offset", 0));
            int limit = Math.max(1, Math.min(200,
                    QqToolSupport.integer(toolContext, "limit", 50)));
            boolean includeSegments = Boolean.TRUE.equals(
                    toolContext.arguments().get("includeSegments"));
            int depth = Math.max(1, Math.min(2, QqToolSupport.integer(toolContext, "depth", 1)));
            if (forwardId.isBlank()) {
                if (fileId.isBlank()) {
                    throw new IllegalArgumentException("fileId 与 forwardId 至少提供一个");
                }
                var resolved = QqMediaSupport.resolveAny(context, fileId).orElseThrow();
                Segment segment = resolved.segment();
                if (!"forward".equals(segment.type())) {
                    return ToolResult.error("NOT_A_FORWARD",
                            "该段不是合并转发: " + segment.type());
                }
                forwardId = segment.data("id");
                if (accountId.isBlank()) {
                    accountId = io.mosire.bma3.core.source.SourceAddress.parse(
                            io.mosire.bma3.core.media.MediaId.decode(fileId)
                                    .orElseThrow().source()).accountId();
                }
            }
            if (forwardId.isBlank()) {
                return ToolResult.error("FORWARD_ID_MISSING",
                        "该段没有 forward id，无法展开");
            }
            OneBot11Client client = QqToolSupport.client(context, accountId);
            JsonNode response = client.sendAction("get_forward_msg", Map.of("id", forwardId))
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();
            JsonNode data = response.path("data");
            JsonNode messages = data.path("messages");
            if (!messages.isArray()) {
                messages = data.path("message");
            }
            if (!messages.isArray()) {
                return ToolResult.error("INVALID_FORWARD_RESPONSE",
                        "get_forward_msg 没有返回 messages 数组");
            }
            int total = messages.size();
            var root = QqToolSupport.object();
            root.put("fileId", fileId);
            root.put("forwardId", forwardId);
            root.put("total", total);
            root.put("offset", offset);
            var rows = root.putArray("messages");
            int shown = 0;
            int nestedFetched = 0;
            for (int index = offset; index < total && shown < limit; index++, shown++) {
                JsonNode node = messages.get(index);
                var row = rows.addObject();
                JsonNode sender = node.path("sender");
                var who = row.putObject("sender");
                who.put("id", sender.path("user_id").asText(""));
                who.put("name", sender.path("nickname").asText(""));
                who.put("card", sender.path("card").asText(""));
                who.put("role", sender.path("role").asText(""));
                long epoch = node.path("time").asLong(0L);
                row.put("at", epoch > 0 ? Instant.ofEpochSecond(epoch).toString() : "");
                JsonNode content = node.path("content");
                if (!content.isArray()) {
                    content = node.path("message");
                }
                List<Segment> segments = OneBot11Codec.parseSegments(content);
                StringBuilder text = new StringBuilder();
                for (Segment item : segments) {
                    String piece = "forward".equals(item.type()) ? "[嵌套合并转发]" : item.text();
                    if (piece == null || piece.isBlank()) {
                        continue;
                    }
                    if (text.length() > 0) {
                        text.append(' ');
                    }
                    text.append(piece);
                }
                row.put("text", text.toString());
                if (depth > 1) {
                    for (Segment item : segments) {
                        if (!"forward".equals(item.type()) || nestedFetched >= MAX_NESTED_FETCHES) {
                            continue;
                        }
                        String nestedId = item.data("id");
                        if (nestedId == null || nestedId.isBlank()) {
                            continue;
                        }
                        nestedFetched++;
                        row.set("nested", expandNested(client, nestedId));
                    }
                }
                if (includeSegments) {
                    var array = row.putArray("segments");
                    for (Segment item : segments) {
                        var nodeOut = array.addObject();
                        nodeOut.put("type", item.type());
                        nodeOut.put("text", item.text());
                        var itemData = nodeOut.putObject("data");
                        item.data().forEach(itemData::put);
                    }
                }
            }
            int next = offset + shown;
            root.put("shown", shown);
            root.put("truncated", next < total);
            root.put("nextOffset", next < total ? next : 0);
            root.put("nestedExpanded", depth > 1);
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        } catch (RuntimeException failure) {
            return ToolResult.error("FORWARD_VIEW_FAILED",
                    failure.getClass().getSimpleName());
        }
    }

    /**
     * 展开一层嵌套转发。失败不抛：嵌套是加分项，不能因为它挂了就毁掉外层结果。
     */
    private com.fasterxml.jackson.databind.node.ObjectNode expandNested(
            OneBot11Client client, String forwardId) {
        var out = QqToolSupport.object();
        out.put("forwardId", forwardId);
        try {
            JsonNode response = client.sendAction("get_forward_msg", Map.of("id", forwardId))
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();
            if (response.path("retcode").asInt(-1) != 0) {
                out.put("error", "RETCODE_" + response.path("retcode").asInt(-1));
                return out;
            }
            JsonNode messages = response.path("data").path("messages");
            if (!messages.isArray()) {
                messages = response.path("data").path("message");
            }
            if (!messages.isArray()) {
                out.put("error", "INVALID_FORWARD_RESPONSE");
                return out;
            }
            out.put("total", messages.size());
            var rows = out.putArray("messages");
            int shown = 0;
            for (JsonNode node : messages) {
                if (shown >= MAX_NESTED_MESSAGES) {
                    break;
                }
                var row = rows.addObject();
                row.put("sender", node.path("sender").path("nickname").asText(""));
                row.put("senderId", node.path("sender").path("user_id").asText(""));
                long epoch = node.path("time").asLong(0L);
                row.put("at", epoch > 0 ? Instant.ofEpochSecond(epoch).toString() : "");
                JsonNode content = node.path("content");
                if (!content.isArray()) {
                    content = node.path("message");
                }
                StringBuilder text = new StringBuilder();
                for (Segment item : OneBot11Codec.parseSegments(content)) {
                    String piece = "forward".equals(item.type())
                            ? "[更深一层的合并转发]" : item.text();
                    if (piece != null && !piece.isBlank()) {
                        text.append(text.length() > 0 ? " " : "").append(piece);
                    }
                }
                row.put("text", text.toString());
                shown++;
            }
            return out;
        } catch (RuntimeException failure) {
            out.put("error", failure.getClass().getSimpleName());
            return out;
        }
    }

    private static String textArg(ToolContext context, String name) {
        Object raw = context.arguments().get(name);
        return raw instanceof String value ? value.trim() : "";
    }
}
