package io.github.c1589190.bma3.qqofficial.event;

import java.time.Instant;

/**
 * One decoded Gateway dispatch frame ({@code op=0} with a non-blank {@code t}).
 *
 * <p>This is a closed model: message, system/directory, and unknown dispatches are the only
 * forms the decoder can emit. A caller can therefore switch exhaustively over the three
 * permitted record types without accidentally accepting a crate of arbitrary JSON.</p>
 */
public sealed interface OfficialQqDispatch
        permits OfficialQqEvent, OfficialQqSystemEvent, OfficialQqUnknownEvent {

    /** Envelope {@code id}; a string on the wire and kept as a string here. */
    String eventId();

    /** Envelope {@code t}; the verbatim official event name. */
    String eventType();

    /** Envelope {@code s}; {@code -1} when the platform did not provide a sequence. */
    long sequence();

    /** Server-side time at which the decoder received this frame. */
    Instant receivedAt();
}
