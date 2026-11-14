package io.mosire.bma3.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.bma3.inbox.core.DeliveryPolicy;
import io.mosire.bma3.inbox.core.InboxRuntime;
import io.mosire.bma3.inbox.core.InboxService;
import io.mosire.bma3.inbox.core.PluginHeartbeat;
import io.mosire.bma3.inbox.core.SqliteInboxStore;
import io.mosire.bma3.watchdog.probe.Probes;
import io.mosire.bma3.watchdog.spi.Health;
import io.mosire.bma3.watchdog.spi.ProbeResult;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProbesDshPluginTest {

    @TempDir
    Path tempDir;

    private InboxService service() {
        DeliveryPolicy policy = DeliveryPolicy.defaults();
        SqliteInboxStore store = new SqliteInboxStore(tempDir.resolve("inbox.db"));
        return new InboxService(store, policy, "bma3", () -> new InboxRuntime(6, 6, true));
    }

    private static PluginHeartbeat heartbeat(boolean bound, boolean tokenOk, Instant at) {
        return new PluginHeartbeat("session-1", bound, "open", 7L, "mcp-1", "", "", "env-file",
                tokenOk, at);
    }

    @Test
    void healthyHeartbeatIsUp() {
        InboxService service = service();
        service.pluginHeartbeat(heartbeat(true, true, Instant.now()));

        ProbeResult result = Probes.dshPlugin(service, Duration.ofSeconds(45)).check();

        assertThat(result.health()).isEqualTo(Health.UP);
        assertThat(result.facts()).containsEntry("tokenSource", "env-file")
                .containsEntry("tokenOk", "true");
    }

    @Test
    void tokenMismatchIsReportedEvenWhenTheHeartbeatArrives() {
        InboxService service = service();
        service.pluginHeartbeat(heartbeat(true, false, Instant.now()));

        ProbeResult result = Probes.dshPlugin(service, Duration.ofSeconds(45)).check();

        assertThat(result.health()).isEqualTo(Health.DEGRADED);
        assertThat(result.code()).isEqualTo("TOKEN_MISMATCH");
        assertThat(result.facts()).containsEntry("tokenSource", "env-file");
    }

    @Test
    void staleHeartbeatIsReported() {
        InboxService service = service();
        service.pluginHeartbeat(heartbeat(true, true, Instant.now().minusSeconds(600)));

        ProbeResult result = Probes.dshPlugin(service, Duration.ofSeconds(45)).check();

        assertThat(result.code()).isEqualTo("HEARTBEAT_STALE");
    }

    @Test
    void missingHeartbeatIsDegraded() {
        ProbeResult result = Probes.dshPlugin(service(), Duration.ofSeconds(45)).check();

        assertThat(result.health()).isEqualTo(Health.DEGRADED);
        assertThat(result.code()).isEqualTo("NO_HEARTBEAT");
    }
}
