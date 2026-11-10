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
        assertThat(config.qqconnect().send().maxTextChars()).isEqualTo(3000);
        assertThat(config.weighting().platformWeight("qq_private")).isEqualTo(300);
        assertThat(config.weighting().platformWeight("bilibili")).isZero();
        assertThat(config.logging().level()).isEqualTo("INFO");
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
                  platformWeights:
                    qq_group: 50
                    bilibili: 120
                  signals:
                    mention: 250
                """);

        AppConfig config = ConfigLoader.load(tempDir);
        assertThat(config.inbox().display().maxMessagesPerItem()).isEqualTo(4);
        assertThat(config.inbox().display().maxItemsPerPanel()).isEqualTo(8);
        assertThat(config.inbox().display().includeOmittedCount()).isFalse();
        assertThat(config.qqconnect().send().maxTextChars()).isEqualTo(2800);
        assertThat(config.qqconnect().send().overflow()).isEqualTo("split");
        assertThat(config.weighting().platformWeight("qq_group")).isEqualTo(50);
        assertThat(config.weighting().platformWeight("bilibili")).isEqualTo(120);
        assertThat(config.weighting().signals().mention()).isEqualTo(250);
    }

    @Test
    void rejectsInvalidDisplayRange() throws Exception {
        writeConfig("""
                inbox:
                  display:
                    maxMessagesPerItem: 2
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

    private void writeConfig(String yaml) throws Exception {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("app.yaml"), yaml);
    }
}
