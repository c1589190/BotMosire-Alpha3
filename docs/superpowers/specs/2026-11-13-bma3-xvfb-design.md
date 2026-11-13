# bma3-xvfb —— 全桌面 Computer Use 服务（Xvfb 功能封装）

> **状态**：当前有效（2026-11-13）
> **需求原话**：D-053「对于 linux，干脆直接上全桌面 ComputerUSE 得了，Agent 想搞浏览器还是啥自己搞」；D-054「不设策略闸门 / 取消人机接力 / Agent 就是全权力用户」；D-055「这个包应该叫 xvfb 功能封装包，额外带平台检测 / 能出网 / 什么都能挂 / 默认生成并提供一个当前目录下的 xvfb 工作区」。
> **本文取代**：`reports/2026-11-13-computer-use-linux-routes-survey.md` 的 §6 建议（L1 优先、L2 留接口、noVNC 接力）与 §8 待拍板清单。
> **关联**：D-005（浏览器 sidecar；「Linux 全桌面控制是否纳入」由 D-053 回答）。

## 1. 定位

`bma3-xvfb` 是**一个 Xvfb 工作区的封装包**：在一个没有显示器的 Linux 机器上，为 Agent 开出一块虚拟屏幕，让它可以启动任意程序、看屏幕、动鼠标键盘、跑命令。浏览器不是这个包的功能，而是它里面能跑的一个普通程序。

**不做**：平台抽象层（Windows/macOS 的等价实现另立模块）、安全沙箱语义、人工接管通道、浏览器结构化工具。

## 2. 模块与依赖

```
bma3-xvfb ──> bma3-agentlib ──> bma3-core
```

不依赖 qqconnect / inbox / webconnect / watchdog。装配在 `bma3-app`。

## 3. 工作区（Workspace）

一个工作区 = 一个 display = 一次任务的全部桌面状态，**可丢弃**。

```
<xvfb.workspaceRoot>/           默认 ${cwd}/.xvfb/
└── ws-7f3a/
    ├── meta.json               id/display/state/pid/pids/geometry/createdAt/error
    ├── home/                   会话内 $HOME（浏览器 profile、应用配置、登录态）
    ├── uploads/                给应用读的入口目录
    ├── downloads/              产物出口
    ├── shots/                  截图（Agent 自行 read_image）
    └── logs/                   xvfb / wm / 应用日志
```

### 状态机

```
  (无) --open--> RUNNING --close{keep:false}--> (目录删除)
                    │
                    └----close{keep:true}----> KEPT --open{id}--> RUNNING
```

**不变量**

- **W1**：一个工作区同一时刻最多一个 RUNNING 的 Xvfb；重复 `open` 已 RUNNING 的 id 返回既有实例，不再起第二个。
- **W2**：display 号在 `90..199` 内分配，分配前检查 `/tmp/.X<n>-lock` 与 `/tmp/.X11-unix/X<n>`；已被占用的号不得使用。
- **W3**：`close{keep:false}` 必须删除整个工作区目录；`close{keep:true}` 必须保留 `home/` 与 `meta.json`（`state=KEPT`），并清空 `shots/`。
- **W4**：任何会话级工具在 `workspace` 参数缺省时，使用**最近一次 RUNNING 的工作区**；没有则**自动开一个**（满足「默认提供一个工作区」）。
- **W5**：工作区目录解析后必须落在 `workspaceRoot` 内，越界路径拒绝（`WORKSPACE_PATH_ESCAPE`）。

## 4. 平台检测与「提醒装一个」

`xvfb_status` 是唯一的自描述入口。检测顺序：OS → `Xvfb` → 截图后端 → `xdotool` → `xwininfo/xdpyinfo` → 窗口管理器 → 剪贴板 → CJK 字体 → 容器运行时。

**关键要求（用户原话：「如果本机未检测到 xvfb 的话，提醒 Agent 装一个就行」）**：任何会因此失败的工具，都必须返回**可执行安装提示**，而不是堆栈或空结果。

| reason | 触发 | hint 示例 |
|---|---|---|
| `PLATFORM_UNSUPPORTED` | 非 Linux | `Xvfb 是 X11 显示服务；Windows 无单一等价原语（需 IDD 虚拟显示器 + 登录交互会话 + UIA + SendInput）` |
| `NO_XVFB` | PATH 与常见目录都找不到 `Xvfb` | `sudo apt install xvfb`（Debian/Ubuntu）/ `sudo dnf install xorg-x11-server-Xvfb`（Fedora/RHEL） |
| `NO_SCREENSHOT_BACKEND` | `import`/`scrot`/`ffmpeg`/`xwd` 全无 | `sudo apt install imagemagick`（或 `scrot` / `ffmpeg`） |
| `NO_XDOTOOL` | 找不到 `xdotool` | `sudo apt install xdotool` |
| `NO_CLIPBOARD` | 非 ASCII 文本且无剪贴板工具 | `sudo apt install xclip` |
| `ATSPI_UNSUPPORTED` | v1 未实现无障碍树 | `v1 未实现；用 xvfb_screenshot + xvfb_act 坐标操作` |

**不报告隔离级别**（D-055 补充裁定：`isolation` 字段删除——Agent 自身即全权限，不需要被提示「你在裸奔」）。

## 5. 工具契约（12 个）

| 工具 | 参数 | 返回 | 失败码 |
|---|---|---|---|
| `xvfb_status` | — | 平台/能力/安装提示/工作区清单 | 从不失败 |
| `xvfb_workspace_open` | `workspace?`, `width?`, `height?`, `depth?`, `windowManager?` | `{workspace, display, state, dir}` | `PLATFORM_UNSUPPORTED` `NO_XVFB` `NO_SCREENSHOT_BACKEND` |
| `xvfb_workspace_close` | `workspace?`, `keep?` | `{workspace, state, dir}` | `WORKSPACE_NOT_FOUND` |
| `xvfb_workspace_list` | — | `[{workspace, display, state, dir, createdAt}]` | — |
| `xvfb_launch` | `workspace?`, `argv[]` | `{pid, log}` | `INVALID_ARGUMENT` `WORKSPACE_NOT_RUNNING` |
| `xvfb_screenshot` | `workspace?`, `window?`（`0x…` id）, `region?`（`x,y,w,h`）, `format?` | `{path, download, bytes, width, height, backend, region?, window?}` | `NO_SCREENSHOT_BACKEND` `SCREENSHOT_FAILED` `WINDOW_NOT_FOUND` `INVALID_ARGUMENT` |
| `xvfb_windows` | `workspace?` | `[{id, title, x, y, width, height}]` | `NO_XWININFO` |
| `xvfb_exec` | `workspace?`, `argv[]`, `timeoutMs?` | `{rc, stdout, stderr, truncated}` | `TIMEOUT` |
| `xvfb_key` | `workspace?`, `keys` | `{ok, keys}` | `NO_XDOTOOL` |
| `xvfb_type` | `workspace?`, `text`, `mode=clipboard\|keys` | `{ok, chars, mode}` | `NO_CLIPBOARD` `NO_XDOTOOL` |
| `xvfb_act` | `workspace?`, `x`, `y`, `action=click\|double\|right\|move\|scroll`, `amount?` | `{ok, x, y}` | `NO_XDOTOOL` |
| `xvfb_tree` | `workspace?` | — | `ATSPI_UNSUPPORTED`（v1 正式不支持） |

**错误格式**：`ToolResult.error(<CODE>, "<人话>；<安装提示>")`。代码前缀统一 `XVFB_`？否——沿用本项目既有风格：短大写下划线码。

### 5.1 标准截屏的三种取景

一个工具、三种取景，共用同一套后端选择：

| 取景 | 参数 | 结果 | 说明 |
|---|---|---|---|
| **全屏**（默认） | — | 屏幕尺寸 | 根窗口，含 WM 标题栏与所有窗口 |
| **窗口** | `window: "0x400002"` | 该窗口客户区尺寸 | id 来自 `xvfb_windows`；几何用 `xwininfo -id` 的**绝对**坐标；**不含** WM 装饰（要标题栏就截全屏或 region） |
| **区域** | `region: "x,y,w,h"` | `w×h` | 屏幕坐标；越界自动裁到屏幕内 |

后端优先级（按「能精确取景」排）：

| 顺序 | 后端 | 全屏 | 窗口/区域 | 备注 |
|---|---|---|---|---|
| 1 | `import`（ImageMagick） | `-window root` | `-crop WxH+X+Y -window root` / `-window <id>` | 唯一能抓**被遮挡窗口**的后端 |
| 2 | `scrot` | `-o -z` | `-a X,Y,W,H` | 最轻；只能抓屏幕上可见的部分 |
| 3 | `ffmpeg` | `-f x11grab -video_size WxH -i :N` | `-i :N+X,Y` | 兜底；同样只能抓可见部分 |

### 5.2 产物落两份

每次截图产出两个路径：

- `path` —— 工作区 `shots/`（临时，`close{keep:true}` 时会清空）；
- `download` —— `<dataDir>/downloads/`（持久，**QQ 的 `qq_conversation_send_image` / `send_file` 只认 data 目录下的路径**，所以这一步做进工具里，Agent 不用自己 `cp`）。

命名：`xvfb-<workspace>-<epochMillis>[-win|-region].<png|jpg>`，两边同名。

## 6. 运行序列（`xvfb_workspace_open`）

1. 探测能力；`NO_XVFB` ⇒ 立即返回安装提示，**不创建工作区目录**。
2. 分配 display 号（W2）。
3. 建目录 + 写 `meta.json`（`state=STARTING`）。
4. 启动 `Xvfb :N -screen 0 WxHxD -nolisten tcp -ac -noreset`，stdout/stderr 落 `logs/xvfb.log`。
5. 轮询 `xdpyinfo -display :N`，最多 10 s；成功 ⇒ `state=RUNNING`；超时 ⇒ 杀进程、`state=FAILED`、清 meta。
6. 可选启动窗口管理器（`openbox`/`fluxbox`/`xfwm4`/`metacity`/`twm`，取第一个存在的）；`wmPid` 记进 meta，没有 WM 时为 0。有 WM 时窗口会带标题栏（几何相对无 WM 时下移），`xvfb_windows` 也会看到 WM 自己的 1×1 辅助窗口——**如实返回，不过滤**。
7. 返回工作区视图。

## 7. 不变量（安全与正确性）

- **I1**：`Xvfb` 只监听 unix socket（`-nolisten tcp`），不开 TCP 端口。
- **I2**：所有进程以 bma3 的 uid 运行；本模块**不做**提权、不做 sudo。
- **I3**：不自动安装任何软件包；缺什么只返回提示（用户明确要求）。
- **I4**：`xvfb_exec` / `xvfb_launch` 的 argv 由调用方给出，模块不做白名单——D-054「Agent 即全权力用户」。
- **I5**：截图与命令输出都会被写入模型上下文，属外发通道；日志只记路径、字节数、返回码，不记正文。
- **I6**：工作区关闭后不得残留 Xvfb 进程；`meta.json` 是唯一事实源。
- **I7**：截图产物必须同时写工作区 `shots/` 与 `<dataDir>/downloads/`（后者供 QQ 发送工具直接取用）；日志只记路径与字节数，不记画面内容。

## 8. 验收判据

| # | 判据 | 可红可绿 |
|---|---|---|
| A1 | 本机（有 Xvfb）`xvfb_status.supported=true` 且列出工作区 | 是 |
| A2 | 无 Xvfb 的机器上 `xvfb_status` 返回 `NO_XVFB` + 安装提示，且 `xvfb_workspace_open` 同样返回提示、**不产生目录** | 是（用假 PATH 复现） |
| A3 | `open` 后 `xvfb_windows` 至少能看到一个窗口（启动了一个应用后） | 是 |
| A4 | `screenshot` 产出 PNG 且尺寸等于 `width×height` | 是 |
| A5 | `close{keep:false}` 后目录不存在、`pgrep Xvfb :N` 为空 | 是 |
| A6 | `close{keep:true}` 后目录存在且 `home/` 保留 | 是 |
| A7 | 默认工作区在 `${cwd}/.xvfb/` 下创建 | 是 |
| A8 | 缺 `xdotool` 时 `xvfb_key` 返回 `NO_XDOTOOL` + 安装提示 | 是 |
| A9 | `./mvnw -q test` 全绿 | 是 |
| A10 | `xvfb_act` 的点击真的到达页面（改 `document.title` 可观察到） | 是 |
| A11 | `xvfb_type` 用 clipboard 模式能把**中文**原样送进输入框 | 是 |
| A12 | `xvfb_key` 的按键真的送到焦点窗口 | 是 |
| A13 | `window` 取景的产物尺寸 == 该窗口尺寸（不是整屏，也不是 10×10 助手窗） | 是 |
| A14 | `region` 取景的产物尺寸 == 请求的 `w×h` | 是 |
| A15 | 每张截图都给出 `<dataDir>/downloads/` 下的同名副本 | 是 |

## 9. 未做（v1 明确不覆盖）

- AT-SPI 无障碍树（`xvfb_tree` 返回 `ATSPI_UNSUPPORTED`）。
- 容器化会话（`runtime=container`）：v1 直接在本机起 Xvfb；容器编排留 P1。
- 资源配额、空闲 TTL 回收、并发上限。
- Windows/macOS 后端。
- 人机接力（noVNC / CDP 串流）——D-054 已取消。
