package io.mosire.bma3.app.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.bma3.app.config.AppConfig;
import io.mosire.bma3.app.config.ConfigLoader;
import io.mosire.bma3.app.config.ConfigWatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code set_config} is the single write path for scores: it validates before touching the live
 * file, then the watcher hot-reloads it. The two assertions that matter are "a valid patch takes
 * effect in the running process" and "an invalid patch changes nothing on disk".
 */
class SetConfigToolTest {

    @TempDir
    Path root;

    private static ToolContext call(Map<String, Object> arguments) {
        return new ToolContext(AccessToken.DEFAULT,
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                Map.of(), arguments, AgentIdentity.external());
    }

    private void writeConfig() throws Exception {
        Path configDir = root.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("app.yaml"), """
                dataDir: %s
                server:
                  tokenEnv: BMA3_TEST_TOKEN
                inbox:
                  display:
                    maxMessagesPerItem: 6
                weighting:
                  immediateThreshold: 300
                  sourcePriority:
                    "*": 0
                    qq_private: 300
                    qq_group: 200
                """.formatted(root.resolve("data")));
    }

    @Test
    void aValidPatchIsPersistedAndHotReloaded() throws Exception {
        writeConfig();
        Map<String, String> env = Map.of("BMA3_TEST_TOKEN", "token");
        AtomicReference<AppConfig> effective = new AtomicReference<>(ConfigLoader.load(root, env));
        try (ConfigWatcher watcher = ConfigWatcher.watch(root, env, effective::set)) {
            SetConfigTool tool = new SetConfigTool(new ConfigToolContext(root, env, effective::get));

            ToolResult result = tool.execute(call(Map.of(
                    "patch", Map.of("weighting.sourcePriority.qq_group", 150))));

            assertThat(result.success()).as(result.message()).isTrue();
            assertThat(effective.get().weighting().sourcePriority()).containsEntry("qq_group", 150);
            assertThat(Files.readString(root.resolve("config/app.yaml")))
                    .contains("qq_group: 150");
        }
    }

    @Test
    void anInvalidPatchNeverReachesTheFile() throws Exception {
        writeConfig();
        Map<String, String> env = Map.of("BMA3_TEST_TOKEN", "token");
        AtomicReference<AppConfig> effective = new AtomicReference<>(ConfigLoader.load(root, env));
        String before = Files.readString(root.resolve("config/app.yaml"));
        try (ConfigWatcher watcher = ConfigWatcher.watch(root, env, effective::set)) {
            SetConfigTool tool = new SetConfigTool(new ConfigToolContext(root, env, effective::get));

            ToolResult result = tool.execute(call(Map.of(
                    "patch", Map.of("inbox.display.maxMessagesPerItem", 2))));

            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("CONFIG_INVALID");
            assertThat(Files.readString(root.resolve("config/app.yaml"))).isEqualTo(before);
            assertThat(effective.get().inbox().display().maxMessagesPerItem()).isEqualTo(6);
        }
    }

    @Test
    void unsetRemovesARuleEntirely() throws Exception {
        writeConfig();
        Map<String, String> env = Map.of("BMA3_TEST_TOKEN", "token");
        AtomicReference<AppConfig> effective = new AtomicReference<>(ConfigLoader.load(root, env));
        try (ConfigWatcher watcher = ConfigWatcher.watch(root, env, effective::set)) {
            SetConfigTool tool = new SetConfigTool(new ConfigToolContext(root, env, effective::get));

            ToolResult result = tool.execute(call(Map.of(
                    "unset", java.util.List.of("weighting.sourcePriority.qq_group"))));

            assertThat(result.success()).as(result.message()).isTrue();
            assertThat(effective.get().weighting().sourcePriority()).doesNotContainKey("qq_group");
        }
    }
}
