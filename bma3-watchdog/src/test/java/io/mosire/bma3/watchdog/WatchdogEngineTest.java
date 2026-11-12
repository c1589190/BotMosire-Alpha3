package io.mosire.bma3.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.bma3.watchdog.remediation.DeployerAlertListener;
import io.mosire.bma3.watchdog.spi.Health;
import io.mosire.bma3.watchdog.spi.Probe;
import io.mosire.bma3.watchdog.spi.ProbeResult;
import io.mosire.bma3.watchdog.spi.Remediation;
import io.mosire.bma3.watchdog.spi.WatchdogEvent;
import io.mosire.bma3.watchdog.spi.WatchdogOptions;
import io.mosire.bma3.watchdog.spi.WatchdogSnapshot;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatchdogEngineTest {

    @TempDir
    Path tempDir;

    private WatchdogOptions options(int threshold, Duration probeTimeout) {
        return new WatchdogOptions(true, Duration.ofSeconds(60), probeTimeout, threshold,
                Duration.ofHours(1), Duration.ofHours(1), tempDir, List.of("fake"), 50,
                Duration.ofSeconds(45), 0);
    }

    private static Probe probe(String name, java.util.function.Supplier<ProbeResult> result) {
        return new Probe() {
            @Override
            public ProbeResult check() {
                return result.get();
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    private static Remediation remediation(String component, AtomicInteger runs,
                                            Duration cooldown) {
        return new Remediation() {
            @Override
            public String name() {
                return "fix-" + component;
            }

            @Override
            public String component() {
                return component;
            }

            @Override
            public Duration cooldown() {
                return cooldown;
            }

            @Override
            public RemediationResult remediate(ProbeResult result) {
                runs.incrementAndGet();
                return RemediationResult.changed(name(), "fixed");
            }
        };
    }

    @Test
    void aDownComponentRunsRemediationAfterTheThresholdAndIsNotReady() {
        AtomicInteger runs = new AtomicInteger();
        WatchdogEngine engine = new WatchdogEngine(() -> options(1, Duration.ofMillis(500)),
                new JsonWatchdogStore(tempDir));
        engine.addProbe(probe("fake", () -> ProbeResult.down("fake", "DOWN", "boom")))
                .addRemediation(remediation("fake", runs, Duration.ofHours(1)));
        WatchdogSnapshot snapshot = engine.tickOnce();
        assertThat(runs.get()).isEqualTo(1);
        assertThat(snapshot.overall()).isEqualTo(Health.DOWN);
        assertThat(snapshot.ready()).isFalse();
        assertThat(snapshot.components()).containsKey("fake");
        assertThat(engine.snapshot().recentEvents())
                .anyMatch(event -> event.component().equals("fake")
                        && event.health() == Health.DOWN);
        engine.close();
    }

    @Test
    void aHealthyComponentNeverRemediates() {
        AtomicInteger runs = new AtomicInteger();
        WatchdogEngine engine = new WatchdogEngine(() -> options(1, Duration.ofMillis(500)),
                new JsonWatchdogStore(tempDir));
        engine.addProbe(probe("fake", () -> ProbeResult.up("fake", "ok")))
                .addRemediation(remediation("fake", runs, Duration.ofHours(1)));
        WatchdogSnapshot snapshot = engine.tickOnce();
        assertThat(runs.get()).isZero();
        assertThat(snapshot.overall()).isEqualTo(Health.UP);
        assertThat(snapshot.ready()).isTrue();
        engine.close();
    }

    @Test
    void aCooldownSuppressesRepeatedRemediation() {
        AtomicInteger runs = new AtomicInteger();
        WatchdogEngine engine = new WatchdogEngine(() -> options(1, Duration.ofMillis(500)),
                new JsonWatchdogStore(tempDir));
        engine.addProbe(probe("fake", () -> ProbeResult.down("fake", "DOWN", "boom")))
                .addRemediation(remediation("fake", runs, Duration.ofHours(1)));
        engine.tickOnce();
        engine.tickOnce();
        assertThat(runs.get()).as("冷却期内只恢复一次").isEqualTo(1);
        assertThat(engine.snapshot().remediationsSuppressed()).isPositive();
        engine.close();
    }

    @Test
    void aHungProbeBecomesDownInsteadOfBlockingTheTick() {
        WatchdogEngine engine = new WatchdogEngine(() -> options(1, Duration.ofMillis(50)),
                new JsonWatchdogStore(tempDir));
        engine.addProbe(probe("fake", () -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ProbeResult.up("fake", "late");
        }));
        WatchdogSnapshot snapshot = engine.tickOnce();
        assertThat(snapshot.components().get("fake").health()).isEqualTo(Health.DOWN);
        assertThat(snapshot.components().get("fake").code()).isEqualTo("PROBE_TIMEOUT");
        engine.close();
    }

    @Test
    void theDeployerAlertIsSentOncePerCooldown() {
        List<String> sent = new ArrayList<>();
        DeployerAlertListener listener = new DeployerAlertListener(sent::add,
                Duration.ofHours(1), true);
        WatchdogEvent event = new WatchdogEvent(Instant.now(), "mcp", Health.DOWN, "DOWN",
                "listener is down", "", true);
        listener.accept(event);
        listener.accept(event);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).contains("mcp").contains("DOWN");
    }

    @Test
    void theJsonStoreRoundTripsResultsAndEvents() {
        JsonWatchdogStore store = new JsonWatchdogStore(tempDir);
        ProbeResult result = ProbeResult.down("mcp", "DOWN", "boom");
        store.saveResults(Map.of("mcp", result));
        assertThat(store.loadResults().get("mcp").health()).isEqualTo(Health.DOWN);
        store.appendEvent(new WatchdogEvent(Instant.now(), "mcp", Health.DOWN, "DOWN",
                "boom", "restartMcp", true));
        assertThat(store.recentEvents(10)).hasSize(1);
        store.close();
    }
}
