package io.mosire.bma3.app.channel.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.app.channel.QqChannelRegistry;
import io.mosire.bma3.app.channel.QqChannelRegistry.ChannelBinding;
import io.mosire.bma3.app.channel.QqChannelRegistry.MatchingMode;
import io.mosire.bma3.app.channel.binding.BindingCrashHook;
import io.mosire.bma3.app.channel.binding.QqBindingCoordinator;
import io.mosire.bma3.app.channel.router.QqChannelBoardPort;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.ContentIntegrity;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.Direction;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.Observation;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.ObservationDraft;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.SourceBinding;
import io.mosire.bma3.app.channel.store.QqChannelObservationStore.TimeReliability;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelBindingCoordinatorToolsTest {

    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String THREAD = "thread.coordinated";
    private static final String RAW = "qq_group/bot-1/955432692";

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> knownAccounts = new HashSet<>();
    private final Set<String> observedSources = new HashSet<>();
    private final Set<String> availableAccounts = new HashSet<>();
    private QqChannelRegistry registry;
    private QqChannelObservationStore observations;
    private SqliteMessageBoardStore board;
    private CountingGate gate;
    private MutableCrashHook crashHook;
    private QqBindingCoordinator coordinator;
    private List<AgentTool> tools;

    @BeforeEach
    void setUp() {
        knownAccounts.add("onebot:bot-1");
        availableAccounts.add("onebot:bot-1");
        observedSources.add(RAW);
        registry = new QqChannelRegistry(tempDir.resolve("registry.db"),
                (channel, accountId) -> knownAccounts.contains(
                        channel.wireName() + ":" + accountId),
                observedSources::contains,
                FIXED_CLOCK,
                MatchingMode.STRICT,
                (channel, accountId) -> availableAccounts.contains(
                        channel.wireName() + ":" + accountId));
        observations = new QqChannelObservationStore(tempDir.resolve("observations.db"),
                FIXED_CLOCK);
        board = new SqliteMessageBoardStore(tempDir.resolve("board.db"));
        gate = new CountingGate();
        crashHook = new MutableCrashHook();
        coordinator = new QqBindingCoordinator(registry, observations,
                QqChannelBoardPort.from(board), gate,
                tempDir.resolve("binding-journal.db"), FIXED_CLOCK, crashHook);
        tools = ChannelTools.assembleProduction(registry, ChannelSourceCatalog.empty(), gate,
                coordinator, FIXED_CLOCK).tools();
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.close();
        }
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
    void productionAssemblyRequiresCoordinatorAndDelegatesBindUnbindToLedger() throws Exception {
        ChannelToolAssembly production = ChannelTools.assembleProduction(registry,
                ChannelSourceCatalog.empty(), gate, coordinator, FIXED_CLOCK);
        assertThat(production.productionReady()).isTrue();
        assertThat(production.bindingCoordinatorConfigured()).isTrue();
        assertThat(production.mutationGate()).isSameAs(gate);

        ChannelToolAssembly legacy = ChannelTools.assemble(registry,
                ChannelSourceCatalog.empty(), gate, FIXED_CLOCK);
        assertThat(legacy.productionReady()).isFalse();
        assertThat(legacy.bindingCoordinatorConfigured()).isFalse();
        assertThatThrownBy(() -> ChannelTools.assembleProduction(registry,
                ChannelSourceCatalog.empty(), gate, null, FIXED_CLOCK))
                .isInstanceOf(NullPointerException.class);

        long boardSeq = append("m0", NOW, "before bind");
        ToolResult bound = tool("qq_thread_bind").execute(call(Map.of(
                "threadId", THREAD,
                "rawSource", RAW,
                "channel", "onebot",
                "label", "coordinated")));
        assertThat(bound.success()).as(bound.message()).isTrue();
        JsonNode boundJson = mapper.readTree(bound.message());
        assertThat(boundJson.path("coordinated").asBoolean()).isTrue();
        assertThat(boundJson.path("ledgerGeneration").asLong()).isPositive();
        assertThat(boundJson.path("startBoardSeq").asLong()).isEqualTo(boardSeq + 1);
        assertThat(boundJson.path("channel").asText()).isEqualTo("onebot");
        assertThat(boundJson.path("accountId").asText()).isEqualTo("bot-1");
        assertThat(boundJson.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(boundJson.path("revision").asLong()).isPositive();
        assertThat(gate.calls()).isEqualTo(2); // tool outer gate + coordinator reentrant gate

        SourceBinding ledger = observations.sourceBinding(RAW).orElseThrow();
        assertThat(ledger.active()).isTrue();
        assertThat(ledger.generation()).isEqualTo(boundJson.path("ledgerGeneration").asLong());
        assertThat(ledger.startBoardSeq()).isEqualTo(boardSeq + 1);
        assertThat(registry.binding(RAW).filter(ChannelBinding::active)).get()
                .extracting(ChannelBinding::threadId).isEqualTo(THREAD);

        ToolResult unbound = tool("qq_thread_unbind").execute(call(Map.of(
                "threadId", THREAD, "rawSource", RAW)));
        assertThat(unbound.success()).as(unbound.message()).isTrue();
        JsonNode unboundJson = mapper.readTree(unbound.message());
        assertThat(unboundJson.path("coordinated").asBoolean()).isTrue();
        assertThat(unboundJson.path("ledgerUnbound").asBoolean()).isTrue();
        assertThat(unboundJson.path("ledgerComplete").asBoolean()).isTrue();
        assertThat(unboundJson.path("changed").asBoolean()).isTrue();
        assertThat(observations.sourceBinding(RAW).filter(SourceBinding::active)).isEmpty();
        assertThat(registry.binding(RAW).filter(ChannelBinding::active)).isEmpty();
        assertThat(gate.calls()).isEqualTo(4);
    }

    @Test
    void bindCrashRecoveryRollsBackLedgerThenRetrySucceeds() throws Exception {
        long boardSeq = append("m-bind-crash", NOW, "before bind");
        crashHook.failAt(BindingCrashHook.CrashPoint.AFTER_LEDGER_BIND);

        assertThatThrownBy(() -> tool("qq_thread_bind").execute(call(Map.of(
                "threadId", THREAD, "rawSource", RAW))))
                .isInstanceOf(SimulatedCrash.class);

        assertThat(observations.sourceBinding(RAW).filter(SourceBinding::active)).isPresent();
        assertThat(registry.binding(RAW).filter(ChannelBinding::active)).isEmpty();

        crashHook.clear();
        coordinator.repairOrThrow();
        assertThat(observations.sourceBinding(RAW).filter(SourceBinding::active)).isEmpty();

        ToolResult retry = tool("qq_thread_bind").execute(call(Map.of(
                "threadId", THREAD, "rawSource", RAW)));
        assertThat(retry.success()).as(retry.message()).isTrue();
        JsonNode retryJson = mapper.readTree(retry.message());
        assertThat(retryJson.path("ledgerGeneration").asLong()).isPositive();
        assertThat(retryJson.path("startBoardSeq").asLong()).isEqualTo(boardSeq + 1);
        assertThat(observations.sourceBinding(RAW).filter(SourceBinding::active)).isPresent();
    }

    @Test
    void unbindCrashRecoveryCompletesBothStoresWithoutDeletingFacts() throws Exception {
        long boardSeq = append("m-unbind-crash", NOW, "before bind");
        assertThat(tool("qq_thread_bind").execute(call(Map.of(
                "threadId", THREAD, "rawSource", RAW))).success()).isTrue();
        observations.observe(new ObservationDraft(THREAD,
                QqChannelObservationStore.Channel.ONEBOT, "bot-1", RAW, "m-unbind-crash",
                boardSeq, "sender-1", Direction.INBOUND, NOW, NOW.plusMillis(5), 50,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE));

        crashHook.failAt(BindingCrashHook.CrashPoint.AFTER_REGISTRY_UNBIND);
        assertThatThrownBy(() -> tool("qq_thread_unbind").execute(call(Map.of(
                "threadId", THREAD, "rawSource", RAW))))
                .isInstanceOf(SimulatedCrash.class);

        assertThat(registry.binding(RAW).filter(ChannelBinding::active)).isEmpty();
        assertThat(observations.sourceBinding(RAW).filter(SourceBinding::active)).isPresent();
        assertThat(observations.observationsForThread(THREAD, null, null, 10).items())
                .hasSize(1);

        crashHook.clear();
        coordinator.repairOrThrow();
        assertThat(observations.sourceBinding(RAW).filter(SourceBinding::active)).isEmpty();
        assertThat(observations.observationsForThread(THREAD, null, null, 10).items())
                .hasSize(1);
        assertThat(tool("qq_thread_unbind").execute(call(Map.of(
                "threadId", THREAD, "rawSource", RAW))).success()).isTrue();
    }

    @Test
    void channelMismatchFailsBeforeCoordinatorOrAnyWrite() {
        int auditsBefore = coordinator.audits(20).size();
        ToolResult mismatch = tool("qq_thread_bind").execute(call(Map.of(
                "threadId", THREAD,
                "rawSource", RAW,
                "channel", "qq_official")));
        assertThat(mismatch.success()).isFalse();
        assertThat(mismatch.code()).isEqualTo("CHANNEL_MISMATCH");
        assertThat(observations.sourceBinding(RAW)).isEmpty();
        assertThat(registry.binding(RAW)).isEmpty();
        assertThat(coordinator.audits(20)).hasSize(auditsBefore);
    }

    @Test
    void wrongUnbindThreadReturnsFixedErrorAndKeepsBindingAndObservation() throws Exception {
        assertThat(tool("qq_thread_bind").execute(call(Map.of(
                "threadId", THREAD, "rawSource", RAW))).success()).isTrue();
        Observation observation = observations.observe(new ObservationDraft(THREAD,
                QqChannelObservationStore.Channel.ONEBOT, "bot-1", RAW, "m-wrong-thread",
                1L, "sender-1", Direction.INBOUND, NOW, NOW.plusMillis(5), 50,
                TimeReliability.RELIABLE, ContentIntegrity.COMPLETE)).observation();

        ToolResult wrongThread = tool("qq_thread_unbind").execute(call(Map.of(
                "threadId", "thread.other", "rawSource", RAW)));
        assertThat(wrongThread.success()).isFalse();
        assertThat(wrongThread.code()).isEqualTo("SOURCE_ALREADY_BOUND");
        assertThat(wrongThread.message()).isNotBlank();
        assertThat(registry.binding(RAW).filter(ChannelBinding::active)).isPresent();
        assertThat(observations.sourceBinding(RAW).filter(SourceBinding::active)).isPresent();
        assertThat(observations.observationsForThread(THREAD, null, null, 10).items())
                .extracting(Observation::observationId).contains(observation.observationId());
    }

    @Test
    void coordinatorBlockerMapsToSafeFixedCodeWithoutArbitraryMessage() throws Exception {
        // Deliberately create a registry-only active binding without a ledger generation.
        registry.bind(THREAD, RAW);

        ToolResult blocked = tool("qq_thread_bind").execute(call(Map.of(
                "threadId", THREAD, "rawSource", RAW)));
        assertThat(blocked.success()).isFalse();
        assertThat(blocked.code()).isEqualTo("BINDING_LEDGER_BLOCKED");
        assertThat(blocked.message())
                .isEqualTo("registry/ledger 状态不可证明，操作已拒绝")
                .doesNotContain(RAW, "startBoardSeq", "Exception");
    }

    private AgentTool tool(String name) {
        return tools.stream().filter(tool -> name.equals(tool.name())).findFirst().orElseThrow();
    }

    private long append(String messageId, Instant at, String text) {
        InboundMessage message = new InboundMessage(SourceAddress.parse(RAW), messageId, at,
                List.of(new Segment("text", text)), io.mosire.bma3.core.message.Direction.INBOUND,
                "sender-1", "Sender One");
        return board.append(message).message().localSeq();
    }

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), arguments, AgentIdentity.external());
    }

    private static final class CountingGate implements ChannelMutationGate {

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

    private static final class MutableCrashHook implements BindingCrashHook {

        private volatile CrashPoint point;

        void failAt(CrashPoint point) {
            this.point = point;
        }

        void clear() {
            this.point = null;
        }

        @Override
        public void at(CrashPoint current) {
            if (current == point) {
                throw new SimulatedCrash();
            }
        }
    }

    private static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;
    }
}
