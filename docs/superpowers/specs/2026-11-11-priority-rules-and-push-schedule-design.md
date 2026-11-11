# 优先级规则 / 推送时序 / 配置写入口设计（0.0.3 第一批）

> **状态**：已实现（2026-11-11）· **类型**：项目设计（专题）
> **需求原话**：见 [`../DESIGN_RULINGS.md`](../DESIGN_RULINGS.md) D-017..D-028（逐字）
> **取代**：`2026-11-10-inbox-panel-sse-design.md` §8（优先级公式）、§9 之外的推送时机；不取代 D-009/D-010（N/M 截断）与 D-011（Inbox 不带 URL）
> **本批未做**：MCP ImageContent 图片通道（D-035 明确先走 `read_image`）；真实 LLBot/DSH 联调 —— 见 [`../plans/2026-11-11-0.0.3-plan.md`](../plans/2026-11-11-0.0.3-plan.md)

## 1. 一句话模型

一条消息的**分数** = 最具体的来源规则值 + 所有命中的属性加分。
分数只决定三件事：**丢不丢**（`< dropBelow`）、**多快推**（`≥ immediateThreshold` 走 1s，否则 30s 批）、**排序**。
分数只有一处真源：`config/app.yaml`；只有一个写入口：`set_config`。

## 2. 为什么推倒重来

旧模型（`PriorityResolver` 的 `max(base, sourceWeight, notePriority, base+boost) >= notifyThreshold`）把三类互不相容的东西压到一根数轴：

| 输入 | 人想表达 | 代码实际效果 |
|---|---|---|
| `platformWeights` | 这个平台值多少分 | 与阈值比较 ⇒ 隐式"要不要唤醒" |
| `qq_source_weight.weight` | 这个群/人值多少分 | 同上，且**会被 `notifyThreshold` 默认 200 静默覆盖** |
| `qq_user_note.priority` | 这个人我在意 | 300 ⇒ 永久唤醒（操作者被自己坑到的直接原因） |
| `signals` | 这条消息对我做了动作 | 只在 `base + boost` 上生效，与上面三项互斥 |

同时 QQ 侧的 `reply`/`name` 恒为 `false`（因为没有出站平台 id 索引、没有 bot 昵称），真正在破群静音的是 `mention` 把 `@全体成员` 也算进来、`command` 只要以 `/` 开头就算。

## 3. 数据模型（本批）

### 3.1 消息板（`bma3-core`）

`chat_message` 新增列（additive migration，老行为 NULL）：

| 列 | 含义 |
|---|---|
| `sender_id` / `sender_name` | 入站是发言人；出站是 bot 账号身份（`get_login_info` 学到或配置覆盖） |
| `segments_json` | 结构化段落（`Segment(type, text, data)`）；`text` 列仍是**不含 URL** 的扁平渲染 |
| `external_message_id` | 平台消息 id；入站写入时即有，出站发送成功后才回写 |

`Message(localSeq, inbound, externalMessageId)` 提供 `self()`（`direction == OUTBOUND`）。
反查 `findByExternalMessageId(source, id)` 是"被引用的消息是不是我发的"的唯一依据。

### 3.2 段落（`Segment`）

```
Segment(type, text, data)
```

| 类型 | `data` 白名单 |
|---|---|
| `text` | `text` |
| `at` | `qq`, `name` |
| `reply` | `id` |
| `image` | `file`, `url`, `sub_type`, `summary`, `file_size`, `width`, `height` |
| `file`/`record`/`video` | `file`, `url`, `name`, `size` |
| `forward` | `id`, `count`, `title` |

不变量：`chat_message.text` 与 Inbox 面板**永远不含 URL/rkey**；`data` 只在工具显式 `includeSegments=true` 时返回。

## 4. 优先级解析（`bma3-app/priority` + `bma3-core/priority`）

### 4.1 键空间只有两级

adapter 把板地址投影成配置键（`SourceKeyer`）：

| 板地址 | 配置键 |
|---|---|
| `qq_group/<accountId>/955432692` | `qq_group/955432692` |
| `qq_private/<accountId>/132843233` | `qq_private/132843233` |

`accountId` 是内部路由细节，**不进配置键**。

### 4.2 匹配规则（`PriorityRules`）

- 规则键是配置键的合法前缀：`*` → `qq_group` → `qq_group/955432692`；
- **命中段数最多者胜**，同段数比字面长度，再比字典序（保证确定性）；
- `*` 命中段数为 0，永远垫底。

### 4.3 公式

```
base   = 最具体来源规则的 value
delta  = Σ weighting.attributes[命中的事件属性]
       + Σ weighting.attributes[qq_sender/<qq> 且等于发送人]
       + Σ weighting.attributes[qq_name/<子串> 且发送人名字包含]
total  = min(base + delta, maxPriority)
dropped = total < dropBelow          // 不注入、不落库
immediate = total >= immediateThreshold
```

**加分是加法**，不是取 max：`qq_private`(300) + `qq_sender/<qq>`(+50) = 350。

### 4.4 QQ 属性（`QqSignalExtractor`）

| 属性 | 触发条件 |
|---|---|
| `qq_at_bot` | 有 `at` 段且目标是自己（`self_id` 或配置 `self_qq`） |
| `qq_at_all` | `at` 段目标为 `all` |
| `qq_at_other` | `at` 段目标既非自己也不是 all |
| `qq_reply_to_bot` | 被引用的平台 id 命中本账号发出的 `external_message_id` |
| `qq_self_name` | 文本包含 bot 自己的昵称（`selfName`，配置或 `get_login_info`） |
| `qq_command` | 文本以 `/` 或 `!` 开头，且**私聊或 at-bot** |

### 4.5 决策可观测

`PriorityDecision(priority, dropped, immediate, reasons)`；`reasons` 是完整算式，例如

```
source=qq_group/955432692 → qq_group 200
qq_at_bot +100
total 300 ≥ immediateThreshold 300 ⇒ 立即推送
```

## 5. 推送时序（`bma3-inbox`）

### 5.1 状态

- 每个 item 有 `revision`；服务端内存记 `pushedItems[itemId] = 已推送过的 revision`；
- "pending" = 未 ACK、`eventRevision > ackedRevision`、且 `pushedItems[id] != revision`；
- `dueAt(item) = item.updatedAt + (priority >= importantThreshold ? importantDelay : normalDelay)`。

### 5.2 规则

1. 有 pending 时，取**最早**的 `dueAt` 作为批次时刻；
2. 到点就把**当前全部 pending** 一起 flush（刚落 1 秒的低优先项也搭车）；
3. 新到的低优先项**不重置**时钟（锚点是最早那条）；
4. flush 后这些 item 不再重复推送，直到被 ACK 或有新内容（revision 变化）；
5. 无 pending 时 `awaitPush` 一直等到超时（SSE 发心跳）；
6. 新连接/重连先收一次当前面板快照（保证断线不丢状态），之后只收 flush 事件。

### 5.3 SSE

- 事件 id 仍是 `panel.revision`（客户端按 revision 去重，插件无需改动）；
- `pull` 语义不变（`GET /panel` 返回实时全量未 ack 视图，工具侧不受延迟影响）。

## 6. 媒体查看（D-019）

### 6.1 fileId

`MediaId` = `source + messageId + segmentIndex` 的 base64url 编码，形如 `file-…`。
不建新表：消息板里那三样就是全部依据。它同时是安全边界 —— 查看工具**只接受 fileId**，
不接受 URL，所以"看媒体"不会退化成任意 URL 抓取代理。

读取工具在 `includeSegments=true` 时对媒体段输出 `fileId`；`messageId` 取平台 id
（`external_message_id`，缺省回退板内 id）。

### 6.2 解析顺序

1. 用平台 id 在消息板找到那条消息、取第 `index` 段；
2. 刷新链接：`get_msg` → 该段 `data.url`；失败再 `get_image {file}`；再失败回退库里存的 url
   （QQ 的 CDN 链接带 rkey，会过期，所以**优先问平台要新的**）；
3. 下载：硬上限 `maxDownloadBytes`，超限直接 `MEDIA_TOO_LARGE`，不写盘；
4. 文本（`text/*`、json/xml，或能按 UTF-8 解码且无 NUL）且 ≤ `maxInlineBytes` ⇒ 内联返回；
5. 其余（含图片）⇒ 写到 `<media.dir>`（默认 `<dataDir>/downloads`），返回本地 `path`，
   图片附 `hint: 用 read_image 打开`。

### 6.3 配置

```yaml
qqconnect:
  media:
    dir: ""                  # 空 = <dataDir>/downloads
    maxInlineBytes: 65536
    maxDownloadBytes: 33554432
    timeoutSeconds: 20
```

### 6.4 不变量

- 工具入参没有 URL；文件名由 `MediaId` 哈希生成，不使用平台提供的字符串；
- URL/rkey 不进日志、不进 Inbox、不进 `chat_message.text`；
- 超过下载上限失败而不是截断（不静默产出半个文件）。

## 7. Inbox 工具面与逐条 ack（D-020 / D-029 / D-030 / D-031 / D-032）

### 7.1 工具

| 工具 | 作用 |
|---|---|
| `inbox_status` | 计数与 revision |
| `inbox_list` | 面板（top M，含 entries） |
| `inbox_items` | **全部**未 ack 条目的紧凑列表（index/id/source/priority/status/条数/一行预览），能看见 M 之外 |
| `inbox_get(itemId)` | 单个条目的完整窗口（≤N 条 entries + 计数 + revision）；ACKED 也能取 |
| `inbox_ack` | 逐条 ack：`itemIds[]` / `indices[]` / `rangeFrom+rangeTo` / 不带参数=全部可见；可带 `revision` |
| `inbox_wait(timeoutMs)` | 阻塞到下一次 flush；**只读不 ack**；超时 `changed:false` |

### 7.2 逐条 ack

- `ItemStatus.ACKED` 是可见性的唯一开关；`ackedRevision` 降级为观测值；
- 面板序号是 1-based 且**只在同一 revision 内有效**：`inbox_ack(indices, revision)` 若 revision 已变则 `REVISION_CONFLICT`，避免"按旧序号 ack 到新条目"；
- 插件仍可 `POST /ack {revision}`（注入后整体确认）；HTTP 也支持 `{itemIds:[…]}` / `{indices:[…]}`。

### 7.3 保留期（D-031）

`inbox.retention.maxAgeDays`（默认 30）/ `maxItems`（默认 5000）：启动与每 100 次 append 清理一次，
先按时间清 ACKED，再按数量从最老的 ACKED 开始删。**未 ack 的条目永不清理。**

### 7.4 注入筛选与时钟重置（D-029）

- `inbox.push.minPriority`：低于该分数的条目**仍进面板与工具**，但永不唤醒；默认 -1000（不过滤）。
- 每一轮 flush 后记录 `lastFlushAt`；新一轮的到期时间按 `max(item.updatedAt, lastFlushAt) + delay(priority)` 计算 —— 即"推完一轮重排重算"。

## 8. 诊断（D-033 / D-034）

### 8.1 OneBot 连接

`OneBotDiagnostics` 把握手失败映射成可执行提示：

| 现象 | hint |
|---|---|
| HTTP 426 | 该地址像 HTTP API 端口；BMA3 只支持正向 WS（`ws://`/`wss://`） |
| 401 / 403 | token 与 OneBot 配置不一致 |
| 404 | path 不对（正向 WS 常见 `/ws` 或 `/`） |
| 400 | 不是 OneBot 11 正向 WS |
| ConnectException | 服务没起 / 端口不对 / 被防火墙挡 |
| UnknownHost | DNS |
| SSLException | wss 证书/端口不匹配 |

握手失败后还会**主动探测**同 host:port 的普通 HTTP（D-033 授权），返回码会追加到 hint 里
（"该端口是 HTTP 服务，不是 WS 端点"）。结果进 `AccountRegistry.Fault`，
由 `qq_account_list.lastError{phase,httpStatus,hint,at}` 暴露；`connected` 改为真实 socket 状态。

### 8.2 MCP 401

```json
{"error":"unauthorized","errorCode":"MCP_UNAUTHORIZED",
 "reason":"missing_authorization_header|token_mismatch",
 "headerPresent":false,
 "hint":"需要 Authorization: Bearer <token>；token 值来自 BMA3_MCP_TOKEN，变量名不是 token 本身"}
```

只出现环境变量名，日志升 ERROR。

## 9. 配置


```yaml
weighting:
  immediateThreshold: 300     # ≥ 即 1s 推
  dropBelow: -300             # < 即丢弃（不落库）
  maxPriority: 1000           # 加分封顶
  importantDelaySeconds: 1
  normalDelaySeconds: 30
  sourcePriority:
    "*": 0
    qq_group: 200
    qq_private: 300
    # qq_group/955432692: 350        # 更具体者胜
  attributes:
    qq_at_bot: 100
    qq_at_all: 20
    qq_at_other: 0
    qq_reply_to_bot: 100
    qq_self_name: 60
    qq_command: 100
    # qq_sender/132843233: 50
    # qq_name/管理: 40

qqconnect:
  accounts:
    - accountId: bot-1
      selfQq: "10001"      # 可省：启动时 get_login_info 自动取
      selfName: "BotMosire"
```

## 10. `set_config` / `get_config`

- `set_config { patch: {点分路径: 值}, unset: [路径] }`；
- 写入前把候选树序列化到临时目录并用 `ConfigLoader` 完整校验 —— **非法配置不落盘**；
- 校验通过后 `ATOMIC_MOVE` 覆盖 `config/app.yaml`，等 `ConfigWatcher` 采纳（最多 3s），返回 `applied/reloaded/effective`；
- **无白名单**（D-028）：端口/host 这类非热重载项写进去不会生效，返回 `reloaded:false` 并提示需重启；
- `get_config` 只返回非敏感值（token 只以环境变量名出现）。

## 11. 删除清单（D-036）

为减少重复面，以下已删除且不再提供等价入口：

| 删除 | 替代 |
|---|---|
| `Delivery` / `DeliveryState` / `DeliveryAck` / `InboxService.due()/ack(deliveryId)` / `inbox_delivery` 表 / `retryTtl`+`maxAttempts` | `awaitPush` + 逐条 ack |
| `POST /deliveries/due`、`POST /deliveries/{id}/ack` | `POST /ack {revision\|itemIds\|indices}` |
| `inbox_ack(deliveryId)` | `inbox_ack(itemIds/indices/range)` |
| `qq_recent_messages` | `qq_thread_list` + `qq_conversation_history` |
| `qq_account_login_info` | `qq_account_list.self`（连接时 `get_login_info` 自动学习） |
| `qq_user_note_get` | `qq_user_note_list` |

## 12. 不变量

- **推送是游标，不是竞态**：`pushRevision` 是最新一次 flush 的编号；`awaitPush(lastPush, …)` 在入口先检查
  `pushRevision > lastPush` 并立即返回，因此"某个读者慢/断开"不会让这一轮推送从在线读者眼前消失。
- **慢路径不持锁**：`AccountRegistry.reconcile()` 的连接/探测在锁外完成；入站只用并发 map 读身份。
- **只能有一个 complete 段**：DSH 的 `systemPrompt` 规定「多于一个有效 complete 段 ⇒ 组装失败」。
  因此 legacy `persona` 模块（`bma3-persona`，且丢弃了 section disposer）已删除；`self` 只注册
  `deployment:persona-prefix` 一个 complete 段，并在 agent scope 注册同名空段 `bma3-persona`
  遮蔽旧进程残留的 global complete 段（热重载即恢复，无需重启）。
- **人设即 system prompt**（D-037）：`<workspace>/systemprompt.md` 整份替换 system prompt（`complete` 默认 `true`），插件不追加任何内置台词或安全约束；文件缺失/为空则不挂 section；只有显式 `complete:false` 才叠加。
- **推送窗口即交付边界**：一次 flush 只把 top-`maxItemsPerPanel` 的条目标记为已推送；排名在窗口外的 pending 条目**不算 due**（等 ack 腾出槽位），既不会被标记已推送，也不会让 flush 空转。
- **ack 只确认真正展示过的**：`POST /ack {revision}` 只 ack 当前处于 delivered 状态（`pushedItems[id] == revision`）且 `eventRevision <= revision` 的条目；插件优先按 `itemIds` 精确确认。
- **批次锚点 = 该条目首次欠推送的时刻**（`pendingSince`），不是它最后一条消息的 `updatedAt`。

1. 分数只有一处真源（`config/app.yaml`），只有一个写入口（`set_config`）。
2. `qq_source_weight` 表、`qq_user_note.priority`、`qq_source_weight_*` 三工具**不存在**。
3. `< dropBelow` 的消息**不落消息板**。
4. Inbox/SSE/`chat_message.text` 不含 URL/rkey；`segments` 只在显式请求时返回。
5. 延迟只影响"推送/唤醒"，不影响 `GET /panel` 与工具的实时可见性。
6. `broadcast` 批次的锚点是最早的 pending 项，后到者不重置。
7. 出站消息只有发送成功后才有 `external_message_id`；`UNKNOWN` 不重发。

## 13. 验收判据（本批已覆盖）

| # | 判据 | 证据 |
|---|---|---|
| 1 | 私聊 300 ⇒ 立即；群普通 200 ⇒ 等批 | `PriorityResolverTest.privateMessageIsImmediate` / `ordinaryGroupMessageWaitsForTheBatch` |
| 2 | 群 @bot 300 ⇒ 立即；@全体 220 ⇒ 等批 | `atBotCrossesTheImmediateLineButAtAllDoesNot` |
| 3 | `qq_sender` 与来源分数**叠加** | `senderAndSourceRulesStack` |
| 4 | 最具体的来源规则胜 | `theMostSpecificSourceRuleWins` |
| 5 | `< dropBelow` 丢弃 | `scoreBelowDropIsDroppedBeforeItIsStored` |
| 6 | 低于丢线时消息板也没有该行 | `Bma3AppFakeQqTest.aMessageBelowDropLineIsDiscardedBeforeItIsStored` |
| 7 | 低优先项等到批次；立即项一次带走全部 pending；后到不重置；不重复推 | `InboxPushScheduleTest`（5 例） |
| 8 | `set_config` 生效并热重载；非法 patch 不动文件 | `SetConfigToolTest`（3 例） |
| 9 | 出站发送成功后 `externalMessageId`/`self` 可读 | `QqToolsTest` |
| 10 | 图片 URL 只在 `includeSegments` 时出现，正文仍 `[图片]` | `OneBot11CodecTest.keepsStructuredPayloadForMediaAndRelations`、`QqToolsTest` |
| 11 | 图片先刷新链接再落盘、文本内联、超限失败、坏 fileId 不发网络请求 | `QqMediaViewToolTest`（4 例） |
| 12 | `inbox_items` 能看到 M 之外；`inbox_get` 取回条目；逐条 ack + 过期 revision 拒绝；wait 超时不 ack | `InboxToolsTest`（4 例） |
| 13 | 推完一轮重置时钟；低于注入筛选线只可见不唤醒 | `InboxPushScheduleTest`（7 例） |
| 14 | 426/401/404/拒绝/DNS 的 hint；HTTP 探测能确认「这是 HTTP 端口」 | `OneBotDiagnosticsTest`（5 例） |
| 15 | MCP 401 响应体自带 errorCode/reason/hint 且不含 token | `Bma3McpServerTest` |
| 16 | 删除 delivery 路径与三个重复工具后全量仍 PASS | `./mvnw -q test` 76 tests / 1 skipped；`dsh-plugin npm test` 14 |
| 17 | **本地真进程 e2e**（假 OneBot + 真 MCP/HTTP）：三档时序、合并带走、逐条 ack、`set_config` 热重载、丢弃线、媒体下载、诊断 hint、read/ack 游标、引用回写平台 id | `tools/e2e-local/` 四套驱动 **27/27 PASS × 两轮可重复**（2026-11-11） |

## 14. 风险 / 未验证

- **真实 LLBot/DSH 联调 `NOT_RUN`**：`get_login_info`、`external_message_id` 回写、SSE 推送节奏都只有 fake/回环证据。
- **e2e 抓到并修掉的四条**（2026-11-11）：同一来源连发把批次时钟后推；连不上的账号从 `qq_account_list` 消失；flush 被旧连接抢走导致在线客户端漏收；热重载持锁卡住入站。
- **`set_config` 会重写整份 YAML**：注释与键顺序会丢（Jackson 序列化），值不变。
- **重启后推送状态丢失**：`pushedItems` 是内存表，重启后所有未 ack item 会重新进入 pending（按 `updatedAt + delay` 计算，通常立即 flush 一次）。这是有意的"宁重推不漏推"，但需真实环境观察。
- **加分叠加可能超预期**：`@bot + 引用 + 喊名字` 会累加；`maxPriority` 只封顶不降噪，最终靠 `dropBelow`/权重。
- **`self_name` 子串匹配**误命中率高（"小博"、"bot"），默认 `+60` 刻意压在立即线以下。
