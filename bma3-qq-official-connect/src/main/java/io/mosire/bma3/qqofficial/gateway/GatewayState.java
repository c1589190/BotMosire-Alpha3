package io.mosire.bma3.qqofficial.gateway;

/** Observable lifecycle of one official QQ Gateway connection. */
public enum GatewayState {
    STOPPED,
    CONNECTING,
    HELLO,
    IDENTIFYING,
    RESUME,
    READY,
    RECONNECTING,
    CLOSED,
    DOWN
}
