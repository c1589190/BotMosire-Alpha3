package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.media.MediaId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Views one media segment by file id.
 *
 * <p>Text that fits the inline budget comes back as text; images and everything else are written
 * under the configured {@code downloads} directory and the caller gets a path it can hand to
 * {@code read_image} or any file tool. The caller never supplies a URL, only the id that the
 * history/read tools printed.</p>
 */
public final class QqMediaViewTool implements AgentTool {

    private final QqToolContext context;

    public QqMediaViewTool(QqToolContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "qq_media_view";
    }

    @Override
    public String description() {
        return "按 fileId 查看一条消息里的媒体（图片/文件/语音/视频）。文本装得下就直接返回 text；"
                + "图片和其他类型下载到 downloads 目录并返回本地 path（图片可再用 read_image 看）。"
                + "fileId 来自 qq_conversation_history / qq_message_get 的 includeSegments=true。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "fileId", Map.of("type", "string",
                        "description", "形如 file-…；由读取工具在 segments[].fileId 给出")),
                "required", List.of("fileId"));
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String fileId = QqToolSupport.text(toolContext, "fileId");
            QqMediaPolicy policy = context.media();
            MediaId media = MediaId.decode(fileId)
                    .orElseThrow(() -> new IllegalArgumentException("INVALID_FILE_ID: " + fileId));
            var resolved = QqMediaSupport.resolve(context, fileId).orElseThrow();
            String url = QqMediaSupport.freshUrl(context, media, resolved.segment());
            var fetched = QqMediaSupport.fetch(url, policy);

            String contentType = fetched.contentType();
            String extension = QqMediaSupport.extension(contentType);
            boolean text = QqMediaSupport.isText(contentType, fetched.bytes());

            var root = QqToolSupport.object();
            root.put("fileId", fileId);
            root.put("kind", resolved.segment().type());
            root.put("contentType", contentType);
            root.put("size", fetched.bytes().length);

            if (text && fetched.bytes().length <= policy.maxInlineBytes()) {
                root.put("inline", true);
                root.put("text", new String(fetched.bytes(), StandardCharsets.UTF_8));
                return ToolResult.ok(root.toString());
            }

            var path = QqMediaSupport.write(policy,
                    media.fileName(extension), fetched.bytes());
            root.put("inline", false);
            root.put("path", path.toAbsolutePath().toString());
            if (QqMediaSupport.isImage(contentType)) {
                root.put("hint", "图片已落盘；用 read_image 打开该 path 查看");
            } else if (text) {
                root.put("hint", "文本超过内联上限，已落盘；用文件工具读取该 path");
            } else {
                root.put("hint", "非图文内容已落盘；用相应工具读取该 path");
            }
            return ToolResult.ok(root.toString());
        } catch (IllegalArgumentException failure) {
            return QqToolSupport.invalid(failure);
        }
    }
}
