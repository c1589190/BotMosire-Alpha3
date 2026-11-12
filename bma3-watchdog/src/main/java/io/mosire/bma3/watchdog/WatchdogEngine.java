package io.mosire.bma3.watchdog;

import io.mosire.bma3.watchdog.spi.Health;
import io.mosire.bma3.watchdog.spi.MaintenanceTask;
import io.mosire.bma3.watchdog.spi.Probe;
import io.mosire.bma3.watchdog.spi.ProbeResult;
import io.mosire.bma3.watchdog.spi.Remediation;
import io.mosire.bma3.watchdog.spi.WatchdogEvent;
import io.mosire.bma3.watchdog.spi.WatchdogOptions;
import io.mosire.bma3.watchdog.spi.WatchdogSnapshot;
import io.mosire.bma3.watchdog.spi.WatchdogStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The watchdog engine: runs probes on a fixed cadence, applies bounded remediations after a failure
 * threshold, runs maintenance tasks, and keeps a small content-free event history.
 *
 * <p>It depends only on the SPI and JDK; concrete BMA3 probes live in the same module but are wired
 * by the application. That keeps the engine reusable and testable with fakes.</p>
 */
public final class WatchdogEngine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WatchdogEngine.class);

    private final Supplier<WatchdogOptions> optionsSupplier;
    private final WatchdogStore store;
    private final List<Probe> probes = new CopyOnWriteArrayList<>();
    private final List<Remediation> remediations = new CopyOnWriteArrayList<>();
    private final List<MaintenanceTask> tasks = new CopyOnWriteArrayList<>();
    private final List<Consumer<WatchdogEvent>> listeners = new CopyOnWriteArrayList<>();

    private final Map<String, ProbeResult> current = new ConcurrentHashMap<>();
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastRemediation = new ConcurrentHashMap<>();
    private final Deque<WatchdogEvent> events = new ArrayDeque<>();
    private final AtomicLong probesRun = new AtomicLong();
    private final AtomicLong remediationsRun = new AtomicLong();
    private final AtomicLong remediationsSuppressed = new AtomicLong();
    private final AtomicLong alertsSent = new AtomicLong();

    private final ExecutorService probeExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "bma3-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean started;
    private volatile Instant lastTickAt;

    public WatchdogEngine(Supplier<WatchdogOptions> optionsSupplier, WatchdogStore store) {
        this.optionsSupplier = optionsSupplier == null
                ? WatchdogOptions::defaults : optionsSupplier;
        this.store = store == null ? new JsonWatchdogStore(null) : store;
        this.current.putAll(this.store.loadResults());
    }

    public WatchdogEngine addProbe(Probe probe) {
        if (probe != null) {
            probes.add(probe);
        }
        return this;
    }

    public WatchdogEngine addRemediation(Remediation remediation) {
        if (remediation != null) {
            remediations.add(remediation);
        }
        return this;
    }

    public WatchdogEngine addTask(MaintenanceTask task) {
        if (task != null) {
            tasks.add(task);
        }
        return this;
    }

    /** Listener for status transitions (used by the deployer alert). */
    public WatchdogEngine onEvent(Consumer<WatchdogEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return this;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        WatchdogOptions options = options();
        if (!options.enabled()) {
            LOG.info("event=watchdog_disabled");
            return;
        }
        started = true;
        long intervalMillis = Math.max(1000, options.interval().toMillis());
        scheduler.scheduleWithFixedDelay(this::safeTick, 0, intervalMillis,
                TimeUnit.MILLISECONDS);
        for (MaintenanceTask task : tasks) {
            long every = Math.max(1000, task.interval().toMillis());
            scheduler.scheduleWithFixedDelay(() -> safeTask(task), every, every,
                    TimeUnit.MILLISECONDS);
        }
        LOG.info("event=watchdog_started intervalMs={} probes={} remediations={} tasks={}",
                intervalMillis, probes.size(), remediations.size(), tasks.size());
    }

    public synchronized boolean started() {
        return started;
    }

    public WatchdogOptions options() {
        WatchdogOptions options = optionsSupplier.get();
        return options == null ? WatchdogOptions.defaults() : options;
    }

    /** Runs every probe once and applies remediations. Public for tests and the probe tool. */
    public WatchdogSnapshot tickOnce() {
        WatchdogOptions options = options();
        lastTickAt = Instant.now();
        for (Probe probe : probes) {
            ProbeResult result = runProbe(probe, options);
            record(result, options);
        }
        store.saveResults(current);
        return snapshot();
    }

    /** Runs one named probe (or all when {@code component} is blank). */
    public List<ProbeResult> runProbeNow(String component) {
        WatchdogOptions options = options();
        List<ProbeResult> results = new ArrayList<>();
        for (Probe probe : probes) {
            String name = probe.name();
            if (component != null && !component.isBlank() && !component.equals(name)) {
                continue;
            }
            ProbeResult result = runProbe(probe, options);
            record(result, options);
            results.add(result);
        }
        store.saveResults(current);
        return List.copyOf(results);
    }

    /** Manual remediation; bypasses the failure threshold but keeps the cooldown. */
    public boolean remediateNow(String component) {
        ProbeResult result = current.get(component);
        if (result == null) {
            return false;
        }
        return applyRemediation(result, options(), true) != null;
    }

    public WatchdogSnapshot snapshot() {
        WatchdogOptions options = options();
        Health overall = Health.UP;
        for (ProbeResult result : current.values()) {
            overall = Health.worst(overall, result.health());
        }
        boolean ready = true;
        for (String critical : options.readyzCritical()) {
            ProbeResult result = current.get(critical);
            if (result == null || result.health() == Health.DOWN
                    || result.health() == Health.UNKNOWN) {
                ready = false;
                break;
            }
        }
        List<WatchdogEvent> recent;
        synchronized (events) {
            recent = List.copyOf(new ArrayList<>(events));
        }
        return new WatchdogSnapshot(overall, ready, Instant.now(), Map.copyOf(current),
                recent, probesRun.get(), remediationsRun.get(), remediationsSuppressed.get(),
                alertsSent.get());
    }

    public Instant lastTickAt() {
        return lastTickAt;
    }

    private void safeTick() {
        try {
            tickOnce();
        } catch (RuntimeException failure) {
            LOG.warn("event=watchdog_tick_failed type={}", failure.getClass().getSimpleName());
        }
    }

    private void safeTask(MaintenanceTask task) {
        try {
            task.run();
        } catch (RuntimeException failure) {
            LOG.warn("event=watchdog_task_failed task={} type={}",
                    safe(task.name()), failure.getClass().getSimpleName());
        }
    }

    private ProbeResult runProbe(Probe probe, WatchdogOptions options) {
        String name = probe.name();
        Future<ProbeResult> future = probeExecutor.submit(probe::check);
        probesRun.incrementAndGet();
        try {
            long timeout = Math.max(100, options.probeTimeout().toMillis());
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return ProbeResult.down(name, "PROBE_TIMEOUT",
                    "probe exceeded " + options.probeTimeout().toMillis() + "ms");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            return ProbeResult.down(name, "PROBE_FAILED", cause.getClass().getSimpleName());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ProbeResult.down(name, "PROBE_INTERRUPTED", "interrupted");
        }
    }

    private void record(ProbeResult result, WatchdogOptions options) {
        String component = result.component();
        ProbeResult previous = current.put(component, result);
        boolean changed = previous == null || previous.health() != result.health();
        if (!result.health().healthy()) {
            failures.merge(component, 1, Integer::sum);
        } else {
            failures.remove(component);
        }
        boolean downTransition = changed && result.health() == Health.DOWN;
        if (changed) {
            emit(new WatchdogEvent(result.at(), component, result.health(), result.code(),
                    result.detail(), "", true));
        }
        int consecutive = failures.getOrDefault(component, 0);
        Remediation.RemediationResult remediation = null;
        if (!result.health().healthy() && consecutive >= options.failureThreshold()) {
            remediation = applyRemediation(result, options, false);
        }
        if (downTransition) {
            // Notify after remediation, not before: a reconnect may restore the very account the
            // deployer alert would use to send the private message.
            alertsSent.incrementAndGet();
            notifyListeners(new WatchdogEvent(result.at(), component, result.health(),
                    result.code(), result.detail(),
                    remediation == null ? "" : remediation.action(), true));
        }
    }

    private Remediation.RemediationResult applyRemediation(ProbeResult result,
                                                           WatchdogOptions options,
                                                           boolean manual) {
        for (Remediation remediation : remediations) {
            // A manual remediation is an explicit operator/agent request: skip the health gate,
            // but still require the component to match and respect the cooldown.
            if (!manual && !remediation.applies(result)) {
                continue;
            }
            String target = remediation.component();
            if (!target.isBlank() && !target.equals(result.component())) {
                continue;
            }
            Instant last = lastRemediation.get(remediation.name());
            Duration cooldown = remediation.cooldown() == null
                    ? options.recoveryCooldown() : remediation.cooldown();
            if (last != null && Instant.now().isBefore(last.plus(cooldown))) {
                remediationsSuppressed.incrementAndGet();
                continue;
            }
            lastRemediation.put(remediation.name(), Instant.now());
            Remediation.RemediationResult outcome;
            try {
                outcome = remediation.remediate(result);
                remediationsRun.incrementAndGet();
            } catch (RuntimeException failure) {
                outcome = Remediation.RemediationResult.changed(remediation.name(),
                        "FAILED:" + failure.getClass().getSimpleName());
            }
            emit(new WatchdogEvent(Instant.now(), result.component(), result.health(),
                    result.code(), result.detail(), remediation.name(), true));
            LOG.info("event=watchdog_remediation action={} component={} manual={} outcome={}",
                    safe(remediation.name()), safe(result.component()), manual,
                    safe(outcome.outcome()));
            return outcome;
        }
        return null;
    }

    private void emit(WatchdogEvent event) {
        synchronized (events) {
            events.addLast(event);
            int max = options().maxEvents();
            while (events.size() > max) {
                events.removeFirst();
            }
        }
        store.appendEvent(event);
    }

    private void notifyListeners(WatchdogEvent event) {
        for (Consumer<WatchdogEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException failure) {
                LOG.warn("event=watchdog_listener_failed type={}",
                        failure.getClass().getSimpleName());
            }
        }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    @Override
    public synchronized void close() {
        started = false;
        scheduler.shutdownNow();
        probeExecutor.shutdownNow();
        store.saveResults(current);
        store.close();
    }
}
