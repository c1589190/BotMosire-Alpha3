package io.github.c1589190.bma3.qqofficial.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.bma3.qqofficial.FakeQqHttpServer;
import io.github.c1589190.bma3.qqofficial.FakeQqHttpServer.RecordedRequest;
import io.github.c1589190.bma3.qqofficial.FakeQqHttpServer.Reply;
import io.github.c1589190.bma3.qqofficial.FakeQqHttpServer.Responder;
import io.github.c1589190.bma3.qqofficial.auth.OfficialQqTokenManager;
import io.github.c1589190.bma3.qqofficial.credential.OfficialQqCredential;
import io.github.c1589190.bma3.qqofficial.credential.OfficialQqCredentialResolver;
import io.github.c1589190.bma3.qqofficial.media.OfficialQqMediaMessage;
import io.github.c1589190.bma3.qqofficial.media.OfficialQqMediaTransportException;
import io.github.c1589190.bma3.qqofficial.send.OfficialQqTransportException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OfficialQqApiMediaTest {

    private static final String TOKEN_PATH = "/app/getAppAccessToken";
    private static final String APP_SECRET = "synthetic-media-secret";
    private static final String BASE64 = "bG9jYWwtYnl0ZXM=";

    @Test
    void uploadsAndSendsUseOnlyFixedEndpointsAndExpectedBodies() throws Exception {
        try (Fixture fixture = fixture((request, index) -> {
            if (request.rawPath().endsWith("/files")) {
                return Reply.json("{\"file_info\":\"opaque-file-info\",\"ttl\":300}");
            }
            return Reply.json("{\"id\":\"msg-media\"}");
        })) {
            OfficialQqMediaTransport transport = new OfficialQqMediaTransport(fixture.client());

            var groupUpload = transport.uploadGroup("g/1", 1, "x.png", BASE64);
            var userUpload = transport.uploadUser("u/1", 4, "y.txt", BASE64);
            String groupId = transport.sendGroupMedia("g/1",
                    new OfficialQqMediaMessage("opaque-file-info", "ROBOT1.0_IN", "", 2));
            String userId = transport.sendUserMedia("u/1",
                    new OfficialQqMediaMessage("opaque-file-info", "", "event-1", null));

            assertThat(groupUpload.fileInfo()).isEqualTo("opaque-file-info");
            assertThat(groupUpload.ttlSeconds()).isEqualTo(300);
            assertThat(userUpload.toString()).doesNotContain("opaque-file-info");
            assertThat(groupId).isEqualTo("msg-media");
            assertThat(userId).isEqualTo("msg-media");

            List<RecordedRequest> api = fixture.apiRequests();
            assertThat(api).extracting(RecordedRequest::rawPath)
                    .containsExactly(
                            "/v2/groups/g%2F1/files",
                            "/v2/users/u%2F1/files",
                            "/v2/groups/g%2F1/messages",
                            "/v2/users/u%2F1/messages");
            assertThat(api).extracting(RecordedRequest::method)
                    .containsExactly("POST", "POST", "POST", "POST");
            assertThat(api).allSatisfy(request -> {
                assertThat(request.authorization()).isEqualTo("QQBot tok-1");
                assertThat(request.contentType()).startsWith("application/json");
            });

            JsonNode groupUploadBody = new ObjectMapper().readTree(api.get(0).body());
            assertThat(groupUploadBody.path("file_type").asInt()).isEqualTo(1);
            assertThat(groupUploadBody.path("file_name").asText()).isEqualTo("x.png");
            assertThat(groupUploadBody.path("file_data").asText()).isEqualTo(BASE64);
            assertThat(groupUploadBody.path("srv_send_msg").asBoolean()).isFalse();
            assertThat(groupUploadBody.has("url")).isFalse();
            assertThat(groupUploadBody.has("upload_id")).isFalse();
            assertThat(api.get(0).body()).doesNotContain(APP_SECRET);

            JsonNode userUploadBody = new ObjectMapper().readTree(api.get(1).body());
            assertThat(userUploadBody.path("file_type").asInt()).isEqualTo(4);
            assertThat(userUploadBody.path("file_name").asText()).isEqualTo("y.txt");
            assertThat(userUploadBody.path("file_data").asText()).isEqualTo(BASE64);

            JsonNode groupSendBody = new ObjectMapper().readTree(api.get(2).body());
            assertThat(groupSendBody.path("msg_type").asInt()).isEqualTo(7);
            assertThat(groupSendBody.path("media").path("file_info").asText())
                    .isEqualTo("opaque-file-info");
            assertThat(groupSendBody.path("msg_id").asText()).isEqualTo("ROBOT1.0_IN");
            assertThat(groupSendBody.path("msg_seq").asInt()).isEqualTo(2);
            assertThat(groupSendBody.has("event_id")).isFalse();
            assertThat(groupSendBody.has("content")).isFalse();

            JsonNode userSendBody = new ObjectMapper().readTree(api.get(3).body());
            assertThat(userSendBody.path("msg_type").asInt()).isEqualTo(7);
            assertThat(userSendBody.path("event_id").asText()).isEqualTo("event-1");
            assertThat(userSendBody.has("msg_id")).isFalse();
            assertThat(userSendBody.has("msg_seq")).isFalse();
            assertThat(fixture.events().toString())
                    .doesNotContain(APP_SECRET)
                    .doesNotContain("tok-1")
                    .doesNotContain("opaque-file-info")
                    .doesNotContain(BASE64);
        }
    }

    @Test
    void nonStandardBase64FailsBeforeAnyNetworkRequest() throws Exception {
        try (Fixture fixture = fixture((request, index) ->
                Reply.json("{\"file_info\":\"must-not-be-used\",\"ttl\":300}"))) {
            OfficialQqMediaTransport transport = new OfficialQqMediaTransport(fixture.client());

            assertThatThrownBy(() -> transport.uploadGroup("g1", 1, "x.png", "not base64"))
                    .isInstanceOfSatisfying(OfficialQqMediaTransportException.class,
                            failure -> {
                                assertThat(failure.kind()).isEqualTo(
                                        OfficialQqMediaTransportException.Kind.NOT_DISPATCHED);
                                assertThat(failure.errorCode()).isEqualTo("INVALID_REQUEST");
                            });
            assertThat(fixture.server().requestCount()).isZero();
        }
    }

    @Test
    void uploadRejectionIsNotRetriedAndDoesNotSendAMessage() throws Exception {
        AtomicInteger fileCalls = new AtomicInteger();
        try (Fixture fixture = fixture((request, index) -> {
            if (request.rawPath().endsWith("/files")) {
                fileCalls.incrementAndGet();
                return Reply.statusJson(400, "{\"err_code\":850019,\"trace_id\":\"trace-upload\"}");
            }
            return Reply.json("{\"id\":\"must-not-send\"}");
        })) {
            OfficialQqMediaTransport transport = new OfficialQqMediaTransport(fixture.client());

            assertThatThrownBy(() -> transport.uploadGroup("g1", 1, "x.png", BASE64))
                    .isInstanceOfSatisfying(OfficialQqMediaTransportException.class,
                            failure -> {
                                assertThat(failure.kind())
                                        .isEqualTo(OfficialQqMediaTransportException.Kind.REJECTED);
                                assertThat(failure.errorCode()).isEqualTo("UPLOAD_REJECTED");
                                assertThat(failure.getMessage())
                                        .doesNotContain(APP_SECRET)
                                        .doesNotContain("tok-1");
                            });
            assertThat(fileCalls).hasValue(1);
            assertThat(fixture.server().countPath("/v2/groups/g1/files")).isEqualTo(1);
            assertThat(fixture.server().countPath("/v2/groups/g1/messages")).isZero();
            assertThat(fixture.server().countPath(TOKEN_PATH)).isEqualTo(1);
        }
    }

    @Test
    void malformedUploadResponseIsUnknownAndNotRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture fixture = fixture((request, index) -> {
            if (request.rawPath().endsWith("/files")) {
                calls.incrementAndGet();
                return Reply.json("{\"file_info\":\"missing-ttl\"}");
            }
            return Reply.json("{\"id\":\"must-not-send\"}");
        })) {
            OfficialQqMediaTransport transport = new OfficialQqMediaTransport(fixture.client());

            assertThatThrownBy(() -> transport.uploadUser("u1", 4, "x.bin", BASE64))
                    .isInstanceOfSatisfying(OfficialQqMediaTransportException.class,
                            failure -> {
                                assertThat(failure.kind())
                                        .isEqualTo(OfficialQqMediaTransportException.Kind.UNKNOWN);
                                assertThat(failure.errorCode()).isEqualTo("UPLOAD_RESPONSE_INVALID");
                            });
            assertThat(calls).hasValue(1);
            assertThat(fixture.server().countPath("/v2/users/u1/files")).isEqualTo(1);
            assertThat(fixture.server().countPath("/v2/users/u1/messages")).isZero();
        }
    }

    @Test
    void uploadTimeoutIsUnknownAndNotRetried() throws Exception {
        try (StallingQqHttpServer server =
                     new StallingQqHttpServer("/v2/groups/g1/files", 3_000L, false)) {
            OfficialQqCredential credential = new OfficialQqCredentialResolver(
                            "acc-1", "app-1", "BMA3_TEST_SECRET", null,
                            Map.of("BMA3_TEST_SECRET", APP_SECRET))
                    .resolve();
            try (OfficialQqTokenManager manager = OfficialQqTokenManager.forLoopbackTesting(
                    "acc-1",
                    credential,
                    server.baseUri(),
                    HttpClient.newBuilder().connectTimeout(Duration.ofMillis(250)).build(),
                    new ObjectMapper(),
                    java.time.Clock.systemUTC(),
                    Duration.ofMillis(250),
                    Duration.ofSeconds(60),
                    event -> { })) {
                OfficialQqApiClient client = OfficialQqApiClient.forLoopbackTesting(
                        "acc-1",
                        manager,
                        server.baseUri(),
                        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(250)).build(),
                        new ObjectMapper(),
                        Duration.ofMillis(250),
                        event -> { });
                OfficialQqMediaTransport transport = new OfficialQqMediaTransport(client);

                assertThatThrownBy(() -> transport.uploadGroup("g1", 1, "x.png", BASE64))
                        .isInstanceOfSatisfying(OfficialQqMediaTransportException.class,
                                failure -> {
                                    assertThat(failure.kind()).isEqualTo(
                                            OfficialQqMediaTransportException.Kind.UNKNOWN);
                                    assertThat(failure.errorCode()).isEqualTo("UPLOAD_TIMEOUT");
                                });
                assertThat(server.requestCount())
                        .as("token acquisition plus exactly one upload request")
                        .isEqualTo(2);
            }
        }
    }

    @Test
    void mediaSendWithoutIdIsUnknownAndNotRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture fixture = fixture((request, index) -> {
            if (request.rawPath().endsWith("/messages")) {
                calls.incrementAndGet();
                return Reply.json("{\"data\":{}}");
            }
            return Reply.json("{\"file_info\":\"opaque-file-info\",\"ttl\":300}");
        })) {
            OfficialQqMediaTransport transport = new OfficialQqMediaTransport(fixture.client());

            assertThatThrownBy(() -> transport.sendGroupMedia("g1",
                    new OfficialQqMediaMessage("opaque-file-info", "", "", null)))
                    .isInstanceOfSatisfying(OfficialQqTransportException.class,
                            failure -> {
                                assertThat(failure.dispatched()).isTrue();
                                assertThat(failure.errorCode()).isEqualTo("MALFORMED_RESPONSE");
                            });
            assertThat(calls).hasValue(1);
            assertThat(fixture.server().countPath("/v2/groups/g1/messages")).isEqualTo(1);
        }
    }

    private static Fixture fixture(Responder apiResponder) throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        List<OfficialQqHttpEvent> events = new ArrayList<>();
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) -> {
            if (TOKEN_PATH.equals(request.rawPath())) {
                return Reply.json("{\"access_token\":\"tok-"
                        + tokenCalls.incrementAndGet() + "\",\"expires_in\":7200}");
            }
            return apiResponder.handle(request, index);
        });
        OfficialQqCredential credential = new OfficialQqCredentialResolver(
                        "acc-1", "app-1", "BMA3_TEST_SECRET", null,
                        Map.of("BMA3_TEST_SECRET", APP_SECRET))
                .resolve();
        OfficialQqTokenManager manager = OfficialQqTokenManager.forLoopbackTesting(
                "acc-1",
                credential,
                server.baseUri(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(),
                java.time.Clock.systemUTC(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(60),
                events::add);
        OfficialQqApiClient client = OfficialQqApiClient.forLoopbackTesting(
                "acc-1",
                manager,
                server.baseUri(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(),
                Duration.ofSeconds(2),
                events::add);
        return new Fixture(server, manager, client, events);
    }

    private record Fixture(
            FakeQqHttpServer server,
            OfficialQqTokenManager tokenManager,
            OfficialQqApiClient client,
            List<OfficialQqHttpEvent> events)
            implements AutoCloseable {

        private List<RecordedRequest> apiRequests() {
            return server.requests().stream()
                    .filter(request -> !TOKEN_PATH.equals(request.rawPath()))
                    .toList();
        }

        @Override
        public void close() {
            tokenManager.close();
            server.close();
        }
    }
}
