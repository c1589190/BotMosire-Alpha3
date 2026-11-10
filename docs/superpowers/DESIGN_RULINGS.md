# 设计裁定索引（D-xxx）

> 用户设计裁定的**逐字原话**与落点。维护裁定在 [`../agents/MAINTENANCE_RULINGS.md`](../agents/MAINTENANCE_RULINGS.md)。
> 引用格式：`D-xxx · 日期 · 来源 · 原话`。
> 标记 `〔转述〕` 的条目是压缩后的要点，不是逐字引文。

| ID | 日期 | 来源 | 裁定（原话或转述） | 落点 |
|---|---|---|---|---|
| D-001 | 2026-11-09 | 本会话用户消息 | 「当前目录将会作为隔壁项目的继承与重构被开发」 | 根 `AGENTS.md` §2；继承调查报告 |
| D-002 | 2026-11-09 | 本会话用户消息 | 「那QQ协议方面就用单正向WS吧」 | QQ 适配器第一版只开正向 WS：同一连接收事件/调 API，不双开 WS+HTTP |
| D-003 | 2026-11-09 | 本会话用户消息 | 「DSH插件不单单要维护Inbox的实际注入，还要维护一个简单的角色扮演用系统提示词、工具组预设，这两个东西是分开的小组件，但是统一在一个插件中维护」 | 一个 DSH 插件包，内部三个独立 module/row：Inbox 注入、角色提示词、工具组预设；配置/测试/失败域分开 |
| D-004 | 2026-11-09 | 本会话用户消息 | 「inbox肯定是要做成独立常驻服务的」<br>「单论Inbox服务端lib，只管单个Inbox的封装状态，包括有多少需要处理的条目、什么时候推送、什么时候被消费等」 | Inbox 独立常驻服务；服务端 lib 只封装单 Inbox 状态机，不拥有正文、不知道平台、不做传输 |
| D-005（提议） | 2026-11-09 | 本会话用户消息 | 「我的想法是，这种网页端比较成熟的应用，可以让服务端对接Windows/Linux的成熟Chronium，封装一套简单的登录、状态保存机制，方面Agent自己操作，进行登录、服务端linux屏幕控制等服务端本体环境不方便做的事」 | 浏览器 sidecar（Chromium/Playwright）负责网页登录/状态保存/截图操作；Linux 全桌面控制是否纳入，**未定，待评审** |
| D-006 | 2026-11-09 | 本会话用户消息 | 「走jlink+jpackage吧」<br>「类似billion context，首次安装时检测机器上是否有botmosire-a3程序，如果没有就从github上拉安装包安装，哦对了，botmosire的dsh插件也要做到一站式标准安装」 | Java 21 用 `jlink`/`jpackage` 产出自带运行时的 app-image；DSH 插件负责首次检测、从 GitHub Release 下载/校验/安装；服务由 systemd 常驻 |
| D-007 | 2026-11-09 | 本会话用户消息 | 「botmosire-a3的配置文件系统和日志系统必须优秀，允许部分服务热修改配置文件重连（例如qq连接……其实qq模块本来就该允许多账号热插拔吧？反正onebot协议本来就允许多开），日志则是该录入的调试信息都要录入」 | 配置采用分模块 schema + 热重载/对账；QQ 账号 registry 支持热插拔；日志结构化、分级、脱敏、可动态调级 |
| D-008 | 2026-11-09 | 本会话用户消息 | 「首先是底包……agentlib（带标准AgentTool实现，自动把AgentTool注册到MCP并走agentlib本土渠道暴露）、qqconnect……webconnect……是肯定需要的……此外还有新的inbox，inbox也要依赖agentlib，提供标准MCP、HttpAPI，最终全部整合到app，顺便挂WatchDog或者监控之类的」 | 初版模块：`agentlib` / `qqconnect` / `webconnect` / `inbox` / `app`；connect 模块实现标准 `AgentTool`，app 统一注册 MCP；**共享 core/store 是当前缺项，待确认** |
| D-009 | 2026-11-10 | 本会话用户消息 | 「对于单次inbox注入的单个显示条目，类似于QQ客户端的单个窗口（如图1），应当只显示大约4~8条最后消息」 | Inbox item 是会话窗口，只保留最近 N 条（N=4..8，默认 6）；完整消息在消息板；见 [spec](specs/2026-11-10-inbox-panel-sse-design.md) §4、§6 |
| D-010 | 2026-11-10 | 本会话用户消息 | 「对于显示条目本身，应当只显示优先级最高的前4~8个条目」 | 每次面板最多 M 个条目（M=4..8，默认 6）；默认保持最高优先级层、层内取前 M 个、不足不补；见 spec §4.3、§8 |
| D-011 | 2026-11-10 | 本会话用户消息 | 「消息来源、消息本身的自带关系（是否带有图片？是否带有引用关系？是否是嵌套聊天记录？不用完全展示，但是诸如引用等，概要是肯定要的），应当标清楚，实在无法在inbox中标清楚则也不需要上额外的提示词引导Agent去看history，Agent很聪明，不要浪费token」 | 行内标 source/时间/序号/发送者 + `[@]`/`[引用]`/`[图片]`/`[合并转发]` 摘要；不写 history 引导语；不出现图片 URL/rkey；见 spec §6 |
| D-012 | 2026-11-10 | 本会话用户消息 | 「不管是多少条消息还是多少个条目，都应该做进配置文件方便配置和热重载」 | `inbox.display.maxMessagesPerItem`、`maxItemsPerPanel` 等进 `config/app.yaml`；`ConfigWatcher` 热更新 `RuntimePolicy`；见 spec §5 |
| D-013 | 2026-11-10 | 本会话用户消息 | 「一个inbox总面板一个sse，其中的”窗口“事实上就是不同来源的inbox条目，它不需要被额外监听、订阅、维护，只需要等qqbot后端推送过来然后被动地添加就行，在查看上进行进一步限制——inbox总面板被推送的作用就是提示Agent干活，它不需要额外承担更多内容」 | 一个 inbox 一条 SSE；source 只是面板条目，没有 per-source subscription/lease；SSE 只推受限面板/心跳，ack 是面板游标；见 spec §3、§7 |
| D-014 | 2026-11-10 | 本会话用户消息 | 「来源字段的通用性，今天有qqconnect维护的qq来源（qqgroup/xxx/xxx、qqprivate/xxx），明天可能还有bilibili/xxx、wechat/xxx，这样的字段通用识别能实现吗？需要加一个简单的util方便inbox读取吗」 | `SourceAddress` 保持 `platform/...` opaque；`SourceView`+`SourceLabeler` 由 adapter 提供 label；Inbox 不 switch platform；保留 `qq_group/qq_private`，新平台用 `bilibili/...`、`wechat/...`；见 spec §4.1 |
| D-015 | 2026-11-10 | 本会话用户消息 | 「各类消息来源的通用优先级适配也一并整合」 | `PriorityResolver` 统一 platform 默认权重、per-source 覆盖、备注、信号权重、per-source threshold；QQ 只是第一个 adapter；见 spec §8 |
| D-016 | 2026-11-10 | 本会话用户消息 | 「消息发送tool等Agent自己写自己发的tool，硬限制应当是可配置的，默认设3000字因为这是qq的单条消息限制」 | 所有文本发送经 `QqSendSupport`；`maxTextChars` 默认 3000，可配置；按 code point 计数；reject/split；见 spec §9 |

## D-009..D-016 代码环境语义

> 按 M-002：每条裁定除决定本身外，必须能定位到模块、数据结构、状态机、配置、并发/失败模式与验收。完整实现细节在 [spec](specs/2026-11-10-inbox-panel-sse-design.md)。

- **D-009**：`bma3-inbox` 的 `InboxItem/AppendItem/InboxService` 拥有有界 entries；`bma3-core` 消息板保留全量；`Bma3App.ingest` 构造 entry；SQLite `inbox_item` 加 entries/summary 迁移；并发 append 串行化；验收见 spec §11.1。
- **D-010**：`InboxService.panel()` 做 priority/lastAt 排序与 top M；`InboxPanel/PanelItem` 带 omitted；不改变消息板；验收 spec §11.2。
- **D-011**：`OneBot11Codec`/`Bma3App` 生成 `InboxEntry.markers`；`InboxEntry.render()` 统一格式；禁止 URL/rkey；验收 spec §11.3。
- **D-012**：`AppConfig.Inbox.Display`、`ConfigLoader`、`ConfigWatcher`、`AtomicReference<RuntimePolicy>`；非法配置 last-known-good；验收 spec §11.4。
- **D-013**：`InboxService` 的 panel revision/acked revision；`InboxHttpServer` 的 `/events` SSE；DSH 插件单连接；无 per-source 表/lease；验收 spec §11.5/§11.6。
- **D-014**：`SourceAddress`（core）+ `SourceView`/`SourceLabeler`/`SourceLabels`；`QqSourceLabeler`；Inbox 不 import QQ；验收 spec §11.8。
- **D-015**：`app/priority` 的 `PriorityResolver`；`AppConfig.Weighting` 的 platformWeights/signals；QQ adapter 提供 signals；验收 spec §11.7。
- **D-016**：`QqSendSupport` 单一发送出口；`AppConfig.QqConnect.Send`；所有发送工具；验收 spec §11.9。

## 待裁定

以下问题部分已定、部分未定；未定项等 spec 确认，不得把提议当成既定设计。

| # | 问题 | 现状 |
|---|---|---|
| Q-A | 重构范围：保留并演进现有 Java 实现，还是重写？ | 未定；倾向重写，但语言未定 |
| Q-B | 仓库形态：单仓单模块、单仓多模块，还是回到多仓？ | 未定；当前远端是单仓 |
| Q-C | 运行时形态：外部宿主、进程内 Agent 循环，还是纯服务端？ | **已定方向**：DSH 为唯一宿主；Inbox 为独立常驻服务（D-004） |
| Q-D | Inbox/唤醒层：保留独立通知板、内嵌，还是取消 IPC 只留本地表？ | **已定**：独立服务；Inbox lib 只管单 inbox 状态（D-004） |
| Q-E | B 站能力与 Playwright：留在主产物、拆独立模块，还是移出？ | 未定；视频工具可复用，私信需适配器，Playwright 不应进主产物 |
| Q-F | 兼容性：旧配置键、`qq_*`/`inbox_*` 工具名、`source` 地址是否保持？ | 未定 |
| Q-G | 数据迁移：旧 `bot.db`、`inbox.db`、媒体与凭据如何处理？ | 未定 |
| Q-H | 发布形态：fat jar、容器、服务端常驻，还是别的？ | **已定方向**：独立常驻服务；Java 用 `jlink`/`jpackage` 自带运行时（D-006） |
| Q-I | 语言：Java/Kotlin、TypeScript、Go？ | **已定**：Java 21 + `jlink`/`jpackage`（D-006） |
| Q-J | 浏览器 sidecar 范围：只做网页登录/状态保存，还是包含 Linux 桌面控制？ | 未定（D-005 是提议） |
| Q-K | 是否增加共享 `core`/`store`（Account/Conversation/Message/Source/Outbox/Media），让 qqconnect/webconnect/inbox 共用消息板？ | 未定；当前模块图缺此项，是本轮最大结构缺口 |
| Q-L | Inbox 是 app 内的模块，还是 app 之外的第二个进程？ | 倾向：app 是独立于 DSH 的常驻服务，Inbox 是 app 内模块；待确认 |

## 记录规则

- 用户给出新设计裁定后，先在本表登记逐字原话与日期，再写专题 spec；索引与 spec 互相链接。
- 旧项目的 D-xxx 不自动继承；只有被本项目 spec 明确吸收的，才以「继承自 D-xxx」标注。
- 被取代的裁定不删除，加状态头与取代者。
