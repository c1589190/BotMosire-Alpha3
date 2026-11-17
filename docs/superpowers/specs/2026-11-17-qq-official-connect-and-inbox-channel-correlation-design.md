# QQ 官方机器人接入与双渠道 Inbox 一致性设计

> **状态**：已确认（2026-11-17，UTC+8）。
> **用户裁定**：[D-074..D-077](../DESIGN_RULINGS.md)。
> **实施计划**：[2026-11-17 QQ 官方机器人与双渠道一致性计划](../plans/2026-11-17-qq-official-connect-and-channel-correlation-plan.md)。
> **上游契约**：[QQ 官方机器人 API 摘要](../../upstream/2026-11-17-qq-official-bot-api.md)。
> **本文取代**：无。

## 1. 需求原话

用户原话（逐字摘录，不含凭据）：

> 「我准备让当前项目支持官方QQ机器人，我已经准备好了一个官机账号，告诉我我应该提供什么，以及你预计在新的qq-official-connect里实现什么」

> 「其实是A+B都要」

> 「此外还有一个问题，就是官机 qq机器人和Onebot渠道的inbox重复注入问题，我的建议是把两个渠道的来源统一起来，然后做一个一致性校验，在inbox层面，两个渠道的消息视为同一来源的不同渠道印证，单次只显示一个渠道、可以通过不同的tool分别调取两个负责模块的详细记录」

> 「我认为，这可以交给Agent自己负责——允许Agent自己调用工具，把两个渠道的来源绑定在一起，如果绑定了，那么bma3再在来源里进行去重；也允许Agent主动关闭一个渠道的消息注入之类的，总之就是原则是，Agent是用户；其它的没啥问题」

> 「按你说的做吧，先记录完整的开发计划文档」

A/B 的语境为：

- **A**：腾讯官方 Connector 的扫码创建/绑定机器人并取得 AppID/AppSecret。
- **B**：已有凭据下，生成机器人分享/添加二维码。

## 2. 目标与非目标

### 2.1 目标

1. 新增 `bma3-qq-official-connect`，在 Linux/Windows 原生 Java 21 上接入 QQ 官方机器人 WebSocket 事件与 HTTPS OpenAPI。
2. 官方群 @、QQ 单聊、机器人事件能写入现有消息板，并按现有 Inbox/优先级/工具架构暴露。
3. 支持 A+B 二维码；A 的 AppSecret 不进入日志、MCP 结果或提交。
4. 允许 Agent 发现、绑定、解绑两个 QQ 渠道（OneBot / 官方机器人）的逻辑来源。
5. 绑定后的双渠道消息在 Inbox 层只产生一个逻辑会话语义、一次注入；原始事实仍分别保留。
6. 提供跨渠道一致性证据：`MATCHED` / `CHANNEL_ONLY` / `UNVERIFIED` / `CONFLICT`。
7. Agent 可以按渠道或逻辑会话暂停/恢复 Inbox 注入，并可设置首选展示渠道。
8. 发送仍按具体渠道路由，绑定关系绝不合并发送路由。

### 2.2 非目标

- 不做 QQ 频道/Guild、频道私信；首版聚焦 QQ 群聊和消息列表单聊。
- 不做 Webhook 入站；首版只做 WebSocket。
- 不实现官方平台自己的 AI Agent/OpenClaw 托管，也不自动回复。
- 不保证能拉取平台历史消息；BMA3 消息板是本地事实源。
- 不自动把官方 `openid` 映射为真实 QQ 号。
- 不提供“机器人已加入的全部 QQ 群”平台级枚举；只能使用本地发现/别名。
- 不通过绑定关系自动选择发送渠道，也不一次向两个渠道发送。
- 不引入跨系统精确一次；发送 `UNKNOWN` 继续禁止自动重发。
- 不以正文相似度静默把两条消息标成 `MATCHED`。
- 不把官方 connector 的内部 HTTP 端点写成公开 OpenAPI；A 方案隔离使用官方 Node SDK。

## 3. 术语与不可混淆的 ID

| 术语 | 含义 |
|---|---|
| 渠道（channel） | `onebot` 或 `qq_official` |
| 原始来源（raw source） | 渠道自己的 source，如 `qq_group/<account>/<group>` 或 `qq_official_group/<account>/<group_openid>` |
| 逻辑会话（logical thread） | Agent 显式建立的双渠道绑定目标，例如 `qq_thread/<threadId>` |
| 观察（observation） | 某一渠道实际收到的一条原始事件 |
| 印证（corroboration） | 两个渠道的观察在严格条件下被判定为同一逻辑消息 |
| 展示渠道（display channel） | 某个 Inbox item 当前实际渲染的单一渠道 |
| 注入开关（injection gate） | 是否允许某个来源/逻辑会话产生新的 Inbox item 或唤醒；不改变消息板事实 |

不可自动互转的 ID：

- OneBot 群号 ↔ 官方 `group_openid`
- OneBot QQ 号 ↔ 官方 `user_openid` / `member_openid`
- OneBot 消息 ID ↔ 官方消息 ID

以上映射只能由 Agent 显式建立，或保持 `UNVERIFIED`。

## 4. 总体架构

```text
OneBot provider ──> bma3-qqconnect ──┐
                                     ├──> Bma3App ingest / QqChannelService
QQ 官方机器人 ──> bma3-qq-official-connect ─┘
                                     │
                                     ├──> 各渠道消息板（原始事实，按 raw source）
                                     │
                                     └──> 逻辑来源绑定/一致性
                                              │
                                              v
                                        bma3-inbox
                                   （单一 display channel）
                                              │
                                              v
                                      MCP / SSE / 工具面
```

模块边界：

- `bma3-qqconnect`：OneBot 连接、原始来源、OneBot 详情工具；不感知逻辑绑定。
- `bma3-qq-official-connect`：官方认证、网关、事件、官方 OpenAPI、官方详情工具与分享二维码。
- `bma3-app`：组合根；装配两个渠道，持有 QQ 双渠道逻辑来源/绑定/一致性服务。
- `bma3-core`：通用逻辑来源、渠道观察、绑定/证据模型（不写 QQ 专用判断）。
- `bma3-inbox`：只按传入的 canonical source、display channel、entry/channel 元数据显示与调度；不 switch QQ 平台。

## 5. 官方机器人模块

### 5.1 Maven 与依赖

新增模块：

```text
bma3-qq-official-connect
```

首版依赖：

- `bma3-core`
- `bma3-agentlib`
- Jackson
- SLF4J
- JUnit/AssertJ（test）

运行时使用 Java 21：

- `java.net.http.HttpClient` 调官方 HTTPS API。
- `java.net.http.WebSocket` 连接官方 Gateway。
- 不引入第三方 OneBot 或 QQ 官方 SDK 到 Java 主进程。
- B 二维码 PNG 可使用纯 Java QR 库（推荐 ZXing core）；依赖需在实现时验收。

### 5.2 配置

新增独立配置段，禁止把 AppSecret 明文写进 `config/app.yaml`：

```yaml
qqofficial:
  accounts:
    - accountId: qq-official-main
      appId: "<APP_ID>"
      appSecretEnv: BMA3_QQ_OFFICIAL_APP_SECRET
      # 或 appSecretFile: /home/user/.config/bma3/qq-official.secret
      enabled: true
      shard: [0, 1]
  transport: websocket
  reconnect:
    initialMs: 1000
    maxMs: 60000
    jitter: true
  http:
    connectTimeoutSeconds: 10
    requestTimeoutSeconds: 20
  matching:
    strictTimeToleranceSeconds: 5
```

要求：

- `appSecretEnv` 与 `appSecretFile` 二选一；都存在时 fail-closed。
- 凭据文件读取前先收紧 owner-only；失败则 token 模式不启动。
- 配置样例只显示环境变量名或占位符。
- AccessToken 只在内存中缓存，不写入数据库或配置文件。
- 支持多官方账号；每个账号独立 token、Gateway、状态与 fault。

### 5.3 认证与 HTTP 客户端

流程：

1. `POST https://api.bot.qq.com/app/getAppAccessToken`
2. 解析 `access_token` 与 `expires_in`；成功但缺 token 视为失败。
3. 缓存 token；在到期前约 60 秒刷新，单飞防并发重复刷新。
4. 所有 OpenAPI 增加 `Authorization: QQBot <token>`。
5. 解析 HTTP 状态与响应体 `err_code`/`code`/`trace_id`；不能仅凭 HTTP 200 判定成功。
6. token 失效时只允许一次受控刷新重试；业务错误不盲目重试。

日志只允许：账号 ID、HTTP 状态、错误码、trace_id、耗时、请求方法、端点类别。禁止请求/响应全文、token、secret。

### 5.4 WebSocket Gateway

状态机：

```text
STOPPED -> CONNECTING -> HELLO -> IDENTIFYING -> READY
                                      |
                                      v
                                  RECONNECTING -> RESUME -> READY
                                      |
                                      +-> IDENTIFY（仅在不可 Resume 时）
```

协议要求：

- `GET /gateway/bot` 获取 URL、建议分片、session 限制。
- 首版 `shard=[0,1]`；未来多分片另行设计。
- 收到 `OpCode 10 Hello` 后按 `heartbeat_interval` 发 `OpCode 1` 心跳。
- 保存 `session_id`、最近 `s`、`seq`；断线优先 `OpCode 6 Resume`。
- 处理 `OpCode 7 Reconnect`、`OpCode 9 Invalid Session`。
- 4006/4007 等不可 Resume 时重新 Identify；4008/4009 按可恢复处理。
- 4014、4914、4915 等不可重试错误标记为 `DOWN/UNSUPPORTED`，通过状态工具给出原因，避免无限重连。
- 指数退避 + jitter；不因正常 close 快速打爆平台。
- 单账号单活连接；重复连接 fail-closed。

### 5.5 事件解析

首版支持：

- `READY`
- `RESUMED`
- `C2C_MESSAGE_CREATE`
- `GROUP_AT_MESSAGE_CREATE`
- `GROUP_MESSAGE_CREATE`（有权限时）
- `FRIEND_ADD` / `FRIEND_DEL`
- `GROUP_ADD_ROBOT` / `GROUP_DEL_ROBOT`
- `GROUP_MSG_RECEIVE` / `GROUP_MSG_REJECT`
- 其他未知 Dispatch 事件：仅计数、记录事件名与长度，不解析正文。

官方事件模型使用字符串 ID：

```java
record OfficialQqEvent(
    String eventId, String eventType, String messageId,
    String groupOpenid, String userOpenid, String memberOpenid,
    String username, String memberNickname, String memberRole,
    Instant at, List<Segment> segments, String refMsgIdx,
    String traceId)
```

约束：

- 不能把官方 openid/消息 ID 塞进现有 `OneBotEvent` 的 `long` 字段。
- `content` 已去除群 @ 机器人前缀；群 @ 事件按 `qq_at_bot=true` 处理。
- `mentions` 的 `scope=all` → `qq_at_all=true`；其他 mention 按目标区分。
- `message_scene.ext.auth_token` 不持久化、不记录、不进入 Segment。
- `msg_idx` / `ref_msg_idx` 作为引用关系保存到结构化 Segment。
- `attachments` 映射为 `image/video/record/file`，只保留类型、MIME、大小、宽高、文件名和 URL；URL 不得进正文或 Inbox。
- `ark_data` 映射为卡片 Segment；`msg_elements` 提取引用/嵌套文本。
- `timestamp` 使用 RFC3339 解析；缺失/非法时回退服务器收到时间并记录告警。
- 按官方消息 ID + 事件封套 ID 做去重；不能按正文去重。原始事件可记录 hash 用于诊断，但不作为唯一事实键。
- 顺序：先写对应原始 source 的消息板，再进入 Inbox 路由。

### 5.6 官方读工具

首版暴露：

- `qq_official_account_list`
- `qq_official_account_status`
- `qq_official_group_list`（本地目录/别名，不宣称平台全量）
- `qq_official_group_info`
- `qq_official_bot_state`
- `qq_official_user_info`
- `qq_official_conversation_list`
- `qq_official_conversation_history`
- `qq_official_message_get`
- `qq_official_share_qr`

能力受限时：

- 无权限：`CAPABILITY_UNAVAILABLE` 或官方 `err_code` 分类。
- 无数据：明确“未发现”，不伪造空平台列表。
- 无好友关系/无群成员关系：显示官方错误原因与可执行提示。

### 5.7 官方发送工具

首版：

- `qq_official_send_text`
- `qq_official_reply`
- 后续：图片/文件（上传 `file_info`）与撤回。

规则：

- 必须指定账户和具体 raw source / group_openid / user_openid。
- 被动回复优先使用真实入站 `msg_id`；超过窗口或次数由平台错误显式返回。
- 同一 `msg_id` 的多次回复使用服务端原子分配并持久化的递增 `msg_seq`，重启后不得回退；发送前将已分配序号与出站记录关联。
- 主动发送必须由 Agent 显式调用；清楚展示平台频控/用户关闭主动消息的错误。
- 发送前先写入本地 outbound 消息板记录；发送结果写回状态。
- 发送超时/无法判定 → `UNKNOWN`，禁止自动重发。
- 不提供绕过消息板发送的通用 raw API 写工具；所有文本发送遵守现有统一出口的 `maxTextChars` 硬限和溢出策略（默认 3000）。

## 6. A+B 二维码

### 6.1 A：扫码创建/绑定机器人

实现方式：

- 独立 onboarding 工具，不进入 BMA3 常驻 Java 运行时。
- 使用腾讯官方 NPM 包 `@tencent-connect/qqbot-connector@1.2.0`，Node >= 18；版本和依赖锁文件入库。
- 该工具可输出终端二维码和 PNG，并把扫码返回的 AppID/AppSecret 写入受保护凭据文件。
- 成功后只输出账户标识与文件路径；**绝不把 secret 打到 stdout/MCP**。
- POSIX 文件 `0600`；Windows 仅当前用户 ACL；无法落实权限则拒绝写凭据。
- 官方 connector 流程/端点变化时明确报错，不静默降级。
- 若主发行包要求完全无 Node，另立 Java port 裁定；本设计不把 Node 引入常驻运行依赖。

### 6.2 B：运行期分享二维码

- 使用现有官方账号凭据调用 `POST /v2/generate_url_link`。
- 将返回短链接渲染成 PNG 和终端 QR。
- 工具返回：`url`、`pngPath`、`accountId`；不返回任何凭据。
- 文件写入 `<dataDir>/qr/` 或受控导出目录；不提交。
- 短链接用途仅为机器人分享/添加；不能绕过平台群沙箱、审核、权限或主动消息限制。

## 7. 双渠道逻辑来源

### 7.1 原始来源命名

OneBot 保持：

```text
qq_group/<accountId>/<group>
qq_private/<accountId>/<peer>
qq_system/<accountId>/<event>
```

官方新增独立 platform，避免与 OneBot 群号混淆：

```text
qq_official_group/<accountId>/<group_openid>
qq_official_private/<accountId>/<user_openid>
qq_official_system/<accountId>/<event>
```

逻辑来源：

```text
qq_thread/<threadId>
```

`threadId` 由 Agent 提供，只允许 `[A-Za-z0-9._-]`，服务端持久化并管理 revision。Label 可使用 `QQ群 测试群`、`QQ私聊 某人`，可配置 alias。

### 7.2 绑定模型

- 每个 raw source 最多属于一个 active logical thread。
- 每个 logical thread 每个 channel 最多一个 active binding（OneBot + 官方各一个）。
- 绑定需要 raw source 至少在一个渠道中被观察到，或由操作者显式提供；对不存在的来源可以预绑定，但状态标 `PENDING_OBSERVATION`。
- 绑定操作幂等；重复绑定同一对返回当前状态，不创建重复记录。
- 解绑不删除消息、不删除 Inbox item、不重置 ACK。
- 绑定默认只对后续 ingest 生效。
- Agent 可以建立可选的渠道发送者身份映射：
  - `onebot` QQ 号 ↔ `qq_official` `member_openid`（群）
  - `onebot` QQ 号 ↔ `qq_official` `user_openid`（单聊）
- 身份映射必须带 scope（logical thread 或具体 raw source），不能跨会话全局串用。

### 7.3 Agent 控制工具

服务端提供：

- `qq_channel_sources`：列出已观察 raw source、渠道、账号、最后活动时间、当前绑定/注入状态。
- `qq_channel_thread_list`：列出逻辑会话及渠道、首选渠道、注入状态、一致性摘要。已有 OneBot 工具 `qq_thread_list` 保留原名和原语义，不能注册同名工具。
- `qq_thread_bind`：绑定 raw source 到 logical thread；创建 thread 或追加绑定。
- `qq_thread_unbind`：解绑 raw source；保留历史。
- `qq_thread_identity_bind` / `qq_thread_identity_unbind`：建立/删除发送者映射。
- `qq_thread_set_injection`：按 raw source 或 logical thread 开启/关闭后续 Inbox 注入。
- `qq_thread_set_preferred_channel`：设置逻辑会话首选展示渠道。
- `qq_thread_set_matching`：`strict`（默认）/`off`/`loose`（显式选择）。配置中的 matching mode 只用于新建会话的初始值；已有会话的持久化设置优先，热重载不得覆盖。
- `qq_thread_status` / `qq_thread_compare`：查看绑定、观察、匹配/冲突和注入状态。

工具规则：

- Agent 是操作主体；不要求人工二次确认即可执行可逆绑定/开关。
- 不要求 Agent 传 expectedRevision；服务端原子更新并返回新的 revision。
- 所有操作写入审计表：动作、threadId、raw source、enabled、结果、错误码；不写正文。
- 操作幂等；非法 source/thread/revision 状态返回可诊断错误。
- 绑定工具不允许传 token、secret、正文。

## 8. Inbox 去重与展示

### 8.1 三去重层次

| 层次 | 规则 |
|---|---|
| 消息事实 | 两个渠道分别保存；`message board` 是事实源；不因绑定删除任何原始行 |
| Inbox item | 绑定后使用 canonical `qq_thread/<threadId>`；同一逻辑会话只允许一个 active item |
| 注入 | 只有 display channel 的有效消息能驱动注入；不可因第二个渠道产生第二次唤醒 |

### 8.2 观察进入路由

对每一条原始消息，先按既有 [D-027/D-028](../DESIGN_RULINGS.md) 计算优先级：低于 `dropBelow` 的消息直接丢弃，不落消息板、不建 observation，也不注入。以下流程适用于**通过该过滤的消息**；绑定、去重或关闭注入不得额外删除已保留的原始事实。此处澄清不改变既有 OneBot 丢弃语义。

1. 用 raw source 写对应渠道消息板；按 `(raw_source, message_id)` 幂等。
2. 查询 raw source 是否绑定 logical thread。
3. 未绑定：
   - Inbox 用 raw source；行为与现有一致。
   - 若该 raw source 的注入被暂停，仍写消息板，但跳过 Inbox append。
4. 已绑定：
   - 记录 `channel_observation`。
   - 若该 source 的注入暂停，仍记录 observation/board，不产生 Inbox append。
   - 若该 source 不是当前 display channel，则只尝试记录证据，不新增正文。
   - 若是当前/候选 display channel，则 append/merge 到 canonical item。
5. canonical item 的 display channel 一旦确定，在该 item 生命周期内固定；后续不同渠道仅作为证据。
6. item 被推送/冻结后，不修改其 entries/summary；晚到证据只写 evidence 表，不重新唤醒。
7. 新 item 可选择首选渠道；若首选渠道不可用/注入关闭，则回退到另一个已启用且可用的渠道，并在 item 元数据/工具结果中报告 `selectedChannel`。

### 8.3 展示投影

- Inbox item 的 `source` 为 canonical `qq_thread/<threadId>`。
- item 保存 `displayChannel` 和 `channelSummary`（如各渠道观察数、matched 数、channel-only 数、conflict 数）。
- 一个 item 的正文只来自一个渠道。
- 另一渠道的正文不进入注入；必须通过 `qq_thread_compare`、`qq_conversation_history` 或 `qq_official_conversation_history` 查看。
- `inbox_get`/panel 输出增加渠道元数据和证据摘要；旧客户端忽略未知字段仍可工作。
- 优先级不重复计算：匹配的同一逻辑消息取两渠道证据中的最大优先级；若证据晚到且 item 已冻结，不改变已经发生的注入。

### 8.4 注入开关语义

- 粒度：raw source 与 logical thread 均可控制；两者同时存在时以更严格者生效。
- 关闭注入只阻止后续 Inbox item/唤醒；消息板、目录、观察、详情读取不受影响。
- 不自动 ACK、dismiss 或删除已有 item。
- 已有 pending item 不被改写；Agent 可通过现有 `inbox_ack`/`inbox_dismiss` 清理。
- 恢复注入后从下一条消息开始正常参与；不补注入暂停期间的消息。

## 9. 一致性校验与消息级印证

### 9.1 观察状态

- `CHANNEL_ONLY`：仅一个渠道有该观察。
- `UNVERIFIED`：同逻辑会话/候选时间窗口，但缺少可靠发送者映射或匹配条件不完整。
- `MATCHED`：满足严格匹配。
- `CONFLICT`：存在同发送者/时间窗口的候选，但正文或媒体类型不一致。

### 9.2 严格匹配（默认）

必须全部满足：

1. 两个 raw source 绑定到同一 logical thread。
2. 发送者身份已通过 `qq_thread_identity_bind` 映射到同一 canonical identity；或双方 raw sender ID 完全相同且能证明不是 openid/QQ 号误用。
3. 时间差 ≤ `matching.strictTimeToleranceSeconds`（默认 5 秒）。
4. 归一化正文一致；或正文为空且媒体类型/数量一致。
5. 消息方向相同。
6. 候选配对是 1:1；如果同窗口有多条候选，无法唯一判定则不匹配，标 `UNVERIFIED`。

归一化：

- Unicode NFKC、trim、连续空白折叠。
- 去除平台包装的 @ 机器人前缀和 mention 标记差异。
- URL 只比较是否存在或规范化 host/path？默认不比较短链接 query（防止 CDN rkey 差异）；多媒体用 MIME/类型/数量。
- 卡片/合并转发仅在有可靠结构化字段时比较；无法可靠比较则 `UNVERIFIED`。
- 不基于正文哈希单独判等。

### 9.3 Loose 模式

- 只能由 Agent 显式设置。
- 允许缺少发送者映射时按 thread + 时间 + 归一化正文/媒体做候选配对。
- 结果仍标 `UNVERIFIED_MATCH`，不能升级为 `MATCHED`，也不能在任何对外摘要中宣称已证实。
- 候选歧义时不做匹配；保留两侧观察。

### 9.4 冲突

- 有显式身份映射且同窗口但正文/媒体不一致 → `CONFLICT`。
- 不删除、不覆盖任一观察。
- Inbox 只显示 display channel 正文，并在 channelSummary/工具中暴露冲突数。
- 冲突不会自动触发额外注入；Agent 用 `qq_thread_compare` 查看后决定人工/工具处理。

### 9.5 晚到证据

- 第二个渠道消息晚于 Inbox item 冻结：
  - 写 observation 和 evidence。
  - 不追加 item 正文、不 bump 可注入 revision、不唤醒。
  - 可在已有的 evidence 计数/工具结果中体现。
- 如果第二渠道消息早于 item 冻结且成功匹配，可以在冻结前更新 evidence summary；正文仍只取 display channel。
- 不能为了让晚到证据进入面板而重新打开冻结 item。

## 10. 数据模型与迁移

### 10.1 通用核心模型

建议 `bma3-core` 新增（名称实现时可微调）：

- `ChannelId` / `ChannelObservation`
- `LogicalSourceBinding`
- `ChannelIdentityBinding`
- `CorroborationState`
- `ChannelEvidence`

这些模型不 switch QQ 平台；QQ 专用 source 解析在 app/QQ 模块中进行。

### 10.2 存储

新增独立 SQLite（建议 `qq-channels.db`，具体文件名实现可定）：

```text
logical_thread(
  thread_id TEXT PRIMARY KEY, label TEXT, preferred_channel TEXT,
  matching_mode TEXT, injection_enabled INTEGER, revision INTEGER,
  created_at TEXT, updated_at TEXT)

channel_binding(
  raw_source TEXT PRIMARY KEY, thread_id TEXT, channel TEXT, account_id TEXT,
  created_at TEXT, updated_at TEXT, revision INTEGER, UNIQUE(thread_id, channel))

raw_source_injection(
  raw_source TEXT PRIMARY KEY, injection_enabled INTEGER, revision INTEGER, updated_at TEXT)

channel_identity_binding(
  thread_id TEXT, channel TEXT, channel_user_id TEXT, canonical_user_id TEXT,
  scope TEXT, revision INTEGER, created_at TEXT, updated_at TEXT,
  PRIMARY KEY(thread_id, channel, channel_user_id, scope))

channel_observation(
  observation_id TEXT PRIMARY KEY, thread_id TEXT, channel TEXT, account_id TEXT,
  raw_source TEXT, channel_message_id TEXT, channel_sender_id TEXT,
  canonical_sender_id TEXT, observed_at TEXT, message_at TEXT, priority INTEGER,
  normalized_fingerprint TEXT, media_summary TEXT, evidence_state TEXT,
  correlation_group_id TEXT, inbox_item_id TEXT, created_at TEXT,
  UNIQUE(raw_source, channel_message_id))

channel_audit(
  id INTEGER PRIMARY KEY AUTOINCREMENT, action TEXT, thread_id TEXT, raw_source TEXT,
  enabled INTEGER, result TEXT, error_code TEXT, created_at TEXT)
```

- 绑定/开关持久化，重启不丢。
- 写操作事务化；唯一约束保证 1:1。
- 审计只记 ID/动作/结果，不记正文或凭据。

### 10.3 Inbox 迁移

- `inbox_item` 增加向后兼容列/JSON：
  - `display_channel TEXT NOT NULL DEFAULT ''`
  - `channel_summary_json TEXT NOT NULL DEFAULT '{}'`
- `InboxEntry` 增加可选 `channel`、`evidence` 字段；旧 JSON 缺省为空。
- `PanelItem` 增加 `displayChannel`、`channelSummary`（或等价结构）。
- 不在 Inbox 中新增 QQ-specific 字段名；只使用通用 channel/evidence 术语。
- 迁移必须保留现有 DB；旧单渠道行为默认 `displayChannel=""`。

### 10.4 消息板

- 保持现有 `chat_message.source` 为 raw source；不强制改 schema。
- 如需高效显示渠道，可从 raw source 前缀推导或增加可选 `channel` 列；优先不破坏现有存储。
- 跨渠道 evidence 不写回 `chat_message.text`；仍以各渠道原始事实为准。

## 11. 工具面总览

### 11.1 官方模块

见 §5.6/5.7；所有工具带 accountId/官方 raw source；读取工具不返回 token/secret。

### 11.2 跨渠道控制面

见 §7.3；工具名以 `qq_channel_*` / `qq_thread_*` 命名，和现有 OneBot `qq_*` 工具区分。

### 11.3 详情读取

- `qq_conversation_history`（OneBot）：保持现有语义，只返回 OneBot raw source 的事实。
- `qq_official_conversation_history`（官方）：只返回官方 raw source 的事实。
- `qq_thread_history`：返回 logical thread 统一事实时间线，带 channel/evidence 元数据。
- `qq_thread_compare`：列出 matched/channel-only/conflict/unverified 观察及两侧 ID、时间、摘要（不输出敏感原始事件全文）。
- `inbox_get`/panel：返回 canonical item，正文只来自 display channel；渠道证据摘要和 `qq_thread_compare` 引用不包含正文。

## 12. 安全、权限与可观测性

- AppSecret/AccessToken/Authorization/auth_token：不进入日志、Inbox、MCP 工具结果、错误消息、配置样例、git。
- A 工具落盘凭据前先建立/验证 owner-only；失败不继续。
- B 返回分享 URL/PNG，不返回 token/secret。
- 所有外部 HTTP/WS 只从回环 BMA3 主动出站；官方 API 不需要监听公网。
- 默认只允许 BMA3 回环监听；官方接入不改变红线。
- 工具审计：只记录 accountId、raw source、threadId、动作、状态、错误码。
- 正文不落日志；未知事件不记录正文，只记录 event type/长度。
- 指标：token 刷新、Gateway 状态、心跳、最近事件、重连次数、HTTP 请求数/错误码、匹配/冲突计数。
- watchdog 增加 `qqOfficial` 账号/连接探针；未配置/禁用与故障区分。

## 13. 失败模式

| 场景 | 行为 |
|---|---|
| AppSecret 缺失/权限错误 | 账号 DOWN；输出错误分类；不输出 secret |
| AccessToken 过期 | 单飞刷新一次；失败则 DOWN，等待退避 |
| WebSocket 正常断开 | Resume；不可恢复则 Identify |
| Intent 无权限/机器人下架/封禁 | 不可无限重连；状态标不可用与平台原因 |
| 未知官方事件 | 计数/记录类型，不解析正文 |
| 重复消息 | 按 raw source + message ID / event ID 幂等 |
| 两渠道绑定缺失 | 保持两个 raw source，各自独立 Inbox item |
| 两渠道绑定冲突（同 thread 重复 raw source / channel） | 工具返回错误；不自动覆盖 |
| 注入关闭 | 仍写消息板和 observation；不新增 Inbox |
| 匹配候选歧义 | 不匹配；标 UNVERIFIED |
| 冻结 item 晚到证据 | 只附加 evidence；不重新注入 |
| 发送网络超时 | UNKNOWN；禁止自动重发 |
| A 扫码过期/取消 | 刷新/退出；不写凭据；不残留 secret |
| B 分享链接失败 | 返回错误码；不返回缓存敏感数据 |

## 14. 不变量

1. 消息板是通过既有 `dropBelow` 过滤后的完整事实源；两个渠道保留的原始消息不因绑定、去重或冲突而删除/覆盖。
2. Inbox 不 switch QQ 平台；只消费通用 logical source、channel 和 display 元数据。
3. 绑定后的同一逻辑会话在一个 active window 内只产生一个 Inbox item；不产生第二渠道的独立唤醒。
4. 单次注入只显示一个渠道正文；另一渠道只能通过证据摘要和工具访问。
5. 未绑定/未证实时不伪造 `MATCHED`；`UNVERIFIED` 不能升级为确定性印证。
6. 注入暂停不等于删除/ACK；不自动改写已有 Inbox item。
7. 发送路由按具体渠道 fail-closed；绑定绝不能合并出站账号或 source。
8. 凭据不出现在提交、样例、日志、Inbox 或 MCP 结果；凭据文件 owner-only。
9. 官方 API 的业务错误按 `err_code`/`code` 识别；不把 HTTP 200 当业务成功。
10. 真实平台验收不能用 fixture/fake 冒充；没跑的事项写 `NOT_RUN`。

## 15. 验收判据

### 15.1 构建与安全

- `./mvnw -B -ntp test` PASS；新增模块测试数与总数记录。
- `dsh-plugin npm test` PASS（现有插件不回归）。
- `packaging/smoke.sh` PASS（应用仍能启动、`/healthz` 200）。
- 扫描仓库/构建产物/日志 fixture：无 AppSecret、AccessToken、Authorization、官方 auth_token。
- 凭据文件权限测试覆盖首次创建、已有文件、替换和失败路径（POSIX 0600 / Windows DACL）。

### 15.2 官方通道

- AccessToken 请求成功；无效 secret 错误被分类且不含敏感值。
- WebSocket Hello/Identify/READY 成功；可 Resume；不可恢复错误不会无限重连。
- fixture 覆盖 C2C、群 @、群全量、引用、图片、卡片、重复消息和未知事件。
- 真实只读：测试群 @ 与单聊消息进入原始消息板，`qq_official_conversation_history` 可读取。
- 真实发送仅在另行授权后：文本/回复走消息板、状态回写；`UNKNOWN` 不重发。
- 受限能力（群列表、历史、群成员、群信息）返回明确 `CAPABILITY_UNAVAILABLE`，不返回假空。

### 15.3 A+B 二维码

- A：隔离 onboarding 工具可显示/保存二维码；扫码成功后只输出成功信息；凭据文件 owner-only；过期可刷新；取消/失败无 secret 残留；真实扫码 `NOT_RUN` 直到用户执行。
- B：工具成功调用 `/v2/generate_url_link`，返回可扫描短链接和本地 PNG；手机扫码能打开官方分享页；工具输出无 token。

### 15.4 双渠道 Inbox

- 绑定后两个渠道的同一条逻辑消息只产生一个 Inbox item 和一次注入。
- 原始消息板保留两行渠道事实。
- 单次 Inbox 正文只来自 displayChannel；另一渠道通过详情工具可读。
- 渠道注入暂停后仍能读原始消息，但不再新增 Inbox 注入。
- 首选渠道不可用时正确回退并报告 selectedChannel。
- 已冻结 item 晚到证据不创建新 item、不重新唤醒。
- 匹配/单侧/未证实/冲突 fixture 全部覆盖；歧义和冲突不覆盖事实。
- 优先级不重复叠加；同一匹配逻辑消息最多取证据最大分值。

### 15.5 Agent 控制面

- `qq_channel_sources` 能列出两渠道来源与状态。
- `qq_thread_bind/unbind` 幂等、持久化、非法源/重复绑定 fail-closed。
- Agent 可设置 preferred channel、matching mode、raw source/thread injection。
- 所有控制操作返回 revision/effective state；审计不含正文或凭据。
- 重启后绑定、注入开关、身份映射与首选渠道仍生效。

### 15.6 真实联调

- 需要真实官方账号：AccessToken + Gateway + READY 可执行。
- 需要真实测试群 @ 与单聊消息：真实入站到消息板/MCP。
- 需要同一测试群同时接入 OneBot 与官方机器人：跨渠道真实匹配才能标 `PASS`；否则 `NOT_RUN`。
- A 的真实扫码与 B 的真实手机扫码均需用户参与；未参与写 `NOT_RUN`。

## 16. 未决与已知限制

没有新的用户裁定阻塞项。实现时不得把下列限制伪造成能力：

- 当前官方公开接口没有确认“机器人所加入的全部 QQ 群列表”和 QQ 群/C2C 历史消息拉取；只以本地消息板/目录为事实源。
- 官方 openid 与 OneBot QQ 号没有自动映射；需要 Agent 显式身份绑定或保留 `UNVERIFIED`。
- 两渠道没有共享消息 ID；在缺少身份映射、匹配关闭或渠道故障切换时，超出已配置时间窗口的迟到副本无法被确定地识别。不得把这种情况伪称跨系统精确一次；原始事实仍保留，窗口内不确定候选应保守抑制额外唤醒并标未证实。
- 首选渠道在线且启用时，非展示渠道独有的观察不补发、不额外唤醒，仍可通过详情与比较工具查看；暂停或切换只影响后续消息。
- 群全量消息依赖官方机器人权限和群设置；只有 @ 事件时跨渠道覆盖天然不完整。
- 活跃消息/被动回复窗口与频控由平台决定，无法通过本地重试消除。
- A 方案依赖官方 Node connector 的发布契约；这组端点不是公开 OpenAPI，协议变化时明确失败。
- QQ 频道/Guild、Webhook、自动回复不在本设计范围。

## 17. 参考

- [DESIGN_RULINGS D-074..D-077](../DESIGN_RULINGS.md)
- [计划](../plans/2026-11-17-qq-official-connect-and-channel-correlation-plan.md)
- [官方 API 上游摘要](../../upstream/2026-11-17-qq-official-bot-api.md)
- 现有消息板、Inbox、OneBot 代码：
  - `bma3-core/src/main/java/io/mosire/bma3/core/store/`
  - `bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/`
  - `bma3-app/src/main/java/io/mosire/bma3/app/Bma3App.java`
  - `bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/`
