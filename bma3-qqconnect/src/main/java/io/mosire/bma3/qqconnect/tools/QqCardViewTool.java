package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.message.Segment;
import java.util.List;
import java.util.Map;

/**
 * Reads one card segment (json/xml) by its fileId.
 *
 * <p>The inbox only shows {@code [卡片] 标题}; this tool is the explicit detail view: title,
 * description, http(s) link and — for QQ group invites — the group id and notification sequence.
 * Raw card JSON is opt-in because it is the largest part of the payload.</p>
 */
public final class QqCardViewTool implements AgentTool {

    private final QqToolContext context;

    public QqCardViewTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_card_view";
    }

    @Override
    public String description() {
        return "按 fileId 查看一条卡片消息（json/xml）：标题/描述/http(s) 链接/群邀请 groupId+sequence；"
                + "includeRaw=true 才返回有界原始卡片。fileId 来自读取工具的 segments[].fileId。"
                + "通用链接可再用 browser_open 打开（公网 http/https）。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "fileId", Map.of("type", "string"),
                "includeRaw", Map.of("type", "boolean",
                        "description", "true=附带截断后的原始卡片 JSON/XML")));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String fileId = QqToolSupport.text(toolContext, "fileId");
            var resolved = QqMediaSupport.resolveAny(context, fileId).orElseThrow();
            Segment segment = resolved.segment();
            if (!"json".equals(segment.type()) && !"xml".equals(segment.type())) {
                return ToolResult.error("NOT_A_CARD",
                        "该段不是卡片: " + segment.type());
            }
            var root = QqToolSupport.object();
            root.put("fileId", fileId);
            root.put("type", segment.type());
            root.put("title", segment.data("title"));
            root.put("desc", segment.data("desc"));
            root.put("bizsrc", segment.data("bizsrc"));
            String url = segment.data("url");
            root.put("url", url);
            String groupId = segment.data("invite_group");
            String sequence = segment.data("invite_seq");
            if (!groupId.isBlank() || !sequence.isBlank()) {
                var invite = root.putObject("groupInvite");
                invite.put("groupId", groupId);
                invite.put("sequence", sequence);
                invite.put("acceptTool", "qq_group_invite_accept");
            }
            if (Boolean.TRUE.equals(toolContext.arguments().get("includeRaw"))) {
                root.put("raw", segment.data("raw"));
            }
            if (!url.isBlank()) {
                root.put("hint", "需要打开链接时用 browser_open(url)");
            }
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
