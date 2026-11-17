package io.mosire.bma3.qqofficial.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.bma3.qqofficial.FakeQqHttpServer;
import io.mosire.bma3.qqofficial.MutableClock;
import io.mosire.bma3.qqofficial.api.OfficialQqApiException;
import io.mosire.bma3.qqofficial.api.OfficialQqEndpoint;
import io.mosire.bma3.qqofficial.api.OfficialQqErrorKind;
import io.mosire.bma3.qqofficial.api.OfficialQqHttpEvent;
import io.mosire.bma3.qqofficial.api.OfficialQqResponseBody;
import io.mosire.bma3.qqofficial.credential.OfficialQqCredential;
import io.mosire.bma3.qqofficial.credential.OfficialQqCredentialResolver;
import io.mosire.bma3.qqofficial.api.StallingQqHttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OfficialQqTokenManagerTest {

    private static final String APP_SECRET = "synthetic-app-secret-value";
    private static final String TOKEN_PATH = "/app/getAppAccessToken";

    @Test
    void fetchesOnceThenUsesTheMemoryCache() throws Exception {
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) ->
                FakeQqHttpServer.Reply.json(
                        "{\"access_token\":\"tok-1\",\"expires_in\":\"7200\"}"));
        try (server) {
            OfficialQqTokenManager manager = manager(server, new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));

            assertThat(manager.authorizationHeader()).isEqualTo("QQBot tok-1");
            assertThat(manager.authorizationHeader()).isEqualTo("QQBot tok-1");
            assertThat(manager.accessToken()).isEqualTo("tok-1");

            assertThat(server.countPath(TOKEN_PATH)).isEqualTo(1);
            assertThat(server.request(0).method()).isEqualTo("POST");
            assertThat(server.request(0).body()).contains("\"appId\":\"app-1\"");
            assertThat(server.request(0).body()).contains("\"clientSecret\":\"" + APP_SECRET + "\"");
        }
    }

    @Test
    void refreshesBeforeExpiryUsingTheInjectedClock() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) -> {
            int call = calls.incrementAndGet();
            return FakeQqHttpServer.Reply.json(
                    "{\"access_token\":\"tok-" + call + "\",\"expires_in\":120}");
        });
        try (server) {
            MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
            OfficialQqTokenManager manager = manager(server, clock);

            assertThat(manager.authorizationHeader()).isEqualTo("QQBot tok-1");
            clock.advance(Duration.ofSeconds(59));
            assertThat(manager.authorizationHeader()).isEqualTo("QQBot tok-1");
            assertThat(calls).hasValue(1);

            clock.advance(Duration.ofSeconds(2));
            assertThat(manager.authorizationHeader()).isEqualTo("QQBot tok-2");
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void concurrentCallersShareOneSingleFlightRefresh() throws Exception {
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstRequestStarted.countDown();
                assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return FakeQqHttpServer.Reply.json(
                    "{\"access_token\":\"tok-" + call + "\",\"expires_in\":7200}");
        });
        ExecutorService workers = Executors.newFixedThreadPool(12);
        try (server) {
            OfficialQqTokenManager manager = manager(server, Clock.systemUTC());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                futures.add(workers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return manager.authorizationHeader();
                }));
            }
            start.countDown();
            assertThat(firstRequestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100L);
            assertThat(server.countPath(TOKEN_PATH))
                    .as("并发调用只允许发出一个 token 请求")
                    .isEqualTo(1);
            releaseFirst.countDown();
            for (Future<String> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("QQBot tok-1");
            }
            assertThat(calls).hasValue(1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void concurrentRejectionsShareOneReplacementRefresh() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) -> {
            int call = calls.incrementAndGet();
            return FakeQqHttpServer.Reply.json(
                    "{\"access_token\":\"tok-" + call + "\",\"expires_in\":7200}");
        });
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try (server) {
            OfficialQqTokenManager manager = manager(server, Clock.systemUTC());
            String rejected = manager.authorizationHeader();
            assertThat(rejected).isEqualTo("QQBot tok-1");

            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(workers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return manager.authorizationHeaderAfterRejected(rejected);
                }));
            }
            start.countDown();
            for (Future<String> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("QQBot tok-2");
            }
            assertThat(calls).hasValue(2);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void successfulTraceMetadataCannotLeakTheNewAccessToken() throws Exception {
        List<OfficialQqHttpEvent> events = new ArrayList<>();
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) ->
                FakeQqHttpServer.Reply.json(
                        "{\"access_token\":\"tok-1\",\"expires_in\":7200,"
                                + "\"trace_id\":\"trace-tok-1\"}"));
        try (server) {
            OfficialQqTokenManager manager = manager(
                    server, Clock.systemUTC(), event -> events.add(event));
            assertThat(manager.authorizationHeader()).isEqualTo("QQBot tok-1");
            assertThat(events).hasSize(1);
            assertThat(events.get(0).traceId()).doesNotContain("tok-1").contains("redacted");
            assertThat(events.toString()).doesNotContain("tok-1");
        }
    }

    @Test
    void traceMetadataIsSanitizedAndRedactsTheAppSecret() throws Exception {
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) ->
                FakeQqHttpServer.Reply.json(
                        "{\"code\":100016,\"trace_id\":\"trace\\n" + APP_SECRET + "\"}"));
        try (server) {
            assertThatThrownBy(manager(server, Clock.systemUTC())::authorizationHeader)
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> {
                                assertThat(failure.traceId())
                                        .doesNotContain(APP_SECRET)
                                        .doesNotContain("\n")
                                        .contains("redacted");
                                assertThat(failure.getMessage()).doesNotContain(APP_SECRET);
                            });
        }
    }

    @Test
    void aBusinessErrorIsClassifiedAndItsMessageIsNotLeaked() throws Exception {
        List<OfficialQqHttpEvent> events = new ArrayList<>();
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) ->
                FakeQqHttpServer.Reply.json(
                        "{\"code\":100016,\"trace_id\":\"trace-1\",\"message\":\""
                                + APP_SECRET + "\"}"));
        try (server) {
            OfficialQqTokenManager manager = manager(
                    server, Clock.systemUTC(), event -> events.add(event));

            assertThatThrownBy(manager::authorizationHeader)
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> {
                                assertThat(failure.kind()).isEqualTo(OfficialQqErrorKind.BUSINESS);
                                assertThat(failure.errorCode()).isEqualTo("100016");
                                assertThat(failure.traceId()).isEqualTo("trace-1");
                                assertThat(failure.getMessage()).doesNotContain(APP_SECRET);
                                assertThat(failure.toString()).doesNotContain(APP_SECRET);
                            });
            assertThat(events).hasSize(1);
            assertThat(events.get(0).success()).isFalse();
            assertThat(events.toString()).doesNotContain(APP_SECRET).doesNotContain("tok-1");
        }
    }

    @Test
    void httpAndMalformedResponsesAreClassifiedWithoutResponseBodies() throws Exception {
        FakeQqHttpServer httpError = new FakeQqHttpServer((request, index) ->
                FakeQqHttpServer.Reply.statusJson(503, "{\"trace_id\":\"trace-503\",\"message\":\""
                        + APP_SECRET + "\"}"));
        try (httpError) {
            assertThatThrownBy(manager(httpError, Clock.systemUTC())::authorizationHeader)
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> {
                                assertThat(failure.kind()).isEqualTo(OfficialQqErrorKind.SERVER);
                                assertThat(failure.httpStatus()).isEqualTo(503);
                                assertThat(failure.traceId()).isEqualTo("trace-503");
                                assertThat(failure.getMessage()).doesNotContain(APP_SECRET);
                            });
        }

        FakeQqHttpServer nonJson = new FakeQqHttpServer((request, index) ->
                new FakeQqHttpServer.Reply(200, "not-json", Map.of()));
        try (nonJson) {
            assertThatThrownBy(manager(nonJson, Clock.systemUTC())::authorizationHeader)
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> assertThat(failure.kind())
                                    .isEqualTo(OfficialQqErrorKind.MALFORMED_RESPONSE));
        }

        FakeQqHttpServer missingToken = new FakeQqHttpServer((request, index) ->
                FakeQqHttpServer.Reply.json("{\"expires_in\":7200}"));
        try (missingToken) {
            assertThatThrownBy(manager(missingToken, Clock.systemUTC())::authorizationHeader)
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> {
                                assertThat(failure.kind())
                                        .isEqualTo(OfficialQqErrorKind.MALFORMED_RESPONSE);
                                assertThat(failure.getMessage()).contains("access_token");
                            });
        }
    }

    @Test
    void aRejectedTokenIsRefreshedOnlyOnceAndConcurrentStaleRejectionsReuseIt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) -> {
            int call = calls.incrementAndGet();
            return FakeQqHttpServer.Reply.json(
                    "{\"access_token\":\"tok-" + call + "\",\"expires_in\":7200}");
        });
        try (server) {
            OfficialQqTokenManager manager = manager(server, Clock.systemUTC());
            String first = manager.authorizationHeader();
            assertThat(first).isEqualTo("QQBot tok-1");

            String second = manager.authorizationHeaderAfterRejected(first);
            assertThat(second).isEqualTo("QQBot tok-2");
            assertThat(calls).hasValue(2);

            // A second caller that still holds tok-1 must reuse tok-2, not start another refresh.
            assertThat(manager.authorizationHeaderAfterRejected(first)).isEqualTo("QQBot tok-2");
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void closingDuringInFlightRefreshRejectsOwnerAndWaiterWithoutReturningToken() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) -> {
            calls.incrementAndGet();
            requestStarted.countDown();
            assertThat(releaseResponse.await(5, TimeUnit.SECONDS)).isTrue();
            return FakeQqHttpServer.Reply.json(
                    "{\"access_token\":\"tok-1\",\"expires_in\":7200}");
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        OfficialQqTokenManager manager = manager(server, Clock.systemUTC());
        try {
            Future<String> owner = workers.submit(manager::authorizationHeader);
            Future<String> waiter = workers.submit(manager::authorizationHeader);
            assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue();

            manager.close();
            releaseResponse.countDown();

            assertClosedFailure(catchThrowable(() -> owner.get(5, TimeUnit.SECONDS)));
            assertClosedFailure(catchThrowable(() -> waiter.get(5, TimeUnit.SECONDS)));
            assertThat(calls).hasValue(1);
            assertClosedFailure(catchThrowable(manager::authorizationHeader));
            assertThat(server.countPath(TOKEN_PATH)).isEqualTo(1);
            assertClosedFailure(catchThrowable(manager::accessToken));
        } finally {
            releaseResponse.countDown();
            manager.close();
            workers.shutdownNow();
            server.close();
        }
    }

    @Test
    void oversizedTokenResponseFailsBeforeBufferingTheWholeBody() throws Exception {
        String huge = "x".repeat(OfficialQqResponseBody.MAX_RESPONSE_BYTES + 1);
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) ->
                FakeQqHttpServer.Reply.json(huge));
        try (server) {
            assertThatThrownBy(manager(server, Clock.systemUTC())::authorizationHeader)
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> {
                                assertThat(failure.kind())
                                        .isEqualTo(OfficialQqErrorKind.MALFORMED_RESPONSE);
                                assertThat(failure.getMessage()).doesNotContain(huge);
                            });
        }
    }

    @Test
    void aStalledTokenBodyHitsTheOverallDeadlineAndReturnsTimeout() throws Exception {
        StallingQqHttpServer server =
                new StallingQqHttpServer(TOKEN_PATH, 3_000L, false);
        try {
            OfficialQqTokenManager manager = manager(
                    server.baseUri(),
                    Clock.systemUTC(),
                    Duration.ofMillis(250),
                    event -> { });
            long startedNanos = System.nanoTime();
            assertThatThrownBy(manager::authorizationHeader)
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
    void redirectsAreNeverFollowedForTheTokenEndpoint() throws Exception {
        FakeQqHttpServer server = new FakeQqHttpServer((request, index) ->
                new FakeQqHttpServer.Reply(
                        307, "{}", Map.of("Location", "/redirect-target")));
        try (server) {
            assertThatThrownBy(manager(server, Clock.systemUTC())::authorizationHeader)
                    .isInstanceOfSatisfying(
                            OfficialQqApiException.class,
                            failure -> {
                                assertThat(failure.httpStatus()).isEqualTo(307);
                                assertThat(failure.kind()).isEqualTo(OfficialQqErrorKind.HTTP);
                            });
            assertThat(server.countPath(TOKEN_PATH)).isEqualTo(1);
            assertThat(server.countPath("/redirect-target")).isZero();
        }
    }

    @Test
    void productionFactoryRejectsRedirectFollowingClients() throws Exception {
        OfficialQqCredential credential = credential();
        HttpClient redirecting = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        assertThatThrownBy(() -> OfficialQqTokenManager.production(
                        "acc-1",
                        credential,
                        redirecting,
                        new ObjectMapper(),
                        Clock.systemUTC(),
                        Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("followRedirects=NEVER");
    }

    @Test
    void legacyAndTestFactoriesSeparateProductionFromLoopbackOnly() throws Exception {
        OfficialQqCredential credential = credential();
        HttpClient client = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.systemUTC();

        assertThatThrownBy(() -> new OfficialQqTokenManager(
                        "acc-1",
                        credential,
                        URI.create("https://evil.example"),
                        client,
                        mapper,
                        clock,
                        Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api.bot.qq.com");
        assertThatThrownBy(() -> OfficialQqTokenManager.forLoopbackTesting(
                        "acc-1",
                        credential,
                        URI.create("http://example.com"),
                        client,
                        mapper,
                        clock,
                        Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("回环");
    }

    private static void assertClosedFailure(Throwable thrown) {
        Throwable current = thrown;
        while (current != null && !(current instanceof OfficialQqApiException)) {
            current = current.getCause();
        }
        assertThat(current)
                .isInstanceOfSatisfying(
                        OfficialQqApiException.class,
                        failure -> {
                            assertThat(failure.kind())
                                    .isEqualTo(OfficialQqErrorKind.CONFIGURATION);
                            assertThat(failure.getMessage()).contains("已关闭").doesNotContain("tok-1");
                        });
    }

    private static OfficialQqCredential credential() throws Exception {
        return new OfficialQqCredentialResolver(
                        "acc-1", "app-1", "BMA3_TEST_SECRET", null,
                        Map.of("BMA3_TEST_SECRET", APP_SECRET))
                .resolve();
    }

    private static OfficialQqTokenManager manager(FakeQqHttpServer server, Clock clock) throws Exception {
        return manager(server.baseUri(), clock, Duration.ofSeconds(2), event -> { });
    }

    private static OfficialQqTokenManager manager(
            FakeQqHttpServer server,
            Clock clock,
            io.mosire.bma3.qqofficial.api.OfficialQqHttpObserver observer)
            throws Exception {
        return manager(server.baseUri(), clock, Duration.ofSeconds(2), observer);
    }

    private static OfficialQqTokenManager manager(
            URI baseUri,
            Clock clock,
            Duration requestTimeout,
            io.mosire.bma3.qqofficial.api.OfficialQqHttpObserver observer)
            throws Exception {
        OfficialQqCredential credential = credential();
        return OfficialQqTokenManager.forLoopbackTesting(
                "acc-1",
                credential,
                baseUri,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(),
                clock,
                requestTimeout,
                Duration.ofSeconds(60),
                observer);
    }
}
