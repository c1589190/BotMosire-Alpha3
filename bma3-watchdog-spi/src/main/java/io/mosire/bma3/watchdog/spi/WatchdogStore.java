package io.mosire.bma3.watchdog.spi;

import java.util.List;
import java.util.Map;

/** Persistence port for watchdog state and the recent event ring. */
public interface WatchdogStore extends AutoCloseable {

    Map<String, ProbeResult> loadResults();

    void saveResults(Map<String, ProbeResult> results);

    void appendEvent(WatchdogEvent event);

    List<WatchdogEvent> recentEvents(int limit);

    @Override
    default void close() {
    }
}
