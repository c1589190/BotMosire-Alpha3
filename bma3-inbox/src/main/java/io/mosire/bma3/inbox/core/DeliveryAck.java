package io.mosire.bma3.inbox.core;

/** Result of acknowledging one delivery. */
public record DeliveryAck(String deliveryId, boolean acked, int remaining) {
}
