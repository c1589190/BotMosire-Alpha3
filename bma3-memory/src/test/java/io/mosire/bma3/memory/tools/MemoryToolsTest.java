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
import io.mosire.bma3.memory.MemoryOptions;
import io.mosire.bma3.memory.MemoryStore;
import io.mosire.bma3.memory.SqliteMemoryStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tool-surface acceptance: A6 (no delete/forget) plus the argument contracts of the four tools. */
class MemoryToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Supplier<MemoryOptions> OPTIONS = MemoryOptions::defaults;

    @TempDir
    Path root;

    private SqliteMemoryStore store;
    private List<AgentTool> tools;

    @BeforeEach
    void setUp() {
        store = new SqliteMemoryStore(root.resolve("memory.db"), OPTIONS, UnaryOperator.identity());
        tools = MemoryTools.all(store, OPTIONS, seq -> seq == 42L);
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

    @Test
    void a6_tool_surface_has_no_delete_or_forget() {
        assertThat(tools).extracting(AgentTool::name)
                .containsExactly("memory_write", "memory_search", "memory_view", "memory_rate");
        assertThat(MemoryStore.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("delete"))
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("forget"))
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("remove"));
    }

    @Test
    void write_search_view_rate_roundtrip() {
        JsonNode written = payload(invoke("memory_write", Map.of(
                "text", "站会固定在周二上午十点",
                "tags", "例会 排期")));
        long id = written.get("id").asLong();
        assertThat(id).isPositive();

        JsonNode hits = payload(invoke("memory_search", Map.of("query", "例会")));
        assertThat(hits.get("items")).hasSize(1);
        assertThat(hits.get("items").get(0).get("id").asLong()).isEqualTo(id);
        assertThat(hits.get("items").get(0).get("digest").asText()).contains("站会固定在周二");

        JsonNode viewed = payload(invoke("memory_view", Map.of("id", id)));
        assertThat(viewed.get("text").asText()).isEqualTo("站会固定在周二上午十点");
        assertThat(viewed.get("tags").asText()).isEqualTo("例会 排期");
        assertThat(viewed.get("use_count").asLong()).isEqualTo(1);

        JsonNode rated = payload(invoke("memory_rate", Map.of("id", id, "value", "useful")));
        assertThat(rated.get("weight").asDouble()).isEqualTo(1.0);
    }

    @Test
    void search_pages_with_the_returned_cursor() {
        for (int i = 0; i < 3; i++) {
            payload(invoke("memory_write", Map.of("text", "站会第 " + i + " 次记录")));
        }
        JsonNode first = payload(invoke("memory_search", Map.of("query", "站会", "limit", 2)));
        assertThat(first.get("count").asInt()).isEqualTo(2);
        assertThat(first.get("next_cursor").asText()).isEqualTo("2");

        JsonNode second = payload(invoke("memory_search",
                Map.of("query", "站会", "limit", 2, "cursor", first.get("next_cursor").asText())));
        assertThat(second.get("count").asInt()).isEqualTo(1);
    }

    @Test
    void mute_removes_the_row_from_search_but_not_from_view() {
        long id = payload(invoke("memory_write", Map.of("text", "站会固定在周二"))).get("id").asLong();
        payload(invoke("memory_rate", Map.of("id", id, "value", "mute")));
        assertThat(payload(invoke("memory_search", Map.of("query", "站会")))
                .get("items")).isEmpty();
        assertThat(payload(invoke("memory_view", Map.of("id", id))).get("muted").asBoolean())
                .isTrue();
    }

    @Test
    void write_rejects_unknown_source_kind() {
        ToolResult result = invoke("memory_write",
                Map.of("text", "随便写的", "source_kind", "made-up"));
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(result.message()).contains("source_kind");
    }

    @Test
    void write_requires_a_real_board_seq_for_message_board_sources() {
        ToolResult missing = invoke("memory_write",
                Map.of("text", "引用一条消息", "source_kind", "message_board",
                        "source_ref", "41"));
        assertThat(missing.success()).isFalse();
        assertThat(missing.code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(missing.message()).contains("local_seq=41");

        ToolResult notANumber = invoke("memory_write",
                Map.of("text", "引用一条消息", "source_kind", "message_board",
                        "source_ref", "abc"));
        assertThat(notANumber.success()).isFalse();

        JsonNode accepted = payload(invoke("memory_write",
                Map.of("text", "引用一条消息", "source_kind", "message_board",
                        "source_ref", "42")));
        assertThat(accepted.get("id").asLong()).isPositive();
    }

    @Test
    void write_rejects_text_over_the_configured_limit() {
        Supplier<MemoryOptions> tiny = () -> new MemoryOptions(20, 10.0, 1.0, -1.0, -0.9, 5);
        List<AgentTool> small = MemoryTools.all(store, tiny, seq -> true);
        ToolResult result = small.get(0).execute(call(Map.of("text", "123456")));
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(result.message()).contains("上限 5");
    }

    @Test
    void view_reports_not_found_for_unknown_ids() {
        ToolResult result = invoke("memory_view", Map.of("id", 999));
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void rate_rejects_a_second_vote_after_mute() {
        long id = payload(invoke("memory_write", Map.of("text", "站会固定在周二"))).get("id").asLong();
        payload(invoke("memory_rate", Map.of("id", id, "value", "mute")));
        ToolResult result = invoke("memory_rate", Map.of("id", id, "value", "useful"));
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("MEMORY_RATE_REJECTED");
    }

    @Test
    void search_rejects_a_non_numeric_cursor() {
        ToolResult result = invoke("memory_search", Map.of("query", "站会", "cursor", "abc"));
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("INVALID_ARGUMENT");
    }
}
