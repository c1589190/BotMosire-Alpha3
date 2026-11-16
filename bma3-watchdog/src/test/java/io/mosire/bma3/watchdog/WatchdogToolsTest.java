package io.mosire.bma3.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.watchdog.spi.Probe;
import io.mosire.bma3.watchdog.spi.ProbeResult;
import io.mosire.bma3.watchdog.spi.WatchdogOptions;
import io.mosire.bma3.watchdog.tool.WatchdogTools;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatchdogToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void diagnoseToolCombinesSnapshotWithTheContentFreeBundle() throws Exception {
        WatchdogOptions options = new WatchdogOptions(true, Duration.ofSeconds(60),
                Duration.ofMillis(500), 1, Duration.ofHours(1), Duration.ofHours(1),
                tempDir, List.of("fake"), 50, Duration.ofSeconds(45), 0);
        try (WatchdogEngine engine = new WatchdogEngine(() -> options,
                new JsonWatchdogStore(tempDir))) {
            engine.addProbe(new Probe() {
                @Override
                public ProbeResult check() {
                    return ProbeResult.up("fake", "ok");
                }

                @Override
                public String name() {
                    return "fake";
                }
            }).tickOnce();

            AgentTool diagnose = WatchdogTools.all(engine, () -> Map.of(
                            "version", "0.0.7-hotfix2",
                            "watchdog", engine.snapshot(),
                            "media", Map.of("attempts", 2)))
                    .stream().filter(tool -> tool.name().equals("bma3_watchdog_diagnose"))
                    .findFirst().orElseThrow();
            ToolResult result = diagnose.execute(new ToolContext(AccessToken.DEFAULT,
                    AgentPermissionSet.unrestricted(AccessToken.DEFAULT), Map.of(), Map.of(),
                    AgentIdentity.external()));
            assertThat(result.success()).isTrue();
            var json = new ObjectMapper().readTree(result.message());
            assertThat(json.path("watchdog").path("components").has("fake")).isTrue();
            assertThat(json.path("version").asText()).isEqualTo("0.0.7-hotfix2");
            assertThat(json.path("bundle").isMissingNode()).isTrue();
            assertThat(result.message()).doesNotContain("token", "Bearer", "Cookie");
        }
    }
}
