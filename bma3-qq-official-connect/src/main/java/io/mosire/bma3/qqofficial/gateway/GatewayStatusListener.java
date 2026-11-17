package io.mosire.bma3.qqofficial.gateway;

/** Receives credential-free status snapshots for observability/watchdog wiring. */
@FunctionalInterface
public interface GatewayStatusListener {

    void onStatus(GatewayStatus status);

    static GatewayStatusListener noop() {
        return status -> {
        };
    }
}
