package io.github.c1589190.bma3.qqofficial.gateway;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JdkWebSocketGatewayTransportSecurityTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "wss://api.sgroup.qq.com/websocket",
            "wss://sandbox.api.sgroup.qq.com/websocket",
            "wss://q.qq.com/websocket",
            "wss://qq.com/websocket",
            "wss://API.SGROUP.QQ.COM/websocket"
    })
    void acceptsOnlySecureOfficialHosts(String url) {
        assertThatCode(() -> JdkWebSocketGatewayTransport.validateGatewayUri(URI.create(url)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeOrAmbiguousGatewayUrlsBeforeOpeningSocket() {
        List<String> rejected = List.of(
                "ws://api.sgroup.qq.com/websocket",
                "https://api.sgroup.qq.com/websocket",
                "wss://evil.example/websocket",
                "wss://qq.com.evil.example/websocket",
                "wss://evilqq.com/websocket",
                "wss://user:pass@api.sgroup.qq.com/websocket",
                "wss://api.sgroup.qq.com/websocket#fragment",
                "wss://api.sgroup.qq.com:8443/websocket",
                "wss://127.0.0.1/websocket",
                "wss://10.0.0.1/websocket",
                "wss://localhost/websocket",
                "wss://[::1]/websocket");

        for (String url : rejected) {
            assertThatThrownBy(() -> JdkWebSocketGatewayTransport.validateGatewayUri(
                    URI.create(url)))
                    .as("URL must be rejected: %s", url)
                    .isInstanceOf(GatewayPermanentException.class);
        }
    }

    @Test
    void productionFrameLimitMustBePositiveButFakeTransportInjectionIsUnrestricted() {
        assertThatThrownBy(() -> JdkWebSocketGatewayTransport.factory(Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);

        FakeTransportInjectionTestSupport support = new FakeTransportInjectionTestSupport();
        try (OfficialQqGatewayClient client = OfficialQqGatewayClient.builder(
                        GatewayOptions.defaults("fake-nonofficial"),
                        () -> "fake-token",
                        () -> URI.create("wss://fake.invalid/websocket"))
                .transportFactory((uri, listener) -> support.newTransport(uri, listener))
                .scheduler(support.scheduler)
                .backoff(attempt -> Duration.ofMillis(1))
                .build()) {
            client.start();
            support.transport.hello(1_000L);
            assertThatCode(() -> support.transport.lastSentJson()).doesNotThrowAnyException();
        }
    }

    private static final class FakeTransportInjectionTestSupport {
        final FakeTransport transport = new FakeTransport();
        final FakeScheduler scheduler = new FakeScheduler();

        OfficialQqGatewayTransport newTransport(URI uri, OfficialQqGatewayTransport.Listener listener) {
            transport.listener = listener;
            return transport;
        }

        final class FakeTransport implements OfficialQqGatewayTransport {
            OfficialQqGatewayTransport.Listener listener;
            final List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();

            void hello(long interval) {
                listener.onText("{\"op\":10,\"d\":{\"heartbeat_interval\":" + interval + "}}");
            }

            com.fasterxml.jackson.databind.JsonNode lastSentJson() {
                try {
                    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(sent.getLast());
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> sendText(String text) {
                sent.add(text);
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
            }
        }

        final class FakeScheduler implements GatewayScheduler {
            final List<ScheduledTask> tasks = new java.util.concurrent.CopyOnWriteArrayList<>();

            @Override
            public ScheduledTask schedule(Duration delay, Runnable action) {
                ScheduledTask task = () -> {
                };
                tasks.add(task);
                return task;
            }
        }
    }
}
