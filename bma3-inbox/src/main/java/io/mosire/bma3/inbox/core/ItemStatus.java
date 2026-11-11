package io.mosire.bma3.inbox.core;

/**
 * Lifecycle of one inbox item.
 *
 * <p>Only two states survive: acked is a per-item fact, and the global revision cursor is just an
 * observation. The legacy DELIVERED/UNDELIVERED values (retry bookkeeping) are gone; a stale row
 * carrying one of them is read back as AVAILABLE rather than failing the whole inbox.</p>
 */
public enum ItemStatus {
    AVAILABLE,
    ACKED;

    public static ItemStatus parse(String raw) {
        if (raw == null) {
            return AVAILABLE;
        }
        return switch (raw) {
            case "ACKED" -> ACKED;
            default -> AVAILABLE;
        };
    }
}
