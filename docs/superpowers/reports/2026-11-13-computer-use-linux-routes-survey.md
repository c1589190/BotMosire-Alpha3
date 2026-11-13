# bma3 Computer Use 服务 —— Linux 现成技术路线调查

> **类型**：调查（时间点快照，只作追溯，不作当前事实）
> **状态**：历史建议（2026-11-13）· **取代者**：D-053 / D-054 · **勿据此开工**。§6「建议」与 §8「待拍板」已被用户裁定覆盖：方向改为**全桌面 Computer Use 作为底座**，且**取消 L3 人机接力**、不设策略闸门。§1–§5 的事实与路线对照仍然有效。
> **日期**：2026-11-13
> **触发**：远端针对本项目提交了新的开发建议——「轻量 Xfce + Chromium + Playwright（无头优先），放独立容器/VM，别装在跑 QQ bot 的宿主机；GUI 只在真要操作网页/软件时才开；完整 desktop + VNC 维护和安全成本高，收益主要是浏览器自动化——先把 CDP/无头浏览器给我就够 90% 的活了」，要求调查 LinuxAgentComputerUse 相关现成技术，并给出让 bma3 提供一套完善 Computer Use 服务的可选技术路线与建议。
> **方法**：① 本项目源码逐项核对（`bma3-webconnect` / `bma3-app` / `pom.xml`）；② 本机实测（CDP 附着、AX 无障碍树、点击注入、截图渲染、进程内存占用、Selenium/DevTools 依赖、apt 候选、Docker）；③ 外部项目官方文档与 README（URL 见 §9）；④ 上游 MCP server 的 `--help` 实测（`@playwright/mcp` v0.0.85、`chrome-devtools-mcp`）。
> **限制**：本机不是远端 bma3 主机（远端 `/home/ubuntu` 不在本机）；未安装/实跑 Xfce、VNC、noVNC、AT-SPI 桌面栈；未跑真实 LLBot / DSH / systemd；未提交、未推送、未改产品代码。**没跑的项一律在 §10 标 `NOT_RUN`**。
> **纪律**：本报告只给事实、推断与候选路线。§6 是建议，不是裁定；需要改变设计时必须另写 spec 并经用户拍板。

---

## 0. 结论摘要

1. **"Computer Use" 不是一个功能，是三层可分离的能力**：L1 浏览器结构化操作、L2 桌面 GUI 操作、L3 人机接力（人接管一下）。三层的观测面（DOM/AX 树 vs 像素）和输入面（CDP vs AT-SPI/xdotool/坐标）完全不同，混在一起做会把成本放大一个量级。**远端的判断是对的：L1 覆盖绝大多数真实需求。**

2. **bma3 现在缺的不是"更强的浏览器"，而是"浏览器在哪、谁能看见它、状态归谁"**。现状是：Chromium 由 bma3 进程内拉起（`SeleniumBrowserSession`），全进程一个共享会话（`BrowserService`），工具只有 7 个，`ref` 没有代际校验，没有按键/滚动/等待/多标签/上传/多会话。**这些缺口与"用 Selenium 还是 Playwright"几乎无关。**

3. **最值钱的一个设计决定是把 bma3 变成 CDP 客户端，而不是浏览器宿主**。只要工具面只依赖"一个 CDP 端点"，那么"本机拉起 Chromium / 独立容器 / 另一台机器 / 换成 Playwright"都变成配置项。远端建议的"独立容器/VM"因此不是一次重构，而是一个部署选择。

4. **本机实测否决了"无头就很轻"的直觉**：一个 headless Chromium 进程树实测 **886 MB / 9 进程**（含 snap 的背景扩展页），而 Xvfb 空屏只要 **61.9 MB**。所以"轻量桌面"的成本主要在浏览器和合成器，不在 X server；**真正该省的是"每次任务起一整套桌面"这件事**，不是省 Xfce 的那几个包。

5. **桌面层（L2）在 bma3 侧目前没有硬用例**：B 站是 web；QQ 客户端 GUI 在 QQ 机上，且 D-046/D-047 已裁定走 SSH / 协议内解决。L2 应当留接口、不排 v1。

6. **推荐的落地形状**：`bma3-webconnect` 保留为唯一工具面所有者 → 内部改成"会话管理器 + CDP 客户端" → 浏览器宿主可替换（先本机 Xvfb+Chromium，后容器）→ 人机接力用 CDP screencast（浏览器层）而不是 VNC（桌面层才需要 VNC）。

---

## 1. 先把「Computer Use」拆成三层

这是所有路线之争的根源。远端建议里的"浏览器自动化"和"desktop + VNC"其实分属不同层，谈成本时必须分开算。

| 层 | 能力 | 观测面（agent 看到什么） | 输入面（agent 怎么动） | 典型工具 | 隔离难度 | bma3 现状 |
|---|---|---|---|---|---|---|
| **L1 浏览器** | 打开网页、结构化读页面、点击输入、截图、下载上传、多标签 | DOM / **AX 无障碍树**（结构化、几十个 token/元素）/ 截图（像素，贵） | **CDP**（`Input.dispatchMouseEvent`、`DOM`、`Accessibility`、`Page`） | Selenium、Playwright、Puppeteer、chrome-devtools-mcp、playwright-mcp | 中（进程 + 网络命名空间即可） | **已有一半**（7 个工具，Selenium 进程内） |
| **L2 桌面 GUI** | 操作原生应用（GTK/Qt/Electron/X11 app）、窗口管理、剪贴板 | AT-SPI 树（有则结构化）/ 只能靠截图（无则纯像素） | AT-SPI action / `xdotool` `ydotool` `wtype` / `/dev/uinput` / 坐标 | computer-use-linux、linux-desktop-mcp、agent-workspace-linux、anthropic computer-use-demo | 难（合成器矩阵 + 显示服务 + 输入设备 + 无障碍总线） | **没有**（D-005 明确"未定，待评审"） |
| **L3 人机接力** | 人接管一下：扫码登录、验证码、2FA、支付确认、"你自己看一眼" | 实时画面 | 人的鼠标键盘 | noVNC + x11vnc/tigerview、CDP `Page.startScreencast` | 取决于 L1/L2 的宿主 | **没有**（现在只有"截图落盘 + `read_image`"） |

**关键推论（推断）**：

- L1 的"完善"成本是**周级**；L2 是**月级**；把 L2 当成 L1 的前置条件，是这类项目最常见的超支方式。
- L3 在本项目的真实价值高于 L2：bma3 要替人做 B 站登录（扫码/验证码），这是**必然会发生**的人机接力，而"操作原生软件"目前没有指名用例。
- 三层的观测面差异直接决定 token 成本：AX 树一次快照几十个元素、约几百 token；截图一次就是上千 token 且**不可检索**。所以"结构化优先、像素兜底"不是风格问题，是成本问题。

---

## 2. bma3 现状盘点（事实，均带 `file:line`）

### 2.1 工具面：7 个，全是 L1 的基础动作

`bma3-webconnect/src/main/java/io/mosire/bma3/webconnect/BrowserTools.java:24` 注册全部工具；名字与语义：

| 工具 | 位置 | 语义 | 缺口 |
|---|---|---|---|
| `browser_open` | `:71` | 打开 http/https，返回 url/title | 不能开新标签、不能后退前进 |
| `browser_snapshot` | `:120` | **DOM** 选择器挑出可交互元素，打 `data-bma3-ref`（最多 80 个） | 不是 AX 树；ref 无代际；无 role/state |
| `browser_click` | `:151` | 按 ref 点击 | 无右键/双击/组合键/坐标兜底 |
| `browser_type` | `:199` | 按 ref 输入 | 无清空、无提交、无 `press_key` |
| `browser_text` | `:245` | 页面可见文本，截断 20000 字符 | 无结构化分块 |
| `browser_screenshot` | `:277` | 存 PNG 返本地路径 | 无全页、无缩放、无格式/质量控制、无脱敏 |
| `browser_video_frames` | `BrowserVideoFramesTool` | 视频抽帧 | — |

**没有**：`press_key`/快捷键、`scroll`、`wait_for`、`tabs`、`upload`（`input[type=file]`）、`download`、`console`、`network`、`evaluate`、iframe 穿透、dialog 处理、cookie/存储管理、AX 树、坐标操作。

### 2.2 进程模型：浏览器住在 bma3 的 JVM 里

- `BrowserService.java:16-19`：`session()` 懒加载，**整个进程一个 session**，所有 agent 会话共用同一个浏览器 profile 和同一个标签页。
- `SeleniumBrowserSession.java:55-79`：在 app 进程内 `new ChromeDriver(chrome)` 拉起 Chromium；`--user-data-dir` 固定（`:66`）；`--headless=new` 由配置决定（`:68`）。
- `Bma3App.java:231-237`：只有 `webconnect.enabled=true` 时才构造 `BrowserService`；`ConfigLoader.java:165-168` 默认 **`enabled=false`**、`binary=/snap/bin/chromium`、`headless=true`。

**由此产生的三个结构性后果（推断）**：

1. **爆炸半径 = bma3 进程**：页面里的漏洞利用跑在 bma3 的 uid、bma3 的网络命名空间里。bma3 的 MCP 监听 8765、消息板 SQLite、QQ token 都在同一台机器同一 uid 下（`AGENTS.md` §3 红线 1/2）。这与远端"别装在跑 QQ bot 的宿主机"的提醒是同一个问题。
2. **bma3 重启 = 浏览器死**：登录态靠 `--user-data-dir` 磁盘残留，但会话/标签/滚动位置全丢。
3. **多任务互相踩**：两个群同时让 agent 上网，会抢同一个标签页。bma3 是给多个 QQ 来源/多个会话服务的，这在设计上就不成立。

### 2.3 已有的安全面（要保留的资产）

- `BrowserTools.java:90-92`：只允许 `http`/`https`，其余 scheme 直接拒（`file://`、`chrome://` 天然被挡）。
- `BrowserTools.java:94-98`：`allowedHosts` 域名白名单，命中不了报 `DOMAIN_NOT_ALLOWED`。
- `AppConfig` / `ConfigView.java:84-88`：`enabled`/`binary`/`headless`/`allowedHosts` 可配、可热重载。
- `Bma3App.java:261`：watchdog 有 webconnect 探针。

这套"最小权限 + fail-closed"的口径是对的，任何新路线都必须能承接它，而不是绕过它。

### 2.4 依赖面：CDP 能力其实已经躺在本地仓库里

- `pom.xml:43`：`selenium.version=4.52.0`。
- 本机 `~/.m2/repository/org/seleniumhq/selenium/` 下已有 `selenium-devtools-v154 / v155 / v156` 与 `selenium-devtools-latest`（各约 1.7–1.8 MB）。
- 本机 Chromium 实测版本 **156.0.8078.47**（`/json/version` 输出，见 §4），与 `selenium-devtools-v156` **对齐**。

**含义（事实 + 推断）**：bma3 想用上完整 CDP（AX 树、`Input.dispatchMouseEvent`、`Page.captureScreenshot`、网络/控制台事件），**不需要增加任何新依赖**——Selenium 4 的 `DevTools` 接口已经具备，且版本已经匹配。

### 2.5 与既有裁定的关系

- **D-005（提议，未定）**：「浏览器 sidecar（Chromium/Playwright）负责网页登录/状态保存/截图操作；**Linux 全桌面控制是否纳入，未定，待评审**」。本报告 §5 的路线 D 正是这条待评审项的证据与选项；本报告不改变 D-005 的状态。
- `docs/superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md:145`：继承项目的教训是「227 MB fat jar，99% 是 Playwright 五平台驱动；运行时 `video.browser.enabled=false`」。**任何把浏览器二进制打进 bma3 产物的方案都应直接否决**（当前 bma3-app jar 53 MB，见 §4）。

---

## 3. 现成技术盘点

### 3.1 L1 浏览器层

#### (a) Selenium 4 + DevTools（已在用，零新增依赖）

- 能力：WebDriver 语义 + 完整 CDP（`Accessibility.getFullAXTree`、`Input.dispatchMouseEvent`、`Page.captureScreenshot`、`Network.*`、`Emulation.*`）。
- 附着已有浏览器：`ChromeOptions.setExperimentalOption("debuggerAddress", "127.0.0.1:9222")`。
- 优点：**零新依赖、零部署变化**、已有代码与测试（`BrowserToolsTest`、`SeleniumBrowserSmokeTest`）。
- 缺点：Java 侧 CDP 是"能调但不是一等公民"（要自己维护 message→JSON 映射）；`selenium-manager` 的 11 MB jar 会去联网找 driver（本机经验：曾长时间无输出，见 `ENVIRONMENT.md`）。

#### (b) Playwright（Java / Python / Node）+ `@playwright/mcp`

- 库能力：自动等待（auto-waiting）、`connectOverCDP`、无障碍/ARIA 快照（Playwright MCP 的工具结果就是这种「元素引用 + 角色 + 名称」的形状）、tracing、多 context 隔离、下载事件、console/network 钩子。
- **`@playwright/mcp` 实测版本 `0.0.85`**，`--help` 已验证的关键参数：
  - `--cdp-endpoint <endpoint>`：**连接到已有 CDP**（这是"bma3 不拥有浏览器"的直接支持）；
  - `--headless` / `--isolated`（profile 只在内存）/ `--user-data-dir` / `--idle-timeout`（默认无头浏览器 1 小时空闲回收）；
  - `--allowed-origins` / `--blocked-origins`——**帮助文本自己写明「does not serve as a security boundary and does not affect redirects」**，即这不是隔离边界；
  - `--caps vision,pdf,devtools`；`--image-responses allow|omit|only`；`--output-dir`；`--proxy-server`；`--no-sandbox`。
- 优点：成熟度最高、工具面最全、可附着外部 CDP。
- 缺点（针对 bma3）：**自带 agent 语义与 40+ 工具**，全量塞进 MCP schema 会明显吃 token；工具命名（`browser_*`）与 bma3 既有的单命名空间治理不完全同构；Java 侧若改用 Playwright 库，会引入 `driver-bundle`（含 Node + Playwright JS，数十 MB）——**但若只用 `connectOverCDP` 连接外部浏览器，就不会下载浏览器二进制**，这与继承项目"227 MB fat jar"的教训不冲突。

#### (c) `chrome-devtools-mcp`（Google 官方，实测 `--help`）

关键参数（实测输出）：

- `-u, --browserUrl http://127.0.0.1:9222` / `-w, --wsEndpoint ws://...`：**附着已运行浏览器**；
- `--allowedUrlPattern` / `--blockedUrlPattern`：**在浏览器侧按 URL Pattern 真拦截**（导航 + 子资源 + 重定向；`--allowedUrlPattern` 需 Chrome 149+，本机 156 满足）——这比 `--allowed-origins` 强得多；
- `--pageIdRouting`（默认 **true**）：「Require pageId on page-scoped tools and route requests by page ID (useful for concurrent agent sessions)」——**直接对上 bma3 的多会话需求**；
- `--experimentalVision`：开启 `click_at(x,y)` 坐标工具（通常需要能看图的 computer-use 模型）；
- `--screenshotFormat jpeg|webp|png`、`--screenshotMaxWidth/Height`、`--screenshotQuality`：**按 token 预算压截图**；
- `--isolated`（临时 user-data-dir 自动清理）、`--proxyServer`、`--headless`、`--viewport`；
- 工具分组开关：`--categoryInput/Navigation/Emulation/Performance/Network/...`，**可以按需裁掉工具**（对 bma3 的 token 预算友好）。

**判断（推断）**：如果走"直挂上游 MCP"路线，`chrome-devtools-mcp` 比 `@playwright/mcp` 更贴合 bma3 的三条硬需求——**真网络边界（`--allowedUrlPattern`）、多会话（`--pageIdRouting`）、token 可控（screenshot 缩放 + category 裁剪）**。

#### (d) browser-use / Stagehand / Midscene（不建议）

这三个都是**agent 框架**：自己带 LLM 循环（browser-use 让模型写 Python 打 CDP；Stagehand 提供 `act()/extract()/observe()`；Midscene 用视觉模型）。bma3 是**工具提供方**，上层已经有 DSH agent（`AGENTS.md` §1 的 preset/工具组）。再嵌一层 agent 会：重复消耗 token、破坏"工具面单命名空间"、把模型选择绑死在 bma3 里。**建议只把它们当参考实现读，不作为依赖。**

### 3.2 L2 桌面层

#### (a) `agent-sh/computer-use-linux`（Rust，MCP + CLI，MIT，379 commits）

这是最接近"LinuxAgentComputerUse"这个名字的项目。要点（README 实测摘要）：

- **观测**：AT-SPI 语义树（`role`/`name`/`states`/`element_index`）+ 有界截图；`get_app_state` 一次返回"截图 + 压缩后的 AT-SPI 树 + 稳定句柄"。
- **输入**：`click`/`drag`/`scroll`/`press_key`（支持 `Ctrl+L` 这种组合）/`type_text`；Wayland 走 `/dev/uinput` 或 `org.freedesktop.portal.RemoteDesktop`，X11 走 EWMH；文本优先 KDE 剪贴板 / `wtype` / portal keysym。
- **窗口**：compositor-aware 的窗口注册表（GNOME Shell 扩展、KWin DBus、Hyprland `hyprctl`、niri IPC、i3 IPC、labwc、wlr-foreign-toplevel、X11/EWMH 依次尝试），**并报告哪个 backend 赢了、其余为什么失败**。
- **★ 可借鉴的关键设计——`snapshot_id` 代际校验**：每个树结果带 `snapshot_id`；用 `element_index` 执行动作时必须带同一个 `snapshot_id`，若中途有更新的树替换了它，**在抬起窗口或注入输入之前就拒绝**，理由是"这个 index 那时可能已经指向别的元素了"。
- **失败面**：`doctor` 输出结构化 readiness（platform/portals/AT-SPI/windowing/input + blockers + 下一步建议）。
- 注意：它**驱动的是真实桌面**，作者明确说"想隔离就用兄弟项目 `agent-workspace-linux`"。

#### (b) `agent-sh/agent-workspace-linux`（隐藏桌面，MIT，397 commits）

- 形态：给 agent 一个**自己的** X11/Wayland 显示（Xvfb + Openbox 为默认；另有 i3 / KWin / GNOME 46 / Hyprland / COSMIC 后端），有自己的 WM、剪贴板、浏览器；人可以在小浮窗里看/暂停/终止。
- 依赖：`xvfb openbox xdotool xauth x11-utils imagemagick xclip dbus-daemon libxkbcommon-x11-dev`。
- **这就是"远端建议"的工业化实现**：不碰你的鼠标键盘焦点、可以随手起停、可看可控。
- 但它的后端矩阵（GNOME/KWin/Hyprland/COSMIC + 各自包 + render node + bubblewrap overlay）对 bma3 来说是**过重**的；只有 X11 后端（Xvfb+Openbox）是"够用且轻"的那一档。

#### (c) `linux-desktop-mcp`（PyPI `0.1.0`，MIT，1 维护者，Alpha）

- AT-SPI2（`pyatspi`）语义引用 `ref_1/ref_2` + `desktop_snapshot`/`desktop_find`/`desktop_click`/`desktop_type`/`desktop_key`/`desktop_capabilities`；
- 输入：X11 用 `xdotool`，Wayland 用 `ydotool`；
- 优点：代码小（whl 36.6 KB）、概念清晰、可作为"AT-SPI 树 → ref"的最小参考；
- 缺点：**Alpha、单人、无代际校验、无隔离**。只作参考，不作依赖。

#### (d) Anthropic `computer-use-demo`（参考实现）

Dockerfile 事实（`anthropics/claude-quickstarts/computer-use-demo/Dockerfile`）：`ubuntu:22.04` + `xvfb xterm xdotool scrot imagemagick sudo mutter x11vnc`，加 noVNC 暴露 6080 端口，agent 循环是"截图 → 模型给动作 → 注入 → 再截图"。

价值：**它是"截图+坐标"路线的最小完整证据**，也证明了这套栈的维护面（Xvfb + mutter + x11vnc + noVNC + 浏览器 + 字体）确实不小。

#### (e) UI-TARS-desktop / Agent TARS（字节，Apache 系，Electron）

- 本地/远程 computer operator + browser operator，**依赖 UI-TARS 视觉模型**做坐标决策。
- 对 bma3 的意义：它是"视觉坐标路线"的成品，但**自带模型与推理循环**，与 bma3 的工具提供方定位冲突；且坐标准确率依赖模型，模型不可控时整条链不可控。

### 3.3 隔离底座

| 方案 | 隔离强度 | 成本 | 适配 bma3 |
|---|---|---|---|
| 同进程（现状） | 无 | 0 | ✗（爆炸半径 = bma3） |
| 同机 sidecar 进程（systemd unit） | 进程级 | 低 | ✓ 起步够用 |
| **容器（Docker/Podman，独立 netns）** | 中（共享内核） | 中 | ✓✓ **推荐主线** |
| 微 VM（Firecracker / Kata / gVisor） | 高 | 高 | 视威胁模型再上 |

**本机事实**：`docker` 可用（`docker run --rm hello-world` 成功），已有镜像 `linyuchen/llbot:8.3.2`、`linyuchen/pmhq:9.0.6`。**（推断）**意味着远端/本机的部署形态本来就能跑容器，给浏览器 sidecar 起一个容器不是新增能力。

**为什么容器在这里的真正收益是"网络命名空间"而不是 CPU**：浏览器里跑的任意页面 JS 可以 `fetch('http://127.0.0.1:8765/...')` 打 bma3 自己的 MCP 端口、`http://127.0.0.1:<inbox>` 等等。域名白名单/`--allowed-origins` 都**不拦同源重定向与子资源**（Playwright MCP 的帮助文本自己承认了）。只有独立 netns + 显式出网策略才能真拦。这与 `AGENTS.md` §3 红线 1（默认只绑回环）是同一个威胁模型的两半。

### 3.4 人机接力（L3）

| 方案 | 需要 X | 需要 VNC | 适用层 | 说明 |
|---|---|---|---|---|
| 截图落盘 + `read_image`（现状） | 否 | 否 | L1 | 只能"看"，不能"接管"；扫码登录其实已经够用 |
| **CDP `Page.startScreencast` + `Input.dispatch*`** | **否** | **否** | **L1** | 无头浏览器也能流帧；人看到的即 agent 的页面；输入可回灌 |
| noVNC + x11vnc / tigervnc / websockify | 是 | 是 | L2 | 完整桌面接管；本机 apt 均有候选（见 §4） |
| Kasm / linuxserver/webtop 这类"桌面即容器"镜像 | 容器内 | 内置 | L2 | 省事但重（整桌面 + 浏览器 + 会话管理） |

**关键点（推断）**：如果只做 L1，**VNC 是不必要的**——CDP screencast 已经能给出"边看边接"的体验，而且不引入 X、不引入 5900/6080 端口暴露面。VNC 只在真要做 L2 时才是必需品。这一点直接削弱了"完整 desktop + VNC 维护成本高"里的那一半成本。

---

## 4. 本机实测证据

> 全部在本机（Ubuntu 24.04.5 / 12 核 / 11 GB / JDK 21 / Node 22.23.3 / Chromium 156 snap）执行；命令与输出按原样摘录。

### 4.1 CDP 可附着、版本匹配

```text
$ /snap/bin/chromium --headless=new --no-sandbox --disable-gpu \
    --remote-debugging-port=9333 --user-data-dir=/tmp/cdp-probe/user about:blank
$ curl -s http://127.0.0.1:9333/json/version
{
   "Browser": "Chrome/156.0.8078.47",
   "Protocol-Version": "1.3",
   "webSocketDebuggerUrl": "ws://127.0.0.1:9333/devtools/browser/c6dffb59-..."
}
```

### 4.2 无障碍树（结构化观测面）——中文元素名正常

用 Node 22 内建 `WebSocket` 直连 CDP，`Accessibility.getFullAXTree`：

```text
targets: background_page:chrome-extension://... | page:about:blank | browser_ui:...
AX nodes(sample): RootWebArea: | heading:测试页面 | button:保存 | textbox:搜索框 | link:链接
                 | StaticText:测试页面 | StaticText:保存 | StaticText:链接
screenshot bytes: 10282
viewport: {"pageX":0,"pageY":0,"clientWidth":1280,"clientHeight":757}
after CDP click, document.title = "clicked"
```

**结论（事实）**：headless Chromium 下，(1) AX 树可按 role/name 结构化返回；(2) `Input.dispatchMouseEvent` 注入的点击**真的生效**（页面 title 被 onclick 改写）；(3) 截图可用。**这三件事就是 L1 的全部底层能力，且已在依赖里。**

### 4.3 截图的中文渲染（CJK 前提已满足）

`Page.captureScreenshot` 产出 `1280x757`、**15,316 B** 的 PNG；用 `read_image` 查看确认标题「中文渲染测试 界面」、正文「保存 / 取消 / 搜索框」**全部正常显示，无豆腐块**。本机 `fc-list :lang=zh` = **12** 款字体。

**含义**：截图→模型的链路在本机可用；但容器镜像若用 `-slim` 基底，**必须显式装 CJK 字体**，否则文字全变方块——这是"放进容器"最容易踩的坑。

### 4.4 资源占用（推翻"无头=轻"）

```text
Xvfb :99 -screen 0 1280x900x24  →  RSS 61.9 MB
headless chromium 进程树         →  886 MB / 9 个进程
bma3-app jar                     →  53 MB
```

**（推断）** 单会话浏览器预算按 **700 MB–1 GB** 估。11 GB 机器上扣掉 bma3（JVM）+ QQ 侧，**并发 2 个浏览器会话是安全线，3 个就要开始赌**。这也是"宁可把浏览器放容器、按需起停"的另一个理由：**空闲即回收**比"多开就够"更有效。

### 4.5 依赖与工具链现状

| 项 | 实测 |
|---|---|
| `~/.m2/.../selenium/` | 已有 `selenium-devtools-v154/v155/v156` + `devtools-latest`（每个 ~1.7–1.8 MB）、`selenium-manager-4.52.0.jar` 11 MB |
| `docker` | 可用；`hello-world` 跑通；已有 `linyuchen/llbot:8.3.2`、`linyuchen/pmhq:9.0.6` |
| `Xvfb` / `xvfb-run` | 已装 |
| `at-spi2-core` | 已装 `2.52.0-1build1`；但**无** `python3-pyatspi` |
| `xdotool` / `ydotool` / `x11vnc` / `openbox` / `xfce4-session` / `tigervnc-standalone-server` / `novnc` / `websockify` / `scrot` / `imagemagick` | **均未安装，apt 均有候选**（`x11vnc 0.9.16`、`ydotool 0.1.8`、`xfce4-session 4.18.3`、`novnc 1.3.0`、`websockify 0.10.0`…） |
| Playwright 浏览器缓存 | `~/.cache/ms-playwright/` 有 `chromium-1187`、`chromium_headless_shell-1187`、`firefox`、`webkit` |

### 4.6 上游 MCP 的 `--help` 实测（摘录：为可读性把多行帮助文本归并成一行）

```text
$ npx -y @playwright/mcp@latest --version
Version 0.0.85
$ npx -y @playwright/mcp@latest --help | grep -E "cdp-endpoint|allowed-origins|isolated|idle-timeout|caps"
  --allowed-origins <origins>   ... Important: *does not* serve as a security boundary and *does not* affect redirects.
  --caps <caps>                 ... possible values: vision, pdf, devtools.
  --cdp-endpoint <endpoint>     CDP endpoint to connect to.
  --idle-timeout <timeout>      close the browser after this many milliseconds without a completed tool call ...
  --isolated                    keep the browser profile in memory, do not save it to disk.
$ npx -y chrome-devtools-mcp@latest --help | grep -E "browserUrl|allowedUrlPattern|pageIdRouting|experimentalVision|screenshotMax"
  -u, --browserUrl ...          Connect to a running, debuggable Chrome instance (e.g. `http://127.0.0.1:9222`)
      --allowedUrlPattern ...   Restricts browser's network access by allowing only specified URL patterns ... Requires Chrome 149+.
      --pageIdRouting ...       Require pageId on page-scoped tools and route requests by page ID (useful for concurrent agent sessions).  [default: true]
      --experimentalVision ...  Whether to enable coordinate-based tools such as click_at(x,y).
      --screenshotMaxWidth ...  Maximum width in pixels for screenshots ... Reduces context size in AI conversations.
```

---

## 5. 五条可选技术路线

> 每条按「形状 / 改动面 / 优点 / 代价与风险 / 适配判断 / 粗估」写。粗估是**人日量级**，不是承诺。

### 路线 A —— 现状加固：Selenium 进程内 + CDP 补能力

- **形状**：`bma3-webconnect` 内部不变（仍在 app 进程内拉 Chromium），用 Selenium 的 `DevTools`（v156 已在本地）补齐：`browser_ax_snapshot`（AX 树 + ref）、`press_key`、`scroll`、`wait_for`、`tabs`、`upload`、`download`、`console/network`；给 ref 加 `snapshotId` 代际校验；把单例会话改成 `Map<sessionId, session>` + 空闲 TTL。
- **改动面**：`bma3-webconnect`（主要）+ `bma3-app/config`（会话上限、TTL）+ watchdog 探针 + 工具面测试。
- **优点**：**零新依赖、零部署变化**；今天就能开工；工具面/命名/错误码完全可控；与既有 red lines 同构。
- **代价/风险**：爆炸半径不变（页面漏洞 = bma3 同 uid 同 netns 的代码执行）；bma3 重启丢浏览器；资源与 bma3 抢；容器化仍要做一次改造。
- **适配**：**必须做，但不该是终点**。它是 B 的前置，也是"明天就要能用"的答案。
- **粗估**：2–3 天（含测试）。

### 路线 B —— CDP 客户端 + 浏览器宿主分离（推荐主线）

- **形状**：
  - `BrowserSession` 拆两种实现：`SpawnedChromiumSession`（本地拉起，兼容 A）与 `RemoteCdpSession`（连 `cdpEndpoint`）。
  - 新增配置 `webconnect.cdpEndpoint`（空 = 自己拉；非空 = 附着）。
  - 浏览器宿主三档，同一套工具面：① 本机 `Xvfb + Chromium`（systemd unit）；② **容器**（独立 netns，只挂 `downloads/`、`uploads/`）；③ 另一台机器（跨机，走 SSH 隧道或内网 + token）。
  - 新增 `browser_session_open/close/list`；每会话独立 profile 目录 + `allowedHosts`。
- **改动面**：`bma3-webconnect`（会话管理器 + CDP 客户端）+ `bma3-app`（配置、生命周期、watchdog）+ `packaging/`（容器镜像/unit 文件）。
- **优点**：**一次把"浏览器在哪"变成配置**；bma3 可重启；可容器化获得真隔离；可远端扩容；后续换 Playwright 不动工具面。
- **代价/风险**：多一个需要运维的进程/容器；endpoint 挂了要有明确失败语义（`BROWSER_UNAVAILABLE`，不静默重拉）；端口分配与清理要写对（残留容器 = 资源泄漏）。
- **适配**：**推荐作为 v1 主线**。它正好把远端建议（独立容器）吸收成一个部署选项。
- **粗估**：4–6 天。

### 路线 C —— 直挂上游 MCP server（`chrome-devtools-mcp` / `@playwright/mcp`）

- **形状**：bma3 增加一个 MCP client 桥（stdio），把上游工具映射进自己的工具面（前缀 `cu_` 或 `browser_`）；上游以 `--browserUrl` / `--cdp-endpoint` 附着到同一个浏览器宿主。
- **优点**：**40+ 工具立刻可用**（性能追踪、网络 mock、console、多标签、ARIA 快照、`--experimentalVision` 的 `click_at`）；跟随上游演进；bma3 不写浏览器代码。
- **代价/风险**：
  1. **token**：工具 schema 全量进上下文（可用 `--category*` 裁剪缓解，但仍比自研工具面大）；
  2. **治理**：命名/错误码/权限面不受控，难与 `AGENTS.md` §3 的 red lines 逐条对齐；上游还暴露 `evaluate`（页面内任意 JS = 打内网的现成通道）；
  3. **部署**：bma3 宿主多一个 Node 运行时依赖（现在 Node 只存在于 DSH 宿主，不在 bma3 宿主）；
  4. **定制**：想加"每会话 allowedHosts""ref 代际"这类 bma3 特有语义，仍要 fork 或包一层。
- **适配**：**作为"对照实现"和"能力上限参照"极有价值**；作为正式依赖，治理成本会在三个月内超过自研。建议**读它的设计、不背它的运行时**。
- **粗估**：打通 3–5 天；长期维护另计。

### 路线 D —— 桌面层（L2）：代理 `computer-use-linux` 或自研最小集

- **形状 D1（代理成熟件）**：把 `computer-use-linux`（Rust 二进制，MCP stdio）作为 sidecar，bma3 桥接其工具；需要它驱动一个**隐藏**桌面时搭配 `agent-workspace-linux` 的 X11 后端（Xvfb + Openbox）。
- **形状 D2（自研最小集）**：`Xvfb :N` + `openbox` + `xdotool`（输入）+ `imagemagick`/`scrot`（截图）+ 可选 AT-SPI（`at-spi2-core` 本机已装）；工具面只做 `desktop_screenshot / click_xy / type / key / launch_app / list_windows`。
- **优点**：能操作**任何**原生应用；`computer-use-linux` 的窗口注册表与 `snapshot_id` 设计质量很高，值得抄设计。
- **代价/风险**：合成器矩阵（GNOME/KDE/Hyprland/niri/COSMIC/Wayland/X11 各自坑）；CJK 字体、输入法（中文输入是硬伤）、ATK 依赖、`/dev/uinput` 权限、剪贴板串味；VNC 暴露面；**且维护成本是持续的**。
- **适配**：**bma3 侧目前没有硬用例**——B 站是 web；QQ 客户端 GUI 在 QQ 机上（D-046/D-047 已裁定走 SSH/协议内）。**建议 v1 不做，只留接口**（工具名前缀预留 `desktop_`，配置留 `desktop.enabled=false`）。
- **粗估**：D1 代理 3–5 天（不含调合成器）；D2 自研 2–3 周。

### 路线 E —— 视觉坐标路线（UI-TARS / OmniParser / `click_at`）

- **形状**：每步截图 → 视觉模型给坐标 → 注入点击。可以只取一小块：一个 `browser_click_xy` 工具（`Input.dispatchMouseEvent`），模型侧用现有多模态能力。
- **优点**：能处理 canvas、游戏、无 DOM 的怪页面；不需要 AX。
- **代价/风险**：每步一张图（token 与延迟都高）；坐标准确率依赖模型；不可检索、不可复现；失败模式是"点错地方"而不是"报错"。
- **适配**：**只作兜底**。若做，就做 `chrome-devtools-mcp --experimentalVision` 那一档的**单工具**，不要引入整套 TARS/UI-TARS 栈。
- **粗估**：2 天（只加工具）；深度集成不建议。

### 5.6 路线对比总表

| | A 现状加固 | **B CDP+宿主分离** | C 直挂上游 MCP | D 桌面层 | E 视觉坐标 |
|---|---|---|---|---|---|
| 新增依赖 | 0 | 0（容器另计） | Node 运行时 + 上游包 | Rust 二进制 / X 栈 | 视觉模型 |
| 工具面可控 | 高 | 高 | 低 | 中 | 中 |
| 进程隔离 | ✗ | ✓（容器后） | ✓（取决于宿主） | ✓ | ✓ |
| 多会话 | 需自建 | 需自建 | 上游有 `pageIdRouting` | 需自建 | — |
| bma3 重启影响 | 浏览器死 | 无 | 无 | 无 | 无 |
| 覆盖原生应用 | ✗ | ✗ | ✗ | ✓ | 部分 |
| 三个月维护成本 | 低 | 中 | **高** | **很高** | 中 |
| 建议 | 先做（P0） | **主线（P1）** | 参照 | 不做（留接口） | 兜底可选 |

---

## 6. 建议

> 以下是**建议**，不是裁定。落地前需要 §8 的问题有答案，并按 `AGENTS.md` §1 走「需求 → spec（不变量 + 验收判据）→ 计划 → 实现」。

### 6.1 一句话

**bma3 应该做「CDP 客户端 + 结构化工具面 + 可替换的浏览器宿主」，而不是做「桌面」。**

理由三条：

1. **结构性**：bma3 是工具提供方（`AGENTS.md` §1；D-008 的模块划分）。它一旦自己拥有浏览器/桌面，就同时拥有了一个"需要被监控、被回收、被隔离、被升级"的有状态服务——这与它现在的定位（无状态工具面 + SQLite 消息板）冲突。
2. **成本**：L1 是周级、L2 是月级（§5）；而远端对需求的判断（90% 是浏览器自动化）与 bma3 的真实场景（B 站登录/私信/视频、网页抓取）一致。
3. **可逆性**：把 `cdpEndpoint` 作为唯一 seam，则"本机 / 容器 / 远端 / 换 Playwright / 接上游 MCP"全部是**配置或适配器**级别的事。反之，若先写死 Selenium 拉起，所有后续选择都要重做。

### 6.2 目标形状

```text
DSH agent
   │  MCP (Bearer, 回环)
   ▼
bma3-app  ── bma3-webconnect ──┐
   │        会话管理器           │  CDP over ws://127.0.0.1:<port>（或 unix socket）
   │        (sessionId/TTL/配额)  ▼
   │                       浏览器宿主（可替换）
   │                       ├─ ① 本机 Xvfb+Chromium（systemd unit，起步）
   │                       ├─ ② 容器（独立 netns，推荐）
   │                       └─ ③ 另一台机器（扩容/隔离）
   │
   └─ 人机接力（L3）：CDP Page.startScreencast → 一次性 token 的极简 viewer
                       （只在做 L2 时才退化为 noVNC）
```

### 6.3 建议的 v1 工具面（10–12 个，宁少勿多）

设计原则沿用 D-044（方便第一、省 token 第二）与 `AGENTS.md` §3 红线 5（Agent 不维护内部标记：会话号、快照号由服务端管理）。

| 工具 | 关键参数 | 返回 | 关键语义 |
|---|---|---|---|
| `browser_session_open` | `profileId?`, `allowedHosts?`, `viewport?` | `{sessionId}` | 服务端分配 id；`allowedHosts` 按会话生效（不再全局） |
| `browser_session_close` | `sessionId` | `{closed}` | 幂等；容器/进程一并回收 |
| `browser_open` | `sessionId`, `url` | `{url,title,snapshotId?}` | 仍只允许 http/https + 会话白名单 |
| `browser_snapshot` | `sessionId`, `mode=ax\|text`, `maxNodes?` | `{snapshotId, nodes:[{ref,role,name,value,states,bounds}]}` | **AX 优先**；`text` 是兜底 |
| `browser_act` | `sessionId`, `snapshotId`, `ref`, `action=click\|type\|press\|select\|check\|hover` | `{ok, snapshotId?}` | **`snapshotId` 不匹配 ⇒ `STALE_SNAPSHOT`，零副作用**（抄 `computer-use-linux` 的纪律） |
| `browser_scroll` | `sessionId`, `ref?`, `dy/dx` | `{ok}` | 元素或整页 |
| `browser_wait_for` | `sessionId`, `text?`/`selector?`/`urlContains?`/`networkIdle?`, `timeoutMs` | `{matched, elapsedMs}` | 禁止无限等；上限可配 |
| `browser_screenshot` | `sessionId`, `fullPage?`, `maxWidth?`, `format=png\|jpeg` | `{path, bytes, w, h}` | 默认压到 ≤1280 宽（token 预算） |
| `browser_tabs` | `sessionId`, `op=open\|switch\|close\|list` | `{tabs, activeId}` | 多标签不再靠"重开页面" |
| `browser_upload` | `sessionId`, `snapshotId`, `ref`, `fileId?` | `{uploaded}` | **只接受 fileId 解析到 `<dataDir>/uploads`**，不接受任意路径 |
| `browser_downloads` | `sessionId` | `[{fileId,name,bytes}]` | 落 `<dataDir>/downloads`，有大小上限 |
| `browser_console` / `browser_network` | `sessionId`, `limit?` | `[{...}]` | 建议 GM-only（诊断面，不是业务面） |

**明确不进 v1**：`evaluate`（页面内任意 JS = 打内网的现成通道）、任意 `click_xy`（除非走 E 的兜底并标注"可能点错"）、`file://`、剪贴板读写。

### 6.4 会话模型（"完善"的分水岭）

现状是"全进程一个浏览器"，只要这一点不改，工具再多也不是服务。

- **键**：`sessionId` 由服务端分配（Agent 不维护内部标记，红线 5）。
- **归属**：`sessionId` ↔ `profileId` ↔ 磁盘 profile 目录 `data/browser/profiles/<profileId>`（0600；Cookie 属敏感物，红线 2）。
- **回收**：空闲 TTL（建议默认 10 分钟，可配，上限硬顶）；进程退出/容器回收；`browser_session_close` 幂等。
- **配额**：`maxSessions`（建议默认 2，硬顶 3；依据 §4.4 的 700MB–1GB/会话）；
- **失败语义**：宿主不可用 → `BROWSER_UNAVAILABLE`（**不静默重拉**，避免"以为还在登录态，其实开了个新 profile"）；配额满 → `SESSION_LIMIT`（不排队）。
- **watchdog**：把"宿主健康 + 会话数 + 最近失败"做成探针，接 `bma3-watchdog-spi`（D-049 已有引擎）。

### 6.5 `ref` 代际：这是当前最像"bug"的地方

现状 `browser_snapshot` 给元素打 `data-bma3-ref` 属性（`BrowserTools.java` 的 `SNAPSHOT_JS`），**ref 在下一次页面变化后仍然"看着有效"**。页面重渲染/路由跳转后，同一个 ref 可能指向另一个元素——agent 会**静默点错地方**（下单、发消息、删除）。

- 建议：每次快照生成 `snapshotId`，`browser_act` 必须带；不匹配 ⇒ `STALE_SNAPSHOT` + 提示"重新 `browser_snapshot`"，**在执行任何副作用之前**拒绝。
- 依据：`computer-use-linux` 的 README 明确写了这条设计及其原因（"the index could name another element by then"）；这与本项目 `AGENTS.md` §3 的 fail-closed 文化一致。

### 6.6 人机接力：用 CDP screencast，不要急着上 VNC

- **L1 接力（推荐）**：`Page.startScreencast`（CDP）把帧推到 bma3 的一个极简 HTTP 页面；人看、人点，点的坐标用 `Input.dispatchMouseEvent` 回灌。**不需要 X、不需要 VNC、不新增 5900/6080 端口**，无头浏览器照样能用。
- **触发方式**：工具 `browser_request_handoff {sessionId, reason, ttlSeconds}` → 返回一次性 URL（token 短时效、走回环或 SSH 隧道，红线 1）。人处理完扫码/验证码，会话继续，agent 不需要重开。
- **为什么不先做 VNC**：VNC 只在 L2 有意义（§3.4）。做 VNC 而不做 L2，等于为一个不存在的用例付维护费。
- **扫码登录其实已经有更好的一半**：现状 `browser_screenshot` + `read_image` 就能把二维码给模型/人；L3 只在"需要人真的动手"（拖拽验证码、2FA 推送、输密码）时才必需。

### 6.7 安全边界（必须写进 spec 的不变量）

| 不变量 | 理由 | 落点 |
|---|---|---|
| CDP 端点只绑回环/unix socket，且带 token | 谁连上 CDP 谁就拥有那个已登录的浏览器 | 宿主启动参数 + 配置校验（非回环 fail-closed，红线 1） |
| 浏览器宿主在**独立网络命名空间** | 页面 JS 能 `fetch` 到 `127.0.0.1:8765`（bma3 MCP）、消息板、watchdog 端口 | 容器；`--allowed-origins` **不足以**作为边界（§4.6 实测其自述） |
| `file://` 一律拒；上传只认 `fileId`→`<dataDir>/uploads` | 防止注入页面读本机文件（`input[type=file]` 是现成通道） | `browser_open` 已有 scheme 校验（`:90`），扩展到 `browser_upload` |
| 下载落 `<dataDir>/downloads`，有大小上限，不自动打开/执行 | 同上传的镜像风险 | `browser_downloads` |
| 剪贴板默认禁用 | 剪贴板串味是"数据从别处流进来"的最短路径 | 宿主策略 + 不实现剪贴板工具 |
| 不暴露 `evaluate` | 页面内任意 JS = 打内网的现成通道，且绕过全部域名策略 | 工具面 |
| 每个 profile 不装真凭据 | 截图/console 会把秘密带进模型上下文 | 运维约定 + 文档 |
| profile/Cookie `0600`；日志不记正文、不记 URL 查询串中的敏感参数 | 红线 2/3 | `Redactor` 已有能力，补 browser 侧 |

### 6.8 容量与成本

- 单会话预算 **700 MB–1 GB**（§4.4 实测 886 MB）；`maxSessions=2` 起步。
- **空闲回收比扩容更划算**：`--idle-timeout`（`@playwright/mcp` 默认无头 1 小时）这类机制要在自己的会话管理器里复刻，建议默认 10 分钟。
- 截图 token：默认压到 `maxWidth=1280`、必要时 `jpeg`（`chrome-devtools-mcp` 有一整套 `--screenshotMax*/Quality` 说明 token 按尺寸而非字节计）。
- 容器镜像：基底要带 **CJK 字体**（§4.3 的反面教训：缺字体会让截图全变豆腐）。

### 6.9 分期建议

| 期 | 内容 | 验收判据（可红可绿） |
|---|---|---|
| **P0**（2–3 天） | 路线 A：`snapshotId` 代际 + `press_key`/`scroll`/`wait_for`/`tabs`/`upload`/`download` + 多会话 Map（先不容器化） | ① 旧 `snapshotId` 的 `browser_act` 返回 `STALE_SNAPSHOT` 且**零副作用**（用"点一次就把计数 +1 的页面"验证不 +1）；② 两个会话同时操作互不干扰；③ 会话 TTL 到期后宿主进程被回收（`pgrep` 为空）；④ `./mvnw -q test` 全绿 |
| **P1**（4–6 天） | 路线 B：`cdpEndpoint` 配置 + `RemoteCdpSession` + 容器化宿主（独立 netns）+ watchdog 探针 | ① `webconnect.cdpEndpoint` 指向容器时全部工具可用；② 容器内 `curl 127.0.0.1:8765` **失败**（netns 生效）；③ bma3 重启后会话可重连、登录态在；④ 容器异常退出时工具返回 `BROWSER_UNAVAILABLE` 而不是挂死 |
| **P2**（可选） | L3 接力（CDP screencast viewer）+ 真实 B 站扫码登录端到端 | ① 一次性 URL 过期后不可用；② 人接管期间 agent 调用被明确拒绝而不是并发乱点；③ 真机扫一次码成功（`NOT_RUN` 直到真跑） |
| **不做** | L2 桌面层 | 留 `desktop_*` 命名与 `desktop.enabled=false` 配置位；无指名用例前不开工 |

### 6.10 明确不做

1. **不把任何浏览器二进制打进 bma3 产物**（继承教训：227 MB / 99% 是 Playwright 驱动）。
2. **不在 bma3 里再嵌一层 agent 循环**（browser-use / UI-TARS / Agent TARS 的形态）。
3. **v1 不上 Xfce 全桌面 + VNC**（§5 路线 D 的理由）。
4. **不把 `allowedHosts` 当安全边界**——它只是"防手滑"，真边界在网络命名空间。

---

## 7. 对远端建议的逐条对照

| 远端建议 | 我的判断 | 理由 |
|---|---|---|
| 「轻量 Xfce + Chromium」 | **部分同意** | 若只做 L1，连 Xfce 都不需要（headless 就够）；若要做"随时能接管"，**Xvfb + openbox**（61.9 MB 起）比 Xfce 更省，且不引入 DE 的一堆服务。Xfce 的收益是"人看着舒服"，代价是一整套 session/dbus/panel。 |
| 「Playwright（无头优先）」 | **同意方向，但不必是 Playwright** | bma3 已在用 Selenium 4.52，本地已有 `devtools-v156` 且与 Chromium 156 对齐（§4.1/4.5）——**CDP 能力零成本可得**。选 Playwright 的正当理由是"自动等待/aria snapshot/多 context"，不是"能不能用 CDP"。若引入，**只用 `connectOverCDP`，不下载浏览器二进制**。 |
| 「放独立容器/VM」 | **强烈同意，但要补一条理由** | 主要收益不是 CPU/内存，而是**网络命名空间隔离**（防页面 JS 打 bma3 自己的回环端口）与文件系统隔离。VM 视威胁模型再上，容器是性价比拐点。 |
| 「别装在跑 QQ bot 的宿主机」 | **同意** | 与 `AGENTS.md` §3 红线 1/2 同源：bma3 的 MCP、消息板、QQ token 都在那台机器上；浏览器是**不可信内容的执行器**。 |
| 「GUI 只在真要操作网页/软件时才开」 | **同意，但要改一个细节** | "无头 → 需要时再开"如果**换了进程/换了 profile**，登录态与页面状态会丢。更稳的做法是：**默认无头，但保留"同一会话内转可视"的通道**——L1 用 CDP screencast（无需重启、无需 X），L2 才用 Xvfb+VNC。 |
| 「完整 desktop + VNC 维护和安全成本高」 | **同意** | 且证据更强：合成器矩阵（GNOME/KDE/Hyprland/niri/COSMIC/wlroots/X11）+ 输入法 + ATK + `/dev/uinput` + VNC 暴露面，是持续成本而不是一次性成本。 |
| 「先把 CDP/无头浏览器给我就够 90% 的活」 | **同意，并可量化** | bma3 的真实场景（B 站登录/私信/视频、网页抓取、截图给人看）全在 L1；L2 目前**没有指名用例**（QQ 客户端 GUI 在 QQ 机上，D-046/D-047 已裁定走 SSH/协议内）。 |

---

## 8. 待用户拍板的问题（不预设答案）

1. **Q1 定位**：`bma3-webconnect` 是继续做"bma3 自己拉浏览器"，还是改成"CDP 客户端 + 可替换宿主"（路线 B）？这决定后续所有工作量。
2. **Q2 容器**：浏览器宿主是否**必须**容器化（独立 netns）才能上线？还是"同机 sidecar + 域名白名单"可以先接受、容器排后？
3. **Q3 库**：继续 Selenium（零新增依赖、能力已够），还是换 Playwright（更强等待/多 context，但要引入 `driver-bundle` 与 Node 侧维护）？**建议：先 Selenium+CDP，等出现 Selenium 表达不了的具体需求再评估。**
4. **Q4 上游 MCP**：是否接受"把 `chrome-devtools-mcp` 这类上游 MCP 作为**参照实现/应急通道**"（不进入正式依赖）？
5. **Q5 L3 接力**：人机接力用 CDP screencast（自建极简 viewer）还是 noVNC？前者轻、后者通用。
6. **Q6 L2 桌面层**：是否**现在就确认不做**（只留接口与配置位），还是希望保留"将来操作原生软件"的排期？若有指名用例（哪个软件、什么操作），请给出来，这会直接改变 D 的估算。
7. **Q7 命名**：新工具是否统一前缀 `browser_`（沿用现状），还是借机收敛成 `cu_`（computer use）以给 `desktop_*` 留位？

---

## 9. 外部资料

- `agent-sh/computer-use-linux`（Rust MCP，AT-SPI + 多合成器 + `snapshot_id`）：<https://github.com/agent-sh/computer-use-linux>
- `agent-sh/agent-workspace-linux`（隐藏桌面，Xvfb/Openbox 及 GNOME/KWin/Hyprland/COSMIC 后端）：<https://github.com/agent-sh/agent-workspace-linux>
- `linux-desktop-mcp`（Python，AT-SPI2，Alpha 0.1.0）：<https://pypi.org/project/linux-desktop-mcp/>
- Anthropic computer-use-demo Dockerfile（Xvfb + mutter + x11vnc + noVNC 参考栈）：<https://github.com/anthropics/claude-quickstarts/blob/main/computer-use-demo/Dockerfile>
- Playwright MCP（`@playwright/mcp`，实测 v0.0.85）：<https://github.com/microsoft/playwright-mcp> · <https://playwright.dev/mcp/introduction>
- Chrome DevTools MCP（Google 官方，`--browserUrl`/`--allowedUrlPattern`/`--pageIdRouting`/`--experimentalVision`）：<https://developer.chrome.com/docs/devtools/agents/get-started>
- browser-use（自带 agent loop 的 Python 框架，仅作参考）：<https://docs.browser-use.com/open-source/introduction>
- Stagehand（`act()/extract()/observe()` 框架）：<https://docs.stagehand.dev/>
- UI-TARS / Agent TARS（视觉坐标路线成品）：<https://github.com/bytedance/ui-tars-desktop>
- Microsoft Foundry computer-use tool（截图→动作循环的协议形状参考）：<https://learn.microsoft.com/azure/foundry/agents/how-to/tools/computer-use>

---

## 10. NOT_RUN / 未验证

| 项 | 状态 | 说明 |
|---|---|---|
| 远端 bma3 主机的容器/显示能力 | `NOT_RUN` | 本机不是远端；远端是否装 docker、是否有 `/dev/dri`、是否有 CJK 字体，均未核实 |
| Xfce / openbox / 完整 DE 的实际内存 | `NOT_RUN` | 只测了空 Xvfb（61.9 MB）；未装 `xfce4-session`（会改动本机系统，未执行） |
| VNC / noVNC 端到端 | `NOT_RUN` | `x11vnc`/`novnc`/`websockify` 只有 apt 候选，未安装 |
| AT-SPI 树（`pyatspi`）实跑 | `NOT_RUN` | 本机有 `at-spi2-core` 2.52.0，但无 `python3-pyatspi`；未测任何 GTK/Qt 应用的树形状 |
| `computer-use-linux` 实跑 | `NOT_RUN` | 未下载二进制、未跑 `doctor` |
| `agent-workspace-linux` 实跑 | `NOT_RUN` | 未准备后端包 |
| `@playwright/mcp` / `chrome-devtools-mcp` 实际连通 | `NOT_RUN` | 只跑了 `--help` 与 `--version`；未起 MCP 会话、未附着 CDP |
| Playwright（Java/Python/Node）库 | `NOT_RUN` | 本机有 `~/.cache/ms-playwright` 浏览器缓存，但无 Python/Java 的 Playwright 包 |
| 容器内 Chromium + CJK 渲染 | `NOT_RUN` | 未起容器 |
| 真实 LLBot / DSH / systemd 联调 | `NOT_RUN` | 与本次调查无关，沿用 `STATUS.md` 的既有 `NOT_RUN` |
| 线上真实 B 站扫码登录 | `NOT_RUN` | 需要真实会话与人的参与 |

**本机实测过、可作为事实引用的**：CDP 附着与版本、AX 树、CDP 点击生效、截图（含 CJK）、Xvfb/Chromium 内存、Selenium DevTools 依赖存在性、docker 可用性、apt 候选、两份上游 MCP 的 `--help`。
