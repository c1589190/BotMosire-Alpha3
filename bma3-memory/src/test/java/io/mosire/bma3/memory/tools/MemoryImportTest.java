package io.mosire.bma3.memory.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.memory.MemoryHit;
import io.mosire.bma3.memory.MemoryItem;
import io.mosire.bma3.memory.MemoryOptions;
import io.mosire.bma3.memory.SqliteMemoryStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P3: importing a whole file. The Agent reads what QQ delivered instead of retyping it as a tool
 * argument, and the three guards (inside downloads, regular file, size ceiling) are what keep that
 * from becoming an arbitrary file read.
 */
class MemoryImportTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    private Path downloads;
    private SqliteMemoryStore store;
    private List<AgentTool> tools;

    @BeforeEach
    void setUp() throws IOException {
        downloads = Files.createDirectories(root.resolve("downloads"));
        store = new SqliteMemoryStore(root.resolve("memory.db"), MemoryOptions::defaults,
                UnaryOperator.identity());
        tools = MemoryTools.all(store, MemoryOptions::defaults, seq -> false, () -> downloads);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private AgentTool tool(String name) {
        return tools.stream().filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少工具 " + name));
    }

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), arguments, AgentIdentity.external());
    }

    private ToolResult invoke(String name, Map<String, Object> arguments) {
        return tool(name).execute(call(arguments));
    }

    private static JsonNode payload(ToolResult result) {
        assertThat(result.success()).as(result.message()).isTrue();
        try {
            return JSON.readTree(result.message());
        } catch (Exception failure) {
            throw new AssertionError("工具返回的不是 JSON: " + result.message(), failure);
        }
    }

    private Path write(String name, String body) throws IOException {
        Path file = downloads.resolve(name);
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void aWholeFileBecomesOneMemoryItem() throws IOException {
        write("notes.txt", "周一站会：讨论长期记忆\n周二：标签优先检索");

        JsonNode body = payload(invoke("memory_write", Map.of("file", "notes.txt")));

        assertThat(body.get("count").asInt()).isEqualTo(1);
        assertThat(body.get("tags").asText()).isEqualTo("notes");
        long id = body.get("ids").get(0).asLong();
        Optional item = item(id);
        assertThat(item.found.text()).contains("标签优先检索");
        assertThat(item.found.sourceKind()).isEqualTo("import");
        assertThat(item.found.sourceRef()).isEqualTo("notes.txt");
        assertThat(item.found.tags()).isEqualTo("notes");
    }

    @Test
    void aLongFileIsSplitAndNothingIsDropped() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int line = 1; line <= 40; line += 1) {
            body.append("第").append(line).append("行：记忆体要能装下整份导出\n");
        }
        write("long.txt", body.toString());
        Supplier<MemoryOptions> tiny = () -> new MemoryOptions(20, 10.0, 1.0, -1.0, -0.9, 100,
                1_048_576);
        SqliteMemoryStore small = new SqliteMemoryStore(root.resolve("small.db"), tiny,
                UnaryOperator.identity());
        try {
            List<AgentTool> smallTools = MemoryTools.all(small, tiny, seq -> false,
                    () -> downloads);
            AgentTool write = smallTools.stream()
                    .filter(candidate -> candidate.name().equals("memory_write"))
                    .findFirst().orElseThrow();

            ToolResult result = write.execute(call(Map.of("file", "long.txt", "tags", "导入 会议")));

            JsonNode payload = payload(result);
            assertThat(payload.get("count").asInt()).isGreaterThan(1);
            int restored = 0;
            for (JsonNode id : payload.get("ids")) {
                MemoryItem item = small.view(id.asLong(), java.time.Instant.now(), false)
                        .orElseThrow();
                assertThat(item.text().codePointCount(0, item.text().length()))
                        .isLessThanOrEqualTo(100);
                restored += 1;
            }
            assertThat(restored).isEqualTo(payload.get("count").asInt());
        } finally {
            small.close();
        }
    }

    @Test
    void aPathOutsideDownloadsIsRefused() throws IOException {
        write("inside.txt", "ok");
        Files.writeString(root.resolve("secret.txt"), "机密", StandardCharsets.UTF_8);

        ToolResult result = invoke("memory_write", Map.of("file", "../secret.txt"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("downloads");
    }

    @Test
    void aSymlinkIsRefused() throws IOException {
        Path outside = Files.writeString(root.resolve("secret.txt"), "机密",
                StandardCharsets.UTF_8);
        Files.createSymbolicLink(downloads.resolve("link.txt"), outside);

        ToolResult result = invoke("memory_write", Map.of("file", "link.txt"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("符号链接");
    }

    @Test
    void anOversizeFileIsRefused() throws IOException {
        write("big.txt", "x".repeat(4000));
        Supplier<MemoryOptions> tight = () -> new MemoryOptions(20, 10.0, 1.0, -1.0, -0.9, 8000,
                1000);
        List<AgentTool> tightTools = MemoryTools.all(store, tight, seq -> false, () -> downloads);
        AgentTool write = tightTools.stream()
                .filter(candidate -> candidate.name().equals("memory_write"))
                .findFirst().orElseThrow();

        ToolResult result = write.execute(call(Map.of("file", "big.txt")));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("字节");
    }

    @Test
    void aMissingFileIsRefused() {
        ToolResult result = invoke("memory_write", Map.of("file", "absent.txt"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("不存在");
    }

    @Test
    void textAndFileTogetherAreRefused() throws IOException {
        write("notes.txt", "正文");

        ToolResult result = invoke("memory_write", Map.of("text", "hello", "file", "notes.txt"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("只能给一个");
    }

    @Test
    void neitherTextNorFileIsRefused() {
        ToolResult result = invoke("memory_write", Map.of("tags", "只有标签"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("至少要给一个");
    }

    @Test
    void anImportCannotClaimAnotherSourceKind() throws IOException {
        write("notes.txt", "正文");

        payload(invoke("memory_write", Map.of("file", "notes.txt", "source_kind", "user_said")));

        List<MemoryHit> hits = store.search("正文", 10, 0);
        assertThat(hits).isNotEmpty();
        assertThat(item(hits.get(0).id()).found.sourceKind()).isEqualTo("import");
    }

    @Test
    void withoutAnImportDirectoryTheToolSaysSo() throws IOException {
        write("notes.txt", "正文");
        List<AgentTool> withoutDir = MemoryTools.all(store, MemoryOptions::defaults, seq -> false);
        AgentTool write = withoutDir.stream()
                .filter(candidate -> candidate.name().equals("memory_write"))
                .findFirst().orElseThrow();

        ToolResult result = write.execute(call(Map.of("file", "notes.txt")));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("导入不可用");
    }

    @Test
    void anEmptyFileIsRefused() throws IOException {
        write("empty.txt", "   \n\n  ");

        ToolResult result = invoke("memory_write", Map.of("file", "empty.txt"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("没有可导入的正文");
    }

    private Optional item(long id) {
        java.util.Optional<MemoryItem> found = store.view(id, java.time.Instant.now(), false);
        assertThat(found).as("memory_view 找不到 " + id).isPresent();
        return new Optional(found.orElseThrow());
    }

    /** Tiny wrapper so assertions read as `item(id).found.text()`. */
    private static final class Optional {
        final MemoryItem found;

        Optional(MemoryItem found) {
            this.found = found;
        }
    }
}
