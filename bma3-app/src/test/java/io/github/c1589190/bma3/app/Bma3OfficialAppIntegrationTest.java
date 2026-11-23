package io.github.c1589190.bma3.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.github.c1589190.bma3.app.config.AppConfig;
import io.github.c1589190.bma3.app.config.ConfigLoader;
import io.github.c1589190.bma3.core.message.DeliveryStatus;
import io.github.c1589190.bma3.core.message.Direction;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.core.store.SqliteMessageBoardStore;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountClient;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountClientContext;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountClientFactory;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountConfig;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountErrorKind;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountRuntimeStatus;
import io.github.c1589190.bma3.qqofficial.account.OfficialQqAccountState;
import io.github.c1589190.bma3.qqofficial.api.OfficialQqApiClient;
import io.github.c1589190.bma3.qqofficial.auth.OfficialQqTokenManager;
import io.github.c1589190.bma3.qqofficial.event.OfficialQqEvent;
import io.github.c1589190.bma3.qqofficial.event.OfficialQqSystemEvent;
import io.github.c1589190.bma3.qqofficial.eventstore.OfficialQqEventReferenceStore;
import io.github.c1589190.bma3.core.message.Segment;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class Bma3OfficialAppIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OFFICIAL_A = "official-a";
    private static final String OFFICIAL_B = "official-b";
    private static final String OFFICIAL_A_CONFIG = """
            qqofficial:
              accounts:
                - accountId: official-a
                  appId: "synthetic-app-id-a"
                  appSecretEnv: BMA3_OFFICIAL_A_SECRET
                  enabled: true
                  shard: [0, 1]
            """;

    @TempDir
    Path root;

    @Test
    void emptyAndDisabledOfficialConfigStartsOfflineAndDiscoversTools() throws Exception {
        String yaml = baseConfig() + """
                qqofficial:
                  accounts:
                    - accountId: official-disabled
                      appId: "synthetic-app-id-disabled"
                      appSecretEnv: BMA3_OFFICIAL_DISABLED_SECRET
                      enabled: false
                      shard: [0, 1]
                """;
        writeConfig(yaml);
        AppConfig config = ConfigLoader.load(root, Map.of());
        FakeFactory factory = new FakeFactory();

        try (Bma3App app = Bma3App.start(config, Map.of(), root, factory);
             McpSyncClient mcp = openMcp(app)) {
            assertThat(factory.createCount()).isZero();
            assertThat(app.officialService().registry().accountStatuses())
                    .singleElement()
                    .satisfies(status -> {
                        assertThat(status.accountId()).isEqualTo("official-disabled");
                        assertThat(status.enabled()).isFalse();
                        assertThat(status.state()).isEqualTo(OfficialQqAccountState.DISABLED);
                    });
            List<String> names = mcp.listTools().tools().stream()
                    .map(McpSchema.Tool::name).toList();
            assertThat(names).contains(
                    "qq_official_account_list",
                    "qq_official_account_status",
                    "qq_official_group_list",
                    "qq_official_group_info",
                    "qq_official_bot_state",
                    "qq_official_user_info",
                    "qq_official_conversation_list",
                    "qq_official_conversation_history",
                    "qq_official_message_get",
                    "qq_official_share_qr",
                    "qq_official_send_text",
                    "qq_official_reply");
            assertThat(names).contains("qq_channel_sources", "qq_channel_thread_list",
                    "qq_thread_bind", "qq_thread_unbind", "qq_thread_compare",
                    "qq_thread_history");

            JsonNode probe = awaitWatchdogComponent(app, "qqOfficial");
            assertThat(probe.path("health").asText()).isEqualTo("UP");
            assertThat(probe.path("detail").asText()).isEqualTo("disabled");

            McpSchema.CallToolResult disabledSend = mcp.callTool(
                    new McpSchema.CallToolRequest("qq_official_send_text", Map.of(
                            "accountId", "official-disabled",
                            "source", "qq_official_private/official-disabled/user-1",
                            "text", "must not leave the process")));
            assertThat(disabledSend.isError()).isTrue();
            assertThat(text(disabledSend)).contains("ACCOUNT");
            assertThat(text(disabledSend)).doesNotContain("must not leave the process");
        }
    }

    @Test
    void multiAccountConfigPassesEveryRuntimeFieldToTheFactory() throws Exception {
        Path secretFile = root.resolve("official-file.secret");
        Files.writeString(secretFile, "synthetic-file-secret");
        Files.setPosixFilePermissions(secretFile, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        writeConfig(baseConfig() + """
                qqofficial:
                  accounts:
                    - accountId: official-a
                      appId: "synthetic-app-id-a"
                      appSecretEnv: BMA3_OFFICIAL_A_SECRET
                      enabled: true
                      shard: [0, 1]
                    - accountId: official-b
                      appId: "synthetic-app-id-b"
                      appSecretFile: %s
                      enabled: true
                      shard: [0, 1]
                  reconnect:
                    initialMs: 1500
                    maxMs: 120000
                    jitter: false
                  http:
                    connectTimeoutSeconds: 7
                    requestTimeoutSeconds: 25
                """.formatted(secretFile.toAbsolutePath()));
        Map<String, String> env = Map.of(
                "BMA3_OFFICIAL_A_SECRET", "synthetic-env-secret-a");
        AppConfig config = ConfigLoader.load(root, env);
        FakeFactory factory = new FakeFactory();

        try (Bma3App app = Bma3App.start(config, env, root, factory)) {
            assertThat(factory.createCount()).isEqualTo(2);
            OfficialQqAccountConfig a = factory.context(OFFICIAL_A).config();
            assertThat(a.accountId()).isEqualTo(OFFICIAL_A);
            assertThat(a.appId()).isEqualTo("synthetic-app-id-a");
            assertThat(a.appSecretEnv()).isEqualTo("BMA3_OFFICIAL_A_SECRET");
            assertThat(a.appSecretFile()).isNull();
            assertThat(a.shardIndex()).isZero();
            assertThat(a.shardCount()).isEqualTo(1);
            assertThat(a.runtimeOptions().httpConnectTimeout()).isEqualTo(Duration.ofSeconds(7));
            assertThat(a.runtimeOptions().httpRequestTimeout()).isEqualTo(Duration.ofSeconds(25));
            assertThat(a.runtimeOptions().reconnectInitial()).isEqualTo(Duration.ofMillis(1500));
            assertThat(a.runtimeOptions().reconnectMax()).isEqualTo(Duration.ofMinutes(2));
            assertThat(a.runtimeOptions().reconnectJitter()).isFalse();

            OfficialQqAccountConfig b = factory.context(OFFICIAL_B).config();
            assertThat(b.appSecretEnv()).isEmpty();
            assertThat(b.appSecretFile()).isEqualTo(secretFile.toAbsolutePath().normalize());
            assertThat(b.runtimeOptions()).isEqualTo(a.runtimeOptions());
            assertThat(app.officialService().registry().desiredConfigs()).hasSize(2);
        }
    }

    @Test
    void fakeOfficialEventReachesBoardInboxAndReadTool() throws Exception {
        writeConfig(baseConfig() + """
                qqofficial:
                  accounts:
                    - accountId: official-a
                      appId: "synthetic-app-id-a"
                      appSecretEnv: BMA3_OFFICIAL_A_SECRET
                      enabled: true
                      shard: [0, 1]
                """);
        Map<String, String> env = Map.of(
                "BMA3_OFFICIAL_A_SECRET", "synthetic-env-secret-a");
        AppConfig config = ConfigLoader.load(root, env);
        FakeFactory factory = new FakeFactory();

        try (Bma3App app = Bma3App.start(config, env, root, factory);
             McpSyncClient mcp = openMcp(app)) {
            OfficialQqEvent event = new OfficialQqEvent(
                    "event-1", "C2C_MESSAGE_CREATE", "message-1", "", "user-openid-1",
                    "", "Alice", "", "", Instant.parse("2026-11-17T00:00:00Z"),
                    List.of(new Segment("text", "hello official")), "", "trace-1");
            factory.context(OFFICIAL_A).dispatchSink().accept(event);

            await(() -> app.inboxService().status().available() > 0, Duration.ofSeconds(5));
            assertThat(app.officialService().ingest().stats().boardAcceptedMessages())
                    .isEqualTo(1);
            assertThat(app.officialService().ingest().stats().projectedMessages())
                    .isEqualTo(1);
            assertThat(app.inboxService().panel().items()).isNotEmpty();

            McpSchema.CallToolResult history = mcp.callTool(new McpSchema.CallToolRequest(
                    "qq_official_conversation_history",
                    Map.of("source", "qq_official_private/official-a/user-openid-1", "limit", 10)));
            assertThat(history.isError()).isFalse();
            JsonNode body = JSON.readTree(text(history));
            assertThat(body.path("found").asBoolean()).isTrue();
            assertThat(body.path("messages")).hasSize(1);
            assertThat(body.path("messages").get(0).path("text").asText())
                    .isEqualTo("hello official");

            JsonNode officialProbe = awaitWatchdogComponent(app, "qqOfficial");
            assertThat(officialProbe.path("health").asText()).isEqualTo("UP");
            assertThat(officialProbe.path("facts").path("connected").asText()).isEqualTo("1");

            McpSchema.CallToolResult accounts = mcp.callTool(new McpSchema.CallToolRequest(
                    "qq_official_account_list", Map.of()));
            assertThat(accounts.isError()).isFalse();
            assertThat(JSON.readTree(text(accounts)).path("accounts").get(0)
                    .path("accountId").asText()).isEqualTo(OFFICIAL_A);
        }
    }

    @Test
    void officialEventsRemainIsolatedPerAccount() throws Exception {
        writeConfig(baseConfig() + """
                qqofficial:
                  accounts:
                    - accountId: official-a
                      appId: "synthetic-app-id-a"
                      appSecretEnv: BMA3_OFFICIAL_A_SECRET
                      enabled: true
                    - accountId: official-b
                      appId: "synthetic-app-id-b"
                      appSecretEnv: BMA3_OFFICIAL_B_SECRET
                      enabled: true
                """);
        Map<String, String> env = Map.of(
                "BMA3_OFFICIAL_A_SECRET", "synthetic-env-secret-a",
                "BMA3_OFFICIAL_B_SECRET", "synthetic-env-secret-b");
        AppConfig config = ConfigLoader.load(root, env);
        FakeFactory factory = new FakeFactory();

        try (Bma3App app = Bma3App.start(config, env, root, factory);
             McpSyncClient mcp = openMcp(app)) {
            OfficialQqEvent eventA = new OfficialQqEvent(
                    "event-a", "C2C_MESSAGE_CREATE", "message-a", "", "user-openid-a",
                    "", "Alice", "", "", Instant.parse("2026-11-17T00:00:00Z"),
                    List.of(new Segment("text", "body-a")), "", "");
            OfficialQqEvent eventB = new OfficialQqEvent(
                    "event-b", "C2C_MESSAGE_CREATE", "message-b", "", "user-openid-b",
                    "", "Bob", "", "", Instant.parse("2026-11-17T00:00:01Z"),
                    List.of(new Segment("text", "body-b")), "", "");
            factory.context(OFFICIAL_A).dispatchSink().accept(eventA);
            factory.context(OFFICIAL_B).dispatchSink().accept(eventB);
            await(() -> app.officialService().ingest().stats().boardAcceptedMessages() == 2,
                    Duration.ofSeconds(5));

            JsonNode historyA = JSON.readTree(text(mcp.callTool(new McpSchema.CallToolRequest(
                    "qq_official_conversation_history",
                    Map.of("source", "qq_official_private/official-a/user-openid-a")))));
            JsonNode historyB = JSON.readTree(text(mcp.callTool(new McpSchema.CallToolRequest(
                    "qq_official_conversation_history",
                    Map.of("source", "qq_official_private/official-b/user-openid-b")))));
            JsonNode crossAtoB = JSON.readTree(text(mcp.callTool(new McpSchema.CallToolRequest(
                    "qq_official_conversation_history",
                    Map.of("source", "qq_official_private/official-b/user-openid-a")))));

            assertThat(historyA.path("messages")).hasSize(1);
            assertThat(historyA.path("messages").get(0).path("text").asText())
                    .isEqualTo("body-a");
            assertThat(historyB.path("messages")).hasSize(1);
            assertThat(historyB.path("messages").get(0).path("text").asText())
                    .isEqualTo("body-b");
            assertThat(crossAtoB.path("found").asBoolean()).isFalse();
            assertThat(crossAtoB.path("messages")).isEmpty();
        }
    }

    @Test
    void officialSendTextWritesPendingThenSentThroughAccountBoundApi() throws Exception {
        try (LoopbackApiServer api = new LoopbackApiServer()) {
            writeConfig(configWithSend(3000, "reject"));
            Map<String, String> env = Map.of(
                    "BMA3_OFFICIAL_A_SECRET", "synthetic-env-secret-a");
            AppConfig config = ConfigLoader.load(root, env);
            FakeFactory factory = new FakeFactory()
                    .apiFactory(context -> OFFICIAL_A.equals(context.config().accountId())
                            ? Optional.of(loopbackApiClient(context, api.baseUri()))
                            : Optional.empty());
            SourceAddress source = SourceAddress.parse(
                    "qq_official_private/official-a/user-send");
            api.observeBoard(root.resolve("data/bot.db"), source);

            try (Bma3App app = Bma3App.start(config, env, root, factory);
                 McpSyncClient mcp = openMcp(app)) {
                McpSchema.CallToolResult result = mcp.callTool(new McpSchema.CallToolRequest(
                        "qq_official_send_text", Map.of(
                                "accountId", OFFICIAL_A,
                                "source", source.format(),
                                "text", "hello official send")));
                assertThat(result.isError()).isFalse();
                JsonNode outcomes = JSON.readTree(text(result));
                assertThat(outcomes.get(0).path("state").asText()).isEqualTo("SENT");
                assertThat(outcomes.get(0).path("messageId").asText())
                        .isEqualTo("external-msg-1");
                assertThat(api.sendCalls()).isEqualTo(1);
                assertThat(api.boardStatusAtDispatch()).isEqualTo("PENDING");

                try (SqliteMessageBoardStore probe = new SqliteMessageBoardStore(
                        root.resolve("data/bot.db"))) {
                    var outbound = probe.read(source, 0L, 10).stream()
                            .filter(row -> row.inbound().direction() == Direction.OUTBOUND)
                            .toList();
                    assertThat(outbound).hasSize(1);
                    assertThat(outbound.getFirst().delivery().status())
                            .isEqualTo(DeliveryStatus.SENT);
                    assertThat(outbound.getFirst().externalMessageId())
                            .isEqualTo("external-msg-1");
                }
            }
        }
    }

    @Test
    void officialSendTimeoutBecomesUnknownAndIsNotRetried() throws Exception {
        try (LoopbackApiServer api = new LoopbackApiServer()) {
            writeConfig(configWithSend(3000, "reject").replace(OFFICIAL_A_CONFIG, """
                    qqofficial:
                      accounts:
                        - accountId: official-a
                          appId: "synthetic-app-id-a"
                          appSecretEnv: BMA3_OFFICIAL_A_SECRET
                          enabled: true
                          shard: [0, 1]
                      http:
                        connectTimeoutSeconds: 1
                        requestTimeoutSeconds: 1
                    """));
            Map<String, String> env = Map.of(
                    "BMA3_OFFICIAL_A_SECRET", "synthetic-env-secret-a");
            AppConfig config = ConfigLoader.load(root, env);
            FakeFactory factory = new FakeFactory()
                    .apiFactory(context -> Optional.of(
                            loopbackApiClient(context, api.baseUri())));
            SourceAddress source = SourceAddress.parse(
                    "qq_official_private/official-a/user-timeout");
            api.observeBoard(root.resolve("data/bot.db"), source);
            api.delaySend(1500L);

            try (Bma3App app = Bma3App.start(config, env, root, factory);
                 McpSyncClient mcp = openMcp(app)) {
                McpSchema.CallToolResult result = mcp.callTool(new McpSchema.CallToolRequest(
                        "qq_official_send_text", Map.of(
                                "accountId", OFFICIAL_A,
                                "source", source.format(),
                                "text", "timeout me")));
                assertThat(result.isError()).isFalse();
                JsonNode outcome = JSON.readTree(text(result)).get(0);
                assertThat(outcome.path("state").asText()).isEqualTo("UNKNOWN");
                assertThat(outcome.path("errorCode").asText()).isEqualTo("TIMEOUT");
                assertThat(outcome.path("resendAllowed").asBoolean()).isFalse();
                assertThat(api.sendCalls()).isEqualTo(1);

                try (SqliteMessageBoardStore probe = new SqliteMessageBoardStore(
                        root.resolve("data/bot.db"))) {
                    var outbound = probe.read(source, 0L, 10).stream()
                            .filter(row -> row.inbound().direction() == Direction.OUTBOUND)
                            .toList();
                    assertThat(outbound).singleElement().satisfies(row -> {
                        assertThat(row.delivery().status()).isEqualTo(DeliveryStatus.UNKNOWN);
                        assertThat(row.delivery().errorCode()).isEqualTo("TIMEOUT");
                    });
                }
            }
        }
    }

    @Test
    void officialReplyEventIdIsBoundToExactAccountAndSource() throws Exception {
        try (LoopbackApiServer api = new LoopbackApiServer()) {
            writeConfig(configWithSend(3000, "reject"));
            Map<String, String> env = Map.of(
                    "BMA3_OFFICIAL_A_SECRET", "synthetic-env-secret-a");
            AppConfig config = ConfigLoader.load(root, env);
            FakeFactory factory = new FakeFactory()
                    .apiFactory(context -> Optional.of(
                            loopbackApiClient(context, api.baseUri())));
            SourceAddress source = SourceAddress.parse(
                    "qq_official_group/official-a/group-reply");
            api.observeBoard(root.resolve("data/bot.db"), source);

            try (Bma3App app = Bma3App.start(config, env, root, factory);
                 McpSyncClient mcp = openMcp(app)) {
                OfficialQqSystemEvent inbound = new OfficialQqSystemEvent(
                        "event-reply-valid", "GROUP_ADD_ROBOT", 1,
                        Instant.parse("2026-11-17T00:00:00Z"),
                        Instant.parse("2026-11-17T00:00:00Z"), false,
                        "group-reply", "", "", "", "", "", "", "");
                factory.context(OFFICIAL_A).dispatchSink().accept(inbound);
                await(() -> app.officialService().eventReferenceStore()
                        .find(OFFICIAL_A, source, "event-reply-valid").isPresent(),
                        Duration.ofSeconds(5));

                McpSchema.CallToolResult valid = mcp.callTool(new McpSchema.CallToolRequest(
                        "qq_official_reply", Map.of(
                                "accountId", OFFICIAL_A,
                                "source", source.format(),
                                "text", "event reply",
                                "eventId", "event-reply-valid")));
                assertThat(valid.isError()).as(text(valid)).isFalse();
                assertThat(JSON.readTree(text(valid)).get(0).path("state").asText())
                        .isEqualTo("SENT");
                assertThat(api.sendCalls()).isEqualTo(1);

                McpSchema.CallToolResult unknown = mcp.callTool(
                        new McpSchema.CallToolRequest("qq_official_reply", Map.of(
                                "accountId", OFFICIAL_A,
                                "source", source.format(),
                                "text", "bad event",
                                "eventId", "event-does-not-exist")));
                assertThat(unknown.isError()).isTrue();
                assertThat(text(unknown)).contains("EVENT_REFERENCE_UNKNOWN");
                assertThat(api.sendCalls()).isEqualTo(1);

                McpSchema.CallToolResult crossSource = mcp.callTool(
                        new McpSchema.CallToolRequest("qq_official_reply", Map.of(
                                "accountId", OFFICIAL_A,
                                "source", "qq_official_group/official-a/other-group",
                                "text", "wrong source",
                                "eventId", "event-reply-valid")));
                assertThat(crossSource.isError()).isTrue();
                assertThat(text(crossSource)).contains("EVENT_REFERENCE_SOURCE_MISMATCH");
                assertThat(api.sendCalls()).isEqualTo(1);
            }
        }
    }

    @Test
    void qqconnectSendPolicyHotReloadAffectsLaterOfficialCalls() throws Exception {
        try (LoopbackApiServer api = new LoopbackApiServer()) {
            writeConfig(configWithSend(4, "reject"));
            Map<String, String> env = Map.of(
                    "BMA3_OFFICIAL_A_SECRET", "synthetic-env-secret-a");
            AppConfig config = ConfigLoader.load(root, env);
            FakeFactory factory = new FakeFactory()
                    .apiFactory(context -> Optional.of(
                            loopbackApiClient(context, api.baseUri())));

            try (Bma3App app = Bma3App.start(config, env, root, factory);
                 McpSyncClient mcp = openMcp(app)) {
                McpSchema.CallToolResult tooLong = mcp.callTool(
                        new McpSchema.CallToolRequest("qq_official_send_text", Map.of(
                                "accountId", OFFICIAL_A,
                                "source", "qq_official_private/official-a/user-policy",
                                "text", "12345")));
                assertThat(tooLong.isError()).isTrue();
                assertThat(text(tooLong)).contains("TEXT_TOO_LONG");
                assertThat(api.sendCalls()).isZero();

                writeConfig(configWithSend(10, "reject"));
                awaitSendMaxChars(mcp, 10);

                McpSchema.CallToolResult accepted = mcp.callTool(
                        new McpSchema.CallToolRequest("qq_official_send_text", Map.of(
                                "accountId", OFFICIAL_A,
                                "source", "qq_official_private/official-a/user-policy",
                                "text", "12345")));
                assertThat(accepted.isError()).isFalse();
                assertThat(JSON.readTree(text(accepted)).get(0).path("state").asText())
                        .isEqualTo("SENT");
                assertThat(api.sendCalls()).isEqualTo(1);
            }
        }
    }

    @Test
    void eventReferenceStorePersistsAcrossRestartAndSkipsReadyEvents() throws Exception {
        writeConfig(baseConfig() + """
                qqofficial:
                  accounts:
                    - accountId: official-a
                      appId: "synthetic-app-id-a"
                      appSecretEnv: BMA3_OFFICIAL_A_SECRET
                      enabled: true
                """);
        Map<String, String> env = Map.of(
                "BMA3_OFFICIAL_A_SECRET", "synthetic-env-secret-a");
        AppConfig config = ConfigLoader.load(root, env);
        SourceAddress source = SourceAddress.parse(
                "qq_official_private/official-a/user-openid-ref");
        FakeFactory factory = new FakeFactory();

        try (Bma3App app = Bma3App.start(config, env, root, factory)) {
            OfficialQqEvent message = new OfficialQqEvent(
                    "event-ref-1", "C2C_MESSAGE_CREATE", "message-ref-1", "",
                    "user-openid-ref", "", "Alice", "", "",
                    Instant.parse("2026-11-17T00:00:00Z"),
                    List.of(new Segment("text", "persist me")), "", "");
            factory.context(OFFICIAL_A).dispatchSink().accept(message);
            await(() -> app.officialService().ingest().stats().boardAcceptedMessages() == 1,
                    Duration.ofSeconds(5));
        }

        Path referenceDb = root.resolve("data/official-event-reference.db");
        try (OfficialQqEventReferenceStore reopened = new OfficialQqEventReferenceStore(referenceDb)) {
            assertThat(reopened.count()).isEqualTo(1);
            assertThat(reopened.find(OFFICIAL_A, source, "event-ref-1")).get()
                    .satisfies(reference -> {
                        assertThat(reference.accountId()).isEqualTo(OFFICIAL_A);
                        assertThat(reference.source()).isEqualTo(source.format());
                        assertThat(reference.messageId()).isEqualTo("message-ref-1");
                        assertThat(reference.eventType()).isEqualTo("C2C_MESSAGE_CREATE");
                        assertThat(reference.direction()).isEqualTo(Direction.INBOUND);
                    });
        }

        try (Bma3App restarted = Bma3App.start(config, env, root, factory)) {
            assertThat(restarted.officialService().eventReferenceStore()
                    .find(OFFICIAL_A, source, "event-ref-1")).isPresent();
            OfficialQqSystemEvent groupAdded = new OfficialQqSystemEvent(
                    "system-ref-1", "GROUP_ADD_ROBOT", 2,
                    Instant.parse("2026-11-17T00:00:02Z"),
                    Instant.parse("2026-11-17T00:00:02Z"), false,
                    "group-openid-ref", "", "", "", "", "", "", "");
            factory.context(OFFICIAL_A).dispatchSink().accept(groupAdded);
            assertThat(restarted.officialService().eventReferenceStore().count()).isEqualTo(2);

            OfficialQqSystemEvent ready = new OfficialQqSystemEvent(
                    "ready-ref-1", "READY", 3,
                    Instant.parse("2026-11-17T00:00:03Z"),
                    Instant.parse("2026-11-17T00:00:03Z"), false,
                    "", "", "", "", "", "", "", "");
            factory.context(OFFICIAL_A).dispatchSink().accept(ready);
            assertThat(restarted.officialService().eventReferenceStore().count()).isEqualTo(2);
        }
    }

    @Test
    void configWatcherRemovesOldAccountAndRestartsChangedAccount() throws Exception {
        String first = baseConfig() + """
                qqofficial:
                  accounts:
                    - accountId: official-a
                      appId: "synthetic-app-id-a"
                      appSecretEnv: BMA3_OFFICIAL_A_SECRET
                      enabled: true
                    - accountId: official-b
                      appId: "synthetic-app-id-b"
                      appSecretEnv: BMA3_OFFICIAL_B_SECRET
                      enabled: true
                  reconnect:
                    initialMs: 1000
                    maxMs: 60000
                """;
        writeConfig(first);
        Map<String, String> env = Map.of(
                "BMA3_OFFICIAL_A_SECRET", "synthetic-env-secret-a",
                "BMA3_OFFICIAL_B_SECRET", "synthetic-env-secret-b");
        AppConfig config = ConfigLoader.load(root, env);
        FakeFactory factory = new FakeFactory();

        try (Bma3App app = Bma3App.start(config, env, root, factory)) {
            FakeClient oldA = factory.client(OFFICIAL_A);
            FakeClient oldB = factory.client(OFFICIAL_B);
            writeConfig(baseConfig() + """
                    qqofficial:
                      accounts:
                        - accountId: official-a
                          appId: "synthetic-app-id-a"
                          appSecretEnv: BMA3_OFFICIAL_A_SECRET
                          enabled: true
                      reconnect:
                        initialMs: 2222
                        maxMs: 60000
                    """);

            await(() -> factory.createCount(OFFICIAL_A) >= 2 && oldB.closed(),
                    Duration.ofSeconds(8));
            assertThat(oldA.closed()).isTrue();
            assertThat(oldB.closed()).isTrue();
            assertThat(factory.context(OFFICIAL_A).config().runtimeOptions()
                    .reconnectInitial()).isEqualTo(Duration.ofMillis(2222));
            assertThat(app.officialService().registry().desiredConfigs())
                    .extracting(OfficialQqAccountConfig::accountId)
                    .containsExactly(OFFICIAL_A);
        }
    }

    @Test
    void periodicReconcileDetectsSecretFileRotationWithoutConfigChange() throws Exception {
        Path secretFile = root.resolve("rotating.secret");
        Files.writeString(secretFile, "synthetic-secret-one");
        Files.setPosixFilePermissions(secretFile, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        writeConfig(baseConfig() + """
                qqofficial:
                  accounts:
                    - accountId: official-a
                      appId: "synthetic-app-id-a"
                      appSecretFile: %s
                      enabled: true
                """.formatted(secretFile.toAbsolutePath()));
        AppConfig config = ConfigLoader.load(root, Map.of());
        FakeFactory factory = new FakeFactory();

        try (Bma3App app = Bma3App.start(config, Map.of(), root, factory)) {
            FakeClient first = factory.client(OFFICIAL_A);
            assertThat(factory.createCount()).isEqualTo(1);
            Files.writeString(secretFile, "synthetic-secret-two");
            Files.setPosixFilePermissions(secretFile, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            app.officialService().reconcileNow();

            assertThat(factory.createCount(OFFICIAL_A)).isEqualTo(2);
            assertThat(first.closed()).isTrue();
            assertThat(factory.client(OFFICIAL_A)).isNotSameAs(first);
            assertThat(factory.context(OFFICIAL_A).config().appSecretFile())
                    .isEqualTo(secretFile.toAbsolutePath().normalize());
        }
    }

    @Test
    void credentialFileInsideGitWorktreeFailsClosedBeforeFactory() throws Exception {
        Path repo = root.resolve("repo");
        Files.createDirectory(repo);
        Files.createDirectory(repo.resolve(".git"));
        Path secretFile = repo.resolve("official.secret");
        Files.writeString(secretFile, "synthetic-secret-in-repo");
        Files.setPosixFilePermissions(secretFile, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        writeConfig(baseConfig() + """
                qqofficial:
                  accounts:
                    - accountId: official-a
                      appId: "synthetic-app-id-a"
                      appSecretFile: %s
                      enabled: true
                """.formatted(secretFile.toAbsolutePath()));
        AppConfig config = ConfigLoader.load(root, Map.of());
        FakeFactory factory = new FakeFactory();

        try (Bma3App app = Bma3App.start(config, Map.of(), root, factory)) {
            assertThat(factory.createCount()).isZero();
            assertThat(app.officialService().registry().accountStatus(OFFICIAL_A))
                    .get()
                    .satisfies(status -> {
                        assertThat(status.state()).isEqualTo(OfficialQqAccountState.DOWN);
                        assertThat(status.errorKind())
                                .isEqualTo(OfficialQqAccountErrorKind.CREDENTIAL_FORBIDDEN_LOCATION);
                    });
        }
    }

    @Test
    void shareQrUsesOnlyTheExactAccountsApiClient() throws Exception {
        try (LoopbackApiServer api = new LoopbackApiServer()) {
            writeConfig(baseConfig() + """
                    qqofficial:
                      accounts:
                        - accountId: official-a
                          appId: "synthetic-app-id-a"
                          appSecretEnv: BMA3_OFFICIAL_A_SECRET
                          enabled: true
                        - accountId: official-b
                          appId: "synthetic-app-id-b"
                          appSecretEnv: BMA3_OFFICIAL_B_SECRET
                          enabled: true
                    """);
            Map<String, String> env = Map.of(
                    "BMA3_OFFICIAL_A_SECRET", "synthetic-env-secret-a",
                    "BMA3_OFFICIAL_B_SECRET", "synthetic-env-secret-b");
            AppConfig config = ConfigLoader.load(root, env);
            FakeFactory factory = new FakeFactory()
                    .apiFactory(context -> OFFICIAL_A.equals(context.config().accountId())
                            ? Optional.of(loopbackApiClient(context, api.baseUri()))
                            : Optional.empty());

            try (Bma3App app = Bma3App.start(config, env, root, factory);
                 McpSyncClient mcp = openMcp(app)) {
                McpSchema.CallToolResult missing = mcp.callTool(new McpSchema.CallToolRequest(
                        "qq_official_share_qr", Map.of("accountId", OFFICIAL_B)));
                assertThat(missing.isError()).isTrue();
                assertThat(text(missing)).contains("ACCOUNT_NOT_FOUND");
                assertThat(api.linkCalls()).isZero();

                McpSchema.CallToolResult generated = mcp.callTool(new McpSchema.CallToolRequest(
                        "qq_official_share_qr", Map.of("accountId", OFFICIAL_A)));
                assertThat(generated.isError()).isFalse();
                JsonNode output = JSON.readTree(text(generated));
                assertThat(output.path("accountId").asText()).isEqualTo(OFFICIAL_A);
                assertThat(output.path("url").asText()).isEqualTo(api.shareUrl());
                assertThat(Files.isRegularFile(Path.of(output.path("pngPath").asText()))).isTrue();
                assertThat(api.linkCalls()).isEqualTo(1);
                assertThat(text(generated)).doesNotContain("synthetic-env-secret-a")
                        .doesNotContain("app-secret")
                        .doesNotContain("access_token");
            }
        }
    }

    @Test
    void failedOneBotReloadKeepsOldPolicyAndDoesNotPublishNewOfficialAccounts() throws Exception {
        writeConfig(baseConfig());
        Map<String, String> env = Map.of(
                "BMA3_OFFICIAL_NEW_SECRET", "synthetic-env-secret-new");
        AppConfig config = ConfigLoader.load(root, env);
        FakeFactory factory = new FakeFactory();

        try (Bma3App app = Bma3App.start(config, env, root, factory);
             McpSyncClient mcp = openMcp(app)) {
            String invalidReload = baseConfig().replace("""
                    qqconnect:
                      accounts: []
                    """, """
                    qqconnect:
                      accounts:
                        - accountId: onebot-bad
                          endpoint: ws://127.0.0.1:1
                          tokenEnv: BMA3_ONEBOT_MISSING_TOKEN
                          enabled: true
                    """) + """
                    qqofficial:
                      accounts:
                        - accountId: official-new
                          appId: "synthetic-app-id-new"
                          appSecretEnv: BMA3_OFFICIAL_NEW_SECRET
                          enabled: true
                    """;
            writeConfig(invalidReload);

            JsonNode configProbe = awaitWatchdogCode(app, "config", "RELOAD_REJECTED");
            assertThat(configProbe.path("health").asText()).isEqualTo("DEGRADED");

            McpSchema.CallToolResult view = mcp.callTool(new McpSchema.CallToolRequest(
                    "get_config", Map.of()));
            assertThat(view.isError()).isFalse();
            JsonNode body = JSON.readTree(text(view));
            assertThat(body.path("qqconnect").path("accounts")).isEmpty();
            assertThat(body.path("weighting").path("immediateThreshold").asInt()).isZero();
            assertThat(app.officialService().registry().desiredConfigs()).isEmpty();
            assertThat(factory.createCount()).isZero();
        }
    }

    @Test
    void officialDirectoryFailureReleasesAllEarlierResourcesAndInstanceLock() throws Exception {
        writeConfig(baseConfig());
        AppConfig config = ConfigLoader.load(root, Map.of());
        Path dataDir = root.resolve("data");
        Files.createDirectories(dataDir);
        Path blockedDirectoryDb = dataDir.resolve("official-directory.db");
        Files.createDirectory(blockedDirectoryDb);
        FakeFactory factory = new FakeFactory();

        assertThatThrownBy(() -> Bma3App.start(config, Map.of(), root, factory))
                .isInstanceOf(RuntimeException.class);

        Files.delete(blockedDirectoryDb);
        try (Bma3App app = Bma3App.start(config, Map.of(), root, factory)) {
            assertThat(app.officialService()).isNotNull();
            assertThat(factory.createCount()).isZero();
        }
    }

    @Test
    void replySequenceStoreFailureReleasesEarlierResourcesAndInstanceLock() throws Exception {
        writeConfig(baseConfig());
        AppConfig config = ConfigLoader.load(root, Map.of());
        Path dataDir = root.resolve("data");
        Files.createDirectories(dataDir);
        Path blockedDb = dataDir.resolve("official-reply-sequence.db");
        Files.createDirectory(blockedDb);
        FakeFactory factory = new FakeFactory();

        assertThatThrownBy(() -> Bma3App.start(config, Map.of(), root, factory))
                .isInstanceOf(RuntimeException.class);

        Files.delete(blockedDb);
        try (Bma3App app = Bma3App.start(config, Map.of(), root, factory)) {
            assertThat(app.officialService().replySequenceStore()).isNotNull();
            assertThat(factory.createCount()).isZero();
        }
    }

    @Test
    void eventReferenceStoreFailureReleasesEarlierResourcesAndInstanceLock() throws Exception {
        writeConfig(baseConfig());
        AppConfig config = ConfigLoader.load(root, Map.of());
        Path dataDir = root.resolve("data");
        Files.createDirectories(dataDir);
        Path blockedDb = dataDir.resolve("official-event-reference.db");
        Files.createDirectory(blockedDb);
        FakeFactory factory = new FakeFactory();

        assertThatThrownBy(() -> Bma3App.start(config, Map.of(), root, factory))
                .isInstanceOf(RuntimeException.class);

        Files.delete(blockedDb);
        try (Bma3App app = Bma3App.start(config, Map.of(), root, factory)) {
            assertThat(app.officialService().eventReferenceStore().count()).isZero();
            assertThat(factory.createCount()).isZero();
        }
    }

    @Test
    void startupFailureAfterOfficialStartClosesFakeClients() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            writeConfig(baseConfig().replace("  port: 0", "  port: " + occupied.getLocalPort())
                    + """
                    qqofficial:
                      accounts:
                        - accountId: official-a
                          appId: "synthetic-app-id-a"
                          appSecretEnv: BMA3_OFFICIAL_A_SECRET
                          enabled: true
                    """);
            Map<String, String> env = Map.of(
                    "BMA3_OFFICIAL_A_SECRET", "synthetic-env-secret-a");
            AppConfig config = ConfigLoader.load(root, env);
            FakeFactory factory = new FakeFactory();

            assertThatThrownBy(() -> Bma3App.start(config, env, root, factory))
                    .isInstanceOf(RuntimeException.class);
            assertThat(factory.createCount()).isEqualTo(1);
            assertThat(factory.client(OFFICIAL_A).closed()).isTrue();
        }
    }

    private String configWithSend(int maxTextChars, String overflow) {
        return baseConfig().replace("""
                qqconnect:
                  accounts: []
                """, """
                qqconnect:
                  accounts: []
                  send:
                    maxTextChars: %d
                    overflow: %s
                """.formatted(maxTextChars, overflow)) + OFFICIAL_A_CONFIG;
    }

    private void awaitSendMaxChars(McpSyncClient mcp, int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            McpSchema.CallToolResult view = mcp.callTool(new McpSchema.CallToolRequest(
                    "get_config", Map.of()));
            if (!view.isError()) {
                JsonNode send = JSON.readTree(text(view)).path("qqconnect").path("send");
                if (send.path("maxTextChars").asInt(-1) == expected) {
                    return;
                }
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("qqconnect.send.maxTextChars did not reload to " + expected);
    }

    private String baseConfig() {
        return """
                dataDir: '%s'
                server:
                  host: 127.0.0.1
                  port: 0
                  healthPort: 0
                inbox:
                  httpPort: 0
                qqconnect:
                  accounts: []
                xvfb:
                  enabled: false
                debug:
                  enabled: false
                schedule:
                  enabled: false
                watchdog:
                  enabled: true
                  intervalSeconds: 1
                  failureThreshold: 1
                weighting:
                  immediateThreshold: 0
                  dropBelow: -300
                  normalDelaySeconds: 1
                  importantDelaySeconds: 1
                  sourcePriority:
                    "*": 0
                """.formatted(root.resolve("data").toAbsolutePath().toString()
                .replace("\\", "/").replace("'", "''"));
    }

    private void writeConfig(String yaml) throws Exception {
        Path file = root.resolve("config/app.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
    }

    private McpSyncClient openMcp(Bma3App app) throws Exception {
        String token = Bma3Env.read(root.resolve(".bma3env")).get(Bma3Env.MCP_TOKEN);
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + app.mcpPort())
                .endpoint("/mcp")
                .resumableStreams(false)
                .connectTimeout(Duration.ofSeconds(5))
                .httpRequestCustomizer((request, method, uri, body, context) ->
                        request.header("Authorization", "Bearer " + token))
                .build();
        McpSyncClient client = McpClient.sync(transport).build();
        client.initialize();
        return client;
    }

    private JsonNode awaitWatchdogComponent(Bma3App app, String component) throws Exception {
        String token = Bma3Env.read(root.resolve(".bma3env")).get(Bma3Env.MCP_TOKEN);
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + app.healthPort() + "/watchdog"))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        HttpClient client = HttpClient.newHttpClient();
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        JsonNode latest = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                latest = JSON.readTree(response.body());
                JsonNode found = latest.path("components").path(component);
                if (!found.isMissingNode()) {
                    return found;
                }
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("watchdog component not found: " + component + " latest=" + latest);
    }

    private JsonNode awaitWatchdogCode(Bma3App app, String component, String code)
            throws Exception {
        String token = Bma3Env.read(root.resolve(".bma3env")).get(Bma3Env.MCP_TOKEN);
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + app.healthPort() + "/watchdog"))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        HttpClient client = HttpClient.newHttpClient();
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        JsonNode latest = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                latest = JSON.readTree(response.body());
                JsonNode found = latest.path("components").path(component);
                if (!found.isMissingNode() && code.equals(found.path("code").asText())) {
                    return found;
                }
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("watchdog code not reached: " + component + "/" + code
                + " latest=" + latest);
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("condition not met within " + timeout);
    }

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
    }

    private static final class FakeFactory implements OfficialQqAccountClientFactory {
        private final Map<String, OfficialQqAccountClientContext> contexts =
                new ConcurrentHashMap<>();
        private final Map<String, FakeClient> clients = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> creates = new ConcurrentHashMap<>();
        private Function<OfficialQqAccountClientContext, Optional<OfficialQqApiClient>> apiFactory =
                context -> Optional.empty();

        FakeFactory apiFactory(
                Function<OfficialQqAccountClientContext, Optional<OfficialQqApiClient>> value) {
            this.apiFactory = value;
            return this;
        }

        @Override
        public OfficialQqAccountClient create(OfficialQqAccountClientContext context) {
            String accountId = context.config().accountId();
            contexts.put(accountId, context);
            creates.computeIfAbsent(accountId, ignored -> new AtomicInteger())
                    .incrementAndGet();
            FakeClient client = new FakeClient(context,
                    apiFactory.apply(context).orElse(null));
            clients.put(accountId, client);
            return client;
        }

        OfficialQqAccountClientContext context(String accountId) {
            return contexts.get(accountId);
        }

        FakeClient client(String accountId) {
            return clients.get(accountId);
        }

        int createCount() {
            return clients.size();
        }

        int createCount(String accountId) {
            AtomicInteger value = creates.get(accountId);
            return value == null ? 0 : value.get();
        }
    }

    private static final class FakeClient implements OfficialQqAccountClient {
        private final String accountId;
        private final OfficialQqApiClient api;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile OfficialQqAccountRuntimeStatus status = new OfficialQqAccountRuntimeStatus(
                OfficialQqAccountState.CONNECTING, false, false, Instant.EPOCH,
                null, null, null, 0, OfficialQqAccountErrorKind.NONE, "", "", "");

        private FakeClient(OfficialQqAccountClientContext context, OfficialQqApiClient api) {
            this.accountId = context.config().accountId();
            this.api = api;
        }

        @Override
        public String accountId() {
            return accountId;
        }

        @Override
        public void start() {
            started.set(true);
            status = new OfficialQqAccountRuntimeStatus(
                    OfficialQqAccountState.READY, true, false, Instant.now(),
                    Instant.now(), null, null, 0, OfficialQqAccountErrorKind.NONE, "", "", "");
        }

        @Override
        public OfficialQqAccountRuntimeStatus status() {
            return status;
        }

        @Override
        public Optional<OfficialQqApiClient> apiClient() {
            return closed.get() ? Optional.empty() : Optional.ofNullable(api);
        }

        @Override
        public void close() {
            closed.set(true);
        }

        boolean closed() {
            return closed.get();
        }

        boolean started() {
            return started.get();
        }
    }

    private static OfficialQqApiClient loopbackApiClient(
            OfficialQqAccountClientContext context, URI baseUri) {
        HttpClient http = HttpClient.newHttpClient();
        Duration requestTimeout = context.config().runtimeOptions().httpRequestTimeout();
        OfficialQqTokenManager tokens = OfficialQqTokenManager.forLoopbackTesting(
                context.config().accountId(), context.credential(), baseUri, http, JSON,
                Clock.systemUTC(), requestTimeout);
        return OfficialQqApiClient.forLoopbackTesting(
                context.config().accountId(), tokens, baseUri, http, JSON, requestTimeout);
    }

    private static final class LoopbackApiServer implements AutoCloseable {
        private static final String SHARE_URL = "https://bot.q.qq.com/s/Phase1_AppIntegration";
        private final HttpServer server;
        private final ExecutorService executor;
        private final AtomicInteger linkCalls = new AtomicInteger();
        private final AtomicInteger sendCalls = new AtomicInteger();
        private final AtomicReference<String> boardStatusAtDispatch = new AtomicReference<>("");
        private volatile Path boardPath;
        private volatile SourceAddress boardSource;
        private volatile long sendDelayMillis;

        LoopbackApiServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool();
            server.setExecutor(executor);
            server.createContext("/app/getAppAccessToken", exchange -> respond(exchange, 200,
                    "{\"access_token\":\"loopback-token\",\"expires_in\":\"7200\"}"));
            server.createContext("/v2/generate_url_link", exchange -> {
                linkCalls.incrementAndGet();
                respond(exchange, 200,
                        "{\"data\":{\"url\":\"" + SHARE_URL + "\"}}");
            });
            server.createContext("/v2/users", this::handleSend);
            server.createContext("/v2/groups", this::handleSend);
            server.start();
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        String shareUrl() {
            return SHARE_URL;
        }

        int linkCalls() {
            return linkCalls.get();
        }

        int sendCalls() {
            return sendCalls.get();
        }

        String boardStatusAtDispatch() {
            return boardStatusAtDispatch.get();
        }

        void observeBoard(Path boardPath, SourceAddress source) {
            this.boardPath = boardPath;
            this.boardSource = source;
        }

        void delaySend(long millis) {
            this.sendDelayMillis = millis;
        }

        private void handleSend(HttpExchange exchange) throws IOException {
            int call = sendCalls.incrementAndGet();
            captureBoardStatus();
            if (sendDelayMillis > 0L) {
                try {
                    Thread.sleep(sendDelayMillis);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
            respond(exchange, 200,
                    "{\"data\":{\"message_id\":\"external-msg-" + call + "\"}}");
        }

        private void captureBoardStatus() {
            if (boardPath == null || boardSource == null) {
                return;
            }
            try (SqliteMessageBoardStore probe = new SqliteMessageBoardStore(boardPath)) {
                String status = probe.read(boardSource, 0L, 100).stream()
                        .filter(row -> row.inbound().direction() == Direction.OUTBOUND)
                        .reduce((left, right) -> right)
                        .map(row -> row.delivery() == null ? "NONE"
                                : row.delivery().status().name())
                        .orElse("NONE");
                boardStatusAtDispatch.set(status);
            } catch (RuntimeException failure) {
                boardStatusAtDispatch.set("PROBE_ERROR");
            }
        }

        private static void respond(HttpExchange exchange, int status, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
