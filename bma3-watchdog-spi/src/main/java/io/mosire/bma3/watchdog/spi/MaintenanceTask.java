package io.mosire.bma3.watchdog.spi;

import java.time.Duration;

/** A periodic maintenance job (auto-dismiss, directory refresh, …). */
public interface MaintenanceTask {

    String name();

    Duration interval();

    /** Runs once; exceptions are caught and reported by the engine, never fatal. */
    void run();
}
