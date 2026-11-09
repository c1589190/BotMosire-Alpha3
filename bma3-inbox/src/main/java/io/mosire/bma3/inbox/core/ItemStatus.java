package io.mosire.bma3.inbox.core;

/** Lifecycle of one inbox item. */
public enum ItemStatus {
    AVAILABLE,
    DELIVERED,
    ACKED,
    UNDELIVERED
}
