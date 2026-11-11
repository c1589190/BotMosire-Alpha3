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
| D-017 | 2026-11-11 | 本会话用户消息 | 「消息来源设置可由tool调整的权重，超过300权重的inbox消息在inbox收到后1秒推送，没超过的则等30秒（两个时间都可配置）再统一推送，如果inbox在短时间内压入多条优先级小于300的消息，则默认不重置时间，等第一条优先级大于300的消息出现然后立刻一起推送，或者等第一条压入的优先级小于300的等待时间超过30秒后再推送；此外，优先级有各自数值，排序自然也是需要的」 | 三档推送时序：`≥immediateThreshold` 走 `importantDelaySeconds`（默认1s），其余走 `normalDelaySeconds`（默认30s）；批次以**最早一条**的入队时间为锚、不重置；任一条立即项到点就把全部 pending 一起 flush；`priority` 同时用于排序；见 [spec](specs/2026-11-11-priority-rules-and-push-schedule-design.md) §5 |
| D-018 | 2026-11-11 | 本会话用户消息 | 「是的，所有拿到的信息都必须通过inbox队列推送给bot，虽然在qq面目前没有任何硬响应字段」 | **全量入队**：取消 `notifyThreshold` 作为"进不进 inbox"的闸门；信号/权重只决定分数、推送时序与排序；QQ 面没有"这是回应你"的硬字段，因此不靠猜来丢消息 |
| D-019 | 2026-11-11 | 本会话用户消息 | 「iibot本来就有一个download机制，如果Agent通过qq历史消息或者群文件查看等方法拿到一个文件，可以返回一个文件id，通过额外的查看工具可以直接查看图片/文本，如果不是图片或者文本，或者图片/文本太大无法通过工具查看，则下载到bma3运行目录下自动生成的downloads文件夹中，其他平台也是如此；可直接返回的图片/文本直接通过工具查看」 | 统一 `fileId` + 查看工具 + `<dataDir>/downloads`；已实现 `qq_media_view`（2026-11-11）；MCP ImageContent 通道待真实宿主验证 |
| D-020 | 2026-11-11 | 本会话用户消息 | 「是的，推送的inbox面板中文本只显示有限的未ack条目，已ack的条目应当直接从inbox面板项目中删除；此外应该加一个按优先级额外搞的条目排序，允许批量ack指定序号、指定序号范围的条目，做成工具」 | 面板只显示未 ack；item 按 priority 排序；批量 ack 按序号/范围做成工具（**本批落了前两条**，批量 ack 列入后续） |
| D-021 | 2026-11-11 | 本会话用户消息 | 「不考虑任何兼容性，Alpha阶段搞什么版本兼容？」 | 允许 breaking：删除 `qq_source_weight_*` 三工具、删除 `qq_user_note.priority`、旧表/旧列直接 DROP，不留兼容层 |
| D-022 | 2026-11-11 | 本会话用户消息 | 「直接规划0.0.3」 | 版本目标 0.0.3；本轮**不发版、不打 tag**，后续还有改动 |
| D-023 | 2026-11-11 | 本会话用户消息 | 「可以设置一个-300，默认大部分消息来源都是0，QQConnect的At字段消息和私聊消息设置成301，不过这都是可以修改的配置项，一切取决于Agent意愿（例如Agent不但可以把qq_private整个根消息字段优先级设置成250，还可以把qq_private:132843233这个具体的小消息来源优先级额外设置成350，这里可以引入一个配置规则优先级）」 | 来源分数用**最具体前缀胜**的规则表；`dropBelow=-300`；一切可被 Agent 用 `set_config` 改写 |
| D-024 | 2026-11-11 | 本会话用户消息 | 「这个就随便了，建议是300本身也立刻注入」 | 立即档边界为 `≥ immediateThreshold`（300 本身即立即） |
| D-025 | 2026-11-11 | 本会话用户消息 | 「可以根据来源字段做机制，qq_private 为300 qq_group为200，然后额外给qqconnect出专门的配置项（同以格式化字段写在总配置文件），qq_at字段设置为+100这种，让配置更加灵活、统一」 | `weighting.sourcePriority`（来源基准分）+ `weighting.attributes`（平台属性加分，**加法**叠加）；QQ 的属性名由 adapter 提供 |
| D-026 | 2026-11-11 | 本会话用户消息 | 「qq_private为什么会有三级字段？qq_private被qqconnect解析，应当只读取是否为qq_private，以及/后面的qq号……不过确实应该加一个qq_sender，qq_sender/xxxx可以配置+多少多少，qq_name/具体名称字段包含 也可以；qq_name应该改成qq_self_name，表示bot自己的qq名称——哦对了，应该额外出self_qq、self_name等配置项；这些都是可以自由配置的」 | 配置键只有两级（`qq_private/<对方QQ>`、`qq_group/<群号>`）；`qq_sender/<qq>` 与 `qq_name/<子串>` 是加分项；`qq_self_name` 指 bot 自己的名字；`self_qq`/`self_name` 每账号 |
| D-027 | 2026-11-11 | 本会话用户消息 | 「1、qq_sender为什么不能和qq_private叠加？ 2、对，如果来源优先级小于-300，那就不注入了，直接丢掉 …… 4、额，我本来设想的是，Agent有标准的set_config工具，方便格式化修改自己的配置文件并走reload热重载，你没做吗？」 | `qq_sender` 与来源分数**叠加**；`< dropBelow` 的消息**不注入且不落消息板**；`set_config` 是分数的唯一写入口，写完靠 `ConfigWatcher` 热重载 |
| D-028 | 2026-11-11 | 本会话用户消息 | 「a 不落！」「b 不搞白名单，Agent想咋改咋改」「c 是的」「d 叠加」「e 看Agent怎么配置，如果+300那确实应该立刻注入」「f 是的」 | 丢弃即不落库；`set_config` 无白名单；self 身份每账号可取；名称匹配叠加；分值由配置决定是否跨立即线；旧分数入口全部退休 |

| D-029 | 2026-11-11 | 本会话用户消息 | 「N1 是的，出inbox注入筛选配置，一轮inbox被推送之后，重置一下等待时间，然后根据优先级排序、重新算等待时间来注入；每一个条目独立出ack」 | 每个 item **独立 ack**（全局 revision 只作观测）；推送一轮后**重置等待时钟**，按优先级重排、重算等待再推；新增 `inbox.push.minPriority` 作为注入筛选 |
| D-030 | 2026-11-11 | 本会话用户消息 | 「N2 是的」 | 批量 ack 的序号 = **面板当前排序下的 1-based 显示序号**，并绑定 `revision` 校验；允许跨 source |
| D-031 | 2026-11-11 | 本会话用户消息 | 「N3 可以设一个比较长的等待时间和数量限制」 | 保留期可配：`inbox.retention.maxAgeDays`（默认 30）+ `maxItems`（默认 5000）；只清 ACKED |
| D-032 | 2026-11-11 | 本会话用户消息 | 「N4 是这样」 | `inbox_wait`：等"面板出现未 ack 条目"，默认 20s、硬顶 60s，**只读不 ack**，超时返回 `changed:false` |
| D-033 | 2026-11-11 | 本会话用户消息 | 「N5 允许」 | OneBot 连接诊断可在握手失败后主动探测同 host:port 的普通 HTTP，用于区分"HTTP API 端口"与"路径/token 问题" |
| D-034 | 2026-11-11 | 本会话用户消息 | 「N6 可以」 | MCP 401 响应体带 `errorCode`/`reason`/`headerPresent`/`hint`（只出现环境变量名，不出现 token 值），日志升 ERROR |
| D-035 | 2026-11-11 | 本会话用户消息 | 「N7 先走标准read_image吧」 | 媒体先走"落盘 + `read_image`"；MCP ImageContent（`assetDocIds`）暂不接 |


## D-029..D-035 代码环境语义

- **D-029**：`InboxService.ackItems/ackIndices`；`PanelItem.index`；`lastFlushAt` 作为新一轮的排程锚点；`InboxRuntime.pushMinPriority` 控制"进面板但不唤醒"。
- **D-030**：`inbox_ack` 的 `indices` / `rangeFrom+rangeTo`；`checkRevision` 拒绝过期 revision（`REVISION_CONFLICT`）。
- **D-031**：`InboxStore.purge(cutoff, maxItems)`；`InboxService` 每 100 次 append 与启动时清理。
- **D-032**：`InboxWaitTool`（`MAX_TIMEOUT_MS=60000`）；HTTP SSE 与工具共用 `InboxService.awaitPush`。
- **D-033**：`OneBotDiagnostics.of/probeHttp`；`AccountRegistry.Fault` + `recordFault`；`qq_account_list.lastError`。
- **D-034**：`Bma3McpServer.authFailure/unauthorizedBody` + `authHint`。
- **D-035**：`QqMediaViewTool` 返回 `path` + `hint`；`ToolAssetResolver` 未接线。

## D-017..D-028 代码环境语义

> 按 M-002：每条裁定除决定本身外，必须能定位到模块、数据结构、状态机、配置、并发/失败模式与验收。完整实现细节在 [spec](specs/2026-11-11-priority-rules-and-push-schedule-design.md)。

- **D-017**：`bma3-inbox` 的 `InboxService.awaitPush` + `DeliveryPolicy(importantThreshold, importantDelay, normalDelay)`；pending 判定用 `InboxItem.revision` 与内存 `pushedItems`；`flushPending()` 一次标记全部 pending 并 bump `pushRevision`；SSE 只推 flush 结果。
- **D-018**：`Bma3App.ingest` 先算分数再决定是否入库；`notifyThreshold` 语义删除。
- **D-019**：`Segment.data` + `chat_message.segments_json`（本批）；`fileId`/查看工具/`downloads` 目录待做。
- **D-020**：`InboxService.panel()` 过滤 ACKED（已有）；`PanelItem` 按 priority 排序（已有）；批量 ack 待做。
- **D-021**：`QqExtrasStore` 打开时 `DROP COLUMN priority` / `DROP TABLE qq_source_weight`；三个 `qq_source_weight_*` 工具删除。
- **D-022**：`docs/superpowers/plans/2026-11-11-0.0.3-plan.md`。
- **D-023**：`bma3-core` 的 `PriorityRules`（最具体前缀，`*` 兜底）+ `SourceKeyer`。
- **D-024**：`AppConfig.Weighting.immediateThreshold`（默认 300，`≥` 比较）。
- **D-025**：`AppConfig.Weighting.sourcePriority` / `attributes`；`PriorityResolver` 加法叠加 + `reasons` trace。
- **D-026**：`QqSourceKeyer` 把 `qq_group/<acct>/<gid>` 投成 `qq_group/<gid>`；`QqSignalExtractor` 产出 `qq_at_bot`/`qq_at_all`/`qq_at_other`/`qq_reply_to_bot`/`qq_self_name`/`qq_command`；`qq_sender/`、`qq_name/` 由 resolver 按配置匹配。
- **D-027/D-028**：`ConfigEditor`（校验后原子替换）+ `SetConfigTool`（`set_config`）/`GetConfigTool`（`get_config`）；`QqSendSupport` 发送成功后回写 `external_message_id`。

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
| Q-F | 兼容性：旧配置键、`qq_*`/`inbox_*` 工具名、`source` 地址是否保持？ | **已定**：不考虑兼容（D-021） |
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
