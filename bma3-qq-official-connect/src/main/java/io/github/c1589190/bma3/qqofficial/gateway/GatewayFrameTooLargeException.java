package io.github.c1589190.bma3.qqofficial.gateway;

/** A fragmented WebSocket text message exceeded the configured in-memory bound. */
public final class GatewayFrameTooLargeException extends GatewayPermanentException {

    private static final long serialVersionUID = 1L;

    public GatewayFrameTooLargeException() {
        super("FRAME_TOO_LARGE");
    }
}
