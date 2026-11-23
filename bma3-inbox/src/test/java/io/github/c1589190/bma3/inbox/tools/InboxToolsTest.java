package io.github.c1589190.bma3.inbox.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.AgentIdentity;
import io.github.c1589190.agentmosire.agentlib.permission.AgentPermissionSet;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.inbox.core.AppendItem;
import io.github.c1589190.bma3.inbox.core.DeliveryPolicy;
import io.github.c1589190.bma3.inbox.core.InboxPanel;
import io.github.c1589190.bma3.inbox.core.InboxService;
import io.github.c1589190.bma3.inbox.core.PluginHeartbeat;
import io.github.c1589190.bma3.inbox.core.SqliteInboxStore;
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
        var view = io.github.c1589190.bma3.core.source.SourceView.of(source, source);
        var entry = new io.github.c1589190.bma3.inbox.core.InboxEntry(1L, Instant.now(), "某人", text,
                List.of());
        service.append(AppendItem.of(view, entry, priority, Instant.now()));
    }

    @Test
    void exposesTheFullToolSurface() {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("tools.db"))) {
            List<AgentTool> tools = InboxTools.all(service(store));
            assertThat(tools).extracting(AgentTool::name).containsExactly(
                    "inbox_status", "inbox_list", "inbox_items", "inbox_get",
                    "inbox_ack", "inbox_dismiss", "inbox_wait", "inbox_wait_quiet",
                    "inbox_diagnose", "inbox_plugin_control");
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
            assertThat(detail.has("frozenRevision"))
                    .as("inbox_get must expose push eligibility for diagnostics")
                    .isTrue();
            assertThat(detail.path("frozenRevision").asLong()).isZero();

            JsonNode byIndex = mapper.readTree(tool(tools, "inbox_get")
                    .execute(call(Map.of("index", 1,
                            "revision", service.panel().revision()))).message());
            assertThat(byIndex.path("id").asText())
                    .isEqualTo(items.path("items").get(0).path("id").asText());
        }
    }

    @Test
    void panelAndGetExposeGenericChannelProjectionAndEvidence() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("channel-tools.db"))) {
            InboxService service = service(store, Duration.ofSeconds(30));
            String source = "qq_thread/channel-tools";
            var view = io.github.c1589190.bma3.core.source.SourceView.of(source, "逻辑会话");
            var entry = new io.github.c1589190.bma3.inbox.core.InboxEntry(1L, Instant.now(), "u",
                    "display body", List.of(), "onebot", Map.of("messageId", "m-1"));
            var item = service.appendProjected(AppendItem.projected(view, entry, 250,
                    Instant.now(), "onebot", Map.of("matched", 0)));
            service.appendEvidenceOnly(item.id(), "qq_official",
                    Map.of("observationId", "obs-2", "state", "CHANNEL_ONLY"));

            List<AgentTool> tools = InboxTools.all(service);
            JsonNode panel = mapper.readTree(tool(tools, "inbox_list")
                    .execute(call(Map.of())).message());
            JsonNode panelItem = panel.path("items").get(0);
            assertThat(panelItem.path("displayChannel").asText()).isEqualTo("onebot");
            assertThat(panelItem.path("channelSummary").path("evidence")).hasSize(1);

            JsonNode detail = mapper.readTree(tool(tools, "inbox_get")
                    .execute(call(Map.of("itemId", item.id()))).message());
            assertThat(detail.path("displayChannel").asText()).isEqualTo("onebot");
            assertThat(detail.path("channelSummary").path("evidence").get(0)
                    .path("observationId").asText()).isEqualTo("obs-2");
            assertThat(detail.path("entries").get(0).path("channel").asText())
                    .isEqualTo("onebot");
            assertThat(detail.path("entries").get(0).path("evidence").path("messageId").asText())
                    .isEqualTo("m-1");
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

    @Test
    void explicitButMalformedItemIdsMustNotDegradeToARevisionOnlyAck() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("tool-ids.db"))) {
            InboxService service = service(store);
            append(service, "qq_group/bot-1/1", 200, "消息");
            List<AgentTool> tools = InboxTools.all(service);
            String itemId = service.items().getFirst().id();

            java.util.List<Object> malformed = new java.util.ArrayList<>();
            malformed.add(List.of());                 // explicit empty list
            malformed.add("itm-not-a-list");          // wrong JSON type
            malformed.add(List.of(itemId, 7));        // mixed non-string element
            malformed.add(List.of(""));               // blank id

            for (Object raw : malformed) {
                java.util.Map<String, Object> args = new java.util.HashMap<>();
                args.put("itemIds", raw);
                ToolResult result = tool(tools, "inbox_ack").execute(call(args));
                assertThat(result.success()).as("itemIds=" + raw).isFalse();
                assertThat(result.message()).as("itemIds=" + raw).contains("itemIds");
                assertThat(service.panel().items())
                        .as("畸形 itemIds 不能退化到 revision-only/全量 ACK: " + raw)
                        .hasSize(1);
            }

            // Explicit null is covered twice: the strict parser rejects it, and the tool-context
            // boundary cannot even carry it (Map.copyOf rejects null values), so it fails closed
            // before any ACK instead of becoming a no-argument ACK.
            assertThat(InboxAckTool.strictItemIds(null)).isNull();
            assertThat(InboxAckTool.strictItemIds(List.of())).isNull();
            assertThat(InboxAckTool.strictItemIds("itm-not-a-list")).isNull();
            assertThat(InboxAckTool.strictItemIds(List.of(itemId, 7))).isNull();
            assertThat(InboxAckTool.strictItemIds(List.of(itemId))).containsExactly(itemId);
            java.util.Map<String, Object> withNullValue = new java.util.HashMap<>();
            withNullValue.put("itemIds", null);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> call(withNullValue))
                    .as("显式 null 参数在进入工具前就被拒绝（不会退化为全量 ACK）")
                    .isInstanceOf(NullPointerException.class);
            assertThat(service.panel().items()).hasSize(1);

            // A true manual no-argument ACK keeps its legacy "ack everything visible" meaning.
            ToolResult manual = tool(tools, "inbox_ack").execute(call(Map.of()));
            assertThat(manual.success()).as(manual.message()).isTrue();
            assertThat(service.panel().items()).isEmpty();
        }
    }

    @Test
    void waitQuietWaitsForTheSourceToSettleAndIsReadOnly() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("wait-quiet.db"))) {
            InboxService service = service(store, Duration.ofSeconds(30));
            append(service, "qq_private/bot-1/2", 300, "第一句");
            long revision = service.panel().revision();
            List<AgentTool> tools = InboxTools.all(service);

            ToolResult waited = tool(tools, "inbox_wait_quiet").execute(call(Map.of(
                    "source", "qq_private/bot-1/2", "quietMs", 50, "maxWaitMs", 1000)));
            assertThat(waited.success()).as(waited.message()).isTrue();
            JsonNode body = mapper.readTree(waited.message());
            assertThat(body.path("settled").asBoolean()).isTrue();
            assertThat(body.path("reason").asText()).isEqualTo("quiet");
            assertThat(body.path("source").asText()).isEqualTo("qq_private/bot-1/2");
            assertThat(body.path("items")).hasSize(1);
            assertThat(service.panel().revision()).isEqualTo(revision);
            assertThat(service.panel().items()).hasSize(1);
        }
    }

    @Test
    void waitQuietDoesNotReturnAckedHistoryOrImplyUnfrozenItemsAreAckable() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("wait-unacked.db"))) {
            InboxService service = service(store, Duration.ofSeconds(30));
            append(service, "qq_private/bot-1/3", 300, "旧消息");
            InboxPanel first = service.awaitPush(service.pushRevision(), Duration.ofSeconds(1));
            assertThat(first).isNotNull();
            service.ackPanel(first.revision());
            append(service, "qq_private/bot-1/3", 300, "新消息");

            List<AgentTool> tools = InboxTools.all(service);
            ToolResult waited = tool(tools, "inbox_wait_quiet").execute(call(Map.of(
                    "source", "qq_private/bot-1/3", "quietMs", 20, "maxWaitMs", 1000)));
            assertThat(waited.success()).as(waited.message()).isTrue();
            JsonNode body = mapper.readTree(waited.message());
            assertThat(body.path("items")).hasSize(1);
            assertThat(body.path("items").get(0).path("totalMessages").asInt()).isEqualTo(1);
            assertThat(body.path("items").get(0).path("ackable").asBoolean()).isFalse();
        }
    }

    @Test
    void waitQuietSupportsAnySourcePrefixAndReportsActiveAtMaxWait() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("wait-prefix.db"))) {
            InboxService service = service(store, Duration.ofSeconds(30));
            append(service, "bma3_task/example", 300, "任务");
            List<AgentTool> tools = InboxTools.all(service);

            ToolResult waited = tool(tools, "inbox_wait_quiet").execute(call(Map.of(
                    "sourcePrefix", "bma3_task", "quietMs", 10, "maxWaitMs", 50)));
            assertThat(waited.success()).as(waited.message()).isTrue();
            JsonNode body = mapper.readTree(waited.message());
            assertThat(body.path("matchedBy").asText()).isEqualTo("sourcePrefix");
            assertThat(body.path("items")).hasSize(1);
            assertThat(body.path("reason").asText()).isIn("quiet", "max_wait");
        }
    }

    @Test
    void waitQuietAcceptsAPrefixWithoutTrailingSlashAndReportsProcessability() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("wait-prefix2.db"))) {
            InboxService service = service(store);
            List<AgentTool> tools = InboxTools.all(service);
            ToolResult waited = tool(tools, "inbox_wait_quiet").execute(call(Map.of(
                    "sourcePrefix", "bma3_debug", "quietMs", 10, "maxWaitMs", 100)));
            assertThat(waited.success()).as(waited.message()).isTrue();
            JsonNode body = mapper.readTree(waited.message());
            assertThat(body.path("settled").asBoolean()).isTrue();
            assertThat(body.path("items")).isEmpty();
            assertThat(body.path("allAckable").asBoolean()).isTrue();
            assertThat(body.path("processable").asBoolean()).isTrue();
        }
    }

    @Test
    void waitQuietRejectsMissingSourceAndInvalidRange() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("wait-invalid.db"))) {
            InboxService service = service(store);
            List<AgentTool> tools = InboxTools.all(service);

            ToolResult missing = tool(tools, "inbox_wait_quiet").execute(call(Map.of()));
            assertThat(missing.success()).isFalse();
            assertThat(missing.message()).contains("source");

            ToolResult invalid = tool(tools, "inbox_wait_quiet").execute(call(Map.of(
                    "source", "qq_private/bot-1/2", "quietMs", 100, "maxWaitMs", 50)));
            assertThat(invalid.success()).isFalse();
            assertThat(invalid.message()).contains("maxWaitMs");
        }
    }

    @Test
    void diagnoseAndPluginControlExposeHeartbeatWithoutBodyOrCredentials() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("diagnose.db"))) {
            InboxService service = service(store);
            service.pluginHeartbeat(new PluginHeartbeat(
                    "session-1", true, "open", 7L, "mcp-1", "", "", "env-file", true,
                    Instant.now(), Map.of(
                            "version", "0.0.7-hotfix1",
                            "toolRegistration", Map.of("registered", false,
                                    "attempts", 2, "lastError", "tools_service_unavailable"))));
            List<AgentTool> tools = InboxTools.all(service, "0.0.7-hotfix1");

            JsonNode diagnose = mapper.readTree(tool(tools, "inbox_diagnose")
                    .execute(call(Map.of())).message());
            assertThat(diagnose.path("version").asText()).isEqualTo("0.0.7-hotfix1");
            assertThat(diagnose.path("heartbeat").path("sessionId").asText())
                    .isEqualTo("session-1");
            assertThat(diagnose.path("heartbeat").path("diagnostics").path("version").asText())
                    .isEqualTo("0.0.7-hotfix1");
            assertThat(diagnose.path("waitQuiet").path("defaultQuietMs").asInt())
                    .isEqualTo(2000);
            assertThat(diagnose.path("waitPolicy").path("defaultQuietSeconds").asInt())
                    .isZero();
            assertThat(diagnose.toString()).doesNotContain("SECRET_BODY", "Bearer", "Cookie");

            ToolResult controlResult = tool(tools, "inbox_plugin_control")
                    .execute(call(Map.of("action", "restore", "sessionId", "session-9")));
            assertThat(controlResult.success()).as(controlResult.message()).isTrue();
            JsonNode control = mapper.readTree(controlResult.message());
            assertThat(control.path("control").path("action").asText()).isEqualTo("restore");
            assertThat(control.path("control").path("sessionId").asText()).isEqualTo("session-9");
            assertThat(service.pendingControl().action()).isEqualTo("restore");
            assertThat(service.pendingControl().sessionId()).isEqualTo("session-9");

            ToolResult noConfirm = tool(tools, "inbox_plugin_control")
                    .execute(call(Map.of("action", "recover")));
            assertThat(noConfirm.success()).isFalse();
            assertThat(noConfirm.message()).contains("confirm");

            ToolResult recover = tool(tools, "inbox_plugin_control")
                    .execute(call(Map.of("action", "recover", "confirm", true)));
            assertThat(recover.success()).as(recover.message()).isTrue();
            JsonNode recoverBody = mapper.readTree(recover.message());
            assertThat(recoverBody.path("control").path("action").asText()).isEqualTo("recover");
            assertThat(service.pendingControl().action()).isEqualTo("recover");
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
