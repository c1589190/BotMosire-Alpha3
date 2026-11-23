package io.github.c1589190.bma3.app.onebot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.bma3.app.Bma3App;
import io.github.c1589190.bma3.app.Bma3Env;
import io.github.c1589190.bma3.app.config.ConfigLoader;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@Timeout(100)
class OneBotCredentialsIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIRST = "synthetic-onebot-first";
    private static final String SECOND = "synthetic-onebot-second";
    private static final String IMPORTED = "synthetic-onebot-windows-import";

    @TempDir
    Path root;

    @Test
    void credentialLifecycleThroughMcpHotReconnectsOnlyTheBoundAccountAndPersists() throws Exception {
        try (OneBotServer primary = new OneBotServer(); OneBotServer unrelated = new OneBotServer()) {
            configure("""
                    qqconnect:
                      accounts:
                        - accountId: primary
                          endpoint: ws://127.0.0.1:%d
                          tokenRef: snowluma
                        - accountId: unrelated
                          endpoint: ws://127.0.0.1:%d
                        - accountId: legacy-missing
                          endpoint: ws://127.0.0.1:1
                          tokenEnv: TEST_MISSING_TOKEN
                    """.formatted(primary.getPort(), unrelated.getPort()));
            Map<String, String> env = Map.of();
            try (Bma3App app = start(env); McpSyncClient mcp = mcp(app)) {
                assertThat(unrelated.nextAuthorization()).isEmpty();
                assertThat(account(mcp, "primary").path("lastError").path("hint").asText())
                        .contains("CREDENTIAL_MISSING");
                assertThat(account(mcp, "legacy-missing").path("lastError").path("hint").asText())
                        .contains("TOKEN_ENV_MISSING");
                assertThat(ok(mcp, "inbox_status", Map.of()).isObject()).isTrue();
                assertThat(credential(mcp, "list", Map.of()).path("credentials")).isEmpty();
                for (var invalid : List.of(Map.of("tokenRef", "snowluma", "tokenEnv", "OTHER_TOKEN"),
                        Map.of("token", FIRST))) {
                    var invalidAccount = new java.util.HashMap<>(invalid);
                    invalidAccount.put("accountId", "primary");
                    invalidAccount.put("endpoint", "ws://127.0.0.1:" + primary.getPort());
                    var rejection = call(mcp, "set_config",
                            Map.of("patch", Map.of("qqconnect.accounts", List.of(invalidAccount))));
                    assertThat(rejection.isError()).isTrue();
                    assertThat(text(rejection)).contains("CONFIG_INVALID").doesNotContain(FIRST);
                }
                if (!WindowsDpapi.available()) {
                    var unsupported = call(mcp, "bma3_credentials",
                            Map.of("action", "import_windows_onebot", "name", "snowluma"));
                    assertThat(unsupported.isError()).isTrue();
                    assertThat(text(unsupported)).contains("PLATFORM_UNSUPPORTED");
                }

                assertThat(credential(mcp, "set", Map.of("name", "snowluma", "value", FIRST))
                        .path("present").asBoolean()).isTrue();
                assertThat(primary.nextAuthorization()).isEqualTo("Bearer " + FIRST);
                assertThat(account(mcp, "primary").path("connected").asBoolean()).isTrue();
                assertThat(credential(mcp, "set", Map.of("name", "snowluma", "value", SECOND))
                        .path("changed").asBoolean()).isTrue();
                assertThat(primary.nextAuthorization()).isEqualTo("Bearer " + SECOND);
                assertThat(unrelated.pollAuthorization()).isNull();

                assertThat(credential(mcp, "remove", Map.of("name", "snowluma"))
                        .path("present").asBoolean()).isFalse();
                JsonNode blocked = account(mcp, "primary");
                assertThat(blocked.path("connected").asBoolean()).isFalse();
                assertThat(blocked.path("lastError").path("hint").asText()).contains("CREDENTIAL_MISSING");
                assertThat(account(mcp, "unrelated").path("connected").asBoolean()).isTrue();

                credential(mcp, "set", Map.of("name", "snowluma", "value", FIRST));
                assertThat(primary.nextAuthorization()).isEqualTo("Bearer " + FIRST);
                credential(mcp, "set", Map.of("name", "next", "value", SECOND));
                var rebound = ok(mcp, "set_config", Map.of("patch", Map.of("qqconnect.accounts", List.of(
                        Map.of("accountId", "primary", "endpoint", "ws://127.0.0.1:" + primary.getPort(),
                                "tokenRef", "next"),
                        Map.of("accountId", "unrelated", "endpoint", "ws://127.0.0.1:" + unrelated.getPort())))));
                assertThat(rebound.path("reloaded").asBoolean()).isTrue();
                assertThat(primary.nextAuthorization()).isEqualTo("Bearer " + SECOND);
                assertThat(unrelated.pollAuthorization()).isNull();
                assertThat(Files.readString(root.resolve("config/app.yaml"))).doesNotContain(FIRST, SECOND);
                assertThat(Files.readString(root.resolve(".bma3env"))).doesNotContain(FIRST, SECOND);
                assertThat(ok(mcp, "get_config", Map.of()).toString()).doesNotContain(FIRST, SECOND);
            }

            Path stored = root.resolve("data/secrets/onebot.json");
            if (WindowsDpapi.available()) {
                assertThat(Files.readString(stored, StandardCharsets.ISO_8859_1)).doesNotContain(FIRST, SECOND);
            } else {
                assertThat(Files.getPosixFilePermissions(stored)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            }
            try (Bma3App app = start(env); McpSyncClient mcp = mcp(app)) {
                assertThat(primary.nextAuthorization()).isEqualTo("Bearer " + SECOND);
                assertThat(account(mcp, "primary").path("connected").asBoolean()).isTrue();
                assertThat(credential(mcp, "list", Map.of()).path("credentials")).hasSize(2);
            }
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void importsExistingDpapiFileAndRestoresItAfterRestart() throws Exception {
        Path localAppData = root.resolve("test-local-app-data");
        Path legacy = localAppData.resolve("BotMosire-Alpha3/onebot-token.dpapi");
        Files.createDirectories(legacy.getParent());
        byte[] ciphertext = WindowsDpapi.protect(IMPORTED.getBytes(StandardCharsets.UTF_8));
        Files.write(legacy, ciphertext);
        try (OneBotServer primary = new OneBotServer()) {
            configure("""
                    qqconnect:
                      accounts:
                        - accountId: primary
                          endpoint: ws://127.0.0.1:%d
                          tokenRef: imported
                    """.formatted(primary.getPort()));
            Map<String, String> env = Map.of("LOCALAPPDATA", localAppData.toString());
            try (Bma3App app = start(env); McpSyncClient mcp = mcp(app)) {
                JsonNode imported = credential(mcp, "import_windows_onebot", Map.of("name", "imported"));
                assertThat(imported.path("source").asText()).isEqualTo("windows_dpapi");
                assertThat(primary.nextAuthorization()).isEqualTo("Bearer " + IMPORTED);
                assertThat(account(mcp, "primary").path("connected").asBoolean()).isTrue();
            }
            assertThat(Files.readAllBytes(legacy)).isEqualTo(ciphertext);
            Files.delete(legacy);
            try (Bma3App app = start(env); McpSyncClient mcp = mcp(app)) {
                assertThat(primary.nextAuthorization()).isEqualTo("Bearer " + IMPORTED);
                assertThat(account(mcp, "primary").path("connected").asBoolean()).isTrue();
            }
        }
    }

    private void configure(String accounts) throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/app.yaml"), """
                dataDir: %s
                server:
                  host: 127.0.0.1
                  port: 0
                  healthPort: 0
                inbox:
                  httpPort: 0
                watchdog:
                  enabled: false
                logging:
                  dir: %s
                """.formatted(JSON.writeValueAsString(root.resolve("data").toString()),
                JSON.writeValueAsString(root.resolve("logs").toString())) + accounts);
    }

    private Bma3App start(Map<String, String> env) {
        return Bma3App.start(ConfigLoader.load(root, env), env, root);
    }

    private McpSyncClient mcp(Bma3App app) {
        String token = Bma3Env.read(root.resolve(".bma3env")).get(Bma3Env.MCP_TOKEN);
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + app.mcpPort())
                .endpoint("/mcp")
                .resumableStreams(false)
                .httpRequestCustomizer((request, method, uri, body, context) ->
                        request.header("Authorization", "Bearer " + token))
                .build();
        McpSyncClient mcp = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(25)).build();
        mcp.initialize();
        return mcp;
    }

    private static JsonNode account(McpSyncClient mcp, String id) throws Exception {
        return ok(mcp, "qq_account_list", Map.of()).path("accounts").valueStream()
                .filter(item -> id.equals(item.path("accountId").asText())).findFirst().orElseThrow();
    }

    private static JsonNode credential(McpSyncClient mcp, String action, Map<String, Object> args) throws Exception {
        var arguments = new java.util.HashMap<>(args);
        arguments.put("action", action);
        return ok(mcp, "bma3_credentials", arguments);
    }

    private static JsonNode ok(McpSyncClient mcp, String name, Map<String, Object> args) throws Exception {
        var result = call(mcp, name, args);
        String text = text(result);
        assertThat(result.isError()).as(text).isFalse();
        assertThat(text).doesNotContain(FIRST, SECOND, IMPORTED);
        return JSON.readTree(text);
    }

    private static McpSchema.CallToolResult call(McpSyncClient mcp, String name, Map<String, Object> args) {
        return mcp.callTool(new McpSchema.CallToolRequest(name, args));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private static final class OneBotServer extends WebSocketServer implements AutoCloseable {
        private final CountDownLatch started = new CountDownLatch(1);
        private final LinkedBlockingQueue<String> authorizations = new LinkedBlockingQueue<>();

        OneBotServer() throws Exception {
            super(new InetSocketAddress("127.0.0.1", 0));
            start();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        }

        String nextAuthorization() throws InterruptedException {
            return authorizations.poll(5, TimeUnit.SECONDS);
        }

        String pollAuthorization() {
            return authorizations.poll();
        }

        @Override
        public void onOpen(WebSocket socket, ClientHandshake handshake) {
            authorizations.add(handshake.getFieldValue("Authorization"));
        }

        @Override
        public void onClose(WebSocket socket, int code, String reason, boolean remote) {}

        @Override
        public void onMessage(WebSocket socket, String message) {
            try {
                JsonNode echo = JSON.readTree(message).path("echo");
                socket.send(JSON.writeValueAsString(Map.of("status", "ok", "retcode", 0, "echo", echo,
                        "data", Map.of("user_id", "10000", "nickname", "synthetic-bot"))));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public void onError(WebSocket socket, Exception error) {}

        @Override
        public void onStart() {
            started.countDown();
        }

        @Override
        public void close() throws InterruptedException {
            stop(2000);
        }
    }
}
