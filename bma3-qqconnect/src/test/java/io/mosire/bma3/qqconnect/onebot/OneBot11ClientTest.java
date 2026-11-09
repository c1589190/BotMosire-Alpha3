package io.mosire.bma3.qqconnect.onebot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;

class OneBot11ClientTest {

    @Test
    void connectsReceivesEventsAndCallsActionsOverOneSocket() throws Exception {
        FakeOneBotServer server = new FakeOneBotServer();
        server.start();
        server.awaitStarted(java.time.Duration.ofSeconds(3));
        try {
            List<OneBotEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch eventLatch = new CountDownLatch(1);
            OneBot11Client client = new OneBot11Client(
                    URI.create("ws://127.0.0.1:" + server.getPort()), "test-token",
                    event -> {
                        events.add(event);
                        eventLatch.countDown();
                    });
            try {
                client.start();
                client.awaitReady(Duration.ofSeconds(3));
                assertThat(server.authorization).isEqualTo("Bearer test-token");

                server.sendToClient("""
                        {"post_type":"message","message_type":"private","self_id":10001,
                         "user_id":20002,"message_id":9001,"time":1762700000,
                         "message":[{"type":"text","data":{"text":"ping"}}],
                         "sender":{"nickname":"Alice"}}
                        """);

                assertThat(eventLatch.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(events).singleElement().satisfies(event ->
                        assertThat(event.text()).isEqualTo("ping"));

                var response = client.sendAction("get_login_info", Map.of()).get(3, TimeUnit.SECONDS);
                assertThat(response.path("status").asText()).isEqualTo("ok");
                assertThat(response.path("data").path("user_id").asLong()).isEqualTo(10001L);
            } finally {
                client.close();
            }
        } finally {
            server.stop();
        }
    }

    private static final class FakeOneBotServer extends WebSocketServer {

        private volatile String authorization;
        private volatile WebSocket client;
        private final CountDownLatch started = new CountDownLatch(1);

        FakeOneBotServer() {
            super(new InetSocketAddress("127.0.0.1", 0));
        }

        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) {
            this.client = connection;
            this.authorization = handshake.getFieldValue("Authorization");
        }

        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) {
            this.client = null;
        }

        @Override
        public void onMessage(WebSocket connection, String message) {
            if (message.contains("\"action\":\"get_login_info\"")) {
                connection.send("""
                        {"status":"ok","retcode":0,"data":{"user_id":10001,"nickname":"bot"},"echo":"%s"}
                        """.formatted(echoOf(message)));
            }
        }

        @Override
        public void onError(WebSocket connection, Exception exception) {
        }

        @Override
        public void onStart() {
            started.countDown();
        }

        void awaitStarted(java.time.Duration timeout) {
            try {
                if (!started.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("fake OneBot server 未启动");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 fake OneBot server 被中断", interrupted);
            }
        }

        void sendToClient(String message) {
            WebSocket target = client;
            if (target == null) {
                throw new IllegalStateException("fake OneBot client 尚未连接");
            }
            target.send(message);
        }

        private static String echoOf(String message) {
            int index = message.indexOf("\"echo\":\"");
            if (index < 0) {
                return "";
            }
            int start = index + "\"echo\":\"".length();
            int end = message.indexOf('"', start);
            return message.substring(start, end);
        }
    }
}
