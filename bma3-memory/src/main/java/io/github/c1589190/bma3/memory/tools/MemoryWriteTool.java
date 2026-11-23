package io.github.c1589190.bma3.memory.tools;

import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.memory.MemoryOptions;
import io.github.c1589190.bma3.memory.MemoryStore;
import io.github.c1589190.bma3.memory.TextChunks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

/**
 * Appends notebook entries. This is the only write path into memory: nothing extracts, summarises or
 * auto-ingests in the background.
 *
 * <p>{@code text} writes one entry. {@code file} imports a whole file from the QQ downloads
 * directory, split on line boundaries into entries that each fit the per-entry cap — the Agent reads
 * a file it received instead of retyping it as a tool argument (D-079), and no line is lost to the
 * cap.</p>
 */
public final class MemoryWriteTool implements AgentTool {

    private static final Set<String> SOURCE_KINDS =
            Set.of("agent", "message_board", "user_said", "import");

    private final MemoryStore store;
    private final Supplier<MemoryOptions> options;
    private final LongPredicate boardSeqExists;
    private final Supplier<Path> importDir;

    /**
     * @param importDir directory file imports may read from (the QQ downloads directory); a
     *                  {@code null} supplier, or one answering {@code null}, disables file imports
     */
    public MemoryWriteTool(MemoryStore store, Supplier<MemoryOptions> options,
            LongPredicate boardSeqExists, Supplier<Path> importDir) {
        this.store = store;
        this.options = options;
        this.boardSeqExists = boardSeqExists;
        this.importDir = importDir;
    }

    @Override
    public String name() {
        return "memory_write";
    }

    @Override
    public String description() {
        MemoryOptions settings = options.get();
        return "写长期记忆（只增不删）。二选一：text 是一条记忆的完整正文；file 是 downloads 目录内"
                + "的文件名，整个文件按行切成不超过 " + settings.maxTextChars() + " 字符的若干条"
                + "记忆（source_kind 固定 import，source_ref 为文件名，不传 tags 时用文件名）。"
                + "tags 用空格或逗号分隔，检索时标签权重远高于正文命中。"
                + "source_kind=message_board 时 source_ref 必须是真实存在的消息板 local_seq；"
                + "context_ref 可放 dsh 会话事件锚点。上限 " + settings.maxTextChars() + " 字符。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return MemoryToolSupport.schema(MemoryToolSupport.property(
                "text", MemoryToolSupport.property(
                        "type", "string",
                        "minLength", 1,
                        "description", "记忆正文"),
                "file", MemoryToolSupport.property(
                        "type", "string",
                        "minLength", 1,
                        "description", "downloads 目录内的文件名，整份导入；与 text 二选一"),
                "tags", MemoryToolSupport.property(
                        "type", "string",
                        "description", "空格或逗号分隔的标签"),
                "source_kind", MemoryToolSupport.property(
                        "type", "string",
                        "enum", List.copyOf(SOURCE_KINDS),
                        "description", "默认 agent；用 file 时固定为 import"),
                "source_ref", MemoryToolSupport.property(
                        "type", "string",
                        "description", "消息板 local_seq 或导入文件名"),
                "context_ref", MemoryToolSupport.property(
                        "type", "string",
                        "description", "dsh 会话事件锚点，形如 session=<id>#seq=<n>")),
                List.of());
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String file = MemoryToolSupport.string(toolContext, "file", "").strip();
            String text = MemoryToolSupport.string(toolContext, "text", "");
            if (!file.isEmpty() && !text.isBlank()) {
                return MemoryToolSupport.invalid("text 与 file 只能给一个");
            }
            if (!file.isEmpty()) {
                return importFile(toolContext, file);
            }
            if (text.isBlank()) {
                return MemoryToolSupport.invalid("text 与 file 至少要给一个");
            }
            // The store enforces its own ceiling too; the tool checks the advertised one so the
            // description and the rejection message can never disagree.
            int maxChars = options.get().maxTextChars();
            if (text.codePointCount(0, text.length()) > maxChars) {
                return MemoryToolSupport.invalid("text 超过上限 " + maxChars + " 字符");
            }
            String kind = MemoryToolSupport.string(toolContext, "source_kind", "agent");
            if (!SOURCE_KINDS.contains(kind)) {
                return MemoryToolSupport.invalid("source_kind 非法: " + kind);
            }
            String sourceRef = MemoryToolSupport.string(toolContext, "source_ref", "");
            if (kind.equals("message_board")) {
                long seq;
                try {
                    seq = Long.parseLong(sourceRef);
                } catch (NumberFormatException failure) {
                    return MemoryToolSupport.invalid(
                            "source_kind=message_board 需要 source_ref 是 local_seq");
                }
                if (seq <= 0 || !boardSeqExists.test(seq)) {
                    return MemoryToolSupport.invalid("消息板不存在 local_seq=" + seq);
                }
            }
            String tags = MemoryToolSupport.string(toolContext, "tags", "");
            String contextRef = MemoryToolSupport.string(toolContext, "context_ref", "");
            long id = store.write(text, tags, kind, sourceRef, contextRef, Instant.now());
            var payload = MemoryToolSupport.object();
            payload.put("id", id);
            payload.put("chars", text.strip().length());
            payload.put("tags", tags);
            return MemoryToolSupport.ok(payload);
        } catch (IllegalArgumentException failure) {
            return MemoryToolSupport.invalid(failure.getMessage());
        } catch (RuntimeException failure) {
            return ToolResult.error("MEMORY_WRITE_FAILED", String.valueOf(failure.getMessage()));
        }
    }

    /**
     * Import one whole file. The three guards are the whole security story of this path: the file
     * must live inside the downloads directory even after symlinks are resolved, it must be a
     * regular file, and it must fit the configured byte ceiling.
     */
    private ToolResult importFile(ToolContext toolContext, String name) {
        Path directory = importDir == null ? null : importDir.get();
        if (directory == null) {
            return MemoryToolSupport.invalid("导入不可用：没有配置 downloads 目录");
        }
        MemoryOptions settings = options.get();
        Instant now = Instant.now();
        Path root = directory.toAbsolutePath().normalize();
        Path candidate = root.resolve(name).normalize();
        if (!candidate.startsWith(root)) {
            return MemoryToolSupport.invalid("file 必须是 downloads 目录内的相对路径");
        }
        try {
            if (Files.isSymbolicLink(candidate)) {
                return MemoryToolSupport.invalid("file 不能是符号链接: " + name);
            }
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return MemoryToolSupport.invalid("file 不存在: " + name);
            }
            Path realRoot = Files.exists(root) ? root.toRealPath() : root;
            Path realFile = candidate.toRealPath();
            if (!realFile.startsWith(realRoot)) {
                return MemoryToolSupport.invalid("file 指向 downloads 之外: " + name);
            }
            long size = Files.size(realFile);
            if (size > settings.maxImportBytes()) {
                return MemoryToolSupport.invalid(
                        "文件超过上限 " + settings.maxImportBytes() + " 字节");
            }
            String raw = Files.readString(realFile, StandardCharsets.UTF_8);
            if (raw.startsWith("\uFEFF")) {
                raw = raw.substring(1);
            }
            String body = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
            if (body.isBlank()) {
                return MemoryToolSupport.invalid("文件没有可导入的正文: " + name);
            }
            List<String> pieces = TextChunks.split(body, settings.maxTextChars());
            String tags = MemoryToolSupport.string(toolContext, "tags", "");
            if (tags.isBlank()) {
                tags = stem(name);
            }
            String contextRef = MemoryToolSupport.string(toolContext, "context_ref", "");
            String sourceRef = MemoryToolSupport.string(toolContext, "source_ref", name);
            List<Long> ids = new ArrayList<>(pieces.size());
            long chars = 0;
            for (String piece : pieces) {
                ids.add(store.write(piece, tags, "import", sourceRef, contextRef, now));
                chars += piece.length();
            }
            var payload = MemoryToolSupport.object();
            var idArray = MemoryToolSupport.JSON.createArrayNode();
            ids.forEach(idArray::add);
            payload.set("ids", idArray);
            payload.put("count", ids.size());
            payload.put("chars", chars);
            payload.put("file", name);
            payload.put("tags", tags);
            return MemoryToolSupport.ok(payload);
        } catch (IOException failure) {
            return ToolResult.error("MEMORY_IMPORT_FAILED",
                    failure.getClass().getSimpleName());
        }
    }

    private static String stem(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = slash < 0 ? name : name.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        return dot <= 0 ? base : base.substring(0, dot);
    }
}
