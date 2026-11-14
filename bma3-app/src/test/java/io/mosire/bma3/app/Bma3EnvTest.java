package io.mosire.bma3.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.bma3.app.config.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Bma3EnvTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesBothTokensOnceAndKeepsThemOnRestart() {
        Path file = tempDir.resolve(".bma3env");

        Bma3Env first = Bma3Env.ensure(file, false);
        Bma3Env second = Bma3Env.ensure(file, false);

        assertThat(first.mcpToken()).hasSize(48).matches("[0-9a-f]{48}");
        assertThat(first.inboxToken()).hasSize(48).matches("[0-9a-f]{48}");
        assertThat(first.mcpToken()).isNotEqualTo(first.inboxToken());
        assertThat(second.mcpToken()).isEqualTo(first.mcpToken());
        assertThat(second.inboxToken()).isEqualTo(first.inboxToken());
        assertThat(second.degraded()).isFalse();
    }

    @Test
    void rotateReplacesBothValues() {
        Path file = tempDir.resolve(".bma3env");
        Bma3Env original = Bma3Env.ensure(file, false);

        Bma3Env rotated = Bma3Env.ensure(file, true);

        assertThat(rotated.mcpToken()).isNotEqualTo(original.mcpToken());
        assertThat(rotated.inboxToken()).isNotEqualTo(original.inboxToken());
        assertThat(Bma3Env.read(file)).containsKeys(Bma3Env.MCP_TOKEN, Bma3Env.INBOX_TOKEN);
    }

    @Test
    void fillsOnlyTheMissingToken() throws Exception {
        Path file = tempDir.resolve(".bma3env");
        Files.writeString(file, Bma3Env.MCP_TOKEN + "=keep-me\n");

        Bma3Env ensured = Bma3Env.ensure(file, false);

        assertThat(ensured.mcpToken()).isEqualTo("keep-me");
        assertThat(ensured.inboxToken()).hasSize(48);
    }

    @Test
    void writesOwnerOnlyPermissions() throws Exception {
        Path file = tempDir.resolve(".bma3env");

        Bma3Env.ensure(file, false);

        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            assertThat(permissions).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
        assertThat(file.getParent().resolve(".bma3env.tmp")).doesNotExist();
    }

    @Test
    void readIgnoresCommentsAndMalformedLines() throws Exception {
        Path file = tempDir.resolve(".bma3env");
        Files.writeString(file, """
                # comment
                MCP_TOKEN=abc

                nope
                INBOX_TOKEN=def
                """);

        Map<String, String> values = Bma3Env.read(file);

        assertThat(values).containsEntry(Bma3Env.MCP_TOKEN, "abc")
                .containsEntry(Bma3Env.INBOX_TOKEN, "def")
                .hasSize(2);
    }

    @Test
    void resolvesToConfigRootUnlessOverridden() {
        AppConfig defaults = AppConfig.defaults();
        assertThat(Bma3Env.resolve(defaults, tempDir))
                .isEqualTo(tempDir.toAbsolutePath().normalize().resolve(".bma3env"));

        AppConfig overridden = defaults.withSecurity(new AppConfig.Security(
                AppConfig.Security.AUTH_TOKEN, tempDir.resolve("custom.env").toString()));
        assertThat(Bma3Env.resolve(overridden, tempDir))
                .isEqualTo(tempDir.toAbsolutePath().normalize().resolve("custom.env"));
    }
}
