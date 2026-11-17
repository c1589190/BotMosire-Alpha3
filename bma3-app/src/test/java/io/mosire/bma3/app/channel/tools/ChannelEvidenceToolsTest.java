package io.mosire.bma3.app.channel.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.app.channel.QqChannelRegistry;
import io.mosire.bma3.app.channel.QqChannelRegistry.Channel;
import io.mosire.bma3.app.channel.QqChannelRegistry.MatchingMode;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.ContentIntegrity;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.CorrelationState;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.IdentityStatus;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.Observation;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.ObservationDraft;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.RouteReason;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.TimeReliability;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelEvidenceToolsTest {

    private static final Instant START = Instant.parse("2026-11-17T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(START, ZoneOffset.UTC);
    private static final String THREAD = "thread.evidence";
    private static final String ONEBOT_GROUP = "qq_group/bot-1/955432692";
    private static final String ONEBOT_PRIVATE = "qq_private/bot-2/132843233";
    private static final String OFFICIAL_GROUP = "qq_official_group/app-1/group_openid_A";
    private static final String OFFICIAL_PRIVATE = "qq_official_private/app-1/user_openid_missing";
    private static final String SECRET = "auth-token-do-not-leak-71b2";
    private static final String BODY_SECRET = "body-secret-never-returned";

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> knownAccounts = new HashSet<>();
    private final Set<String> observedSources = new HashSet<>();
    private final Set<String> availableAccounts = new HashSet<>();
    private QqChannelRegistry registry;
    private QqChannelObservationStore observations;
    private SqliteMessageBoardStore board;

    @BeforeEach
    void setUp() {
        known("onebot", "bot-1");
        known("qq_official", "app-1");
        available("onebot", "bot-1");
        available("qq_official", "app-1");
        observedSources.add(ONEBOT_GROUP);
        observedSources.add(OFFICIAL_GROUP);
        registry = new QqChannelRegistry(tempDir.resolve("channels.db"),
                (channel, accountId) -> knownAccounts.contains(key(channel, accountId)),
                observedSources::contains,
                FIXED_CLOCK,
                MatchingMode.STRICT,
                (channel, accountId) -> availableAccounts.contains(key(channel, accountId)));
        registry.bind(THREAD, ONEBOT_GROUP);
        registry.bind(THREAD, OFFICIAL_GROUP);
        observations = new QqChannelObservationStore(tempDir.resolve("observations.db"),
                FIXED_CLOCK);
        board = new SqliteMessageBoardStore(tempDir.resolve("board.db"));
    }

    @AfterEach
    void closeStores() {
        if (board != null) {
            board.close();
        }
        if (observations != null) {
            observations.close();
        }
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    void factoryExposesExactlyTheTwoReadOnlyEvidenceToolsWithStrictSchemas() {
        List<AgentTool> tools = tools();
        assertThat(tools).extracting(AgentTool::name)
                .containsExactly("qq_thread_compare", "qq_thread_history");
        assertThat(tools).extracting(AgentTool::name).doesNotHaveDuplicates();

        ToolRegistry discovered = new ToolRegistry();
        discovered.registerAll(tools);
        assertThat(discovered.size()).isEqualTo(2);

        for (AgentTool tool : tools) {
            JsonNode schema = mapper.valueToTree(tool.jsonSchema());
            assertThat(schema.path("type").asText()).isEqualTo("object");
            assertThat(schema.path("properties").isObject()).isTrue();
            assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
            assertThat(schema.path("required")).extracting(JsonNode::asText)
                    .containsExactly("threadId");
            assertThat(schema.path("description").asText()).isNotBlank();
            assertThat(schema.path("properties").has("token")).isFalse();
            assertThat(schema.path("properties").has("secret")).isFalse();
            assertThat(schema.path("properties").has("confirm")).isFalse();
            schema.path("properties").properties().forEach(entry ->
                    assertThat(entry.getValue().path("description").asText())
                            .as(tool.name() + "." + entry.getKey()).isNotBlank());
        }

        JsonNode compare = mapper.valueToTree(tool(tools, "qq_thread_compare").jsonSchema());
        assertThat(compare.path("properties").path("limit").path("maximum").asInt())
                .isEqualTo(200);
        assertThat(compare.path("properties").path("cursor").path("additionalProperties")
                .asBoolean(true)).isFalse();

        JsonNode history = mapper.valueToTree(tool(tools, "qq_thread_history").jsonSchema());
        assertThat(history.path("properties").path("includeSegments").path("type").asText())
                .isEqualTo("boolean");
        assertThat(history.path("properties").path("includeSegments").path("default")
                .asBoolean(true)).isFalse();
    }

    @Test
    void comparePagesBodyFreeEvidenceWithStatesSourcesIdentitiesAndStats() throws Exception {
        long seqOne = append(ONEBOT_GROUP, "m-one", START, "onebot " + BODY_SECRET,
                List.of());
        long seqTwo = append(OFFICIAL_GROUP, "m-two", START.plusSeconds(1), "official body",
                List.of());
        long seqThree = append(ONEBOT_PRIVATE, "m-three", START.plusSeconds(2), "third body",
                List.of());
        Observation one = observe(ONEBOT_GROUP, QqChannelObservationStore.Channel.ONEBOT,
                "bot-1", "m-one", seqOne, START);
        Observation two = observe(OFFICIAL_GROUP, QqChannelObservationStore.Channel.QQ_OFFICIAL,
                "app-1", "m-two", seqTwo, START.plusSeconds(1));
        Observation three = observe(ONEBOT_PRIVATE, QqChannelObservationStore.Channel.ONEBOT,
                "bot-2", "m-three", seqThree, START.plusSeconds(2));
        observations.recordCorrelationPair(ONEBOT_GROUP, "m-one", OFFICIAL_GROUP, "m-two",
                CorrelationState.MATCHED);

        ToolResult firstResult = tool("qq_thread_compare").execute(call(Map.of(
                "threadId", THREAD, "limit", 2)));
        assertThat(firstResult.success()).as(firstResult.message()).isTrue();
        JsonNode first = mapper.readTree(firstResult.message());
        assertThat(first.path("scope").asText()).isEqualTo("local_observation_store");
        assertThat(first.path("platformComplete").asBoolean()).isFalse();
        assertThat(first.path("available").asBoolean()).isTrue();
        assertThat(first.path("unavailable").asBoolean()).isFalse();
        assertThat(first.path("empty").asBoolean()).isFalse();
        assertThat(first.path("pageEmpty").asBoolean()).isFalse();
        assertThat(first.path("threadObservationCount").asInt()).isEqualTo(3);
        assertThat(first.path("returned").asInt()).isEqualTo(2);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("containsBody").asBoolean()).isFalse();
        assertThat(first.path("containsSegments").asBoolean()).isFalse();
        assertThat(first.path("containsContentHash").asBoolean()).isFalse();
        assertThat(first.path("containsRawUrls").asBoolean()).isFalse();
        assertThat(first.path("nextCursor").path("messageAt").asText())
                .isEqualTo(START.plusSeconds(1).toString());
        assertThat(first.path("nextCursor").path("observationId").asText())
                .isEqualTo(two.observationId());
        assertThat(first.path("statusCounts").path("observations").asInt()).isEqualTo(3);
        assertThat(first.path("statusCounts").path("correlationCounts").path("MATCHED")
                .asInt()).isEqualTo(2);
        assertThat(first.path("statusCounts").path("correlationCounts").path("CHANNEL_ONLY")
                .asInt()).isEqualTo(1);
        assertThat(first.path("pageChannelCounts").path("onebot").asInt()).isEqualTo(1);
        assertThat(first.path("pageChannelCounts").path("qq_official").asInt()).isEqualTo(1);

        JsonNode observationOne = first.path("observations").get(0);
        assertThat(observationOne.path("observationId").asText())
                .isEqualTo(one.observationId());
        assertThat(observationOne.path("channel").asText()).isEqualTo("onebot");
        assertThat(observationOne.path("accountId").asText()).isEqualTo("bot-1");
        assertThat(observationOne.path("rawSource").asText()).isEqualTo(ONEBOT_GROUP);
        assertThat(observationOne.path("sourceAccountVerified").asBoolean()).isTrue();
        assertThat(observationOne.path("correlation").path("pairPresent").asBoolean()).isTrue();
        assertThat(observationOne.path("correlation").path("peerObservationId").asText())
                .isEqualTo(two.observationId());
        assertThat(observationOne.path("correlation").path("history")).hasSize(1);
        assertThat(observationOne.path("summary").path("bodyFree").asBoolean()).isTrue();
        assertThat(observationOne.path("summary").path("hasBody").asBoolean()).isFalse();
        assertThat(observationOne.path("summary").path("hasBodyHash").asBoolean()).isFalse();
        assertThat(observationOne.has("text")).isFalse();
        assertThat(observationOne.has("segments")).isFalse();
        assertThat(observationOne.has("requestKey")).isFalse();
        assertThat(observationOne.has("ingestIntentKey")).isFalse();

        String firstMessage = firstResult.message();
        assertThat(firstMessage)
                .doesNotContain(BODY_SECRET)
                .doesNotContain("onebot " + BODY_SECRET)
                .doesNotContain("official body")
                .doesNotContain("https://")
                .doesNotContain(SECRET);

        ToolResult secondResult = tool("qq_thread_compare").execute(call(Map.of(
                "threadId", THREAD,
                "limit", 2,
                "cursor", mapper.convertValue(first.path("nextCursor"), Map.class))));
        assertThat(secondResult.success()).as(secondResult.message()).isTrue();
        JsonNode second = mapper.readTree(secondResult.message());
        assertThat(second.path("returned").asInt()).isEqualTo(1);
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(second.path("observations").get(0).path("accountId").asText())
                .isEqualTo("bot-2");
        assertThat(second.path("observations").get(0).path("observationId").asText())
                .isEqualTo(three.observationId());
    }

    @Test
    void compareMarksEmptyAndUnavailableExplicitly() throws Exception {
        observedSources.add(OFFICIAL_PRIVATE);
        registry.bind("thread.empty", OFFICIAL_PRIVATE);

        ToolResult emptyResult = tool("qq_thread_compare").execute(call(Map.of(
                "threadId", "thread.empty")));
        assertThat(emptyResult.success()).as(emptyResult.message()).isTrue();
        JsonNode empty = mapper.readTree(emptyResult.message());
        assertThat(empty.path("available").asBoolean()).isTrue();
        assertThat(empty.path("empty").asBoolean()).isTrue();
        assertThat(empty.path("pageEmpty").asBoolean()).isTrue();
        assertThat(empty.path("threadObservationCount").asInt()).isZero();
        assertThat(empty.path("observations")).isEmpty();
        assertThat(empty.path("statusCounts").path("observations").asInt()).isZero();

        observations.close();
        ToolResult unavailable = tool("qq_thread_compare").execute(call(Map.of(
                "threadId", THREAD)));
        assertThat(unavailable.success()).isFalse();
        assertThat(unavailable.code()).isEqualTo("OBSERVATION_UNAVAILABLE");
        assertThat(unavailable.message()).doesNotContain(SECRET, "SQL", "sqlite");
    }

    @Test
    void historyReadsExactBoardRowsPagesInTimeOrderAndMarksMissingBoard() throws Exception {
        List<Segment> media = List.of(new Segment("image", "[图片]", Map.of(
                "url", "https://cdn.example.invalid/pic.png?token=" + SECRET,
                "file", "pic.png",
                "subType", "1")));
        long seqOne = append(ONEBOT_GROUP, "h-one", START, "onebot history body", List.of());
        long seqTwo = append(OFFICIAL_GROUP, "h-two", START.plusSeconds(1),
                "official history body", media);
        long seqThree = append(ONEBOT_PRIVATE, "h-three", START.plusSeconds(2),
                "legacy account body", List.of());
        Observation one = observe(ONEBOT_GROUP, QqChannelObservationStore.Channel.ONEBOT,
                "bot-1", "h-one", seqOne, START);
        Observation two = observe(OFFICIAL_GROUP, QqChannelObservationStore.Channel.QQ_OFFICIAL,
                "app-1", "h-two", seqTwo, START.plusSeconds(1));
        Observation three = observe(ONEBOT_PRIVATE, QqChannelObservationStore.Channel.ONEBOT,
                "bot-2", "h-three", seqThree, START.plusSeconds(2));
        Observation missing = observe(OFFICIAL_PRIVATE,
                QqChannelObservationStore.Channel.QQ_OFFICIAL, "app-1", "h-missing",
                9999L, START.plusSeconds(3));

        // 历史读取必须支持 source 已解绑；registry thread 本身仍保留。
        registry.unbind(THREAD, ONEBOT_GROUP);
        registry.unbind(THREAD, OFFICIAL_GROUP);
        ToolResult compareAfterUnbind = tool("qq_thread_compare").execute(call(Map.of(
                "threadId", THREAD, "limit", 10)));
        assertThat(compareAfterUnbind.success()).as(compareAfterUnbind.message()).isTrue();
        assertThat(mapper.readTree(compareAfterUnbind.message()).path("threadObservationCount")
                .asInt()).isEqualTo(4);

        ToolResult firstResult = tool("qq_thread_history").execute(call(Map.of(
                "threadId", THREAD, "limit", 2)));
        assertThat(firstResult.success()).as(firstResult.message()).isTrue();
        JsonNode first = mapper.readTree(firstResult.message());
        assertThat(first.path("scope").asText()).isEqualTo("local_board_and_observation");
        assertThat(first.path("platformComplete").asBoolean()).isFalse();
        assertThat(first.path("available").asBoolean()).isTrue();
        assertThat(first.path("timeline")).hasSize(2);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("pageBoardSummary").path("found").asInt()).isEqualTo(2);
        assertThat(first.path("pageBoardSummary").path("missing").asInt()).isZero();
        assertThat(first.path("segmentUrlFieldsExposed").asBoolean()).isFalse();

        JsonNode oneItem = timelineItem(first, ONEBOT_GROUP, "h-one");
        assertThat(oneItem.path("observationId").asText()).isEqualTo(one.observationId());
        assertThat(oneItem.path("channel").asText()).isEqualTo("onebot");
        assertThat(oneItem.path("accountId").asText()).isEqualTo("bot-1");
        assertThat(oneItem.path("board").path("boardStatus").asText()).isEqualTo("FOUND");
        assertThat(oneItem.path("board").path("text").asText()).isEqualTo("onebot history body");
        assertThat(oneItem.path("board").has("segments")).isFalse();

        JsonNode twoItem = timelineItem(first, OFFICIAL_GROUP, "h-two");
        assertThat(twoItem.path("channel").asText()).isEqualTo("qq_official");
        assertThat(twoItem.path("accountId").asText()).isEqualTo("app-1");
        assertThat(twoItem.path("board").path("boardStatus").asText()).isEqualTo("FOUND");
        assertThat(twoItem.path("board").path("text").asText())
                .isEqualTo("official history body\n[图片]");
        assertThat(twoItem.path("board").has("segments")).isFalse();
        assertThat(firstResult.message()).doesNotContain("https://").doesNotContain(SECRET);

        ToolResult secondResult = tool("qq_thread_history").execute(call(Map.of(
                "threadId", THREAD,
                "limit", 2,
                "cursor", mapper.convertValue(first.path("nextCursor"), Map.class))));
        assertThat(secondResult.success()).as(secondResult.message()).isTrue();
        JsonNode second = mapper.readTree(secondResult.message());
        assertThat(second.path("timeline")).hasSize(2);
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("pageBoardSummary").path("found").asInt()).isEqualTo(1);
        assertThat(second.path("pageBoardSummary").path("missing").asInt()).isEqualTo(1);

        JsonNode threeItem = timelineItem(second, ONEBOT_PRIVATE, "h-three");
        assertThat(threeItem.path("accountId").asText()).isEqualTo("bot-2");
        assertThat(threeItem.path("board").path("boardStatus").asText()).isEqualTo("FOUND");
        assertThat(threeItem.path("board").path("text").asText())
                .isEqualTo("legacy account body");

        JsonNode missingItem = timelineItem(second, OFFICIAL_PRIVATE, "h-missing");
        assertThat(missingItem.path("observationId").asText())
                .isEqualTo(missing.observationId());
        assertThat(missingItem.path("board").path("boardStatus").asText())
                .isEqualTo("MISSING_BOARD");
        assertThat(missingItem.path("board").has("text")).isFalse();
        assertThat(missingItem.path("board").path("textIncluded").asBoolean()).isFalse();

        ToolResult segmentsResult = tool("qq_thread_history").execute(call(Map.of(
                "threadId", THREAD, "limit", 10, "includeSegments", true)));
        assertThat(segmentsResult.success()).as(segmentsResult.message()).isTrue();
        JsonNode segmentsRoot = mapper.readTree(segmentsResult.message());
        JsonNode official = timelineItem(segmentsRoot, OFFICIAL_GROUP, "h-two");
        JsonNode segments = official.path("board").path("segments");
        assertThat(segments).hasSize(2);
        assertThat(segments.get(1).path("type").asText()).isEqualTo("image");
        assertThat(segments.get(1).path("fileId").asText()).startsWith("file-");
        assertThat(segments.get(1).path("data").has("url")).isFalse();
        assertThat(segments.get(1).path("data").has("file")).isFalse();
        assertThat(segmentsResult.message())
                .doesNotContain("https://")
                .doesNotContain(SECRET)
                .doesNotContain("auth-token");
    }

    @Test
    void invalidThreadsArgumentsAndCredentialFieldsFailWithFixedSafeCodes() throws Exception {
        expect("INVALID_THREAD_ID", tool("qq_thread_compare").execute(call(Map.of(
                "threadId", "bad thread"))));
        expect("THREAD_NOT_FOUND", tool("qq_thread_compare").execute(call(Map.of(
                "threadId", "thread.missing"))));
        expect("INVALID_ARGUMENT", tool("qq_thread_compare").execute(call(Map.of(
                "threadId", THREAD, "limit", 0))));
        expect("INVALID_CURSOR", tool("qq_thread_compare").execute(call(Map.of(
                "threadId", THREAD,
                "cursor", Map.of("messageAt", "not-an-instant", "observationId", "x")))));
        expect("INVALID_TIME_RANGE", tool("qq_thread_compare").execute(call(Map.of(
                "threadId", THREAD,
                "from", START.toString(),
                "to", START.toString()))));
        expect("INVALID_THREAD_ID", tool("qq_thread_history").execute(call(Map.of(
                "threadId", "bad thread"))));
        expect("THREAD_NOT_FOUND", tool("qq_thread_history").execute(call(Map.of(
                "threadId", "thread.missing"))));

        String secret = "credential-value-not-returned-1199";
        ToolResult unsupported = tool("qq_thread_history").execute(call(Map.of(
                "threadId", THREAD, "token", secret)));
        expect("INVALID_ARGUMENT", unsupported);
        assertThat(unsupported.message()).doesNotContain(secret, "Authorization", "SQL");
    }

    private List<AgentTool> tools() {
        return ChannelEvidenceTools.all(registry, observations, board);
    }

    private AgentTool tool(String name) {
        return tool(tools(), name);
    }

    private static AgentTool tool(List<AgentTool> tools, String name) {
        return tools.stream().filter(tool -> name.equals(tool.name())).findFirst().orElseThrow();
    }

    private long append(String rawSource, String messageId, Instant at, String text,
                        List<Segment> additionalSegments) {
        List<Segment> segments = new ArrayList<>();
        if (!text.isBlank()) {
            segments.add(new Segment("text", text));
        }
        segments.addAll(additionalSegments);
        InboundMessage inbound = new InboundMessage(SourceAddress.parse(rawSource), messageId,
                at, segments, Direction.INBOUND, "sender-1", "Sender One", "Sender Card",
                "member");
        return board.append(inbound).message().localSeq();
    }

    private Observation observe(String rawSource,
                                QqChannelObservationStore.Channel channel,
                                String accountId,
                                String messageId,
                                long boardLocalSeq,
                                Instant at) {
        // store 对 MATCHED fail-closed：两侧必须有非空 senderId、相同 canonicalSenderId
        // 且 identityStatus=MAPPED。夹具按产品校验构造，不降低 store 约束。
        return observations.observe(new ObservationDraft(THREAD, channel, accountId, rawSource,
                messageId, boardLocalSeq, "sender-1", Optional.of("person-1"),
                IdentityStatus.MAPPED, 1, QqChannelObservationStore.Direction.INBOUND, at,
                at.plusMillis(5), 50,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE, "epoch-evidence-test",
                RouteReason.PENDING)).observation();
    }

    private static JsonNode timelineItem(JsonNode root, String rawSource, String messageId) {
        for (JsonNode item : root.path("timeline")) {
            if (rawSource.equals(item.path("rawSource").asText())
                    && messageId.equals(item.path("channelMessageId").asText())) {
                return item;
            }
        }
        throw new AssertionError("timeline item not found: " + rawSource + "/" + messageId);
    }

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), arguments, AgentIdentity.external());
    }

    private static void expect(String code, ToolResult result) {
        assertThat(result.success()).as(result.message()).isFalse();
        assertThat(result.code()).as(result.message()).isEqualTo(code);
        assertThat(result.message()).isNotBlank();
    }

    private void known(String channel, String accountId) {
        knownAccounts.add(key(channel, accountId));
    }

    private void available(String channel, String accountId) {
        availableAccounts.add(key(channel, accountId));
    }

    private static String key(String channel, String accountId) {
        return channel + ":" + accountId;
    }

    private static String key(Channel channel, String accountId) {
        return channel.wireName() + ":" + accountId;
    }
}
