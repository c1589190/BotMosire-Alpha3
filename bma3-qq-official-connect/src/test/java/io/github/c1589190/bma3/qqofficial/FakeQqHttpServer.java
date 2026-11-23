package io.github.c1589190.bma3.qqofficial;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only fake HTTP server. It never resolves or contacts an external host. */
public final class FakeQqHttpServer implements AutoCloseable {

    @FunctionalInterface
    public interface Responder {
        Reply handle(RecordedRequest request, int index) throws Exception;
    }

    public record RecordedRequest(
            String method,
            String rawPath,
            String rawQuery,
            String authorization,
            String contentType,
            String body) {
    }

    public record Reply(int status, String body, Map<String, String> headers) {
        public static Reply json(String body) {
            return new Reply(200, body, Map.of("Content-Type", "application/json"));
        }

        public static Reply statusJson(int status, String body) {
            return new Reply(status, body, Map.of("Content-Type", "application/json"));
        }
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final Responder responder;
    private final AtomicInteger counter = new AtomicInteger();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    public FakeQqHttpServer(Responder responder) throws IOException {
        this.responder = responder;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public int requestCount() {
        return requests.size();
    }

    public RecordedRequest request(int index) {
        return requests.get(index);
    }

    public long countPath(String rawPath) {
        return requests.stream().filter(request -> request.rawPath().equals(rawPath)).count();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            RecordedRequest recorded = new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    body);
            requests.add(recorded);
            int index = counter.getAndIncrement();
            Reply reply = responder.handle(recorded, index);
            Map<String, String> headers = new LinkedHashMap<>(reply.headers());
            headers.putIfAbsent("Content-Type", "application/json");
            headers.forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
            byte[] responseBody = reply.body() == null
                    ? new byte[0]
                    : reply.body().getBytes(StandardCharsets.UTF_8);
            if (responseBody.length == 0) {
                exchange.sendResponseHeaders(reply.status(), -1L);
            } else {
                exchange.sendResponseHeaders(reply.status(), responseBody.length);
                exchange.getResponseBody().write(responseBody);
            }
        } catch (Exception failure) {
            throw failure instanceof IOException io ? io : new IOException(failure);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    public static List<String> paths(List<RecordedRequest> requests) {
        List<String> paths = new ArrayList<>();
        for (RecordedRequest request : requests) {
            paths.add(request.rawPath());
        }
        return List.copyOf(paths);
    }
}
