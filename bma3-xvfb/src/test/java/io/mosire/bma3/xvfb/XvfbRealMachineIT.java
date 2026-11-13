package io.mosire.bma3.xvfb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 真机验证：真的起 Xvfb、真的跑一个程序、真的截图、真的收尸。
 *
 * <p>默认跳过（会在机器上起进程），跑法：
 * <pre>BMA3_XVFB_VERIFY=1 ./mvnw -o -pl bma3-xvfb -am test -Dtest=XvfbRealMachineIT -DfailIfNoSpecifiedTests=false</pre>
 *
 * <p>这条用例就是 spec §8 的 A1/A3/A4/A5/A7/A8 落地：缺件时必须给安装提示而不是抛栈。
 */
class XvfbRealMachineIT {

    private static final Path ROOT = Path.of("target/xvfb-verify/.xvfb");
    private static final java.util.concurrent.atomic.AtomicReference<String> lastShotPath =
            new java.util.concurrent.atomic.AtomicReference<>("");

    @Test
    @Timeout(180)
    @EnabledIfEnvironmentVariable(named = "BMA3_XVFB_VERIFY", matches = "1")
    void aWorkspaceCanBeOpenedDrivenObservedAndCollected() throws Exception {
        WorkspaceStore.deleteRecursively(Path.of("target/xvfb-verify"));
        Path downloads = Path.of("target/xvfb-verify/downloads");
        WorkspaceStore.deleteRecursively(Path.of("target/xvfb-verify/downloads"));
        XvfbService service = new XvfbService(ROOT, downloads);
        PlatformProbe.Capabilities caps = service.capabilities();

        // A1：自描述
        Map<String, Object> status = service.status();
        System.out.println("[verify] status = " + status);
        assertThat(status.get("platform")).isEqualTo("linux");
        assertThat(status.get("supported")).isEqualTo(true);
        assertThat(status.get("reason")).isEqualTo("OK");
        assertThat(status).doesNotContainKey("isolation");

        // A7：默认根目录就是传进来的 .xvfb
        assertThat(service.workspaceRoot()).isEqualTo(ROOT.toAbsolutePath().normalize());

        Workspace workspace;
        try {
            workspace = service.open(null, 1440, 900, 24, null);
        } catch (XvfbService.XvfbException failure) {
            // A2：缺 Xvfb 时必须给安装提示，而不是栈
            assertThat(failure.code()).isEqualTo("NO_XVFB");
            assertThat(failure.getMessage()).contains("install");
            System.out.println("[verify] Xvfb 未安装，提示 = " + failure.getMessage());
            return;
        }

        try {
            System.out.println("[verify] opened " + workspace.id() + " on " + workspace.displayName()
                    + " pid=" + workspace.xvfbPid());
            assertThat(workspace.state()).isEqualTo(Workspace.RUNNING);
            assertThat(workspace.dir()).isDirectory();
            assertThat(workspace.dir().resolve("home")).isDirectory();

            // 检测到窗口管理器就必须真的拉起来（有 WM 才有标题栏/焦点管理）
            if (caps.windowManager().isEmpty()) {
                System.out.println("[verify] 无窗口管理器，跳过 WM 断言");
            } else {
                assertThat(workspace.wmPid()).as("检测到 %s 就必须投递 wmPid", caps.windowManager())
                        .isPositive();
                System.out.println("[verify] window manager = " + caps.windowManager()
                        + " pid=" + workspace.wmPid());
            }

            // 起一个真实的 GUI 程序
            String chromium = PlatformProbe.which("chromium");
            if (!chromium.isEmpty()) {
                long pid = service.launch(workspace.id(), List.of(chromium,
                        "--no-sandbox", "--disable-gpu", "--no-first-run",
                        "--user-data-dir=" + workspace.dir().resolve("home/chromium"),
                        INTERACTIVE_PAGE));
                System.out.println("[verify] launched chromium pid=" + pid);

                    // A3：窗口列表。注意：有 WM 时 Openbox 会先挂一个 1x1 的辅助窗口，
                // 所以要等"目标程序的窗口"出现，而不是"任意窗口"。
                boolean appeared = awaitTitle(service, workspace, "chromium", 30);
                List<Map<String, Object>> windows = service.windows(workspace.id());
                System.out.println("[verify] windows = " + windows);
                if (!appeared) {
                    dumpLogs(workspace);
                }
                assertThat(appeared).as("Chromium 的窗口必须出现").isTrue();
                assertThat(windows).isNotEmpty();
            } else {
                System.out.println("[verify] 本机没有 chromium，跳过窗口断言");
            }

            // A4：全屏截图 = 屏幕尺寸
            Map<String, Object> shot = service.screenshot(workspace.id(), null, null, "png");
            lastShotPath.set(String.valueOf(shot.get("path")));
            System.out.println("[verify] fullscreen = " + shot);
            Path png = Path.of(String.valueOf(shot.get("path")));
            assertThat(png).exists();
            assertThat(Files.size(png)).isGreaterThan(1000L);
            assertThat(shot.get("width")).isEqualTo(1440);
            assertThat(shot.get("height")).isEqualTo(900);

            // —— 标准截屏：窗口取景 ——
            // 取面积最大的窗口：Chromium 会挂好几个含 "chromium" 的助手窗口（10×10 / 1×1），
            // 按标题选会选错——这正是上一版"10×10 断言 10×10 假绿"的原因。
            Map<String, Object> target = null;
            long bestArea = -1;
            for (Map<String, Object> row : service.windows(workspace.id())) {
                boolean isChromium = String.valueOf(row.get("title"))
                        .toLowerCase(java.util.Locale.ROOT).contains("chromium");
                long area = ((Number) row.get("width")).longValue()
                        * ((Number) row.get("height")).longValue();
                if (isChromium && area > bestArea) {
                    bestArea = area;
                    target = row;
                }
            }
            if (target != null) {
                assertThat(bestArea).as("挑中的窗口必须是真窗口，不是 10×10 助手窗").isGreaterThan(10_000L);
            }
            if (target != null) {
                Map<String, Object> windowShot = service.screenshot(workspace.id(),
                        String.valueOf(target.get("id")), null, "png");
                System.out.println("[verify] window = " + windowShot);
                assertThat(windowShot.get("width"))
                        .as("窗口截图宽度 = 窗口宽度").isEqualTo(target.get("width"));
                assertThat(windowShot.get("height"))
                        .as("窗口截图高度 = 窗口高度").isEqualTo(target.get("height"));
                assertThat(windowShot.get("window")).isEqualTo(target.get("id"));
            }

            // —— 标准截屏：区域取景 ——
            Map<String, Object> regionShot = service.screenshot(workspace.id(), null,
                    "0,0,320,200", "png");
            System.out.println("[verify] region = " + regionShot);
            assertThat(regionShot.get("width")).isEqualTo(320);
            assertThat(regionShot.get("height")).isEqualTo(200);

            // 每张截图都必须额外落一份到 downloads（QQ 发送工具只认 data 目录下的路径）
            for (Map<String, Object> each : List.of(shot, regionShot)) {
                Object download = each.get("download");
                assertThat(download).as("截图必须给 download 副本路径").isNotNull();
                Path copy = Path.of(String.valueOf(download));
                assertThat(copy).isRegularFile();
                assertThat(copy.toAbsolutePath().normalize())
                        .startsWith(downloads.toAbsolutePath().normalize());
            }

            // exec：工作区里跑命令
            CommandRunner.Result echo = service.exec(workspace.id(),
                    List.of("sh", "-c", "echo DISPLAY=$DISPLAY; pwd"), 10_000);
            System.out.println("[verify] exec rc=" + echo.rc() + " out=" + echo.stdout().strip());
            assertThat(echo.rc()).isZero();
            assertThat(echo.stdout()).contains("DISPLAY=" + workspace.displayName());

            if (caps.canInput()) {
                exerciseInput(service, workspace);
            }

            // A8：缺 xdotool 时给安装提示（本机常态）；有就真按一下
            if (!caps.canInput()) {
                assertThatThrownBy(() -> service.key(workspace.id(), "Return"))
                        .isInstanceOfSatisfying(XvfbService.XvfbException.class, failure -> {
                            assertThat(failure.code()).isEqualTo("NO_XDOTOOL");
                            assertThat(failure.getMessage()).contains("apt install xdotool");
                        });
                System.out.println("[verify] 无 xdotool，已返回安装提示（符合 A8）");
            } else {
                service.key(workspace.id(), "Return");
                System.out.println("[verify] xdotool 可用，按键通过");
            }

            // 中文必须走剪贴板：无 xclip 时应明确拒绝，而不是打出乱码
            if (caps.clipboard().isEmpty() && caps.canInput()) {
                assertThatThrownBy(() -> service.type(workspace.id(), "中文", "clipboard"))
                        .isInstanceOfSatisfying(XvfbService.XvfbException.class,
                                failure -> assertThat(failure.code()).isEqualTo("NO_CLIPBOARD"));
            }

            // 剪贴板一半：有 xclip 时在工作区 display 上做一次往返
            if (!caps.clipboard().isEmpty()) {
                CommandRunner.Result clip = service.exec(workspace.id(), List.of("sh", "-c",
                        "printf '剪贴板往返' | xclip -selection clipboard && "
                                + "xclip -selection clipboard -o"), 10_000);
                System.out.println("[verify] clipboard rc=" + clip.rc()
                        + " out=[" + clip.stdout().strip() + "]");
                assertThat(clip.rc()).isZero();
                assertThat(clip.stdout()).contains("剪贴板往返");
            }

            // v1 明确不支持的无障碍树
            assertThatThrownBy(() -> service.tree(workspace.id()))
                    .isInstanceOfSatisfying(XvfbService.XvfbException.class,
                            failure -> assertThat(failure.code()).isEqualTo("ATSPI_UNSUPPORTED"));
        } finally {
            long displayPid = workspace.xvfbPid();
            Path dir = workspace.dir();

            // A5：keep=false 后目录消失、进程消失
            boolean keep = "1".equals(System.getenv("BMA3_XVFB_VERIFY_KEEP"));
            if (!String.valueOf(lastShotPath.get()).isEmpty()
                    && Files.isRegularFile(Path.of(lastShotPath.get()))) {
                Files.copy(Path.of(lastShotPath.get()),
                        Path.of("target/xvfb-verify/keep-last.png"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            service.close(workspace.id(), keep);
            if (keep) {
                // ★ 这里绝对不能 return：finally 里 return 会吞掉上面的断言失败
                System.out.println("[verify] KEEP=1，工作区保留在 " + dir + "（截图还在 shots/ 里）");
            } else {
                assertThat(dir).doesNotExist();
                Thread.sleep(500);
                assertThat(ProcessHandle.of(displayPid).filter(ProcessHandle::isAlive))
                        .as("Xvfb 进程必须已退出")
                        .isEmpty();
                System.out.println("[verify] closed and collected " + dir);
            }
        }
    }

    /** 点击/输入/按键都必须能在 window title 上留下证据，这样不用 OCR 也能断言。 */
    private static final String INTERACTIVE_PAGE =
            "data:text/html;charset=utf-8,"
                    + "<body style='margin:0'>"
                    + "<textarea id=i style='position:fixed;inset:0;font-size:40px;"
                    + "border:0;outline:0' placeholder='type here'></textarea>"
                    + "<script>"
                    + "document.addEventListener('click',()=>{document.title='CLICK_OK'});"
                    + "document.addEventListener('keydown',e=>{"
                    + "if(e.key==='Escape'){document.title='KEY_OK'}});"
                    + "document.addEventListener('input',e=>{"
                    + "document.title='TEXT:'+e.target.value});"
                    + "</script></body>";

    /** 真点、真打字（中文走剪贴板）、真按键。 */
    private static void exerciseInput(XvfbService service, Workspace workspace) throws Exception {
        // 点空白处 → body 的 click 处理器把标题改成 CLICK_OK
        service.act(workspace.id(), 200, 600, "click", 1);
        assertThat(awaitTitle(service, workspace, "CLICK_OK", 15))
                .as("xvfb_act 的点击必须真的到达页面").isTrue();

        // 再点一次页面正文（避开 Chromium 自己的工具栏），顺便聚焦 textarea
        service.act(workspace.id(), 300, 400, "click", 1);
        String mode = service.type(workspace.id(), "中文测试", "clipboard");
        System.out.println("[verify] type mode=" + mode);
        assertThat(awaitTitle(service, workspace, "TEXT:中文测试", 15))
                .as("xvfb_type 必须把中文原样送进输入框").isTrue();

        // 按 Escape → keydown 处理器把标题改成 KEY_OK
        service.key(workspace.id(), "Escape");
        assertThat(awaitTitle(service, workspace, "KEY_OK", 15))
                .as("xvfb_key 必须真的送到焦点窗口").isTrue();

        // 留一张"打完之后"的截图当证据
        Map<String, Object> after = service.screenshot(workspace.id(), null, null, "png");
        lastShotPath.set(String.valueOf(after.get("path")));
        System.out.println("[verify] after-input screenshot = " + after);
    }

    private static boolean awaitTitle(XvfbService service, Workspace workspace, String needle,
                                      int seconds) throws InterruptedException {
        for (int i = 0; i < seconds * 2; i++) {
            for (Map<String, Object> window : service.windows(workspace.id())) {
                // 标题匹配忽略大小写：窗口标题是 "… - Chromium"，断言常量常写成小写
                if (String.valueOf(window.get("title")).toLowerCase(java.util.Locale.ROOT)
                        .contains(needle.toLowerCase(java.util.Locale.ROOT))) {
                    return true;
                }
            }
            Thread.sleep(500);
        }
        System.out.println("[verify] 等 '" + needle + "' 超时；当前窗口 = "
                + service.windows(workspace.id()));
        return false;
    }

    private static void dumpLogs(Workspace workspace) throws Exception {
        Path logs = workspace.dir().resolve("logs");
        if (!Files.isDirectory(logs)) {
            System.out.println("[verify] 没有 logs 目录");
            return;
        }
        try (var files = Files.list(logs)) {
            for (Path log : files.toList()) {
                String text = Files.readString(log);
                System.out.println("[verify] --- " + log.getFileName() + " ---");
                System.out.println(text.length() > 2000 ? text.substring(0, 2000) : text);
            }
        }
    }

}
