package io.mosire.bma3.inbox.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
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

class InboxToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesStatusListAndAckTools() throws Exception {
        try (SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("inbox.db"))) {
            InboxService service = new InboxService(store,
                    new DeliveryPolicy(200, Duration.ofSeconds(1), Duration.ofSeconds(30),
                            Duration.ofMinutes(10), 3));
            service.append(new AppendItem("qq_private/bot-1/1", "hello", 300,
                    Instant.now().minusSeconds(5)));
            List<AgentTool> tools = InboxTools.all(service);
            assertThat(tools).extracting(AgentTool::name)
                    .containsExactly("inbox_status", "inbox_list", "inbox_ack");

            ToolContext empty = call(Map.of());
            assertThat(tools.get(0).execute(empty).message()).contains("\"available\":1");
            assertThat(tools.get(1).execute(empty).message()).contains("hello");

            var due = service.due(Instant.now());
            assertThat(due).hasSize(1);
            var ack = tools.get(2).execute(call(Map.of("deliveryId", due.getFirst().id())));
            assertThat(ack.success()).isTrue();
            assertThat(new ObjectMapper().readTree(ack.message()).path("acked").asBoolean())
                    .isTrue();
        }
    }

    private static ToolContext call(Map<String, Object> args) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), args, AgentIdentity.external());
    }
}
