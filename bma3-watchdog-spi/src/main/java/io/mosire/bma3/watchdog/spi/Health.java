package io.mosire.bma3.watchdog.spi;

/** Coarse health of one component. */
public enum Health {
    UP,
    DEGRADED,
    DOWN,
    UNKNOWN;

    public boolean healthy() {
        return this == UP || this == DEGRADED;
    }

    public static Health worst(Health left, Health right) {
        return severity(left) >= severity(right) ? left : right;
    }

    private static int severity(Health health) {
        return switch (health == null ? UNKNOWN : health) {
            case UP -> 0;
            case DEGRADED -> 1;
            case DOWN -> 2;
            case UNKNOWN -> 3;
        };
    }
}
