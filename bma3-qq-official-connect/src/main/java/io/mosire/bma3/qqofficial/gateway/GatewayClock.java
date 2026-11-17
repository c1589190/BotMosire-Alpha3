package io.mosire.bma3.qqofficial.gateway;

import java.time.Instant;

/** Injectable wall clock so status timestamps and timeout tests are deterministic. */
@FunctionalInterface
public interface GatewayClock {

    Instant now();

    static GatewayClock system() {
        return Instant::now;
    }
}
