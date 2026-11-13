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
    /** 运行时可切换的故障注入开关（走 /control），用来复现远端报的坑。 */
    private static final AtomicReference<Boolean> FAIL_GROUP_LIST = new AtomicReference<>(false);
    private static final AtomicReference<Boolean> TIMEOUT_HISTORY = new AtomicReference<>(false);
    private static final Pattern NUMBER = Pattern.compile("\"(\\w+)\"\\s*:\\s*(\\d+)");

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
        String status = "ok";
        int retcode = 0;
        switch (name) {
            case "get_login_info" -> data = "{\"user_id\":10001,\"nickname\":\"BMA3E2E\"}";
            case "get_status" -> data = "{\"online\":true,\"good\":true}";
            case "get_group_list" -> {
                if (FAIL_GROUP_LIST.get()) {
                    status = "failed";
                    retcode = 120;
                    data = "null";
                } else {
                    data = "[{\"group_id\":955432692,\"group_name\":\"E2E群\","
                            + "\"member_count\":42}]";
                }
            }
            case "get_group_info" -> {
                long gid = number(message, "group_id");
                if (gid == 999) {
                    status = "failed";
                    retcode = 102;
                    data = "null";
                } else {
                    data = "{\"group_id\":955432692,\"group_name\":\"E2E群\","
                            + "\"member_count\":42,\"max_member_count\":200}";
                }
            }
            case "delete_msg" -> {
                long mid = number(message, "message_id");
                if (mid == 999) {
                    status = "failed";
                    retcode = 100;
                    data = "null";
                } else {
                    data = "null";
                }
            }
            case "get_friend_list" -> data = "[{\"user_id\":132843233,\"nickname\":\"南沐秋\"}]";
            case "get_msg" -> data = "{\"message_id\":1,\"message\":[]}";
            case "get_image" -> data = "{}";
            case "get_group_member_list" -> data = "["
                    + "{\"user_id\":10001,\"nickname\":\"BMA3E2E\",\"card\":\"\","
                    + "\"role\":\"admin\"},"
                    + "{\"user_id\":20002,\"nickname\":\"南沐秋\",\"card\":\"群名片小王\","
                    + "\"role\":\"member\"}]";
            case "get_group_member_info" -> data = "{\"user_id\":20002,"
                    + "\"nickname\":\"南沐秋\",\"card\":\"群名片小王\",\"role\":\"member\","
                    + "\"title\":\"群管\"}";
            case "get_group_system_msg" -> data = "{\"invited_requests\":["
                    + "{\"request_id\":11,\"invitor_uin\":20002,"
                    + "\"group_id\":955432692,\"group_name\":\"E2E群\","
                    + "\"message\":\"邀请你加入\",\"checked\":false}]}";
            case "set_group_add_request" -> data = "{}";
            case "get_forward_msg" -> data = "{\"messages\":["
                    + "{\"sender\":{\"user_id\":20002,\"nickname\":\"南沐秋\"},"
                    + "\"time\":1762700000,\"content\":["
                    + "{\"type\":\"text\",\"data\":{\"text\":\"转发内容一\"}}]},"
                    + "{\"sender\":{\"user_id\":20002,\"nickname\":\"南沐秋\"},"
                    + "\"time\":1762700001,\"content\":["
                    + "{\"type\":\"text\",\"data\":{\"text\":\"转发内容二\"}},"
                    + "{\"type\":\"forward\",\"data\":{\"id\":\"fwd-nested-1\"}}]}]}";
            case "upload_group_file", "upload_private_file" ->
                    data = "{\"message_id\":" + MESSAGE_ID.incrementAndGet()
                            + ",\"file_id\":\"file-e2e\"}";
            case "get_group_msg_history", "get_friend_msg_history" -> {
                long count = number(message, "count");
                if (count >= 500) {
                    if (TIMEOUT_HISTORY.get()) {
                        // 模拟远端现象：大页请求 provider 根本不回话
                        System.out.println("EVENT history_timeout count=" + count);
                        System.out.flush();
                        return;
                    }
                    status = "failed";
                    retcode = 1200;
                    data = "null";
                } else {
                    data = "{\"messages\":["
                            + "{\"message_id\":1,\"message_seq\":100,\"time\":1762700000,"
                            + "\"sender\":{\"user_id\":20002,\"nickname\":\"南沐秋\","
                            + "\"card\":\"群名片小王\"},"
                            + "\"message\":[{\"type\":\"text\",\"data\":"
                            + "{\"text\":\"远端历史\"}}]}]}";
                }
            }
            case "llonebot_debug" -> data = "{\"errorCode\":0}";
            case "send_private_msg" -> {
                boolean tempSession = message.contains("\"group_id\"");
                System.out.println("EVENT send_private_msg tempSession=" + tempSession);
                System.out.flush();
                data = "{\"message_id\":" + MESSAGE_ID.incrementAndGet()
                        + (tempSession ? ",\"group_id\":" + number(message, "group_id") : "") + "}";
            }
            case "send_group_msg" -> data = "{\"message_id\":" + MESSAGE_ID.incrementAndGet() + "}";
            default -> data = "{}";
        }
        connection.send("{\"status\":\"" + status + "\",\"retcode\":" + retcode
                + ",\"data\":" + data + ",\"echo\":\"" + echoValue + "\"}");
    }

    /** 从 WS 请求里取一个数字字段（假服务只关心 group_id / message_id / count）。 */
    private static long number(String payload, String key) {
        Matcher matcher = NUMBER.matcher(payload);
        while (matcher.find()) {
            if (key.equals(matcher.group(1))) {
                return Long.parseLong(matcher.group(2));
            }
        }
        return -1;
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
        inject.createContext("/control", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length != 2) {
                        continue;
                    }
                    boolean on = "1".equals(kv[1]) || "true".equalsIgnoreCase(kv[1]);
                    if ("failGroupList".equals(kv[0])) {
                        FAIL_GROUP_LIST.set(on);
                    } else if ("timeoutHistory".equals(kv[0])) {
                        TIMEOUT_HISTORY.set(on);
                    }
                }
            }
            byte[] reply = ("{\"failGroupList\":" + FAIL_GROUP_LIST.get()
                    + ",\"timeoutHistory\":" + TIMEOUT_HISTORY.get() + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
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
