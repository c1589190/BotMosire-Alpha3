package io.github.c1589190.bma3.memory.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.AgentIdentity;
import io.github.c1589190.agentmosire.agentlib.permission.AgentPermissionSet;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.memory.ContextEntry;
import io.github.c1589190.bma3.memory.ContextOptions;
import io.github.c1589190.bma3.memory.MemoryOptions;
import io.github.c1589190.bma3.memory.SqliteContextDigestStore;
import io.github.c1589190.bma3.memory.SqliteMemoryStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tool-level acceptance for the context copy: A8 catalog, A14 export, A15 dangling pointer, and the
 * rule that the session is never something the model passes in.
 */
class MemoryContextToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Supplier<MemoryOptions> MEMORY = MemoryOptions::defaults;
    private static final Supplier<ContextOptions> CONTEXT = ContextOptions::defaults;
    private static final String SESSION = "session-abc";
    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    @TempDir
    Path root;

    private SqliteMemoryStore store;
    private SqliteContextDigestStore digest;
    private List<AgentTool> tools;

    @BeforeEach
    void setUp() {
        store = new SqliteMemoryStore(root.resolve("memory.db"), MEMORY, UnaryOperator.identity());
        digest = new SqliteContextDigestStore(root.resolve("memory.db"), CONTEXT,
                UnaryOperator.identity());
        tools = MemoryTools.all(store, MEMORY, seq -> seq == 42L, digest, CONTEXT,
                () -> SESSION, root.resolve("exports"), () -> root.resolve("downloads"));
    }

    @AfterEach
    void tearDown() {
        digest.close();
        store.close();
    }

    private AgentTool tool(String name) {
        return tools.stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("缺少工具 " + name));
    }

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), arguments, AgentIdentity.external());
    }

    private JsonNode payload(String name, Map<String, Object> arguments) {
        ToolResult result = tool(name).execute(call(arguments));
        assertThat(result.success()).as(result.message()).isTrue();
        try {
            return JSON.readTree(result.message());
        } catch (Exception failure) {
            throw new AssertionError("工具返回的不是 JSON: " + result.message(), failure);
        }
    }

    private void push(long... seqs) {
        List<ContextEntry> batch = java.util.Arrays.stream(seqs)
                .mapToObj(seq -> new ContextEntry(seq, seq % 2 == 0 ? "assistant" : "user",
                        "owner", NOW, "第 " + seq + " 条上下文"))
                .toList();
        digest.append(SESSION, seqs[seqs.length - 1], batch, NOW);
    }

    @Test
    void tool_surface_gains_memory_context_and_still_has_no_delete() {
        assertThat(tools).extracting(AgentTool::name).containsExactly(
                "memory_write", "memory_search", "memory_view", "memory_rate", "memory_context");
    }

    @Test
    void a8_catalog_lists_digests_of_the_bound_session() {
        push(1, 2, 3);

        JsonNode catalog = payload("memory_context", Map.of());

        assertThat(catalog.get("session").asText()).isEqualTo(SESSION);
        assertThat(catalog.get("count").asInt()).isEqualTo(3);
        assertThat(catalog.get("entries").get(0).get("seq").asLong()).isEqualTo(1);
        assertThat(catalog.get("entries").get(0).get("digest").asText()).contains("第 1 条上下文");
        assertThat(catalog.get("last_seq").asLong()).isEqualTo(3);
    }

    @Test
    void a8_a_range_and_a_limit_bound_the_catalog() {
        push(1, 2, 3, 4);

        JsonNode windowed = payload("memory_context", Map.of("from", 2, "to", 3, "limit", 10));
        assertThat(windowed.get("entries")).hasSize(2);
        assertThat(windowed.get("entries").get(0).get("seq").asLong()).isEqualTo(2);

        JsonNode capped = payload("memory_context", Map.of("limit", 2));
        assertThat(capped.get("entries")).hasSize(2);
        assertThat(capped.get("entries").get(1).get("seq").asLong()).isEqualTo(4);
    }

    @Test
    void view_returns_one_full_body() {
        push(1, 2);

        JsonNode viewed = payload("memory_context", Map.of("view", 2));
        assertThat(viewed.get("seq").asLong()).isEqualTo(2);
        assertThat(viewed.get("text").asText()).isEqualTo("第 2 条上下文");
        assertThat(viewed.get("kind").asText()).isEqualTo("assistant");
    }

    @Test
    void a14_export_writes_a_txt_file_with_bodies() throws Exception {
        push(1, 2);

        JsonNode exported = payload("memory_context", Map.of("export", "txt"));
        Path file = Path.of(exported.get("path").asText());
        assertThat(file).exists();
        assertThat(file.getParent()).isEqualTo(root.resolve("exports").toAbsolutePath().normalize());
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("第 1 条上下文", "第 2 条上下文", "# session=" + SESSION);
    }

    @Test
    void export_rejects_anything_but_txt() {
        ToolResult refused = tool("memory_context").execute(call(Map.of("export", "pdf")));
        assertThat(refused.success()).isFalse();
        assertThat(refused.code()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void a15_a_dangling_context_ref_is_reported_stale() {
        push(1);
        long alive = payload("memory_write", Map.of(
                "text", "站会固定在周二",
                "context_ref", "session=" + SESSION + "#seq=1")).get("id").asLong();
        long forged = payload("memory_write", Map.of(
                "text", "这条指向不存在的现场",
                "context_ref", "session=" + SESSION + "#seq=999")).get("id").asLong();

        assertThat(payload("memory_view", Map.of("id", alive)).get("stale").asBoolean()).isFalse();
        assertThat(payload("memory_view", Map.of("id", forged)).get("stale").asBoolean()).isTrue();
    }

    @Test
    void a15_an_unparseable_context_ref_is_stale_rather_than_trusted() {
        long id = payload("memory_write", Map.of(
                "text", "随手写了个锚点",
                "context_ref", "第二个回合")).get("id").asLong();

        assertThat(payload("memory_view", Map.of("id", id)).get("stale").asBoolean()).isTrue();
    }

    @Test
    void a_memory_without_a_context_ref_is_not_stale() {
        long id = payload("memory_write", Map.of("text", "没有锚点的记忆")).get("id").asLong();
        assertThat(payload("memory_view", Map.of("id", id)).get("stale").asBoolean()).isFalse();
    }

    @Test
    void without_any_push_the_catalog_says_so_instead_of_failing() {
        SqliteContextDigestStore empty = new SqliteContextDigestStore(
                root.resolve("empty.db"), CONTEXT, UnaryOperator.identity());
        try {
            MemoryContextTool lonely = new MemoryContextTool(empty, CONTEXT, MEMORY,
                    () -> "", root.resolve("exports"));
            ToolResult result = lonely.execute(call(Map.of()));
            assertThat(result.success()).isTrue();
            JsonNode body = JSON.readTree(result.message());
            assertThat(body.get("entries")).isEmpty();
            assertThat(body.get("note").asText()).contains("尚未收到任何上下文推送");
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            empty.close();
        }
    }
}
