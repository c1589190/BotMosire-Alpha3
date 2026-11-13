package io.mosire.bma3.xvfb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Xvfb 工作区的编排：起 display、起窗口管理器、跑程序、截图、列窗口、注入输入。
 *
 * <p>设计要点见 {@code docs/superpowers/specs/2026-11-13-bma3-xvfb-design.md}：
 * 一个工作区 = 一个 display = 一次任务，可丢弃；缺件只提示不代装（I3）。
 */
public final class XvfbService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(XvfbService.class);
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(15);
    /** {@code 0x200002 "title": ("class" "Class") 1200x800+0+0  +0+0} —— 只取 id/标题，几何单独解析。 */
    private static final Pattern WINDOW_HEAD = Pattern.compile(
            "^\\s*(0x[0-9a-fA-F]+)\\s+\"([^\"]*)\"");
    /** {@code -tree} 会给两段几何（相对父窗口、绝对）；取最后一段（绝对）。 */
    private static final Pattern WINDOW_GEOMETRY = Pattern.compile(
            "(\\d+)x(\\d+)\\+(-?\\d+)\\+(-?\\d+)");

    private final WorkspaceStore store;
    private final PlatformProbe.Capabilities caps;
    /** 截图额外落一份的地方（通常是 {@code <dataDir>/downloads}）；null = 不额外落。 */
    private final Path artifactDir;
    private final Set<Integer> claimedDisplays = ConcurrentHashMap.newKeySet();

    public XvfbService(Path workspaceRoot) {
        this(workspaceRoot, null, PlatformProbe.probe());
    }

    public XvfbService(Path workspaceRoot, Path artifactDir) {
        this(workspaceRoot, artifactDir, PlatformProbe.probe());
    }

    public XvfbService(Path workspaceRoot, PlatformProbe.Capabilities caps) {
        this(workspaceRoot, null, caps);
    }

    public XvfbService(Path workspaceRoot, Path artifactDir, PlatformProbe.Capabilities caps) {
        this.store = new WorkspaceStore(workspaceRoot);
        this.artifactDir = artifactDir == null ? null : artifactDir.toAbsolutePath().normalize();
        this.caps = caps;
    }

    /** 截图额外落盘的目录；{@code null} 表示只留在工作区。 */
    public Path artifactDir() {
        return artifactDir;
    }

    public PlatformProbe.Capabilities capabilities() {
        return caps;
    }

    public Path workspaceRoot() {
        return store.root();
    }

    /** 一个工具失败时带出去的东西：错误码 + 人话 + （可选）安装提示。 */
    public static final class XvfbException extends RuntimeException {
        private final String code;

        public XvfbException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    // ---------------------------------------------------------------- status

    public Map<String, Object> status() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("platform", caps.platform());
        view.put("supported", caps.supported());
        view.put("reason", caps.reason());
        if (!caps.hint().isEmpty()) {
            view.put("hint", caps.hint());
        }
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("xvfb", caps.xvfb());
        tools.put("windowManager", caps.windowManager());
        tools.put("xdotool", caps.xdotool());
        tools.put("xwininfo", caps.xwininfo());
        tools.put("xdpyinfo", caps.xdpyinfo());
        tools.put("screenshot", caps.canScreenshot()
                ? caps.screenshotTool() + " (" + caps.screenshotBinary() + ")" : "");
        tools.put("clipboard", caps.clipboard());
        tools.put("shell", caps.shell());
        view.put("tools", tools);
        view.put("cjkFonts", caps.cjkFonts());
        List<Map<String, String>> missing = PlatformProbe.missing(caps);
        if (!missing.isEmpty()) {
            view.put("missing", missing);
        }
        view.put("workspaceRoot", store.root().toString());
        List<Map<String, Object>> workspaces = new ArrayList<>();
        for (Workspace workspace : store.list()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workspace", workspace.id());
            row.put("display", workspace.displayName());
            row.put("state", alive(workspace) ? workspace.state() : stale(workspace));
            row.put("dir", workspace.dir().toString());
            row.put("createdAt", workspace.createdAt());
            row.put("windows", "-");
            workspaces.add(row);
        }
        view.put("workspaces", workspaces);
        return view;
    }

    // ------------------------------------------------------------- workspace

    /**
     * 开一个工作区：分配 display → 建目录 → 起 Xvfb → 等就绪 → 起 WM。
     *
     * <p>同一个 id 重复 open 时返回既有实例（W1）；KEPT 的工作区会被重新拉起到<em>新</em> display。
     */
    public synchronized Workspace open(String requested, int width, int height, int depth,
                                       String windowManager) {
        requireSupported();
        if (requested != null && !requested.isBlank()) {
            Workspace existing = store.find(requested).orElseThrow(
                    () -> new XvfbException("WORKSPACE_NOT_FOUND", "找不到工作区: " + requested));
            if (alive(existing)) {
                return existing;
            }
            return start(existing.withDisplay(nextDisplay()), windowManager);
        }
        XvfbService.XvfbException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Workspace created = store.create(nextDisplay(), width, height, depth);
            try {
                return start(created, windowManager);
            } catch (XvfbService.XvfbException failure) {
                // 撞号（比如别的进程用 abstract socket 占了同一号）：清掉失败目录换个号再来
                store.close(created, false);
                last = failure;
            }
        }
        throw last == null ? new XvfbService.XvfbException("XVFB_START_FAILED", "无法启动 Xvfb") : last;
    }

    private Workspace start(Workspace workspace, String windowManager) {
        List<String> command = List.of(
                caps.xvfb(),
                workspace.displayName(),
                "-screen", "0", workspace.width() + "x" + workspace.height() + "x" + workspace.depth(),
                "-nolisten", "tcp",
                "-ac",
                "-noreset");
        Path log = workspace.dir().resolve("logs").resolve("xvfb.log");
        CommandRunner.note(log, "[bma3-xvfb] start " + String.join(" ", command));
        Workspace starting = workspace.withState(Workspace.STARTING).withError("");
        store.save(starting);
        try {
            Process process = CommandRunner.start(command, Map.of(), workspace.dir(), log);
            Workspace running = starting.withProcesses(process.pid(), 0L, List.of());
            if (!awaitReady(running)) {
                process.destroyForcibly();
                Workspace failed = running.withState(Workspace.FAILED)
                        .withError("Xvfb 15s 内未就绪；见 " + log);
                store.save(failed);
                throw new XvfbException("XVFB_START_FAILED", failed.error());
            }
            long wmPid = startWindowManager(running, windowManager);
            Workspace ready = running.withProcesses(process.pid(), wmPid, List.of())
                    .withState(Workspace.RUNNING);
            store.save(ready);
            CommandRunner.note(log, "[bma3-xvfb] ready " + ready.displayName()
                    + " pid=" + process.pid());
            return ready;
        } catch (IOException failure) {
            Workspace failed = starting.withState(Workspace.FAILED)
                    .withError("启动 Xvfb 失败: " + failure.getMessage());
            store.save(failed);
            throw new XvfbException("XVFB_START_FAILED", failed.error());
        }
    }

    private long startWindowManager(Workspace workspace, String requested) {
        String binary = requested != null && !requested.isBlank()
                ? PlatformProbe.which(requested) : caps.windowManager();
        if (binary == null || binary.isEmpty()) {
            return 0L;
        }
        Path log = workspace.dir().resolve("logs").resolve("wm.log");
        try {
            Process process = CommandRunner.start(List.of(binary), displayEnv(workspace),
                    workspace.dir(), log);
            return process.pid();
        } catch (IOException failure) {
            CommandRunner.note(log, "[bma3-xvfb] wm start failed: " + failure.getMessage());
            return 0L;
        }
    }

    /** 轮询 xdpyinfo 直到 display 就绪。没有 xdpyinfo 时退化为「进程活着 + 等一下」。 */
    private boolean awaitReady(Workspace workspace) {
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            ProcessHandle.of(workspace.xvfbPid())
                    .filter(ProcessHandle::isAlive)
                    .orElseThrow(() -> new XvfbException("XVFB_START_FAILED",
                            "Xvfb 进程已退出；见 " + workspace.dir().resolve("logs/xvfb.log")));
            if (caps.xdpyinfo().isEmpty()) {
                sleep(500);
                return true;
            }
            CommandRunner.Result probe = CommandRunner.run(
                    List.of(caps.xdpyinfo(), "-display", workspace.displayName()),
                    Duration.ofSeconds(5), Map.of(), workspace.dir());
            if (probe.ok()) {
                return true;
            }
            sleep(250);
        }
        return false;
    }

    public synchronized Workspace close(String requested, boolean keep) {
        Workspace workspace = requireWorkspace(requested, false);
        List<Long> victims = new ArrayList<>(workspace.appPids());
        if (workspace.wmPid() > 0) {
            victims.add(workspace.wmPid());
        }
        if (workspace.xvfbPid() > 0) {
            victims.add(workspace.xvfbPid());
        }
        for (long pid : victims) {
            ProcessHandle.of(pid).ifPresent(handle -> {
                handle.descendants().forEach(ProcessHandle::destroy);
                handle.destroy();
            });
        }
        sleep(300);
        for (long pid : victims) {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
        claimedDisplays.remove(workspace.display());
        Workspace closed = store.close(workspace, keep);
        LOG.info("xvfb workspace closed id={} display={} keep={}",
                closed.id(), closed.display(), keep);
        return closed;
    }

    public List<Workspace> list() {
        return store.list();
    }

    // ------------------------------------------------------------------ apps

    public long launch(String requested, List<String> argv) {
        Workspace workspace = requireRunning(requested);
        Path log = workspace.dir().resolve("logs")
                .resolve("app-" + System.currentTimeMillis() + ".log");
        try {
            Process process = CommandRunner.start(argv, displayEnv(workspace),
                    workspace.dir().resolve("home"), log);
            List<Long> pids = new ArrayList<>(workspace.appPids());
            pids.add(process.pid());
            store.save(workspace.withProcesses(workspace.xvfbPid(), workspace.wmPid(), pids));
            CommandRunner.note(log, "[bma3-xvfb] launched " + String.join(" ", argv));
            return process.pid();
        } catch (IOException failure) {
            throw new XvfbException("LAUNCH_FAILED",
                    "启动失败: " + failure.getMessage() + "；日志 " + log);
        }
    }

    public CommandRunner.Result exec(String requested, List<String> argv, long timeoutMs) {
        Workspace workspace = requireRunning(requested);
        return CommandRunner.run(argv, Duration.ofMillis(timeoutMs), displayEnv(workspace),
                workspace.dir().resolve("home"));
    }

    // ------------------------------------------------------------ observation

    /**
     * 标准截屏：三种取景方式，一套返回。
     *
     * <ul>
     *   <li><b>全屏</b>（默认）—— 根窗口，含窗口管理器的标题栏；</li>
     *   <li><b>窗口</b> —— 给 {@code xvfb_windows} 返回的 id，按该窗口的绝对几何裁剪；</li>
     *   <li><b>区域</b> —— {@code "x,y,w,h"}，屏幕坐标。</li>
     * </ul>
     *
     * <p>产物一律先落工作区 {@code shots/}，再<b>额外拷一份到 {@link #artifactDir}</b>
     * （通常 {@code <dataDir>/downloads}），这样 QQ 的 send_image/send_file 能直接拿去发，
     * 不用 Agent 自己 cp 一次。
     *
     * @param windowId {@code 0x…} 窗口 id；省略则全屏
     * @param region   {@code x,y,w,h}；与 {@code windowId} 互斥，优先取窗口
     */
    public Map<String, Object> screenshot(String requested, String windowId, String region,
                                          String format) {
        Workspace workspace = requireRunning(requested);
        if (!caps.canScreenshot()) {
            throw new XvfbException("NO_SCREENSHOT_BACKEND",
                    "没有截图后端。安装：sudo apt install imagemagick"
                            + "（或 scrot / ffmpeg，任意一个即可）");
        }
        Rect rect = null;
        if (windowId != null && !windowId.isBlank()) {
            rect = windowRect(workspace, windowId);
        } else if (region != null && !region.isBlank()) {
            rect = parseRegion(region, workspace);
        }
        String ext = "jpeg".equalsIgnoreCase(format) || "jpg".equalsIgnoreCase(format)
                ? "jpg" : "png";
        String kind = windowId != null && !windowId.isBlank() ? "-win"
                : rect != null ? "-region" : "";
        String stamp = "xvfb-" + workspace.id() + "-" + System.currentTimeMillis() + kind
                + "." + ext;
        Path out = workspace.dir().resolve("shots").resolve(stamp);
        try {
            Files.createDirectories(out.getParent());
        } catch (IOException failure) {
            throw new XvfbException("SCREENSHOT_FAILED", "建 shots 目录失败: " + failure.getMessage());
        }
        Capture capture = capture(workspace, rect, out);
        if (!capture.ok()) {
            throw new XvfbException("SCREENSHOT_FAILED",
                    "截图失败（" + capture.backend() + " rc=" + capture.rc() + "）: "
                            + trim(capture.stderr()));
        }
        long bytes;
        int width = 0;
        int height = 0;
        try {
            bytes = Files.size(out);
            var image = javax.imageio.ImageIO.read(out.toFile());
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (IOException failure) {
            bytes = -1;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("path", out.toString());
        if (artifactDir != null) {
            view.put("download", copyToArtifacts(out, stamp));
        }
        view.put("bytes", bytes);
        view.put("width", width);
        view.put("height", height);
        view.put("backend", capture.backend());
        if (rect != null) {
            view.put("region", rect.x() + "," + rect.y() + "," + rect.width() + "," + rect.height());
        }
        if (windowId != null && !windowId.isBlank()) {
            view.put("window", windowId);
        }
        view.put("workspace", workspace.id());
        view.put("display", workspace.displayName());
        return view;
    }

    /** 取景矩形（屏幕坐标，已裁到屏幕内）。 */
    private record Rect(int x, int y, int width, int height) {
    }

    private record Capture(boolean ok, int rc, String backend, String stderr) {
    }

    /**
     * 三种后端按「能精确取景」优先：import（可抓被遮挡窗口）→ scrot（支持 -a 区域）→ ffmpeg（x11grab 偏移裁剪）。
     */
    private Capture capture(Workspace workspace, Rect rect, Path out) {
        List<String> command;
        switch (caps.screenshotTool()) {
            case "import" -> command = rect == null
                    ? List.of(caps.screenshotBinary(), "-display", workspace.displayName(),
                            "-window", "root", out.toString())
                    : List.of(caps.screenshotBinary(), "-display", workspace.displayName(),
                            "-crop", rect.width() + "x" + rect.height() + "+" + rect.x() + "+" + rect.y(),
                            "-window", "root", out.toString());
            case "scrot" -> {
                List<String> base = new ArrayList<>(List.of(caps.screenshotBinary(), "-o", "-z"));
                if (rect != null) {
                    base.add("-a");
                    base.add(rect.x() + "," + rect.y() + "," + rect.width() + "," + rect.height());
                }
                base.add(out.toString());
                command = base;
            }
            default -> {
                List<String> base = new ArrayList<>(List.of(caps.screenshotBinary(),
                        "-hide_banner", "-loglevel", "error", "-f", "x11grab"));
                if (rect == null) {
                    base.addAll(List.of("-video_size",
                            workspace.width() + "x" + workspace.height(),
                            "-i", workspace.displayName()));
                } else {
                    base.addAll(List.of("-video_size", rect.width() + "x" + rect.height(),
                            "-i", workspace.displayName() + "+" + rect.x() + "," + rect.y()));
                }
                base.addAll(List.of("-frames:v", "1", "-y", out.toString()));
                command = base;
            }
        }
        CommandRunner.Result result = CommandRunner.run(command, Duration.ofSeconds(20),
                displayEnv(workspace), workspace.dir());
        boolean ok = result.ok() && Files.isRegularFile(out) && sizeOf(out) > 0;
        return new Capture(ok, result.rc(), caps.screenshotTool(), result.stderr());
    }

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException failure) {
            return -1;
        }
    }

    /** 窗口 → 屏幕坐标矩形：{@code xwininfo -id} 的绝对几何。 */
    private Rect windowRect(Workspace workspace, String windowId) {
        if (!windowId.matches("0x[0-9a-fA-F]{1,12}")) {
            throw new XvfbException("INVALID_ARGUMENT",
                    "window 必须是 xvfb_windows 返回的十六进制 id，例如 0x400002");
        }
        if (caps.xwininfo().isEmpty()) {
            throw new XvfbException("NO_XWININFO", "没有 xwininfo：sudo apt install x11-utils");
        }
        CommandRunner.Result result = CommandRunner.run(
                List.of(caps.xwininfo(), "-id", windowId, "-display", workspace.displayName()),
                Duration.ofSeconds(10), displayEnv(workspace), workspace.dir());
        if (!result.ok()) {
            throw new XvfbException("WINDOW_NOT_FOUND",
                    "取不到窗口 " + windowId + " 的几何: " + trim(result.stderr()));
        }
        Integer x = numberAfter(result.stdout(), "Absolute upper-left X:");
        Integer y = numberAfter(result.stdout(), "Absolute upper-left Y:");
        Integer width = numberAfter(result.stdout(), "Width:");
        Integer height = numberAfter(result.stdout(), "Height:");
        if (x == null || y == null || width == null || height == null) {
            throw new XvfbException("WINDOW_NOT_FOUND", "xwininfo 没给出完整几何: " + windowId);
        }
        return clamp(new Rect(x, y, width, height), workspace);
    }

    /** {@code "x,y,w,h"} → 矩形；解析不了就是 BAD_REQUEST（不猜）。 */
    private Rect parseRegion(String region, Workspace workspace) {
        String[] parts = region.split(",");
        if (parts.length != 4) {
            throw new XvfbException("INVALID_ARGUMENT", "region 必须是 \"x,y,w,h\"，收到: " + region);
        }
        try {
            return clamp(new Rect(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()),
                    Integer.parseInt(parts[3].trim())), workspace);
        } catch (NumberFormatException failure) {
            throw new XvfbException("INVALID_ARGUMENT", "region 里有非数字: " + region);
        }
    }

    /** 裁到屏幕内：x11grab 对越界区域会直接失败。 */
    private Rect clamp(Rect rect, Workspace workspace) {
        int x = Math.max(0, Math.min(rect.x(), workspace.width() - 1));
        int y = Math.max(0, Math.min(rect.y(), workspace.height() - 1));
        int width = Math.max(1, Math.min(rect.width(), workspace.width() - x));
        int height = Math.max(1, Math.min(rect.height(), workspace.height() - y));
        return new Rect(x, y, width, height);
    }

    private String copyToArtifacts(Path source, String fileName) {
        try {
            Files.createDirectories(artifactDir);
            Path target = artifactDir.resolve(fileName);
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return target.toString();
        } catch (IOException failure) {
            throw new XvfbException("SCREENSHOT_FAILED",
                    "拷贝到 " + artifactDir + " 失败: " + failure.getMessage());
        }
    }

    /** 从 xwininfo 输出里取 {@code label: 123} 的数字。 */
    private static Integer numberAfter(String text, String label) {
        int at = text.indexOf(label);
        if (at < 0) {
            return null;
        }
        int cursor = at + label.length();
        while (cursor < text.length() && !Character.isDigit(text.charAt(cursor))
                && text.charAt(cursor) != '-') {
            cursor++;
        }
        int start = cursor;
        if (cursor < text.length() && text.charAt(cursor) == '-') {
            cursor++;
        }
        while (cursor < text.length() && Character.isDigit(text.charAt(cursor))) {
            cursor++;
        }
        try {
            return Integer.parseInt(text.substring(start, cursor));
        } catch (RuntimeException failure) {
            return null;
        }
    }

    public List<Map<String, Object>> windows(String requested) {
        Workspace workspace = requireRunning(requested);
        if (caps.xwininfo().isEmpty()) {
            throw new XvfbException("NO_XWININFO",
                    "没有 xwininfo。安装：sudo apt install x11-utils（xvfb_windows 需要它）");
        }
        CommandRunner.Result result = CommandRunner.run(
                List.of(caps.xwininfo(), "-root", "-tree", "-display", workspace.displayName()),
                Duration.ofSeconds(10), displayEnv(workspace), workspace.dir());
        if (!result.ok()) {
            throw new XvfbException("WINDOWS_FAILED", "xwininfo 失败: " + trim(result.stderr()));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : result.stdout().split("\n")) {
            Matcher head = WINDOW_HEAD.matcher(line);
            if (!head.find()) {
                continue;
            }
            Matcher geometry = WINDOW_GEOMETRY.matcher(line);
            Integer width = null;
            Integer height = null;
            int x = 0;
            int y = 0;
            while (geometry.find()) {
                width = Integer.parseInt(geometry.group(1));
                height = Integer.parseInt(geometry.group(2));
                x = Integer.parseInt(geometry.group(3));
                y = Integer.parseInt(geometry.group(4));
            }
            if (width == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", head.group(1));
            row.put("title", head.group(2));
            row.put("width", width);
            row.put("height", height);
            row.put("x", x);
            row.put("y", y);
            out.add(row);
        }
        return out;
    }

    // ----------------------------------------------------------------- input

    public void key(String requested, String keys) {
        Workspace workspace = requireRunning(requested);
        requireInput();
        CommandRunner.Result result = xdotool(workspace, List.of("key", "--clearmodifiers", keys));
        if (!result.ok()) {
            throw new XvfbException("INPUT_FAILED", "xdotool key 失败: " + trim(result.stderr()));
        }
    }

    public String type(String requested, String text, String mode) {
        Workspace workspace = requireRunning(requested);
        requireInput();
        boolean clipboard = !"keys".equalsIgnoreCase(mode);
        if (clipboard && !caps.clipboard().isEmpty()) {
            CommandRunner.Result paste = CommandRunner.run(
                    List.of(caps.clipboard(), "-selection", "clipboard"),
                    Duration.ofSeconds(10), displayEnv(workspace), workspace.dir(), text);
            if (paste.ok()) {
                CommandRunner.Result result = xdotool(workspace,
                        List.of("key", "--clearmodifiers", "ctrl+v"));
                if (!result.ok()) {
                    throw new XvfbException("INPUT_FAILED",
                            "粘贴前聚焦失败: " + trim(result.stderr()));
                }
                return "clipboard";
            }
        }
        if (!isAscii(text)) {
            throw new XvfbException("NO_CLIPBOARD",
                    "非 ASCII 文本需要剪贴板：sudo apt install xclip"
                            + "（xdotool type 的键位映射打不出中文）");
        }
        CommandRunner.Result result = xdotool(workspace,
                List.of("type", "--clearmodifiers", "--delay", "12", "--", text));
        if (!result.ok()) {
            throw new XvfbException("INPUT_FAILED", "xdotool type 失败: " + trim(result.stderr()));
        }
        return "keys";
    }

    public void act(String requested, int x, int y, String action, int amount) {
        Workspace workspace = requireRunning(requested);
        requireInput();
        String op = action == null || action.isBlank() ? "click" : action.toLowerCase(Locale.ROOT);
        List<String> args = new ArrayList<>(List.of("mousemove", String.valueOf(x), String.valueOf(y)));
        List<List<String>> steps = new ArrayList<>();
        steps.add(args);
        switch (op) {
            case "move" -> {
                // 只移动
            }
            case "click" -> steps.add(List.of("click", "1"));
            case "double", "dblclick" -> steps.add(List.of("click", "--repeat", "2", "--delay", "80", "1"));
            case "right" -> steps.add(List.of("click", "3"));
            case "middle" -> steps.add(List.of("click", "2"));
            case "scroll" -> {
                int times = Math.max(1, Math.min(Math.abs(amount), 25));
                String button = amount < 0 ? "4" : "5";
                steps.add(List.of("click", "--repeat", String.valueOf(times), button));
            }
            default -> throw new XvfbException("INVALID_ARGUMENT",
                    "未知 action: " + action + "（可用 click/double/right/middle/move/scroll）");
        }
        for (List<String> step : steps) {
            CommandRunner.Result result = xdotool(workspace, step);
            if (!result.ok()) {
                throw new XvfbException("INPUT_FAILED",
                        "xdotool " + step.get(0) + " 失败: " + trim(result.stderr()));
            }
        }
    }

    public void tree(String requested) {
        Workspace workspace = requireRunning(requested);
        throw new XvfbException("ATSPI_UNSUPPORTED",
                "v1 未实现无障碍树（工作区 " + workspace.id() + "）。"
                        + "用 xvfb_screenshot 看画面 + xvfb_act 按坐标操作。");
    }

    // ----------------------------------------------------------------- utils

    private CommandRunner.Result xdotool(Workspace workspace, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(caps.xdotool());
        command.addAll(args);
        return CommandRunner.run(command, Duration.ofSeconds(15), displayEnv(workspace),
                workspace.dir());
    }

    private void requireSupported() {
        if (!caps.supported()) {
            throw new XvfbException(caps.reason(), caps.hint());
        }
    }

    private void requireInput() {
        if (!caps.canInput()) {
            throw new XvfbException("NO_XDOTOOL",
                    "没有 xdotool。安装：sudo apt install xdotool"
                            + "（xvfb_key / xvfb_type / xvfb_act 需要它）");
        }
    }

    /** W4：{@code workspace} 缺省 ⇒ 最近一个 RUNNING；没有就自动开一个。 */
    private Workspace requireWorkspace(String requested, boolean autoOpen) {
        if (requested != null && !requested.isBlank()) {
            Workspace found = store.find(requested).orElseThrow(
                    () -> new XvfbException("WORKSPACE_NOT_FOUND", "找不到工作区: " + requested));
            return found;
        }
        Optional<Workspace> running = store.latestRunning().filter(this::alive);
        if (running.isPresent()) {
            return running.get();
        }
        if (!autoOpen) {
            throw new XvfbException("WORKSPACE_NOT_FOUND", "没有可用工作区，先调 xvfb_workspace_open");
        }
        return open(null, 1440, 900, 24, null);
    }

    private Workspace requireRunning(String requested) {
        Workspace workspace = requireWorkspace(requested, true);
        if (!runningNow(workspace)) {
            throw new XvfbException("WORKSPACE_NOT_RUNNING",
                    "工作区 " + workspace.id() + " 当前 " + workspace.state()
                            + "；调 xvfb_workspace_open {workspace:\"" + workspace.id()
                            + "\"} 重新拉起");
        }
        return workspace;
    }

    private boolean runningNow(Workspace workspace) {
        return workspace.running() && alive(workspace);
    }

    private boolean alive(Workspace workspace) {
        if (workspace.xvfbPid() <= 0) {
            return false;
        }
        return ProcessHandle.of(workspace.xvfbPid()).filter(ProcessHandle::isAlive).isPresent();
    }

    private String stale(Workspace workspace) {
        return Workspace.RUNNING.equals(workspace.state()) ? "STALE" : workspace.state();
    }

    private Map<String, String> displayEnv(Workspace workspace) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DISPLAY", workspace.displayName());
        env.put("HOME", workspace.dir().resolve("home").toString());
        env.put("XDG_CONFIG_HOME", workspace.dir().resolve("home/.config").toString());
        return env;
    }

    private int nextDisplay() {
        int display = DisplayAllocator.allocate(Path.of("/tmp"), claimedDisplays);
        claimedDisplays.add(display);
        return display;
    }

    private static boolean isAscii(String text) {
        return text.chars().allMatch(c -> c < 128);
    }

    private static String trim(String text) {
        if (text == null) {
            return "";
        }
        String single = text.strip();
        return single.length() > 800 ? single.substring(0, 800) : single;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        for (Workspace workspace : store.list()) {
            if (workspace.running() || Workspace.STARTING.equals(workspace.state())) {
                try {
                    close(workspace.id(), true);
                } catch (RuntimeException failure) {
                    LOG.warn("xvfb 工作区关闭失败 id={}: {}", workspace.id(), failure.getMessage());
                }
            }
        }
    }
}
