package io.mosire.bma3.qqofficial.gateway;

import java.net.URI;
import java.util.concurrent.CompletionStage;

/**
 * Minimal WebSocket transport seam.
 *
 * <p>Callbacks may arrive on any transport thread. Implementations must call {@code onOpen} once,
 * then exactly one of {@code onClose}/{@code onError}. The production implementation is backed by
 * {@link java.net.http.WebSocket}; tests use a fully in-memory fake.</p>
 */
public interface OfficialQqGatewayTransport extends AutoCloseable {

    /** Asynchronously sends one complete text frame. */
    CompletionStage<Void> sendText(String text);

    /** Best-effort close. Must be safe to call more than once. */
    @Override
    void close();

    @FunctionalInterface
    interface Factory {
        OfficialQqGatewayTransport open(URI uri, Listener listener) throws Exception;
    }

    interface Listener {
        void onOpen();

        void onText(String text);

        /** reason is transport-supplied, untrusted and must not be logged or exposed. */
        void onClose(int statusCode, String reason);

        void onError(Throwable failure);
    }
}
