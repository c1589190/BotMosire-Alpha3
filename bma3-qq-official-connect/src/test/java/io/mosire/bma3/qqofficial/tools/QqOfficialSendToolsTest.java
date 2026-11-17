package io.mosire.bma3.qqofficial.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.message.TextSendPolicy;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import io.mosire.bma3.qqofficial.send.OfficialQqReplySequenceStore;
import io.mosire.bma3.qqofficial.send.OfficialQqSendAccount;
import io.mosire.bma3.qqofficial.send.OfficialQqSendAccounts;
import io.mosire.bma3.qqofficial.send.OfficialQqSendMessage;
import io.mosire.bma3.qqofficial.send.OfficialQqSendService;
import io.mosire.bma3.qqofficial.send.OfficialQqSendTransport;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QqOfficialSendToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-11-17T08:00:00Z");
    private static final SourceAddress SOURCE =
            SourceAddress.parse("qq_official_group/acc-a/group-a");

    @TempDir
    Path temp;

    @Test
    void bothToolsExposeFixedNamesAndFailClosedSchemas() {
        try (Fixture fixture = new Fixture()) {
            assertThat(fixture.sendTool().name()).isEqualTo("qq_official_send_text");
            assertThat(fixture.replyTool().name()).isEqualTo("qq_official_reply");
            assertThat(fixture.sendTool().jsonSchema().get("required"))
                    .isEqualTo(List.of("accountId", "source", "text"));
            assertThat(fixture.replyTool().jsonSchema().get("required"))
                    .isEqualTo(List.of("accountId", "source", "text"));
            @SuppressWarnings("unchecked")
            Map<String, Object> replyProperties =
                    (Map<String, Object>) fixture.replyTool().jsonSchema().get("properties");
            assertThat(replyProperties).containsKeys("replyToMessageId", "eventId");
            assertThat(replyProperties).doesNotContainKey("confirm");
            assertThat(fixture.replyTool().jsonSchema().get("oneOf")).isEqualTo(List.of(
                    Map.of("required", List.of("replyToMessageId")),
                    Map.of("required", List.of("eventId"))));
            assertThat(fixture.sendTool().jsonSchema().get("additionalProperties"))
                    .isEqualTo(false);
        }
    }

    @Test
    void sendToolReturnsSafeOutcomeAndReplyValidatesSameSource() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ToolResult sent = fixture.sendTool().execute(call(Map.of(
                    "accountId", "acc-a",
                    "source", SOURCE.format(),
                    "text", "hello")));
            assertThat(sent.success()).isTrue();
            assertThat(JSON.readTree(sent.message()).get(0).path("state").asText())
                    .isEqualTo("SENT");

            fixture.board.append(new InboundMessage(SOURCE, "ROBOT1.0_IN", T0,
                    List.of(new Segment("text", "in")), Direction.INBOUND, "u", "U"));
            ToolResult replied = fixture.replyTool().execute(call(Map.of(
                    "accountId", "acc-a",
                    "source", SOURCE.format(),
                    "text", "reply",
                    "replyToMessageId", "ROBOT1.0_IN")));
            assertThat(replied.success()).isTrue();
            assertThat(JSON.readTree(replied.message()).get(0).path("state").asText())
                    .isEqualTo("SENT");

            ToolResult malformedSource = fixture.sendTool().execute(call(Map.of(
                    "accountId", "acc-a",
                    "source", "qq_official_group//SECRET",
                    "text", "hello")));
            assertThat(malformedSource.success()).isFalse();
            assertThat(malformedSource.code()).isEqualTo("INVALID_ARGUMENT");
            assertThat(malformedSource.message()).doesNotContain("SECRET");

            ToolResult eventWithoutStore = fixture.replyTool().execute(call(Map.of(
                    "accountId", "acc-a",
                    "source", SOURCE.format(),
                    "text", "event reply",
                    "eventId", "event-1")));
            assertThat(eventWithoutStore.success()).isFalse();
            assertThat(eventWithoutStore.code()).isEqualTo("EVENT_ID_REPLY_UNSUPPORTED");
        }
    }

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(
                AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(),
                arguments,
                AgentIdentity.external());
    }

    private final class Fixture implements AutoCloseable {

        private final SqliteMessageBoardStore board =
                new SqliteMessageBoardStore(temp.resolve("tools.db"));
        private final OfficialQqReplySequenceStore seq = new OfficialQqReplySequenceStore(
                temp.resolve("tools-seq.db"), Clock.fixed(T0, ZoneOffset.UTC));
        private final OfficialQqSendTransport transport = new OfficialQqSendTransport() {
            @Override
            public String accountId() {
                return "acc-a";
            }

            @Override
            public String sendGroup(String groupOpenid, OfficialQqSendMessage message) {
                return "external-group";
            }

            @Override
            public String sendUser(String userOpenid, OfficialQqSendMessage message) {
                return "external-user";
            }
        };
        private final OfficialQqSendAccounts accounts = accountId ->
                "acc-a".equals(accountId)
                        ? Optional.of(new OfficialQqSendAccount("acc-a", true, transport))
                        : Optional.empty();
        private final OfficialQqSendService service = new OfficialQqSendService(
                board, accounts, TextSendPolicy::defaults, seq,
                Clock.fixed(T0, ZoneOffset.UTC));

        private QqOfficialSendTextTool sendTool() {
            return new QqOfficialSendTextTool(service);
        }

        private QqOfficialReplyTool replyTool() {
            return new QqOfficialReplyTool(service);
        }

        @Override
        public void close() {
            seq.close();
            board.close();
        }
    }
}
