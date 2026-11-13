package io.mosire.bma3.xvfb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Xvfb 工作区的 AgentTools（12 个）。
 *
 * <p>命名与语义见 spec §5。所有会话级工具的第一个可选参数都是 {@code workspace}
 * （id 或目录）；缺省时走 {@link XvfbService} 的 W4 规则（最近 RUNNING，没有就自动开一个），
 * 所以「默认提供一个工作区」不需要额外配置。
 *
 * <p>错误一律 {@code ToolResult.error(CODE, 人话 + 安装提示)}：缺件不代装（I3），
 * 只告诉 Agent 该敲哪一句。
 */
public final class XvfbTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    private XvfbTools() {
    }

    public static List<AgentTool> all(XvfbService service) {
        return List.of(
                new StatusTool(service),
                new WorkspaceOpenTool(service),
                new WorkspaceCloseTool(service),
                new WorkspaceListTool(service),
                new LaunchTool(service),
                new ScreenshotTool(service),
                new WindowsTool(service),
                new ExecTool(service),
                new KeyTool(service),
                new TypeTool(service),
                new ActTool(service),
                new TreeTool(service));
    }

    // ------------------------------------------------------------------ base

    abstract static class Base implements AgentTool {

        final XvfbService service;

        Base(XvfbService service) {
            this.service = service;
        }

        @Override
        public ToolResult execute(ToolContext context) {
            try {
                return ToolResult.ok(JSON.writeValueAsString(run(context)));
            } catch (XvfbService.XvfbException failure) {
                return ToolResult.error(failure.code(), failure.getMessage());
            } catch (IllegalArgumentException failure) {
                return ToolResult.error("INVALID_ARGUMENT", failure.getMessage());
            } catch (Exception failure) {
                String message = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                return ToolResult.error("XVFB_ERROR", message);
            }
        }

        abstract Object run(ToolContext context) throws Exception;

        String workspace(ToolContext context) {
            return optionalText(context, "workspace");
        }

        String requiredText(ToolContext context, String name) {
            String value = optionalText(context, name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " 不能为空");
            }
            return value;
        }

        String optionalText(ToolContext context, String name) {
            Object raw = context.arguments().get(name);
            if (raw == null) {
                return null;
            }
            String value = String.valueOf(raw);
            return value.isBlank() ? null : value;
        }

        int intArg(ToolContext context, String name, int fallback) {
            Object raw = context.arguments().get(name);
            if (raw == null) {
                return fallback;
            }
            if (raw instanceof Number number) {
                return number.intValue();
            }
            return Integer.parseInt(String.valueOf(raw).trim());
        }

        boolean boolArg(ToolContext context, String name, boolean fallback) {
            Object raw = context.arguments().get(name);
            if (raw == null) {
                return fallback;
            }
            if (raw instanceof Boolean flag) {
                return flag;
            }
            return Boolean.parseBoolean(String.valueOf(raw).trim());
        }

        List<String> argv(ToolContext context, boolean required) {
            Object raw = context.arguments().get("argv");
            List<String> out = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        out.add(String.valueOf(item));
                    }
                }
            } else if (raw != null) {
                out.add(String.valueOf(raw));
            }
            if (required && out.isEmpty()) {
                throw new IllegalArgumentException("argv 不能为空（给一个字符串数组）");
            }
            return out;
        }

        static Map<String, Object> stringArraySchema() {
            return Map.of("type", "array", "items", Map.of("type", "string"));
        }
    }

    // ----------------------------------------------------------------- tools

    private static final class StatusTool extends Base {

        StatusTool(XvfbService service) {
            super(service);
        }

        @Override
        public String name() {
            return "xvfb_status";
        }

        @Override
        public String description() {
            return "检测这台机器能不能开 Xvfb 工作区：平台、可用的 Xvfb/xdotool/xwininfo/截图后端/窗口管理器/剪贴板/"
                    + "中文字体数量，缺什么就给安装命令；同时列出已有工作区。从不失败。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        Object run(ToolContext context) {
            return service.status();
        }
    }

    private static final class WorkspaceOpenTool extends Base {

        WorkspaceOpenTool(XvfbService service) {
            super(service);
        }

        @Override
        public String name() {
            return "xvfb_workspace_open";
        }

        @Override
        public String description() {
            return "开一块虚拟屏幕（工作区）：分配 display、起 Xvfb、可选起窗口管理器。"
                    + "给 workspace 则重新拉起已有工作区（保留 home/ 里的登录态），不给则新建。"
                    + "返回 {workspace, display, state, dir, width, height}。";
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, false, false);
        }

        @Override
        public Map<String, Object> jsonSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("workspace", Map.of("type", "string", "description", "工作区 id 或目录；省略=新建"));
            props.put("width", Map.of("type", "integer", "description", "屏幕宽，默认 1440"));
            props.put("height", Map.of("type", "integer", "description", "屏幕高，默认 900"));
            props.put("depth", Map.of("type", "integer", "description", "色深，默认 24"));
            props.put("windowManager", Map.of("type", "string", "description", "指定窗口管理器可执行名；省略=自动挑"));
            return Map.of("type", "object", "properties", props);
        }

        @Override
        Object run(ToolContext context) {
            Workspace workspace = service.open(
                    workspace(context),
                    intArg(context, "width", 1440),
                    intArg(context, "height", 900),
                    intArg(context, "depth", 24),
                    optionalText(context, "windowManager"));
            return view(workspace);
        }

        static Map<String, Object> view(Workspace workspace) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workspace", workspace.id());
            row.put("display", workspace.displayName());
            row.put("state", workspace.state());
            row.put("dir", workspace.dir().toString());
            row.put("width", workspace.width());
            row.put("height", workspace.height());
            row.put("depth", workspace.depth());
            return row;
        }
    }

    private static final class WorkspaceCloseTool extends Base {

        WorkspaceCloseTool(XvfbService service) {
            super(service);
        }

        @Override
        public String name() {
            return "xvfb_workspace_close";
        }

        @Override
        public String description() {
            return "关掉工作区并回收它的 Xvfb/窗口管理器/应用进程。keep=false（默认）连目录一起删；"
                    + "keep=true 保留 home/ 里的登录态，下次 xvfb_workspace_open 还能用。";
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, false, true);
        }

        @Override
        public Map<String, Object> jsonSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("workspace", Map.of("type", "string", "description", "工作区 id；省略=最近一个"));
            props.put("keep", Map.of("type", "boolean", "description", "true=保留目录与 home/，默认 false"));
            return Map.of("type", "object", "properties", props);
        }

        @Override
        Object run(ToolContext context) {
            Workspace workspace = service.close(workspace(context), boolArg(context, "keep", false));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workspace", workspace.id());
            row.put("state", workspace.state());
            row.put("dir", workspace.dir().toString());
            row.put("kept", boolArg(context, "keep", false));
            return row;
        }
    }

    private static final class WorkspaceListTool extends Base {

        WorkspaceListTool(XvfbService service) {
            super(service);
        }

        @Override
        public String name() {
            return "xvfb_workspace_list";
        }

        @Override
        public String description() {
            return "列出全部工作区（含 KEPT 的），返回 id/display/state/目录/尺寸。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        Object run(ToolContext context) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Workspace workspace : service.list()) {
                Map<String, Object> row = WorkspaceOpenTool.view(workspace);
                row.put("createdAt", workspace.createdAt());
                row.put("xvfbPid", workspace.xvfbPid());
                if (!workspace.error().isEmpty()) {
                    row.put("error", workspace.error());
                }
                rows.add(row);
            }
            return Map.of("workspaces", rows, "root", service.workspaceRoot().toString());
        }
    }

    private static final class LaunchTool extends Base {

        LaunchTool(XvfbService service) {
            super(service);
        }

        @Override
        public String name() {
            return "xvfb_launch";
        }

        @Override
        public String description() {
            return "在工作区里启动一个程序（不等待退出），返回 pid 和日志路径。"
                    + "例：argv=[\"chromium\",\"--no-sandbox\",\"https://example.com\"]。";
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, false, true);
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "workspace", Map.of("type", "string", "description", "工作区 id；省略=最近一个"),
                    "argv", stringArraySchema()), "required", List.of("argv"));
        }

        @Override
        Object run(ToolContext context) {
            long pid = service.launch(workspace(context), argv(context, true));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pid", pid);
            row.put("launched", true);
            return row;
        }
    }

    private static final class ScreenshotTool extends Base {

        ScreenshotTool(XvfbService service) {
            super(service);
        }

        @Override
        public String name() {
            return "xvfb_screenshot";
        }

        @Override
        public String description() {
            return "标准截屏。三种取景：默认全屏（根窗口，含 WM 标题栏与应用 UI）；给 window=0x… 只截那个窗口"
                    + "（id 来自 xvfb_windows）；给 region=\"x,y,w,h\" 截一块。"
                    + "产物先落工作区 shots/，再拷一份到下载目录（download 字段），可直接用 "
                    + "qq_conversation_send_image / send_file 发出去。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("workspace", Map.of("type", "string", "description", "工作区 id；省略=最近一个"));
            props.put("window", Map.of("type", "string",
                    "description", "只截这个窗口，值是 xvfb_windows 返回的 id（如 0x400002）"));
            props.put("region", Map.of("type", "string",
                    "description", "只截这块区域，格式 \"x,y,w,h\"（屏幕坐标）；与 window 互斥"));
            props.put("format", Map.of("type", "string", "description", "png（默认）或 jpeg"));
            return Map.of("type", "object", "properties", props);
        }

        @Override
        Object run(ToolContext context) {
            return service.screenshot(workspace(context),
                    optionalText(context, "window"),
                    optionalText(context, "region"),
                    optionalText(context, "format"));
        }
    }

    private static final class WindowsTool extends Base {

        WindowsTool(XvfbService service) {
            super(service);
        }

        @Override
        public String name() {
            return "xvfb_windows";
        }

        @Override
        public String description() {
            return "列出工作区里的窗口（xwininfo -root -tree）：{id,title,x,y,width,height}。用来确认程序起没起来、在哪。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "workspace", Map.of("type", "string", "description", "工作区 id；省略=最近一个")));
        }

        @Override
        Object run(ToolContext context) {
            List<Map<String, Object>> windows = service.windows(workspace(context));
            return Map.of("windows", windows, "count", windows.size());
        }
    }

    private static final class ExecTool extends Base {

        ExecTool(XvfbService service) {
            super(service);
        }

        @Override
        public String name() {
            return "xvfb_exec";
        }

        @Override
        public String description() {
            return "在工作区里跑一条命令并等它结束（DISPLAY 已设好，cwd=工作区 home/），返回 rc/stdout/stderr。"
                    + "装包、改配置、批量文件操作优先用它，不要用点击。";
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, false, true);
        }

        @Override
        public Map<String, Object> jsonSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("workspace", Map.of("type", "string", "description", "工作区 id；省略=最近一个"));
            props.put("argv", stringArraySchema());
            props.put("timeoutMs", Map.of("type", "integer", "description", "超时毫秒，默认 30000"));
            return Map.of("type", "object", "properties", props, "required", List.of("argv"));
        }

        @Override
        Object run(ToolContext context) {
            CommandRunner.Result result = service.exec(workspace(context), argv(context, true),
                    Math.max(1000, intArg(context, "timeoutMs", 30000)));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rc", result.rc());
            row.put("stdout", result.stdout());
            row.put("stderr", result.stderr());
            row.put("timedOut", result.timedOut());
            return row;
        }
    }

    private static final class KeyTool extends Base {

        KeyTool(XvfbService service) {
            super(service);
        }

        @Override
        public String name() {
            return "xvfb_key";
        }

        @Override
        public String description() {
            return "按键/组合键，语法同 xdotool：\"Return\"、\"ctrl+l\"、\"alt+F4\"、\"ctrl+shift+t\"。";
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, false, true);
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "workspace", Map.of("type", "string", "description", "工作区 id；省略=最近一个"),
                    "keys", Map.of("type", "string")), "required", List.of("keys"));
        }

        @Override
        Object run(ToolContext context) {
            String keys = requiredText(context, "keys");
            service.key(workspace(context), keys);
            return Map.of("ok", true, "keys", keys);
        }
    }

    private static final class TypeTool extends Base {

        TypeTool(XvfbService service) {
            super(service);
        }

        @Override
        public String name() {
            return "xvfb_type";
        }

        @Override
        public String description() {
            return "输入文本。默认走剪贴板粘贴（中文必须走这条）；mode=keys 才用按键模拟（只支持 ASCII）。";
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, false, true);
        }

        @Override
        public Map<String, Object> jsonSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("workspace", Map.of("type", "string", "description", "工作区 id；省略=最近一个"));
            props.put("text", Map.of("type", "string"));
            props.put("mode", Map.of("type", "string", "description", "clipboard（默认）或 keys"));
            return Map.of("type", "object", "properties", props, "required", List.of("text"));
        }

        @Override
        Object run(ToolContext context) {
            String text = requiredText(context, "text");
            String mode = service.type(workspace(context), text, optionalText(context, "mode"));
            return Map.of("ok", true, "chars", text.length(), "mode", mode);
        }
    }

    private static final class ActTool extends Base {

        ActTool(XvfbService service) {
            super(service);
        }

        @Override
        public String name() {
            return "xvfb_act";
        }

        @Override
        public String description() {
            return "鼠标动作：把指针移到 (x,y) 再 click/double/right/middle/move/scroll。"
                    + "坐标以 xvfb_screenshot 的画面为准（同源，左上角原点）。";
        }

        @Override
        public ToolSpec spec() {
            return ToolSpec.level(AccessToken.DEFAULT, false, true);
        }

        @Override
        public Map<String, Object> jsonSchema() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("workspace", Map.of("type", "string", "description", "工作区 id；省略=最近一个"));
            props.put("x", Map.of("type", "integer"));
            props.put("y", Map.of("type", "integer"));
            props.put("action", Map.of("type", "string",
                    "description", "click（默认）/double/right/middle/move/scroll"));
            props.put("amount", Map.of("type", "integer", "description", "scroll 的格数，负=上滚"));
            return Map.of("type", "object", "properties", props, "required", List.of("x", "y"));
        }

        @Override
        Object run(ToolContext context) {
            int x = intArg(context, "x", Integer.MIN_VALUE);
            int y = intArg(context, "y", Integer.MIN_VALUE);
            if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE) {
                throw new IllegalArgumentException("x/y 必填");
            }
            String action = optionalText(context, "action");
            service.act(workspace(context), x, y, action, intArg(context, "amount", 1));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ok", true);
            row.put("x", x);
            row.put("y", y);
            row.put("action", action == null ? "click" : action);
            return row;
        }
    }

    private static final class TreeTool extends Base {

        TreeTool(XvfbService service) {
            super(service);
        }

        @Override
        public String name() {
            return "xvfb_tree";
        }

        @Override
        public String description() {
            return "无障碍树（AT-SPI）—— v1 未实现，会返回 ATSPI_UNSUPPORTED。用 xvfb_screenshot + xvfb_windows + xvfb_act 代替。";
        }

        @Override
        public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "workspace", Map.of("type", "string", "description", "工作区 id；省略=最近一个")));
        }

        @Override
        Object run(ToolContext context) {
            service.tree(workspace(context));
            return Map.of();
        }
    }
}
