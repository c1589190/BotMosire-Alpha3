package io.mosire.bma3.memory.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.memory.ContextDigest;
import io.mosire.bma3.memory.ContextDigestStore;
import io.mosire.bma3.memory.ContextOptions;
import io.mosire.bma3.memory.MemoryOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The jar's own copy of the DSH session context, for the Agent that lives in it.
 *
 * <p>Three shapes, one tool, because every extra schema costs tokens on every request:
 * a catalog (default), one full entry ({@code view}), and a file export ({@code export=txt}).
 * The session is never an argument — it is whatever the panel is currently bound to, so a memory
 * cannot be pointed at somebody else's conversation by writing a parameter.</p>
 */
public final class MemoryContextTool implements AgentTool {

    /** Same width as the {@code memory_search} digest; presentation, not a tuning knob. */
    private static final int CATALOG_DIGEST_CHARS = 60;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ContextDigestStore digest;
    private final Supplier<ContextOptions> options;
    private final Supplier<MemoryOptions> memoryOptions;
    private final Supplier<String> boundSessionId;
    private final Path exportDir;

    public MemoryContextTool(ContextDigestStore digest, Supplier<ContextOptions> options,
            Supplier<MemoryOptions> memoryOptions, Supplier<String> boundSessionId,
            Path exportDir) {
        this.digest = digest;
        this.options = options;
        this.memoryOptions = memoryOptions;
        this.boundSessionId = boundSessionId;
        this.exportDir = exportDir;
    }

    @Override
    public String name() {
        return "memory_context";
    }

    @Override
    public String description() {
        return "查看 jar 自己保存的最近会话上下文副本（默认只给 #seq 缩略目录）。"
                + "view=seq 取某一条完整正文；export='txt' 导出到文件并回路径；"
                + "from/to 按事件序号过滤。这是一份可丢弃的副本，权威现场请用 DSH 的 session_* 工具。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
        return MemoryToolSupport.schema(MemoryToolSupport.property(
                "from", MemoryToolSupport.property(
                        "type", "integer",
                        "minimum", 1,
                        "description", "起始事件序号（含）"),
                "to", MemoryToolSupport.property(
                        "type", "integer",
                        "minimum", 1,
                        "description", "结束事件序号（含）"),
                "limit", MemoryToolSupport.property(
                        "type", "integer",
                        "minimum", 1,
                        "maximum", 50,
                        "description", "目录条数上限，默认取 memory.search.limit"),
                "view", MemoryToolSupport.property(
                        "type", "integer",
                        "minimum", 1,
                        "description", "取该事件序号的完整正文"),
                "export", MemoryToolSupport.property(
                        "type", "string",
                        "enum", List.of("txt"),
                        "description", "导出为 txt 文件，返回服务器生成的路径")),
                List.of());
    }

    @Override
    public ToolResult execute(ToolContext toolContext) {
        try {
            String session = session();
            if (session == null) {
                var payload = MemoryToolSupport.object();
                payload.put("session", "");
                payload.put("entries", MemoryToolSupport.JSON.createArrayNode());
                payload.put("note", "尚未收到任何上下文推送：插件没有连上，或还没有绑定会话。");
                return MemoryToolSupport.ok(payload);
            }
            long view = MemoryToolSupport.lng(toolContext, "view", 0L);
            if (view > 0) {
                return viewTool(session, view);
            }
            String export = MemoryToolSupport.string(toolContext, "export", "");
            if (!export.isEmpty()) {
                if (!"txt".equals(export.toLowerCase(Locale.ROOT))) {
                    return MemoryToolSupport.invalid("export 目前只支持 txt");
                }
                return exportTool(session, toolContext);
            }
            return catalog(session, toolContext);
        } catch (IllegalArgumentException failure) {
            return MemoryToolSupport.invalid(failure.getMessage());
        } catch (RuntimeException | IOException failure) {
            return ToolResult.error("MEMORY_CONTEXT_FAILED",
                    String.valueOf(failure.getMessage()));
        }
    }

    private ToolResult viewTool(String session, long eventSeq) {
        Optional<ContextDigest> found = digest.view(session, eventSeq);
        if (found.isEmpty()) {
            return MemoryToolSupport.missing("上下文副本里没有 seq=" + eventSeq);
        }
        ContextDigest entry = found.get();
        var payload = MemoryToolSupport.object();
        payload.put("session", entry.sessionId());
        payload.put("seq", entry.eventSeq());
        payload.put("kind", entry.kind());
        payload.put("speaker", entry.speaker());
        payload.put("at", entry.at() == null ? null : entry.at().toString());
        payload.put("chars", entry.chars());
        payload.put("truncated", entry.truncated());
        payload.put("text", entry.body());
        return MemoryToolSupport.ok(payload);
    }

    private ToolResult catalog(String session, ToolContext toolContext) {
        int limit = Math.min(MemoryToolSupport.integer(toolContext, "limit", 0) > 0
                ? MemoryToolSupport.integer(toolContext, "limit", 0)
                : memoryOptions.get().searchLimit(), 50);
        long from = MemoryToolSupport.lng(toolContext, "from", 0L);
        long to = MemoryToolSupport.lng(toolContext, "to", 0L);
        List<ContextDigest> rows = to > 0 || from > 0
                ? digest.crawl(session, Math.max(0L, from - 1), 50)
                : digest.recent(session, limit);
        var items = MemoryToolSupport.JSON.createArrayNode();
        int used = 0;
        long lastSeq = 0;
        for (ContextDigest row : rows) {
            if (to > 0 && row.eventSeq() > to) {
                break;
            }
            if (items.size() >= limit) {
                break;
            }
            String line = row.digest(CATALOG_DIGEST_CHARS);
            used += line.length();
            lastSeq = row.eventSeq();
            var item = MemoryToolSupport.JSON.createObjectNode();
            item.put("seq", row.eventSeq());
            item.put("at", row.at() == null ? null : row.at().toString());
            item.put("kind", row.kind());
            item.put("speaker", row.speaker());
            item.put("chars", row.chars());
            item.put("truncated", row.truncated());
            item.put("digest", line);
            items.add(item);
        }
        var payload = MemoryToolSupport.object();
        payload.put("session", session);
        payload.put("entries", items);
        payload.put("count", items.size());
        payload.put("chars", used);
        payload.put("catalog_chars", CATALOG_DIGEST_CHARS);
        payload.put("max_entries", options.get().maxEntries());
        payload.set("gaps", gaps(session));
        if (lastSeq > 0) {
            payload.put("last_seq", lastSeq);
        }
        return MemoryToolSupport.ok(payload);
    }

    private com.fasterxml.jackson.databind.node.ArrayNode gaps(String session) {
        var gaps = MemoryToolSupport.JSON.createArrayNode();
        for (var gap : digest.gaps(session)) {
            var node = MemoryToolSupport.JSON.createObjectNode();
            node.put("from", gap.fromSeq());
            node.put("to", gap.toSeq());
            gaps.add(node);
        }
        return gaps;
    }

    private ToolResult exportTool(String session, ToolContext toolContext) throws IOException {
        List<ContextDigest> rows = digest.recent(session, options.get().maxEntries());
        if (rows.isEmpty()) {
            return MemoryToolSupport.missing("上下文副本里没有会话 " + session);
        }
        StringBuilder body = new StringBuilder();
        body.append("# session=").append(session).append('\n');
        body.append("# events=").append(rows.get(0).eventSeq()).append("..")
                .append(rows.get(rows.size() - 1).eventSeq()).append('\n');
        body.append("# exported_at=").append(Instant.now()).append('\n');
        int truncated = 0;
        for (ContextDigest row : rows) {
            body.append("\n--- seq=").append(row.eventSeq())
                    .append(" kind=").append(row.kind())
                    .append(" speaker=").append(row.speaker())
                    .append(" at=").append(row.at() == null ? "-" : row.at())
                    .append(" truncated=").append(row.truncated() ? 1 : 0)
                    .append('\n');
            body.append(row.body()).append('\n');
            if (row.truncated()) {
                truncated++;
            }
        }
        Path directory = exportDir.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) {
            return ToolResult.error("EXPORT_REFUSED", "导出目录是符号链接，拒绝写入");
        }
        Path file = directory.resolve("context-" + safeName(session) + "-" + STAMP.format(
                Instant.now()) + ".txt");
        // CREATE_NEW fails on an existing target, so a planted symlink cannot be followed.
        Files.writeString(file, body.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        var payload = MemoryToolSupport.object();
        payload.put("path", file.toString());
        payload.put("session", session);
        payload.put("entries", rows.size());
        payload.put("chars", body.length());
        payload.put("truncated_entries", truncated);
        payload.put("gaps", gaps(session));
        return MemoryToolSupport.ok(payload);
    }

    private String session() {
        String bound = boundSessionId.get();
        if (bound != null && !bound.isBlank()) {
            return bound.trim();
        }
        return digest.lastSession().orElse(null);
    }

    private static String safeName(String session) {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < session.length() && name.length() < 64; i++) {
            char c = session.charAt(i);
            name.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
        }
        return name.length() == 0 ? "session" : name.toString();
    }
}
