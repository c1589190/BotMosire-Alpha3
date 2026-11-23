package io.github.c1589190.bma3.qqofficial.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OfficialQqHttpTransportTest {

    @Test
    void oversizedChunkCancelsTheSubscriptionAndFailsTheBody() {
        OfficialQqHttpTransport.LimitedBodySubscriber subscriber =
                new OfficialQqHttpTransport.LimitedBodySubscriber(4);
        TrackingSubscription subscription = new TrackingSubscription();
        subscriber.onSubscribe(subscription);

        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[5])));

        assertThat(subscription.cancelled).isTrue();
        assertThat(subscriber.getBody().toCompletableFuture())
                .isCompletedExceptionally();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
                .hasCauseInstanceOf(OfficialQqResponseBody.TooLargeException.class);
    }

    @Test
    void explicitCancelCancelsTheSubscription() {
        OfficialQqHttpTransport.LimitedBodySubscriber subscriber =
                new OfficialQqHttpTransport.LimitedBodySubscriber(16);
        TrackingSubscription subscription = new TrackingSubscription();
        subscriber.onSubscribe(subscription);

        subscriber.cancel();

        assertThat(subscription.cancelled).isTrue();
        assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
    }

    private static final class TrackingSubscription implements Flow.Subscription {
        private final AtomicLong requested = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void request(long count) {
            requested.addAndGet(count);
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }
}
