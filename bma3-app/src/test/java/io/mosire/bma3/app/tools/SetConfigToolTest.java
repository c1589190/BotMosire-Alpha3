package io.mosire.bma3.app.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    void normalDelayCanBeHotReloadedUpToTwentyFourHours() throws Exception {
        writeConfig();
        Map<String, String> env = Map.of("BMA3_TEST_TOKEN", "token");
        Path file = root.resolve("config/app.yaml");
        AtomicReference<AppConfig> effective = new AtomicReference<>(ConfigLoader.load(root, env));
        try (ConfigWatcher watcher = ConfigWatcher.watch(root, env, effective::set)) {
            SetConfigTool tool = new SetConfigTool(new ConfigToolContext(root, env, effective::get));
            for (int delay : new int[]{1800, 86_400}) {
                ToolResult result = tool.execute(call(Map.of(
                        "patch", Map.of("weighting.normalDelaySeconds", delay))));
                assertThat(result.success()).as(result.message()).isTrue();
                assertThat(new ObjectMapper().readTree(result.message()).path("reloaded")
                        .asBoolean()).isTrue();
                assertThat(effective.get().weighting().normalDelaySeconds()).isEqualTo(delay);
                assertThat(ConfigLoader.load(root, env).weighting().normalDelaySeconds())
                        .isEqualTo(delay);
            }

            String valid = Files.readString(file);
            ToolResult rejected = tool.execute(call(Map.of(
                    "patch", Map.of("weighting.normalDelaySeconds", 86_401))));
            assertThat(rejected.success()).isFalse();
            assertThat(rejected.code()).isEqualTo("CONFIG_INVALID");
            assertThat(Files.readString(file)).isEqualTo(valid);
            assertThat(effective.get().weighting().normalDelaySeconds()).isEqualTo(86_400);
        }
    }

    @Test
    void getConfigExposesOfficialSafeViewWithoutSecretValues() throws Exception {
        Path configDir = root.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("app.yaml"), """
                qqofficial:
                  accounts:
                    - accountId: official-main
                      appId: "synthetic-app-id-main"
                      appSecretEnv: BMA3_QQ_OFFICIAL_APP_SECRET
                      enabled: true
                      shard: [0, 1]
                    - accountId: official-disabled
                      appId: "synthetic-app-id-disabled"
                      appSecretFile: /run/secrets/synthetic-official.secret
                      enabled: false
                      shard: [0, 1]
                  transport: websocket
                  reconnect:
                    initialMs: 1500
                    maxMs: 120000
                    jitter: false
                  http:
                    connectTimeoutSeconds: 7
                    requestTimeoutSeconds: 25
                  matching:
                    mode: loose
                    strictTimeToleranceSeconds: 12
                """);
        String sentinelSecret = "phase1-get-config-secret-sentinel-6d82";
        Map<String, String> env = Map.of(
                "BMA3_QQ_OFFICIAL_APP_SECRET", sentinelSecret);
        AppConfig config = ConfigLoader.load(root, env);
        GetConfigTool tool = new GetConfigTool(new ConfigToolContext(root, env, () -> config));

        ToolResult result = tool.execute(call(Map.of()));

        assertThat(result.success()).as(result.message()).isTrue();
        JsonNode official = new ObjectMapper().readTree(result.message()).path("qqofficial");
        assertThat(official.path("transport").asText()).isEqualTo("websocket");
        assertThat(official.path("accounts").size()).isEqualTo(2);
        JsonNode enabled = official.path("accounts").get(0);
        assertThat(enabled.path("accountId").asText()).isEqualTo("official-main");
        assertThat(enabled.path("appId").asText()).isEqualTo("synthetic-app-id-main");
        assertThat(enabled.path("enabled").asBoolean()).isTrue();
        assertThat(enabled.path("shard").toString()).isEqualTo("[0,1]");
        assertThat(enabled.path("appSecretEnv").asText())
                .isEqualTo("BMA3_QQ_OFFICIAL_APP_SECRET");
        assertThat(enabled.path("appSecretFile").asText()).isEmpty();
        JsonNode disabled = official.path("accounts").get(1);
        assertThat(disabled.path("accountId").asText()).isEqualTo("official-disabled");
        assertThat(disabled.path("enabled").asBoolean()).isFalse();
        assertThat(disabled.path("appSecretEnv").asText()).isEmpty();
        assertThat(disabled.path("appSecretFile").asText())
                .isEqualTo("/run/secrets/synthetic-official.secret");
        assertThat(official.path("reconnect").path("initialMs").asInt()).isEqualTo(1500);
        assertThat(official.path("reconnect").path("jitter").asBoolean()).isFalse();
        assertThat(official.path("http").path("requestTimeoutSeconds").asInt()).isEqualTo(25);
        assertThat(official.path("matching").path("mode").asText()).isEqualTo("loose");
        assertThat(official.path("matching").path("strictTimeToleranceSeconds").asInt())
                .isEqualTo(12);
        assertThat(result.message())
                .doesNotContain(sentinelSecret)
                .doesNotContain("phase1-get-config-secret-sentinel");
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
                    "patch", Map.of("inbox.display.maxMessagesPerItem", 0))));

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
