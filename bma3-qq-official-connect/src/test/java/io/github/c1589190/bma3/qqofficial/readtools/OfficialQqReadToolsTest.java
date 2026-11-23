package io.github.c1589190.bma3.qqofficial.readtools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.AgentIdentity;
import io.github.c1589190.agentmosire.agentlib.permission.AgentPermissionSet;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.core.message.Direction;
import io.github.c1589190.bma3.core.message.InboundMessage;
import io.github.c1589190.bma3.core.message.Segment;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.core.store.SqliteMessageBoardStore;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountErrorKind;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountState;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountStatus;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqApiException;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqErrorKind;
import io.github.c1589190.bma3.qqofficial.directory.OfficialQqDirectoryStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialQqReadToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-11-17T00:00:00Z");
    private static final String GROUP_A = "qq_official_group/bot-a/group-a";
    private static final String PRIVATE_A = "qq_official_private/bot-a/user-a";
    private static final String GROUP_B = "qq_official_group/bot-b/group-b";

    @TempDir
    Path tempDir;

    @Test
    void discoversAllNineReadToolsWithObjectSchemas() {
        try (Fixture fixture = fixture(List.of(status("bot-a", OfficialQqAccountState.READY)),
                OfficialQqReadApiProvider.unavailable())) {
            assertThat(fixture.tools).extracting(AgentTool::name).containsExactlyInAnyOrder(
                    "qq_official_account_list",
                    "qq_official_account_status",
                    "qq_official_group_list",
                    "qq_official_group_info",
                    "qq_official_bot_state",
                    "qq_official_user_info",
                    "qq_official_conversation_list",
                    "qq_official_conversation_history",
                    "qq_official_message_get");
            assertThat(fixture.tools).allSatisfy(tool -> {
                assertThat(tool.description()).isNotBlank();
                assertThat(tool.jsonSchema()).containsEntry("type", "object");
                assertThat(tool.jsonSchema()).containsKey("additionalProperties");
            });
        }
    }

    @Test
    void accountSelectionIsOptionalForOneAccountAndAmbiguousForMany() {
        try (Fixture one = fixture(List.of(status("bot-a", OfficialQqAccountState.READY)),
                OfficialQqReadApiProvider.unavailable())) {
            ToolResult list = tool(one, "qq_official_account_list").execute(call(Map.of()));
            assertThat(list.success()).isTrue();
            JsonNode body = json(list);
            assertThat(body.path("count").asInt()).isEqualTo(1);
            assertThat(body.path("accounts").get(0).path("accountId").asText())
                    .isEqualTo("bot-a");
            assertThat(body.path("accounts").get(0).toString())
                    .doesNotContain("token").doesNotContain("secret");

            ToolResult status = tool(one, "qq_official_account_status").execute(call(Map.of()));
            assertThat(status.success()).isTrue();
            assertThat(json(status).path("accountId").asText()).isEqualTo("bot-a");
        }

        try (Fixture many = fixture(List.of(
                status("bot-a", OfficialQqAccountState.READY),
                status("bot-b", OfficialQqAccountState.CONNECTING)),
                OfficialQqReadApiProvider.unavailable())) {
            ToolResult ambiguous = tool(many, "qq_official_account_status")
                    .execute(call(Map.of()));
            assertThat(ambiguous.success()).isFalse();
            assertThat(ambiguous.code()).isEqualTo("AMBIGUOUS_ACCOUNT");

            ToolResult explicit = tool(many, "qq_official_account_status")
                    .execute(call(Map.of("accountId", "bot-b")));
            assertThat(explicit.success()).isTrue();
            assertThat(json(explicit).path("accountId").asText()).isEqualTo("bot-b");

            ToolResult missing = tool(many, "qq_official_account_status")
                    .execute(call(Map.of("accountId", "bot-missing")));
            assertThat(missing.success()).isFalse();
            assertThat(missing.code()).isEqualTo("ACCOUNT_NOT_FOUND");
        }
    }

    @Test
    void sourceAccountMustMatchExplicitAccountAndBeOfficialAndKnown() {
        try (Fixture fixture = fixture(List.of(
                status("bot-a", OfficialQqAccountState.READY),
                status("bot-b", OfficialQqAccountState.READY)),
                OfficialQqReadApiProvider.unavailable())) {
            ToolResult mismatch = tool(fixture, "qq_official_conversation_history")
                    .execute(call(Map.of("source", GROUP_A, "accountId", "bot-b")));
            assertThat(mismatch.success()).isFalse();
            assertThat(mismatch.code()).isEqualTo("ACCOUNT_SOURCE_MISMATCH");

            ToolResult notOfficial = tool(fixture, "qq_official_conversation_history")
                    .execute(call(Map.of("source", "qq_group/bot-a/123")));
            assertThat(notOfficial.success()).isFalse();
            assertThat(notOfficial.code()).isEqualTo("INVALID_SOURCE");

            ToolResult unknown = tool(fixture, "qq_official_conversation_history")
                    .execute(call(Map.of("source", "qq_official_group/bot-z/group-a")));
            assertThat(unknown.success()).isFalse();
            assertThat(unknown.code()).isEqualTo("ACCOUNT_NOT_FOUND");

            ToolResult invalidAccount = tool(fixture, "qq_official_group_list")
                    .execute(call(Map.of("accountId", "bad account")));
            assertThat(invalidAccount.success()).isFalse();
            assertThat(invalidAccount.code()).isEqualTo("INVALID_ARGUMENT");
        }
    }

    @Test
    void conversationListAndHistoryUseOnlyOfficialBoardFactsWithPagination() {
        try (Fixture fixture = fixture(List.of(status("bot-a", OfficialQqAccountState.READY)),
                OfficialQqReadApiProvider.unavailable())) {
            append(fixture.board, GROUP_A, "m-g1", "group one",
                    List.of(new Segment("text", "group one auth_token=body-secret")));
            append(fixture.board, GROUP_A, "m-g2", "group two",
                    List.of(new Segment("text", "group two"),
                            new Segment("image", "[图片]", Map.of(
                                    "url", "https://cdn.example/a.png?rkey=rkey-1"
                                            + "&auth_token=must-not-leak",
                                    "rkey", "rkey-1",
                                    "auth_token", "must-not-leak"))));
            append(fixture.board, PRIVATE_A, "m-p1", "private one",
                    List.of(new Segment("text", "private one")));
            append(fixture.board, "qq_group/bot-a/123", "onebot-1", "not official",
                    List.of(new Segment("text", "not official")));

            ToolResult list = tool(fixture, "qq_official_conversation_list")
                    .execute(call(Map.of("accountId", "bot-a", "limit", 20)));
            assertThat(list.success()).isTrue();
            JsonNode listed = json(list);
            assertThat(listed.path("platformComplete").asBoolean()).isFalse();
            assertThat(listed.path("count").asInt()).isEqualTo(2);
            assertThat(listed.path("threads").toString())
                    .contains(GROUP_A, PRIVATE_A)
                    .doesNotContain("qq_group/bot-a/123");

            ToolResult firstPage = tool(fixture, "qq_official_conversation_history")
                    .execute(call(Map.of("source", GROUP_A, "limit", 1)));
            assertThat(firstPage.success()).isTrue();
            JsonNode first = json(firstPage);
            assertThat(first.path("includeSegments").asBoolean()).isFalse();
            assertThat(first.path("hasMore").asBoolean()).isTrue();
            assertThat(first.path("messages")).hasSize(1);
            assertThat(first.path("messages").get(0).path("text").asText())
                    .contains("group one")
                    .doesNotContain("body-secret");
            assertThat(first.toString()).doesNotContain("body-secret");
            assertThat(first.path("messages").get(0).has("segments")).isFalse();
            assertThat(first.toString()).doesNotContain("https://").doesNotContain("rkey-1");
            long next = first.path("nextAfterSeq").asLong();
            assertThat(next).isGreaterThan(0L);

            ToolResult secondPage = tool(fixture, "qq_official_conversation_history")
                    .execute(call(Map.of("source", GROUP_A, "afterSeq", next,
                            "limit", 10, "includeSegments", true)));
            assertThat(secondPage.success()).isTrue();
            JsonNode second = json(secondPage);
            assertThat(second.path("messages")).hasSize(1);
            JsonNode segments = second.path("messages").get(0).path("segments");
            assertThat(segments).hasSize(2);
            assertThat(segments.get(1).path("data").path("url").asText())
                    .contains("rkey=rkey-1").doesNotContain("auth_token");
            assertThat(segments.get(1).path("data").has("auth_token")).isFalse();
            assertThat(second.toString()).doesNotContain("must-not-leak");
        }
    }

    @Test
    void messageGetIsExactAndOmitsSegmentsUnlessRequested() {
        try (Fixture fixture = fixture(List.of(status("bot-a", OfficialQqAccountState.READY)),
                OfficialQqReadApiProvider.unavailable())) {
            long firstSeq = append(fixture.board, GROUP_A, "m-get", "one",
                    List.of(new Segment("text", "one"),
                            new Segment("file", "[文件]", Map.of(
                                    "url", "https://cdn.example/file.bin?rkey=secret-rkey",
                                    "rkey", "secret-rkey"))));

            ToolResult found = tool(fixture, "qq_official_message_get")
                    .execute(call(Map.of("source", GROUP_A, "seq", firstSeq)));
            assertThat(found.success()).isTrue();
            JsonNode body = json(found);
            assertThat(body.path("seq").asLong()).isEqualTo(firstSeq);
            assertThat(body.path("source").asText()).isEqualTo(GROUP_A);
            assertThat(body.has("segments")).isFalse();
            assertThat(body.toString()).doesNotContain("rkey").doesNotContain("https://");

            ToolResult withSegments = tool(fixture, "qq_official_message_get")
                    .execute(call(Map.of("source", GROUP_A, "seq", firstSeq,
                            "includeSegments", true)));
            assertThat(withSegments.success()).isTrue();
            assertThat(json(withSegments).path("segments").get(1).path("data").path("rkey")
                    .asText()).isEqualTo("secret-rkey");

            ToolResult missing = tool(fixture, "qq_official_message_get")
                    .execute(call(Map.of("source", GROUP_A, "seq", firstSeq + 100)));
            assertThat(missing.success()).isFalse();
            assertThat(missing.code()).isEqualTo("ITEM_NOT_FOUND");
        }
    }

    @Test
    void groupListIsLocalDirectoryOnlyAndNeverClaimsPlatformCompleteness() {
        try (Fixture fixture = fixture(List.of(
                status("bot-a", OfficialQqAccountState.READY),
                status("bot-b", OfficialQqAccountState.READY)),
                OfficialQqReadApiProvider.unavailable())) {
            fixture.directory.setGroupAlias("bot-a", "group-a", "别名群", T0);
            fixture.directory.observeGroup("bot-a", "group-a", T0.plusSeconds(1));
            fixture.directory.observeGroup("bot-b", "group-b", T0.plusSeconds(2));

            ToolResult ambiguous = tool(fixture, "qq_official_group_list")
                    .execute(call(Map.of()));
            assertThat(ambiguous.success()).isFalse();
            assertThat(ambiguous.code()).isEqualTo("AMBIGUOUS_ACCOUNT");

            ToolResult local = tool(fixture, "qq_official_group_list")
                    .execute(call(Map.of("accountId", "bot-a")));
            assertThat(local.success()).isTrue();
            JsonNode body = json(local);
            assertThat(body.path("platformComplete").asBoolean()).isFalse();
            assertThat(body.path("localOnly").asBoolean()).isTrue();
            assertThat(body.path("source").asText()).isEqualTo("local_directory");
            assertThat(body.path("count").asInt()).isEqualTo(1);
            assertThat(body.path("groups").get(0).path("alias").asText())
                    .isEqualTo("别名群");
            assertThat(body.path("groups").get(0).path("groupOpenid").asText())
                    .isEqualTo("group-a");
        }
    }

    @Test
    void apiBackedToolsAreAccountIsolatedAndSanitizeCredentialFields() {
        FakeReadApi apiA = new FakeReadApi();
        apiA.groupInfo = jsonNode("""
                {"data":{"group_openid":"group-a","group_name":"群A","member_count":42,
                 "auth_token":"secret-auth","nested":{"secret":"secret-nested","keep":"ok"}}}
                """);
        apiA.botState = jsonNode("""
                {"data":{"bot_state":1,"receive_messages":true,
                 "Authorization":"Bearer secret-token"}}
                """);
        apiA.userInfo = jsonNode("""
                {"data":{"user_openid":"user-a","nickname":"Alice",
                 "access_token":"secret-access"}}
                """);
        FakeReadApi apiB = new FakeReadApi();
        apiB.groupInfo = jsonNode("{\"data\":{\"group_name\":\"群B\"}}");
        OfficialQqReadApiProvider provider = accountId -> {
            if ("bot-a".equals(accountId)) {
                return apiA;
            }
            if ("bot-b".equals(accountId)) {
                return apiB;
            }
            throw new OfficialQqApiException(OfficialQqErrorKind.NOT_FOUND, 404, accountId,
                    "READ_API", "", "", false, "missing", null);
        };

        try (Fixture fixture = fixture(List.of(
                status("bot-a", OfficialQqAccountState.READY),
                status("bot-b", OfficialQqAccountState.READY)), provider)) {
            ToolResult group = tool(fixture, "qq_official_group_info")
                    .execute(call(Map.of("source", GROUP_A)));
            assertThat(group.success()).isTrue();
            JsonNode groupJson = json(group);
            assertThat(groupJson.path("groupName").asText()).isEqualTo("群A");
            assertThat(groupJson.path("memberCount").asLong()).isEqualTo(42L);
            assertThat(groupJson.path("data").path("group_name").asText()).isEqualTo("群A");
            assertThat(group.toString())
                    .doesNotContain("secret-auth")
                    .doesNotContain("secret-nested")
                    .doesNotContain("auth_token")
                    .doesNotContain("access_token");
            assertThat(apiA.lastGroupOpenid).isEqualTo("group-a");

            ToolResult botState = tool(fixture, "qq_official_bot_state")
                    .execute(call(Map.of("source", GROUP_A)));
            assertThat(botState.success()).isTrue();
            assertThat(json(botState).path("data").path("receive_messages").asBoolean())
                    .isTrue();
            assertThat(botState.toString()).doesNotContain("secret-token")
                    .doesNotContain("Authorization");

            ToolResult user = tool(fixture, "qq_official_user_info")
                    .execute(call(Map.of("source", PRIVATE_A)));
            assertThat(user.success()).isTrue();
            assertThat(json(user).path("nickname").asText()).isEqualTo("Alice");
            assertThat(user.toString()).doesNotContain("secret-access")
                    .doesNotContain("access_token");

            ToolResult isolated = tool(fixture, "qq_official_group_info")
                    .execute(call(Map.of("source", GROUP_B)));
            assertThat(isolated.success()).isTrue();
            assertThat(json(isolated).path("groupName").asText()).isEqualTo("群B");
            assertThat(apiB.lastGroupOpenid).isEqualTo("group-b");
        }
    }

    @Test
    void permissionFailuresAreFixedErrorsAndNeverEmptySuccesses() {
        FakeReadApi permissionDenied = new FakeReadApi();
        permissionDenied.failure = new OfficialQqApiException(
                OfficialQqErrorKind.PERMISSION, 403, "bot-a", "GROUP_INFO",
                "11253", "trace-should-not-leak", false,
                "forbidden secret-token", null);
        try (Fixture fixture = fixture(List.of(status("bot-a", OfficialQqAccountState.READY)),
                accountId -> permissionDenied)) {
            ToolResult group = tool(fixture, "qq_official_group_info")
                    .execute(call(Map.of("source", GROUP_A)));
            assertThat(group.success()).isFalse();
            assertThat(group.code()).isEqualTo("CAPABILITY_UNAVAILABLE");
            assertThat(group.message())
                    .doesNotContain("11253")
                    .doesNotContain("trace-should-not-leak")
                    .doesNotContain("secret-token")
                    .doesNotContain("forbidden");

            ToolResult user = tool(fixture, "qq_official_user_info")
                    .execute(call(Map.of("source", PRIVATE_A)));
            assertThat(user.success()).isFalse();
            assertThat(user.code()).isEqualTo("NOT_FRIEND");
            assertThat(user.message()).doesNotContain("11253")
                    .doesNotContain("trace-should-not-leak")
                    .doesNotContain("secret-token");
        }
    }

    @Test
    void apiToolsRequireReadyAccountAndProviderAndMalformedResponseFailsClosed() {
        try (Fixture unavailable = fixture(
                List.of(status("bot-a", OfficialQqAccountState.CONNECTING)),
                OfficialQqReadApiProvider.unavailable())) {
            ToolResult notReady = tool(unavailable, "qq_official_group_info")
                    .execute(call(Map.of("source", GROUP_A)));
            assertThat(notReady.success()).isFalse();
            assertThat(notReady.code()).isEqualTo("ACCOUNT_UNAVAILABLE");
        }

        FakeReadApi malformed = new FakeReadApi();
        malformed.groupInfo = jsonNode("{\"data\":null}");
        try (Fixture fixture = fixture(List.of(status("bot-a", OfficialQqAccountState.READY)),
                accountId -> malformed)) {
            ToolResult result = tool(fixture, "qq_official_group_info")
                    .execute(call(Map.of("source", GROUP_A)));
            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("MALFORMED_RESPONSE");
        }

        try (Fixture noProvider = fixture(
                List.of(status("bot-a", OfficialQqAccountState.READY)),
                OfficialQqReadApiProvider.unavailable())) {
            ToolResult result = tool(noProvider, "qq_official_bot_state")
                    .execute(call(Map.of("source", GROUP_A)));
            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("CAPABILITY_UNAVAILABLE");
            assertThat(result.message()).doesNotContain("token").doesNotContain("secret");
        }
    }

    private Fixture fixture(List<OfficialQqAccountStatus> statuses,
                            OfficialQqReadApiProvider provider) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        SqliteMessageBoardStore board = new SqliteMessageBoardStore(
                tempDir.resolve("bot-" + suffix + ".db"));
        OfficialQqDirectoryStore directory = new OfficialQqDirectoryStore(
                tempDir.resolve("directory-" + suffix + ".db"));
        OfficialQqReadContext context = new OfficialQqReadContext(
                board, directory,
                new OfficialQqAccountResolver(() -> List.copyOf(statuses)),
                provider);
        return new Fixture(board, directory, context, OfficialQqReadTools.all(context));
    }

    private static long append(SqliteMessageBoardStore board, String source, String messageId,
                               String text, List<Segment> segments) {
        return board.append(new InboundMessage(
                SourceAddress.parse(source), messageId, T0, segments, Direction.INBOUND,
                "sender-openid", "Sender", "", "member")).message().localSeq();
    }

    private static OfficialQqAccountStatus status(String accountId,
                                                   OfficialQqAccountState state) {
        boolean connected = state == OfficialQqAccountState.READY;
        boolean enabled = state != OfficialQqAccountState.DISABLED;
        return new OfficialQqAccountStatus(
                accountId, state, enabled, connected, state.terminal(), 0, 1,
                T0, connected ? T0 : null, connected ? T0 : null, connected ? 1L : null,
                0, OfficialQqAccountErrorKind.NONE, "", "", "");
    }

    private static AgentTool tool(Fixture fixture, String name) {
        return fixture.tools.stream().filter(tool -> name.equals(tool.name()))
                .findFirst().orElseThrow();
    }

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), arguments, AgentIdentity.external());
    }

    private static JsonNode json(ToolResult result) {
        try {
            return JSON.readTree(result.message());
        } catch (Exception failure) {
            throw new AssertionError("tool output is not JSON: " + result.message(), failure);
        }
    }

    private static JsonNode jsonNode(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class FakeReadApi implements OfficialQqReadApi {
        private JsonNode groupInfo;
        private JsonNode botState;
        private JsonNode userInfo;
        private OfficialQqApiException failure;
        private String lastGroupOpenid;

        @Override
        public JsonNode groupInfo(String groupOpenid) throws OfficialQqApiException {
            failIfNeeded();
            lastGroupOpenid = groupOpenid;
            return groupInfo;
        }

        @Override
        public JsonNode groupBotState(String groupOpenid) throws OfficialQqApiException {
            failIfNeeded();
            return botState;
        }

        @Override
        public JsonNode userInfo(String userOpenid) throws OfficialQqApiException {
            failIfNeeded();
            return userInfo;
        }

        private void failIfNeeded() throws OfficialQqApiException {
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record Fixture(SqliteMessageBoardStore board,
                           OfficialQqDirectoryStore directory,
                           OfficialQqReadContext context,
                           List<AgentTool> tools) implements AutoCloseable {
        @Override
        public void close() {
            directory.close();
            board.close();
        }
    }
}
