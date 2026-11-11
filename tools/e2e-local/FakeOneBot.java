import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal OneBot 11 forward-WS endpoint: answers actions, forwards injected events from stdin. */
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
            case "get_group_list" -> data = "[{\"group_id\":955432692,\"group_name\":\"E2E群\"}]";
            case "get_friend_list" -> data = "[{\"user_id\":132843233,\"nickname\":\"南沐秋\"}]";
            case "get_msg" -> data = "{\"message_id\":1,\"message\":[]}";
            case "get_image" -> data = "{}";
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
     * Starts the WS endpoint and a tiny {@code POST /inject} HTTP port so a driver can push OneBot
     * events without fighting over a FIFO's EOF semantics.
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
        inject.start();
        System.out.println("INJECT_PORT=" + inject.getAddress().getPort());
        System.out.flush();
        Thread.currentThread().join();
    }
}
