import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal OneBot 11 forward-WS endpoint for local e2e: answers actions, forwards injected events
 * from a tiny HTTP port, and can drop the live socket to test watchdog reconnection.
 */
public final class FakeOneBot extends WebSocketServer {

    private static final Pattern ACTION = Pattern.compile("\"action\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ECHO = Pattern.compile("\"echo\"\\s*:\\s*\"([^\"]*)\"");
    private static final AtomicReference<WebSocket> CLIENT = new AtomicReference<>();
    private static final AtomicInteger MESSAGE_ID = new AtomicInteger(7000);

    FakeOneBot(int port) {
        super(new InetSocketAddress("127.0.0.1", port));
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        CLIENT.set(connection);
        System.out.println("EVENT client_open");
        System.out.flush();
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        CLIENT.compareAndSet(connection, null);
        System.out.println("EVENT client_close " + code);
        System.out.flush();
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        if (!message.contains("\"action\"")) {
            return;
        }
        Matcher action = ACTION.matcher(message);
        Matcher echo = ECHO.matcher(message);
        String name = action.find() ? action.group(1) : "";
        String echoValue = echo.find() ? echo.group(1) : "";
        String data;
        switch (name) {
            case "get_login_info" -> data = "{\"user_id\":10001,\"nickname\":\"BMA3E2E\"}";
            case "get_status" -> data = "{\"online\":true,\"good\":true}";
            case "get_group_list" -> data = "[{\"group_id\":955432692,\"group_name\":\"E2E群\","
                    + "\"member_count\":42}]";
            case "get_group_info" -> data = "{\"group_id\":955432692,\"group_name\":\"E2E群\","
                    + "\"member_count\":42}";
            case "get_friend_list" -> data = "[{\"user_id\":132843233,\"nickname\":\"南沐秋\"}]";
            case "get_msg" -> data = "{\"message_id\":1,\"message\":[]}";
            case "get_image" -> data = "{}";
            case "get_group_member_list" -> data = "["
                    + "{\"user_id\":20002,\"nickname\":\"南沐秋\",\"card\":\"群名片小王\","
                    + "\"role\":\"admin\",\"title\":\"大肥鱼\",\"title_expire_time\":0},"
                    + "{\"user_id\":20003,\"nickname\":\"路人\",\"card\":\"\","
                    + "\"role\":\"member\",\"title\":\"\",\"title_expire_time\":0}]";
            case "get_group_member_info" -> data = "{\"user_id\":20002,"
                    + "\"nickname\":\"南沐秋\",\"card\":\"群名片小王\",\"role\":\"admin\","
                    + "\"title\":\"大肥鱼\",\"title_expire_time\":0}";
            case "get_group_system_msg" -> data = "{\"invited_requests\":["
                    + "{\"request_id\":\"777\",\"group_id\":123456,\"group_name\":\"邀请群\","
                    + "\"invitor_uin\":20002,\"invitor_nick\":\"南沐秋\","
                    + "\"message\":\"来玩\",\"checked\":false}],\"join_requests\":[]}";
            case "set_group_add_request" -> data = "{}";
            case "get_forward_msg" -> data = "{\"messages\":["
                    + "{\"sender\":{\"user_id\":20002,\"nickname\":\"A\",\"card\":\"卡A\"},"
                    + "\"time\":1762700000,\"content\":["
                    + "{\"type\":\"text\",\"data\":{\"text\":\"转发内容一\"}},"
                    + "{\"type\":\"forward\",\"data\":{\"id\":\"nested\"}}]},"
                    + "{\"sender\":{\"user_id\":20003,\"nickname\":\"B\"},"
                    + "\"time\":1762700001,\"content\":["
                    + "{\"type\":\"text\",\"data\":{\"text\":\"转发内容二\"}}]}]}";
            case "upload_group_file", "upload_private_file" ->
                    data = "{\"message_id\":" + MESSAGE_ID.incrementAndGet()
                            + ",\"file_id\":\"file-e2e\"}";
            case "get_group_msg_history", "get_friend_msg_history" -> data = "{\"messages\":["
                    + "{\"message_id\":1,\"message_seq\":100,\"time\":1762700000,"
                    + "\"sender\":{\"user_id\":20002,\"nickname\":\"南沐秋\","
                    + "\"card\":\"群名片小王\"},"
                    + "\"message\":[{\"type\":\"text\",\"data\":{\"text\":\"远端历史\"}}]}]}";
            case "llonebot_debug" -> data = "{\"errorCode\":0}";
            case "send_private_msg", "send_group_msg" ->
                    data = "{\"message_id\":" + MESSAGE_ID.incrementAndGet() + "}";
            default -> data = "{}";
        }
        connection.send("{\"status\":\"ok\",\"retcode\":0,\"data\":" + data
                + ",\"echo\":\"" + echoValue + "\"}");
    }

    @Override
    public void onError(WebSocket connection, Exception exception) {
        System.out.println("EVENT error " + exception.getClass().getSimpleName());
        System.out.flush();
    }

    @Override
    public void onStart() {
        System.out.println("FAKE_PORT=" + getPort());
        System.out.flush();
    }

    /**
     * Starts the WS endpoint and a tiny control HTTP port:
     * {@code POST /inject} pushes a raw OneBot event; {@code POST /drop} closes the live socket.
     */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int injectPort = args.length > 1 ? Integer.parseInt(args[1]) : port + 1000;
        FakeOneBot server = new FakeOneBot(port);
        server.start();
        while (server.getPort() <= 0) {
            Thread.sleep(20L);
        }
        HttpServer inject = HttpServer.create(new InetSocketAddress("127.0.0.1", injectPort), 0);
        inject.createContext("/inject", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            WebSocket target = CLIENT.get();
            boolean forwarded = false;
            if (target != null && body.length > 0) {
                target.send(new String(body, StandardCharsets.UTF_8));
                forwarded = true;
            }
            byte[] reply = ("{\"forwarded\":" + forwarded + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(forwarded ? 200 : 503, reply.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(reply);
            }
        });
        inject.createContext("/drop", exchange -> {
            WebSocket target = CLIENT.getAndSet(null);
            boolean dropped = target != null;
            if (target != null) {
                target.close();
            }
            byte[] reply = ("{\"dropped\":" + dropped + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(reply);
            }
        });
        inject.start();
        System.out.println("INJECT_PORT=" + inject.getAddress().getPort());
        System.out.flush();
        Thread.currentThread().join();
    }
}
