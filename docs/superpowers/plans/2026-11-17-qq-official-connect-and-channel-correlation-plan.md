# QQ 官方机器人与双渠道 Inbox 一致性实施计划

> **状态**：已确认（2026-11-17，UTC+8）；**实施进行中**，实际阶段进度与验证以 [STATUS](../../agents/STATUS.md) 为准。
> **设计正本**：[QQ 官方机器人接入与双渠道 Inbox 一致性设计](../specs/2026-11-17-qq-official-connect-and-inbox-channel-correlation-design.md)。
> **用户裁定**：[D-074..D-077](../DESIGN_RULINGS.md)。
> **上游契约**：[QQ 官方机器人 API 摘要](../../upstream/2026-11-17-qq-official-bot-api.md)。
> **分支基线**：`feat/0.0.8-windows-mcp`，HEAD `43b57b4`，跟踪 `origin/feat/0.0.8-windows-mcp`。
> **当前工作树**：已有未提交的 Linux smoke 修复与 STATUS 更新；本计划新增文档后仍不 commit/push，除非用户明确授权。

## 0. 本计划完成定义

全部完成时必须同时满足：

1. `bma3-qq-official-connect` 可支付官方 AccessToken、连接 WebSocket、接收群 @/单聊事件并写入消息板。
2. 官方只读详情工具、发送工具、A+B 二维码可在真实或明确限定的环境中验证。
3. Agent 可通过 MCP 工具绑定/解绑两个渠道的逻辑来源、设置发送者映射、设置首选渠道、暂停/恢复注入。
4. 绑定后同一逻辑消息不重复注入；Inbox 正文只显示一个渠道；详情工具可分别读取两个渠道的原始事实。
5. 跨渠道一致性状态 `MATCHED / CHANNEL_ONLY / UNVERIFIED / CONFLICT` 可查询、可审计、fail-closed。
6. 全量自动化回归、app-image smoke、安全扫描通过；真实项按 `PASS` / `NOT_RUN` 如实记录。
7. `docs/agents/STATUS.md` 记录实际命令、rc、测试数、证据路径和未验证项。

## 1. 已完成的预检

本计划成稿前已完成以下只读/无外发验证（2026-11-17，Linux）：

| 项目 | 结果 | 证据 |
|---|---|---|
| 官方 AppID/AppSecret → AccessToken | PASS；HTTP 200，拿到 token | 凭据不落文档；证据见 STATUS |
| `GET /users/@me` | PASS；HTTP 200 | 不记录 secret |
| `GET /gateway/bot` | PASS；HTTP 200，`shards=1` | 下游 WebSocket 成功 |
| WebSocket Hello → Identify → READY | PASS；收到 `op=10` 后 `op=0 READY` | 未发送消息 |
| `POST /v2/generate_url_link` | PASS；返回手机 QQ 分享页短链接 | 未把 token 写入文档 |
| 真实 QQ 消息收发 | `NOT_RUN` | 尚未触发/授权测试消息 |

凭据文件在仓库外，权限 `0600`；不得把路径下的内容复制进本仓或其构建产物。

## 2. 实施原则

1. 先官方只读链路，再官方写入，再双渠道绑定，最后跨渠道匹配；每一步可独立回退。
2. 不修改现有 OneBot 的成功路径；新增能力默认关闭/未配置。
3. 所有工具和状态输出按项目红线脱敏；正文不落日志。
4. 每个阶段结束都跑聚焦测试；阶段边界跑全量测试与 smoke。
5. 真实联调不冒充通过；未执行项一律 `NOT_RUN`。
6. 不 commit/push/tag，除非用户当次明确授权。
7. 遵从既有 D-027/D-028：两渠道消息均先通过优先级过滤；`dropBelow` 的消息不落板、不产生 observation、不注入。对通过过滤的消息，渠道绑定/去重/暂停注入不得删除原始事实，详见设计 §8.2。

## 3. 阶段与交付

### Phase 0：文档、裁定与契约（本批）

**状态**：完成。

交付：

- `DESIGN_RULINGS.md` D-074..D-077。
- 官方 API 上游契约摘要。
- 本 spec 与本计划。
- `STATUS.md` 当前目标/基线/下一步。

验证：

- 本文相对链接存在；`git diff --check` 无空白错误。
- 尚未有产品代码。

回滚：

- 仅删除/还原本轮文档；不影响产品与运行数据。

### Phase 1：模块骨架与配置

**目标**：建立可编译的新模块和独立配置，不触网。

交付：

1. 父 `pom.xml` 增加 `bma3-qq-official-connect`。
2. 新模块 `pom.xml`：`bma3-core`、`bma3-agentlib`、Jackson、SLF4J；测试依赖 JUnit/AssertJ。
3. `bma3-app/pom.xml` 增加新模块依赖。
4. 包结构：`io.mosire.bma3.qqofficial.{account,auth,api,gateway,event,tools,credential}`。
5. `AppConfig.QqOfficial`：账号、transport、shard、secretEnv/secretFile、超时、matching 配置。
6. `ConfigLoader` 容器 schema + 校验；不支持 inline secret。
7. 配置模板只给占位符/环境变量名。
8. `bma3-qq-official-connect/AGENTS.md`（短目录规则、入口、验证命令）。

测试：

- 配置加载默认值/合法值/非法值。
- inline secret 拒绝。
- 模块编译；无新业务网络请求。

验证命令：

```bash
./mvnw -B -ntp -pl bma3-qq-official-connect -am test
./mvnw -B -ntp -pl bma3-app -am -DskipTests package
```

回滚：

- 移除模块依赖/配置段与模块目录；旧 OneBot 配置不受影响。

### Phase 2：AccessToken 与官方 HTTP 客户端

**目标**：可诊断、可刷新、脱敏的官方 HTTP 通信。

交付：

1. `OfficialQqCredentialResolver`：读取 env/受保护文件；fail-closed。
2. `OfficialQqTokenManager`：
   - token 获取；
   - 内存缓存；
   - 到期前约 60 秒刷新；
   - 单飞；
   - `code`/HTTP 错误映射；
   - 不记录 token。
3. `OfficialQqApiClient`：
   - `GET /users/@me`
   - `GET /gateway/bot`
   - `GET /v2/groups/{group_openid}/info`
   - `GET /v2/groups/{group_openid}/bot_state`
   - `GET /v2/users/{user_openid}/info`
   - `POST /v2/generate_url_link`
   - 通用错误解析 `err_code`/`trace_id`。
4. JSON fixture 与 fake HTTP server 测试。

测试：

- token 成功、业务错误、HTTP 错误、非 JSON。
- 缓存命中、并发单飞、到期刷新。
- `err_code` 处理；trace_id 出现在诊断但不包含 token。
- secret 文件缺失/权限失败时启动 fail-closed。
- 日日志断言不出现 secret/token/正文。

回滚：

- 禁用 `qqofficial.accounts.enabled=false`；不启动新模块网络。

### Phase 3：WebSocket Gateway 客户端

**目标**：稳定接收官方事件并正确恢复会话。

交付：

1. `OfficialQqGatewayClient` 使用 Java 21 `java.net.http.WebSocket`。
2. 状态机：CONNECTING/HELLO/IDENTIFYING/READY/RECONNECTING/CLOSED/DOWN。
3. Hello 心跳、Heartbeat ACK、Reconnect、Invalid Session、Resume。
4. session_id/seq 内存保存；断线后退避重连。
5. 错误码分类：可 Resume、可 Identify、不可重试。
6. Watchdog 探测接口返回连接状态、最近事件时间、最后错误码、重连次数；不返回 token。
7. 测试用本地 WSS/fake gateway 或可注入 transport；真实 WSS 仅在显式手动验收执行。

测试：

- Hello → Identify → READY。
- Resume 成功；4006/4007 → Identify。
- 4008/4009 退避；4014/4914/4915 标不可重试。
- 心跳 seq 正确；重复连接被拒绝。
- 日志不包含 token。

回滚：

- 停止账号 supervisor；旧 OneBot/Inbox 进程不受影响。

### Phase 4：官方事件解析与原始消息入板

**目标**：官方群 @/单聊消息进入原始消息板，尚不启用双渠道合并。

交付：

1. `OfficialQqEventDecoder`：
   - C2C/GROUP_AT/GROUP_MESSAGE；
   - READY/RESUMED；
   - 目录/开关事件；
   - 消息文本/附件/mentions/ark/msg_elements/ref_msg_idx；
   - `auth_token` 丢弃；
   - 字符串 ID 保留。
2. `OfficialQqEvent` 与 `Segment` 映射。
3. `OfficialQqDirectoryStore`：
   - 已观察群/用户；
   - 群别名；
   - 机器人群内状态/消息接收范围；
   - 不伪造平台全量群列表。
4. `OfficialQqAccountRegistry`：类似 OneBot 的 desired-state，但独立 token/gateway；支持多官方账号。
5. `Bma3App` 装配官方账号 reconcile。
6. ingest 将官方事件写绝对原始 source：
   - `qq_official_group/<accountId>/<group_openid>`
   - `qq_official_private/<accountId>/<user_openid>`
7. 目录标签：优先群名/别名，fallback 为短 openid 标签；不把 openid 当 QQ 号。
8. 消息去重：raw source + 官方消息 ID；事件封套 ID 作为附加幂等。

测试 fixture：

- C2C 文本；
- 群 @ 文本（@ 前缀已去除）；
- 群全量消息；
- 图片/文件/语音附件；
- ARK 卡片；
- 引用消息/ref_msg_idx；
- @全体；
- 重复消息；
- 未知事件；
- 时间非法。

测试断言：

- 原文只入消息板；URL/rkey 只入 segments，不进正文；
- auth_token 不出现；
- 原始 source 正确；
- duplicate 不新增行；
- 未绑定时不产生跨渠道合并。

回滚：

- `qqofficial.accounts.enabled=false`，官方 source 不再产生；既有消息/目录保留。

### Phase 5：官方只读工具与详情面

**目标**：Agent 能通过 MCP 主动读取官方 QQ。

交付：

1. 只读工具：
   - `qq_official_account_list`
   - `qq_official_account_status`
   - `qq_official_group_list`（本地目录/别名）
   - `qq_official_group_info`
   - `qq_official_bot_state`
   - `qq_official_user_info`
   - `qq_official_conversation_list`
   - `qq_official_conversation_history`
   - `qq_official_message_get`
2. `QqOfficialSourceLabeler` / keyer：官方 platform 独立命名。
3. 能力不足时返回明确错误码：
   - `CAPABILITY_UNAVAILABLE`
   - `PERMISSION_DENIED`
   - `NOT_FRIEND`
   - `ACCOUNT_NOT_FOUND`
   - `ITEM_NOT_FOUND`
4. MCP 注册与工具 schema 测试。

测试：

- 工具发现；accountId 可选/多账号歧义。
- 本地 history 分页；includeSegments 开关。
- 群列表只来自目录，不声称平台全量。
- 无权限错误不被映射为空列表。
- 工具输出不含 token/secret/正文日志。

回滚：

- 不注册官方工具；消息板仍可人工/其他工具读取。

### Phase 6：官方发送与媒体

**目标**：在统一消息板安全边界内支持官方文本、回复、媒体发送。

交付：

1. 文本发送：`POST /v2/groups/{group_openid}/messages` 和 `/v2/users/{user_openid}/messages`。
2. 被动回复：`msg_id`、`event_id`、`msg_seq` 路由。
3. 主动发送：显式工具参数；quota/用户关闭主动消息错误透传。
4. 工具：
   - `qq_official_send_text`
   - `qq_official_reply`
   - `qq_official_send_image` / `qq_official_send_file`（分阶段）
   - `qq_official_delete_msg`
5. 发送前写 outbound 消息板 row；发送成功回写平台 message id；网络不确定为 `UNKNOWN`，禁止自动重发。
6. 媒体上传：单聊/群聊端点不互通，`file_info` TTL 处理；本阶段可先文本+回复，媒体拆为独立子任务但保留在本计划范围。
7. 测试 fake API：成功、去重错误、消息过期、无权限、429/quota、网络 UNKNOWN。

回滚：

- 关闭官方写工具/发送能力；接收与读取继续。

### Phase 7：A+B 二维码

**A：扫码创建/绑定**

交付：

1. `tools/qq-official-onboarding/` Node 18+ 隔离工具。
2. 锁 `@tencent-connect/qqbot-connector@1.2.0` 与 QR 渲染依赖，提交 `package-lock.json`。
3. CLI：
   - 生成终端/PNG 二维码；
   - 过期刷新；
   - 成功后将 AppID/AppSecret 写入仓库外 owner-only 文件；
   - 只输出成功状态/账户标识/路径。
4. POSIX 0600；Windows 私有目录/DACL 检查。
5. 单测可注入 connector 回调；真实扫码由用户执行并标 PASS/NOT_RUN。
6. 文档：Node 仅为 onboarding 可选要求，不是 BMA3 常驻运行依赖。

**B：分享二维码**

交付：

1. `OfficialQqApiClient.generateUrlLink(callbackData)`。
2. QR 渲染（推荐 ZXing core）与 PNG 输出。
3. 工具 `qq_official_share_qr`：返回 url/pngPath/accountId；不返回 token。
4. 输出目录 `<dataDir>/qr/` 或受控导出目录；清理策略。
5. 测试 URL 解析、PNG 文件存在、QR 内容可解码、错误路径不生成残留。
6. 真实手机扫码分享页作为用户验收项。

回滚：

- 只删除/禁用 QR 工具；官方事件/消息链路不受影响。

### Phase 8：逻辑来源、绑定和 Agent 控制面

**目标**：Agent 能自行把两个渠道来源绑定并控制注入。

交付：

1. `QqChannelRegistry`（`bma3-app`）
   - `logical_thread`
   - `channel_binding`
   - `channel_identity_binding`
   - `channel_audit`
   - SQLite 迁移/初始化/事务/唯一约束。
2. `qq_thread/<threadId>` source 与 `SourceLabeler`。
3. MCP 工具：
   - `qq_channel_sources`
   - `qq_channel_thread_list`（不覆盖既有 OneBot `qq_thread_list`）
   - `qq_thread_bind`
   - `qq_thread_unbind`
   - `qq_thread_identity_bind`
   - `qq_thread_identity_unbind`
   - `qq_thread_set_injection`
   - `qq_thread_set_preferred_channel`
   - `qq_thread_set_matching`
   - `qq_thread_status`
4. 绑定/开关持久化，重启恢复；幂等、无 expectedRevision 要求、服务端 revision。
5. 来源绑定前置校验：channel/account/source format/account known/1:1。
6. 预绑定 `PENDING_OBSERVATION` 状态。
7. 解绑/关闭不删除消息板，不 ACK/dismiss。

测试：

- 同一 raw source 不能绑定多个 thread。
- 每 thread 每 channel 只能一个 active binding。
- 重复 bind 幂等。
- 绑定/开关重启后恢复。
- 暂停注入后消息仍入库但无新 Inbox item。
- 审计无正文/token。
- Agent 工具发现、参数错误、跨账号 source 拒绝。

回滚：

- 不启用绑定工具；所有 raw source 继续独立注入。
- 保留 DB 文件以便重新启用；不回滚产品消息板。

### Phase 9：Inbox schema 与单渠道展示投影

**目标**：canonical source 的 Inbox item 只显示一个渠道，并保留渠道/证据摘要。

交付：

1. `bma3-core` 通用模型：
   - `ChannelObservation`
   - `LogicalSourceBinding`
   - `ChannelIdentityBinding`
   - `CorroborationState`
   - `ChannelEvidence`
2. `InboxEntry` 可选 `channel`/`evidence` 字段（向后兼容缺省）。
3. `InboxItem`/`PanelItem` 增加 `displayChannel`、`channelSummary`（JSON/结构化）。
4. `inbox_item` 向后兼容迁移：新增 `display_channel`、`channel_summary_json`，旧行空/空 map。
5. `AppendItem` 或等价投影入口携带 display channel/evidence summary。
6. Inbox `appendOrMerge` 按 canonical source 合并；display channel 在 item 生命周期固定。
7. 冻结 item 只允许追加 evidence 元数据，不允许改正文/entries/summary/权重；不重新唤醒。
8. Panel/`inbox_get` 返回渠道元数据；旧 DSH 客户端容忍未知字段。
9. 首选渠道/暂停注入时的回退逻辑由 app 计算并传入；Inbox 不 switch QQ。

测试：

- 旧 schema 读取/迁移。
- canonical 两渠道只产生一个 item。
- 单 item 正文只来自 displayChannel。
- 第二渠道晚到不增正文/不新增 item/不重 push。
- preferred 不可用时 fallback 且 selectedChannel 正确。
- 旧单渠道行为不变。

回滚：

- 新列不删除；通过配置关闭 canonical 路径，raw source 继续工作。

### Phase 10：一致性校验与证据

**目标**：实现跨渠道印证、冲突和 Agent 可查询证据。

交付：

1. `QqChannelReconciler`（`bma3-app`）：
   - 归一化 text/segments/attachments；
   - 时间窗口；
   - canonical sender mapping；
   - 1:1 配对；
   - 状态机 `CHANNEL_ONLY / UNVERIFIED / MATCHED / CONFLICT`；
   - strict/off/loose 模式。
2. 写入 `channel_observation`、`channel_identity_binding`；不修改原始 message board 行。
3. 优先用于新绑定之后的消息；既有 Inbox item 不变。
4. `qq_thread_compare`：返回 matched/only/conflict/unverified 数量与观察 ID/时间/摘要；不输出正文全文和 auth_token。
5. `qq_thread_history`：统一事实时间线，channel/evidence 字段；正文分页。
6. `matching` 策略：
   - strict 默认；不可靠的事件时间、解析不完整或媒体 MIME 无法比较时不得标 `MATCHED`；
   - loose 仅 Agent 显式开启且标 `UNVERIFIED_MATCH`；
   - off 不产生 matched claim；
   - 任何面向 Agent 的证据结果不暴露可离线猜测正文的无盐正文哈希；观察使用不含正文的 opaque ID 引用。
7. 冲突/歧义不覆盖、不删除；不触发二次唤醒。
8. 优先级：同一逻辑消息匹配前取 display channel；匹配后同一冻结窗口内取最大证据分数；晚到不重注入。
9. 跨库恢复：绑定后从各 raw source 的消息板水位幂等补齐 observation，记录路由决定；绑定与 ingest 的切换边界同步，不能补注绑定前历史。对渠道投影使用 Inbox 同库原子 receipt（即使条目已 ACK/清理也保留），避免 board/observation/Inbox 之间崩溃或重放造成二次注入；证据摘要按唯一 observation 聚合或覆盖，不用不可幂等累加。证据键存在窄表中并随目标 item 清理，迟到证据必须按已记录的原 item ID 查询；目标已清理时不得回落到新 item。receipt 跟随消息板生命周期、可长期增长，不随 Inbox 窗口上限自动删除。未绑定 OneBot 路径保持原样。

测试 fixture：

- 文本完全相同 → MATCHED；
- 时间差在/超容差；
- 不同 sender/无 identity → UNVERIFIED；
- 相同 sender + 不同正文 → CONFLICT；
- 两条同文同窗口 → 歧义不匹配；
- 附件类型一致/不一致；
- @ 前缀/空白归一化；
- 第二渠道晚到 → evidence only；
- 注入暂停 → observation 存在、无 Inbox；
- loose 显式开关后的 unverified match。

回滚：

- `qq_thread_set_matching(off)` 或关闭 reconciler；绑定仍可做展示级合并。
- 原始观察/证据不删除。

### Phase 11：端到端、安全与回归

**目标**：证明双渠道、安全、取消/失败和回归。

交付：

1. Fake OneBot + fake official gateway + real BMA3 app:
   - 两渠道同消息；
   - 一次 Inbox 注入；
   - 两次原始消息板行；
   - 各自详情工具可读；
   - 注入开关/回退/解绑。
2. MCP 工具发现/调用测试：官方读、跨渠道 bind/status/compare、暂停注入。
3. 安全：
   - git grep 搜索 secret/token/auth_token 不命中（除说明文档占位）；
   - 日志、MCP 工具结果、Inbox/tool snapshots 不包含 secret/token/正文；
   - 凭据文件权限测试；
   - 分享 QR 结果无 secret。
4. 全量门禁：
   ```bash
   ./mvnw -B -ntp test
   cd dsh-plugin && npm test
   cd .. && bash packaging/smoke.sh
   ```
5. Windows 构建兼容：`mvnw.cmd -B -ntp test`（在可运行 Windows 环境时；否则 `NOT_RUN`）。
6. 真实官方账号：
   - token/gateway/READY；
   - 测试群 @ 和单聊入站；
   - 工具读取；
   - 在用户明确授权后测试发送/回复，未授权则 `NOT_RUN`。
7. 真实双渠道：
   - 同一测试群同时加入 OneBot 和官方机器人；
   - 同一发送者发一条相同消息；
   - 验证 `MATCHED` 或如实标 `NOT_RUN`（若 OneBot/官方权限/群设置不满足）。
8. A 真实扫码、B 真实手机扫码；
   - 用户参与才 PASS，否则 NOT_RUN。
9. 更新 `STATUS.md` 和必要时报告，列证据/未验证项。

### Phase 12：打包、文档与交付

交付：

1. README/Windows MCP 指南补充官方账号配置与二维码用法；不写 secret。
2. `docs/windows-mcp.md` 更新官方 transport 选择。
3. `docs/upstream` 记录新发现/限制。
4. jpackage/jlink 依赖检查（若新增 QR 库、java.desktop 等）。
5. app-image smoke 与 Linux/Windows 可执行性记录。
6. 发布/提交/推送仅在用户明确授权后执行。

## 4. 文件/模块落点

| 路径 | 责任 |
|---|---|
| `pom.xml` | 加入新模块 |
| `bma3-qq-official-connect/` | 官方认证、HTTP、WS、事件、官方 tools、B QR |
| `bma3-qq-official-connect/AGENTS.md` | 新目录边界/验证 |
| `bma3-core/` | 通用 channel/observation/evidence 模型 |
| `bma3-inbox/` | canonical source、display channel、channel summary、迁移 |
| `bma3-app/` | 官方 registry、QqChannelRegistry、reconciler、工具装配、优先级 |
| `bma3-qqconnect/` | 不破坏；提供 raw source 详情与测试 |
| `tools/qq-official-onboarding/` | A 的隔离 Node 工具和锁文件 |
| `bma3-app/src/main/resources/config-template/app.yaml` | 官方配置占位符 |
| `docs/agents/STATUS.md` | 每阶段证据和 NOT_RUN |
| `docs/upstream/2026-11-17-qq-official-bot-api.md` | 官方契约 |
| `docs/superpowers/specs/2026-11-17-qq-official-connect-and-inbox-channel-correlation-design.md` | 设计正本 |
| `docs/superpowers/plans/2026-11-17-qq-official-connect-and-channel-correlation-plan.md` | 本计划 |

## 5. 测试矩阵

| 层 | 必测 |
|---|---|
| 配置 | 合法/非法值、inline secret 拒绝、env/file 二选一 |
| 凭据 | 缺失、权限失败、读取、轮换、日志脱敏 |
| Token/API | 成功、业务错误、HTTP 错误、刷新、单飞、trace_id |
| WebSocket | Hello/Identify/Ready/Heartbeat/Resume/Reconnect/终止错误 |
| 事件 | C2C、群 @、群全量、附件、引用、卡片、重复、未知、时间 |
| 消息板 | 原始 source 幂等、两渠道独立、正文/媒体边界 |
| 官方工具 | 读、能力不可用、权限错误、多账号、本地历史 |
| 发送 | 文本、回复窗口、msg_seq、UNKNOWN、quota、媒体 |
| 绑定 | 1:1、幂等、持久化、解绑、pending observation、跨账号拒绝 |
| Inbox | canonical item、单展示渠道、晚到证据、暂停、回退、优先级、迁移 |
| 一致性 | MATCHED/ONLY/UNVERIFIED/CONFLICT/歧义、strict/loose/off |
| QR | B URL/PNG/可解码；A 成功/取消/过期/权限失败/不泄露 |
| 安全 | secret/token/auth_token 不进入日志/结果/快照/提交 |
| 打包 | Maven 全量、插件 npm、smoke、Windows 原生（如可运行） |

## 6. 真实联调前置条件

用户需要提供/配合的真实条件：

1. 官方机器人在测试群中；群内 @ 机器人可触发 `GROUP_AT_MESSAGE_CREATE`。
2. 测试用户可与机器人单聊；会触发 `C2C_MESSAGE_CREATE`。
3. 如需全量群消息，机器人已获得“接收所有消息”权限并在群设置开启；否则全量事件继续 `NOT_RUN`。
4. 如需双渠道验证，同一测试群还要接入现有 OneBot/SnowLuma，且两边都能收到同一条消息。
5. A 扫码需要用户用手机 QQ 扫描；B 分享二维码需要用户实际扫码打开。
6. 真实发送必须单独获得当次授权；读取验收不包含外发。

## 7. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 官方 connector 内部端点变化 | 锁 Node SDK 版本；onboarding 明确失败，不静默降级；主运行不需要 Node |
| 官方 openid/OneBot 账号不可映射 | 显式 Agent 身份绑定；默认 UNVERIFIED；不自动猜测 |
| 群全量消息权限不足 | capability 状态 + 明确错误；跨渠道证据不足时 NOT_RUN |
| 官方消息与 OneBot 内容格式差异 | 归一化器 + fixture；卡片/引用等不可比较项不强行匹配 |
| 第二渠道晚到造成重复唤醒 | canonical source + evidence-only 晚到路径；冻结 item 不改正文 |
| 绑定后丢失某渠道独有消息的可见性 | 原始消息板和模块详情工具保留；Agent 可拆绑/暂停/读取 |
| Inbox schema 迁移损坏旧数据 | 先迁移测试 + 备份；旧字段默认空；可回退到 raw source |
| 分享/onboarding 泄露 secret | 只输出 QR/成功状态；凭据直写 owner-only 文件；日志扫描 |
| 发送重复 | 现有消息板三态 + 官方 msg_seq；UNKNOWN 不重发 |

## 8. 提交/发布边界

- 本计划完成后先不 commit/push；用户明确授权后才提交。
- 不修改其他项目（尤其 `/home/cna/DevMosire-iiBot`）。
- 不把凭据文件、数据库、日志、二维码 PNG、构建产物纳入提交。
- 未跑过的真实项保持 `NOT_RUN`，不以 fake/回环结果冒充。
