package io.mosire.bma3.watchdog.task;

import io.mosire.bma3.inbox.core.DismissResult;
import io.mosire.bma3.inbox.core.InboxService;
import io.mosire.bma3.watchdog.spi.MaintenanceTask;

import java.time.Duration;

/** Periodic maintenance jobs that must not live on the ingest path. */
public final class Tasks {

    private Tasks() {
    }

    /** Applies the configured auto-dismiss policy (no-op when disabled). */
    public static MaintenanceTask autoDismiss(InboxService inbox, Duration interval) {
        return task("autoDismiss", interval, () -> {
            DismissResult result = inbox.autoDismiss();
            if (result.dismissed() > 0) {
                org.slf4j.LoggerFactory.getLogger(Tasks.class).info(
                        "event=watchdog_auto_dismiss dismissed={} skippedImportant={} remaining={}",
                        result.dismissed(), result.skippedImportant(), result.remaining());
            }
        });
    }

    /** Runs a supplied maintenance action (e.g. group directory refresh) on a fixed cadence. */
    public static MaintenanceTask periodically(String name, Duration interval, Runnable action) {
        return task(name, interval, action);
    }

    private static MaintenanceTask task(String name, Duration interval, Runnable action) {
        return new MaintenanceTask() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Duration interval() {
                return interval == null ? Duration.ofSeconds(60) : interval;
            }

            @Override
            public void run() {
                action.run();
            }
        };
    }
}
