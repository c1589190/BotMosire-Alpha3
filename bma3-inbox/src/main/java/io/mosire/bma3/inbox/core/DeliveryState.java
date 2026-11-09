package io.mosire.bma3.inbox.core;

/** Delivery state machine. */
public enum DeliveryState {
    DELIVERED,
    ACKED,
    UNDELIVERED
}
