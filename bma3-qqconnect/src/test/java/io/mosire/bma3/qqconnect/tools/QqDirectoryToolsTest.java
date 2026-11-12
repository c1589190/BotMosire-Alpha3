package io.mosire.bma3.qqconnect.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import io.mosire.bma3.qqconnect.account.AccountRegistry;
import io.mosire.bma3.qqconnect.directory.QqDirectoryStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Directory-backed reads: member card/title and the generic search. */
class QqDirectoryToolsTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void memberListAndSearchReadFromTheDirectory() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(tempDir.resolve("bot.db"));
             QqExtrasStore extras = new QqExtrasStore(tempDir.resolve("extras.db"));
             QqDirectoryStore directory = new QqDirectoryStore(
                     tempDir.resolve("qq-directory.db"))) {
            directory.upsertGroup("bot-1", "955432692", "钓鱼群", 42);
            directory.upsertMember(new QqDirectoryStore.Member("bot-1", "955432692", "20002",
                    "南沐秋", "群名片小王", "admin", "大肥鱼", null, Instant.now()));
            board.append(new InboundMessage(
                    SourceAddress.parse("qq_group/bot-1/955432692"), "m1", Instant.now(),
                    List.of(new Segment("text", "今天钓鱼吗")), Direction.INBOUND,
                    "20002", "南沐秋", "群名片小王", "admin"));

            AccountRegistry registry = new AccountRegistry((config, listener) -> null, event -> { });
            QqToolContext context = new QqToolContext(registry, board, board, tempDir, extras,
                    null, null, directory);

            ToolResult members = new QqGroupMembersTool(context).execute(call(Map.of(
                    "source", "qq_group/bot-1/955432692")));
            assertThat(members.success()).isTrue();
            JsonNode memberJson = mapper.readTree(members.message());
            assertThat(memberJson.path("total").asInt()).isEqualTo(1);
            assertThat(memberJson.path("members").get(0).path("card").asText())
                    .isEqualTo("群名片小王");
            assertThat(memberJson.path("members").get(0).path("title").asText())
                    .isEqualTo("大肥鱼");

            ToolResult search = new QqSearchTool(context).execute(call(Map.of(
                    "query", "钓鱼", "scope", "all")));
            assertThat(search.success()).isTrue();
            JsonNode searchJson = mapper.readTree(search.message());
            assertThat(searchJson.path("messages")).hasSize(1);
            assertThat(searchJson.path("messages").get(0).path("source").asText())
                    .isEqualTo("qq_group/bot-1/955432692");
            assertThat(searchJson.path("groups").get(0).path("name").asText())
                    .isEqualTo("钓鱼群");

            ToolResult people = new QqSearchTool(context).execute(call(Map.of(
                    "query", "小王", "scope", "people")));
            JsonNode peopleJson = mapper.readTree(people.message());
            assertThat(peopleJson.path("people").get(0).path("personId").asText())
                    .isEqualTo("qq_person_id/20002");
        }
    }

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), arguments, AgentIdentity.external());
    }
}
