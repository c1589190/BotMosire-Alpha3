package io.mosire.bma3.inbox.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.inbox.core.AppendItem;
import io.mosire.bma3.inbox.core.DeliveryPolicy;
import io.mosire.bma3.inbox.core.InboxService;
import io.mosire.bma3.inbox.core.SqliteInboxStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tool surface: list/get expose the items beyond the panel window, ack is per item, and a stale
 * revision cannot ack the wrong row.
 */
class InboxToolsTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private InboxService service(SqliteInboxStore store) {
        return service(store, Duration.ofMillis(200));
    }

    private InboxService service(SqliteInboxStore store, Duration normalDelay) {
        return new InboxService(store,
                new DeliveryPolicy(300, Duration.ofMillis(80), normalDelay));
    }

    private static void append(InboxService service, String source, int priority, String text) {
        var view = io.mosire.bma3.core.source.SourceView.of(source, source);
        var entry = new io.mosire.bma3.inbox.core.InboxEntry(1L, Instant.now(), "某人", text,
                List.of());
        service.append(AppendItem.of(view, entry, priority, Instant.now()));
    }

    @Test
    void exposesTheFullToolSurface() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("tools.db"))) {
            List<AgentTool> tools = InboxTools.all(service(store));
            assertThat(tools).extracting(AgentTool::name).containsExactly(
                    "inbox_status", "inbox_list", "inbox_items", "inbox_get",
                    "inbox_ack", "inbox_wait");
        }
    }

    @Test
    void itemsAndGetReachBeyondThePanelWindow() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("items.db"))) {
            InboxService service = service(store);
            for (int index = 1; index <= 8; index++) {
                append(service, "qq_group/bot-1/" + index, 100 + index, "消息" + index);
            }
            List<AgentTool> tools = InboxTools.all(service);

            JsonNode items = mapper.readTree(tool(tools, "inbox_items")
                    .execute(call(Map.of())).message());
            assertThat(items.path("total").asInt()).isEqualTo(8);
            assertThat(items.path("items")).hasSize(8);

            String lastId = items.path("items").get(7).path("id").asText();
            JsonNode detail = mapper.readTree(tool(tools, "inbox_get")
                    .execute(call(Map.of("itemId", lastId))).message());
            assertThat(detail.path("id").asText()).isEqualTo(lastId);
            assertThat(detail.path("entries")).hasSize(1);
            assertThat(detail.path("entries").get(0).path("text").asText()).isEqualTo("消息1");
        }
    }

    @Test
    void ackIsPerItemAndRejectsAStaleRevision() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("ack.db"))) {
            InboxService service = service(store);
            append(service, "qq_group/bot-1/1", 100, "低优先");
            append(service, "qq_private/bot-1/2", 300, "高优先");
            List<AgentTool> tools = InboxTools.all(service);
            long revision = service.panel().revision();

            ToolResult acked = tool(tools, "inbox_ack").execute(call(Map.of(
                    "indices", List.of(1), "revision", revision)));
            assertThat(acked.success()).as(acked.message()).isTrue();
            JsonNode body = mapper.readTree(acked.message());
            assertThat(body.path("remaining").asInt()).isEqualTo(1);

            JsonNode remaining = mapper.readTree(tool(tools, "inbox_list")
                    .execute(call(Map.of())).message());
            assertThat(remaining.path("items")).hasSize(1);
            assertThat(remaining.path("items").get(0).path("source").asText())
                    .isEqualTo("qq_group/bot-1/1");

            ToolResult stale = tool(tools, "inbox_ack").execute(call(Map.of(
                    "indices", List.of(1), "revision", revision)));
            assertThat(stale.success()).isFalse();
            assertThat(stale.message()).contains("REVISION_CONFLICT");
        }
    }

    @Test
    void waitTimesOutWithoutAcking() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("wait.db"))) {
            InboxService service = service(store, Duration.ofSeconds(30));
            append(service, "qq_group/bot-1/1", 100, "低优先");
            List<AgentTool> tools = InboxTools.all(service);

            ToolResult waited = tool(tools, "inbox_wait")
                    .execute(call(Map.of("timeoutMs", 50)));
            assertThat(waited.success()).isTrue();
            JsonNode body = mapper.readTree(waited.message());
            assertThat(body.path("changed").asBoolean()).isFalse();
            assertThat(body.path("reason").asText()).isEqualTo("timeout");
            assertThat(service.panel().items()).as("wait 只读，不能替 Agent ack").hasSize(1);
        }
    }

    private static AgentTool tool(List<AgentTool> tools, String name) {
        return tools.stream().filter(tool -> name.equals(tool.name())).findFirst().orElseThrow();
    }

    private static ToolContext call(Map<String, Object> args) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), args, AgentIdentity.external());
    }
}
