package io.mosire.bma3.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.bma3.app.config.AppConfig;
import io.mosire.bma3.app.config.ConfigLoader;
import io.mosire.bma3.xvfb.XvfbService;
import io.mosire.bma3.xvfb.XvfbTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** xvfb 在 app 里的接线：配置能读到、工具面是 spec §5 那 12 个。 */
class XvfbWiringTest {

    @Test
    void xvfbDefaultsToEnabledWithACwdWorkspaceRoot() {
        AppConfig config = AppConfig.defaults();

        assertThat(config.xvfb().enabled()).isTrue();
        assertThat(config.xvfb().workspaceRoot()).isEmpty();
        assertThat(config.xvfb().root())
                .isEqualTo(Path.of(".xvfb").toAbsolutePath().normalize());
        assertThat(config.xvfb().width()).isEqualTo(1440);
        assertThat(config.xvfb().height()).isEqualTo(900);
        assertThat(config.xvfb().depth()).isEqualTo(24);
    }

    @Test
    void configFileOverridesTheWorkspaceRoot(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("config"));
        Path configFile = root.resolve("config/app.yaml");
        Files.writeString(configFile, """
                xvfb:
                  enabled: true
                  workspaceRoot: /tmp/bma3-xvfb-workspaces
                  width: 1280
                  height: 720
                  depth: 24
                """);

        AppConfig config = ConfigLoader.load(root);

        assertThat(config.xvfb().enabled()).isTrue();
        assertThat(config.xvfb().root())
                .isEqualTo(Path.of("/tmp/bma3-xvfb-workspaces").toAbsolutePath().normalize());
        assertThat(config.xvfb().width()).isEqualTo(1280);
        assertThat(config.xvfb().height()).isEqualTo(720);
    }

    @Test
    void theToolSurfaceIsExactlyTheTwelveContractedTools(@TempDir Path root) {
        XvfbService service = new XvfbService(root.resolve(".xvfb"));
        Set<String> names = XvfbTools.all(service).stream()
                .map(tool -> tool.name())
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrderElementsOf(List.of(
                "xvfb_status",
                "xvfb_workspace_open",
                "xvfb_workspace_close",
                "xvfb_workspace_list",
                "xvfb_launch",
                "xvfb_screenshot",
                "xvfb_windows",
                "xvfb_exec",
                "xvfb_key",
                "xvfb_type",
                "xvfb_act",
                "xvfb_tree"));
    }
}
