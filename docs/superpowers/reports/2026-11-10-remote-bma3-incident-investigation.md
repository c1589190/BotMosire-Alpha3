# 远端 bma3 停机报告复核

> **类型**：调查（时间点快照，只作追溯，不作当前事实）
> **日期**：2026-11-10
> **触发**：用户转述远端对 `BotMosire-Alpha3` 的停机报告，要求先排查、定位已有问题。
> **方法**：本机 `feat/bma3-plan-1` @ `53b13a1` 源码逐项核对；本机可复现实验；本地 `run/bma3-live` 的 DB/日志/配置核对；DSH 0.2.2-rc.1 API 与继承来源 `iiBotMosire-DSH` 只读对照。
> **限制**：远端 `/home/ubuntu` 在本机不存在，远端服务、端口、DB 计数、155/1049/0 日志计数均未在本机复现，只能作为转述事实；本报告确认的是**代码根因**与本地可复现部分。

## 1. 结论

远端报告的 10 项均能在当前代码中找到对应根因，不是误报；但其中 4 项的范围需要收窄或换层：

1. **P0-2 只合并 `AVAILABLE` item，不是“任何非 ACKED”**；成灾条件是“无人轮询 + 无上限 + `availableAt` 不推进”。
2. **P1-5 的图片 URL 确实进了 DB 和读取工具结果，但当前代码没有把正文写进日志**；“日志泄漏”需要远端日志实证，不能由代码直接推出。
3. **P1-7 不是永久卡死**：有 poller 时默认约 30 分钟后 `UNDELIVERED`，但期间整条链停摆；若 binding 丢失、没人调用 `due`，则连重试都不会发生。真正的“多 delivery 只 ack 最后一条”还叠加了 `due` 并发重复的根因。
4. **P1-6 的 binding 状态不属于 Java `/healthz` 的职责**：Java 侧能修 QQ 连接与账号 readiness；DSH binding 只能由插件侧状态/心跳暴露，不能塞进同一个进程的 health 里假装同层。

另外发现若干远端报告未列的缺陷（详见 §3，不止 3 个）：

- `InboxService.due` 与 `appendOrMerge` 都不是原子操作；并发时 `due` 可重复生成 delivery（本机 281/300 次），`appendOrMerge` 可丢行（本机 48/300 次）。
- `qq_account_list.connected` 只看 client 对象是否存在，断线后仍可能显示 `true`。
- `inbox_list` 的 description 写“不含正文”，实际返回完整 `summary`；这是 P0-2 直接放大上下文占用的入口。

**构建结论**：远端报告的“Java 硬阻塞”是远端环境事实，不是本机事实。本机有 `/usr/bin/mvn`、`~/.m2/.../agentlib-mosire/0.2.5` 和 `~/ProjectMosire/AgentLibMosire`；`./mvnw -q test` PASS（31 tests / 1 skipped），`./mvnw -o -q -pl bma3-app -am package -DskipTests` PASS，产出 53MB shaded jar，内含 AgentLib 0.2.5。Java 侧可以在本机构建后把 jar 带到远端，不需要先补齐远端的 Maven/本地仓库。

**人设结论**：当前 `dsh-plugin` 的 persona 只是静态系统提示词桩，没有 `PERSONA.md` 注入/压缩重锚，也没有工具暴露面控制；这同时是 spec §8 的未实现项，不只是“新需求”。iibot 已有完整参考实现，但 BMA3 需要重新设计 persona 的归属与作用域。

## 2. 逐项复核

### P0-1 重启后 binding 丢失，唤醒链静默断掉 —— 成立

**代码事实**

- `dsh-plugin/lib/inbox.js:101-103`：`bindings`、`lastDeliveries` 都是进程内 `Map`。
- `dsh-plugin/lib/inbox.js:105-124`：只有 `bma3_inbox_bind` 工具会写入 binding；模块加载时没有恢复逻辑。
- `dsh-plugin/lib/inbox.js:275-287`：`autoStart` 定时器只调用 `registry.pollAll()`；`bindings` 为空时 `pollAll` 循环零次，且 `.catch(() => {})` 把错误吞掉。
- `dsh-plugin/lib/inbox.js:145-146`：插件 dispose 时 `clear()` 同时清空 binding 和 last delivery。
- `dsh-plugin/lib/inbox.js:228-249`：`bma3_inbox_ack` 只认 `lastDeliveries` 里最后一条；进程重启后即使服务端还有未 ack delivery，插件也无法再 ack。

**本机证据**

- 当前 DSH 会话 `bma3_inbox_status` 返回：
  `sessionId=session-0869743f-...`, `bound=false`, `current=null`, `bindings=[]`。
- 本机没有运行中的 bma3 进程，8765/46500 均未监听；只有 DSH 3080。故只能证明“未绑定状态”与代码路径，不能复现远端的 7 个 item。

**远端报告的对应关系**：远端 session-5a37… 在机器重启后丢失 binding；`delivered=0`、7 个 item 停在 `AVAILABLE`，与“内存 binding + 无持久化 + 空 binding 定时器空转”完全一致。

**修复边界**：这是 DSH 插件侧问题，不需要重编 Java。持久化 binding 时不要把 token 值写盘，只存 `tokenFile`/`tokenEnv` 引用；同时要持久化 last delivery 或改成可批量 ack，否则重启后仍无法 ack 旧 delivery。

### P0-2 AVAILABLE item 无限合并 —— 成立，但范围要收窄

**代码事实**

- `bma3-inbox/.../InboxService.java:39-57`：`appendOrMerge` 取同 source 的 active item，`text = item.summary() + "\n" + line`；只做 64KB 截断，没有条数上限、没有时间窗上限。
- `bma3-inbox/.../InboxService.java:50-54`：合并时保留 `item.availableAt()`，不随新消息推进。
- `bma3-inbox/.../InboxService.java:129-138`：`due` 的到期时间 = `item.availableAt() + delayFor(important)`。合并进来的后续消息不重新等 30 秒；item 只要老于 30 秒，下一次 `due` 就整段到期。
- `bma3-inbox/.../SqliteInboxStore.java:131-145`：`activeForSource` 的条件是 `status = 'AVAILABLE'`，**不是**“任何非 ACKED”。一旦 item 被 delivery 变成 `DELIVERED`，下一条消息会新建 item，不会继续并入旧 item。

**本机复现**（临时程序，未改仓库）：

```text
500 次 appendOrMerge 同一 source
merged revision=500 chars=19783 availableAt=2026-11-10T00:00:00Z status=AVAILABLE
due at t0+2s deliveries=1 textChars=19783
```

即：合并 500 条后仍是 1 个 AVAILABLE item，19,783 字符；到第一次 `due` 时整段 19,783 字符一次性成为 delivery.text。远端 7 个 item 合计约 16KB、单条 revision 205/130/108，符合这条路径。

**为什么是 P0**：它和 P0-1 是乘法关系。binding 丢失导致没有人调用 `due`，item 永远停在 `AVAILABLE`，于是持续合并；恢复 binding 的那一刻，这些老 item 又因为 `availableAt` 很老而立即到期。只修 P0-1 会把 7 个巨型 item 一次性放出来，不能算安全修复。

**修复边界**：Java 侧。至少要有“条数/时间窗/字节数”三重上限；达到上限就关闭当前 item、另起新 item。是否重置 `availableAt` 需要单独定：重置可保证每条消息都等延迟，但持续繁忙的群会永远等不到唤醒；更合理的是“首条到达时间 + 最大合并窗口”，窗口到期强制切换 item。`inbox_list` 也应改成紧凑摘要，或增加 `inbox_get` 读全文。

### P1-3 没有 @/reply 信号权重，群消息只能全收或全静音 —— 成立

**代码事实**

- `bma3-app/.../Bma3App.java:175-190`：priority 只来自 `privateSourceWeight`/`groupSourceWeight`，再被 `sourcePolicy.weight()` 覆盖；用户备注只做 `max`。完全没有 MENTION、REPLY_TO_BOT、NAME、COMMAND 信号。
- `bma3-app/.../Bma3App.java:186-190`：群消息是否进 Inbox 只用全局 `weighting.notifyThreshold()`；per-source 的 `notifyThreshold` 没有参与。
- `bma3-qqconnect/.../QqExtrasStore.java:16-18`：`SourceWeight` 明明有 `notifyThreshold` 字段；`QqSourceWeightSetTool.java:49-52` 也允许设置，但 ingest 不读它。工具面因此存在“设置了但不生效”的假能力。
- `OneBotEvent` 已有 `selfId`、`replyToMessageId` 和 `at`/`reply` segment；问题不在解析层，在权重层完全没用它们。

**行为后果**：群 source 的 weight 一旦低于 200，该群所有消息（包括 @ 和回复）都不进 Inbox；一旦设到 200 以上，该群所有消息都进 Inbox。要“只在该说话时说话”，当前只能二选一。

**额外约束**：`REPLY_TO_BOT` 不能只靠 `replyToMessageId` 判断。出站消息的外显 `message_id` 目前只出现在发送工具返回值里，没有回写消息板（`QqSendSupport` 用 `out-UUID` 作为 `messageKey`，响应里的 `message_id` 只拼进结果字符串）。要做“回复的是 Bot”需要先持久化出站外显 id，或维护内存映射；这是设计项，不是一行判断。

### P1-4 唤醒摘要丢 @ 和结构化 segment —— 成立

**代码事实**

- `bma3-app/.../Bma3App.java:213`：`body = event.text().isBlank() ? segmentsText(event) : event.text()`。
- `bma3-qqconnect/.../OneBotEvent.java:26-38`：`text()` 只拼接 `type == "text"` 的 segment，at/reply/image 全部跳过。
- 因此只要消息里有一小段文字，@、回复、图片就全部从摘要消失；只有“纯非文本消息”才走 `segmentsText`，那时 at 会被渲染成 `[@qq]`，image 变成 `[图片]`。

**本机复现**（反射调用 private `renderLine`）：

```text
输入 segments: at 3924945183, text "欢迎回来？", reply 8888, image https://...rkey=SECRET
输出: [20:00] #42 LvtN：欢迎回来？
```

@、reply、image 全部丢失。远端 LvtN 的 `@3924945183` 在唤醒摘要里没有，属于同一根因。

**修复边界**：Java 侧。渲染应始终遍历 segments，按类型输出文本、`[@qq]`、`[回复 #id]`、`[图片]`；reply 最好解析成原消息的 sender/seq，而不是裸 id。注意不要把图片 URL 放进摘要（见 P1-5）。

### P1-5 图片 URL 被写进 chat_message.text —— 成立（DB/工具结果；日志待实证）

**代码事实**

- `bma3-qqconnect/.../OneBot11Codec.java:62`：image 分支取 `data.url`，没有 URL 时退回 `data.file` 或 `[image]`。
- `bma3-core/.../SqliteMessageBoardStore.java:90-94`：`append` 把所有 segment 的 `text` 用换行拼接成 `chat_message.text`；image 的 URL 就这样进了正文。
- `bma3-qqconnect/.../QqRecentMessagesTool.java:45-48`：`lastText` 直接取这条 text 并截 300 字；`qq_conversation_read` 也返回同一条 text。于是 URL（含 `rkey`）进入模型上下文。

**本机证据**：`run/bma3-live/data/bot.db` 中 105 条消息正文是 `https://multimedia.nt.qq.com.cn/download?...`，合计 25,625 字符，覆盖 5 个 source。远端报告说的“膨胀 DB / 进入上下文”有本地同构证据。

**需要收窄的表述**

- 当前日志代码只记 ID/状态/计数，没有把消息正文写进日志（`Bma3McpServer` 只记 method/status；`LoggingConfigurer` 也不打正文）。所以“rkey 泄漏进日志”不能由代码推出；如果远端日志里确实有，需要单独定位是哪个日志点。
- 图片-only 消息在唤醒摘要里走 `segmentsText`，显示 `[图片]`，URL 不会进 wake preview；URL 主要经 `qq_recent_messages`/`qq_conversation_read` 进入上下文。

**修复边界**：Java 侧，且涉及 `Segment`/消息板 schema。最低限度是 codec 把 image 映射成 `[图片]`，不要让 URL 进入 `text`；若还需要图片能力，另建媒体表/工具，不要把带 `rkey` 的临时 URL 当正文存。已落库的 105 条（远端对应数据）需要单独清洗方案，先备份。

### P1-6 WS 没有自动重连，health 也不反映 QQ 连接 —— 成立

**代码事实**

- `bma3-qqconnect/.../OneBot11Client.java:43-59`：只有 `start()`；`ready` 是一次性 `CompletableFuture`。
- `OneBot11Client.java:124-137`：`onClose`/`onError` 只让 pending 失败，不重连、不清 `socket` 引用、不通知 registry。
- `bma3-qqconnect/.../AccountRegistry.java:41-99`：只有配置 `reconcile` 会重建连接；没有定时健康检查/重连。
- `AccountRegistry.java:117-133`：首次 `start()` 失败就直接返回 false，账号不进入 `active`；如果 NapCat 比 bma3 晚启动，之后不会自动重试，除非配置变更或进程重启。
- `bma3-app/.../HealthServer.java:19-26`：`/healthz` 永远返回 `{"status":"ok"}`，与 QQ/Inbox 状态无关。
- `bma3-qqconnect/.../QqAccountListTool.java:37-41`：`connected` 只看 `client(accountId).isPresent()`；断线后 client 对象还在，仍会显示 `true`。所以“只有 qq_account_status 能看出来”也要修正：`qq_account_list` 同样会骗人，`qq_account_status` 只是会因 `sendAction` 失败而报错。

**规范对照**：foundation spec §5 已写“入站去重、重连 backoff、心跳、发送三态”（`docs/superpowers/specs/2026-11-09-bma3-foundation-design.md:61`），当前实现没做重连/心跳。这不是新需求，是未实现项。

**修复边界**：Java 侧。建议把“进程存活”和“QQ readiness”分开：`/healthz` 保活，另加 `/readyz` 或 health JSON 的 `qq.connected`/`accounts[]`；AccountRegistry 保存 desired state，失败也保留条目并带 backoff 重试；`OneBot11Client` 暴露真实连接状态并支持重连。**binding 状态不要塞进 Java health**，它不是同一个进程的事实。

### P1-7 ack/重投语义 —— 成立，且根因比报告多一层

**代码事实**

- `bma3-inbox/.../InboxService.java:101-127`：只要存在 `DELIVERED` 且未到重试时间的 delivery，就 `open=true` 并直接返回；新的 AVAILABLE 批次不会生成。默认 `DeliveryPolicy` 是 10 分钟重试、3 次；**在有 poller 持续调用 `due` 的前提下**，一次漏 ack 最多卡约 30 分钟，到上限后转 `UNDELIVERED`，不是永久卡死。如果 binding 丢失、根本没人调用 `due`，则连重试/降级都不会发生，会一直停在 `DELIVERED`。
- `InboxService.java:112-118`：超过 `maxAttempts` 后把 item 置 `UNDELIVERED`；`InboxService.list()` 只过滤 `ACKED`（`:82-88`），所以 `UNDELIVERED` item 会一直出现在 `inbox_list`。
- `dsh-plugin/lib/inbox.js:77-85`：`pollOnce` 逐个 `agent.followup`，但 `onInjected` 只把最后一条写进 `lastDeliveries`（`:135-137`）；`bma3_inbox_ack` 只 ack 最后一条（`:228-249`）。
- `dsh-plugin/lib/inbox.js:77-85`：没有 per-delivery try/catch；某条 `followup` 抛错会让循环中断，但服务端 delivery 已经被消费，留下未 ack 的 open delivery。
- `InboxService.due` 没有 `synchronized`，`InboxHttpServer` 用虚拟线程（`:46-50`），多个会话/重叠定时器可以并发调用 `due`。

**本机复现**：

```text
两个线程同时对同一个 item 调 due
concurrent due duplicated in 281/300 runs

两个线程同时对同一个 source 调 appendOrMerge
concurrent merge lost a line in 48/300 runs
```

前者会生成两条 delivery（都包含同一 item），后者会丢消息行。它们解释了为什么会出现“多个 delivery 逐个注入、只 ack 最后一条”的场景，也说明“只修 lastDeliveries 为列表”还不够，服务端必须先原子化。

**另一个边界**：`due(now, minPriority)` 的 open 检查（`:101-127`）不看 delivery 的优先级；一个低优先级未 ack delivery 会阻塞高 `minPriority` 的 binding，即使新 item 优先级更高。多绑定场景要注意。

**修复边界**：Java + JS 都要改。Java：`due`/`appendOrMerge` 串行化或事务化；`due` 支持按优先级/绑定过滤 open delivery；delivery 记录 item 优先级。JS：保存所有注入过的 delivery，`bma3_inbox_ack` 批量 ack 或接受可选 deliveryId；`followup` 失败要有明确状态，不能静默留下 open delivery。

### P2-8 唤醒不带 source —— 成立

- `dsh-plugin/lib/inbox.js:11-26`：`wakeText` 只有 `inbox=bma3 items=N important=...` + preview + 提示；没有 `source`、thread `lastSeq`、delivery id。
- preview 来自服务端拼接的 item summary，里面只有 `[HH:mm] #localSeq sender：body`，没有 source。Agent 每轮必须 `inbox_list` 才知道是哪个会话。
- `inbox_list` 又返回全量 summary（见 P0-2/额外发现），于是“先 list 再决定”正好撞上上下文膨胀。

**修复边界**：Java 的 `Delivery` 需要带 source(s)/lastSeq 元数据，或至少 `InboxService.due` 把 source 放进 delivery；JS 的 `wakeText` 再渲染。不要靠解析 preview 文本来猜 source。

### P2-9 工具面缺口 —— 成立

- 远端 24 个工具 = 21 个 QQ + 3 个 Inbox；`bma3-webconnect` 未启用时正好是这个数。`QqToolSupport.java:22-46` 列出 21 个 QQ 工具。
- 没有 `qq_media_get`/ASR；入站图片/文件/合并转发没有对应工具。
- `QqConversationSendTool.java:38-42` 的参数只有 `source`、`text`；`QqReplyTool.java:38-43` 只有 `source`、`text`、`replyToMessageId`，没有 mention/at。
- 附带发现：`qq_conversation_send` 没有走 `QqSendSupport` 的 2000 字分片；`qq_reply` 才分片。超长文本用 `qq_conversation_send` 可能被网关拒绝。

### P2-10 日志缺 path/remote —— 成立

- `bma3-agentlib/.../Bma3McpServer.java:281-285`：路径不符只记 `event=mcp_request_rejected reason=unknown_path method=...`，没有 `path` 和 `remote`。
- 同文件 `:287-293` 的 auth denied 反而记了 `remote`。两处不一致，导致无法从日志定位探测源。
- 修复很简单，但要注意不要记录 query（可能含敏感参数）；记录 path + remote + user-agent 即可，必要时对未知路径做限流。

## 3. 额外发现（远端报告未列）

### 3.1 Inbox 并发丢行/重复 delivery

见 P1-7 的复现。`InboxService` 的读-改-写没有锁；`SqliteInboxStore` 的每个方法 `synchronized` 只保证单条 SQL 原子，不保证 service 层组合原子。多账号入站、多个 DSH 会话绑定同一 Inbox、`setInterval` 重叠 tick 都可能触发。

**建议**：`InboxService` 的 `appendOrMerge`/`due`/`ack` 至少在同一把锁内，或改成单写线程/队列；如果要多进程，则必须用 SQLite 事务 + `BEGIN IMMEDIATE`。当前是单进程多线程，锁内串行化足够；注意不要把 HTTP 长操作放进锁。

### 3.2 `qq_account_list.connected` 是“对象存在”，不是“连接可用”

`QqAccountListTool.java:37-41` 的 `connected` 只判断 registry 里有没有 client。`OneBot11Client.onClose` 不把 client 从 registry 移除，所以断线后列表仍显示 `true`。这会让“账号诊断”工具在故障时给出错误结论。修复 P1-6 时应同时改这个字段，并加 `lastError`/`lastConnectedAt`。

### 3.3 `inbox_list` 的“不含正文”是假的

`InboxListTool.java:25` 的 description 写“不含正文”，但 `:34-35` 直接序列化 `service.list()`，`ItemSummary` 含完整 `summary`。这正是 P0-2 把 16KB 灌进上下文的入口。应改成紧凑摘要（source/id/priority/status/时间/首尾片段/字符数/条数），全文另设 `inbox_get` 或走消息板。

### 3.4 没有“丢弃/归档 AVAILABLE item”的工具

`InboxTools` 只有 status/list/ack，`InboxService.ack` 只接受 deliveryId。远端 7 个 AVAILABLE item 还没有 delivery，无法通过现有工具 ack/丢弃；恢复 binding 前想清场，只能改 DB 或新增管理工具。这是 P0-2 修复和事故清理的操作缺口。建议加 `inbox_discard`（按 item id，带审计）或提供一次性迁移脚本；不要默认让 Agent 直接操作 SQLite。

### 3.5 `inbox_list` 包含 UNDELIVERED

`InboxService.list()` 只过滤 ACKED；`UNDELIVERED` 会一直出现。重试耗尽后，这些 item 既不会被 ack，也不会消失，后续每轮 `inbox_list` 都带它们。需要定义 `UNDELIVERED` 的清理/重试策略。

## 4. 运行环境、构建与部署

### 4.1 本机事实

- 仓库：`/home/cna/BotMosire-Alpha3`，分支 `feat/bma3-plan-1`，HEAD `53b13a1`，工作树干净，与 `origin/feat/bma3-plan-1` 同步。
- 本机没有运行中的 bma3；8765/46500 未监听；`/healthz` connection refused；只有 DSH 3080。
- 本机 `run/bma3-live`：`bot.db` 745 条消息、`inbox.db` 1 条 AVAILABLE（revision 2）+ 1 条 ACKED（revision 7）；不是远端 851/58/7 的数据集。
- 本机 `~/.hermes/config.yaml` 仍有旧地址：`mcp_servers.iibot.url = http://127.0.0.1:8765/mcp/bot`、`inbox-mosire = http://127.0.0.1:46501/mcp`；本机 hermes 未运行。远端报告说 hermes-gateway 在跑并每 5 分钟重连旧路径，这与配置残留一致，但 1049 条 WARN 无法在本机复现。

### 4.2 单元/测试基线

| 检查 | 命令 | 结果 |
|---|---|---|
| Java 测试 | `./mvnw -q test` | PASS；Surefire 31 tests / 0 failures / 0 errors / 1 skipped / 16 suites（2026-11-10 20:03） |
| DSH 插件测试 | `cd dsh-plugin && npm test` | PASS；7 tests / 0 fail |
| 离线打包 | `./mvnw -o -q -pl bma3-app -am package -DskipTests` | PASS；`bma3-app/target/bma3-app-0.0.1-hotfix1.jar` 53MB |
| jar 内 AgentLib | `unzip -p ... META-INF/maven/io.mosire/agentlib-mosire/pom.properties` | `version=0.2.5` |

### 4.3 远端“Java 硬阻塞”的判断

远端缺 `mvn` 和 `~/.m2/.../agentlib-mosire/0.2.5` 是事实；但本机两者都有，且离线打包成功。因此不需要先解决 AgentLib 源码或本地仓库才能改 Java：

1. 本机构建 shaded jar，复制到远端替换 release jar；或
2. 把 `~/.m2/repository/io/mosire/agentlib-mosire/0.2.5` 复制到远端，让 `./mvnw` 在远端构建；或
3. 远端安装 Maven 后同样复制本地仓库。

远端源码是 `53b13a1`，与本机 HEAD 一致，jar 可复现。真正需要用户提供的是远端部署路径/权限与替换方式，不是 AgentLib 0.2.5 本身。

### 4.4 systemd `failed` 的原因

`packaging/install.sh:56-61` 生成的 unit 只有 `Restart=always`/`RestartSec=5`，没有 `SuccessExitStatus=143`。JVM 收到 SIGTERM 后走 shutdown hook，但退出码仍是 143；systemd 把它当失败。远端报告的解释成立。修复是 unit 加 `SuccessExitStatus=143`（或 `SIGTERM` 对应的成功码），并在安装脚本/文档同步；这不是 bma3 逻辑崩溃。

## 5. DSH 自定义人设：现状、参考与缺口

### 5.1 当前 BMA3 实现

- `dsh-plugin/lib/persona.js:1-23`：从 row config 取 `prompt`，默认 `你是 BMA3 的角色。`；`systemPrompt.section({ complete: true, interpolate: false })`。`complete: true` 可能整段替换部署侧系统提示词，且没有 `suppressRuntimeContext()`；`persona.apply` 是 profile 级 section，不按 preset/session 过滤。一旦启用，它不是“只给 BMA3 角色会话”的人设，而是可能影响该 profile 所有会话。
- `dsh-plugin/lib/preset.js:1-34`：注册 `id=bma3` 的 agent preset，`plugins` 里只有 persona 行，没有工具组、没有 restrict/guard。
- `dsh-plugin/index.js:5-12`：整个包 `inject=['tools','agents']`；`persona`/`preset` 模块即使只需要 `systemPrompt`/`agentPresets`，也会被 tools/agents 的可用性绑架。
- 本机 profile `~/.dsh/profiles/web/cordis.patch.yml:191-205`：`bma3-persona`、`bma3-preset` 都是 `disabled: true`；实际只启用了 `bma3-inbox`。

**与 spec 的差距**：foundation spec §8（`docs/superpowers/specs/2026-11-09-bma3-foundation-design.md:80-84`）明确要求：

- `persona`：角色系统提示词 / `PERSONA.md` 注入 / 压缩重锚；
- `preset`：工具组预设。

当前只做了“静态 prompt + 空 preset”，`PERSONA.md` 注入、压缩重锚、工具组都没有。D-003 也要求三个独立 module/row、配置/失败域分开；当前顶层 `inject` 与空 preset 没有完全做到。

### 5.2 iibot 的参考实现

只读参考：`/home/cna/DevMosire-iiBot/iiBotMosire-DSH/packages/iibot-dsh/lib/persona/`

- `persona.js`：按 session cwd 读 `PERSONA.md`，算 SHA-256；构造 `<persona-contract>` 的 user message；缺失/空文件构造 operator notice；用 `surfaceHasContract` 判断当前 surface 是否已有同 hash 契约。
- `index.js`：在 `agent/pre-step` 注入契约或缺失通知；压缩后 surface 丢了契约会重注；文件改动 hash 变化会重注；`tools.guard` 拒绝子 Agent 的 `qq_*`/`inbox_*`；缺 `PERSONA.md` 时拒绝 `qq_conversation_send`。
- `custom-persona.js`：父 Agent 提供的完整系统提示词作为 `complete: true`、`interpolate: false` 的 section，并 `suppressRuntimeContext()`。
- `custom-restrict.js`：子 Agent 只允许 `read`/`read_image`/`bash`；优先 `tools.restrict`，失败回退 guard allowlist。
- `custom-guard.js`：子 Agent 拒绝 `qq_*`/`inbox_*`。
- `custom-subagent.js`：`subagent_custom` 工具，父 Agent 传 `system_prompt`，生成一次性 preset（custom-persona + 最小工具组 + guard/restrict），子 Agent 不能再创建子 Agent。

这套实现解决的是“人设契约的可见性/抗压缩/抗群聊改写”和“自定义子 Agent 的系统提示词 + 工具暴露面”，不等于“给主 Agent 按 QQ source 切换人设/工具”。BMA3 要先定作用域，不能直接照搬。

### 5.3 DSH 0.2.2-rc.1 的可用接口

- `ctx.tools.restrict({ allow, deny })`：按 agent scope 限制全局工具；空 filter、未知名字会失败；限制在 dispose 时解除。
- `ctx.tools.guard(fn)`：单调拒绝；返回理由即拒绝，后续监听器不能改回允许。
- `systemPrompt.section({ complete, interpolate })` 与 `suppressRuntimeContext()`：iibot 已在同一 DSH 线上使用，BMA3 可直接复用。
- `agents`/`agentPresets`/`subagents`：preset 注册与子 Agent 启动。

### 5.4 需要重新设计的问题（待拍板）

1. **作用域**：人设属于 session、agent preset，还是 QQ source？DSH 的 `tools.restrict` 是 agent scope；如果同一个 Agent 同时处理多个群，想按群限制工具，需要额外的分发层，不能只靠 preset。
2. **提示词来源**：row config、`PERSONA.md`，还是工具运行时设置？iibot 用 `PERSONA.md` + hash；BMA3 是否沿用文件，还是做 `persona_*` 工具/数据库？
3. **工具暴露面**：允许列表、拒绝列表，还是按“角色模板”预置？MCP 工具是全局注册的，`restrict` 能挡住 schema 和调用，但需要确认 DSH 的 scope 语义与 preset 加载顺序。
4. **安全边界**：群消息不能修改人设；缺人设时是否禁止外发；子 Agent 是否一律禁 `qq_*`/`inbox_*`；工具暴露面是否包含 `qq_conversation_send` 这类高危工具。
5. **可观测性**：绑定/恢复/失败要有状态；人设 hash、重锚次数、被 guard 拒绝的调用要可查。
6. **与 Inbox 的关系**：wake 注入时是否带 persona 契约？如果人设按 source 切换，wake 必须告诉 Agent 当前 source，否则无法选人设（与 P2-8 相关）。

**结论**：这是新设计，不是把 iibot 文件复制过来。按 AGENTS.md，先写 spec（不变量 + 验收判据），再实现；不要和 P0 修复混在一个提交里。

## 6. 建议修复顺序（待用户拍板）

**不要直接“先恢复 binding”**：7 个巨型 AVAILABLE item 会在恢复瞬间进入 due，`inbox_list` 仍会返回全量 16KB。若只能改插件、不能马上换 Java jar，至少先备份并清理/归档这 7 个 item（当前没有 item 级 discard 工具，需要 DB 脚本或新增管理工具），再恢复 binding。

推荐顺序：

1. **Java Inbox 正确性（P0-2 + P1-7 并发 + `inbox_list` 紧凑化）**：先加合并上限/窗口，`due`/`appendOrMerge` 串行化，`inbox_list` 不返回全文；补测试；本机构建新 jar 部署。这样恢复 binding 不会放量。
2. **DSH 插件 binding 持久化 + ack 全部 + wake 带 source + status 可观测**：不依赖 Java 重编；持久化 binding/last delivery，批量 ack，记录 `lastPollAt`/`lastError`，`wakeText` 带 source。注意 token 不落盘。
3. **Java QQ 层（P1-3/4/5/6）**：信号权重 + per-source 阈值、segment 渲染、图片占位/媒体表、WS 重连/readiness。可以拆成两批：先渲染/图片（低风险），再信号/重连（行为变化大）。
4. **P2**：`qq_media_get`/ASR、mention/at、`unknown_path` 日志、hermes 旧配置清理。
5. **人设**：单独 spec；先定作用域/提示词来源/工具暴露面/安全边界，再实现。不要和上述修复混在一起。

如果用户只想先做一件、且远端暂时不能换 jar：**先做插件持久化 + 旧 item 清场**，但这是止血，不是完整修复；P0-2/P1-7 仍在。

## 7. 验证记录

| 检查 | 命令/方法 | 结果 |
|---|---|---|
| Java 测试 | `./mvnw -q test` | rc=0；31 tests / 0 fail / 0 err / 1 skipped |
| 插件测试 | `cd dsh-plugin && npm test` | rc=0；7 tests / 0 fail |
| 离线打包 | `./mvnw -o -q -pl bma3-app -am package -DskipTests` | rc=0；53MB jar；AgentLib 0.2.5 |
| 合并上限复现 | 临时 `Repro.java`：500 次 `appendOrMerge` | revision=500，19,783 chars，due 一次全出 |
| 并发 due 复现 | 临时 `ConcurrentRepro.java`：2 线程 × 300 轮 | 281/300 轮重复 delivery |
| 并发 merge 复现 | 同上：2 线程 × 300 轮 | 48/300 轮丢行 |
| 渲染复现 | 反射调用 `Bma3App.renderLine` | 输出只剩文本，@/reply/image 丢失 |
| 本地 DB 图片 URL | Python sqlite3 查询 `run/bma3-live/data/bot.db` | 105 行 / 25,625 chars / 5 sources |
| 本地 binding 状态 | `bma3_inbox_status` | `bound=false`, `bindings=[]` |
| 远端运行事实 | 远端报告转述 | NOT_RUN（本机无 `/home/ubuntu`，无远端访问） |
| 真人 QQ/DSH/systemd 联调 | — | NOT_RUN |

## 8. 证据索引

- 插件 binding/ack/wake：`dsh-plugin/lib/inbox.js:11-26, 77-85, 101-146, 228-287`
- Inbox 合并/due/ack：`bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/InboxService.java:39-57, 94-182`
- Inbox HTTP 并发：`bma3-inbox/src/main/java/io/mosire/bma3/inbox/http/InboxHttpServer.java:46-50`
- Inbox list：`bma3-inbox/src/main/java/io/mosire/bma3/inbox/tools/InboxListTool.java:24-35`
- ingest/渲染：`bma3-app/src/main/java/io/mosire/bma3/app/Bma3App.java:159-237`
- OneBot codec：`bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/onebot/OneBot11Codec.java:47-68`
- OneBot client：`bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/onebot/OneBot11Client.java:43-59, 90-137`
- Account registry：`bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/account/AccountRegistry.java:41-99, 117-133`
- 账号列表/状态：`bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/tools/QqAccountListTool.java:34-42`、`QqAccountStatusTool.java:33-36`
- 消息板正文拼接：`bma3-core/src/main/java/io/mosire/bma3/core/store/SqliteMessageBoardStore.java:85-112`
- health：`bma3-app/src/main/java/io/mosire/bma3/app/HealthServer.java:15-27`
- MCP 未知路径日志：`bma3-agentlib/src/main/java/io/mosire/bma3/agentlib/Bma3McpServer.java:279-307`
- systemd unit：`packaging/install.sh:56-61`
- 基础 spec：`docs/superpowers/specs/2026-11-09-bma3-foundation-design.md:55-85`
- 人设参考：`/home/cna/DevMosire-iiBot/iiBotMosire-DSH/packages/iibot-dsh/lib/persona/{persona,index,preset,custom-persona,custom-restrict,custom-guard,custom-subagent}.js`
