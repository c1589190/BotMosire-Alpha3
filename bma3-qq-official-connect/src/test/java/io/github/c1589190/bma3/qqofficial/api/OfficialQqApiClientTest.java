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
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OfficialQqApiClientTest {

    private static final String TOKEN_PATH = "/app/getAppAccessToken";
    private static final String APP_SECRET = "synthetic-app-secret-value";

    @Test
    void callsEveryFixedEndpointWithABearerTokenAndEncodesPathSegments() throws Exception {
        try (Fixture fixture = fixture((request, index) -> Reply.json("{}"))) {
            fixture.client().currentUser();
            fixture.client().gatewayBot();
            fixture.client().groupInfo("g/with space?&");
            fixture.client().groupBotState("g/with space?&");
            fixture.client().userInfo("u/1");
            JsonNode link = fixture.client().generateUrlLink("bma3&x");

            assertThat(link).isNotNull();
            List<RecordedRequest> apiRequests = fixture.apiRequests();
            assertThat(apiRequests)
                    .extracting(RecordedRequest::rawPath)
                    .containsExactly(
                            "/users/@me",
                            "/gateway/bot",
                            "/v2/groups/g%2Fwith%20space%3F%26/info",
                            "/v2/groups/g%2Fwith%20space%3F%26/bot_state",
                            "/v2/users/u%2F1/info",
                            "/v2/generate_url_link");
            assertThat(apiRequests)
                    .extracting(RecordedRequest::method)
                    .containsExactly("GET", "GET", "GET", "GET", "GET", "POST");
            assertThat(apiRequests).allSatisfy(request -> {
                assertThat(request.authorization()).isEqualTo("QQBot tok-1");
                assertThat(request.rawQuery()).isNull();
            });
            assertThat(apiRequests.get(0).body()).isEmpty();
            JsonNode body = new ObjectMapper().readTree(apiRequests.get(5).body());
            assertThat(body.path("callback_data").asText()).isEqualTo("bma3&x");
            assertThat(fixture.events().toString()).doesNotContain(APP_SECRET).doesNotContain("tok-1");
        }
    }

    @Test
    void http200BusinessErrorUsesErrCodeAndTraceAndIsNotRetried() throws Exception {
        try (Fixture fixture = fixture((request, index) -> {
            assertThat(request.rawPath()).isEqualTo("/v2/groups/g1/info");
            return Reply.json(
                    "{\"err_code\":11253,\"trace_id\":\"trace-q\",\"message\":\"forbidden\"}");
        })) {
            assertThatThrownBy(() -> fixture.client().groupInfo("g1"))
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> {
                                assertThat(failure.kind()).isEqualTo(OfficialQqErrorKind.PERMISSION);
                                assertThat(failure.httpStatus()).isEqualTo(200);
                                assertThat(failure.errorCode()).isEqualTo("11253");
                                assertThat(failure.traceId()).isEqualTo("trace-q");
                                assertThat(failure.tokenRejected()).isFalse();
                                assertThat(failure.getMessage()).doesNotContain("forbidden");
                                assertThat(failure.getMessage()).doesNotContain(APP_SECRET);
                            });
            assertThat(fixture.server().countPath("/v2/groups/g1/info")).isEqualTo(1);
            assertThat(fixture.apiEvents())
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.endpoint()).isEqualTo(OfficialQqEndpoint.GROUP_INFO);
                        assertThat(event.success()).isFalse();
                        assertThat(event.errorCode()).isEqualTo("11253");
                        assertThat(event.traceId()).isEqualTo("trace-q");
                    });
            assertThat(fixture.events().toString()).doesNotContain(APP_SECRET).doesNotContain("tok-1");
        }
    }

    @Test
    void http403IsPermissionAndIsNotRetried() throws Exception {
        try (Fixture fixture = fixture((request, index) ->
                Reply.statusJson(403, "{\"message\":\"tok-1\",\"trace_id\":\"trace-403\"}"))) {
            assertThatThrownBy(() -> fixture.client().userInfo("u1"))
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> {
                                assertThat(failure.kind()).isEqualTo(OfficialQqErrorKind.PERMISSION);
                                assertThat(failure.httpStatus()).isEqualTo(403);
                                assertThat(failure.traceId()).isEqualTo("trace-403");
                                assertThat(failure.getMessage()).doesNotContain("tok-1");
                            });
            assertThat(fixture.server().countPath("/v2/users/u1/info")).isEqualTo(1);
            assertThat(fixture.events().toString()).doesNotContain("tok-1");
        }
    }

    @Test
    void aTokenRejectionGetsExactlyOneRefreshAndOneRetry() throws Exception {
        AtomicInteger meCalls = new AtomicInteger();
        try (Fixture fixture = fixture((request, index) -> {
            if (!request.rawPath().equals("/users/@me")) {
                return Reply.json("{}");
            }
            return switch (meCalls.incrementAndGet()) {
                case 1 -> Reply.statusJson(401, "{\"err_code\":401,\"trace_id\":\"first\"}");
                default -> Reply.json("{\"id\":\"bot-1\"}");
            };
        })) {
            JsonNode me = fixture.client().me();

            assertThat(me.path("id").asText()).isEqualTo("bot-1");
            assertThat(meCalls).hasValue(2);
            assertThat(fixture.server().countPath(TOKEN_PATH)).isEqualTo(2);
            List<RecordedRequest> meRequests = fixture.apiRequests();
            assertThat(meRequests).hasSize(2);
            assertThat(meRequests.get(0).authorization()).isEqualTo("QQBot tok-1");
            assertThat(meRequests.get(1).authorization()).isEqualTo("QQBot tok-2");
        }
    }

    @Test
    void generateLinkCanBeSafelyRetriedOnceAfterATokenRejection() throws Exception {
        AtomicInteger linkCalls = new AtomicInteger();
        try (Fixture fixture = fixture((request, index) -> {
            if (!request.rawPath().equals("/v2/generate_url_link")) {
                return Reply.json("{}");
            }
            return switch (linkCalls.incrementAndGet()) {
                case 1 -> Reply.statusJson(401, "{\"err_code\":401}");
                default -> Reply.json("{\"data\":{\"url\":\"https://bot.q.qq.com/s/example\"}}");
            };
        })) {
            JsonNode result = fixture.client().generateUrlLink("bma3");

            assertThat(result.path("data").path("url").asText())
                    .isEqualTo("https://bot.q.qq.com/s/example");
            assertThat(linkCalls).hasValue(2);
            assertThat(fixture.server().countPath(TOKEN_PATH)).isEqualTo(2);
            assertThat(fixture.apiRequests().get(1).authorization()).isEqualTo("QQBot tok-2");
        }
    }

    @Test
    void serverErrorNonJsonAndInvalidInputAreNotRetried() throws Exception {
        try (Fixture fixture = fixture((request, index) ->
                Reply.statusJson(503, "{\"trace_id\":\"trace-503\"}"))) {
            assertThatThrownBy(() -> fixture.client().gatewayBot())
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> {
                                assertThat(failure.kind()).isEqualTo(OfficialQqErrorKind.SERVER);
                                assertThat(failure.httpStatus()).isEqualTo(503);
                            });
            assertThat(fixture.server().countPath("/gateway/bot")).isEqualTo(1);
        }

        try (Fixture fixture = fixture((request, index) ->
                new Reply(200, "not-json", Map.of()))) {
            assertThatThrownBy(() -> fixture.client().userInfo("u1"))
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> assertThat(failure.kind())
                                    .isEqualTo(OfficialQqErrorKind.MALFORMED_RESPONSE));
            assertThat(fixture.server().countPath("/v2/users/u1/info")).isEqualTo(1);
        }

        try (Fixture fixture = fixture((request, index) -> Reply.json("{}"))) {
            assertThatThrownBy(() -> fixture.client().groupInfo(" "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(fixture.server().countPath("/app/getAppAccessToken")).isZero();
        }
    }

    @Test
    void arbitraryErrorCodeTextCannotEchoATokenIntoDiagnostics() throws Exception {
        try (Fixture fixture = fixture((request, index) ->
                Reply.json("{\"err_code\":\"tok-1\",\"trace_id\":\"trace-x\"}"))) {
            assertThatThrownBy(() -> fixture.client().groupInfo("g1"))
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> {
                                assertThat(failure.errorCode()).isEqualTo("NON_NUMERIC");
                                assertThat(failure.getMessage()).doesNotContain("tok-1");
                            });
            assertThat(fixture.events().toString()).doesNotContain("tok-1");
        }
    }

    @Test
    void traceMetadataIsSanitizedAndRedactsTheAuthorizationToken() throws Exception {
        try (Fixture fixture = fixture((request, index) -> Reply.json(
                "{\"err_code\":11253,\"trace_id\":\"trace\\nQQBot tok-1\"}"))) {
            assertThatThrownBy(() -> fixture.client().groupInfo("g1"))
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> {
                                assertThat(failure.traceId()).doesNotContain("tok-1");
                                assertThat(failure.traceId()).doesNotContain("\n");
                                assertThat(failure.traceId()).contains("redacted");
                                assertThat(failure.getMessage()).doesNotContain("tok-1");
                            });
            assertThat(fixture.events().toString())
                    .doesNotContain("tok-1")
                    .doesNotContain(APP_SECRET);
        }
    }

    @Test
    void dangerousOrOversizedOpenidsFailBeforeAnyNetworkRequest() throws Exception {
        try (Fixture fixture = fixture((request, index) -> Reply.json("{}"))) {
            List<String> invalidOpenids = List.of(
                    ".",
                    "..",
                    "a/../b",
                    "a\\..\\b",
                    "control\nid",
                    "format\u202Eid",
                    "x".repeat(257));
            for (String openid : invalidOpenids) {
                assertThatThrownBy(() -> fixture.client().groupInfo(openid))
                        .as("非法 openid 必须 fail-closed")
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThatThrownBy(() -> fixture.client().userInfo("user\u0000id"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(fixture.server().requestCount())
                    .as("路径校验必须发生在 token 请求和 OpenAPI 请求之前")
                    .isZero();
        }
    }

    @Test
    void oversizedApiResponseIsRejectedAfterReadingAtMostTheLimitPlusOne() throws Exception {
        String huge = "x".repeat(OfficialQqResponseBody.MAX_RESPONSE_BYTES + 1);
        try (Fixture fixture = fixture((request, index) -> new Reply(200, huge, Map.of()))) {
            assertThatThrownBy(() -> fixture.client().groupInfo("g1"))
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> {
                                assertThat(failure.kind())
                                        .isEqualTo(OfficialQqErrorKind.MALFORMED_RESPONSE);
                                assertThat(failure.getMessage()).doesNotContain(huge);
                            });
            assertThat(fixture.apiEvents())
                    .singleElement()
                    .satisfies(event -> assertThat(event.success()).isFalse());
        }
    }

    @Test
    void aStalledResponseBodyHitsTheOverallDeadlineAndReturnsTimeout() throws Exception {
        StallingQqHttpServer server =
                new StallingQqHttpServer("/users/@me", 3_000L, true);
        try {
            OfficialQqHttpObserver observer = event -> { };
            OfficialQqTokenManager manager = managerAt(
                    server.baseUri(), Duration.ofMillis(250), observer);
            OfficialQqApiClient client = OfficialQqApiClient.forLoopbackTesting(
                    "acc-1",
                    manager,
                    server.baseUri(),
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                    new ObjectMapper(),
                    Duration.ofMillis(250),
                    observer);
            long startedNanos = System.nanoTime();
            assertThatThrownBy(client::me)
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> {
                                assertThat(failure.kind())
                                        .isEqualTo(OfficialQqErrorKind.TIMEOUT);
                                assertThat(failure.getMessage()).doesNotContain("tok-1");
                            });
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
            assertThat(elapsedMillis).isLessThan(2_000L);
            assertThat(server.awaitStallStarted(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            server.close();
        }
    }

    @Test
    void aRedirectResponseProducesNoSecondRequest() throws Exception {
        try (Fixture fixture = fixture((request, index) -> new Reply(
                307, "{}", Map.of("Location", "/redirect-target")))) {
            assertThatThrownBy(() -> fixture.client().me())
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> {
                                assertThat(failure.httpStatus()).isEqualTo(307);
                                assertThat(failure.kind()).isEqualTo(OfficialQqErrorKind.HTTP);
                            });
            assertThat(fixture.apiRequests()).hasSize(1);
            assertThat(fixture.server().countPath("/redirect-target")).isZero();
        }
    }

    @Test
    void productionFactoryRejectsRedirectFollowingClients() throws Exception {
        try (Fixture fixture = fixture((request, index) -> Reply.json("{}"))) {
            HttpClient redirecting = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();
            assertThatThrownBy(() -> OfficialQqApiClient.production(
                            "acc-1",
                            fixture.tokenManager(),
                            redirecting,
                            new ObjectMapper(),
                            Duration.ofSeconds(2)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("followRedirects=NEVER");
        }
    }

    @Test
    void apiClientAccountIdMustMatchItsTokenManager() throws Exception {
        try (Fixture fixture = fixture((request, index) -> Reply.json("{}"))) {
            assertThatThrownBy(() -> OfficialQqApiClient.forLoopbackTesting(
                            "other-account",
                            fixture.tokenManager(),
                            fixture.server().baseUri(),
                            HttpClient.newHttpClient(),
                            new ObjectMapper(),
                            Duration.ofSeconds(2)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("accountId");
        }
    }

    @Test
    void productionAndLoopbackBaseUrisAreSeparated() throws Exception {
        assertThat(OfficialQqBaseUri.requireProduction(URI.create("https://api.bot.qq.com")))
                .isEqualTo(OfficialQqBaseUri.PRODUCTION);
        assertThatThrownBy(() -> OfficialQqBaseUri.requireProduction(
                        URI.create("https://evil.example")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api.bot.qq.com");
        assertThat(OfficialQqBaseUri.requireLoopbackTesting(
                        URI.create("http://127.0.0.1:12345")))
                .isEqualTo(URI.create("http://127.0.0.1:12345"));
        assertThatThrownBy(() -> OfficialQqBaseUri.requireLoopbackTesting(
                        URI.create("http://example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("回环");

        try (Fixture fixture = fixture((request, index) -> Reply.json("{}"))) {
            assertThatThrownBy(() -> new OfficialQqApiClient(
                            "acc-1",
                            fixture.tokenManager(),
                            URI.create("http://127.0.0.1:1"),
                            HttpClient.newHttpClient(),
                            new ObjectMapper(),
                            Duration.ofSeconds(2)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("api.bot.qq.com");
        }
    }

    private static OfficialQqTokenManager managerAt(
            URI baseUri, Duration timeout, OfficialQqHttpObserver observer) throws Exception {
        OfficialQqCredential credential = new OfficialQqCredentialResolver(
                        "acc-1", "app-1", "BMA3_TEST_SECRET", null,
                        Map.of("BMA3_TEST_SECRET", APP_SECRET))
                .resolve();
        return OfficialQqTokenManager.forLoopbackTesting(
                "acc-1",
                credential,
                baseUri,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(),
                java.time.Clock.systemUTC(),
                timeout,
                Duration.ofSeconds(60),
                observer);
    }

    private static Fixture fixture(Responder apiResponder) throws Exception {
        AtomicInteger tokenCalls = new AtomicInteger();
        List<OfficialQqHttpEvent> events = new ArrayList<>();
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) -> {
            if (TOKEN_PATH.equals(request.rawPath())) {
                int call = tokenCalls.incrementAndGet();
                return Reply.json(
                        "{\"access_token\":\"tok-" + call + "\",\"expires_in\":7200}");
            }
            return apiResponder.handle(request, index);
        });
        OfficialQqHttpObserver observer = events::add;
        OfficialQqTokenManager manager = managerAt(server.baseUri(), Duration.ofSeconds(2), observer);
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

        private List<OfficialQqHttpEvent> apiEvents() {
            return events.stream()
                    .filter(event -> event.endpoint() != OfficialQqEndpoint.TOKEN)
                    .toList();
        }

        @Override
        public void close() {
            tokenManager.close();
            server.close();
        }
    }
}
