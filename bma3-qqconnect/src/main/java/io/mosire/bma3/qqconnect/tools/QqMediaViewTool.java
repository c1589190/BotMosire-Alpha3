package io.mosire.bma3.qqconnect.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.media.MediaId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
                + "图片和其他类型下载到 downloads 目录并返回本地 path（图片可再用 read_image 看）；"
                + "**超长图会自动切成多段**（每段 ≤2048 高、相邻重叠 64px）并在 parts[] 里给出各自 path，"
                + "避免 read_image 把 1080×18000 压成 4096 高看不清。"
                + "fileId 来自 qq_conversation_history / qq_message_get 的 includeSegments=true。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("fileId", Map.of("type", "string",
                "description", "形如 file-…；由读取工具在 segments[].fileId 给出"));
        props.put("split", Map.of("type", "string",
                "description", "auto（默认，高图自动切段）/ never（不切）"));
        props.put("maxPartHeight", Map.of("type", "integer",
                "description", "每段最大高度，默认 " + LongImageSplitter.DEFAULT_PART_HEIGHT));
        return Map.of("type", "object", "properties", props, "required", List.of("fileId"));
    }

    private static String baseName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String optionalText(ToolContext context, String name) {
        Object raw = context.arguments().get(name);
        return raw instanceof String text ? text.trim() : "";
    }

    private static int intArg(ToolContext context, String name, int fallback) {
        Object raw = context.arguments().get(name);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
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
            String extension = QqMediaSupport.extension(contentType, fetched.bytes());
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
                int requestedPartHeight = intArg(toolContext, "maxPartHeight",
                        LongImageSplitter.DEFAULT_PART_HEIGHT);
                boolean wantSplit = !"never".equalsIgnoreCase(
                        optionalText(toolContext, "split"));
                LongImageSplitter.Result longImage = wantSplit
                        ? LongImageSplitter.inspectAndSplit(path, policy.dir(),
                                baseName(path), requestedPartHeight,
                                LongImageSplitter.DEFAULT_OVERLAP)
                        : new LongImageSplitter.Result(true, -1, -1, java.util.List.of(),
                                "SPLIT_DISABLED");
                if (longImage.width() > 0) {
                    root.put("width", longImage.width());
                    root.put("height", longImage.height());
                }
                root.put("split", longImage.split());
                if (longImage.split()) {
                    var parts = root.putArray("parts");
                    for (LongImageSplitter.Part part : longImage.parts()) {
                        var row = parts.addObject();
                        row.put("index", part.index());
                        row.put("total", part.total());
                        row.put("path", part.path().toAbsolutePath().toString());
                        row.put("bytes", part.bytes());
                        row.put("width", part.width());
                        row.put("height", part.height());
                        row.put("y0", part.y0());
                        row.put("y1", part.y1());
                    }
                    root.put("hint", "长图 " + longImage.width() + "×" + longImage.height()
                            + " 已切成 " + longImage.parts().size() + " 段（每段 ≤"
                            + requestedPartHeight + " 高、相邻重叠 "
                            + LongImageSplitter.DEFAULT_OVERLAP
                            + "px）。逐段用 read_image 看；原图仍在 path。");
                } else {
                    root.put("hint", switch (longImage.reason()) {
                        case "SHORT_ENOUGH" -> "图片已落盘（" + longImage.width() + "×"
                                + longImage.height() + "，无需切段）；用 read_image 打开该 path";
                        case "TOO_MANY_PIXELS" -> "图片像素数过大（" + longImage.width() + "×"
                                + longImage.height() + "），解码有 OOM 风险，未切段；"
                                + "请先用其他方式缩小或只取局部";
                        case "TOO_MANY_PARTS" -> "图片高 " + longImage.height()
                                + "，按 " + requestedPartHeight + " 一段会超过 "
                                + LongImageSplitter.MAX_PARTS + " 段；调大 maxPartHeight 再试";
                        case "UNREADABLE_FORMAT" -> "图片已落盘，但本机 ImageIO 解不了这个格式，"
                                + "拿不到尺寸也无法切段；用 read_image 直接看该 path";
                        case "WRITE_FAILED" -> "切段写盘失败；原图仍在 path，可直接 read_image";
                        case "SPLIT_DISABLED" -> "图片已落盘（按调用方要求不切段）；用 read_image 打开该 path";
                        default -> "图片已落盘；用 read_image 打开该 path";
                    });
                }
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
