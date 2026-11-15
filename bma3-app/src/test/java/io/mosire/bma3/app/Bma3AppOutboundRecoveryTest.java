package io.mosire.bma3.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.bma3.app.config.AppConfig;
import io.mosire.bma3.core.message.Delivery;
import io.mosire.bma3.core.message.DeliveryStatus;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Message;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locked application startup converges leftover {@code PENDING} outbound rows to {@code UNKNOWN}
 * before the MCP surface accepts requests. A plain store open must not do that, and a startup that
 * cannot take the data-directory lock must not touch the board at all.
 */
@Timeout(30)
class Bma3AppOutboundRecoveryTest {

    private static final SourceAddress SOURCE = SourceAddress.parse("qq_private/bot-1/20002");

    @TempDir
    java.nio.file.Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void lockedStartupRecoversPendingRowsBeforeServingAndLeavesTerminalRowsAlone()
            throws Exception {
        Path database = tempDir.resolve("bot.db");
        long inboundSeq;
        long pendingSeq;
        long sentSeq;
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(database)) {
            inboundSeq = board.append(inbound("in-1")).message().localSeq();
            pendingSeq = board.beginOutbound(outbound("out-pending"), "op-start-1", 2, 3)
                    .message().localSeq();
            sentSeq = board.beginOutbound(outbound("out-sent"), "op-start-2", 1, 1)
                    .message().localSeq();
            board.finishOutbound(sentSeq, DeliveryStatus.SENT, "777", null);
        }

        // Merely opening the store (as any read-only tool would) must not recover anything.
        try (SqliteMessageBoardStore reopened = new SqliteMessageBoardStore(database)) {
            assertThat(row(reopened, pendingSeq).delivery().status())
                    .isEqualTo(DeliveryStatus.PENDING);
        }

        Bma3App app = null;
        McpSyncClient mcp = null;
        try {
            app = Bma3App.start(config(), Map.of(), tempDir);
            String token = Bma3Env.read(tempDir.resolve(".bma3env")).get(Bma3Env.MCP_TOKEN);
            mcp = mcp(app, token);

            JsonNode pending = call(mcp, "qq_message_get",
                    Map.of("source", SOURCE.format(), "seq", pendingSeq));
            assertThat(pending.path("delivery").path("status").asText())
                    .as("遗留 PENDING 必须在服务接入请求前收敛为 UNKNOWN")
                    .isEqualTo("UNKNOWN");
            assertThat(pending.path("delivery").path("errorCode").asText())
                    .isEqualTo("PROCESS_INTERRUPTED");
            assertThat(pending.path("delivery").path("operationId").asText())
                    .isEqualTo("op-start-1");
            assertThat(pending.path("delivery").path("partIndex").asInt()).isEqualTo(2);
            assertThat(pending.path("delivery").path("partTotal").asInt()).isEqualTo(3);

            JsonNode sent = call(mcp, "qq_message_get",
                    Map.of("source", SOURCE.format(), "seq", sentSeq));
            assertThat(sent.path("delivery").path("status").asText()).isEqualTo("SENT");
            assertThat(sent.path("externalMessageId").asText()).isEqualTo("777");

            JsonNode inbound = call(mcp, "qq_message_get",
                    Map.of("source", SOURCE.format(), "seq", inboundSeq));
            assertThat(inbound.path("direction").asText()).isEqualTo("IN");
            assertThat(inbound.has("delivery")).isFalse();
        } finally {
            if (mcp != null) {
                mcp.closeGracefully();
            }
            if (app != null) {
                app.close();
            }
        }
    }

    @Test
    void startupWithoutTheDataDirectoryLockLeavesPendingRowsUntouched() throws Exception {
        Path database = tempDir.resolve("bot.db");
        long pendingSeq;
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(database)) {
            pendingSeq = board.beginOutbound(outbound("out-pending"), "op-locked", 1, 2)
                    .message().localSeq();
        }

        try (SingleInstanceLock ignored = SingleInstanceLock.acquire(tempDir)) {
            assertThatThrownBy(() -> Bma3App.start(config(), Map.of(), tempDir))
                    .isInstanceOf(IllegalStateException.class);
        }

        try (SqliteMessageBoardStore reopened = new SqliteMessageBoardStore(database)) {
            assertThat(row(reopened, pendingSeq).delivery().status())
                    .as("拿不到数据目录锁时不得恢复任何行")
                    .isEqualTo(DeliveryStatus.PENDING);
        }
    }

    @Test
    void recoveryMustCompleteBeforeAnyServiceAcceptsRequests() throws Exception {
        Path database = tempDir.resolve("bot.db");
        long pendingSeq;
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(database)) {
            pendingSeq = board.beginOutbound(outbound("out-order"), "op-order-1", 1, 3)
                    .message().localSeq();
        }

        // Slow down only the recovery UPDATE: the trigger fires on PENDING -> UNKNOWN and nowhere
        // else, so the observation window is caused by recovery itself, not by the store opening.
        // The marker rows also prove that this exact UPDATE executed (causality, not a timing label).
        executeSql(database,
                "CREATE TABLE recovery_marker (x INTEGER PRIMARY KEY, padding BLOB)",
                "CREATE TABLE slow_seed (x INTEGER PRIMARY KEY)",
                "WITH RECURSIVE cnt(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM cnt "
                        + "WHERE x < 300000) INSERT INTO slow_seed(x) SELECT x FROM cnt",
                "CREATE TRIGGER slow_recovery_update "
                        + "AFTER UPDATE OF delivery_status ON chat_message "
                        + "WHEN OLD.delivery_status = 'PENDING' AND "
                        + "NEW.delivery_status = 'UNKNOWN' "
                        + "BEGIN INSERT INTO recovery_marker(x, padding) "
                        + "SELECT x, randomblob(64) FROM slow_seed; END");

        int mcpPort = freePort();
        int inboxPort = freePort();
        int healthPort = freePort();
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            long start = System.nanoTime();
            CompletableFuture<Bma3App> starting = CompletableFuture.supplyAsync(
                    () -> Bma3App.start(config(mcpPort, inboxPort, healthPort), Map.of(), tempDir));
            Bma3App app = null;
            try {
                String firstServiceStatus = null;
                int firstServicePort = -1;
                long firstServiceMs = -1L;
                long deadline = start + Duration.ofSeconds(15).toNanos();
                while (System.nanoTime() < deadline) {
                    firstServicePort = firstReachable(mcpPort, inboxPort, healthPort);
                    if (firstServicePort > 0) {
                        firstServiceStatus = readDeliveryStatus(reader, pendingSeq);
                        firstServiceMs = (System.nanoTime() - start) / 1_000_000L;
                        break;
                    }
                    if (starting.isDone()) {
                        break;
                    }
                    Thread.sleep(10L);
                }
                app = starting.get(20L, TimeUnit.SECONDS);
                String finalStatus = readDeliveryStatus(reader, pendingSeq);
                long markers = countMarkers(reader);
                System.out.printf("I1 order probe: firstServicePort=%d firstServiceStatus=%s "
                                + "firstServiceMs=%d finalStatus=%s markers=%d totalMs=%d%n",
                        firstServicePort, firstServiceStatus, firstServiceMs, finalStatus, markers,
                        (System.nanoTime() - start) / 1_000_000L);

                assertThat(firstServicePort)
                        .as("Inbox HTTP、MCP 或 health 任一服务应先于/同时被观察")
                        .isPositive();
                assertThat(firstServiceStatus)
                        .as("任一服务首次可达时恢复必须已提交；marker>0 证明慢窗口来自恢复 UPDATE")
                        .isEqualTo("UNKNOWN");
                assertThat(firstServiceMs).isGreaterThanOrEqualTo(0L);
                assertThat(markers).isGreaterThan(0L);
                assertThat(finalStatus).isEqualTo("UNKNOWN");
                // The same observation through the real read surface after startup.
                String token = Bma3Env.read(tempDir.resolve(".bma3env")).get(Bma3Env.MCP_TOKEN);
                McpSyncClient mcp = mcp(app, token);
                try {
                    JsonNode pending = call(mcp, "qq_message_get",
                            Map.of("source", SOURCE.format(), "seq", pendingSeq));
                    assertThat(pending.path("delivery").path("status").asText())
                            .isEqualTo("UNKNOWN");
                } finally {
                    mcp.closeGracefully();
                }
            } finally {
                if (app != null) {
                    app.close();
                } else {
                    starting.cancel(true);
                }
            }
        }
    }

    @Test
    void recoveryUpdateFailureStopsStartupAndReleasesLock() throws Exception {
        Path database = tempDir.resolve("bot.db");
        long pendingSeq;
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(database)) {
            pendingSeq = board.beginOutbound(outbound("out-fail"), "op-fail", 1, 2)
                    .message().localSeq();
        }
        // A real SQLite trigger aborts the recovery UPDATE only; opening/migrating the board is
        // unaffected because the legacy backfill writes LEGACY_UNKNOWN, not UNKNOWN.
        executeSql(database,
                "CREATE TRIGGER fail_recovery_update "
                        + "BEFORE UPDATE OF delivery_status ON chat_message "
                        + "WHEN NEW.delivery_status = 'UNKNOWN' "
                        + "BEGIN SELECT RAISE(ABORT, 'simulated recovery update failure'); END");

        assertThatThrownBy(() -> Bma3App.start(config(), Map.of(), tempDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("恢复未决出站状态失败");

        // The failed startup must have released the data-directory lock.
        try (SingleInstanceLock reacquired = SingleInstanceLock.acquire(tempDir)) {
            assertThat(reacquired.file()).isEqualTo(tempDir.toAbsolutePath().normalize()
                    .resolve(SingleInstanceLock.LOCK_FILE));
        }

        // The aborted UPDATE leaves the row exactly as it was; reopening must not recover it.
        try (SqliteMessageBoardStore reopened = new SqliteMessageBoardStore(database)) {
            assertThat(row(reopened, pendingSeq).delivery())
                    .isEqualTo(new Delivery("op-fail", 1, 2, DeliveryStatus.PENDING, null));
        }
    }

    private AppConfig config() {
        return config(0, 0, 0);
    }

    private AppConfig config(int mcpPort, int inboxPort, int healthPort) {
        return new AppConfig(
                tempDir,
                new AppConfig.Server("127.0.0.1", mcpPort, "/mcp", "BMA3_TEST_TOKEN", healthPort),
                new AppConfig.QqConnect(List.of()),
                new AppConfig.Inbox("bma3", inboxPort),
                new AppConfig.Logging("INFO", tempDir.resolve("logs").toString()));
    }

    private McpSyncClient mcp(Bma3App app, String token) {
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

    private JsonNode call(McpSyncClient mcp, String tool, Map<String, Object> arguments)
            throws Exception {
        McpSchema.CallToolResult result = mcp.callTool(
                new McpSchema.CallToolRequest(tool, arguments));
        assertThat(result.isError()).isFalse();
        String text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
        return mapper.readTree(text);
    }

    private static void executeSql(Path database, String... statements) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static String readDeliveryStatus(Connection connection, long localSeq)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT delivery_status FROM chat_message WHERE local_seq = ?")) {
            statement.setLong(1, localSeq);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AssertionError("消息板缺少 seq=" + localSeq);
                }
                return result.getString(1);
            }
        }
    }

    private static long countMarkers(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM recovery_marker")) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    private static int firstReachable(int... ports) {
        for (int port : ports) {
            if (canConnect(port)) {
                return port;
            }
        }
        return -1;
    }

    private static boolean canConnect(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
            return true;
        } catch (IOException refusedOrTimedOut) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        // The app binds this explicit port so the test can probe it before start() returns.
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static Message row(SqliteMessageBoardStore store, long localSeq) {
        return store.read(SOURCE, 0L, 100).stream()
                .filter(message -> message.localSeq() == localSeq)
                .findFirst()
                .orElseThrow(() -> new AssertionError("消息板缺少 seq=" + localSeq));
    }

    private static InboundMessage inbound(String messageId) {
        return new InboundMessage(SOURCE, messageId, Instant.parse("2026-11-15T09:00:00Z"),
                List.of(new Segment("text", "入站正文")), Direction.INBOUND, "20002", "Alice");
    }

    private static InboundMessage outbound(String messageId) {
        return new InboundMessage(SOURCE, messageId, Instant.parse("2026-11-15T09:00:00Z"),
                List.of(new Segment("text", "出站正文")), Direction.OUTBOUND, "bot-1", "机器人");
    }
}
