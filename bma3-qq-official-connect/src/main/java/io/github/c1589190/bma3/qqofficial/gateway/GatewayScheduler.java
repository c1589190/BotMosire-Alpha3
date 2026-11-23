package io.github.c1589190.bma3.qqofficial.gateway;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One-shot scheduling abstraction used by the Gateway state machine.
 *
 * <p>The client schedules its next heartbeat explicitly after each tick. This keeps heartbeat,
 * ACK-timeout and reconnect tests deterministic without sleeping.</p>
 */
public interface GatewayScheduler extends AutoCloseable {

    ScheduledTask schedule(Duration delay, Runnable action);

    @Override
    default void close() {
        // Injected schedulers are owned by the caller by default.
    }

    /** A cancellable scheduled one-shot. */
    @FunctionalInterface
    interface ScheduledTask {
        void cancel();
    }

    /** Production scheduler: one daemon thread, no task is allowed to kill the thread. */
    static GatewayScheduler daemonScheduler(String threadName) {
        return new DaemonScheduler(threadName);
    }

    final class DaemonScheduler implements GatewayScheduler {
        private final ScheduledExecutorService executor;

        private DaemonScheduler(String threadName) {
            String name = Objects.requireNonNullElse(threadName, "qq-official-gateway");
            this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override
        public ScheduledTask schedule(Duration delay, Runnable action) {
            Objects.requireNonNull(delay, "delay");
            Objects.requireNonNull(action, "action");
            long millis = Math.max(0L, delay.toMillis());
            try {
                var future = executor.schedule(() -> {
                    try {
                        action.run();
                    } catch (RuntimeException ignored) {
                        // The state machine records failures through its own callbacks. A scheduler
                        // task must not be able to kill the only scheduler thread.
                    }
                }, millis, TimeUnit.MILLISECONDS);
                return () -> future.cancel(false);
            } catch (RejectedExecutionException failure) {
                throw new IllegalStateException("gateway scheduler is closed", failure);
            }
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
