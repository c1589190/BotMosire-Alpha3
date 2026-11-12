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

| D-036 | 2026-11-11 | 本会话用户消息 | 「删掉多余的工具和测试」 | 删除 legacy delivery 唤醒路径（`Delivery`/`DeliveryState`/`DeliveryAck`/`due()`/`ack(deliveryId)`/`/deliveries/*` HTTP/`inbox_delivery` 表/`retryTtl`+`maxAttempts`）与其专属测试；再删三个与保留工具重复的工具：`qq_recent_messages`、`qq_account_login_info`、`qq_user_note_get` |

## D-036 代码环境语义

- `bma3-inbox`：`ItemStatus` 只留 `AVAILABLE/ACKED`（老库里的 DELIVERED/UNDELIVERED 读成 AVAILABLE）；`InboxStatus` 收缩为 available/acked/remainingImportant/revision/unread。
- 工具面：QQ 16 + Inbox 6 + 配置 2 = 24 个；`inbox_ack` 只剩 itemIds/indices/range/revision。
- `dsh-plugin/lib/self.js` 的默认工具组补上 `inbox_items/inbox_get/inbox_wait/qq_media_view/qq_conversation_history/qq_message_get/get_config/set_config`。

| D-037 | 2026-11-11 | 本会话用户消息 | 「我的原话就是当前目录下的systemprompt.md作为直接的系统提示词注入对话，不注入其他东西」（并对"额外补安全约束"的提议明确否决） | `systemprompt.md` = 完整 system prompt：`self` 模块的 `prompt.complete` 默认 `true`（整份替换），插件不拼接任何默认人设/安全约束；文件缺失或为空时**不挂任何 section**；只有显式 `complete:false` 才退化为叠加；见 [spec](specs/2026-11-11-priority-rules-and-push-schedule-design.md) §12 与 `dsh-plugin/README.md` |
| D-038 | 2026-11-11 | 本会话用户消息 | 「已推送的 inbox 小条目，被推送后就冻结了，后续同来源算作新条目不就行了？」 | **推送即冻结**：条目被推送到客户端后 `frozen_revision>0`，此后同来源的新消息**另开新条目**（不再合并进已推送条目）；因此被 ack 的内容恒等于被推送的内容，ack 不再因「条目自身在客户端看到后又变了」而冲突。`activeForSource` 只匹配 `frozen_revision=0`；`flushPending()` 在 bump `pushRevision` 后冻结本轮窗口条目。见 [spec](specs/2026-11-11-priority-rules-and-push-schedule-design.md) §12 与 `InboxItem.frozen()`。**其中「同来源开新条目」部分被 D-039 取代** |
| D-039 | 2026-11-11 | 本会话用户消息 | 「做一个简单的去重吧……如果有同来源的旧冻结条目，那么让旧条目状态改为未冻结、打个简单的续标提醒然后把新条目追加到旧条目上，形成完整的新条目即可」 | **同来源只保留一条**：`appendOrMerge` 找不到 open 条目时，取该来源最新的「已推送未确认」冻结条目，解冻后把新消息**续**上去（新 entry 带 `续` 标记），不新开条目。由此 ack 的仍是这一条；若 ack 携带的是续接前的 revision，服务端按条目校验返回 409（内容确实变了，要求重看），该条目已重新 pending，下一轮会带 `续` 标重新推送。见 `InboxService.mergeInto` / `CONTINUATION_MARKER` |
| D-040 | 2026-11-11 | 本会话用户消息 | 「2 是的，加上」「3 是的，写一个统一的合并管理方法」 | ①条目视图暴露 `continued`（`inbox_items` / `inbox_get` / 面板条目），true = 该条目在被推送过之后又续上了新消息（行尾带「续」标）；②统一合并管理 `InboxService.consolidateSources()`：把同一来源的多条未确认条目并入最新那条（旧内容按时间序在前、接缝处打「续」标、被吸收的行删除，消息板仍是事实源），构造时自动跑一次以清理历史数据。见 spec §12 |
| D-041 | 2026-11-11 | 本会话用户消息 | 「加一个优先级加减法筛选功能，如果一条消息的开头、中间任意部分等地方出现任意可配置字段，那么自动根据配置增加/减少优先级……所有规则同时应用，可叠加；例如……群聊基础 0，`/` 开头 -300，@ 字段 +350，大肥鱼 +150，总优先级 200」 | **`weighting.rules`**：有序规则表 `{pattern, match: prefix|contains, delta, ignoreCase(默认 true)}`，对消息正文逐条匹配，命中即累加（可正可负、可叠加、与 `sourcePriority`/`attributes` 相加）；总分照旧 clamp 到 `maxPriority`，再与 `dropBelow`/`immediateThreshold` 比较。命中原因写进 `reasons`（`rule:prefix:/ -300`）。`set_config` 的结构化值必须落成 YAML 结构而不是一行字符串（已修 `ConfigEditor`）。见 `AppConfig.ContentRule` / `PriorityResolver.contentDelta` |
| D-042 | 2026-11-12 | 本会话用户消息 | 「P0-1 我认为应该保留一条不看直接清的通道，并且要足够自定义，例如优先级低于xxx的统一清空之类的等功能」 | **`inbox_dismiss`**：`scope=visible|filter|ids`，过滤 `sourcePrefix / priorityBelow / olderThanSeconds / limit`；标记 ACKED 不删除；默认跳过 `priority >= immediateThreshold`（`allowImportant` 才动）；支持 `dryRun`。HTTP `POST /dismiss`。见 [spec](specs/2026-11-12-0.0.4-watchdog-delivery-design.md) §3.4 |
| D-043 | 2026-11-12 | 本会话用户消息 | 「1 选B，两条就两条，能消费就行（而且根据我的理解，应该是在ack一条已经推送的条目时，同来源可能会出现第二条吧？这就是设计允许的）」 | **同源可多条**：推送后条目冻结，后续消息另开新条目；同一来源最多一条未冻结条目；删除 `consolidateSources()` 与续接合并；已推送条目内容不再变化，ack 不因同源新消息 409。见 spec §3 |
| D-044a | 2026-11-12 | 本会话用户消息 | 「远程的0.0.3 hotfix7似乎依然完全不推送消息优先级小于指定数（一般是300）的消息，能不能改成60秒后推送，而不是单纯注入？我记得我最开始的想法是，注入确实是都立刻注入，但是推送是延迟的」 | **推送不再有硬静音**：`inbox.push.minPriority` 废弃/忽略；所有未被 `weighting.dropBelow` 丢弃的条目都会推送——≥`immediateThreshold` 走 `importantDelaySeconds`（默认 3s），其余走 `normalDelaySeconds`（默认 60s）。只有 `dropBelow` 表示不注入/不落库。见 0.0.4 spec §4 |
| D-044 | 2026-11-12 | 本会话用户消息 | 「P1貌似都是一些易用性问题，这个你自己以首先必须方便使用、可以适当节省token为设计宗旨设计就行」<br>「补充2 那就每次都注入，但是标注一个“（消息更新活跃）”，来提醒Agent即可，Agent想要处理、想要ack随其便，bug直接修」<br>「3 普通档调整到60」<br>「4 不设总字符上限，设单条目字符上限+总条目显示上限，配置要可！控！」 | ①工具设计原则（方便第一、省 token 第二）进 spec §9；②同源新消息重置未冻结条目等待，推送后新消息另开条目并标「（消息更新活跃）」；③默认 `importantDelaySeconds=3`、`normalDelaySeconds=60`；④不设总字符上限，`inbox.display.maxCharsPerItem` + `maxItemsPerPanel`，范围放宽且可热重载。见 spec §4/§5/§9 |
| D-045 | 2026-11-12 | 本会话用户消息 | 「P0-2 活就活呗，bma3侧绑定了一个会话，那就一直绑着，其中的实现详情见我后面说的watchdog机制（是的要新加）」 | **绑定持久化 + 自动恢复**：插件持久化 `targetSessionId`/`paused`/`stopped`/`lastPushRevision`，加载时 `agents.get() ?? agents.resume()` 后自动 bind+start；`pushRevision` 落库；已冻结未确认条目重启不自动重推。见 spec §3.2/§6.5 |
| D-046 | 2026-11-12 | 本会话用户消息 | 「6 这就是鬼知道了，理论上来说自然是能看的东西越多越好，不能看的东西实在存在的话，我会让Agent自己连ssh读qq机的本地文件，因此不用硬来」 | **平台历史只读穿透**：`qq_remote_history` 不写消息板；`count` 1..1000，真实 LLBot 对 1000 不稳定 → 客户端降级 **1000→300→100** 并记录 attempts；`cursor=message_seq`；失败带 `errorCode/retcode`；读不到的由 Agent 自行 SSH。真实证据：955432692 的 100/300 正常、1000 失败（2026-11-12 只读探测）。见 spec §8.4 |
| D-047 | 2026-11-12 | 本会话用户消息 | 「5 部署的远程环境是不同机的，bma3应当走Onebot协议并处理好相关对接和安全性」<br>「6 你要知道，理论上bma3拿到的只是一个Onebot11 ws配置，尽可能在协议内实现文件收发，非常手段都要上bash了，bma3为啥要维护呢？」 | **文件只走 OneBot 协议**：`upload_group_file`/`upload_private_file`；`base64://` → 绝对路径 → `providerPath`（Agent 用 bash 放到 QQ 机）三档；不做 BMA3 文件服务器/共享 FS 假设；provider 不支持明确 `UNSUPPORTED_REMOTE_FILE`。**真实 LLBot 证据（2026-11-12）**：私聊 `upload_private_file` 接受 `base64://`，39B 文本文件 `SENT`；群文件未发。见 spec §8.3 |
| D-048 | 2026-11-12 | 本会话用户消息 | 「8 qq_name应该被取缔，使用qq_person_id/qq号 这个必定可用的静态地址，昵称、字段可以额外做一个通用大搜索功能」<br>「8 都没问题，通用搜索一起加」<br>「哦对了再加一个，qq群头衔查看、群昵称查看和区分等」 | **身份**：删除 `qq_name/`；`qq_sender/` 改名 `qq_person_id/<qq>`；`qq_self_name` 保留。消息模型加 `sender_card`/`sender_role`；新增 `qq-directory.db`（群名/成员/头衔）；`qq_group_members`/`qq_group_member_info`；`qq_search`（消息/人/群）。见 spec §7 |
| D-049 | 2026-11-12 | 本会话用户消息 | 「0.0.4别急着推送，以上这些东西涉及到了大量状态监控问题，因此引入一个可依赖除了app外所有包的新包watchdog，负责专门监控bma3的各种服务状态，同时可以做一个watchdog-spi，不依赖所有bma3包，来实现底层的通用性」<br>「5 a没问题，watchdog模块本身可以实现mcp接口，方便agent自己监控自己现在和之前的状态日志」<br>「b 是的，可以额外在core里加一个部署者qq配置，允许Agent修改，当出现问题时，给部署者的qq发一条私聊来报警，后续还可以支持更多功能」 | **`bma3-watchdog-spi`（JDK-only）+ `bma3-watchdog`（依赖除 app 外全部模块）**；引擎/探针/恢复/维护任务/MCP 工具/状态落盘；`bma3-app` 装配。部署者 QQ 配置 `deployer.qq/enabled`，DOWN 时私聊报警（冷却、统一发送出口、`UNKNOWN` 不重发）。见 spec §6 |
| D-050 | 2026-11-12 | 本会话用户消息 | 「9 先改代码，改完编译完在本机应用、进行全量真环境调试，调试完告诉我情况、自己改能改的bug，遇到没法解决的问题再找我要裁定」<br>「0.0.4别急着推送」 | 0.0.4 **先实现→编译→本机部署→全量本地真环境调试→自修可修 bug→汇报**；其中「本轮不提交/不打 tag/不推送」被 **D-051 覆盖**。见 [plan](plans/2026-11-12-0.0.4-plan.md) |
| D-051 | 2026-11-12 | 本会话用户消息 | 「没啥问题，发布0.0.4-early」 | **发布 `0.0.4-early`**：版本号统一为 `0.0.4-early`；本地门禁 + 真实 LLBot 私聊/只读验证通过后提交、打 tag `v0.0.4-early`、推送并创建 GitHub Pre-release（assets：`bma3-app-0.0.4-early.jar`、`mosire-bma3-dsh-0.0.4-early.tgz`、`SHA256SUMS`）。**覆盖 D-050 的「本轮不推送」**。 |

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

## D-042..D-050 代码环境语义

> 完整设计见 [0.0.4 spec](specs/2026-11-12-0.0.4-watchdog-delivery-design.md)；计划见 [0.0.4 plan](plans/2026-11-12-0.0.4-plan.md)。

- **D-042**：`InboxService.dismiss(DismissRequest)` + `InboxHttpServer POST /dismiss` + `InboxDismissTool`；`DismissRequest(scope, itemIds, sourcePrefix, priorityBelow, priorityAtLeast, olderThanSeconds, limit, dryRun, allowImportant, reason)`；只写 `ItemStatus.ACKED`，不清正文；`priority >= immediateThreshold` 默认跳过。
- **D-043**：`InboxService.appendOrMerge` 只匹配 `frozen_revision = 0` 的 active item；无 active 时新建条目并置 `continued = hasUnackedFrozenForSource(source)`；删除 `reopenableForSource`/`mergeInto(continuation)`/`consolidateSources()`；`nextDue`/`flushPending` 用 `frozen_revision` 判定已推送。
- **D-044**：`InboxService.afterAppend` 用 `pendingSince.put(...)` 重置；`nextDue` 取 `min(softDue, firstSeen + maxFirstPushDelay)`；`mergeInto` 优先级取 max；`DeliveryPolicy` 由 `Supplier` 提供，`ConfigWatcher` 热更新 `RuntimePolicy`；`AppConfig.Display.maxCharsPerItem` + `PanelItem.truncatedChars`；`InboxDisplay` 上限放宽到 1..50。
- **D-045**：`inbox_meta.push_revision` 持久化；`dsh-plugin/lib/inbox.js` 持久化状态文件（默认 `~/.dsh/storages/bma3-inbox.json`）；`handleEvent` 用 `panel.pushRevision > injectedPushRevision` 判定注入；`wake()` 成功后持久化；`ctx.agents.resume({resumeSessionId})` 恢复会话并由 `ctx.effect` dispose。
- **D-046**：`QqRemoteHistoryTool` → `get_group_msg_history`/`get_friend_msg_history`；`count` schema 1..100；`message_seq` 游标；retcode 1200 空页；日志只记 action/count/retcode/elapsed/messages。
- **D-047**：`QqConversationSendFileTool` → 先写 `InboundMessage(OUTBOUND)`，再 `upload_group_file`/`upload_private_file`；`filePath`（dataDir 下）读字节试 `base64://`，失败试绝对路径，再失败用 `providerPath`；三态回写；`UNKNOWN` 不重试。
- **D-048**：`OneBotEvent.senderCard/senderRole`；`chat_message.sender_card/sender_role`；`QqDirectoryStore`（`qq_group`/`qq_member` 表）；`QqContactListTool` 写群名；`QqGroupMembersTool`/`QqGroupMemberInfoTool`；`QqSourceLabeler` 用群名；`PriorityResolver` 只认 `qq_person_id/`；`QqSearchTool` 走 `MessageBoardStore.search(...)` + 目录。
- **D-049**：`bma3-watchdog-spi`（JDK-only）+ `bma3-watchdog`（core/agentlib/qqconnect/inbox/webconnect）；`WatchdogEngine` 定时探针 + 退避恢复 + `state.json`/`events.jsonl`；`bma3_watchdog_status/probe/remediate`；`HealthServer` 固定端口 + `/healthz`/`/readyz`/`/watchdog`；`AppConfig.Deployer(qq, enabled)` + `QqNotifier` 私聊报警（冷却、统一出口、UNKNOWN 不重发）。
- **D-050**：`docs/superpowers/plans/2026-11-12-0.0.4-plan.md`；本轮工作树只改不提交，真机结果按 `PASS`/`NOT_RUN` 记录。

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
