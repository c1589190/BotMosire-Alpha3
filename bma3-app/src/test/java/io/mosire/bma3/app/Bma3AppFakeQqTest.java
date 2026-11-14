package io.mosire.bma3.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.bma3.app.config.AppConfig;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class Bma3AppFakeQqTest {

    @TempDir
    java.nio.file.Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fakeOneBotMessageReachesInboxAndMcpTools() throws Exception {
        FakeOneBotServer oneBot = new FakeOneBotServer();
        oneBot.start();
        oneBot.awaitStarted(Duration.ofSeconds(3));
        Bma3App app = null;
        McpSyncClient mcp = null;
        try {
            AppConfig config = new AppConfig(
                    tempDir,
                    new AppConfig.Server("127.0.0.1", 0, "/mcp", "BMA3_TEST_TOKEN"),
                    new AppConfig.QqConnect(List.of(new AppConfig.Account(
                            "bot-1", "ws://127.0.0.1:" + oneBot.getPort(), "", true))),
                    new AppConfig.Inbox("bma3", 0),
                    new AppConfig.Logging("INFO", tempDir.resolve("logs").toString()));
            app = Bma3App.start(config, Map.of(), tempDir);
            String mcpToken = Bma3Env.read(tempDir.resolve(".bma3env")).get(Bma3Env.MCP_TOKEN);
            assertThat(app.mcpPort()).isPositive();
            assertThat(app.healthPort()).isPositive();
            assertThat(app.inboxHttpPort()).isPositive();

            oneBot.send("""
                    {"post_type":"message","message_type":"private","self_id":10001,
                     "user_id":20002,"message_id":9001,"time":1762700000,
                     "message":[{"type":"text","data":{"text":"hello-app"}}],
                     "sender":{"nickname":"Alice"}}
                    """);

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (app.inboxService().status().available() == 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(app.inboxService().status().available()).isEqualTo(1);

            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder("http://127.0.0.1:" + app.mcpPort())
                    .endpoint("/mcp")
                    .resumableStreams(false)
                    .connectTimeout(Duration.ofSeconds(5))
                    .httpRequestCustomizer((request, method, uri, body, context) ->
                            request.header("Authorization", "Bearer " + mcpToken))
                    .build();
            mcp = McpClient.sync(transport).build();
            mcp.initialize();

            McpSchema.CallToolResult read = mcp.callTool(new McpSchema.CallToolRequest(
                    "qq_conversation_read",
                    Map.of("source", "qq_private/bot-1/20002", "limit", 10)));
            assertThat(read.isError()).isFalse();
            JsonNode readJson = mapper.readTree(text(read));
            assertThat(readJson.path("messages").get(0).path("text").asText())
                    .isEqualTo("hello-app");

            McpSchema.CallToolResult items = mcp.callTool(new McpSchema.CallToolRequest(
                    "inbox_items", Map.of()));
            assertThat(items.isError()).isFalse();
            JsonNode itemList = mapper.readTree(text(items));
            assertThat(itemList.path("items")).hasSize(1);
            String itemId = itemList.path("items").get(0).path("id").asText();

            McpSchema.CallToolResult detail = mcp.callTool(new McpSchema.CallToolRequest(
                    "inbox_get", Map.of("itemId", itemId)));
            assertThat(detail.isError()).isFalse();
            assertThat(mapper.readTree(text(detail)).path("entries")).hasSize(1);

            McpSchema.CallToolResult acked = mcp.callTool(new McpSchema.CallToolRequest(
                    "inbox_ack", Map.of("itemIds", List.of(itemId))));
            assertThat(acked.isError()).isFalse();
            assertThat(mapper.readTree(text(acked)).path("remaining").asInt()).isZero();

            McpSchema.CallToolResult sent = mcp.callTool(new McpSchema.CallToolRequest(
                    "qq_conversation_send",
                    Map.of("source", "qq_private/bot-1/20002", "text", "reply")));
            assertThat(sent.isError()).isFalse();
            assertThat(mapper.readTree(text(sent)).get(0).path("state").asText()).isEqualTo("SENT");
        } finally {
            if (mcp != null) {
                mcp.closeGracefully();
            }
            if (app != null) {
                app.close();
            }
            oneBot.stop();
        }
    }

    @Test
    void groupMentionEntersTheBoundedPanelWithRelationMarkers() throws Exception {
        FakeOneBotServer oneBot = new FakeOneBotServer();
        oneBot.start();
        oneBot.awaitStarted(Duration.ofSeconds(3));
        Bma3App app = null;
        try {
            AppConfig config = new AppConfig(
                    tempDir,
                    new AppConfig.Server("127.0.0.1", 0, "/mcp", "BMA3_TEST_TOKEN"),
                    new AppConfig.QqConnect(List.of(new AppConfig.Account(
                            "bot-1", "ws://127.0.0.1:" + oneBot.getPort(), "", true))),
                    new AppConfig.Inbox("bma3", 0),
                    AppConfig.Webconnect.defaults(),
                    new AppConfig.Weighting(300, -300, 1000, 1, 1,
                            java.util.Map.of("*", 0, "qq_private", 300, "qq_group", 0),
                            java.util.Map.of("qq_at_bot", 300, "qq_at_all", 0,
                                    "qq_at_other", 0, "qq_reply_to_bot", 0,
                                    "qq_self_name", 0, "qq_command", 0),
                            java.util.List.of()),
                    new AppConfig.Logging("INFO", tempDir.resolve("logs").toString()));
            app = Bma3App.start(config, Map.of(), tempDir);
            String mcpToken = Bma3Env.read(tempDir.resolve(".bma3env")).get(Bma3Env.MCP_TOKEN);

            oneBot.send("""
                    {"post_type":"message","message_type":"group","self_id":10001,
                     "group_id":955432692,"user_id":20002,"message_id":9002,
                     "time":1762700001,
                     "message":[
                       {"type":"at","data":{"qq":"10001"}},
                       {"type":"text","data":{"text":"欢迎回来？"}},
                       {"type":"reply","data":{"id":"8000"}},
                       {"type":"image","data":{"url":"https://multimedia.nt.qq.com.cn/download?rkey=SECRET"}}
                     ],
                     "sender":{"nickname":"LvtN"}}
                    """);

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (app.inboxService().panel().items().isEmpty()
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            var panel = app.inboxService().panel();
            assertThat(panel.items()).hasSize(1);
            var item = panel.items().getFirst();
            assertThat(item.sourceLabel()).isEqualTo("QQ群 955432692");
            assertThat(item.priority()).isEqualTo(300);
            String line = item.entries().getLast().line();
            assertThat(line).contains("LvtN").contains("欢迎回来？")
                    .contains("[@10001]").contains("[引用 #8000]").contains("[图片]");
            assertThat(line).doesNotContain("multimedia.nt.qq.com.cn").doesNotContain("rkey");
        } finally {
            if (app != null) app.close();
            oneBot.stop();
        }
    }

    @Test
    void aMessageBelowDropLineIsDiscardedBeforeItIsStored() throws Exception {
        FakeOneBotServer oneBot = new FakeOneBotServer();
        oneBot.start();
        oneBot.awaitStarted(Duration.ofSeconds(3));
        Bma3App app = null;
        McpSyncClient mcp = null;
        try {
            AppConfig config = new AppConfig(
                    tempDir,
                    new AppConfig.Server("127.0.0.1", 0, "/mcp", "BMA3_TEST_TOKEN"),
                    new AppConfig.QqConnect(List.of(new AppConfig.Account(
                            "bot-1", "ws://127.0.0.1:" + oneBot.getPort(), "", true))),
                    new AppConfig.Inbox("bma3", 0),
                    AppConfig.Webconnect.defaults(),
                    new AppConfig.Weighting(300, -300, 1000, 30, 1,
                            Map.of("*", 0, "qq_group", -301, "qq_private", 300),
                            AppConfig.Weighting.defaultAttributes(),
                            java.util.List.of()),
                    new AppConfig.Logging("INFO", tempDir.resolve("logs").toString()));
            app = Bma3App.start(config, Map.of(), tempDir);
            String mcpToken = Bma3Env.read(tempDir.resolve(".bma3env")).get(Bma3Env.MCP_TOKEN);

            oneBot.send("""
                    {"post_type":"message","message_type":"group","self_id":10001,
                     "group_id":955432692,"user_id":20002,"message_id":9100,
                     "time":1762700010,
                     "message":[{"type":"text","data":{"text":"被丢弃的群消息"}}],
                     "sender":{"nickname":"Bob"}}
                    """);
            Thread.sleep(400L);

            assertThat(app.inboxService().panel().items()).isEmpty();

            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder("http://127.0.0.1:" + app.mcpPort())
                    .endpoint("/mcp")
                    .resumableStreams(false)
                    .connectTimeout(Duration.ofSeconds(5))
                    .httpRequestCustomizer((request, method, uri, body, context) ->
                            request.header("Authorization", "Bearer " + mcpToken))
                    .build();
            mcp = McpClient.sync(transport).build();
            mcp.initialize();
            McpSchema.CallToolResult threads = mcp.callTool(new McpSchema.CallToolRequest(
                    "qq_thread_list", Map.of()));
            assertThat(mapper.readTree(text(threads)).path("threads"))
                    .as("低于 dropBelow 的消息连消息板都不该进")
                    .isEmpty();
        } finally {
            if (mcp != null) {
                mcp.closeGracefully();
            }
            if (app != null) {
                app.close();
            }
            oneBot.stop();
        }
    }

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("");
    }

    private static final class FakeOneBotServer extends WebSocketServer {

        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicReference<WebSocket> client = new AtomicReference<>();

        FakeOneBotServer() {
            super(new InetSocketAddress("127.0.0.1", 0));
        }

        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) {
            client.set(connection);
        }

        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) {
            client.compareAndSet(connection, null);
        }

        @Override
        public void onMessage(WebSocket connection, String message) {
            if (message.contains("\"action\":\"send_private_msg\"")) {
                connection.send("""
                        {"status":"ok","retcode":0,"data":{"message_id":777},"echo":"%s"}
                        """.formatted(echo(message)));
            }
        }

        @Override
        public void onError(WebSocket connection, Exception exception) {
        }

        @Override
        public void onStart() {
            started.countDown();
        }

        void awaitStarted(Duration timeout) throws InterruptedException {
            assertThat(started.await(timeout.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }

        void send(String event) {
            WebSocket target = client.get();
            if (target == null) {
                throw new IllegalStateException("fake OneBot 尚未连接");
            }
            target.send(event);
        }

        private static String echo(String message) {
            int start = message.indexOf("\"echo\":\"") + "\"echo\":\"".length();
            int end = message.indexOf('"', start);
            return message.substring(start, end);
        }
    }
}
