package io.github.c1589190.bma3.app.channel.binding;

/**
 * Test seam for the four cross-store crash boundaries owned by {@link QqBindingCoordinator}.
 *
 * <p>A crash simulation must throw an {@link Error} (or terminate the process) rather than an
 * ordinary exception. The coordinator compensates ordinary failures, but it must not catch a
 * simulated process death and accidentally undo/reapply half an operation.</p>
 */
@FunctionalInterface
public interface BindingCrashHook {

    void at(CrashPoint point);

    static BindingCrashHook noop() {
        return point -> {
        };
    }

    enum CrashPoint {
        /** Ledger generation exists; registry may not have been touched. */
        AFTER_LEDGER_BIND,
        /** Registry bind committed; both stores should now agree. */
        AFTER_REGISTRY_BIND,
        /** Registry unbind committed; ledger may still be active. */
        AFTER_REGISTRY_UNBIND,
        /** Ledger unbind committed; both stores should now agree. */
        AFTER_LEDGER_UNBIND
    }
}
