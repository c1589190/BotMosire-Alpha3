package io.mosire.bma3.xvfb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 平台检测：这台机器能不能开 Xvfb 工作区，缺什么、怎么装。
 *
 * <p>用户原话（D-055）：「额外带平台检测」「如果本机未检测到 xvfb 的话，提醒 Agent 装一个就行」。
 * 所以这里的产物不是布尔值，而是<b>一份带安装提示的能力清单</b>：能干什么、缺什么、补哪一句命令。
 *
 * <p>不报隔离级别——按用户补充裁定，Agent 自身即全权限用户，不需要被告知「你在裸奔」。
 */
public final class PlatformProbe {

    /** PATH 之外的常见安装位置（systemd/non-login shell 下 PATH 可能很短）。 */
    private static final List<String> EXTRA_BIN_DIRS = List.of(
            "/usr/bin", "/usr/local/bin", "/bin", "/usr/sbin", "/snap/bin");

    /** 探测结果。空串表示「没找到」，调用方据此决定 reason/hint。 */
    public record Capabilities(
            String platform,
            boolean supported,
            String reason,
            String hint,
            String xvfb,
            String xdotool,
            String xwininfo,
            String xdpyinfo,
            String screenshotTool,
            String screenshotBinary,
            String windowManager,
            String clipboard,
            String shell,
            int cjkFonts) {

        public boolean canScreenshot() {
            return !screenshotTool.isEmpty();
        }

        public boolean canInput() {
            return !xdotool.isEmpty();
        }
    }

    private PlatformProbe() {
    }

    public static Capabilities probe() {
        String platform = platform();
        if (!"linux".equals(platform)) {
            return new Capabilities(platform, false, "PLATFORM_UNSUPPORTED",
                    "Xvfb 是 X11 显示服务，只在 Linux 上可用。"
                            + "Windows 没有单一等价原语：需要 Indirect Display Driver 造虚拟显示器"
                            + " + 一个登录中的交互式会话 + UI Automation 做语义树 + SendInput 做输入；"
                            + "macOS 对应的是 CGDisplay 虚拟屏 + AXUIElement + CGEvent。"
                            + "本模块不做兼容层，需要时另立后端模块。",
                    "", "", "", "", "", "", "", "", "", -1);
        }

        String xvfb = which("Xvfb");
        String xdotool = which("xdotool");
        String xwininfo = which("xwininfo");
        String xdpyinfo = which("xdpyinfo");
        String wm = firstOf("openbox", "fluxbox", "xfwm4", "metacity", "twm");
        String clipboard = firstOf("xclip", "xsel");

        // which() 找不到时返回空串而不是 null：必须判空串，否则会把缺失的截图工具当成可用。
        String shotTool = "";
        String shotBin = "";
        for (String candidate : List.of("import", "scrot", "ffmpeg")) {
            String found = which(candidate);
            if (!found.isEmpty()) {
                shotTool = candidate;
                shotBin = found;
                break;
            }
        }

        int cjkFonts = cjkFontCount();

        boolean supported = !xvfb.isEmpty();
        String reason = supported ? "OK" : "NO_XVFB";
        String hint = supported ? "" : installHint();

        return new Capabilities(platform, supported, reason, hint, xvfb, xdotool, xwininfo,
                xdpyinfo, shotTool, shotBin, wm, clipboard, which("sh"), cjkFonts);
    }

    /** 缺件清单：每条都是「缺什么 + 怎么补」，给 {@code xvfb_status} 直接回给 Agent。 */
    public static List<Map<String, String>> missing(Capabilities caps) {
        List<Map<String, String>> out = new ArrayList<>();
        if (!caps.supported()) {
            if ("NO_XVFB".equals(caps.reason())) {
                out.add(entry("Xvfb", installHint()));
            }
            return out;
        }
        if (!caps.canScreenshot()) {
            out.add(entry("screenshot", "sudo apt install imagemagick"
                    + "（或 scrot / ffmpeg；三者任意一个即可）"));
        }
        if (!caps.canInput()) {
            out.add(entry("xdotool", "sudo apt install xdotool"
                    + "（xvfb_key / xvfb_type / xvfb_act 需要它）"));
        }
        if (caps.clipboard().isEmpty()) {
            out.add(entry("clipboard", "sudo apt install xclip"
                    + "（想用剪贴板粘贴中文时需要；否则只能 xdotool type 打 ASCII）"));
        }
        if (caps.xwininfo().isEmpty()) {
            out.add(entry("xwininfo", "sudo apt install x11-utils（xvfb_windows 需要它）"));
        }
        if (caps.cjkFonts() == 0) {
            out.add(entry("cjkFonts", "sudo apt install fonts-noto-cjk"
                    + "（否则截图里的中文会变方块）"));
        }
        return out;
    }

    public static String installHint() {
        return "未检测到 Xvfb。安装：sudo apt install xvfb（Debian/Ubuntu）；"
                + "sudo dnf install xorg-x11-server-Xvfb（Fedora/RHEL）；"
                + "sudo pacman -S xorg-server-xvfb（Arch）。装完再调一次 xvfb_status。";
    }

    private static Map<String, String> entry(String tool, String hint) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("tool", tool);
        map.put("hint", hint);
        return map;
    }

    /** {@code which}：先 PATH，再常见目录。返回可执行文件的绝对路径或空串。 */
    static String which(String name) {
        String pathEnv = System.getenv("PATH");
        List<String> dirs = new ArrayList<>();
        if (pathEnv != null) {
            for (String dir : pathEnv.split(":")) {
                if (!dir.isBlank()) {
                    dirs.add(dir);
                }
            }
        }
        dirs.addAll(EXTRA_BIN_DIRS);
        for (String dir : dirs) {
            Path candidate = Path.of(dir, name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return "";
    }

    private static String firstOf(String... names) {
        for (String name : names) {
            String found = which(name);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return "";
    }

    /** 中文字体数量：{@code fc-list :lang=zh} 的行数；没有 fontconfig 则返回 -1（未知，不当成缺）。 */
    private static int cjkFontCount() {
        String fcList = which("fc-list");
        if (fcList.isEmpty()) {
            return -1;
        }
        try {
            Process process = new ProcessBuilder(fcList, ":lang=zh")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            if (process.exitValue() != 0) {
                return -1;
            }
            int count = 0;
            for (String line : output.split("\n")) {
                if (!line.isBlank()) {
                    count++;
                }
            }
            return count;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            return "linux";
        }
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        return "unknown";
    }
}
