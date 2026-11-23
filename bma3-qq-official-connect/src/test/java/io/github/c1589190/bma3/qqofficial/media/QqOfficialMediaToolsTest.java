package io.github.c1589190.bma3.qqofficial.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.agentmosire.agentlib.permission.AccessToken;
import io.github.c1589190.agentmosire.agentlib.permission.AgentIdentity;
import io.github.c1589190.agentmosire.agentlib.permission.AgentPermissionSet;
import io.github.c1589190.agentmosire.agentlib.tool.ToolContext;
import io.github.c1589190.agentmosire.agentlib.tool.ToolResult;
import io.github.c1589190.bma3.core.source.SourceAddress;
import io.github.c1589190.bma3.core.store.SqliteMessageBoardStore;
import io.github.c1589190.bma3.qqofficial.send.OfficialQqReplySequenceStore;
import io.github.c1589190.bma3.qqofficial.send.OfficialQqTransportException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QqOfficialMediaToolsTest {

    private static final SourceAddress SOURCE =
            SourceAddress.parse("qq_official_group/acc-a/group-a");
    private static final Instant T0 = Instant.parse("2026-11-17T08:00:00Z");

    @TempDir
    Path temp;

    @Test
    void factoriesExposeFixedNamesAndSafeSchemasWithoutRegistration() throws Exception {
        try (Fixture fixture = new Fixture()) {
            QqOfficialSendMediaTool image =
                    (QqOfficialSendMediaTool) QqOfficialMediaTools.sendImage(fixture.service);
            QqOfficialSendMediaTool file =
                    (QqOfficialSendMediaTool) QqOfficialMediaTools.sendFile(fixture.service);

            assertThat(image.name()).isEqualTo("qq_official_send_image");
            assertThat(file.name()).isEqualTo("qq_official_send_file");
            assertThat(image.jsonSchema().get("required"))
                    .isEqualTo(List.of("accountId", "source", "path"));
            assertThat(image.jsonSchema().get("additionalProperties")).isEqualTo(false);
            assertThat(image.description()).contains("5MiB").contains("尚未实现");
        }
    }

    @Test
    void toolResultContainsNoPathFileInfoBase64OrFileName() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path file = fixture.dataDir.resolve("private-name.png");
            byte[] bytes = "mcp-safe-image".getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            QqOfficialSendMediaTool tool =
                    (QqOfficialSendMediaTool) QqOfficialMediaTools.sendImage(fixture.service);

            ToolResult result = tool.execute(call(Map.of(
                    "accountId", "acc-a",
                    "source", SOURCE.format(),
                    "path", file.toString())));

            assertThat(result.success()).isTrue();
            JsonNode json = new ObjectMapper().readTree(result.message());
            assertThat(json.path("state").asText()).isEqualTo("SENT");
            assertThat(json.path("messageId").asText()).isEqualTo("ROBOT1.0_MEDIA");
            assertThat(json.path("uploadState").asText()).isEqualTo("UPLOADED");
            assertThat(json.path("resendAllowed").asBoolean()).isFalse();
            assertThat(result.message())
                    .doesNotContain(file.toString())
                    .doesNotContain("private-name.png")
                    .doesNotContain("opaque-file-info")
                    .doesNotContain(base64);
        }
    }

    @Test
    void malformedSourceAndWrongAccountFailWithoutEchoingValues() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path file = fixture.dataDir.resolve("x.bin");
            Files.writeString(file, "x");
            QqOfficialSendMediaTool tool =
                    (QqOfficialSendMediaTool) QqOfficialMediaTools.sendFile(fixture.service);

            ToolResult badSource = tool.execute(call(Map.of(
                    "accountId", "acc-a",
                    "source", "qq_official_group//SECRET",
                    "path", file.toString())));
            assertThat(badSource.success()).isFalse();
            assertThat(badSource.code()).isEqualTo("INVALID_ARGUMENT");
            assertThat(badSource.message()).doesNotContain("SECRET");

            ToolResult wrongAccount = tool.execute(call(Map.of(
                    "accountId", "acc-b",
                    "source", SOURCE.format(),
                    "path", file.toString())));
            assertThat(wrongAccount.success()).isFalse();
            assertThat(wrongAccount.code()).isEqualTo("SOURCE_ACCOUNT_MISMATCH");
            assertThat(fixture.transport.uploadCalls).isZero();
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

        private final Path dataDir;
        private final SqliteMessageBoardStore board;
        private final OfficialQqReplySequenceStore sequences;
        private final FakeTransport transport = new FakeTransport();
        private final OfficialQqMediaService service;

        private Fixture() throws Exception {
            dataDir = Files.createDirectory(temp.resolve("data"));
            board = new SqliteMessageBoardStore(temp.resolve("board.db"));
            sequences = new OfficialQqReplySequenceStore(temp.resolve("seq.db"),
                    Clock.fixed(T0, ZoneOffset.UTC));
            OfficialQqMediaAccounts accounts = accountId ->
                    "acc-a".equals(accountId)
                            ? Optional.of(new OfficialQqMediaAccount("acc-a", true, transport))
                            : Optional.empty();
            service = new OfficialQqMediaService(
                    board,
                    accounts,
                    new OfficialQqLocalMediaPolicy(dataDir),
                    sequences,
                    null,
                    Clock.fixed(T0, ZoneOffset.UTC));
        }

        @Override
        public void close() {
            sequences.close();
            board.close();
        }
    }

    private static final class FakeTransport implements OfficialQqMediaTransportPort {

        private int uploadCalls;

        @Override
        public String accountId() {
            return "acc-a";
        }

        @Override
        public OfficialQqMediaUpload uploadGroup(String groupOpenid, int fileType,
                                                 String fileName, String base64Data) {
            uploadCalls++;
            return new OfficialQqMediaUpload("acc-a", OfficialQqMediaUpload.Scene.GROUP,
                    groupOpenid, "opaque-file-info", 300);
        }

        @Override
        public OfficialQqMediaUpload uploadUser(String userOpenid, int fileType,
                                                String fileName, String base64Data) {
            uploadCalls++;
            return new OfficialQqMediaUpload("acc-a", OfficialQqMediaUpload.Scene.PRIVATE,
                    userOpenid, "opaque-file-info", 300);
        }

        @Override
        public String sendGroupMedia(String groupOpenid, OfficialQqMediaMessage message)
                throws OfficialQqTransportException {
            return "ROBOT1.0_MEDIA";
        }

        @Override
        public String sendUserMedia(String userOpenid, OfficialQqMediaMessage message)
                throws OfficialQqTransportException {
            return "ROBOT1.0_MEDIA";
        }
    }
}
