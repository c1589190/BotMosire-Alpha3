package io.mosire.bma3.xvfb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 缺件路径（spec §8 A2）：没装 Xvfb 时必须给安装提示、且<b>不创建工作区目录</b>。
 *
 * <p>本机有 Xvfb，所以用构造的 {@link PlatformProbe.Capabilities} 复现其它机器的样子。
 */
class XvfbServiceTest {

    private static PlatformProbe.Capabilities noXvfb() {
        return new PlatformProbe.Capabilities("linux", false, "NO_XVFB",
                PlatformProbe.installHint(), "", "", "", "", "", "", "", "", "", -1);
    }

    private static PlatformProbe.Capabilities windows() {
        return new PlatformProbe.Capabilities("windows", false, "PLATFORM_UNSUPPORTED",
                "Windows 需要 IDD + 交互会话 + UIA", "", "", "", "", "", "", "", "", "", -1);
    }

    @Test
    void missingXvfbIsReportedWithAnInstallHintAndNothingIsCreated(@TempDir Path root) {
        XvfbService service = new XvfbService(root.resolve(".xvfb"), noXvfb());

        Map<String, Object> status = service.status();
        assertThat(status.get("supported")).isEqualTo(false);
        assertThat(status.get("reason")).isEqualTo("NO_XVFB");
        assertThat(String.valueOf(status.get("hint"))).contains("sudo apt install xvfb");

        assertThatThrownBy(() -> service.open(null, 1440, 900, 24, null))
                .isInstanceOfSatisfying(XvfbService.XvfbException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("NO_XVFB");
                    assertThat(failure.getMessage()).contains("install");
                });
        assertThat(Files.exists(root.resolve(".xvfb"))).as("失败时不得留下工作区目录").isFalse();
    }

    @Test
    void nonLinuxPlatformIsReportedAsUnsupported(@TempDir Path root) {
        XvfbService service = new XvfbService(root.resolve(".xvfb"), windows());

        assertThat(service.status().get("platform")).isEqualTo("windows");
        assertThatThrownBy(() -> service.open(null, 1440, 900, 24, null))
                .isInstanceOfSatisfying(XvfbService.XvfbException.class,
                        failure -> assertThat(failure.code()).isEqualTo("PLATFORM_UNSUPPORTED"));
    }

    @Test
    void browserBinaryDefaultsAndCanBeOverridden(@TempDir Path root) {
        assertThat(new XvfbService(root.resolve(".xvfb")).browserBinary())
                .isEqualTo("/snap/bin/chromium");
        assertThat(new XvfbService(root.resolve(".xvfb"), null, "/usr/bin/chromium")
                .browserBinary()).isEqualTo("/usr/bin/chromium");
    }

    @Test
    void browserArgsDisableTheFirstRunTourAndKeepTheUrl() {
        var args = XvfbService.browserArgs("/usr/bin/google-chrome",
                Path.of("/tmp/bma3-profile"), "https://www.bilibili.com");
        assertThat(args).contains("--no-first-run", "--no-default-browser-check");
        assertThat(args).contains("--user-data-dir=/tmp/bma3-profile");
        assertThat(args.getLast()).isEqualTo("https://www.bilibili.com");
    }

    @Test
    void statusNeverExposesAnIsolationVerdict(@TempDir Path root) {
        XvfbService service = new XvfbService(root.resolve(".xvfb"), noXvfb());

        assertThat(service.status()).doesNotContainKey("isolation");
    }

    @Test
    void unknownWorkspaceIsRejected(@TempDir Path root) {
        XvfbService service = new XvfbService(root.resolve(".xvfb"), noXvfb());

        assertThatThrownBy(() -> service.close("ws-nope", false))
                .isInstanceOfSatisfying(XvfbService.XvfbException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_NOT_FOUND"));
    }
}
