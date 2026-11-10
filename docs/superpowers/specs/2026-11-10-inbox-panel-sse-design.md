# Inbox 总面板 / SSE / 通用来源与优先级设计

> **状态**：已确认（2026-11-10）
> **需求来源**：本会话用户消息（逐字见 §1 与 `docs/superpowers/DESIGN_RULINGS.md` D-009..D-016）
> **取代**：`2026-11-09-bma3-foundation-design.md` §8 中 persona 之外的 Inbox 展示/订阅细节；不取代 Inbox 状态机与消息板职责
> **类型**：项目设计（专题）

## 1. 需求原话

1. 「对于单次inbox注入的单个显示条目，类似于QQ客户端的单个窗口（如图1），应当只显示大约4~8条最后消息；对于显示条目本身，应当只显示优先级最高的前4~8个条目，不管是多少条消息还是多少个条目，都应该做进配置文件方便配置和热重载」
2. 「但是对于已经注入的条目中消息，消息来源、消息本身的自带关系（是否带有图片？是否带有引用关系？是否是嵌套聊天记录？不用完全展示，但是诸如引用等，概要是肯定要的），应当标清楚，实在无法在inbox中标清楚则也不需要上额外的提示词引导Agent去看history，Agent很聪明，不要浪费token」
3. 「一个inbox总面板一个sse，其中的”窗口“事实上就是不同来源的inbox条目，它不需要被额外监听、订阅、维护，只需要等qqbot后端推送过来然后被动地添加就行，在查看上进行进一步限制——inbox总面板被推送的作用就是提示Agent干活，它不需要额外承担更多内容」
4. 「消息发送tool等Agent自己写自己发的tool，硬限制应当是可配置的，默认设3000字因为这是qq的单条消息限制」
5. 「来源字段的通用性，今天有qqconnect维护的qq来源（qqgroup/xxx/xxx、qqprivate/xxx），明天可能还有bilibili/xxx、wechat/xxx，这样的字段通用识别能实现吗？需要加一个简单的util方便inbox读取吗」
6. 「各类消息来源的通用优先级适配也一并整合」

> 第 5 条的口头拼写是 `qqgroup/qqprivate`；2026-11-10 用户确认保留现有 `qq_group/qq_private`，新平台使用 `bilibili/...`、`wechat/...`。

## 2. 目标 / 非目标

### 2.1 目标

1. 一个 inbox 只有一个总面板、一条 SSE 事件流；面板里的条目按来源自然聚合。
2. 条目被动接收 qqbot/其他 adapter 的 push，不建立 per-source 订阅、lease 或后台监听。
3. 查看限制：每个条目最多 N 条最近消息，每次面板最多 M 个条目；N/M 可配置、可热重载。
4. 条目渲染带来源、发送者、时间/序号，以及图片/引用/合并转发/@ 等关系摘要；不附加“去看 history”的提示词。
5. 通用来源：`platform/...` 只要求平台段通用；显示名由 adapter 的 `SourceLabeler` 提供，Inbox 不做平台分支。
6. 通用优先级：平台默认权重、来源覆盖、用户备注、信号权重、per-source 阈值统一由一个 resolver 计算，QQ 只是第一个 adapter。
7. 发送硬限：所有文本发送出口共享 `maxTextChars`（默认 3000，可配置），按 code point 计数。
8. 面板 SSE 有明确生命周期：连接、snapshot、panel 事件、Last-Event-ID、心跳、重连、关闭；ack 是面板读游标，不阻塞后续事件。

### 2.2 非目标

- 不做 per-source SSE / per-source subscription / per-source lease。
- 不在 Inbox 复制完整消息历史；完整事实仍在消息板。
- 不在本轮实现 persona（用户明确暂缓）。
- 不实现 B 站/微信 adapter 本身；只保证 source/priority/render 接口可扩展。
- 不实现 reply-to-bot / name 信号的全量检测；接口留位，QQ 首版先做 mention/command。

## 3. 运行模型

```text
QQ adapter / future adapter
        |
        v  AppendItem(SourceView, InboxEntry, priority)
  InboxService (one aggregate panel)
        |-- bounded item window (last N entries)
        |-- panel view (top M items)
        |-- panel revision + acked revision
        |-- SSE clients: snapshot / panel / heartbeat
        v
  DSH plugin -> agent.followup(panel text) -> Agent
```

- adapter 负责把平台事件变成 `InboxEntry`（已渲染的来源、关系、正文）；
- Inbox 只负责聚合、截断、排序、revision、SSE；
- DSH 插件只负责连接、注入、游标/状态；不做平台分支。

## 4. 数据模型

### 4.1 SourceAddress / SourceView

- `SourceAddress` 保持 `platform/segment...` 的 opaque 值；`platform()`、`segments()`、`format()` 通用；`accountId()` 仅对 account-bound 平台有意义。
- `SourceView(raw, platform, accountId, route, label)` 由 adapter 在 append 时提供；`label` 是人类可读来源名（如 `QQ群 955432692`）。
- `SourceLabeler` 按 platform 选择；未知平台回退 `platform · 最后一段`，不失败。
- Inbox 只按 `SourceView.raw()` 聚合，只显示 `label`；不得出现 `qq_group`/`bilibili` 的 switch。

### 4.2 InboxEntry

```java
record InboxEntry(
    long localSeq,
    Instant at,
    String sender,
    String text,
    List<String> markers)
```

- `markers` 已渲染为短标记，例如 `[@3924945183]`、`[引用 #42 Alice]`、`[图片]`、`[合并转发 5 条]`。
- `text` 只放文本段；图片 URL、rkey、完整转发内容不得进入。
- 渲染行由 `InboxEntry.render()` 统一生成，格式见 §6。

### 4.3 InboxItem / InboxPanel

- `InboxItem` 保留 `summary`（兼容旧 due/delivery），新增：
  - `sourceLabel`、`entries`（最近 N 条）、`totalMessages`、`updatedAt`、`eventRevision`。
  - `summary` 永远等于窗口内 entries 的渲染结果，不得无界增长。
- `InboxPanel`：
  - `inboxId`、`revision`、`generatedAt`、`items`（top M）、`totalItems`、`omittedItems`、`ackedRevision`、`includeOmittedCount`。
- `PanelItem`：`source`、`sourceLabel`、`priority`、`entries`、`totalMessages`、`omittedMessages`、`lastAt`。
- `panel()` 过滤 `eventRevision > ackedRevision` 的 item，按 priority desc、lastAt desc 取 M。
- 旧 `Delivery`/`due` 保留为兼容路径，但不再是面板主路径；新代码不得依赖 delivery 阻塞。

## 5. 配置与热重载

```yaml
inbox:
  inboxId: bma3
  httpPort: 46500
  display:
    maxMessagesPerItem: 6   # 4..8
    maxItemsPerPanel: 6     # 4..8
    includeOmittedCount: true

qqconnect:
  send:
    maxTextChars: 3000      # 1..3000
    overflow: reject        # reject | split

weighting:
  privateSourceWeight: 300
  groupSourceWeight: 0
  notifyThreshold: 200
  normalDelaySeconds: 30
  importantDelaySeconds: 1
  platformWeights:
    qq_private: 300
    qq_group: 0
    bilibili: 0
    wechat: 0
  signals:
    mention: 300
    reply: 300
    name: 200
    command: 300
```

- 配置校验范围：N/M 4..8；`maxTextChars` 1..3000；overflow 枚举。
- `ConfigWatcher` 在 QQ reconcile 之外更新 `AtomicReference<RuntimePolicy>`；非法配置保留 last-known-good。
- 热重载只影响下一次 append/panel/发送，不重写已注入或已发送的内容。
- `inbox.httpPort`/MCP 端口仍是启动参数，不在热重载范围。

## 6. 渲染格式

单个面板：

```text
[INBOX_PANEL] inbox=bma3 rev=42 unread=3 items=2/7
[QQ群 955432692] priority=300 total=128 shown=6
  [20:08] #101 LvtN：欢迎回来？ [@3924945183]
  [20:07] #100 张三：这个是吗 [引用 #98 李四] [图片]
  ...
[QQ私聊 3668539114] priority=300 total=2 shown=2
  [19:55] #99 某人：在吗
…（其余 5 个来源未显示）
```

- 行内只放事实和关系标记，不加“去查 history/完整内容在服务端”的提示。
- 面板末尾只放必要的省略计数；不写工具指引。
- 图片 URL、rkey、完整合并转发内容不得出现。

### 6.1 当前实现的注入样例

> 由真实 `InboxPanel` JSON + `dsh-plugin/lib/inbox.js` 的 `renderPanel()` 生成；数据为 fake，真实 QQ/DSH 联调仍 `NOT_RUN`。

```text
[INBOX_PANEL] inbox=bma3 rev=133 unread=3 items=3/3
[QQ群 942948213] priority=300 total=3 shown=3
  [20:05] #3001 群友：群消息 1
  [20:05] #3002 群友：群消息 2
  [20:05] #3003 群友：群消息 3
[QQ私聊 3668539114] priority=300 total=2 shown=2
  [20:03] #2001 某人：在吗
  [20:03] #2002 某人：看下这个 [图片]
[QQ群 955432692] priority=300 total=128 shown=6
  [20:02] #1123 群友123：消息 123
  ...
  [20:02] #1128 LvtN：欢迎回来？ [@3924945183] [引用 #126 Bob] [图片]
  …（省略 122 条）
```

## 7. SSE 生命周期

端点：`GET /v1/inboxes/{inbox}/events`，Bearer 鉴权。

状态：

```text
CONNECTING -> OPEN -> STREAMING -> (IDLE | RECONNECTING) -> CLOSED
```

事件：

```text
id: 42
event: panel
data: { ...InboxPanel JSON... }

event: heartbeat
data: {"revision":42}
```

- 连接建立后立即发送当前 `panel` snapshot。
- 面板 revision 变化时发送新的 `panel`；服务端合并短时间内的多次变化。
- 客户端重连带 `Last-Event-ID`；服务端总是发送当前 snapshot（幂等），客户端按 revision 去重。
- 15 秒无变化发送 heartbeat；SSE 连接断开不改变 Inbox 数据。
- ack：`POST /v1/inboxes/{inbox}/ack`，body 可带 `revision`；服务端把 `ackedRevision` 推进到当前面板 revision。ack 不阻塞后续事件，不删除消息板事实。

## 8. 通用优先级

`PriorityResolver` 输入：

- `SourceView`（platform/raw）
- platform 默认权重（`weighting.platformWeights`）
- per-source 覆盖（QQ: `qq_source_weight` 的 weight/notifyThreshold）
- 用户备注（QQ: `qq_user_note` priority）
- `PrioritySignals`（mention/reply/name/command）
- 全局 `notifyThreshold`

计算：

```text
base = platformWeights[platform] (default 0)
priority = max(base, sourceWeight, notePriority, base + signalBoosts)
threshold = sourceWeight.notifyThreshold if present else global notifyThreshold
notify = priority >= threshold
```

- 私聊默认 300 >= 200；普通群 0 < 200；群内 @ 加 mention 300 -> 300 >= 200。
- 信号是加在平台 base 上，避免“群 weight=0 就永远静音”；per-source threshold 真正生效。
- `reply/name` 接口保留；QQ 首版只填 mention/command，reply/name 记 `NOT_RUN` 或 false，不伪造。
- 未来 adapter 自己构造 `PrioritySignals`，复用 resolver。

## 9. 发送硬限

- 所有文本发送经 `QqSendSupport`；`maxTextChars` 默认 3000。
- 计数用 Unicode code point，不按 UTF-16 char 切 emoji。
- `overflow=reject`：不发送，返回 `TEXT_TOO_LONG`（actual/max）；`split`：按 code point 切成多段，逐段三态。
- 图片工具的 text 段同样受控；`qq_conversation_send` 不再绕过 `QqSendSupport`。
- Agent 自写工具只要调用 BMA3 发送出口就自动受控；绕过出口违反“发送必须经消息板”。

## 10. 不变量

1. 一个 inbox 只有一条 SSE 事件流；不存在 per-source subscription/lease。
2. Inbox item 的 entries 永远 ≤ N；panel items 永远 ≤ M。
3. 完整消息、引用原文、图片 URL 只在消息板；Inbox/SSE 只放 preview 与关系摘要。
4. Inbox 不 switch platform；来源显示由 `SourceLabeler` 提供。
5. 面板推送不带“去看 history”类提示词；只放事实、关系标记、省略计数。
6. SSE 断线/重连不丢消息板事实；面板 revision 幂等。
7. ack 只推进游标，不阻塞新 panel；漏 ack 不会卡死推送。
8. 所有发送出口共享硬限；不静默截断。
9. 配置非法时保留 last-known-good；热重载不改变在途内容。
10. `./mvnw -q test` 与 `dsh-plugin npm test` 必须 PASS；真实 QQ/DSH 仍写 `NOT_RUN`。

## 11. 验收判据

1. 500 条同 source 消息 -> item entries=6、total=500、panel 行数 ≤6；无 16KB summary。
2. 10 个 item、不同 priority -> panel 只显示 top 6（或配置值），带 omitted 计数。
3. 带 text+at+reply+image 的消息 -> panel 行出现 `[@...]`、`[引用 ...]`、`[图片]`；无 URL/rkey；无 history 提示。
4. 配置改为 4/4 后下一次 panel 只显示 4/4；非法 2/9 被拒绝且旧值继续。
5. 一个 inbox SSE：连接收到 snapshot；新增消息收到 panel；断开重连收到当前 snapshot；15s 无变化收到 heartbeat。
6. ack 后 panel 为空/减少；新消息再次出现；不阻塞新消息。
7. QQ 群默认 0 不唤醒；群内 @ 使 priority=300 并唤醒；per-source threshold=400 时不唤醒。
8. `bilibili/123`、`wechat/account/chat` 能解析并显示 label fallback；Inbox 无平台分支。
9. 发送 3000 字成功、3001 字按配置 reject/split；emoji 不被切开；所有发送工具一致。
10. `./mvnw -q test` PASS；`cd dsh-plugin && npm test` PASS；真实联调 `NOT_RUN`。

## 12. 迁移与兼容

- `inbox_item` 增加 `source_label`、`entries_json`、`total_messages`、`updated_at`、`event_revision`。旧 `summary` 行保持 legacy：`entries` 为空、`event_revision=0`，不会自动进入新面板；需要手动清理/ack，或等新消息覆盖 summary。
- `inbox_meta` 存 `panel_revision`、`acked_revision`；旧库初始化为 0。
- 旧 `due`/`Delivery` API 保留但标记 legacy；DSH 插件切到 SSE 后不再依赖它。
- 旧 `inbox_list` 改为面板视图；旧 `inbox_ack(deliveryId)` 保留参数，无参数时 ack 面板游标。
- `qq_group/qq_private` 保持；新平台用 `bilibili/...`、`wechat/...`。

## 13. 待确认 / 风险

- reply-to-bot 需要出站外显 message_id 索引；首版未做，标 `NOT_RUN`。
- name 信号需要 bot 昵称；首版未做。
- 多 DSH 会话同时连同一 inbox 时，ack 游标是全局的；首版按单面板/单消费者设计，多消费者需要 per-consumer cursor。
- 真实 SSE/DSH 宿主/QQ 联调 `NOT_RUN`；不得把 fake 测试写成真实验收。
