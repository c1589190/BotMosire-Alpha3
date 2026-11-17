package io.mosire.bma3.qqofficial.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.qqofficial.FakeQqHttpServer;
import io.mosire.bma3.qqofficial.FakeQqHttpServer.Reply;
import io.mosire.bma3.qqofficial.FakeQqHttpServer.Responder;
import io.mosire.bma3.qqofficial.api.OfficialQqApiClient;
import io.mosire.bma3.qqofficial.auth.OfficialQqTokenManager;
import io.mosire.bma3.qqofficial.credential.OfficialQqCredentialResolver;
import io.mosire.bma3.qqofficial.shareqr.OfficialQqShareQrAccountResolver;
import io.mosire.bma3.qqofficial.shareqr.OfficialQqShareQrService;
import java.awt.image.BufferedImage;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QqOfficialShareQrToolTest {

    private static final String TOKEN_PATH = "/app/getAppAccessToken";
    private static final String APP_SECRET = "synthetic-app-secret-value";
    private static final String URL_PATH = "/v2/generate_url_link";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void callsGenerateUrlLinkAndReturnsOnlyUrlPngPathAndAccountId(
            @TempDir Path dataDir) throws Exception {
        String shareUrl = "https://bot.q.qq.com/s/AbC_123-x";
        try (Fixture fixture = fixture((request, index) ->
                Reply.json("{\"data\":{\"url\":\"" + shareUrl + "\"}}"))) {
            QqOfficialShareQrTool tool = tool(dataDir, fixture.client());

            ToolResult result = tool.execute(call(Map.of("accountId", "acc-1")));

            assertThat(result.success()).isTrue();
            JsonNode output = JSON.readTree(result.message());
            assertThat(output.size()).isEqualTo(3);
            assertThat(output.path("url").asText()).isEqualTo(shareUrl);
            assertThat(output.path("accountId").asText()).isEqualTo("acc-1");
            Path pngPath = Path.of(output.path("pngPath").asText());
            assertThat(pngPath).isAbsolute();
            assertThat(pngPath.getParent())
                    .isEqualTo(dataDir.resolve("qr").toAbsolutePath().normalize());
            assertThat(Files.isRegularFile(pngPath)).isTrue();
            assertThat(Files.size(pngPath)).isGreaterThan(100L);
            assertThat(decodePng(pngPath)).isEqualTo(shareUrl);
            assertThat(result.message())
                    .doesNotContain("tok-1")
                    .doesNotContain(APP_SECRET)
                    .doesNotContain("access_token");

            FakeQqHttpServer.RecordedRequest linkRequest =
                    fixture.server().requests().stream()
                            .filter(request -> URL_PATH.equals(request.rawPath()))
                            .findFirst()
                            .orElseThrow();
            assertThat(JSON.readTree(linkRequest.body()).path("callback_data").asText())
                    .isEqualTo("bma3");
            assertThat(fixture.server().countPath(URL_PATH)).isEqualTo(1);
        }
    }

    @Test
    void requiresAnExactAccountIdAndNeverFallsBackToTheOnlyAccount(@TempDir Path dataDir)
            throws Exception {
        AtomicInteger resolverCalls = new AtomicInteger();
        OfficialQqShareQrAccountResolver resolver = accountId -> {
            resolverCalls.incrementAndGet();
            return Optional.empty();
        };
        QqOfficialShareQrTool tool =
                new QqOfficialShareQrTool(new OfficialQqShareQrService(dataDir, resolver));

        assertThat(tool.execute(call(Map.of())).code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(tool.execute(call(Map.of("accountId", " "))).code())
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(tool.execute(call(Map.of("accountId", "../acc-1"))).code())
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(resolverCalls).hasValue(0);

        assertThat(tool.execute(call(Map.of("accountId", "missing"))).code())
                .isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(resolverCalls).hasValue(1);
        assertThat(dataDir.resolve("qr")).doesNotExist();
    }

    @Test
    void permissionFailureIsClassifiedAndSanitizedWithoutResidue(@TempDir Path dataDir)
            throws Exception {
        try (Fixture fixture = fixture((request, index) -> Reply.json(
                "{\"err_code\":11253,\"trace_id\":\"trace-perm\","
                        + "\"message\":\"forbidden tok-1 " + APP_SECRET + "\"}"))) {
            QqOfficialShareQrTool tool = tool(dataDir, fixture.client());

            ToolResult result = tool.execute(call(Map.of("accountId", "acc-1")));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("CAPABILITY_UNAVAILABLE");
            assertThat(result.message())
                    .contains("kind=PERMISSION")
                    .contains("errorCode=11253")
                    .doesNotContain("tok-1")
                    .doesNotContain(APP_SECRET)
                    .doesNotContain("forbidden")
                    .doesNotContain("access_token")
                    .doesNotContain("Authorization");
            assertThat(qrFiles(dataDir)).isEmpty();
            assertThat(fixture.server().countPath(URL_PATH)).isEqualTo(1);
        }
    }

    @Test
    void badLinkIsRejectedWithoutEchoingUpstreamTextOrLeavingResidue(@TempDir Path dataDir)
            throws Exception {
        String unsafeUrl = "https://bot.q.qq.com.evil.invalid/s/SECRET_TOKEN_VALUE";
        try (Fixture fixture = fixture((request, index) ->
                Reply.json("{\"data\":{\"url\":\"" + unsafeUrl + "\"}}"))) {
            QqOfficialShareQrTool tool = tool(dataDir, fixture.client());

            ToolResult result = tool.execute(call(Map.of("accountId", "acc-1")));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("INVALID_SHARE_URL");
            assertThat(result.message())
                    .doesNotContain("SECRET_TOKEN_VALUE")
                    .doesNotContain("evil.invalid")
                    .doesNotContain("tok-1");
            assertThat(qrFiles(dataDir)).isEmpty();
        }
    }

    @Test
    void malformedUpstreamResponseFailsClosedWithoutLeavingResidue(@TempDir Path dataDir)
            throws Exception {
        try (Fixture fixture = fixture((request, index) ->
                Reply.json("{\"data\":{\"url\":123},\"echo\":\"tok-1\"}"))) {
            QqOfficialShareQrTool tool = tool(dataDir, fixture.client());

            ToolResult result = tool.execute(call(Map.of("accountId", "acc-1")));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("MALFORMED_RESPONSE");
            assertThat(result.message()).doesNotContain("tok-1").doesNotContain("echo");
            assertThat(qrFiles(dataDir)).isEmpty();
        }
    }

    @Test
    void ioFailureOccursAfterValidationAndLeavesNoTemporaryFile(@TempDir Path dataDir)
            throws Exception {
        Path qrPath = Files.writeString(
                dataDir.resolve("qr"), "not-a-directory", StandardCharsets.UTF_8);
        try (Fixture fixture = fixture((request, index) ->
                Reply.json("{\"data\":{\"url\":\"https://bot.q.qq.com/s/ok\"}}"))) {
            QqOfficialShareQrTool tool = tool(dataDir, fixture.client());

            ToolResult result = tool.execute(call(Map.of("accountId", "acc-1")));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("QR_IO_FAILURE");
            assertThat(result.message()).doesNotContain(qrPath.toString());
            assertThat(Files.readString(qrPath, StandardCharsets.UTF_8))
                    .isEqualTo("not-a-directory");
            try (Stream<Path> files = Files.list(dataDir)) {
                assertThat(files.map(path -> path.getFileName().toString()).toList())
                        .containsExactly("qr");
            }
        }
    }

    private static QqOfficialShareQrTool tool(Path dataDir, OfficialQqApiClient client) {
        OfficialQqShareQrAccountResolver resolver = accountId ->
                "acc-1".equals(accountId) ? Optional.of(client) : Optional.empty();
        return new QqOfficialShareQrTool(new OfficialQqShareQrService(dataDir, resolver));
    }

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(
                AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(),
                arguments,
                AgentIdentity.external());
    }

    private static List<Path> qrFiles(Path dataDir) throws Exception {
        Path qrDir = dataDir.resolve("qr");
        if (!Files.isDirectory(qrDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(qrDir)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    private static String decodePng(Path png) throws Exception {
        BufferedImage image = ImageIO.read(png.toFile());
        assertThat(image).isNotNull();
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return new MultiFormatReader().decode(bitmap, hints).getText();
    }

    private static Fixture fixture(Responder apiResponder) throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) -> {
            if (TOKEN_PATH.equals(request.rawPath())) {
                return Reply.json("{\"access_token\":\"tok-"
                        + tokenCalls.incrementAndGet()
                        + "\",\"expires_in\":7200}");
            }
            return apiResponder.handle(request, index);
        });
        var credential = new OfficialQqCredentialResolver(
                        "acc-1",
                        "app-1",
                        "BMA3_TEST_SECRET",
                        null,
                        Map.of("BMA3_TEST_SECRET", APP_SECRET))
                .resolve();
        OfficialQqTokenManager tokenManager = OfficialQqTokenManager.forLoopbackTesting(
                "acc-1",
                credential,
                server.baseUri(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                JSON,
                Clock.systemUTC(),
                Duration.ofSeconds(2));
        OfficialQqApiClient client = OfficialQqApiClient.forLoopbackTesting(
                "acc-1",
                tokenManager,
                server.baseUri(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                JSON,
                Duration.ofSeconds(2));
        return new Fixture(server, tokenManager, client);
    }

    private record Fixture(
            FakeQqHttpServer server,
            OfficialQqTokenManager tokenManager,
            OfficialQqApiClient client)
            implements AutoCloseable {

        @Override
        public void close() {
            tokenManager.close();
            server.close();
        }
    }
}
