package io.mosire.bma3.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.bma3.core.config.ConfigException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void usesDefaultsWhenConfigFileIsMissing() {
        AppConfig config = ConfigLoader.load(tempDir);

        assertThat(config.dataDir()).isEqualTo(Path.of("data"));
        assertThat(config.server().host()).isEqualTo("127.0.0.1");
        assertThat(config.server().port()).isEqualTo(8765);
        assertThat(config.server().mcpPath()).isEqualTo("/mcp");
        assertThat(config.inbox().inboxId()).isEqualTo("bma3");
        assertThat(config.inbox().display().maxMessagesPerItem()).isEqualTo(6);
        assertThat(config.inbox().display().maxItemsPerPanel()).isEqualTo(6);
        assertThat(config.inbox().display().mode()).isEqualTo("auto");
        assertThat(config.inbox().display().summaryMaxCharsPerItem()).isEqualTo(200);
        assertThat(config.inbox().display().summaryAboveItems()).isEqualTo(8);
        assertThat(config.inbox().waitPolicy().enabled()).isTrue();
        assertThat(config.inbox().waitPolicy().defaultQuietSeconds()).isZero();
        assertThat(config.inbox().waitPolicy().defaultMaxWaitSeconds()).isEqualTo(20);
        assertThat(config.inbox().waitPolicy().sources()).isEmpty();
        assertThat(config.qqconnect().send().maxTextChars()).isEqualTo(3000);
        assertThat(config.qqconnect().media().dir()).isEmpty();
        assertThat(config.qqconnect().media().maxInlineBytes()).isEqualTo(65536L);
        assertThat(config.qqconnect().media().maxDownloadBytes()).isEqualTo(33_554_432L);
        assertThat(config.weighting().sourcePriority()).containsEntry("qq_private", 300);
        assertThat(config.weighting().sourcePriority()).containsEntry("qq_group", 200);
        assertThat(config.weighting().sourcePriority().getOrDefault("bilibili", 0)).isZero();
        assertThat(config.weighting().immediateThreshold()).isEqualTo(300);
        assertThat(config.weighting().dropBelow()).isEqualTo(-300);
        assertThat(config.logging().level()).isEqualTo("INFO");
        // Debug surface defaults: mounted, capped at 32 concurrent questions, priority 350.
        assertThat(config.debug().enabled()).isTrue();
        assertThat(config.debug().maxPending()).isEqualTo(32);
        assertThat(config.debug().priority()).isEqualTo(350);
        assertThat(config.schedule().enabled()).isTrue();
        assertThat(config.schedule().tickSeconds()).isEqualTo(1);
        assertThat(config.schedule().maxSchedules()).isEqualTo(200);
        assertThat(config.schedule().defaultMissedPolicy()).isEqualTo("skip");
    }

    @Test
    void readsTheDebugSection() throws Exception {
        writeConfig("""
                debug:
                  enabled: false
                  maxPending: 0
                  askTimeoutSeconds: 30
                  questionMaxBytes: 512
                  answerMaxBytes: 1024
                  retention: 50
                  priority: 400
                schedule:
                  enabled: false
                  tickSeconds: 5
                  maxSchedules: 7
                  defaultMissedPolicy: once
                """);

        AppConfig config = ConfigLoader.load(tempDir);

        assertThat(config.debug().enabled()).isFalse();
        assertThat(config.debug().maxPending()).isZero();
        assertThat(config.debug().askTimeoutSeconds()).isEqualTo(30);
        assertThat(config.debug().questionMaxBytes()).isEqualTo(512);
        assertThat(config.debug().answerMaxBytes()).isEqualTo(1024);
        assertThat(config.debug().retention()).isEqualTo(50);
        assertThat(config.debug().priority()).isEqualTo(400);
        assertThat(config.schedule().enabled()).isFalse();
        assertThat(config.schedule().tickSeconds()).isEqualTo(5);
        assertThat(config.schedule().maxSchedules()).isEqualTo(7);
        assertThat(config.schedule().defaultMissedPolicy()).isEqualTo("once");
    }

    @Test
    void interpolatesEnvironmentReferences() throws Exception {
        writeConfig("""
                dataDir: ${ENV:BMA3_DATA_DIR}
                server:
                  tokenEnv: ${ENV:BMA3_TOKEN_ENV}
                """);

        AppConfig config = ConfigLoader.load(tempDir, Map.of(
                "BMA3_DATA_DIR", "/srv/bma3",
                "BMA3_TOKEN_ENV", "BMA3_TEST_TOKEN"));

        assertThat(config.dataDir()).isEqualTo(Path.of("/srv/bma3"));
        assertThat(config.server().tokenEnv()).isEqualTo("BMA3_TEST_TOKEN");
    }

    @Test
    void parsesDisplaySendAndPlatformWeights() throws Exception {
        writeConfig("""
                inbox:
                  display:
                    maxMessagesPerItem: 4
                    maxItemsPerPanel: 8
                    includeOmittedCount: false
                qqconnect:
                  send:
                    maxTextChars: 2800
                    overflow: split
                weighting:
                  immediateThreshold: 400
                  sourcePriority:
                    qq_group: 50
                    bilibili: 120
                  attributes:
                    qq_at_bot: 250
                """);

        AppConfig config = ConfigLoader.load(tempDir);
        assertThat(config.inbox().display().maxMessagesPerItem()).isEqualTo(4);
        assertThat(config.inbox().display().maxItemsPerPanel()).isEqualTo(8);
        assertThat(config.inbox().display().includeOmittedCount()).isFalse();
        assertThat(config.qqconnect().send().maxTextChars()).isEqualTo(2800);
        assertThat(config.qqconnect().send().overflow()).isEqualTo("split");
        assertThat(config.weighting().sourcePriority()).containsEntry("qq_group", 50);
        assertThat(config.weighting().sourcePriority()).containsEntry("bilibili", 120);
        assertThat(config.weighting().attribute("qq_at_bot")).isEqualTo(250);
        assertThat(config.weighting().immediateThreshold()).isEqualTo(400);
    }

    @Test
    void parsesInboxWaitRulesAndSummaryDisplay() throws Exception {
        writeConfig("""
                inbox:
                  display:
                    mode: summary
                    summaryMaxCharsPerItem: 160
                    summaryAboveItems: 4
                  wait:
                    enabled: true
                    defaultQuietSeconds: 0
                    defaultMaxWaitSeconds: 25
                    sources:
                      - source: qq_private
                        quietSeconds: 8
                        maxWaitSeconds: 20
                      - source: bma3_debug
                        quietSeconds: 0
                        maxWaitSeconds: 5
                """);

        AppConfig config = ConfigLoader.load(tempDir);

        assertThat(config.inbox().display().mode()).isEqualTo("summary");
        assertThat(config.inbox().display().summaryMaxCharsPerItem()).isEqualTo(160);
        assertThat(config.inbox().display().summaryAboveItems()).isEqualTo(4);
        assertThat(config.inbox().waitPolicy().enabled()).isTrue();
        assertThat(config.inbox().waitPolicy().defaultQuietSeconds()).isZero();
        assertThat(config.inbox().waitPolicy().defaultMaxWaitSeconds()).isEqualTo(25);
        assertThat(config.inbox().waitPolicy().sources()).hasSize(2);
        assertThat(config.inbox().waitPolicy().sources().getFirst().source())
                .isEqualTo("qq_private");
        assertThat(config.inbox().waitPolicy().sources().getFirst().quietSeconds())
                .isEqualTo(8);
    }

    @Test
    void rejectsInvalidInboxWaitRule() throws Exception {
        writeConfig("""
                inbox:
                  wait:
                    sources:
                      - source: qq_private
                        quietSeconds: 9
                        maxWaitSeconds: 8
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("maxWaitSeconds");
    }

    @Test
    void rejectsInvalidDisplayRange() throws Exception {
        writeConfig("""
                inbox:
                  display:
                    maxMessagesPerItem: 0
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("maxMessagesPerItem");
    }

    @Test
    void rejectsInvalidSendLimit() throws Exception {
        writeConfig("""
                qqconnect:
                  send:
                    maxTextChars: 4000
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("maxTextChars");
    }

    @Test
    void rejectsInvalidPort() throws Exception {
        writeConfig("""
                server:
                  port: 70000
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("port");
    }


    @Test
    void defaultsToTokenAuthWithNoEnvFileOverride() {
        AppConfig config = ConfigLoader.load(tempDir);

        assertThat(config.security().auth()).isEqualTo(AppConfig.Security.AUTH_TOKEN);
        assertThat(config.security().requiresToken()).isTrue();
        assertThat(config.security().envFile()).isEmpty();
    }

    @Test
    void readsExplicitNoAuthMode() throws Exception {
        writeConfig("""
                security:
                  auth: none
                  envFile: /srv/bma3/.bma3env
                """);

        AppConfig config = ConfigLoader.load(tempDir);

        assertThat(config.security().auth()).isEqualTo(AppConfig.Security.AUTH_NONE);
        assertThat(config.security().requiresToken()).isFalse();
        assertThat(config.security().envFile()).isEqualTo("/srv/bma3/.bma3env");
    }

    @Test
    void rejectsUnknownAuthMode() throws Exception {
        writeConfig("""
                security:
                  auth: maybe
                """);

        assertThatThrownBy(() -> ConfigLoader.load(tempDir))
                .isInstanceOf(io.mosire.bma3.core.config.ConfigException.class)
                .hasMessageContaining("security.auth");
    }

    private void writeConfig(String yaml) throws Exception {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("app.yaml"), yaml);
    }
}
