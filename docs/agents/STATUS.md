# STATUS —— 更新时间：2026-11-14 (UTC+8)

## 当前目标 / 进行中范围

> ⚠️ **2026-11-14 方向反转（D-066）**：用户裁定**删除工具面耦合层**，插件收窄为两件事 ——
> ① `bma3-jar` 的 MCP 服务可达，并由 Agent 用 DSH 自带 `mcp_connector_configure` 自行配置连接；
> ② inbox 注入 DSH 会话。**`bma3` 预设保留**，但职责只剩「注入自定义系统提示词」。
> 下面 0.0.6 的工具面切片（D-059/D-065、`toolFace`、`bma3_status/connect/tools`、预设内 MCP 桥接、
> per-agent 组合）**已全部删除**，相关条目仅作历史记录；token 自举（`Bma3Env`/`--no-auth`/`--rotate-token`/
> 单实例锁）与 D-058/D-060 仍然有效。

**工具面调查入库（2026-11-14，只读，未改产品代码）**：bma3 preset 的 Agent 工具面链路已固化到
[`../superpowers/reports/2026-11-14-bma3-dsh-tool-face-survey.md`](../superpowers/reports/2026-11-14-bma3-dsh-tool-face-survey.md)。
- **装配五层**：profile → bundle（`dsh-plugin/cordis.patch.yml`）→ 模块分发（`index.js:24-40`）→ 组合（`lib/preset.js:13-62`，scoped 工具唯一来源）→ 策略（`lib/self.js:563-631`）。Agent 只能改 `<cwd>/agentconfig.json`。
- **核心差距**：`self.js:177-184` 用**全局视图**算 allow，而宿主 `restrict()` 裁的是「全局 + **祖先作用域**」的工具（探针 `tools/e2e-local/dsh-tool-face-probe.mjs`，宿主 `dsh-tools@0.2.2-rc.3` PASS）⇒ preset 层的 `bash/read_image/subagent/*` 会被同一次 `restrict()` 裁掉；`bash/read_image` 靠 `repairHostTools()` 在 agent 层重挂复活，**subagent 一族无复活路径**。
- 另：`wait_agent` 漏在 `SUBAGENT_BASENAMES`（`self.js:68-75`）；`xvfb_*`/`browser_*`/`inbox_dismiss` 无组覆盖；`PRESET_PROVIDED_GROUPS` 把 terminal/image/subagent 从 `missingGroups` 吞掉（`self.js:326`）；单测 fake harness 是单层 registry，测不出以上。
- 门禁：`dsh-plugin npm test` **45 pass / rc=0**；探针 **PASS / rc=0**。真实 DSH 里的 bma3 工具面仍 `NOT_RUN`（该时点 preset 行 disabled）。
- 下一步：用户提出「原生 / 业务 / 可选」三层工具面方向，评估见当次会话；**设计稿待写**（按 §1 需 spec 的不变量 + 验收判据）。

**上游能力核查（2026-11-14，只读）**：DeepSeek 2026-11-13 发布「全家桶应用 API」，契约摘要入
[`../upstream/2026-11-14-deepseek-fullstack-app-api.md`](../upstream/2026-11-14-deepseek-fullstack-app-api.md)。
- 独立接口：Search / Fetch（各 5 元/1000 次成功，QPS 100 / 25）、OCR（1.5 元/1000 页）、ASR（0.78 元/小时）、TTS（3 元/万字符）、文件解析（按页）。
- **可直接复用**：宿主 `0.2.2-rc.3` 已带 `dsh-web` + `dsh-web-deepseek-api`（`deepseek-search-api`/`deepseek-fetch-api`，直连上面两个接口、无额外模型轮次）+ `dsh-tool-web`（`web_search`/`web_fetch`）。`dsh-tool-web` 的 `ctx.tools.register(..., { when: () => ctx.web.searchReady(...) })` 就是"能力就绪才暴露"的现成范式（`src/search.ts:414`、`src/fetch.ts:516`），可作为三层设计 L2 门控的先例。
- **不可直接用**：模型侧 `deepseek_web_search` / `deepseek_read_url` —— `dsh-llm-deepseek@0.2.2-rc.3` 的 wire 类型无 `server_tool_use`，要改上游。
- **缺口/风险**：bma3 无文档解析（OCR 可补，但属按页计费的重操作）；QQ 语音若要 ASR 需先转码（API 只收 16 bit PCM WAV / Ogg Opus）；正文出境是新红线问题；`web_search` 与 modsearch 的 `read_page`/`x_search` 语义重叠需去重。
- 未验证：未用真实 Key 调用任何接口（`NOT_RUN`）。

**工具面分层裁定（2026-11-14，D-058/D-059）**：用户给出两条方向裁定并已入 [`DESIGN_RULINGS.md`](../superpowers/DESIGN_RULINGS.md)。
- **D-058 不设出网闸门**：Agent 可自行连任意 MCP server（任意 URL/headers）；安全边界由容器/VM 承担，插件不做白名单。
- **D-059 默认窄面**：bma3 预设默认只可见 `bash` + bma3 基础检查 + MCP 配置/连接工具；业务工具、DSH 搜索/文件/子 Agent 默认不可见，由工具面管理工具加回。
- **技术后果（已核实）**：「能被加回」要求工具**已在某作用域注册**——本机 base 层 `tool-bash`/`tool-pwsh`/`tool-fs`/`tool-subagent`/`tool-web` 全局行全部 `disabled: true`，只在 preset 内挂载。所以 preset 需要「挂得宽、显示得窄」，否则管理器无法复活未挂载的工具。
- **已裁定（D-060）**：`mcp_connector_configure` 的 Bearer token 是字面值参数（`dsh-mcp-connector/lib/index.js:2163-2210`）→ 会进会话日志；用户裁定**收窄红线 2**（允许落进运行期会话日志，仍禁止进提交/配置样例/仓库文件/MCP 结果，凭据文件仍须 0600）。根 `AGENTS.md` §3 已改。
- **D-064**：无鉴权改为**显式启动模式**（`security.auth: token|none` / `--no-auth`），默认 `token` 且缺 token 仍 fail-fast；`/healthz` 报 `auth` 供插件决定带不带 Bearer。
- **D-061/D-062/D-063（同次）**：单实例锁（同一 `dataDir` 只允许一个 jar）；允许**无 token 启动**（仅回环，非回环仍硬拒）；token 统一由 jar 生成 `.bma3env`（`MCP_TOKEN`/`INBOX_TOKEN`，0600、原子写、幂等），**删除插件的 `tokenFile`/`tokenEnv`**。
- **设计稿已成文**：[`../superpowers/specs/2026-11-14-bma3-tool-face-layers-design.md`](../superpowers/specs/2026-11-14-bma3-tool-face-layers-design.md)（三层工具面 + token 自举 + 单实例 + 心跳/watchdog；含 9 条不变量 I1–I9、12 条验收判据 A1–A12）。
- **0.0.6 范围草案**（详见 spec §7）：① jar：`.bma3env` 生成 + 双 token 分离 + 单实例锁 + 无 token 模式；② 插件：删业务清单/`tokenFile`、preset 挂宽显窄、`bma3_status`（三态）/`bma3_connect`/`bma3_tools`（list/enable/disable/reset）；③ 联动：heartbeat 加 `tokenSource`/`tokenOk` + watchdog 两个探针；④ 门禁：`tools/list` 合规校验脚本。
- **未决（实现前需拍板，spec §8）**：Q1 `toolFace` 存 workspace 还是会话；Q2 分组靠前缀还是透传 `annotations`；Q3 是否提供 `--rotate-token`；Q4 无 token 模式下 watchdog 敏感动作是否降级；Q5 默认窄面是否包含 connector 的 `configure`/`reconnect`。
- **切片 1 已实现并真机验证（2026-11-14）**：jar 侧 `SingleInstanceLock` + `Bma3Env`（`.bma3env` 生成/幂等/0600/原子写）+ `AppConfig.Security`（`auth: token|none`、`envFile`）+ CLI `--no-auth`/`--rotate-token` + **MCP/Inbox token 分离** + 无鉴权模式（`Bma3McpServer`/`InboxHttpServer` 空 token 即跳过校验，回环仍硬约束）。
  - 门禁：`./mvnw -o test` **BUILD SUCCESS**（bma3-app 41 tests，含新增 `Bma3EnvTest` 6、`SingleInstanceLockTest` 3、`ConfigLoaderTest` +3）。
  - 真机 smoke（`/tmp/bma3-env-smoke`）：`.bma3env` 0600 双 token；重启后逐字节不变（幂等）；第二实例退出码 1 并报 pid；`--no-auth` 下 MCP `initialize` 无 Authorization 返回 200；`auth=token` 下 MCP 用 `INBOX_TOKEN` → 401、用 `MCP_TOKEN` → 200，Inbox 反之亦然（Token 分离成立）；`tools/list` 给出 **40 个工具**、公开名最长 37 字符。
  - 附带结论：DSH **不对 input schema 做子集校验**（只校验 output），故 jar 的 `minimum/maximum` 不构成阻塞（详见 spec §9）。
- **切片 2 已完成**：`PluginHeartbeat` 增 `tokenSource`/`tokenOk`（保留 8 参兼容构造）；`Probes.dshPlugin` 报 `TOKEN_MISMATCH`；**`security.auth=none` 时 watchdog 只观测不恢复**（D-064/Q4，启动打 `event=watchdog_remediation_disabled`）。测试：`PluginHeartbeatTest`(2)、`ProbesDshPluginTest`(4)。
- **切片 3 已完成（插件侧 P1/P2/P3）**：`self.js` 删除 `DEFAULT_TOOL_GROUPS`/`BASIC_BMA3_BASENAMES`/`SUBAGENT_BASENAMES`/`resolveToolGroups`/`LOCKED_TOOL_BASENAMES`，改为 **`toolFace` 规则**（`native`/`mcp-admin`/`mcp:<server>`/`prefix:<text>`/`all`/字面名）+ `expandToolFaceRules()`；策略改为按 **agent 视图**展开（修掉 0.0.5 把 preset 工具一起裁掉的缺陷）；`agentconfig.json` 升到 **v2**，v1 的 `defaultTools` 忽略并在 status 报 `legacyDefaultToolsIgnored`；`preset.js` 增加 `tool-web` 候选行，**仅当宿主有 `web` seam 时挂载**（否则整棵 preset 会 pending）。README/examples 同步。
  - 门禁：`dsh-plugin npm test` **48 pass**（原 45；新增 `self.test.mjs` 13 条含分层 harness）；`./mvnw -o test` **BUILD SUCCESS / 170 tests**。
- **切片 4–7 已完成**：新增 `lib/mcp-bridge.js`（`.bma3env` 解析 / TCP 端点探测 / 把 `@deepseek-ai/dsh-mcp-client` 挂到 **preset 作用域**）；self 新增 **`bma3_status`**（env/endpoint/mcp 三态，永不回显 token）、**`bma3_connect`**（connect/disconnect/status，无参即可）、**`bma3_tools`**（`list`/`enable`/`disable`/`reset`/`save`，规则驱动，只改本会话，`save` 才落 `agentconfig.json`）；preset 行把 `envFile`/`mcpUrl`/`mcpServerName` 透传给 self；**`inbox.js` 删除 `tokenFile`/`tokenEnv`/`token`**，改为只读 `.bma3env` 的 `INBOX_TOKEN`，无 token 时不发 `Authorization`，heartbeat 带 `tokenSource`/`tokenOk`。
  - 门禁：`dsh-plugin npm test` **60 pass**（新增 `mcp-bridge.test.mjs` 6 条 + self 6 条）；`./mvnw -o -q test` rc=0。
  - 真机 smoke（`/tmp/bma3-smoke2`，token 模式）：`.bma3env` 双 key；`probeEndpoint` 可达；插件客户端用 `envFile` 报 `tokenSource=env-file`；Inbox 用 `INBOX_TOKEN` 200 / 用 `MCP_TOKEN` 401；MCP `initialize`+`tools/list` 带 `MCP_TOKEN` 得到 40 工具。
- **X1 已完成**：`tools/e2e-local/dsh-tools-list-check.mjs`（名字契约 ≤64/字符集/重名 + schema 子集体检，`--strict` 才计入退出码）。实测 40 工具、名字问题 0、最长公开名 37/64、schema 提示 39 条（全是 `minimum`/`maximum`，DSH 不校验 input schema，故不阻塞）。
- **X3 大部分完成**：新增 `tools/e2e-local/bma3-connect-check.mjs`——用**真实** `@deepseek-ai/dsh-mcp-client` + `lib/mcp-bridge.js` 对真 jar 做 connect：**40 个 jar 工具以 `mcp__bma3__*` 注册成功、status 变 connected、disconnect 后清零（PASS）**。jar 侧 HTTP 冒烟（token 模式）也 PASS。
  - **进行中（用户选 A：在 web profile 临时启用）**：已备份 `~/.dsh/profiles/web/cordis.patch.yml.bak-x3-153245`，把 `bma3-inbox` 的 `tokenFile` 换成 `envFile: /tmp/bma3-session/.bma3env`（`autoStart:false`）、并启用 `bma3-preset`（带 `envFile`/`mcpUrl`）。宿主 pid 4232 未重启（dump-config 已确认行生效）。jar 在跑：`/tmp/bma3-session`，端口 18765/18766/18767，`auth=token`。
  - **待用户操作**：在 Web UI 新建会话并选 **BMA3** preset，发一条测试任务（`bma3_status` → `bma3_connect` → `bma3_tools enable mcp:bma3` → `mcp__bma3__inbox_list`）；之后读该会话日志核对，再回滚 profile 补丁并关 jar。
  - 原先的临时 headless 尝试结论（可复用）：headless 模板**没有 agentPresets 服务**（bma3 两行 pending）、`deepseek-official` 路由缺 API key、`dsh-tui` 因版本不兼容被跳过 —— 三条都排除了"不动 live 宿主"的路子。
- 未验证：`bma3_tools`/`bma3_status`/`bma3_connect` 尚无实现（切片 3–7）；`.bma3env` 的写失败降级路径 `NOT_RUN`。

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

**D-057 DSH 插件 `/inbox` 斜杠命令（2026-11-13 已实现）**：spec [`../superpowers/specs/2026-11-13-dsh-inbox-slash-command-design.md`](../superpowers/specs/2026-11-13-dsh-inbox-slash-command-design.md)。
- `/inbox [status|bind [sessionId]|unbind|detach|restore|pause|resume|start|stop|ack]`；无参数 = `status`。
- 动机：被推送唤醒的往往就是目标会话本身，让 Agent「帮我调 `bma3_inbox` 解绑」是自举死锁；命令面归人。
- 结果由 UI 直接渲染（`CommandResult.text`），**不进模型上下文**；`status` 只报 token 来源，不回显值。
- `unbind` 与工具语义一致（**只清绑定、不停 SSE**）；`detach` = 解绑 + 停 SSE，才是彻底脱手。
- **未改包级 `inject`**：`commands` 是共享的，加进去会连带 gate 掉 `preset`/`self`；改为运行时探测 `ctx.commands ?? ctx.get('commands')`，缺失则 warn 跳过。
- 已核对宿主 runtime 校验：`name` 需匹配 `/^[a-z][a-z0-9_-]*$/`、`description`/`handler`/`input.hint` 必有值、`definitionId` 可省 —— 单测里镜像了这套规则防回归。
- 命名不撞 DSH 内置（goal/plan/feedback/compact/permission/export）。
- `dsh-plugin npm test`：**28 → 42 pass**（新增 `test/inbox-command.test.mjs` 14 条，覆盖 A1–A11 + runtime 契约）。
- **真 registry 验证 PASS 8/8**（`tools/e2e-local/dsh-inbox-command-check.mjs`）：用**宿主自己的** `@deepseek-ai/dsh-commands` + 真 Cordis `Context` 装载命令服务，然后
  `registerInboxCommand` 通过真 `normalizeDefinition`；`commands.find(agent,'inbox')` 能解析；`commands.list()` 含 `inbox`；
  真 `parseCommand('/inbox status')` → `{name:"inbox", rawInput:" status"}`；真 handler 渲染出 status 文本；`disposer()` 注销后 `find` 返回 undefined。
- **未验**：真实 DSH Web 宿主进程内敲 `/inbox` 的**实际 UI 渲染**（宿主需重启/重载；本会话就挂在那个宿主上，不能自杀式重启）；`NOT_RUN`。

**0.0.5-hotfix1 上线即炸 → 0.0.5-hotfix2 修复（2026-11-13，远程实测报错）**
远程宿主报 `Error: cannot get property "commands" without inject`（`lib/inbox.js:705` `registerInboxCommand` → `apply`）。
- **根因**：Cordis 的 context proxy 对未 `inject` 的属性**抛异常**而不是返回 `undefined`，所以 `ctx.commands ?? ctx.get('commands')` 的兜底永远走不到；异常逃出 `apply()`，**整个 inbox 模块（含 `bma3_inbox` 工具与 SSE）都没挂上**。
- **修复**：改用 `ctx.inject(['commands'], child => child.effect(() => child.commands.register(def)))` 可选依赖；注册全程 try/catch，任何异常只 warn、绝不让 `apply()` 失败。
- **测试为什么没抓到**：原 stub 里 `ctx.commands` 缺省返回 `undefined`，与真实 Cordis 语义不符 —— **桩错了，测试就是在验证幻觉**。已把 stub 改为"未注入属性访问抛异常"，并新增 2 条回归（Cordis 式抛异常的 ctx / `ctx.inject` 自身抛异常）。真 registry 脚本也改成在**没 inject `commands` 的 plugin fiber 里**注册，直接复现原错误。
- 门禁：`dsh-plugin npm test` **44 pass**；真 registry **8/8**；Java 145 tests / 0 fail / 1 skip；packaging smoke PASS。
- 版本统一 `0.0.5-hotfix2`。**未发布、未提交**（等授权）。

**同批修掉的第二个回归（更早存在，破坏力更大）：插件重载会把 inbox 永久停掉**
- `persist()` 落盘的是**引擎变量** `stopped`，不是用户意图 `state.stopped`。拆插件时 `stop(false)` 只改引擎、不改意图，却把 `true` 落盘 → 下次加载 `createState` 当成「用户点了 stop」→ `apply()` 的 `restore()`/`start()` 两个分支都被 `!initial.stopped` 挡住 → **不恢复绑定、不启 SSE**，且看起来像用户自己的选择。
- 后果：任何插件卸载/热重载（换版本、改文件）都会静默停摆，**重启 DSH 也救不回来**——远程报告的「重启后 autoStart 会自动恢复」因此不成立。
- 修复：`persist()` 改为 `stopped: state.stopped`。回归测试 `unloading the plugin must not persist the user-stop intent`（拆掉后仍 `false` / 重载自启 / **显式 stop 过则不自启**）。
- **运维后果**：被旧版写坏的状态文件在升级后仍被尊重为「用户意图」，升级后必须手改一次 `"stopped": false` 或敲 `/inbox start`。
- 另核实：`bma3_self_reload` 确实只做 config/prompt/toolPolicy/repairHostTools，**不碰 Cordis 插件行**，救不了死掉的 inbox 行（报告该项准确）。
- 门禁：`dsh-plugin npm test` **45 pass**；真 registry **8/8**；Java 145 tests / 0 fail / 1 skip；packaging smoke PASS。

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
- 版本号：`0.0.5-hotfix2`（pom ×10 / `Main.VERSION` / MCP serverVersion / dsh-plugin package.json / packaging/smoke.sh 一致）。
- 发布：**v0.0.5-hotfix2**（2026-11-13，用户「发0.0.5-hotfix2」授权）。
  - Release：<https://github.com/c1589190/BotMosire-Alpha3/releases/tag/v0.0.5-hotfix2>（Pre-release）
  - tag `v0.0.5-hotfix2` → commit `d5cc369`
  - 内容：修 hotfix1 的 `ctx.commands` 抛异常（整个 inbox 模块没挂上）+ 更早存在的「重载即自停」持久化 bug
  - assets：`bma3-app-0.0.5-hotfix2.jar`（sha256 `7abd8433…`）、`mosire-bma3-dsh-0.0.5-hotfix2.tgz`（sha256 `49f03d79…`）、`SHA256SUMS`
  - 回下载校验 PASS：`sha256sum -c` OK；jar/tgz 版本均 `0.0.5-hotfix2`；包内 `ctx.inject(['commands']` 存在、旧写法 `ctx?.commands` **0 处**
- 上一版：**v0.0.5-hotfix1**（2026-11-13，commit `9e74a90`，用户「发布为0.0.5-hotfix1」授权）——**有致命回归，勿用**。
  - Release：<https://github.com/c1589190/BotMosire-Alpha3/releases/tag/v0.0.5-hotfix1>（Pre-release）
  - tag `v0.0.5-hotfix1` → commit `9e74a90`
  - 内容：DSH 插件新增 `/inbox` 斜杠命令（D-057），其余与 `0.0.5-early` 相同
  - assets：`bma3-app-0.0.5-hotfix1.jar`（sha256 `0e260088…`）、`mosire-bma3-dsh-0.0.5-hotfix1.tgz`（sha256 `377674da…`）、`SHA256SUMS`
  - 回下载校验 PASS：`sha256sum -c` OK；jar `--version` = `0.0.5-hotfix1`；tgz `package.json` = `0.0.5-hotfix1` 且 `lib/inbox.js` 含 `registerInboxCommand`
- 上一版：**v0.0.5-early**（2026-11-13，commit `53e9d86`，用户「可以正式发布0.0.5-early版本了」授权）。
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

**0.0.5-hotfix2 发布门禁（2026-11-13，commit `d5cc369`）**

| 检查 | 命令 | 结果 |
|---|---|---|
| DSH 插件 | `dsh-plugin && npm test` | **PASS**：45 tests |
| 真 registry | `node tools/e2e-local/dsh-inbox-command-check.mjs` | **PASS 8/8**（含「在没 inject commands 的 fiber 里注册」） |
| Java 全量 | `./mvnw -o test` | **PASS**：145 tests / 0 fail / 1 skip |
| 打包 smoke | `bash packaging/smoke.sh` | **PASS** |
| 资产回下载 | `gh release download v0.0.5-hotfix2` + `sha256sum -c` | **PASS** |
| 真实 DSH 宿主里敲 `/inbox` 的 UI 渲染 | — | `NOT_RUN` |

**0.0.5-hotfix1 发布门禁（2026-11-13，commit `9e74a90`）——该版有致命回归，仅存档**

| 检查 | 命令 | 结果 |
|---|---|---|
| DSH 插件 | `dsh-plugin && npm test` | **PASS**：42 tests（+`/inbox` 命令 14 条） |
| 真 registry | `node tools/e2e-local/dsh-inbox-command-check.mjs` | **PASS 8/8**（真 `dsh-commands` + 真 Cordis Context） |
| Java 全量 | `./mvnw -o test` | **PASS**：145 tests / 0 fail / 1 skip |
| 打包 smoke | `bash packaging/smoke.sh` | **PASS** |
| 资产回下载 | `gh release download v0.0.5-hotfix1` + `sha256sum -c` | **PASS** |
| 真实 DSH 宿主里敲 `/inbox` 的 UI 渲染 | — | `NOT_RUN`（宿主需重启，开发会话就挂在上面） |

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

## 0.0.6 收窄：删除工具面耦合层（2026-11-14，D-066）

- **删除范围（代码）**：`lib/self.js`（949 行）、`lib/self-entry.js`、`lib/mcp-bridge.js`、`lib/host-tools.js`、`examples/agentconfig.json`，以及 `self/host-tools/mcp-bridge` 三个测试文件；`index.js` 的 `MODULES` 只剩 `inbox`/`preset`；`package.json` 去掉 `./self` 导出、版本升 `0.0.6`。
- **保留并重写**：`lib/persona.js`（新，预设唯一一行）+ `lib/preset.js`（缩到 90 行，只注册定义）+ `cordis.patch.yml`（两行）。
- **预设现在的行为**：在**预设作用域**注册一个遮蔽 `deployment:persona-prefix` 的 prompt section（`systemPrompt.section()` 按调用方 scope 落层，agent 被 parent 到该 generation scope 即可见 —— 所以**不需要任何 per-agent 钩子**）；文本取 `$DSH_HOME/bma3/systemprompt.md` 或行配置 `promptFile`；每次组装重读、读取失败保留上一份非空文本、缺失或为空则**不挂任何 section**。
- **行为取舍（需用户知悉）**：旧实现按 `<cwd>/systemprompt.md` 取**工作区人设**；新实现是**单一固定路径**，因为按 workspace 取文必须在 `agent.ctx` 里逐 agent 注册 section，而那正是被删掉的 per-agent 耦合。要恢复工作区人设需要重新引入 per-agent 挂载。
- **门禁**：`dsh-plugin npm test` **40 pass / rc=0**（62 → 40：删掉 22 条工具面/桥接测试，新增 `persona.test.mjs` 8 条 + 重写 `preset.test.mjs` 5 条 + `manifest.test.mjs` 回归）。
- **验收判据 ①（jar MCP 可达）PASS**：真实 `curl` 对 `http://127.0.0.1:18765/mcp` 做 Streamable HTTP + `Authorization: Bearer $MCP_TOKEN` → `initialize` **200**（`serverInfo=bma3/0.0.5-hotfix2`，`Mcp-Session-Id` 返回），同一 session `tools/list` → **40 tools**。即：未经过任何插件代码的标准 MCP 客户端可以直接连上。
- **验收判据 ②（inbox 注入）PASS（既有证据）**：`bma3_inbox` 工具在真实会话里可用，`status` 返回 `tokenSource=env-file`、`baseUrl=127.0.0.1:18766`、`agentLive=true`。
- **`NOT_RUN`**：Agent 用 `mcp_connector_configure` 真连一次（本机凭据写入属于用户环境变更，留给用户/Agent 执行）；`BMA3` 预设在真实会话里注入 system prompt 的实机效果（需要一次真实会话，宿主必须加载 0.0.6 代码）。
- **过程教训（记入维护）**：三次"还是没有"的冒烟全部落在 **pid 4232 的旧宿主**（`session.lock` 被其持有），我改的进程（:3090）从未收到任何 agent —— 真实宿主验证前必须先确认会话归谁所有。

## 0.0.6 工具面定稿：挂载即暴露 + 官方 mcp-client 验证（2026-11-14，D-067）

- **删掉组别白名单**：`defaultTools` 组名 / `resolveToolGroups` / `tools.restrict({allow})` 全不再有。理由：它依赖别的插件先把名字注册进全局层（0.0.5 里业务工具出不出现取决于 profile 里有没有别的手配 mcp-client），且 allow 从**全局视图**算 ⇒ 预设自己挂的 `bash`/`read_image`/`subagent` 被同一次 restrict 裁掉。
- **预设改为薄面 6 行**：`persona` + `tool-bash`/`tool-pwsh` + `tool-fs`（仅 `readImage`）+ `tool-subagent-control` + `tool-subagent`（`agentPreset: minimal`）+ `tool-plugin-manager`（宿主有 `profileContext` 才挂、否则 disabled）。挂什么就是什么，无策略层。
- **Agent 三条扩展路径**：MCP server → `mcp_connector_configure`（工具落全局层，`mcp__<server>__*`）；本地插件行 → `tool-plugin-manager`；按会话收窄 → `mcp_connector_session_tools`（其实现就是 `agent.ctx.tools.restrict({deny})`，与"无 allow 层"不冲突）。
- **验收判据 ①（jar 支持 DSH 官方 mcp-client）PASS —— 真机实测**：`mcp_connector_configure(name=bma3, url=http://127.0.0.1:18765/mcp, transport=streamable-http, authMode=bearer, bearerToken=$MCP_TOKEN)` → 返回「已配置并连接」；`mcp_connector_status` 显示 `manual/bearer/streamable-http · 已连接 · 始终注入 · 全局`，诊断 `ok`；40 个工具以 `mcp__bma3__*` 注册进会话；真实调用 `mcp__bma3__inbox_status` → `{"available":0,"pendingPush":0,"panelRevision":0,…}`。验证后已 `mcp_connector_disconnect` 断开（连的是 `build/e2e` 测试 jar，不留常驻）。
- **门禁**：`dsh-plugin npm test` **41 pass**（preset 定义 7 行断言 + plugin-manager seam 开关 + `ctx.inject` 等待回归仍保留）。
- **`NOT_RUN`**：薄面 6 行在真实 BMA3 会话里的实际可见工具数（需要一次真实会话，宿主须加载本次代码）。

## 媒体能力第一批：OCR + ASR（2026-11-14，D-068）

- **插件新增预设行 `lib/media.js`**，注册两个工具（只在 BMA3 会话出现，不进 profile 全局层）：
  - `bma3_ocr(filePath, purpose?)` → `POST https://api.deepseek.com/v1/tools/ocr`；`purpose` 取 `text_recognition`（默认）/`rich_layout_recognition`；按页合并文本，返回 `pageCount`/`pages[].finishReason`/`usage`。
  - `bma3_asr(filePath)` → 先用 ffmpeg 转 16 kHz 单声道 16 位 PCM WAV（mp3/amr/ogg/opus/wav 都能进），再 `POST https://api.deepseek.com/v1/tools/asr`；`>120 s` 或 `>8 MiB` 直接拒。
  - 两个工具都从 DSH `credentials` 取 `DEEPSEEK_API_KEY`（缺省退回同名环境变量）；缺 key 时返回结构化错误、不发起请求、不回显密钥。
- **jar 侧只需补扩展名**：`record` 语音原本就在 `QqMediaSupport.MEDIA_TYPES` 里、`qq_media_view` 已经下载到 downloads（用户判断正确），但 `extension()` 没有音频分支，语音一律落成 `.bin`。已补 `audio/*` → mp3/amr/wav/ogg/silk/m4a，新增用例 `aVoiceMessageIsWrittenWithAnAudioExtension`。
- **TTS 不做**（用户裁定）。
- **门禁**：`dsh-plugin npm test` **56 pass**（新增 `media.test.mjs` 11 条）；`./mvnw -o -q -pl bma3-qqconnect -am test` **51 tests / 0 failures**。
- **真机验收（真实 Key、真实计费）**：
  - ASR：2 秒 16 kHz 单声道 WAV（440 Hz 正弦）→ `HTTP 200`，`{"request_id":"05bf1e22-…","text":"","duration_ms":2000}` —— 鉴权、multipart、格式判定、时长回显全通（纯音无语音，text 为空属预期）。
  - OCR：640×200 PNG（渲染 `BMA3 OCR SMOKE 2026`）→ `HTTP 200`，`page_count=1`，`text="BMA3 OCR SMOKE 2026\n\n00000000"` —— 识别链路通；中文行变 `00000000` 是 PIL 默认字体画不出 CJK（图片本身没有字形），不是接口问题。
- **未验**：`rich_layout_recognition`（会带 `<\|ref\|>`/`<\|box\|>` 标记与坐标）；真实 QQ 语音样本的实际编码（SILK 需第三方解码器，本机 ffmpeg 6.1 只有 amr/opus/mp3）；LLBot 是否已把语音转 mp3 —— 需一条真实语音消息 `NOT_RUN`。

## 0.0.6 发布（2026-11-14）

- **tag** `v0.0.6` → `8c5da07`；GitHub **Pre-release** 已发布：<https://github.com/c1589190/BotMosire-Alpha3/releases/tag/v0.0.6>（按仓库惯例先 Pre-release）。
- **资产**：`botmosire-a3-linux-x86_64.tar.gz`（88,742,446 字节），sha256 `df46c51a6b9987d632d4d231e028fc6be49cb244935c33473916346b2f5ec9ec`。
- **门禁**：Java **172 tests / 0 failures / 1 skipped**；`dsh-plugin npm test` **56 pass**；`packaging/smoke.sh` **PASS**（app-image 启动 + `/healthz` 200）。
- **破坏性**：token 载体从环境变量 `server.tokenEnv` 改为配置目录的 `.bma3env`（`MCP_TOKEN` / `INBOX_TOKEN` 分离）⇒ **jar 与插件必须成对升级**，否则一侧拿不到 token → 401。插件侧同时删除 `bma3_status`/`bma3_connect`/`bma3_tools`/`bma3_self_*`，`agentconfig.json` 与 `<cwd>/systemprompt.md` 失效，inbox 只认 `envFile`。
- **发布内容**：jar = token 自举 + 单实例锁 + `security.auth` + 心跳/探针字段 + 媒体按魔数定扩展名；插件 = 删工具面耦合层（D-066/D-067）+ 人设预设 + `bma3_ocr`/`bma3_asr`（D-068）；连接方式改为 DSH 原生 `@deepseek-ai/dsh-mcp-client`（README 有可直接粘贴的 profile 行）。
- **未验（随版本带走）**：真实 QQ 语音的识别质量（1.34 s AMR-NB 样本返回空文本，8k/16k 直传 API 皆空）；`rich_layout_recognition` 的标记/坐标格式；`--no-auth` 下的 watchdog 只观测行为只在 fake 上验过。
