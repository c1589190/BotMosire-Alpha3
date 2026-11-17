package io.mosire.bma3.qqofficial.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.bma3.qqofficial.FakeQqHttpServer;
import io.mosire.bma3.qqofficial.FakeQqHttpServer.RecordedRequest;
import io.mosire.bma3.qqofficial.FakeQqHttpServer.Reply;
import io.mosire.bma3.qqofficial.FakeQqHttpServer.Responder;
import io.mosire.bma3.qqofficial.auth.OfficialQqTokenManager;
import io.mosire.bma3.qqofficial.credential.OfficialQqCredential;
import io.mosire.bma3.qqofficial.credential.OfficialQqCredentialResolver;
import io.mosire.bma3.core.message.DeliveryStatus;
import io.mosire.bma3.core.message.TextSendPolicy;
import io.mosire.bma3.core.source.SourceAddress;
import io.mosire.bma3.core.store.SqliteMessageBoardStore;
import io.mosire.bma3.qqofficial.send.OfficialQqReplySequenceStore;
import io.mosire.bma3.qqofficial.send.OfficialQqSendAccount;
import io.mosire.bma3.qqofficial.send.OfficialQqSendAccounts;
import io.mosire.bma3.qqofficial.send.OfficialQqSendMessage;
import io.mosire.bma3.qqofficial.send.OfficialQqSendOutcome;
import io.mosire.bma3.qqofficial.send.OfficialQqSendService;
import io.mosire.bma3.qqofficial.send.OfficialQqTransportException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loopback-only tests for the fixed official message-send endpoints. */
class OfficialQqApiSendTest {

    private static final String TOKEN_PATH = "/app/getAppAccessToken";
    private static final String APP_SECRET = "synthetic-app-secret-value";

    @TempDir
    Path temp;

    @Test
    void tokenFailureIsProvablyNotDispatchedAndDoesNotPostAMessage() throws Exception {
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) -> {
            if (TOKEN_PATH.equals(request.rawPath())) {
                return Reply.statusJson(503, "{}");
            }
            return Reply.json("{\"data\":{\"message_id\":\"must-not-send\"}}");
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
                Clock.systemUTC(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(60),
                event -> { });
        OfficialQqApiClient client = OfficialQqApiClient.forLoopbackTesting(
                "acc-1",
                manager,
                server.baseUri(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(),
                Duration.ofSeconds(2),
                event -> { });
        OfficialQqApiSendTransport transport = new OfficialQqApiSendTransport(client);
        SourceAddress source = SourceAddress.parse("qq_official_group/acc-1/group-1");
        try (manager;
             server;
             SqliteMessageBoardStore board =
                     new SqliteMessageBoardStore(temp.resolve("token-fail.db"));
             OfficialQqReplySequenceStore seq = new OfficialQqReplySequenceStore(
                     temp.resolve("token-fail-seq.db"))) {
            OfficialQqSendAccounts accounts = accountId ->
                    "acc-1".equals(accountId)
                            ? Optional.of(new OfficialQqSendAccount("acc-1", true, transport))
                            : Optional.empty();
            OfficialQqSendService service = new OfficialQqSendService(
                    board, accounts, TextSendPolicy::defaults, seq);

            List<OfficialQqSendOutcome> outcomes =
                    service.sendText("acc-1", source, "hello");

            assertThat(outcomes).singleElement().satisfies(outcome -> {
                assertThat(outcome.status()).isEqualTo(DeliveryStatus.FAILED_BEFORE_SEND);
                assertThat(outcome.errorCode()).isEqualTo("TOKEN_ACQUISITION_FAILED");
                assertThat(outcome.dispatched()).isFalse();
                assertThat(outcome.recorded()).isTrue();
            });
            assertThat(server.countPath("/v2/groups/group-1/messages")).isZero();
            assertThat(board.read(source, 0, 10)).singleElement().satisfies(row -> {
                assertThat(row.delivery().status()).isEqualTo(DeliveryStatus.FAILED_BEFORE_SEND);
                assertThat(row.delivery().errorCode()).isEqualTo("TOKEN_ACQUISITION_FAILED");
                assertThat(row.externalMessageId()).isEmpty();
            });
        }
    }

    @Test
    void sendsGroupAndUserTextToOnlyTheFixedEndpoints() throws Exception {
        try (Fixture fixture = fixture((request, index) -> Reply.json(
                "{\"id\":\"msg-from-api\",\"data\":{\"message_id\":\"msg-from-api\"}}"))) {
            JsonNode group = fixture.client().sendGroupText("g/with space?", "hello");
            JsonNode user = fixture.client().sendUserText("u/1", "world");

            assertThat(group.at("/data/message_id").asText()).isEqualTo("msg-from-api");
            assertThat(user.at("/data/message_id").asText()).isEqualTo("msg-from-api");
            List<RecordedRequest> api = fixture.apiRequests();
            assertThat(api).extracting(RecordedRequest::rawPath)
                    .containsExactly("/v2/groups/g%2Fwith%20space%3F/messages",
                            "/v2/users/u%2F1/messages");
            assertThat(api).extracting(RecordedRequest::method).containsExactly("POST", "POST");
            assertThat(api).allSatisfy(request -> {
                assertThat(request.authorization()).isEqualTo("QQBot tok-1");
                assertThat(request.contentType()).startsWith("application/json");
            });

            JsonNode groupBody = new ObjectMapper().readTree(api.get(0).body());
            assertThat(groupBody.path("msg_type").asInt()).isEqualTo(0);
            assertThat(groupBody.path("content").asText()).isEqualTo("hello");
            assertThat(groupBody.has("msg_id")).isFalse();
            assertThat(groupBody.has("event_id")).isFalse();
            assertThat(api.get(0).body()).doesNotContain(APP_SECRET);
        }
    }

    @Test
    void fixedTransportExtractsExternalIdWithoutExposingTheRawResponse() throws Exception {
        try (Fixture fixture = fixture((request, index) -> Reply.json(
                "{\"data\":{\"id\":\"external-from-data\"},\"extra\":\"ignored\"}"))) {
            OfficialQqApiSendTransport transport =
                    new OfficialQqApiSendTransport(fixture.client());

            assertThat(fixture.client().accountId()).isEqualTo("acc-1");
            assertThat(fixture.tokenManager().accountId()).isEqualTo("acc-1");
            assertThat(transport.accountId()).isEqualTo("acc-1");
            assertThat(transport.sendGroup("g1", new OfficialQqSendMessage("text", "", "", null)))
                    .isEqualTo("external-from-data");
            assertThat(fixture.server().countPath("/v2/groups/g1/messages")).isEqualTo(1);
        }
    }

    @Test
    void successfulResponseWithoutPlatformMessageIdIsAmbiguous() throws Exception {
        try (Fixture fixture = fixture((request, index) -> Reply.json(
                "{\"data\":{}}"))) {
            OfficialQqApiSendTransport transport =
                    new OfficialQqApiSendTransport(fixture.client());

            assertThatThrownBy(() -> transport.sendGroup(
                    "g1", new OfficialQqSendMessage("text", "", "", null)))
                    .isInstanceOfSatisfying(OfficialQqTransportException.class, failure -> {
                        assertThat(failure.dispatched()).isTrue();
                        assertThat(failure.errorCode()).isEqualTo("MALFORMED_RESPONSE");
                    });
        }
    }

    @Test
    void includesPassiveFieldsOnlyWhenTheyBelongTogether() throws Exception {
        try (Fixture fixture = fixture((request, index) -> Reply.json("{\"data\":{\"id\":\"m\"}}"))) {
            fixture.client().sendGroupText("g1", "reply", "ROBOT1.0_message", "", 3);
            fixture.client().sendUserText("u1", "event", "", "event-envelope", null);

            List<RecordedRequest> api = fixture.apiRequests();
            JsonNode group = new ObjectMapper().readTree(api.get(0).body());
            assertThat(group.path("msg_id").asText()).isEqualTo("ROBOT1.0_message");
            assertThat(group.path("msg_seq").asInt()).isEqualTo(3);
            assertThat(group.has("event_id")).isFalse();

            JsonNode user = new ObjectMapper().readTree(api.get(1).body());
            assertThat(user.path("event_id").asText()).isEqualTo("event-envelope");
            assertThat(user.has("msg_id")).isFalse();
            assertThat(user.has("msg_seq")).isFalse();
        }
    }

    @Test
    void writeEndpointIsNeverRetriedEvenOn401OrBusinessError() throws Exception {
        AtomicInteger groupCalls = new AtomicInteger();
        try (Fixture fixture = fixture((request, index) -> {
            if (request.rawPath().startsWith("/v2/groups/")) {
                groupCalls.incrementAndGet();
                return Reply.statusJson(401, "{\"err_code\":401,\"trace_id\":\"trace-401\"}");
            }
            return Reply.json("{\"err_code\":11253,\"trace_id\":\"trace-business\"}");
        })) {
            assertThatThrownBy(() -> fixture.client().sendGroupText("g1", "silent"))
                    .isInstanceOfSatisfying(OfficialQqApiException.class, failure ->
                            assertThat(failure.getMessage())
                                    .doesNotContain("tok-1")
                                    .doesNotContain("silent"));
            assertThat(groupCalls).hasValue(1);
            assertThat(fixture.server().countPath("/v2/groups/g1/messages")).isEqualTo(1);
            assertThat(fixture.server().countPath(TOKEN_PATH))
                    .as("write 401 must not trigger token refresh/retry")
                    .isEqualTo(1);

            assertThatThrownBy(() -> fixture.client().sendUserText("u1", "silent"))
                    .isInstanceOfSatisfying(OfficialQqApiException.class, failure ->
                            assertThat(failure.getMessage()).doesNotContain("silent"));
            assertThat(fixture.server().countPath("/v2/users/u1/messages")).isEqualTo(1);
        }
    }

    @Test
    void invalidReplyShapeFailsBeforeAnyNetworkRequest() throws Exception {
        try (Fixture fixture = fixture((request, index) -> Reply.json("{}"))) {
            assertThatThrownBy(() -> fixture.client()
                    .sendGroupText("g1", "text", "msg", "event", 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fixture.client()
                    .sendUserText("u1", "text", "", "", 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fixture.client().sendUserText("u1", " "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(fixture.server().requestCount()).isZero();
        }
    }

    private static Fixture fixture(Responder apiResponder) throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        List<OfficialQqHttpEvent> events = new ArrayList<>();
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) -> {
            if (TOKEN_PATH.equals(request.rawPath())) {
                int call = tokenCalls.incrementAndGet();
                return Reply.json("{\"access_token\":\"tok-" + call + "\",\"expires_in\":7200}");
            }
            return apiResponder.handle(request, index);
        });
        OfficialQqCredential credential = new OfficialQqCredentialResolver(
                        "acc-1", "app-1", "BMA3_TEST_SECRET", null,
                        Map.of("BMA3_TEST_SECRET", APP_SECRET))
                .resolve();
        OfficialQqHttpObserver observer = events::add;
        OfficialQqTokenManager manager = OfficialQqTokenManager.forLoopbackTesting(
                "acc-1",
                credential,
                server.baseUri(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(),
                java.time.Clock.systemUTC(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(60),
                observer);
        OfficialQqApiClient client = OfficialQqApiClient.forLoopbackTesting(
                "acc-1",
                manager,
                server.baseUri(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(),
                Duration.ofSeconds(2),
                observer);
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
