# 0.0.7-hotfix2 新功能调查与落点

> **类型**：调查（时间点快照，只作追溯，不作当前事实）
> **日期**：2026-11-16 21:00（UTC+8）
> **方法**：只读代码与设计文档；只读聚合本机 `run/bma3-live/data/bot.db`（不读取正文）；查阅公开 OneBot/LLBot 文档。**未改产品代码、未跑构建/测试、未连接真实 QQ 或远端 DSH。**
> **触发**：用户要求先调查、定位，并把下列能力列入 `0.0.7-hotfix2` 新功能。
> **当前状态入口**：[`../agents/STATUS.md`](../../agents/STATUS.md)

## 0. 用户需求原话

> 1 首先，可配置的硬秒数等待固然非常不错，需要加上和完善
>
> 2 其次，为什么不能让Agent自己决定是否等待呢？有些消息的语义似乎不完善，那么Agent完全可以选择先继续等待，可以用工具+来源传入实现消息监听、如果超过一定秒数没有新消息就要求Agent正式回复或者干其他的
>
> 3 不做这个
>
> 列入代办项
>
> 我并不建议你只挑两个做
>
> b站适配事实上已经通过xvfb+图片传入+computer use曲线救国了，就是确实需要额外的封装和易用性优化，这一点你可以调查一下
>
> web管理……我认为先把http watchdog做好就万事大吉了，包括媒体诊断包，watchdog必须提供完善的诊断功能
>
> 3 暂时没有支持计划，不过我拉了一个朋友来我github项目组，也许我的朋友会有好贡献
>
> 媒体流水线可以实现吗？面对没有ORC功能的模型怎么办？适配要做好
>
> xvfb事实上Agent非常喜欢使用，非常建议出进一步的易用性优化
>
> QQ其他真实功能涉及Onebot/其他协议适配，你可以了解一下，我认为不应该吝啬多配置
>
> 先调查、定位吧，将作为0.0.7-hotfix2的新功能
>
> 哦对，其实QQ功能里的操作好友等也没适配，我的建议是尽可能全面、通用

**解释边界**：其中“3 不做这个”按上一轮上下文解释为**不做语义猜测/标点判断式“智能”**；“3 暂时没有支持计划”按上一轮编号解释为**更多平台适配器（如微信等）暂无支持计划**，朋友可能后续贡献；QQ 空间仍只是远端提过的独立候选，不等于本批已立项。两者均应写回设计文档确认，避免只靠会话记忆。

**追加方向（D-069，2026-11-16 用户原话）**：

> 硬等待是私聊群聊等消息来源都可用的，因为大部分即时聊天软件都有这个问题；具体看Agent意愿，Agent想怎么配怎么配，想怎么等怎么等，这可以吗？

据此，本报告中的 S1 **不再限定 QQ 私聊/群聊**：所有 Inbox source 都进入同一套“来源前缀 → quiet/max”规则，Agent 通过 `set_config` 或运行时工具自行选择；未配置来源保持中性。默认值仍需 spec 明确，不能把调度/看门狗/调试来源无意延迟。

## 1. 结论速览

**这是至少六个独立责任区，不是一个可以直接塞进 hotfix 的小补丁。** 推荐拆成：

| 切片 | 内容 | 主要风险 |
|---|---|---|
| S1 | Inbox 硬静默窗口 + Agent 自主 `wait_quiet` 工具 | 批次 flush 语义、群聊/发送者粒度、Agent 是否会用 |
| S2 | 面板摘要模式 + 可展开完整内容 | Java panel 与 DSH 插件渲染必须同步改 |
| S3 | watchdog HTTP 诊断面 + 媒体诊断包 | 不能泄露正文/路径/token；现有探针没有媒体统计 |
| S4 | xvfb/B 站 Computer Use 易用性 | 不改变“浏览器不单独成线”的方向，只做封装 |
| S5 | 媒体流水线与无视觉模型适配 | jar 与插件的 OCR/ASR 责任边界 |
| S6 | QQ 好友/群/资料等全面动作 + provider 通用化 | 不能绕过消息板发送；不能假装支持未实现的协议 |

**建议实现顺序**：S1 → S2 → S3 → S4 → S5 → S6。S1/S2 与现有 Inbox 状态机强相关，先定；S3 是排障前置；S4/S5/S6 可以并行拆责任区，但必须各自有 spec。

**当前基线问题**：工作树在 `test/inbox-binding-tools-20261116`，HEAD `48b653d`，test2 尚未合回正式分支 `feat/bma3-plan-1`。hotfix2 开工前必须先决定从哪条基线拉分支，否则会把测试包和正式修复继续叠在一起。

## 2. 已确认事实

### 2.1 Inbox 当前已经有“同源重置”，但窗口太短

`InboxService.afterAppend()` 每次同源新消息都会把该条目的 `pendingSince` 重置为当前时间；`pendingFirstSeen` 只保留第一次。

- 同源新消息重置：`bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/InboxService.java:851`
- 推送延迟计算：`InboxService.java:645`
- 即时/普通延迟：`DeliveryPolicy.defaults()` 为 `importantDelaySeconds=3`、`normalDelaySeconds=60`
- 首次推送硬上限：`maxFirstPushDelaySeconds=60`，见 `InboxService.java:669`
- 推送后条目冻结：`flushPending()`，`InboxService.java:687`
- 冻结后同源新消息另开条目：`appendOrMerge()`，`InboxService.java:128`

所以当前行为是：

- 连续消息间隔小于 3 秒时，会合并到同一 item，问题较小；
- 间隔大于 3 秒时，第一条先被冻结并推送，第二条另起 item；
- DSH 插件只会对 `status === 'idle'` 的 Agent 调 `followup`，**不会打断正在生成的回合**：
  - `dsh-plugin/lib/inbox.js:986`
  - `dsh-plugin/lib/inbox.js:2102`
- 因此第二条消息到达时，Agent 往往已经在针对第一条回复；它只能等本轮结束再看到第二条。

**这是“回复太早”的主因，不是 SSE 丢失或 ACK 问题。**

### 2.2 面板摘要现状

当前 `Inbox.Display` 只有：

- `maxMessagesPerItem`
- `maxItemsPerPanel`
- `includeOmittedCount`
- `maxCharsPerItem`

见 `bma3-app/src/main/java/io/mosire/bma3/app/config/AppConfig.java:654`。

已有两个可展开/压缩路径：

- `inbox_items` 返回所有未 ACK 条目的一行预览，不含正文；
- `inbox_get` 按 `itemId` 返回完整 bounded entries。

但 `panel()` 目前仍渲染每个 item 的 entries；DSH 插件 `renderPanel()` 也依赖 `item.entries`。若直接让 Java 返回空 entries，插件注入会变成空文本。因此摘要模式必须是 Java panel + DSH 插件同批改动。

### 2.3 watchdog / HTTP 诊断现状

- `/watchdog` 在 `HealthServer.java:89`，只返回 `WatchdogSnapshot`；
- `/debug/snapshot` 在 `DebugApi`，包含版本、进程、watchdog、Inbox、少量文件大小；
- watchdog 现有探针：process/disk/db/directory/inboxHttp/inboxBacklog/mcp/qqAccount/qqZombie/dshPlugin/webconnect/xvfb/schedule/config；
- `ProbeResult.facts` 明确要求“不含 token、URL、正文、文件路径”；
- **没有媒体探针、媒体计数、媒体诊断包或统一导出入口。**

### 2.4 媒体现状

已有零件：

- `qq_media_view`：按 fileId 下载图片/文件/语音/视频；图片自动切长图；文本可内联；见 `bma3-qqconnect/.../QqMediaViewTool.java:47`；
- `bma3_ocr`：插件侧 DeepSeek 官方 OCR，支持 PDF/Office/图片，按页；
- `bma3_asr`：插件侧 DeepSeek OCR/ASR，ffmpeg 转 16k 单声道 WAV，上限 120 秒/8 MiB；
- `bma3_ocr` / `bma3_asr` 在 `dsh-plugin/lib/media.js`，key 从 DSH `credentials` 的 `DEEPSEEK_API_KEY` 取。

**缺口**：没有“fileId → 下载 → 切图 → OCR/ASR → 文本结论”的统一工具；也没有在模型无视觉能力或 OCR key 缺失时的明确 fallback 结果。

### 2.5 xvfb 现状

当前 `bma3-xvfb` 有 12 个工具：

- `xvfb_status`
- `xvfb_workspace_open/close/list`
- `xvfb_launch`
- `xvfb_screenshot`
- `xvfb_windows`
- `xvfb_exec`
- `xvfb_key/type/act`
- `xvfb_tree`（v1 返回 `ATSPI_UNSUPPORTED`）

已具备的真机能力（历史证据，非本轮重跑）：

- 工作区、Openbox、Chromium、截图、窗口列表、点击、中文剪贴板输入、按键；
- 截图同时产出 downloads 副本，可交给发送工具；
- 长图切段、OCR/ASR 工具已存在。

易用性问题：

- 一次“打开 B 站并看一眼”需要 `workspace_open → launch → wait → windows → screenshot → read_image` 多步；
- 没有 `wait`/`wait_for_window`，Agent 只能靠猜或反复截图；
- `xvfb_launch` 只返回 pid，不返回窗口 id 或首张截图；
- `xvfb_windows` 会带出 Openbox 辅助窗口，需要 Agent 自己过滤；
- 没有浏览器专用快捷入口（默认 Chromium 参数、URL、profile 复用）；
- 没有把截图/OCR 结果直接整理成文本的便利路径；
- `xvfb_tree` 未实现，无法用无障碍树定位控件。

### 2.6 QQ 动作与好友能力现状

`QqToolSupport.all()` 当前约 28 个 QQ 工具，但真正直接发 OneBot action 的种类很少：

- `get_friend_list`（只读好友列表，已有）
- `get_group_list` / `get_group_info` / `get_group_member_list` / `get_group_member_info`
- `get_group_system_msg` / `set_group_add_request`
- `get_msg` / `get_image` / `get_forward_msg` / `delete_msg`
- 发送相关走 `QqSendSupport` / `QqDelivery`，不是裸 action。

**好友操作只覆盖“列表”**；好友申请、删除、备注、点赞、戳一戳、资料、头像、陌生人信息等都没有。群操作也只覆盖一部分。

`AccountConfig` 当前只有 `accountId / endpoint / token / enabled`，见 `bma3-qqconnect/.../AccountConfig.java`；没有 protocol/provider 字段。`QqGroupInviteAcceptTool` 直接硬编码 LLBot 扩展 `llonebot_debug`（`QqGroupInviteAcceptTool.java:157`），说明 provider 差异已经开始泄漏到工具层。

## 3. 功能定位与方案候选

### 3.1 S1：可配置硬静默窗口

**目标**：同一来源连续消息在静默满 `quietSeconds` 前不推送；有 `maxWaitSeconds` 硬上限，避免无限等待；配置热重载；不能影响其他来源的紧急消息。

建议配置形状（草案，不是最终裁定）：

```yaml
inbox:
  wait:
    enabled: true
    defaultQuietSeconds: 0       # 未配置来源保持现有行为
    defaultMaxWaitSeconds: 20
    # 来源前缀 → 该来源的等待策略；更具体者胜（与 weighting.sourcePriority 同语义）
    sources:
      - source: qq_private
        quietSeconds: 8
        maxWaitSeconds: 20
      - source: qq_group
        quietSeconds: 4
        maxWaitSeconds: 15
      - source: qq_group/955432692
        quietSeconds: 8
        maxWaitSeconds: 20
      - source: bma3_debug
        quietSeconds: 0
        maxWaitSeconds: 5
```

语义：

- 所有 Inbox source 都可配置，不写死 QQ 私聊/群聊；`bma3_debug`、调度、看门狗等内部来源也在同一张规则表里；
- Agent 通过 `set_config` 或运行时等待工具自行选择“等多久/是否等”；
- 同一 item/来源有新消息时，重置该 item 的 quiet 计时；
- `due = max(原延迟到期时间, quietUntil)`，但仍受 `maxWaitSeconds` 约束；
- 如果另一个来源的紧急消息先到，`flushPending()` 不能把仍在 quiet 等待的 item 一起冻结；
- 配置变化后下一次 `nextDue()` 重新计算，已存在 item 也按新值生效；
- 诊断面显示生效规则、`waitingForQuiet`、`quietUntil`、`hardDueAt`、最后消息时间。

**粒度取舍**：

- 硬等待的调度粒度先按 `source`；私聊天然等于同一个人，群聊是整个来源；
- 若群聊要“只等同一个人”，需要额外携带稳定 `senderId`/waitKey，并处理“同一 group item 里混合多个发送者”的语义；这是 S1 的候选增强，不在第一版默认承诺；
- 默认值建议保持中性（未配置来源 0 秒），Agent 可按来源显式开启；是否给 `qq_*` 一个保守出厂默认，留给 spec 拍板。

**为什么不能只加一个字段**：现有 `flushPending()` 是“任意 item 到点就冻结整个窗口内所有 pending”。如果 quiet item 和其他 due item 同时在窗口里，它会被顺带冻结，等于静默窗口没生效。必须改成“只冻结自己已到期的 item，quiet 中的 item 排除”。

### 3.2 S1b：Agent 自主等待工具

**目标**：Agent 收到第一条消息后，如果判断语义未完，可以主动调用一个工具，按来源继续监听；超过指定静默时间没有新消息，返回“已稳定，可以正式回复”；若持续有新消息，达到硬上限后返回“仍在活动，你需要决定继续等还是现在回复”。

建议工具名：`inbox_wait_quiet`（不要复用现有 `inbox_wait`，后者是“等下一次任意 push”，语义不同）。默认 `quietMs=2000`（用户 D-070），支持任意 source/sourcePrefix。

建议参数：

```json
{
  "source": "qq_private/account/12345",
  "quietMs": 2000,
  "maxWaitMs": 20000,
  "includeEntries": true
}
```

返回：

```json
{
  "settled": true,
  "source": "qq_private/account/12345",
  "quietForMs": 8123,
  "newMessages": 2,
  "items": [],
  "hint": "静默窗口已满足；可以正式回复，或继续处理其他事"
}
```

若到 `maxWaitMs` 仍持续有新消息：

```json
{
  "settled": false,
  "reason": "max_wait_reached",
  "stillActive": true,
  "hint": "对方仍在连续发送；你可以再等一轮，或现在给出阶段性回复"
}
```

边界：

- 只读，不 ACK、不冻结、不创建 item；
- `source` 必填；是否支持 `sourcePrefix`/多个来源待定；
- 超时上限建议 60–120 秒，避免 MCP 请求无限占用；
- 不能在工具里“替 Agent 决定回复”，只能返回 `settled`/`stillActive` 和明确 next action；
- 服务端实现可复用 `InboxService` 的 `wait/notifyAll`，但需要新增“按 source 等 quiet”的条件，不是简单 `awaitPush`。

**为什么这个工具值得做**：硬窗口是“防呆”，Agent 工具是“按语义自主决定”。两者互补，不能只做其中一个。

### 3.3 S2：面板摘要模式

建议不要直接删除 entries，而是增加一个明确的摘要视图：

```yaml
inbox:
  display:
    mode: full        # full | summary | auto
    summaryMaxCharsPerItem: 200
    summaryAboveItems: 8
```

- `full`：现有行为；
- `summary`：每个 item 一行预览（来源、优先级、totalMessages、最后 sender/时间、截断后的最后文本）；
- `auto`：只在 visible items 超过阈值时切摘要，避免日常小面板退化；
- 完整内容仍由 `inbox_get(itemId)` 获取；
- 可将 panel index 也作为 `inbox_get` 的可选寻址方式，减少 Agent 找 itemId 的成本；
- **不要引入总字符硬上限**，除非用户另行裁定；D-044 明确说过“不设总字符上限，只设单条字符上限 + 总条目显示上限”；
- Java `PanelItem` 与 `dsh-plugin/lib/inbox.js` 的 `renderPanel()` 必须同步改，否则摘要模式会注入空白。

建议首版默认 `auto`，阈值 8：少于 8 条保持完整，超过 8 条自动摘要。

### 3.4 S3：watchdog HTTP 诊断面 + 媒体诊断包

当前 `/watchdog` 只有 snapshot；建议新增只读端点：

```text
GET /watchdog/diagnostics
GET /watchdog/diagnostics?format=json|tar
```

以及对应 MCP 工具：

```text
bma3_watchdog_diagnose
```

建议内容（全部 content-free）：

- jar 版本、pid、启动时间、运行时长、auth mode；
- watchdog 组件、facts、最近事件、probe/remediation/alert 计数；
- config reload 次数、最后错误（不含明文配置值）；
- Inbox status、heartbeat diagnostics、插件 toolRegistration/deferredPush 摘要；
- DB 文件大小、`PRAGMA quick_check`（只读、限时）；
- 媒体统计：下载目录文件数/总字节、按 image/audio/file/video 分类、最近 OCR/ASR 成功/失败次数、最后错误码、平均耗时；
- xvfb 状态：supported、当前 workspace 数、截图/进程数（不返回绝对路径）；
- QQ provider 能力：协议、检测到的 provider、可用/不可用 action 列表摘要。

媒体诊断包的安全边界：

- 不打包正文、图片、附件、数据库、原始日志或完整配置；
- 不返回 token/Authorization/Cookie/绝对文件路径；
- 只记录 ID/状态/计数/错误码/耗时；
- `tar` 导出属于可选增强，默认先做 JSON；导出前允许调用方查看内容；
- HTTP 仍只绑回环；远端访问继续走显式代理/DEBUG 面，不把 watchdog 暴露成公网无鉴权接口。

媒体统计需要新 instrumentation：当前 `QqMediaSupport` 没有计数器，插件 OCR/ASR 也没有把调用结果回传 jar。最低成本是先在 jar 侧记录下载/切图计数，在插件 heartbeat diagnostics 里加 OCR/ASR 可用性与最近结果；若要完整统计，需要新增进程级 counters 或把插件调用结果写回 Inbox 诊断。

### 3.5 S4：xvfb / B 站 Computer Use 易用性

用户方向已经改写原 Plan 3：B 站不是先做专用 adapter，而是用 **xvfb + 截图/read_image + computer use** 曲线实现。因此本切片只做“桌面封装易用性”，不要重新造 B 站 CLI。

建议新增/增强：

1. `xvfb_workspace_open` 支持可选 `launch`/`url`，一次完成“建工作区 + 启动 Chromium + 返回首屏”；
2. 新增 `xvfb_wait`：
   - `for=window|title|time|file`
   - 超时和轮询间隔有上限；
   - 不依赖 Agent 猜 sleep；
3. 新增 `xvfb_open_browser` / `xvfb_browser`：
   - 默认 Chromium 二进制、`--no-sandbox`、`--user-data-dir` 放工作区 home；
   - 接受 URL，返回 workspace/display/window/screenshot；
   - profile 可复用，便于 B 站登录态保持；
4. `xvfb_windows` 可选过滤 Openbox 辅助窗口（`includeHelpers=false` 默认）；
5. `xvfb_screenshot` 可选 `ocr=true`/返回文本摘要，或提供统一的 `xvfb_ocr`：
   - 有视觉模型：截图路径 + `read_image`；
   - 无视觉模型：截图后直接 OCR 成文本；
6. `xvfb_workspace_list` 增加 age、running、lastUsed、screenshot 数、是否可复用的明确字段；
7. `xvfb_workspace_close` 的 `keep` 默认值和清理策略要重新评估，避免误删登录态；
8. 可选：`xvfb_tree` 的 AT-SPI 留到 P1，不阻塞本切片。

验收场景：

- 一次调用启动 Chromium 并打开 B 站；
- 等待窗口出现，不靠固定 sleep；
- 截图路径可直接交给 `read_image` 或 OCR；
- 无视觉模型得到 OCR 文本而不是只有图片路径；
- 复用同一 workspace 时登录态不丢；
- 清理后进程和目录状态一致。

### 3.6 S5：媒体流水线与无视觉模型适配

先明确责任边界：

- **jar** 负责：按 fileId 取回、刷新 URL、下载、魔数识别、长图切段、落盘；
- **插件** 负责：显式 OCR/ASR 的远程 API 调用（DeepSeek key 只在插件 credentials）；
- **Agent** 负责：决定是否 OCR/ASR、是否把结果写回对话、是否继续读原图。

可选实现：

**A. Agent 编排（最小改动）**

- 保留 `qq_media_view`、`bma3_ocr`、`bma3_asr`；
- 在工具描述/面板提示/预设提示词里写清“无视觉模型先 OCR/ASR”；
- 优点是边界干净，缺点是仍需两次工具调用，易用性一般。

**B. jar 侧 provider OCR/ASR（推荐优先验证）**

- 新增 `qq_media_extract` 或给 `qq_media_view` 增加 `extract=text|ocr|asr|auto`；
- 对 LLBot 调 `ocr_image` / 语音转文字 action（需确认精确 action 名和载荷）；
- 返回 `text`，无 provider OCR 时返回明确的 `OCR_UNAVAILABLE`；
- 优点：一个 fileId 直接拿文本，纯文本模型可用；缺点：provider 差异要抽象。

**C. 插件侧统一 `bma3_media`**

- 输入本地 `filePath`（由 `qq_media_view` 得到），内部按类型选 OCR/ASR；
- 不解决“fileId → 文件”的第一步，但减少第二步的选择成本；
- 无法替代 jar 的下载/切段。

建议：**先做 A 作为保底，再验证 B 作为主路径**。如果 LLBot `ocr_image` 真实可用，B 是 “无视觉模型” 最顺滑的方案；如果 provider 不支持，则回到插件 DeepSeek OCR，仍不可用就明确 `vision_required`，绝不假装识别成功。

长图/大图：

- 先切段，再逐段 OCR，按重叠区域去重/拼接；
- 限制总页数、总字节、总耗时和并发；
- 失败要返回哪一段失败、已成功多少段，不能把半成品当完整文本。

ASR：

- 现有 120 秒/8 MiB 是硬限制；
- 超过上限时，要么明确拒绝并给出分段建议，要么后续做 ffmpeg 切段 + 重叠合并；
- 不能把 SILK/AMR 的容器误判成 mp3；现有魔数识别可复用。

### 3.7 S6：QQ 全面、通用的动作面

当前缺口不是“多写几个工具”这么简单：

- 好友只读列表；缺申请、删除、备注、点赞、戳一戳、资料、头像、陌生人信息；
- 群只有部分管理；缺公告、精华、禁言、管理员、群文件系统、群相册、退群、改群名/头像等；
- 消息缺表情回应、已读标记、合并转发发送、语音转文字等；
- 当前 `AccountConfig` 无 provider/protocol 字段；
- 工具直接依赖 `OneBot11Client.sendAction`，provider 差异（如 `llonebot_debug`）开始写进业务工具。

建议分三层：

1. **Transport**：抽出 `QqActionTransport`（至少提供 `action(name, params, timeout)` 与能力查询），当前实现为 `OneBot11Client`；未来才接 Milky/Satori，不在 hotfix2 假装已支持。
2. **Capability/profile**：
   - `protocol: onebot11`
   - `provider: auto|llbot|napcat|...`
   - `actionAliases`（例如 provider 的 OCR/群邀请回退 action 名）
   - `features` 或启动时探测（`get_version_info` / action 探测）
   - 每 action 超时、重试/不重试策略。
3. **工具面**：
   - 高频、写操作、需要消息板语义的，做 typed tool；
   - 低频、只读、provider 扩展的，做 `qq_provider_action`（默认只读，写操作需显式 allow + confirm）；
   - **发送类 action 必须禁止从通用工具直通**，否则绕过消息板和 `UNKNOWN` 不重发红线；通用工具只能走 `QqSendSupport`/`QqDelivery` 现有出口。

推荐的 hotfix2 好友切片（先取高频）：

- `qq_friend_list`（已有）
- `qq_friend_info` / `qq_friend_remark`
- `qq_friend_request_list` / `qq_friend_request_decide`
- `qq_friend_delete`（confirm）
- `qq_friend_like` / `qq_friend_poke`
- `qq_profile_get` / `qq_profile_set`（敏感字段单独确认）
- `qq_avatar_get`

群切片按 LLBot 实际支持分批，不一次性把 100+ action 全暴露给模型。

## 4. 不能违反的现有红线

1. **发送必须经消息板**：通用 QQ action 不能直接发文本/图片/文件；否则 `UNKNOWN` 语义丢失。
2. **账号不串号**：所有动作必须带 accountId 或从 source 解析并校验。
3. **内容不落日志**：媒体诊断只记计数/错误码/耗时，不记正文、URL、文件路径。
4. **凭据不进仓库/结果**：OCR/ASR/Provider 配置只存凭据文件或既有 credentials，不回显。
5. **真实联调不冒充**：LLBot OCR、输入状态、好友写操作、远端 DSH、B 站登录态都必须在真实环境验证后才能写 PASS。
6. **配置优先热重载**：Inbox 等待、面板摘要、媒体策略应走 `InboxRuntime`/`RuntimePolicy`；端口/host/token 这类不热重载的键要明确标注。

## 5. 待拍板的岔路

1. **S1 的出厂默认**：所有来源默认为 0 秒（Agent 显式开），还是给 `qq_private`/`qq_group` 一个保守默认值？群聊是否要支持“只等同一个人”的 sender 粒度？
2. **S1b 的工具输入**：`source` 精确匹配即可，还是需要 `sourcePrefix`、多个来源、`senderId` 过滤？
3. **S2 的默认模式**：`full` 保持不变、`auto` 超过阈值切摘要，还是默认 `summary`？是否允许总字符预算（与 D-044 的“不设总字符上限”冲突）？
4. **S5 的 OCR 主路径**：优先 LLBot provider `ocr_image`，还是优先插件 DeepSeek OCR？失败链顺序是什么？
5. **S6 的通用工具写权限**：`qq_provider_action` 是否允许写操作？允许哪些？发送类是否永久禁止？
6. **hotfix2 基线**：先从 test2 合并/移植到 `feat/bma3-plan-1` 再开分支，还是直接在测试分支上做？这会决定 commit/tag 历史。

## 6. 建议的 hotfix2 拆分

- **H2-A（Inbox 可靠性）**：S1 + S1b + S2 + 诊断字段 + 聚焦测试。
- **H2-B（可观测性）**：S3 + 媒体 instrumentation + watchdog HTTP/MCP。
- **H2-C（Computer Use）**：S4 + 无视觉模型 OCR 适配。
- **H2-D（QQ 面扩展）**：S6 的 transport/capability + 好友/群高频 typed tools + 只读通用 action。
- 每个切片独立 spec、独立门禁；最后统一做一次全量与真实 LLBot/DSH 验证。

## 7. 本轮未验证 / NOT_RUN

- LLBot 是否真的上报 `input_status`：`NOT_RUN`（其文档只明确有 `set_input_status` 发送动作，未列输入状态上报事件；NapCat 有 `notify/input_status` 扩展）。
- LLBot `ocr_image` / 语音转文字的精确 action 名、载荷和返回结构：`NOT_RUN`。
- 真实好友/群写操作、`set_friend_add_request`、备注、删除、点赞、戳一戳：`NOT_RUN`。
- B 站登录态在 xvfb workspace 的复用、截图/OCR 真机链路：`NOT_RUN`（历史 xvfb 真机证据不覆盖 B 站）。
- watchdog 诊断 JSON/tar 的实际 HTTP 输出、媒体计数、无视觉模型回退：`NOT_RUN`。
- 任何 Maven/npm/打包/真实 QQ/远端 DSH 验证：`NOT_RUN`。

## 8. 本机只读数据补充（不包含正文）

对 `run/bma3-live/data/bot.db` 做了只读聚合（`mode=ro`，不输出消息正文）：

- 797 条 INBOUND，按 `source + sender_id` 分组后 777 个相邻间隔；
- 中位数约 3 秒，75%/80% 分位约 7 秒，90% 分位约 10 秒；
- 约 85% 的间隔 ≤8 秒，约 90% ≤10 秒。

**证据边界**：这是本机历史/测试数据，不是远端真实私聊，也不能证明所有用户行为。它只能作为 `quietSeconds=8` 的初步依据，不能当作用户验收。
