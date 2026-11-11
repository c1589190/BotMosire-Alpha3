package io.mosire.bma3.qqconnect.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.InboundMessage;
import io.mosire.bma3.core.message.Segment;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import io.mosire.bma3.qqconnect.account.AccountConfig;
import io.mosire.bma3.qqconnect.account.AccountRegistry;
import io.mosire.bma3.qqconnect.onebot.OneBot11Client;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Media viewing: the platform link is refreshed through OneBot, the bytes are fetched with a hard
 * cap, text is inlined and everything else lands in the {@code downloads} directory. The file id is
 * the only handle the caller has — no URL ever crosses the tool boundary.
 */
class QqMediaViewToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void anImageIsRefreshedThenWrittenToTheDownloadsDirectory() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        try (FakeCdn cdn = new FakeCdn("/pic.png", "image/png", png)) {
            try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(
                    tempDir.resolve("bot.db"))) {
                SourceAddress source = SourceAddress.parse("qq_group/bot-1/955432692");
                board.append(new InboundMessage(source, "9001", Instant.now(),
                        List.of(new Segment("image", "[图片]",
                                Map.of("file", "expired.image", "url", "https://expired/x"))),
                        Direction.INBOUND, "20002", "LvtN"));

                FakeClient client = new FakeClient(mapper.readTree("""
                        {"status":"ok","retcode":0,"data":{"message":[
                          {"type":"image","data":{"file":"fresh.image","url":"%s"}}]}}
                        """.formatted(cdn.url())));
                List<AgentTool> tools = tools(board, client);

                JsonNode history = mapper.readTree(tool(tools, "qq_conversation_history")
                        .execute(call(Map.of("source", source.format(), "includeSegments", true)))
                        .message());
                String fileId = history.path("messages").get(0).path("segments").get(0)
                        .path("fileId").asText();
                assertThat(fileId).startsWith("file-");

                ToolResult viewed = tool(tools, "qq_media_view")
                        .execute(call(Map.of("fileId", fileId)));
                assertThat(viewed.success()).as(viewed.message()).isTrue();
                JsonNode body = mapper.readTree(viewed.message());
                assertThat(body.path("inline").asBoolean()).isFalse();
                assertThat(body.path("kind").asText()).isEqualTo("image");
                Path written = Path.of(body.path("path").asText());
                assertThat(written).exists();
                assertThat(Files.readAllBytes(written)).isEqualTo(png);
                assertThat(body.path("hint").asText()).contains("read_image");
                assertThat(client.lastAction).as("先问平台要新链接，而不是用过期 URL")
                        .isEqualTo("get_msg");
            }
        }
    }

    @Test
    void smallTextIsInlinedInsteadOfWrittenToDisk() throws Exception {
        byte[] text = "群文件正文".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (FakeCdn cdn = new FakeCdn("/note.txt", "text/plain; charset=utf-8", text)) {
            try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(
                    tempDir.resolve("bot.db"))) {
                SourceAddress source = SourceAddress.parse("qq_group/bot-1/955432692");
                board.append(new InboundMessage(source, "9002", Instant.now(),
                        List.of(new Segment("file", "[文件]",
                                Map.of("file", "note.txt", "url", cdn.url()))),
                        Direction.INBOUND, "20002", "LvtN"));

                List<AgentTool> tools = tools(board, new FakeClient(mapper.readTree(
                        "{\"status\":\"ok\",\"retcode\":0,\"data\":{}}")));
                String fileId = fileId(tools, source);
                ToolResult viewed = tool(tools, "qq_media_view")
                        .execute(call(Map.of("fileId", fileId)));

                assertThat(viewed.success()).as(viewed.message()).isTrue();
                JsonNode body = mapper.readTree(viewed.message());
                assertThat(body.path("inline").asBoolean()).isTrue();
                assertThat(body.path("text").asText()).isEqualTo("群文件正文");
                assertThat(body.has("path")).isFalse();
            }
        }
    }

    @Test
    void aPayloadAboveTheCapFailsLoudlyInsteadOfFillingTheDisk() throws Exception {
        byte[] big = new byte[4096];
        try (FakeCdn cdn = new FakeCdn("/big.bin", "application/octet-stream", big)) {
            try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(
                    tempDir.resolve("bot.db"))) {
                SourceAddress source = SourceAddress.parse("qq_group/bot-1/955432692");
                board.append(new InboundMessage(source, "9003", Instant.now(),
                        List.of(new Segment("file", "[文件]",
                                Map.of("file", "big.bin", "url", cdn.url()))),
                        Direction.INBOUND, "20002", "LvtN"));

                QqMediaPolicy smallCap = new QqMediaPolicy(tempDir.resolve("downloads"),
                        1024, 1024, Duration.ofSeconds(5));
                List<AgentTool> tools = tools(board, new FakeClient(mapper.readTree(
                        "{\"status\":\"ok\",\"retcode\":0,\"data\":{}}")), smallCap);
                ToolResult viewed = tool(tools, "qq_media_view")
                        .execute(call(Map.of("fileId", fileId(tools, source))));

                assertThat(viewed.success()).isFalse();
                assertThat(viewed.code()).isEqualTo("INVALID_ARGUMENT");
                assertThat(viewed.message()).contains("MEDIA_TOO_LARGE");
                assertThat(tempDir.resolve("downloads")).doesNotExist();
            }
        }
    }

    @Test
    void aBadFileIdIsRejectedWithoutTouchingTheNetwork() throws Exception {
        try (SqliteMessageBoardStore board = new SqliteMessageBoardStore(
                tempDir.resolve("bot.db"))) {
            List<AgentTool> tools = tools(board, new FakeClient(mapper.readTree("{}")));
            ToolResult viewed = tool(tools, "qq_media_view")
                    .execute(call(Map.of("fileId", "file-not-a-real-id")));
            assertThat(viewed.success()).isFalse();
            assertThat(viewed.message()).contains("INVALID_FILE_ID");
        }
    }

    private String fileId(List<AgentTool> tools, SourceAddress source) throws Exception {
        JsonNode history = mapper.readTree(tool(tools, "qq_conversation_history")
                .execute(call(Map.of("source", source.format(), "includeSegments", true)))
                .message());
        return history.path("messages").get(0).path("segments").get(0)
                .path("fileId").asText();
    }

    private List<AgentTool> tools(SqliteMessageBoardStore board, FakeClient client) {
        return tools(board, client, QqMediaPolicy.defaults(tempDir));
    }

    private List<AgentTool> tools(SqliteMessageBoardStore board, FakeClient client,
                                  QqMediaPolicy mediaPolicy) {
        AccountRegistry registry = new AccountRegistry((config, listener) -> client, event -> { });
        registry.reconcile(List.of(new AccountConfig(
                "bot-1", URI.create("ws://127.0.0.1:3001"), "token", true)));
        QqExtrasStore extras = new QqExtrasStore(tempDir.resolve("extras.db"));
        return QqTools.all(new QqToolContext(registry, board, board, tempDir, extras,
                null, () -> mediaPolicy));
    }

    private static AgentTool tool(List<AgentTool> tools, String name) {
        return tools.stream().filter(tool -> name.equals(tool.name())).findFirst().orElseThrow();
    }

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), arguments, AgentIdentity.external());
    }

    /** Minimal local CDN so the test never touches the network. */
    private static final class FakeCdn implements AutoCloseable {

        private final HttpServer server;
        private final String path;

        FakeCdn(String path, String contentType, byte[] body) throws Exception {
            this.path = path;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(path, exchange -> {
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class FakeClient extends OneBot11Client {

        private final JsonNode response;
        private String lastAction;

        FakeClient(JsonNode response) {
            super(URI.create("ws://127.0.0.1:1"), "", event -> { });
            this.response = response;
        }

        @Override
        public void start() {
        }

        @Override
        public void awaitReady(Duration timeout) {
        }

        @Override
        public CompletableFuture<JsonNode> sendAction(String action, Map<String, Object> params) {
            lastAction = action;
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public void close() {
        }
    }
}
