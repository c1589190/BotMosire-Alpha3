package io.mosire.bma3.watchdog.spi;

/** One health probe. Implementations must be cheap, bounded and side-effect free. */
@FunctionalInterface
public interface Probe {

    ProbeResult check();

    default String name() {
        return getClass().getSimpleName();
    }
}
