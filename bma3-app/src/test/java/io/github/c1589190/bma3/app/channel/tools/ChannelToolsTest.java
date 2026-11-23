package io.github.c1589190.bma3.app.channel.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.AgentIdentity;
import io.github.c1589190.agentmosire.agentlib.permission.AgentPermissionSet;
import io.github.c1589190.agentmosire.agentlib.tool.AgentTool;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolRegistry;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.AuditResult;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.Channel;
import io.github.c1589190.bma3.app.channel.QqChannelRegistry.MatchingMode;
import io.github.c1589190.bma3.app.channel.tools.ChannelSourceCatalog.ObservedSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelToolsTest {

    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ONEBOT_GROUP = "qq_group/bot-1/955432692";
    private static final String ONEBOT_PRIVATE = "qq_private/bot-1/132843233";
    private static final String OFFICIAL_GROUP = "qq_official_group/app-1/group_openid_A";
    private static final String OFFICIAL_PRIVATE = "qq_official_private/app-1/user_openid_B";

    private static final List<String> TOOL_NAMES = List.of(
            "qq_channel_sources",
            "qq_channel_thread_list",
            "qq_thread_bind",
            "qq_thread_unbind",
            "qq_thread_identity_bind",
            "qq_thread_identity_unbind",
            "qq_thread_set_injection",
            "qq_thread_set_preferred_channel",
            "qq_thread_set_matching",
            "qq_thread_status");

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> knownAccounts = new HashSet<>();
    private final Set<String> availableAccounts = new HashSet<>();
    private final Set<String> observedSources = new HashSet<>();
    private final List<ObservedSource> catalogEntries = new ArrayList<>();
    private QqChannelRegistry registry;

    @BeforeEach
    void setUp() {
        known("onebot", "bot-1");
        known("qq_official", "app-1");
        available("onebot", "bot-1");
        available("qq_official", "app-1");
        registry = new QqChannelRegistry(tempDir.resolve("channels.db"),
                (channel, accountId) -> knownAccounts.contains(key(channel, accountId)),
                observedSources::contains,
                FIXED_CLOCK,
                MatchingMode.STRICT,
                (channel, accountId) -> availableAccounts.contains(key(channel, accountId)));
    }

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void discoveryHasTheExactUniqueControlSurfaceAndDoesNotShadowQqThreadList() {
        List<AgentTool> tools = tools();

        assertThat(tools).extracting(AgentTool::name).containsExactlyElementsOf(TOOL_NAMES);
        assertThat(tools).extracting(AgentTool::name).doesNotHaveDuplicates();
        assertThat(tools).extracting(AgentTool::name).doesNotContain("qq_thread_list");

        ChannelToolAssembly assembly = ChannelTools.assemble(registry,
                ChannelSourceCatalog.empty(), FIXED_CLOCK);
        assertThat(assembly.mutationGate()).isNotNull();
        assertThat(assembly.productionReady()).isFalse();
        assertThat(assembly.bindingCoordinatorConfigured()).isFalse();
        assertThat(assembly.tools()).extracting(AgentTool::name)
                .containsExactlyElementsOf(TOOL_NAMES);

        ToolRegistry discovery = new ToolRegistry();
        discovery.registerAll(tools);
        assertThat(discovery.size()).isEqualTo(TOOL_NAMES.size());
        assertThat(discovery.find("qq_channel_thread_list")).isPresent();
        assertThat(discovery.find("qq_thread_list")).isEmpty();
    }

    @Test
    void everyToolHasAStrictDetailedObjectSchema() {
        for (AgentTool tool : tools()) {
            JsonNode schema = mapper.valueToTree(tool.jsonSchema());
            assertThat(schema.path("type").asText()).as(tool.name()).isEqualTo("object");
            assertThat(schema.path("properties").isObject()).as(tool.name()).isTrue();
            assertThat(schema.path("required").isArray()).as(tool.name()).isTrue();
            assertThat(schema.path("additionalProperties").asBoolean(true)).as(tool.name()).isFalse();
            assertThat(schema.path("description").asText()).as(tool.name()).isNotBlank();
            assertThat(schema.path("properties").has("confirm")).as(tool.name()).isFalse();
            schema.path("properties").properties().forEach(entry -> {
                assertThat(entry.getValue().path("description").asText())
                        .as(tool.name() + "." + entry.getKey()).isNotBlank();
            });
        }

        JsonNode bind = mapper.valueToTree(tool("qq_thread_bind").jsonSchema());
        assertThat(bind.path("required")).extracting(JsonNode::asText)
                .containsExactly("threadId", "rawSource");
        assertThat(bind.path("properties").path("channel").path("enum"))
                .extracting(JsonNode::asText).containsExactly("onebot", "qq_official");

        JsonNode sources = mapper.valueToTree(tool("qq_channel_sources").jsonSchema());
        assertThat(sources.path("properties").path("limit").path("maximum").asInt())
                .isEqualTo(500);
        assertThat(sources.path("description").asText()).contains("platformComplete=false");

        JsonNode injection = mapper.valueToTree(tool("qq_thread_set_injection").jsonSchema());
        assertThat(injection.path("required")).extracting(JsonNode::asText)
                .containsExactly("enabled");
        assertThat(injection.path("oneOf")).hasSize(2);

        JsonNode identity = mapper.valueToTree(tool("qq_thread_identity_bind").jsonSchema());
        assertThat(identity.path("properties").path("scope").path("required"))
                .extracting(JsonNode::asText).containsExactly("type", "value");

        JsonNode preferred = mapper.valueToTree(
                tool("qq_thread_set_preferred_channel").jsonSchema());
        assertThat(preferred.path("properties").path("preferredChannel").path("enum"))
                .extracting(JsonNode::asText).containsExactly("onebot", "qq_official");

        JsonNode matching = mapper.valueToTree(tool("qq_thread_set_matching").jsonSchema());
        assertThat(matching.path("properties").path("matchingMode").path("enum"))
                .extracting(JsonNode::asText).containsExactly("strict", "loose", "off");
    }

    @Test
    void sourcesAreExplicitlyLocalAndEmptyDoesNotClaimPlatformCompleteness() throws Exception {
        ToolResult empty = tool("qq_channel_sources").execute(call(Map.of()));
        assertThat(empty.success()).as(empty.message()).isTrue();
        JsonNode emptyJson = mapper.readTree(empty.message());
        assertThat(emptyJson.path("scope").asText()).isEqualTo("local_observed_catalog");
        assertThat(emptyJson.path("platformComplete").asBoolean()).isFalse();
        assertThat(emptyJson.path("empty").asBoolean()).isTrue();
        assertThat(emptyJson.path("count").asInt()).isZero();
        assertThat(emptyJson.path("generatedAt").asText()).isEqualTo(NOW.toString());
        assertThat(emptyJson.path("notice").asText())
                .contains("不是 QQ 平台全量列表")
                .contains("空本地结果也不代表平台为空");

        observedSources.add(ONEBOT_GROUP);
        observedSources.add(OFFICIAL_GROUP);
        catalogEntries.add(new ObservedSource(ONEBOT_GROUP, NOW.minusSeconds(30)));
        catalogEntries.add(new ObservedSource(OFFICIAL_GROUP, NOW.minusSeconds(10)));

        ToolResult listed = tool("qq_channel_sources").execute(call(Map.of("limit", 100)));
        assertThat(listed.success()).as(listed.message()).isTrue();
        JsonNode listedJson = mapper.readTree(listed.message());
        assertThat(listedJson.path("platformComplete").asBoolean()).isFalse();
        assertThat(listedJson.path("count").asInt()).isEqualTo(2);
        assertThat(listedJson.path("empty").asBoolean()).isFalse();
        JsonNode first = listedJson.path("sources").get(0);
        assertThat(first.path("rawSource").asText()).isEqualTo(OFFICIAL_GROUP);
        assertThat(first.path("channel").asText()).isEqualTo("qq_official");
        assertThat(first.path("accountId").asText()).isEqualTo("app-1");
        assertThat(first.path("lastActivity").asText()).isEqualTo(NOW.minusSeconds(10).toString());
        assertThat(first.path("binding").isNull()).isTrue();
        assertThat(first.path("injection").path("sourceEnabled").asBoolean()).isTrue();
        assertThat(first.path("injection").path("configured").asBoolean()).isTrue();
        assertThat(first.path("injection").path("effective").isNull()).isTrue();
        assertThat(first.path("injection").path("effectiveKnown").asBoolean()).isFalse();
        assertThat(first.path("injection").path("effectiveScope").asText())
                .isEqualTo("unbound_raw_source_gate_only");
        assertThat(first.path("injection").path("affects").asText())
                .isEqualTo("new_messages_only");
    }

    @Test
    void injectionStatusUsesRegistryConfiguredAndEffectiveState() throws Exception {
        // 已知账号但尚未观察到 source：binding 是 PENDING_OBSERVATION，configured=true，
        // 但 registry 的 effective 必须为 false。
        ToolResult pending = tool("qq_thread_bind").execute(call(Map.of(
                "threadId", "thread.pending",
                "rawSource", OFFICIAL_PRIVATE)));
        assertThat(pending.success()).as(pending.message()).isTrue();
        JsonNode pendingJson = mapper.readTree(pending.message());
        assertThat(pendingJson.path("bound").asBoolean()).isTrue();
        assertThat(pendingJson.path("state").asText()).isEqualTo("PENDING_OBSERVATION");
        assertThat(pendingJson.path("pendingObservation").asBoolean()).isTrue();
        assertThat(pendingJson.path("configuredInjection").asBoolean()).isTrue();
        assertThat(pendingJson.path("effectiveInjection").asBoolean()).isFalse();
        assertThat(pendingJson.path("effectiveInjectionScope").asText())
                .isEqualTo("bound_source");
        assertThat(pendingJson.path("accountAvailabilityApplied").asBoolean()).isTrue();

        // active 且账号在线时 effective=true；账号 DOWN 后 configured 仍 true、effective 为 false。
        observedSources.add(ONEBOT_GROUP);
        ToolResult active = tool("qq_thread_bind").execute(call(Map.of(
                "threadId", "thread.down",
                "rawSource", ONEBOT_GROUP)));
        assertThat(mapper.readTree(active.message()).path("effectiveInjection").asBoolean())
                .isTrue();

        availableAccounts.remove("onebot:bot-1");
        ToolResult down = tool("qq_thread_set_injection").execute(call(Map.of(
                "rawSource", ONEBOT_GROUP, "enabled", true)));
        assertThat(down.success()).as(down.message()).isTrue();
        JsonNode downJson = mapper.readTree(down.message());
        assertThat(downJson.path("bound").asBoolean()).isTrue();
        assertThat(downJson.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(downJson.path("configuredInjection").asBoolean()).isTrue();
        assertThat(downJson.path("effectiveInjection").asBoolean()).isFalse();
        assertThat(downJson.path("accountAvailabilityApplied").asBoolean()).isTrue();

        ToolResult downThread = tool("qq_thread_set_injection").execute(call(Map.of(
                "threadId", "thread.down", "enabled", true)));
        assertThat(downThread.success()).as(downThread.message()).isTrue();
        JsonNode downThreadJson = mapper.readTree(downThread.message());
        assertThat(downThreadJson.path("target").asText()).isEqualTo("thread");
        assertThat(downThreadJson.path("configuredInjection").asBoolean()).isTrue();
        assertThat(downThreadJson.path("effectiveInjection").asBoolean()).isFalse();
        assertThat(downThreadJson.path("effectiveInjectionScope").asText())
                .isEqualTo("thread_with_active_sources");

        ToolResult downStatus = tool("qq_thread_status").execute(call(Map.of(
                "threadId", "thread.down")));
        assertThat(downStatus.success()).as(downStatus.message()).isTrue();
        JsonNode downStatusJson = mapper.readTree(downStatus.message());
        assertThat(downStatusJson.path("configuredInjection").asBoolean()).isTrue();
        assertThat(downStatusJson.path("effectiveInjection").asBoolean()).isFalse();
        assertThat(downStatusJson.path("bindings").get(0).path("configuredInjection").asBoolean())
                .isTrue();
        assertThat(downStatusJson.path("bindings").get(0).path("effectiveInjection").asBoolean())
                .isFalse();

        // source gate 关闭后 configured 与 effective 都应来自 registry 的 false。
        ToolResult gateOff = tool("qq_thread_set_injection").execute(call(Map.of(
                "rawSource", ONEBOT_GROUP, "enabled", false)));
        JsonNode gateOffJson = mapper.readTree(gateOff.message());
        assertThat(gateOffJson.path("configuredInjection").asBoolean()).isFalse();
        assertThat(gateOffJson.path("effectiveInjection").asBoolean()).isFalse();

        // 未绑定 raw source 没有 thread/binding/账号在线语义；单独标注 gate-only 语义，
        // 且账号即使 DOWN 也不影响该未绑定 gate 的表达。
        ToolResult unboundOff = tool("qq_thread_set_injection").execute(call(Map.of(
                "rawSource", ONEBOT_PRIVATE, "enabled", false)));
        JsonNode unboundOffJson = mapper.readTree(unboundOff.message());
        assertThat(unboundOffJson.path("bound").asBoolean()).isFalse();
        assertThat(unboundOffJson.path("configuredInjection").asBoolean()).isFalse();
        assertThat(unboundOffJson.path("rawSourceGateEnabled").asBoolean()).isFalse();
        assertThat(unboundOffJson.path("effectiveInjection").isNull()).isTrue();
        assertThat(unboundOffJson.path("effectiveInjectionKnown").asBoolean()).isFalse();
        assertThat(unboundOffJson.path("effectiveInjectionScope").asText())
                .isEqualTo("unbound_raw_source_gate_only");
        assertThat(unboundOffJson.path("accountAvailabilityApplied").asBoolean()).isFalse();

        ToolResult unboundOn = tool("qq_thread_set_injection").execute(call(Map.of(
                "rawSource", ONEBOT_PRIVATE, "enabled", true)));
        JsonNode unboundOnJson = mapper.readTree(unboundOn.message());
        assertThat(unboundOnJson.path("bound").asBoolean()).isFalse();
        assertThat(unboundOnJson.path("configuredInjection").asBoolean()).isTrue();
        assertThat(unboundOnJson.path("rawSourceGateEnabled").asBoolean()).isTrue();
        assertThat(unboundOnJson.path("effectiveInjection").isNull()).isTrue();
        assertThat(unboundOnJson.path("effectiveInjectionKnown").asBoolean()).isFalse();
        assertThat(unboundOnJson.path("effectiveInjectionScope").asText())
                .isEqualTo("unbound_raw_source_gate_only");
        assertThat(unboundOnJson.path("accountAvailabilityApplied").asBoolean()).isFalse();
        assertThat(unboundOnJson.path("effectiveInjectionExplanation").asText())
                .contains("未绑定").contains("effectiveInjection 因此为 null")
                .contains("不涉及 thread/binding/pending/账号在线校验");
    }

    @Test
    void mutationGateSerializesSameInstanceAndReleasesAfterFailure() throws Exception {
        ChannelMutationGate gate = ChannelMutationGate.processLocal();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> gate.underLock(() -> {
                firstEntered.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", interrupted);
                }
                return null;
            }));
            assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(() -> gate.underLock(() -> {
                secondEntered.countDown();
                return null;
            }));
            assertThat(secondEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
            releaseFirst.countDown();
            assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }

        assertThatThrownBy(() -> gate.underLock(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        AtomicBoolean enteredAfterFailure = new AtomicBoolean();
        gate.underLock(() -> {
            enteredAfterFailure.set(true);
            return null;
        });
        assertThat(enteredAfterFailure).isTrue();
    }

    @Test
    void allWriteToolsUseInjectedGateAndReadsBypassIt() throws Exception {
        observedSources.add(ONEBOT_GROUP);
        observedSources.add(OFFICIAL_GROUP);
        catalogEntries.add(new ObservedSource(ONEBOT_GROUP, NOW.minusSeconds(30)));
        catalogEntries.add(new ObservedSource(OFFICIAL_GROUP, NOW.minusSeconds(20)));
        TrackingGate gate = new TrackingGate();
        ChannelToolAssembly assembly = ChannelTools.assemble(registry,
                limit -> catalogEntries.stream().limit(limit).toList(), gate, FIXED_CLOCK);
        assertThat(assembly.mutationGate()).isSameAs(gate);
        assertThat(assembly.gate()).isSameAs(gate);
        List<AgentTool> tools = assembly.tools();

        assertThat(tool(tools, "qq_channel_sources").execute(call(Map.of())).success()).isTrue();
        assertThat(tool(tools, "qq_channel_thread_list").execute(call(Map.of())).success()).isTrue();
        assertThat(gate.calls()).isZero();

        List<ToolResult> writes = List.of(
                tool(tools, "qq_thread_bind").execute(call(Map.of(
                        "threadId", "thread.gate", "rawSource", ONEBOT_GROUP))),
                tool(tools, "qq_thread_bind").execute(call(Map.of(
                        "threadId", "thread.gate", "rawSource", OFFICIAL_GROUP))),
                tool(tools, "qq_thread_identity_bind").execute(call(Map.of(
                        "threadId", "thread.gate", "channel", "onebot", "channelUserId", "10001",
                        "canonicalUserId", "person-gate",
                        "scope", Map.of("type", "thread", "value", "thread.gate")))),
                tool(tools, "qq_thread_identity_unbind").execute(call(Map.of(
                        "threadId", "thread.gate", "channel", "onebot", "channelUserId", "10001",
                        "scope", Map.of("type", "thread", "value", "thread.gate")))),
                tool(tools, "qq_thread_set_injection").execute(call(Map.of(
                        "rawSource", ONEBOT_GROUP, "enabled", false))),
                tool(tools, "qq_thread_set_injection").execute(call(Map.of(
                        "threadId", "thread.gate", "enabled", false))),
                tool(tools, "qq_thread_set_preferred_channel").execute(call(Map.of(
                        "threadId", "thread.gate", "preferredChannel", "onebot"))),
                tool(tools, "qq_thread_set_matching").execute(call(Map.of(
                        "threadId", "thread.gate", "matchingMode", "loose"))),
                tool(tools, "qq_thread_unbind").execute(call(Map.of(
                        "threadId", "thread.gate", "rawSource", ONEBOT_GROUP))),
                tool(tools, "qq_thread_unbind").execute(call(Map.of(
                        "threadId", "thread.gate", "rawSource", OFFICIAL_GROUP))));
        assertThat(writes).allSatisfy(result ->
                assertThat(result.success()).as(result.message()).isTrue());
        assertThat(gate.calls()).isEqualTo(10);

        int afterWrites = gate.calls();
        assertThat(tool(tools, "qq_channel_sources").execute(call(Map.of())).success()).isTrue();
        assertThat(tool(tools, "qq_channel_thread_list").execute(call(Map.of())).success()).isTrue();
        assertThat(tool(tools, "qq_thread_status").execute(call(Map.of(
                "threadId", "thread.gate"))).success()).isTrue();
        assertThat(gate.calls()).isEqualTo(afterWrites);
    }

    @Test
    void normalControlFlowIsIdempotentAndExposesServerRevisions() throws Exception {
        observedSources.add(ONEBOT_GROUP);
        observedSources.add(OFFICIAL_GROUP);
        catalogEntries.add(new ObservedSource(ONEBOT_GROUP, NOW.minusSeconds(30)));
        catalogEntries.add(new ObservedSource(OFFICIAL_GROUP, NOW.minusSeconds(20)));

        ToolResult boundOneBot = tool("qq_thread_bind").execute(call(Map.of(
                "threadId", "thread.demo",
                "rawSource", ONEBOT_GROUP,
                "channel", "onebot",
                "label", "QQ群 测试群")));
        assertThat(boundOneBot.success()).as(boundOneBot.message()).isTrue();
        JsonNode boundOneBotJson = mapper.readTree(boundOneBot.message());
        assertThat(boundOneBotJson.path("changed").asBoolean()).isTrue();
        assertThat(boundOneBotJson.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(boundOneBotJson.path("channel").asText()).isEqualTo("onebot");
        assertThat(boundOneBotJson.path("accountId").asText()).isEqualTo("bot-1");
        long firstRevision = boundOneBotJson.path("revision").asLong();
        assertThat(firstRevision).isPositive();

        ToolResult boundOfficial = tool("qq_thread_bind").execute(call(Map.of(
                "threadId", "thread.demo",
                "rawSource", OFFICIAL_GROUP,
                "channel", "qq_official")));
        assertThat(boundOfficial.success()).as(boundOfficial.message()).isTrue();
        long secondRevision = mapper.readTree(boundOfficial.message()).path("revision").asLong();
        assertThat(secondRevision).isGreaterThan(firstRevision);

        ToolResult repeatedBind = tool("qq_thread_bind").execute(call(Map.of(
                "threadId", "thread.demo",
                "rawSource", ONEBOT_GROUP,
                "label", "QQ群 测试群")));
        JsonNode repeatedBindJson = mapper.readTree(repeatedBind.message());
        assertThat(repeatedBind.success()).as(repeatedBind.message()).isTrue();
        assertThat(repeatedBindJson.path("changed").asBoolean()).isFalse();
        assertThat(repeatedBindJson.path("revision").asLong()).isEqualTo(secondRevision);

        ToolResult preferred = tool("qq_thread_set_preferred_channel").execute(call(Map.of(
                "threadId", "thread.demo", "preferredChannel", "onebot")));
        assertThat(preferred.success()).as(preferred.message()).isTrue();
        JsonNode preferredJson = mapper.readTree(preferred.message());
        assertThat(preferredJson.path("changed").asBoolean()).isTrue();
        assertThat(preferredJson.path("preferredChannel").asText()).isEqualTo("onebot");
        assertThat(preferredJson.path("display").path("channel").asText()).isEqualTo("onebot");
        assertThat(preferredJson.path("display").path("preferred").asBoolean()).isTrue();
        long preferredRevision = preferredJson.path("revision").asLong();

        ToolResult repeatedPreferred = tool("qq_thread_set_preferred_channel").execute(call(Map.of(
                "threadId", "thread.demo", "preferredChannel", "onebot")));
        assertThat(mapper.readTree(repeatedPreferred.message()).path("changed").asBoolean())
                .isFalse();
        assertThat(mapper.readTree(repeatedPreferred.message()).path("revision").asLong())
                .isEqualTo(preferredRevision);

        ToolResult matching = tool("qq_thread_set_matching").execute(call(Map.of(
                "threadId", "thread.demo", "matchingMode", "loose")));
        assertThat(matching.success()).as(matching.message()).isTrue();
        JsonNode matchingJson = mapper.readTree(matching.message());
        assertThat(matchingJson.path("matchingMode").asText()).isEqualTo("loose");
        assertThat(matchingJson.path("changed").asBoolean()).isTrue();
        long matchingRevision = matchingJson.path("revision").asLong();

        ToolResult repeatedMatching = tool("qq_thread_set_matching").execute(call(Map.of(
                "threadId", "thread.demo", "matchingMode", "loose")));
        assertThat(mapper.readTree(repeatedMatching.message()).path("changed").asBoolean())
                .isFalse();
        assertThat(mapper.readTree(repeatedMatching.message()).path("revision").asLong())
                .isEqualTo(matchingRevision);

        ToolResult paused = tool("qq_thread_set_injection").execute(call(Map.of(
                "rawSource", ONEBOT_GROUP, "enabled", false)));
        assertThat(paused.success()).as(paused.message()).isTrue();
        JsonNode pausedJson = mapper.readTree(paused.message());
        assertThat(pausedJson.path("target").asText()).isEqualTo("raw_source");
        assertThat(pausedJson.path("changed").asBoolean()).isTrue();
        assertThat(pausedJson.path("effectiveInjection").asBoolean()).isFalse();
        assertThat(pausedJson.path("affects").asText()).isEqualTo("new_messages_only");
        assertThat(pausedJson.path("boardOrAckChanged").asBoolean()).isFalse();
        long pausedRevision = pausedJson.path("revision").asLong();

        ToolResult repeatedPause = tool("qq_thread_set_injection").execute(call(Map.of(
                "rawSource", ONEBOT_GROUP, "enabled", false)));
        JsonNode repeatedPauseJson = mapper.readTree(repeatedPause.message());
        assertThat(repeatedPauseJson.path("changed").asBoolean()).isFalse();
        assertThat(repeatedPauseJson.path("revision").asLong()).isEqualTo(pausedRevision);

        ToolResult threadPaused = tool("qq_thread_set_injection").execute(call(Map.of(
                "threadId", "thread.demo", "enabled", false)));
        assertThat(threadPaused.success()).as(threadPaused.message()).isTrue();
        JsonNode threadPausedJson = mapper.readTree(threadPaused.message());
        assertThat(threadPausedJson.path("target").asText()).isEqualTo("thread");
        assertThat(threadPausedJson.path("enabled").asBoolean()).isFalse();

        ToolResult resumed = tool("qq_thread_set_injection").execute(call(Map.of(
                "threadId", "thread.demo", "enabled", true)));
        assertThat(resumed.success()).as(resumed.message()).isTrue();

        ToolResult identity = tool("qq_thread_identity_bind").execute(call(Map.of(
                "threadId", "thread.demo",
                "channel", "onebot",
                "channelUserId", "10001",
                "canonicalUserId", "person-1",
                "scope", Map.of("type", "thread", "value", "thread.demo"))));
        assertThat(identity.success()).as(identity.message()).isTrue();
        JsonNode identityJson = mapper.readTree(identity.message());
        assertThat(identityJson.path("changed").asBoolean()).isTrue();
        assertThat(identityJson.path("scopeType").asText()).isEqualTo("thread");
        long identityRevision = identityJson.path("revision").asLong();

        ToolResult repeatedIdentity = tool("qq_thread_identity_bind").execute(call(Map.of(
                "threadId", "thread.demo",
                "channel", "onebot",
                "channelUserId", "10001",
                "canonicalUserId", "person-1",
                "scope", Map.of("type", "thread", "value", "thread.demo"))));
        assertThat(mapper.readTree(repeatedIdentity.message()).path("changed").asBoolean())
                .isFalse();
        assertThat(mapper.readTree(repeatedIdentity.message()).path("revision").asLong())
                .isEqualTo(identityRevision);

        ToolResult identityUnbind = tool("qq_thread_identity_unbind").execute(call(Map.of(
                "threadId", "thread.demo",
                "channel", "onebot",
                "channelUserId", "10001",
                "scope", Map.of("type", "thread", "value", "thread.demo"))));
        assertThat(identityUnbind.success()).as(identityUnbind.message()).isTrue();
        assertThat(mapper.readTree(identityUnbind.message()).path("changed").asBoolean()).isTrue();
        ToolResult repeatedIdentityUnbind = tool("qq_thread_identity_unbind").execute(call(Map.of(
                "threadId", "thread.demo",
                "channel", "onebot",
                "channelUserId", "10001",
                "scope", Map.of("type", "thread", "value", "thread.demo"))));
        assertThat(mapper.readTree(repeatedIdentityUnbind.message()).path("changed").asBoolean())
                .isFalse();

        ToolResult sourceScopedIdentity = tool("qq_thread_identity_bind").execute(call(Map.of(
                "threadId", "thread.demo",
                "channel", "qq_official",
                "channelUserId", "member-openid-1",
                "canonicalUserId", "person-2",
                "scope", Map.of("type", "source", "value", OFFICIAL_GROUP))));
        assertThat(sourceScopedIdentity.success()).as(sourceScopedIdentity.message()).isTrue();
        JsonNode sourceScopedJson = mapper.readTree(sourceScopedIdentity.message());
        assertThat(sourceScopedJson.path("scopeType").asText()).isEqualTo("source");
        assertThat(sourceScopedJson.path("scopeValue").asText()).isEqualTo(OFFICIAL_GROUP);
        ToolResult sourceScopedUnbind = tool("qq_thread_identity_unbind").execute(call(Map.of(
                "threadId", "thread.demo",
                "channel", "qq_official",
                "channelUserId", "member-openid-1",
                "scope", Map.of("type", "source", "value", OFFICIAL_GROUP))));
        assertThat(sourceScopedUnbind.success()).as(sourceScopedUnbind.message()).isTrue();
        assertThat(mapper.readTree(sourceScopedUnbind.message()).path("changed").asBoolean())
                .isTrue();

        ToolResult status = tool("qq_thread_status").execute(call(Map.of(
                "threadId", "thread.demo")));
        assertThat(status.success()).as(status.message()).isTrue();
        JsonNode statusJson = mapper.readTree(status.message());
        assertThat(statusJson.path("revision").asLong())
                .isGreaterThanOrEqualTo(matchingRevision);
        assertThat(statusJson.path("bindings")).hasSize(2);
        assertThat(statusJson.path("identities")).isEmpty();
        assertThat(statusJson.path("thread").path("preferredChannel").asText())
                .isEqualTo("onebot");
        assertThat(statusJson.path("thread").path("matchingMode").asText()).isEqualTo("loose");
        assertThat(statusJson.path("consistency").path("available").asBoolean()).isFalse();
        assertThat(statusJson.path("consistency").path("reason").asText())
                .isEqualTo("phase10_not_wired");

        ToolResult unbound = tool("qq_thread_unbind").execute(call(Map.of(
                "threadId", "thread.demo", "rawSource", ONEBOT_GROUP)));
        assertThat(unbound.success()).as(unbound.message()).isTrue();
        JsonNode unboundJson = mapper.readTree(unbound.message());
        assertThat(unboundJson.path("changed").asBoolean()).isTrue();
        long unboundRevision = unboundJson.path("revision").asLong();

        ToolResult repeatedUnbind = tool("qq_thread_unbind").execute(call(Map.of(
                "threadId", "thread.demo", "rawSource", ONEBOT_GROUP)));
        JsonNode repeatedUnbindJson = mapper.readTree(repeatedUnbind.message());
        assertThat(repeatedUnbindJson.path("changed").asBoolean()).isFalse();
        assertThat(repeatedUnbindJson.path("revision").asLong()).isEqualTo(unboundRevision);

        ToolResult threadList = tool("qq_channel_thread_list").execute(call(Map.of("limit", 50)));
        assertThat(threadList.success()).as(threadList.message()).isTrue();
        JsonNode threadListJson = mapper.readTree(threadList.message());
        assertThat(threadListJson.path("scope").asText()).isEqualTo("local_registry");
        assertThat(threadListJson.path("platformComplete").asBoolean()).isFalse();
        assertThat(threadListJson.path("count").asInt()).isEqualTo(1);
        assertThat(threadListJson.path("threads").get(0).path("bindings")).hasSize(1);
        assertThat(threadListJson.path("threads").get(0).path("bindings").get(0)
                .path("channel").asText()).isEqualTo("qq_official");

        ToolResult sources = tool("qq_channel_sources").execute(call(Map.of()));
        assertThat(sources.success()).as(sources.message()).isTrue();
        JsonNode sourcesJson = mapper.readTree(sources.message());
        assertThat(sourcesJson.path("platformComplete").asBoolean()).isFalse();
        assertThat(sourcesJson.path("sources")).hasSize(2);

        assertThat(registry.audits(100))
                .anySatisfy(audit -> {
                    assertThat(audit.action()).isEqualTo("qq_thread_bind");
                    assertThat(audit.result()).isEqualTo(AuditResult.OK);
                })
                .anySatisfy(audit -> {
                    assertThat(audit.action()).isEqualTo("qq_thread_set_injection");
                    assertThat(audit.result()).isEqualTo(AuditResult.OK);
                })
                .anySatisfy(audit -> {
                    assertThat(audit.action()).isEqualTo("qq_thread_identity_bind");
                    assertThat(audit.result()).isEqualTo(AuditResult.OK);
                });
    }

    @Test
    void invalidInputsAndRegistryConstraintsUseFixedSafeErrorCodes() {
        observedSources.add(ONEBOT_GROUP);
        observedSources.add(ONEBOT_PRIVATE);
        observedSources.add(OFFICIAL_GROUP);

        expect("INVALID_ARGUMENT", tool("qq_channel_sources").execute(call(Map.of("limit", "10"))));
        expect("INVALID_ARGUMENT", tool("qq_channel_thread_list").execute(call(Map.of("limit", 0))));
        expect("INVALID_THREAD_ID", tool("qq_thread_bind").execute(call(Map.of(
                "threadId", "bad thread", "rawSource", ONEBOT_GROUP))));
        expect("INVALID_RAW_SOURCE", tool("qq_thread_bind").execute(call(Map.of(
                "threadId", "thread.bad", "rawSource", "qq_group/bot-1/not-a-number"))));
        expect("INVALID_CHANNEL", tool("qq_thread_bind").execute(call(Map.of(
                "threadId", "thread.bad", "rawSource", ONEBOT_GROUP, "channel", "other"))));
        expect("CHANNEL_MISMATCH", tool("qq_thread_bind").execute(call(Map.of(
                "threadId", "thread.bad", "rawSource", ONEBOT_GROUP,
                "channel", "qq_official"))));
        expect("ACCOUNT_UNKNOWN", tool("qq_thread_bind").execute(call(Map.of(
                "threadId", "thread.bad", "rawSource", "qq_group/unknown-bot/955432692"))));
        expect("THREAD_NOT_FOUND", tool("qq_thread_status").execute(call(Map.of(
                "threadId", "thread.missing"))));
        expect("THREAD_NOT_FOUND", tool("qq_thread_set_matching").execute(call(Map.of(
                "threadId", "thread.missing", "matchingMode", "strict"))));
        expect("INVALID_ARGUMENT", tool("qq_thread_set_injection").execute(call(Map.of(
                "enabled", false))));
        expect("INVALID_ARGUMENT", tool("qq_thread_set_injection").execute(call(Map.of(
                "rawSource", ONEBOT_GROUP, "threadId", "thread.demo", "enabled", false))));
        expect("INVALID_ARGUMENT", tool("qq_thread_set_injection").execute(call(Map.of(
                "rawSource", ONEBOT_GROUP, "enabled", "false"))));
        expect("INVALID_SCOPE", tool("qq_thread_identity_bind").execute(call(Map.of(
                "threadId", "thread.demo", "channel", "onebot", "channelUserId", "10001",
                "canonicalUserId", "person-1", "scope", "thread.demo"))));
        expect("INVALID_SCOPE", tool("qq_thread_identity_bind").execute(call(Map.of(
                "threadId", "thread.demo", "channel", "onebot", "channelUserId", "10001",
                "canonicalUserId", "person-1",
                "scope", Map.of("type", "global", "value", "x")))));

        assertThat(tool("qq_thread_bind").execute(call(Map.of(
                "threadId", "thread.one", "rawSource", ONEBOT_GROUP))).success()).isTrue();
        expect("SOURCE_ALREADY_BOUND", tool("qq_thread_bind").execute(call(Map.of(
                "threadId", "thread.two", "rawSource", ONEBOT_GROUP))));
        expect("THREAD_CHANNEL_ALREADY_BOUND", tool("qq_thread_bind").execute(call(Map.of(
                "threadId", "thread.one", "rawSource", ONEBOT_PRIVATE))));
        expect("THREAD_CHANNEL_NOT_BOUND", tool("qq_thread_identity_bind").execute(call(Map.of(
                "threadId", "thread.one", "channel", "qq_official", "channelUserId", "member-1",
                "canonicalUserId", "person-1",
                "scope", Map.of("type", "thread", "value", "thread.one")))));
        expect("IDENTITY_SCOPE_MISMATCH", tool("qq_thread_identity_bind").execute(call(Map.of(
                "threadId", "thread.one", "channel", "onebot", "channelUserId", "10001",
                "canonicalUserId", "person-1",
                "scope", Map.of("type", "source", "value", OFFICIAL_GROUP)))));
    }

    @Test
    void sourceCatalogFailuresAndMalformedSourceFormatsFailClosedWithoutEcho() {
        catalogEntries.add(new ObservedSource("qq_group/bot-1/not-a-number", NOW));
        expect("INVALID_RAW_SOURCE", tool("qq_channel_sources").execute(call(Map.of())));

        catalogEntries.clear();
        catalogEntries.add(new ObservedSource(ONEBOT_GROUP, NOW));
        catalogEntries.add(new ObservedSource(ONEBOT_GROUP, NOW.minusSeconds(1)));
        expect("SOURCE_CATALOG_INVALID", tool("qq_channel_sources").execute(call(Map.of())));

        List<AgentTool> brokenCatalogTools = ChannelTools.all(registry,
                limit -> {
                    throw new IllegalStateException("provider unavailable");
                },
                FIXED_CLOCK);
        expect("SOURCE_CATALOG_UNAVAILABLE", tool(brokenCatalogTools, "qq_channel_sources")
                .execute(call(Map.of())));

        List<AgentTool> nullCatalogTools = ChannelTools.all(registry, limit -> null, FIXED_CLOCK);
        expect("SOURCE_CATALOG_INVALID", tool(nullCatalogTools, "qq_channel_sources")
                .execute(call(Map.of())));
    }

    @Test
    void secretsBodiesAndUnsupportedArgumentsAreNeverEchoed() {
        observedSources.add(ONEBOT_GROUP);
        String secret = "fake-credential-7f4d9d";

        ToolResult extra = tool("qq_thread_bind").execute(call(Map.of(
                "threadId", "thread.secret",
                "rawSource", ONEBOT_GROUP,
                "token", secret)));
        expect("INVALID_ARGUMENT", extra);
        assertThat(extra.message()).doesNotContain(secret, "Authorization", "auth_token");

        ToolResult malformedSource = tool("qq_thread_set_injection").execute(call(Map.of(
                "rawSource", "qq_group/bot-1/" + secret, "enabled", false)));
        expect("INVALID_RAW_SOURCE", malformedSource);
        assertThat(malformedSource.message()).doesNotContain(secret,
                "Authorization", "auth_token", "正文");

        ToolResult malformedMode = tool("qq_thread_set_matching").execute(call(Map.of(
                "threadId", "thread.secret", "matchingMode", secret)));
        expect("INVALID_ARGUMENT", malformedMode);
        assertThat(malformedMode.message()).doesNotContain(secret,
                "Authorization", "auth_token", "正文");

        assertThat(registry.audits(100)).allSatisfy(audit -> {
            assertThat(audit.action()).doesNotContain(secret);
            assertThat(String.valueOf(audit.rawSource())).doesNotContain(secret);
        });
    }

    private List<AgentTool> tools() {
        return ChannelTools.all(registry,
                limit -> catalogEntries.stream().limit(limit).toList(),
                FIXED_CLOCK);
    }

    private AgentTool tool(String name) {
        return tool(tools(), name);
    }

    private static AgentTool tool(List<AgentTool> tools, String name) {
        return tools.stream().filter(tool -> name.equals(tool.name())).findFirst().orElseThrow();
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
        knownAccounts.add(channel + ":" + accountId);
    }

    private void available(String channel, String accountId) {
        availableAccounts.add(channel + ":" + accountId);
    }

    private static String key(Channel channel, String accountId) {
        return channel.wireName() + ":" + accountId;
    }

    private static final class TrackingGate implements ChannelMutationGate {

        private final ChannelMutationGate delegate = ChannelMutationGate.processLocal();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public <T> T underLock(Supplier<T> action) {
            calls.incrementAndGet();
            return delegate.underLock(action);
        }

        int calls() {
            return calls.get();
        }
    }
}
