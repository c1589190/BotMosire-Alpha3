package io.mosire.bma3.qqofficial.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared asynchronous HTTP exchange with one end-to-end deadline (headers and body) and a bounded
 * body buffer.
 *
 * <p>{@link HttpRequest#timeout(Duration)} alone does not bound the time spent reading a response
 * body after headers have arrived. This transport waits on the {@code sendAsync} future itself, so
 * the same deadline covers both phases. On timeout or interruption it cancels the body subscription
 * before returning; no background transfer is left holding a stalled stream.</p>
 */
public final class OfficialQqHttpTransport {

    private OfficialQqHttpTransport() {
    }

    public record Response(int statusCode, HttpHeaders headers, byte[] body) {
    }

    public static Response send(
            HttpClient client, HttpRequest request, Duration deadline, int maxBodyBytes)
            throws IOException, InterruptedException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("deadline 必须为正");
        }
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes 必须为正");
        }

        LimitedBodySubscriber subscriber = new LimitedBodySubscriber(maxBodyBytes);
        CompletableFuture<HttpResponse<byte[]>> future =
                client.sendAsync(request, responseInfo -> subscriber);
        try {
            HttpResponse<byte[]> response = future.get(
                    Math.max(1L, deadline.toMillis()), TimeUnit.MILLISECONDS);
            return new Response(
                    response.statusCode(), response.headers(), response.body());
        } catch (TimeoutException timeout) {
            cancel(future, subscriber);
            throw new DeadlineExceededException();
        } catch (InterruptedException interrupted) {
            cancel(future, subscriber);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof OfficialQqResponseBody.TooLargeException tooLarge) {
                throw tooLarge;
            }
            if (cause instanceof HttpTimeoutException) {
                throw new DeadlineExceededException();
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("HTTP 请求失败", cause);
        } catch (CancellationException cancelled) {
            throw new IOException("HTTP 请求已取消", cancelled);
        }
    }

    /** Reject clients that could implicitly follow 307/308 redirects and replay a request. */
    public static void requireNoRedirects(HttpClient client) {
        Objects.requireNonNull(client, "client");
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException(
                    "官方 QQ HTTP 客户端必须配置 followRedirects=NEVER");
        }
    }

    private static void cancel(
            CompletableFuture<HttpResponse<byte[]>> future,
            LimitedBodySubscriber subscriber) {
        subscriber.cancel();
        future.cancel(true);
    }

    /** Stable end-to-end deadline failure. */
    public static final class DeadlineExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        public DeadlineExceededException() {
            super("HTTP 请求超过整体 deadline");
        }
    }

    /** Package-visible for direct cancellation and size-bound regression tests. */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final int maxBytes;
        private final ByteArrayOutputStream output;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final AtomicBoolean done = new AtomicBoolean();

        private Flow.Subscription subscription;
        private boolean cancelled;

        LimitedBodySubscriber(int maxBytes) {
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes 必须为正");
            }
            this.maxBytes = maxBytes;
            this.output = new ByteArrayOutputStream(Math.min(maxBytes, 16 * 1024));
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription subscription) {
            this.subscription = Objects.requireNonNull(subscription, "subscription");
            if (cancelled) {
                subscription.cancel();
            } else {
                subscription.request(Long.MAX_VALUE);
            }
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> items) {
            if (done.get() || cancelled || items == null) {
                return;
            }
            long incoming = 0;
            for (ByteBuffer item : items) {
                if (item != null && item.hasRemaining()) {
                    incoming += item.remaining();
                }
            }
            if (incoming > maxBytes - (long) output.size()) {
                fail(new OfficialQqResponseBody.TooLargeException());
                return;
            }
            for (ByteBuffer item : items) {
                if (item == null || !item.hasRemaining()) {
                    continue;
                }
                int length = item.remaining();
                byte[] chunk = new byte[length];
                item.get(chunk);
                output.write(chunk, 0, length);
            }
        }

        @Override
        public synchronized void onError(Throwable throwable) {
            if (done.compareAndSet(false, true)) {
                body.completeExceptionally(throwable);
            }
        }

        @Override
        public synchronized void onComplete() {
            if (done.compareAndSet(false, true)) {
                body.complete(output.toByteArray());
            }
        }

        synchronized void cancel() {
            cancelled = true;
            Flow.Subscription current = subscription;
            if (current != null) {
                current.cancel();
            }
            if (done.compareAndSet(false, true)) {
                body.completeExceptionally(new CancellationException("HTTP body cancelled"));
            }
        }

        private synchronized void fail(Throwable failure) {
            cancelled = true;
            Flow.Subscription current = subscription;
            if (current != null) {
                current.cancel();
            }
            if (done.compareAndSet(false, true)) {
                body.completeExceptionally(failure);
            }
        }
    }
}
