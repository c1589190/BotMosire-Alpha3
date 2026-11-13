package io.mosire.bma3.xvfb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlatformProbeTest {

    @Test
    void probeAlwaysReturnsAConsistentVerdict() {
        PlatformProbe.Capabilities caps = PlatformProbe.probe();

        assertThat(caps.platform()).isNotBlank();
        if (caps.supported()) {
            assertThat(caps.xvfb()).as("supported 为真时必须有 Xvfb 路径").isNotBlank();
            assertThat(caps.reason()).isEqualTo("OK");
            assertThat(caps.hint()).isEmpty();
        } else if ("NO_XVFB".equals(caps.reason())) {
            assertThat(caps.hint())
                    .as("缺 Xvfb 时必须给出安装命令（用户要求：提醒 Agent 装一个）")
                    .contains("install")
                    .contains("xvfb");
        }
    }

    @Test
    void whichFindsAShell() {
        assertThat(PlatformProbe.which("sh")).isNotBlank();
        assertThat(PlatformProbe.which("definitely-not-a-real-binary-xyz")).isEmpty();
    }

    @Test
    void missingListAlwaysCarriesAnActionableHint() {
        PlatformProbe.Capabilities caps = new PlatformProbe.Capabilities(
                "linux", false, "NO_XVFB", PlatformProbe.installHint(),
                "", "", "", "", "", "", "", "", "", -1);

        List<Map<String, String>> missing = PlatformProbe.missing(caps);

        assertThat(missing).hasSize(1);
        assertThat(missing.get(0).get("tool")).isEqualTo("Xvfb");
        assertThat(missing.get(0).get("hint")).contains("sudo apt install xvfb");
    }

    @Test
    void nonLinuxHasNoMissingToolHintsBecauseTheModuleSimplyDoesNotApply() {
        PlatformProbe.Capabilities caps = new PlatformProbe.Capabilities(
                "windows", false, "PLATFORM_UNSUPPORTED", "windows hint",
                "", "", "", "", "", "", "", "", "", -1);

        assertThat(PlatformProbe.missing(caps)).isEmpty();
    }
}
