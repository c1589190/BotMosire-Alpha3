package io.mosire.bma3.qqofficial.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback fixture that sends response headers and then stalls the body. */
public final class StallingQqHttpServer implements AutoCloseable {

    private static final String TOKEN_PATH = "/app/getAppAccessToken";

    private final HttpServer server;
    private final ExecutorService executor;
    private final String stallPath;
    private final long stallMillis;
    private final boolean sendPartialBody;
    private final AtomicInteger requests = new AtomicInteger();
    private final CountDownLatch stallStarted = new CountDownLatch(1);
    private final AtomicBoolean interrupted = new AtomicBoolean();

    public StallingQqHttpServer(String stallPath, long stallMillis, boolean sendPartialBody)
            throws IOException {
        this.stallPath = stallPath;
        this.stallMillis = stallMillis;
        this.sendPartialBody = sendPartialBody;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public int requestCount() {
        return requests.get();
    }

    public boolean awaitStallStarted(long timeout, TimeUnit unit) throws InterruptedException {
        return stallStarted.await(timeout, unit);
    }

    public boolean handlerInterrupted() {
        return interrupted.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getRawPath();
            if (path.equals(stallPath)) {
                stall(exchange);
                return;
            }
            if (TOKEN_PATH.equals(path)) {
                byte[] body = "{\"access_token\":\"tok-1\",\"expires_in\":7200}"
                        .getBytes(StandardCharsets.UTF_8);
                respond(exchange, 200, body);
                return;
            }
            if ("/users/@me".equals(path)) {
                respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, 404, "{}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void stall(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (!sendPartialBody) {
            // Chunked response: headers exist, but no body byte is ever produced before the client
            // deadline fires.
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            stallStarted.countDown();
            sleepUntilClosed();
            return;
        }
        exchange.sendResponseHeaders(200, 32);
        OutputStream output = exchange.getResponseBody();
        output.write(' ');
        output.flush();
        stallStarted.countDown();
        sleepUntilClosed();
    }

    private void sleepUntilClosed() {
        try {
            Thread.sleep(stallMillis);
        } catch (InterruptedException interruptedFailure) {
            interrupted.set(true);
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
