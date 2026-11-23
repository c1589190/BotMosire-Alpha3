package io.github.c1589190.bma3.qqofficial.event;

/**
 * Injectable sink for decoded Gateway dispatch frames.
 *
 * <p>The gateway transport owns JSON/WebSocket details; downstream ingest owns message-board
 * writes, directory updates, and Inbox routing. Implementations can switch exhaustively over
 * the sealed {@link OfficialQqDispatch} hierarchy.</p>
 */
@FunctionalInterface
public interface OfficialQqDispatchHandler {

    /** Called once for each {@code op=0} dispatch that the decoder understands or rejects. */
    void handle(OfficialQqDispatch dispatch);
}
