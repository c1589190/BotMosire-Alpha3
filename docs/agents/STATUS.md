# STATUS —— 更新时间：2026-11-12 19:10 (UTC+8)

## 当前目标 / 进行中范围

**最近调查（2026-11-13，未改产品代码）**：Computer Use 服务技术路线调查 —— 应远端建议（轻量桌面 + Chromium/Playwright + 独立容器）核查 Linux 侧现成技术，给出 5 条候选路线与建议。
结论摘要：L1 浏览器 / L2 桌面 / L3 人机接力应分层；本机实测 headless Chromium 886 MB/9 进程、Xvfb 61.9 MB、CDP+AX 树+点击均可用。
**用户方向（D-053/D-054）：Linux 侧直接上「全桌面 Computer Use」，浏览器不单独成线，由 Agent 在桌面内自行操作；不设策略闸门；取消 L3 人机接力（Agent 即全权力用户）；webconnect 保留并纳入 watchdog 探针。** 报告原建议（L1 优先、L2 留接口、noVNC 接力）据此部分作废。
报告：[`../superpowers/reports/2026-11-13-computer-use-linux-routes-survey.md`](../superpowers/reports/2026-11-13-computer-use-linux-routes-survey.md)（§1–§5 事实仍有效；§6/§8 已被 D-053/D-054 取代）。**未开工，需先出 spec。**

**0.0.5-early（当前开发版本，未发布/未打 tag）**：`fb2f05a`，已推送 `feat/bma3-plan-1`。内容 = 新增 `bma3-xvfb`（全桌面 Computer Use 底座）。上一已发布版本仍是 `v0.0.4-hotfix1`。
**长图自动切段（2026-11-13，未提交）**：`LongImageSplitter`（qqconnect）+ `qq_media_view` 接线。
- 1080×18000 实测切成 9 段、每段 ≤2048 高、相邻重叠 64px、一律写 PNG（避免二次有损）；产物落在 `policy.dir()`（=`<dataDir>/downloads`），可直接被 QQ 发送工具取用。
- 拒绝路径齐全：像素数 >4000 万（OOM 风险）/ 段数 >16 / ImageIO 解不了的格式，都返回明确 reason 而不是装成功。
- 工具返回新增 `width/height/split/parts[{index,total,path,bytes,width,height,y0,y1}]`；参数 `split=auto|never`、`maxPartHeight`（默认 2048）。
- 测试：`LongImageSplitterTest` 7 条（不切/切段覆盖全高/相邻重叠/落盘位置/段数上限/非图片/probe 只读头）。真图肉眼验证过第 5 段：文字清晰。

**远程报告修复批次（2026-11-13，未提交）**：9 条里 7 条已改、2 条部分改（P1-3 主动加好友依赖 provider；P3-8 老数据不可恢复）。
新增工具 `qq_group_info` / `qq_delete_msg`；`qq_remote_history` 带 `attemptDetails` + `TIMEOUT` 分类 + `timeoutMs`；发送结果带 retcode；
`qq_private_send` 支持群临时会话；`qq_forward_view` 支持 `depth=2`；`qq_group_list` 失败回退缓存；`senderId="0"` 修正；面板省略提示改人话。
门禁：`./mvnw -o test` **136 tests / 0 fail / 1 skip**；`dsh-plugin npm test` **28 pass**。（D-056 后：**145 tests / 0 fail / 1 skip**）
**实际调用测试 PASS**（2026-11-13，真进程 + 真 MCP + 扩展过的 FakeOneBot + 假 CDN，12 项）：group_info 正常/不在群、delete_msg 成功/被拒/参数非法、history 1200 降级与 **TIMEOUT** 分类（`attemptDetails` 可见）、群临时会话（假服务日志 `tempSession=true`）、forward depth=1/2、group_list 失败回退缓存、1080×18000 长图切 9 段。
**测试中又抓到一个真 bug**：群列表缓存回退最初判的是 `ToolResult.success()`，而 provider 失败时它仍是 ok → 回退从不触发；改为解析 `response.status/retcode` 后才对。
**真实 LLBot 实测（2026-11-13，token 由用户提供）**：连上 `3924945183(肥鱼弗洛洛)`；`qq_group_list` 返回 **22 个真实群**（报告说缺的 `150112205`/`938269591`/`719028297` 都在）；`qq_group_info` ×4 全 `ok=true`（含「历史 0 条」的 `719028297`）⇒ **P1-2 不是不在群**。
**⚠ 本条已被 2026-11-13 二次实测推翻**：当时 `qq_remote_history` 对 3 个群 × count 1000/300/100 全部 TIMEOUT，据此写的「这台 LLBot 根本没实现 `get_group_msg_history`」**是错的**。读容器内 `llbot.js` 源码 + 重启后实测：该 action 内部**固定 30 条一窗、5 路并发**，`count=1000` 会瞬间发出 34 个 `SsoGetGroupMsg` 打死 QQ 客户端的 CMD-PIPE，**连无关 OIDB 命令一起超时**；`count ≤ 300` 全部秒回（10/30/60/100/300 → 0.47/0.64/0.73/1.37/3.58 s）。根因是 bma3 传了过大的 `count`。详见 [`../superpowers/reports/2026-11-13-llbot-host-freeze-investigation.md`](../superpowers/reports/2026-11-13-llbot-host-freeze-investigation.md)。
**另**：`qq_remote_history` 发的 `reverse_order`（snake）**不是 LLBot 参数**（它认 `reverseOrder`），`parse_mult_msg`/`disable_get_url` 属 NapCat，三项一直无效。

**D-056 `qq_remote_history` 游标分页（2026-11-13 已实现 + 真机验证）**：spec [`../superpowers/specs/2026-11-13-remote-history-paging-design.md`](../superpowers/specs/2026-11-13-remote-history-paging-design.md)。
- `count` 1000→**100**（默认 **30** = provider 恰好 1 个 RPC）；删除「1000→300→100 降级阶梯」（超时说明管道已被打死，再降无效）。
- 参数修正：发 `reverseOrder`（camelCase）；不再发 `reverse_order`/`parse_mult_msg`/`disable_get_url`。
- 游标：`nextCursor = 最旧 seq - 1`（往更旧）、`prevCursor = 最新 seq + count`（往更新）——**两方向不对称**，因为 provider 语义是「取到 seq N 为止向过去数 count 条」；按 min/max 计算，与 `reverseOrder` 无关。
- 保护：同账号**串行**（第二个返回 `HISTORY_BUSY`，不排队）；连续 3 次 `HISTORY_TIMEOUT` → 熔断 60s（期间 `HISTORY_CIRCUIT_OPEN`，不发请求）；超时**不重试**。
- `retcode=1200` 仍是空页（`ok` + `hasMore=false`），不是错误。
- **真机验证 PASS 8/8**（`tools/e2e-local/history_paging_probe.py`，真 LLBot + 真 MCP）：`count=30` 0.64s/30 条 → `nextCursor` 翻页 30 条、**零重叠**、`min(page1)-max(page2)==1` → `prevCursor` 精确回到第 1 页 → `count=100` 1.08s/100 条 → `count=1000` 被 MCP schema 拦在工具之外。
- **真机抓到并修掉的 bug**：`prevCursor` 最初写成 `最新 seq + 1`，翻回去只得到本页平移一条（`match=False`）；改为 `+count` 后 `match=True`。单测按旧期望写也因此更正值。
- `QqRemoteHistoryToolTest` 4 → **13** 条（覆盖 A1–A12）。
**未验**：`qq_delete_msg` / `qq_private_send`（含群临时会话）的真实平台语义 —— 当时 LLBot 停止接受 WS 连接的原因**已查明不是「单连接」**（该结论同样被推翻），而是下述整机冻结：17:21:06 后 ECS 内存耗尽 livelock 约 16 分钟，TCP 能连是内核 SYN 队列还活着、WS 握手超时是用户态已不调度。已修：swap 6G、`swappiness` 0→10、两容器加内存上限。

**远程 0.0.4-early 使用报告已定位**（9 条 P1/P2/P3）：[`../superpowers/reports/2026-11-13-remote-usage-report-triage.md`](../superpowers/reports/2026-11-13-remote-usage-report-triage.md)。结论：2 条已实现（历史降级、面板省略提示）、3 条真缺口（`qq_group_info` 诊断、发送 retcode、`qq_delete_msg`）、其余为已知限制或文案。**未修复、未复现（NOT_RUN）**。

**bma3-xvfb（2026-11-13 新增模块，已本机真机验证）**：全桌面 Computer Use 的 Xvfb 功能封装包。
- 模块 `bma3-xvfb`（依赖 agentlib）：`PlatformProbe`（平台检测 + 缺件安装提示）、`WorkspaceStore`（`${cwd}/.xvfb/ws-xxxx/` 工作区与 meta.json）、`XvfbService`（起 display/WM、launch、screenshot 根窗口、windows、exec、xdotool 输入）、`XvfbTools`（12 个 `xvfb_*` 工具）。
- 装配：`AppConfig.Xvfb`（默认 `enabled=true`、`workspaceRoot=""`⇒`${cwd}/.xvfb`、1440×900×24）+ `ConfigLoader`/`ConfigView` + `Bma3App` 工具注册与关闭回收 + watchdog `xvfb` 探针（D-054「豪华套餐」）。
- 设计：[`../superpowers/specs/2026-11-13-bma3-xvfb-design.md`](../superpowers/specs/2026-11-13-bma3-xvfb-design.md)。方向：D-053 / D-054 / D-055。
- **真机验证 PASS**（本机 Ubuntu 24.04 / Chromium 156）：
  - JUnit IT：`xvfb_status.supported=true`（screenshot=ffmpeg、clipboard=/usr/bin/xclip）；开 `:90` 工作区 → 起 Chromium → `xvfb_windows` 解析 3 个窗口 → `xvfb_screenshot` 1440×900 / 27,133 B（截图含浏览器标签栏与中文）→ `xvfb_exec` rc=0 `DISPLAY=:90` → 剪贴板往返 `剪贴板往返` rc=0 → `close{keep:false}` 后目录与进程均消失。
  - 真 MCP 端到端（`java -jar` + Bearer）：`tools/list` **50 个工具**（`xvfb_*` 12 个齐全）→ `xvfb_status` → `workspace_open`(1024×768) → `xvfb_launch` → `xvfb_windows`(3) → `xvfb_screenshot`(21,329 B) → `workspace_close`；工作区根为 `${cwd}/.xvfb`。
  - **输入链路 PASS**（2026-11-13，`xdotool 3.20160805.1` + `xclip 0.13` 装好后）：`xvfb_act` 点击 → 页面 `document.title=CLICK_OK`；`xvfb_type`（clipboard 模式）打「中文测试」→ `title=TEXT:中文测试`；`xvfb_key Escape` → `title=KEY_OK`。证据截图 `bma3-xvfb/target/xvfb-verify/keep-last.png`：标签页标题 `KEY_OK`、textarea 里是「中文测试」。
  - **窗口管理器 PASS**（2026-11-13，`openbox 3.6.1` 装好后）：`xvfb_status.windowManager=/usr/bin/openbox`；`xvfb_workspace_open` 回报 `wmPid=122000`（真起来了）；窗口列表里能看到 Openbox 的 1×1 辅助窗口；截图里出现 **openbox 标题栏**（`KEY_OK - Chromium` + 最小化/最大化/关闭按钮），Chromium 窗口几何从 `+10+10` 变成 `+1+20`（标题栏占 20px）。截图后端此时自动选中 `scrot`（`import`→`scrot`→`ffmpeg` 顺序）。
  - **标准截屏三取景 PASS**（2026-11-13）：全屏 1440×900 / 窗口 `0x400002` 1050×875（几何 `10,20,1050,875`）/ 区域 `0,0,320,200`；三者都额外产出 `<dataDir>/downloads/` 同名副本（`download` 字段），后端自动选 `scrot`。
  - 命令：`BMA3_XVFB_VERIFY=1 ./mvnw -o -pl bma3-xvfb -am test -Dtest=XvfbRealMachineIT -Dsurefire.failIfNoSpecifiedTests=false`（`BMA3_XVFB_VERIFY_KEEP=1` 保留现场与截图）。
- 门禁：`./mvnw -o test` **122 tests / 0 fail / 1 skip / BUILD SUCCESS**（xvfb 16、app +3）。
- 未做：AT-SPI 树（`xvfb_tree` 返回 `ATSPI_UNSUPPORTED`）、容器化会话、空闲 TTL、Windows 后端。
- 本机已装 `xdotool` / `xclip` / `openbox` / `scrot`，Computer Use 全链路无 blocker。
- 已知噪音（非缺陷）：有 WM 时 `xvfb_windows` 会带出 Openbox 自己的 1×1 辅助窗口；`xvfb_windows` 返回的是**真实窗口树**，不过滤，由 Agent 自行按尺寸/标题筛。

**0.0.4-hotfix1（当前发布）**：修复「重装插件后 bash/read_image 消失」。
根因：`host-tools.js` 把解析失败的 `undefined` 也缓存；`bma3_self_status.lockedTools` 只看 global 视图；
`bma3_self_reload` 不会重挂 preset 子行。修复：不缓存 miss + `invalidateHostToolCache()` + 新增
`bma3_self_repair` + `lockedTools/missingLockedTools` 按 agent scope 计算 + 自动 repair。
报告：[`../superpowers/reports/2026-11-12-dsh-plugin-locked-tools-repair.md`](../superpowers/reports/2026-11-12-dsh-plugin-locked-tools-repair.md)。
版本统一为 `0.0.4-hotfix1`；发布流程：本地门禁 → commit → tag `v0.0.4-hotfix1` → push → Pre-release。
（历史：`0.0.4-early` 已于 2026-11-12 发布。）

**0.0.4-early（上一版，已发布）**：watchdog + 投递模型 + 身份目录 + 富消息 + 协议内文件/历史。
设计：[`../superpowers/specs/2026-11-12-0.0.4-watchdog-delivery-design.md`](../superpowers/specs/2026-11-12-0.0.4-watchdog-delivery-design.md)（D-042..D-050）；
计划：[`../superpowers/plans/2026-11-12-0.0.4-plan.md`](../superpowers/plans/2026-11-12-0.0.4-plan.md)；
回归报告：[`../superpowers/reports/2026-11-12-0.0.4-e2e-report.md`](../superpowers/reports/2026-11-12-0.0.4-e2e-report.md)。

已完成（代码 + 单测 + 本地真进程 e2e）：

1. **Inbox 1B 投递模型**：同源可两条、推送后冻结、`continued` → 面板「（消息更新活跃）」；删除 `consolidateSources()` 与续接合并；ack 幂等；`pushRevision` 落库；`frozen_revision` 作为已推送标记。
2. **`inbox_dismiss`**：`scope=visible|filter|ids`、`priorityBelow/priorityAtLeast/sourcePrefix/olderThanSeconds/limit`、`dryRun`、`allowImportant`；HTTP `POST /dismiss`；watchdog `autoDismiss` 维护任务。
3. **DSH 插件**：状态文件持久化（默认 `~/.dsh/storages/bma3-inbox.json`）；`bind` 落盘；加载时 `agents.get() ?? agents.resume()` 并自动 start；`pushRevision` 去重；`/plugin/heartbeat`；服务端 `control`（restore/remind）；`mcpStartId` 变化时提醒重连 MCP。
4. **watchdog**：新增 `bma3-watchdog-spi`（JDK-only）+ `bma3-watchdog`（依赖除 app 外全部模块）；12 探针、4 恢复动作、1 维护任务、3 个 MCP 工具、`state.json`/`events.jsonl`、固定端口 `/healthz` `/readyz` `/watchdog`、部署者 QQ 私聊报警。
5. **调度**：同源 append 重置等待；`maxFirstPushDelaySeconds` 首次推送硬上限；open item 优先级取 max；`DeliveryPolicy` 热重载；默认 `importantDelaySeconds=3`、`normalDelaySeconds=60`；**`inbox.push.minPriority` 已废弃/忽略**——所有未被 `dropBelow` 丢弃的条目都会推送（低优先走 60s 批次），不再有“只注入不推送”的硬静音。
6. **面板预算**：`maxCharsPerItem`（默认 1200）、`maxMessagesPerItem`/`maxItemsPerPanel` 放宽 1..50、`truncatedChars`；不设总字符上限。
7. **身份/目录/搜索**：`sender_card`/`sender_role` 落库；群名进 source label；`qq_group_members`/`qq_group_member_info`（含头衔）；`qq_person_id/<qq>` 取代 `qq_sender/`，删除 `qq_name/`；`qq_search`。
8. **富消息**：json/xml 卡片（有界 raw、title/desc/url/bizsrc、群邀请 groupId+sequence，ticket 不落库）；`qq_card_view`；`qq_forward_view`（`get_forward_msg` 一层展开、分页、嵌套标记）；`qq_group_request_list/decide` + `qq_group_invite_accept`。
9. **文件/历史**：`qq_conversation_send_file`（`base64://` → 绝对路径 → `providerPath`，三态，UNKNOWN 不重试）；`qq_remote_history`（只读穿透，count 1..1000；真实 LLBot 对 1000 不稳定，自动降级 1000→300→100，retcode 1200 空页，每次尝试记日志）。
10. **观测**：`inbox_status` 带 `pushRevision/ackConflicts/pushes/repushes/dismissed/recentErrors`，计数器跨重启持久化。

## 源码基线

- 仓库：`/home/cna/BotMosire-Alpha3`。
- 分支：`feat/bma3-plan-1`；HEAD 见 `git log -1`。
- 版本号：`0.0.5-early`（pom ×10 / `Main.VERSION` / MCP serverVersion / dsh-plugin package.json / packaging/smoke.sh 一致）。
- 发布：**v0.0.5-early**（2026-11-13，用户「可以正式发布0.0.5-early版本了」授权）。
  - Release：<https://github.com/c1589190/BotMosire-Alpha3/releases/tag/v0.0.5-early>（Pre-release）
  - tag `v0.0.5-early` → commit `53e9d86`
  - assets：`bma3-app-0.0.5-early.jar`（sha256 `28a59d52…`）、`mosire-bma3-dsh-0.0.5-early.tgz`（sha256 `67589baf…`）、`SHA256SUMS`
  - 回下载校验 PASS：`sha256sum -c` OK；jar `--version` = `0.0.5-early`；tgz `package.json` version = `0.0.5-early`，`lib/` 含 4 个模块
- 上一版：**v0.0.4-hotfix1**（2026-11-12，commit `f450608`）保留；再上一版 `v0.0.4-early`（commit `fc99132`）。
  - Release：<https://github.com/c1589190/BotMosire-Alpha3/releases/tag/v0.0.4-hotfix1>（Pre-release）
  - tag `v0.0.4-hotfix1` → commit `f450608`
  - assets：`bma3-app-0.0.4-hotfix1.jar`（sha256 `96463fb5…`）、`mosire-bma3-dsh-0.0.4-hotfix1.tgz`（sha256 `8eeddeb3…`）、`SHA256SUMS`
- 继承参考：`/home/cna/DevMosire-iiBot`（只读）。

## 最近门禁

**0.0.5-early 发布门禁（2026-11-13，commit `53e9d86`）**

| 检查 | 命令 | 结果 |
|---|---|---|
| Java 全量 | `./mvnw -o test` | **PASS**：core 7 / agentlib 5 / qqconnect 49 / inbox 29 / webconnect 4（1 skip）/ xvfb 16 / watchdog 6 / app 29 = **145 tests，0 fail**（144 pass + 1 skip） |
| DSH 插件 | `dsh-plugin && npm test` | **PASS**：28 tests |
| 打包 smoke | `bash packaging/smoke.sh` | **PASS**：app-image 启动且 `/healthz` 200，install.sh `--dry-run` 输出 `systemctl --user` |
| 真机 LLBot 历史分页 | `tools/e2e-local/history_paging_probe.py` | **PASS 8/8**：`count=30` 0.64s/30 条、翻页零重叠且严格连续、`prevCursor` 精确回页、`count=100` 1.08s/100 条、`count=1000` 被 schema 拦截 |
| 发布资产回下载 | `gh release download v0.0.5-early` + `sha256sum -c` | **PASS** |
| `qq_delete_msg` / `qq_private_send`（含群临时会话）真实语义 | — | `NOT_RUN` |
| 长图切段真实经 QQ 发出 | — | `NOT_RUN` |
| DSH 插件真实宿主安装 / watchdog 真实报警 | — | `NOT_RUN` |

| 检查 | 命令 | 结果 |
|---|---|---|
| Java 全量测试 | `./mvnw -o test` | **PASS**：core 7 / agentlib 5 / qqconnect 26 / inbox 29 / webconnect 4（1 skipped）/ watchdog 6 / app 26 = **103 tests，0 fail**（102 pass + 1 skip） |
| DSH 插件测试 | `cd dsh-plugin && npm test` | **PASS**：28 tests |
| 0.0.4 主 e2e | `tools/e2e-local/drive6.py` | **PASS 36/36**（watchdog/目录/卡片/1B/dismiss/文件/历史/搜索/热重载/断线重连） |
| 重启不重推 | `tools/e2e-local/drive6b.py` | **PASS 5/5**（pushRevision/计数器持久化、冻结条目不 pending、不自动重推、新消息仍推进） |
| 真插件客户端 roundtrip | `node tools/e2e-local/e2e_inbox_roundtrip.mjs <inboxPort> 46701` | **PASS 9/9**（窗口 6/8 → 两轮 ack 8 条、itemIds、不重复推） |
| 热重载/防抖/面板/控制面 | `tools/e2e-local/drive6c.py` | **PASS 9/9**（4.04s 延迟、3.02s 防抖重置、truncatedChars、HTTP dismiss、heartbeat/control、minPriority=300 下低优先仍推送） |
| 部署者报警 | `tools/e2e-local/drive6d.py` | **PASS 5/5**（断线→重连→私聊 `[BMA3 watchdog] qqAccount DOWN...`） |
| 真实 LLBot 私聊发送 | `tools/e2e-local/llbot_send_test.py` | **PASS 6/6**：文本 `SENT`、私聊文件 `upload_private_file` `SENT via base64://`、OUT 回写 messageId、真实入站回复 |
| 真实 LLBot 只读探测 | `LlbotProbe ws://47.100.101.156:3001/ <token> 955432692` | **PASS 8/8**：22 群、34 成员（15 title/4 card）、`get_group_member_info.title` 存在、历史 100/300 正常而 1000 超时/retcode1200→工具降级到 300、真实 `get_forward_msg` 形状 `data.messages[].content[]` 可由 `forwardId` 展开、`get_group_system_msg` 正常 |
| jlink/jpackage smoke | `bash packaging/smoke.sh` | **PASS**（app-image 启动且 `/healthz` 200） |
| GitHub Release 资产回下载 | `gh release download v0.0.4-hotfix1` + `sha256sum -c SHA256SUMS` | **PASS**：jar/tgz sha256 OK、jar `--version` = `0.0.4-hotfix1`、tgz `package.json` version = `0.0.4-hotfix1`、含 `lib/inbox.js` 等 10 个文件 |
| 真实群文件 / 真实卡片 / 真实群邀请 / 真实 DSH resume / 真实部署者报警 | — | `NOT_RUN`（见报告 §5） |

证据路径：`/tmp/bma3-e2e-004/`（app.log、data/*.db、fake.log）；报告
[`../superpowers/reports/2026-11-12-0.0.4-e2e-report.md`](../superpowers/reports/2026-11-12-0.0.4-e2e-report.md)。

## 当前有效设计 / 维护入口

- 0.0.4 设计：[`../superpowers/specs/2026-11-12-0.0.4-watchdog-delivery-design.md`](../superpowers/specs/2026-11-12-0.0.4-watchdog-delivery-design.md)
- 0.0.4 计划：[`../superpowers/plans/2026-11-12-0.0.4-plan.md`](../superpowers/plans/2026-11-12-0.0.4-plan.md)
- 设计裁定：D-042..D-050（[`../superpowers/DESIGN_RULINGS.md`](../superpowers/DESIGN_RULINGS.md)）；其中 D-043 取代 0.0.3 的「推送即冻结 + 同源续接」、D-044 取代「后到不重置」、D-048 取代 `qq_name/` 与 `qq_sender/`。
- 0.0.3 设计（仍有效的部分）：[`../superpowers/specs/2026-11-11-priority-rules-and-push-schedule-design.md`](../superpowers/specs/2026-11-11-priority-rules-and-push-schedule-design.md) 的分数/配置/媒体/诊断章节；§5.2 第 3 条与 §12 的续接语义已被 0.0.4 取代。
- 维护裁定：M-002（[`MAINTENANCE_RULINGS.md`](MAINTENANCE_RULINGS.md)）。

## 已实现的关键行为（0.0.4）

- **推送是持久水位**：`inbox_meta.push_revision` + `inbox_item.frozen_revision`；Java 重启不重推已冻结条目。
- **同源可多条**：未冻结条目才合并；推送后新消息另开条目并标「（消息更新活跃）」。
- **ack 幂等**：重复 ack 已确认条目返回 200/no-op；同源第二条不会让第一条 409。
- **dismiss**：不读正文按过滤条件清；默认保护 `priority >= immediateThreshold`；只标 ACKED 不删除。
- **watchdog**：QQ 断线自动重连（`AccountRegistry.forceReconnect` + reconcile 死连接替换）；Inbox HTTP/MCP 可重启；插件心跳/控制；关键组件 DOWN → `/readyz` 503；部署者 QQ 私聊报警（冷却、统一发送出口、UNKNOWN 不重发）。
- **身份**：群名片优先显示，昵称以 `[昵称:...]` 保留；头衔来自目录缓存；群名进来源标签。
- **卡片**：面板只显示 `[卡片] 标题`；完整字段/链接/群邀请只在 `qq_card_view` 显式返回；ticket 不落库。
- **转发**：`qq_forward_view` 只读展开一层，分页；嵌套不递归。
- **文件**：只走 OneBot `upload_group_file`/`upload_private_file`；不假设共享 FS、不提供 BMA3 文件服务器。
- **历史**：`qq_remote_history` 只读穿透，不写消息板；`count` 上限 100。

## 未验证 / 风险 / blocker

- **真实 LLBot**：只读探测 PASS + **私聊发送 PASS**（文本 `SENT`；私聊文件 `upload_private_file` `SENT via base64://`；真实入站回复已收到）。**群文件 `upload_group_file` 未真实发送**，`providerPath` 未用；`llonebot_debug` 回退无待处理邀请，`NOT_RUN`。
- **真实 DSH 宿主**：`agents.resume` + 插件热重载/重启恢复 `NOT_RUN`。
- **跨机文件**：若真实 LLBot 不接受 base64/绝对路径，按 D-047 由 Agent 用 bash 放到 QQ 机后传 `providerPath`；该路径未真机执行。
- **真实 QQ 卡片样本**：群邀请卡片的 `mqqapi://` 参数名/格式只有继承仓代码与假样本证据，真实样本 `NOT_RUN`。
- **部署者 QQ 报警**：e2e 只到假 OneBot；真实私聊 `NOT_RUN`。
- **`qq_search` 性能**：`LIKE` 在超大消息板上可能慢；FTS5/中文分词留后续。
- **真实 DSH 插件热重载/常驻**：`NOT_RUN`。
- **systemd 常驻**：`NOT_RUN`。

## 下一步

1. 真实 LLBot **文件上传**：需要明确的目标群/人；验证 `base64://`/绝对路径/`providerPath`（当前只做了只读探测）；
2. 真实 DSH 宿主验证插件 `agents.resume` + heartbeat 控制；
3. 真实卡片样本（json/xml）与真实群邀请接受（需要一条真实待处理邀请）；
4. 真实部署者 QQ 报警（需要真实账号）；
5. `qq_search` 视数据量再评估 FTS5；
6. 0.0.4-hotfix1 已发布；后续真机项（群文件/真实卡片/DSH resume/部署者报警）通过后再决定下一个版本。

```bash
git status --short --branch
```
