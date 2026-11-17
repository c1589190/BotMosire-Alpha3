package io.mosire.bma3.qqofficial.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OfficialQqGatewayClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void helloIdentifyReadyHeartbeatAckAndDispatchAreSequenced() throws Exception {
        Harness harness = new Harness();
        harness.start();

        assertThat(harness.status().state()).isEqualTo(GatewayState.CONNECTING);
        assertThat(harness.factory.created).hasSize(1);
        FakeTransport transport = harness.transport();

        transport.onOpen();
        transport.hello(1_000L);
        assertThat(harness.status().state()).isEqualTo(GatewayState.IDENTIFYING);

        JsonNode identify = transport.lastSentJson();
        assertThat(identify.path("op").asInt()).isEqualTo(2);
        assertThat(identify.path("d").path("token").asText())
                .isEqualTo("QQBot test-access-token");
        assertThat(identify.path("d").path("intents").asInt())
                .isEqualTo(GatewayOptions.GROUP_AND_C2C_EVENT);
        assertThat(identify.path("d").path("shard").toString()).isEqualTo("[0,1]");
        assertThat(identify.path("d").path("properties").path("$browser").asText())
                .isEqualTo("bma3");

        transport.dispatch("READY", "event-ready", 1L,
                "{\"session_id\":\"session-1\",\"user\":{\"id\":\"bot\"}}");
        assertThat(harness.status().state()).isEqualTo(GatewayState.READY);
        assertThat(harness.status().connected()).isTrue();
        assertThat(harness.status().lastSequence()).isEqualTo(1L);
        assertThat(harness.status().resumable()).isTrue();
        assertThat(harness.status().toString())
                .doesNotContain("test-access-token", "session-1");

        assertThat(harness.events).hasSize(1);
        GatewayDispatch ready = harness.events.getFirst();
        assertThat(ready.eventType()).isEqualTo("READY");
        assertThat(ready.eventId()).isEqualTo("event-ready");
        assertThat(ready.sequence()).isEqualTo(1L);
        assertThat(ready.data().path("session_id").asText()).isEqualTo("session-1");

        assertThat(harness.scheduler.runNext()).isTrue();
        JsonNode heartbeat = transport.lastSentJson();
        assertThat(heartbeat.path("op").asInt()).isEqualTo(1);
        assertThat(heartbeat.path("d").asLong()).isEqualTo(1L);

        transport.ack();
        transport.dispatch("C2C_MESSAGE_CREATE", "event-2", 2L,
                "{\"id\":\"msg\",\"content\":\"body-never-logged\"}");
        assertThat(harness.events).hasSize(2);
        assertThat(harness.status().lastSequence()).isEqualTo(2L);

        assertThat(harness.scheduler.runNext()).isTrue();
        JsonNode secondHeartbeat = transport.lastSentJson();
        assertThat(secondHeartbeat.path("op").asInt()).isEqualTo(1);
        assertThat(secondHeartbeat.path("d").asLong()).isEqualTo(2L);

        harness.client.close();
        assertThat(harness.status().state()).isEqualTo(GatewayState.CLOSED);
        assertThat(transport.closed).isTrue();
        assertThat(harness.scheduler.activeTaskCount()).isZero();
    }

    @Test
    void recoverableCloseRetainsSessionAndResumesWithLastSequence() {
        Harness harness = new Harness();
        harness.start();
        harness.transport().hello(1_000L);
        harness.transport().dispatch("READY", "ready", 7L,
                "{\"session_id\":\"resume-session\"}");
        assertThat(harness.status().state()).isEqualTo(GatewayState.READY);

        harness.transport().closeFromServer(4008, "rate limited");
        assertThat(harness.status().state()).isEqualTo(GatewayState.RECONNECTING);
        assertThat(harness.status().resumable()).isTrue();
        assertThat(harness.status().lastErrorCode()).isEqualTo("RATE_LIMITED");
        assertThat(harness.status().reconnectAttempts()).isEqualTo(1);
        assertThat(harness.backoff.attempts).containsExactly(1);

        assertThat(harness.scheduler.runNext()).isTrue();
        assertThat(harness.factory.created).hasSize(2);
        FakeTransport resumed = harness.transport();
        resumed.hello(1_000L);

        JsonNode resume = resumed.lastSentJson();
        assertThat(resume.path("op").asInt()).isEqualTo(6);
        assertThat(resume.path("d").path("token").asText())
                .isEqualTo("QQBot test-access-token");
        assertThat(resume.path("d").path("session_id").asText())
                .isEqualTo("resume-session");
        assertThat(resume.path("d").path("seq").asLong()).isEqualTo(7L);

        resumed.dispatch("RESUMED", "resumed", 8L, "{}");
        assertThat(harness.status().state()).isEqualTo(GatewayState.READY);
        assertThat(harness.status().reconnectAttempts()).isZero();
        assertThat(harness.status().lastSequence()).isEqualTo(8L);
    }

    @ParameterizedTest
    @ValueSource(ints = {4006, 4007})
    void nonResumableCloseCodesForceFreshIdentify(int closeCode) {
        Harness harness = new Harness();
        harness.start();
        harness.transport().hello(1_000L);
        harness.transport().dispatch("READY", "ready", 3L,
                "{\"session_id\":\"old-session\"}");
        harness.transport().closeFromServer(closeCode, "invalid");

        assertThat(harness.status().state()).isEqualTo(GatewayState.RECONNECTING);
        assertThat(harness.status().resumable()).isFalse();
        assertThat(harness.scheduler.runNext()).isTrue();
        FakeTransport replacement = harness.transport();
        replacement.hello(1_000L);

        JsonNode identify = replacement.lastSentJson();
        assertThat(identify.path("op").asInt()).isEqualTo(2);
        assertThat(identify.path("d").path("token").asText())
                .isEqualTo("QQBot test-access-token");
        assertThat(identify.path("d").has("session_id")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {4008, 4009})
    void transientCloseCodesBackOffThenResume(int closeCode) {
        Harness harness = new Harness();
        harness.start();
        harness.transport().hello(1_000L);
        harness.transport().dispatch("READY", "ready", 4L,
                "{\"session_id\":\"keep-session\"}");
        harness.transport().closeFromServer(closeCode, "");

        assertThat(harness.status().state()).isEqualTo(GatewayState.RECONNECTING);
        assertThat(harness.status().resumable()).isTrue();
        assertThat(harness.scheduler.lastScheduledDelay()).isEqualTo(Duration.ofMillis(250));
        assertThat(harness.scheduler.runNext()).isTrue();
        FakeTransport replacement = harness.transport();
        replacement.hello(1_000L);

        JsonNode resume = replacement.lastSentJson();
        assertThat(resume.path("op").asInt()).isEqualTo(6);
        assertThat(resume.path("d").path("session_id").asText())
                .isEqualTo("keep-session");
        assertThat(resume.path("d").path("seq").asLong()).isEqualTo(4L);
    }

    @ParameterizedTest
    @ValueSource(ints = {4014, 4914, 4915})
    void terminalCloseCodesStopWithoutAnotherConnection(int closeCode) {
        Harness harness = new Harness();
        harness.start();
        harness.transport().hello(1_000L);
        harness.transport().dispatch("READY", "ready", 1L,
                "{\"session_id\":\"terminal-session\"}");

        harness.transport().closeFromServer(closeCode, "terminal");

        assertThat(harness.status().state()).isEqualTo(GatewayState.DOWN);
        assertThat(harness.status().terminal()).isTrue();
        assertThat(harness.status().terminalReason()).isNotBlank();
        assertThat(harness.status().lastCloseCode()).isEqualTo(closeCode);
        assertThat(harness.status().lastErrorCode()).isNotBlank();
        assertThat(harness.scheduler.activeTaskCount()).isZero();
        assertThat(harness.scheduler.runNext()).isFalse();
        assertThat(harness.factory.created).hasSize(1);

        harness.client.close();
        assertThat(harness.status().state()).isEqualTo(GatewayState.CLOSED);
    }

    @Test
    void missingHeartbeatAckTimesOutAndReconnects() {
        Harness harness = new Harness();
        harness.start();
        harness.transport().hello(1_000L);
        harness.transport().dispatch("READY", "ready", 5L,
                "{\"session_id\":\"heartbeat-session\"}");

        assertThat(harness.scheduler.runNext()).isTrue(); // first heartbeat
        assertThat(harness.transport().lastSentJson().path("op").asInt()).isEqualTo(1);
        assertThat(harness.scheduler.runNext()).isTrue(); // ACK timeout

        assertThat(harness.status().state()).isEqualTo(GatewayState.RECONNECTING);
        assertThat(harness.status().lastErrorCode()).isEqualTo("HEARTBEAT_ACK_TIMEOUT");
        assertThat(harness.status().resumable()).isTrue();

        assertThat(harness.scheduler.runNext()).isTrue();
        FakeTransport replacement = harness.transport();
        replacement.hello(1_000L);
        assertThat(replacement.lastSentJson().path("op").asInt()).isEqualTo(6);
    }

    @Test
    void helloDeliveredSynchronouslyByTransportFactoryStillGetsIdentifyFrame() {
        FakeScheduler scheduler = new FakeScheduler();
        List<GatewayDispatch> events = new CopyOnWriteArrayList<>();
        AtomicReference<FakeTransport> created = new AtomicReference<>();
        OfficialQqGatewayTransport.Factory factory = (uri, listener) -> {
            FakeTransport transport = new FakeTransport(uri, listener);
            created.set(transport);
            // Worst-case legal callback ordering: Hello is emitted before open() returns.
            listener.onText("{\"op\":10,\"d\":{\"heartbeat_interval\":1000}}");
            return transport;
        };
        try (OfficialQqGatewayClient client = OfficialQqGatewayClient.builder(
                        GatewayOptions.defaults("sync-factory"),
                        () -> "test-access-token",
                        () -> URI.create("wss://gateway.example.test/websocket"))
                .transportFactory(factory)
                .eventSink(events::add)
                .scheduler(scheduler)
                .backoff(attempt -> Duration.ofMillis(250))
                .build()) {
            client.start();
            assertThat(client.status().state()).isEqualTo(GatewayState.IDENTIFYING);
            assertThat(created.get().sent).hasSize(1);
            assertThat(created.get().lastSentJson().path("op").asInt()).isEqualTo(2);
            assertThat(events).isEmpty();
        } finally {
            scheduler.close();
        }
    }

    @Test
    void opcodeSevenReconnectsByResumingTheSavedSession() {
        Harness harness = new Harness();
        harness.start();
        harness.transport().hello(1_000L);
        harness.transport().dispatch("READY", "ready", 11L,
                "{\"session_id\":\"opcode7-session\"}");

        harness.transport().receive("{\"op\":7}");
        assertThat(harness.status().state()).isEqualTo(GatewayState.RECONNECTING);
        assertThat(harness.status().resumable()).isTrue();
        assertThat(harness.status().lastErrorCode()).isEqualTo("RECONNECT_OPCODE");
        assertThat(harness.scheduler.runNext()).isTrue();

        FakeTransport replacement = harness.transport();
        replacement.hello(1_000L);
        JsonNode resume = replacement.lastSentJson();
        assertThat(resume.path("op").asInt()).isEqualTo(6);
        assertThat(resume.path("d").path("session_id").asText())
                .isEqualTo("opcode7-session");
        assertThat(resume.path("d").path("seq").asLong()).isEqualTo(11L);
    }

    @Test
    void opcodeNineFalseClearsSessionAndTrueKeepsIt() {
        Harness clearHarness = new Harness();
        clearHarness.start();
        clearHarness.transport().hello(1_000L);
        clearHarness.transport().dispatch("READY", "ready", 2L,
                "{\"session_id\":\"clear-me\"}");
        clearHarness.transport().opcode9(false);
        assertThat(clearHarness.status().resumable()).isFalse();
        assertThat(clearHarness.scheduler.runNext()).isTrue();
        clearHarness.transport().hello(1_000L);
        assertThat(clearHarness.transport().lastSentJson().path("op").asInt()).isEqualTo(2);

        Harness keepHarness = new Harness();
        keepHarness.start();
        keepHarness.transport().hello(1_000L);
        keepHarness.transport().dispatch("READY", "ready", 9L,
                "{\"session_id\":\"keep-me\"}");
        keepHarness.transport().opcode9(true);
        assertThat(keepHarness.status().resumable()).isTrue();
        assertThat(keepHarness.scheduler.runNext()).isTrue();
        keepHarness.transport().hello(1_000L);
        JsonNode resume = keepHarness.transport().lastSentJson();
        assertThat(resume.path("op").asInt()).isEqualTo(6);
        assertThat(resume.path("d").path("session_id").asText()).isEqualTo("keep-me");
    }

    @Test
    void duplicateStartIsRejectedAndCloseSuppressesCallbacksAndReconnects() {
        Harness harness = new Harness();
        harness.start();
        assertThatThrownBy(harness.client::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already started");

        FakeTransport first = harness.transport();
        harness.client.close();
        assertThat(first.closed).isTrue();
        assertThat(harness.status().state()).isEqualTo(GatewayState.CLOSED);

        first.dispatch("READY", "ignored", 1L, "{\"session_id\":\"ignored\"}");
        first.closeFromServer(4008, "ignored");
        assertThat(harness.status().state()).isEqualTo(GatewayState.CLOSED);
        assertThat(harness.scheduler.runNext()).isFalse();
        assertThat(harness.factory.created).hasSize(1);
    }

    @Test
    void credentialsAndCloseReasonsNeverAppearInStatus() {
        Harness harness = new Harness();
        harness.start();
        harness.transport().hello(1_000L);
        harness.transport().dispatch("READY", "ready", 1L,
                "{\"session_id\":\"private-session\"}");
        harness.transport().closeFromServer(4914, "reason with secret value");
        GatewayStatus status = harness.status();

        assertThat(status.toString())
                .doesNotContain("test-access-token", "private-session",
                        "reason with secret value");
        assertThat(status.lastErrorType()).isEmpty();
    }

    @Test
    void sinkFailureKeepsLastCommittedSequenceAndResumeReplaysUncommittedEvent() {
        AtomicInteger c2cAttempts = new AtomicInteger();
        List<GatewayDispatch> delivered = new CopyOnWriteArrayList<>();
        Harness harness = new Harness(dispatch -> {
            if ("C2C_MESSAGE_CREATE".equals(dispatch.eventType())
                    && c2cAttempts.getAndIncrement() == 0) {
                throw new IllegalStateException("sink failed with private detail");
            }
            delivered.add(dispatch);
        });
        harness.start();
        harness.transport().hello(1_000L);
        harness.transport().dispatch("READY", "ready", 5L,
                "{\"session_id\":\"commit-session\"}");
        harness.transport().dispatch("C2C_MESSAGE_CREATE", "event-5", 6L,
                "{\"content\":\"private body\"}");

        assertThat(harness.status().state()).isEqualTo(GatewayState.RECONNECTING);
        assertThat(harness.status().connected()).isFalse();
        assertThat(harness.status().lastSequence()).isEqualTo(5L);
        assertThat(harness.status().resumable()).isTrue();
        assertThat(harness.status().lastErrorCode()).isEqualTo("EVENT_SINK_FAILED");
        assertThat(harness.status().lastErrorType()).isEqualTo("IllegalStateException");
        assertThat(harness.status().toString())
                .doesNotContain("private detail", "private body");

        assertThat(harness.scheduler.runNext()).isTrue();
        FakeTransport replacement = harness.transport();
        replacement.hello(1_000L);
        JsonNode resume = replacement.lastSentJson();
        assertThat(resume.path("op").asInt()).isEqualTo(6);
        assertThat(resume.path("d").path("seq").asLong()).isEqualTo(5L);

        replacement.dispatch("RESUMED", "resumed", 5L, "{}");
        assertThat(harness.status().state()).isEqualTo(GatewayState.READY);
        replacement.dispatch("C2C_MESSAGE_CREATE", "event-5", 6L,
                "{\"content\":\"private body\"}");

        assertThat(harness.status().lastSequence()).isEqualTo(6L);
        assertThat(delivered.stream().map(GatewayDispatch::eventId))
                .containsExactly("ready", "resumed", "event-5");
    }

    @Test
    void readySinkFailureNeverClaimsReadyAndReidentifies() {
        AtomicInteger readyAttempts = new AtomicInteger();
        Harness harness = new Harness(dispatch -> {
            if ("READY".equals(dispatch.eventType())
                    && readyAttempts.getAndIncrement() == 0) {
                throw new IllegalArgumentException("ready sink detail");
            }
        });
        harness.start();
        harness.transport().hello(1_000L);
        harness.transport().dispatch("READY", "ready-1", 1L,
                "{\"session_id\":\"not-committed\"}");

        assertThat(harness.status().state()).isEqualTo(GatewayState.RECONNECTING);
        assertThat(harness.status().connected()).isFalse();
        assertThat(harness.status().resumable()).isFalse();
        assertThat(harness.status().lastSequence()).isNull();

        assertThat(harness.scheduler.runNext()).isTrue();
        FakeTransport replacement = harness.transport();
        replacement.hello(1_000L);
        assertThat(replacement.lastSentJson().path("op").asInt()).isEqualTo(2);
        assertThat(replacement.lastSentJson().path("d").has("session_id")).isFalse();

        replacement.dispatch("READY", "ready-2", 1L,
                "{\"session_id\":\"committed\"}");
        assertThat(harness.status().state()).isEqualTo(GatewayState.READY);
        assertThat(harness.status().lastSequence()).isEqualTo(1L);
    }

    @Test
    void resumedSinkFailureResumesAgainFromPreviousCommittedSequence() {
        AtomicInteger resumedAttempts = new AtomicInteger();
        Harness harness = new Harness(dispatch -> {
            if ("RESUMED".equals(dispatch.eventType())
                    && resumedAttempts.getAndIncrement() == 0) {
                throw new IllegalStateException("resumed sink detail");
            }
        });
        harness.start();
        harness.transport().hello(1_000L);
        harness.transport().dispatch("READY", "ready", 5L,
                "{\"session_id\":\"resume-failure-session\"}");
        harness.transport().closeFromServer(4008, "recover");
        assertThat(harness.scheduler.runNext()).isTrue();
        harness.transport().hello(1_000L);
        harness.transport().dispatch("RESUMED", "resumed-1", 5L, "{}");

        assertThat(harness.status().state()).isEqualTo(GatewayState.RECONNECTING);
        assertThat(harness.status().lastSequence()).isEqualTo(5L);
        assertThat(harness.status().resumable()).isTrue();

        assertThat(harness.scheduler.runNext()).isTrue();
        harness.transport().hello(1_000L);
        JsonNode resume = harness.transport().lastSentJson();
        assertThat(resume.path("op").asInt()).isEqualTo(6);
        assertThat(resume.path("d").path("seq").asLong()).isEqualTo(5L);
    }

    @Test
    void permanentTokenFailureGoesDownWithoutMoreConnections() {
        FakeScheduler scheduler = new FakeScheduler();
        FakeFactory factory = new FakeFactory();
        AtomicInteger tokenCalls = new AtomicInteger();
        try (OfficialQqGatewayClient client = OfficialQqGatewayClient.builder(
                        GatewayOptions.defaults("permanent-token"),
                        () -> {
                            tokenCalls.incrementAndGet();
                            throw new GatewayPermanentException("AUTH_CONFIG_INVALID",
                                    new IllegalStateException("raw secret token detail"));
                        },
                        () -> URI.create("wss://fake.example.test/websocket"))
                .transportFactory(factory)
                .scheduler(scheduler)
                .backoff(attempt -> Duration.ofMillis(250))
                .build()) {
            client.start();
            factory.created.getFirst().hello(1_000L);

            assertThat(client.status().state()).isEqualTo(GatewayState.DOWN);
            assertThat(client.status().lastErrorCode()).isEqualTo("AUTH_CONFIG_INVALID");
            assertThat(client.status().toString()).doesNotContain("raw secret token detail");
            assertThat(tokenCalls).hasValue(1);
            assertThat(factory.created).hasSize(1);
            assertThat(scheduler.runNext()).isFalse();
        }
    }

    @Test
    void uriNetworkFailureRetriesButPermanentConfigFailureDoesNot() {
        FakeScheduler retryScheduler = new FakeScheduler();
        AtomicInteger uriAttempts = new AtomicInteger();
        try (OfficialQqGatewayClient retrying = OfficialQqGatewayClient.builder(
                        GatewayOptions.defaults("retry-uri"),
                        () -> "test-access-token",
                        () -> {
                            uriAttempts.incrementAndGet();
                            throw new IOException("network detail");
                        })
                .transportFactory((uri, listener) -> new FakeTransport(uri, listener))
                .scheduler(retryScheduler)
                .backoff(attempt -> Duration.ofMillis(250))
                .build()) {
            retrying.start();
            assertThat(retrying.status().state()).isEqualTo(GatewayState.RECONNECTING);
            assertThat(retrying.status().terminal()).isFalse();
            assertThat(retryScheduler.runNext()).isTrue();
            assertThat(uriAttempts).hasValue(2);
            assertThat(retrying.status().state()).isEqualTo(GatewayState.RECONNECTING);
        }

        FakeScheduler permanentScheduler = new FakeScheduler();
        try (OfficialQqGatewayClient permanent = OfficialQqGatewayClient.builder(
                        GatewayOptions.defaults("permanent-uri"),
                        () -> "test-access-token",
                        () -> {
                            throw new GatewayPermanentException("INVALID_GATEWAY_CONFIG");
                        })
                .transportFactory((uri, listener) -> new FakeTransport(uri, listener))
                .scheduler(permanentScheduler)
                .backoff(attempt -> Duration.ofMillis(250))
                .build()) {
            permanent.start();
            assertThat(permanent.status().state()).isEqualTo(GatewayState.DOWN);
            assertThat(permanent.status().lastErrorCode()).isEqualTo("INVALID_GATEWAY_CONFIG");
            assertThat(permanentScheduler.runNext()).isFalse();
        }
    }

    @Test
    void injectedFailureClassifierCanMakeUriFailurePermanent() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicBoolean classifierCalled = new AtomicBoolean();
        try (OfficialQqGatewayClient client = OfficialQqGatewayClient.builder(
                        GatewayOptions.defaults("custom-classifier"),
                        () -> "test-access-token",
                        () -> {
                            throw new IOException("network");
                        })
                .transportFactory((uri, listener) -> new FakeTransport(uri, listener))
                .failureClassifier(failure -> {
                    classifierCalled.set(true);
                    return new GatewayFailureClassifier.Classification(
                            GatewayFailureClassifier.Disposition.PERMANENT, "CUSTOM_CONFIG");
                })
                .scheduler(scheduler)
                .backoff(attempt -> Duration.ofMillis(250))
                .build()) {
            client.start();
            assertThat(classifierCalled).isTrue();
            assertThat(client.status().state()).isEqualTo(GatewayState.DOWN);
            assertThat(client.status().lastErrorCode()).isEqualTo("CUSTOM_CONFIG");
            assertThat(scheduler.runNext()).isFalse();
        }
    }

    @Test
    @SuppressWarnings("try")
    void closeDuringUriResolutionCannotOpenTransportOrCallTokenProvider() throws Exception {
        CountDownLatch uriEntered = new CountDownLatch(1);
        CountDownLatch releaseUri = new CountDownLatch(1);
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicInteger factoryCalls = new AtomicInteger();
        try (OfficialQqGatewayClient client = OfficialQqGatewayClient.builder(
                        GatewayOptions.defaults("close-uri-race"),
                        () -> {
                            tokenCalls.incrementAndGet();
                            return "test-access-token";
                        },
                        () -> {
                            uriEntered.countDown();
                            awaitUninterruptibly(releaseUri);
                            return URI.create("wss://fake.example.test/websocket");
                        })
                .transportFactory((uri, listener) -> {
                    factoryCalls.incrementAndGet();
                    return new FakeTransport(uri, listener);
                })
                .scheduler(scheduler)
                .backoff(attempt -> Duration.ofMillis(250))
                .build()) {
            Thread starter = Thread.ofPlatform().start(client::start);
            assertThat(uriEntered.await(2, TimeUnit.SECONDS)).isTrue();
            client.close();
            assertThat(client.status().state()).isEqualTo(GatewayState.CLOSED);
            releaseUri.countDown();
            starter.join(2_000L);

            assertThat(starter.isAlive()).isFalse();
            assertThat(factoryCalls).hasValue(0);
            assertThat(tokenCalls).hasValue(0);
            assertThat(scheduler.activeTaskCount()).isZero();
        }
    }

    @Test
    @SuppressWarnings("try")
    void closeDuringTokenAcquisitionCannotSendAuthOrDeliverEvents() throws Exception {
        CountDownLatch tokenEntered = new CountDownLatch(1);
        CountDownLatch releaseToken = new CountDownLatch(1);
        FakeScheduler scheduler = new FakeScheduler();
        FakeFactory factory = new FakeFactory();
        AtomicInteger tokenCalls = new AtomicInteger();
        try (OfficialQqGatewayClient client = OfficialQqGatewayClient.builder(
                        GatewayOptions.defaults("close-token-race"),
                        () -> {
                            tokenCalls.incrementAndGet();
                            tokenEntered.countDown();
                            awaitUninterruptibly(releaseToken);
                            return "test-access-token";
                        },
                        () -> URI.create("wss://fake.example.test/websocket"))
                .transportFactory(factory)
                .scheduler(scheduler)
                .backoff(attempt -> Duration.ofMillis(250))
                .build()) {
            client.start();
            FakeTransport transport = factory.created.getFirst();
            Thread hello = Thread.ofPlatform().start(() -> transport.hello(1_000L));
            assertThat(tokenEntered.await(2, TimeUnit.SECONDS)).isTrue();

            client.close();
            releaseToken.countDown();
            hello.join(2_000L);

            assertThat(hello.isAlive()).isFalse();
            assertThat(transport.sent).isEmpty();
            assertThat(tokenCalls).hasValue(1);
            transport.deliverEvenIfClosed(
                    "{\"op\":10,\"d\":{\"heartbeat_interval\":1000}}");
            assertThat(tokenCalls).hasValue(1);
            assertThat(client.status().state()).isEqualTo(GatewayState.CLOSED);
            assertThat(scheduler.activeTaskCount()).isZero();
        }
    }

    @Test
    void closeWaitsForInFlightDispatchThenSuppressesFurtherDelivery() throws Exception {
        CountDownLatch sinkEntered = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        Harness harness = new Harness(dispatch -> {
            if ("C2C_MESSAGE_CREATE".equals(dispatch.eventType())) {
                sinkEntered.countDown();
                awaitUninterruptibly(releaseSink);
            }
            deliveries.incrementAndGet();
        });
        harness.start();
        harness.transport().hello(1_000L);
        harness.transport().dispatch("READY", "ready", 1L,
                "{\"session_id\":\"dispatch-race\"}");
        FakeTransport transport = harness.transport();

        Thread eventThread = Thread.ofPlatform().start(() ->
                transport.dispatch("C2C_MESSAGE_CREATE", "event-race", 2L,
                        "{\"content\":\"race body\"}"));
        assertThat(sinkEntered.await(2, TimeUnit.SECONDS)).isTrue();

        Thread closer = Thread.ofPlatform().start(() -> {
            harness.client.close();
            closeReturned.countDown();
        });
        Thread.sleep(100L);
        assertThat(closeReturned.getCount()).isEqualTo(1L);

        releaseSink.countDown();
        eventThread.join(2_000L);
        closer.join(2_000L);

        assertThat(closeReturned.getCount()).isZero();
        assertThat(harness.client.status().state()).isEqualTo(GatewayState.CLOSED);
        int deliveredBeforeClose = deliveries.get();
        assertThat(deliveredBeforeClose).isEqualTo(2);

        transport.deliverEvenIfClosed("{\"op\":0,\"id\":\"late\",\"t\":\"C2C_MESSAGE_CREATE\","
                + "\"s\":3,\"d\":{\"content\":\"late body\"}}");
        assertThat(deliveries).hasValue(deliveredBeforeClose);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", interrupted);
        }
    }

    private static final class Harness {
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeFactory factory = new FakeFactory();
        final List<GatewayDispatch> events = new CopyOnWriteArrayList<>();
        final FixedBackoff backoff = new FixedBackoff(Duration.ofMillis(250));
        final AtomicReference<String> token = new AtomicReference<>("test-access-token");
        final OfficialQqGatewayClient client;

        Harness() {
            this(null);
        }

        Harness(GatewayEventSink customSink) {
            GatewayOptions options = GatewayOptions.defaults("qq-official-test")
                    .withHeartbeatAckTimeout(Duration.ofSeconds(5));
            GatewayEventSink sink = customSink == null ? events::add : customSink;
            client = OfficialQqGatewayClient.builder(options,
                            () -> token.get(),
                            () -> URI.create("wss://gateway.example.test/websocket"))
                    .transportFactory(factory)
                    .eventSink(sink)
                    .clock(new FixedClock())
                    .scheduler(scheduler)
                    .backoff(backoff)
                    .build();
        }

        void start() {
            client.start();
        }

        FakeTransport transport() {
            return factory.created.getLast();
        }

        GatewayStatus status() {
            return client.status();
        }
    }

    private static final class FixedClock implements GatewayClock {
        private final AtomicLong millis = new AtomicLong(1_700_000_000_000L);

        @Override
        public Instant now() {
            return Instant.ofEpochMilli(millis.getAndIncrement());
        }
    }

    private static final class FixedBackoff implements GatewayBackoff {
        final Duration delay;
        final List<Integer> attempts = new CopyOnWriteArrayList<>();

        FixedBackoff(Duration delay) {
            this.delay = delay;
        }

        @Override
        public Duration delayForAttempt(int attempt) {
            attempts.add(attempt);
            return delay;
        }
    }

    private static final class FakeFactory implements OfficialQqGatewayTransport.Factory {
        final List<FakeTransport> created = new CopyOnWriteArrayList<>();

        @Override
        public OfficialQqGatewayTransport open(URI uri, OfficialQqGatewayTransport.Listener listener) {
            FakeTransport transport = new FakeTransport(uri, listener);
            created.add(transport);
            return transport;
        }
    }

    private static final class FakeTransport implements OfficialQqGatewayTransport {
        final URI uri;
        final OfficialQqGatewayTransport.Listener listener;
        final List<String> sent = new CopyOnWriteArrayList<>();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile RuntimeException sendFailure;

        FakeTransport(URI uri, OfficialQqGatewayTransport.Listener listener) {
            this.uri = uri;
            this.listener = listener;
        }

        @Override
        public CompletionStage<Void> sendText(String text) {
            if (sendFailure != null) {
                return CompletableFuture.failedFuture(sendFailure);
            }
            sent.add(text);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closed.set(true);
        }

        void onOpen() {
            listener.onOpen();
        }

        void hello(long heartbeatIntervalMillis) {
            receive("{\"op\":10,\"d\":{\"heartbeat_interval\":" + heartbeatIntervalMillis + "}}");
        }

        void ack() {
            receive("{\"op\":11,\"d\":null}");
        }

        void opcode9(boolean resumable) {
            receive("{\"op\":9,\"d\":" + resumable + "}");
        }

        void dispatch(String type, String eventId, long sequence, String dataJson) {
            receive("{\"op\":0,\"id\":\"" + eventId + "\",\"t\":\"" + type
                    + "\",\"s\":" + sequence + ",\"d\":" + dataJson + "}");
        }

        void receive(String text) {
            if (!closed.get()) {
                listener.onText(text);
            }
        }

        void deliverEvenIfClosed(String text) {
            listener.onText(text);
        }

        void closeFromServer(int statusCode, String reason) {
            if (!closed.get()) {
                listener.onClose(statusCode, reason);
            }
        }

        JsonNode lastSentJson() {
            try {
                return JSON.readTree(sent.getLast());
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class FakeScheduler implements GatewayScheduler {
        private final PriorityQueue<Scheduled> queue = new PriorityQueue<>(
                Comparator.comparingLong((Scheduled task) -> task.dueAt).thenComparingLong(task -> task.order));
        private final AtomicLong nowNanos = new AtomicLong();
        private final AtomicLong sequence = new AtomicLong();
        private volatile Duration lastScheduledDelay = Duration.ZERO;

        @Override
        public ScheduledTask schedule(Duration delay, Runnable action) {
            lastScheduledDelay = delay;
            Scheduled scheduled = new Scheduled(nowNanos.get() + delay.toNanos(),
                    sequence.getAndIncrement(), action);
            queue.add(scheduled);
            return scheduled;
        }

        boolean runNext() {
            while (!queue.isEmpty()) {
                Scheduled scheduled = queue.poll();
                if (scheduled.cancelled.get()) {
                    continue;
                }
                nowNanos.set(Math.max(nowNanos.get(), scheduled.dueAt));
                try {
                    scheduled.action.run();
                } catch (RuntimeException failure) {
                    throw new AssertionError("scheduled action failed", failure);
                }
                return true;
            }
            return false;
        }

        int activeTaskCount() {
            queue.removeIf(task -> task.cancelled.get());
            return queue.size();
        }

        Duration lastScheduledDelay() {
            return lastScheduledDelay;
        }

        private static final class Scheduled implements ScheduledTask {
            final long dueAt;
            final long order;
            final Runnable action;
            final AtomicBoolean cancelled = new AtomicBoolean();

            Scheduled(long dueAt, long order, Runnable action) {
                this.dueAt = dueAt;
                this.order = order;
                this.action = action;
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        }
    }
}
