# 调试通道 / 触发规则 / 定时任务设计（0.0.8 批次）

> **状态**：已确认（2026-11-15）。用户在本会话回复「三个都做」与「不要单飞限制」，据此确认本批三个切片全部开工。
> **日期**：2026-11-15（UTC+8）
> **范围**：① watchdog 侧远程问诊 HTTP API；② `weighting.rules` v2（regex / 来源作用域 / 可配命令前缀 / 优先级解释工具）；③ 定时任务模块。

## 1. 背景和需求

- 用户原话（2026-11-15）：「先提交并推送一下……所以这也他妈是Watchdog功能更新的必要性，我认为应该先确保目前的改动可用（不是可靠，是可用），然后立刻开始完善watchdog，允许通过httpAPI调试模式，从本地向远程的Agent直接问问题，以此获得1手调试信息」。
- 用户原话（2026-11-15，本会话）：「远程对于0.0.7early的运行没问题，现在你可以开始加功能了（？告诉我你准备加哪些功能，等我确认」；确认「三个都做」「不要单飞限制」。
- 0.0.7-early 已在远端运行正常；本批是功能增量，不是止损。
- **一个必须先说清的事实纠正**：此前「配置层没有文本前缀规则」的结论只对 0.0.3 时代的 jar 成立。当前源码自 `de15ba4`（0.0.3-hotfix7「正文规则表」）起已有 `AppConfig.ContentRule`（`match = prefix | contains`，`pattern`，`delta`，`ignoreCase`），`PriorityResolver.contentDelta()` 会把命中项叠加进分数。所以 `.rd` 的**权重**今天就能用配置解决，不需要改 Java。本切片要补的是「不可发现 + 不能按来源限定 + 不能正则 + 命令信号写死」这四件事，而不是重造规则表。
- 既有约束不变：`/healthz` `/readyz` `/watchdog` 语义不动；默认只绑回环；token 单一来源 `.bma3env`；正文不落日志；消息板是业务事实源；未跑过的验证写 `NOT_RUN`。

## 2. 切片 A：远程问诊通道（debug API）

### 2.1 端点

| 方法与路径 | 作用 |
|---|---|
| `GET /debug/snapshot` | 版本、pid、uptime、watchdog 快照（含最近事件）、inbox `status` 与插件心跳、`bot.db`/`inbox.db`/`schedule.db` 字节数 |
| `POST /debug/ask` | `{question, timeoutSeconds?, wait?}` → 注入被绑定会话并等待回答；成功 `200 {status:"answered"}`，超时 `202 {status:"pending"}` |
| `GET /debug/ask/{requestId}` | 拉单条状态（`PENDING`/`ANSWERED`/`EXPIRED` + 回答） |
| `GET /debug/ask?status=pending&limit=` | 列表（排查用，不含正文以外的敏感字段） |
| `POST /debug/answer` | `{requestId, text}`，给无 MCP 工具的调用方留的 HTTP 回话口；`bma3_debug_reply` 工具内部走同一路径 |
| `GET /debug/logs?since=<ISO 时刻>&limit=` | watchdog 事件尾（只含 ID/状态/计数/错误码/耗时；事件本身没有序号，`since` 按时间过滤） |

### 2.2 不变量

- **I-A1 认证隔离**：`/debug/*` 只接受 `DEBUG_TOKEN`（`.bma3env` 第三条，与 `MCP_TOKEN`/`INBOX_TOKEN` 不同值）。错误或缺失 token → `401`；debug 面未启用 → `404`（不泄露存在性）。`/watchdog` 继续用 `MCP_TOKEN`，行为不变。
- **I-A2 只绑回环**：复用 health server 的 host/port；非回环地址启动即 fail-closed（沿用 `HealthServer` 现有校验）。
- **I-A3 无鉴权模式关闭**：`security.auth=none` 时整个 debug 面不挂载（`404`）。理由：该模式下 token 不构成边界，远程问诊面不应比它更宽松。
- **I-A4 提问必须经 Inbox**：`POST /debug/ask` 只通过 `InboxService.append` 生成 `bma3_debug/<requestId>` 条目（`priority` 默认 350 ≥ `immediateThreshold`），不新增投递通道、不直接驱动 DSH、不写消息板正文以外的副本。
- **I-A5 并发**：不做单飞（用户裁定）。以 `debug.maxPending`（默认 32，`0` = 不限）封顶，超限返回 `429`；已有的 `PENDING` 不会被新提问挤掉。
- **I-A6 生命周期单向**：`PENDING → ANSWERED | EXPIRED`。过期后回答一律 `409 request_expired`；重复回答不覆盖首个答案（`409 already_answered`）。进程重启后 `PENDING` 保留，按剩余/重置期限继续计时，**不重复注入**（同 requestId 至多一条 inbox 条目）。
- **I-A7 正文不落日志**：审计 `debug/audit.jsonl` 只记 `requestId`、问题/回答字节数、状态、耗时、调用方地址；问题与回答只存 `debug/requests.json`（`0600`）。
- **I-A8 有界**：问题 ≤ `questionMaxBytes`（默认 4096）、回答 ≤ `answerMaxBytes`（默认 16384），超限 `413` 且不注入；保留最近 `retention`（默认 200）条请求。

### 2.3 验收判据

| 编号 | 判据 |
|---|---|
| A1 | 用 `MCP_TOKEN` 或 `INBOX_TOKEN` 访问 `/debug/snapshot` → `401`；用 `DEBUG_TOKEN` → `200` |
| A2 | `POST /debug/ask` 后 inbox 面板出现 `bma3_debug/<id>` 条目，priority ≥ 300，且同 requestId 不重复注入 |
| A3 | `bma3_debug_reply` 后 `GET /debug/ask/{id}` → `ANSWERED`，文本逐字一致 |
| A4 | 超时后状态 `EXPIRED`；迟到的 answer → `409` |
| A5 | `maxPending=1` 时第二个未答提问 → `429`；`maxPending=0` 时不限 |
| A6 | 重启进程后 `PENDING` 仍可 GET；不产生第二条 inbox 条目 |
| A7 | `security.auth=none` 下 `/debug/snapshot` → `404` |
| A8 | 审计文件里搜不到问题或回答原文 |
| A9 | 超长问题 → `413`，且 inbox 未新增条目 |

## 3. 切片 B：触发规则 v2

### 3.1 现状

- `weighting.sourcePriority`：来源键最长前缀匹配（`PriorityRules`）。
- `weighting.attributes`：属性名 → 增量（`qq_at_bot`/`qq_reply_to_bot`/`qq_self_name`/`qq_command`/`qq_person_id/<qq>`）。
- `weighting.rules`：有序正文规则，`match=prefix|contains`，命中累加。
- `QqSignalExtractor`：`qq_command` 写死「正文以 `/` 或 `!` 开头且为私聊或 @bot」。

### 3.2 增补

1. `ContentRule.match` 增加 `regex`；`pattern` ≤ 200 字符，编译失败启动即拒（fail-fast，不静默降级）。匹配对象仍是正文，`ignoreCase` 语义不变。
2. `ContentRule.source`：可选来源作用域，按 `sourceKey` 前缀语义（与 `PriorityRules` 一致，`/` 边界），空 = 全来源。用于「只在某个群把 `.rd` 当命令」这类需求。
3. `weighting.commandPrefixes`（默认 `["/","!"]`）：`qq_command` 由配置派生，不再写死；`.rd` 只要加 `.` 就能同时拿到 `qq_command` 信号和权重。
4. 新只读工具 `bma3_priority_explain({text, source, senderId?, senderName?})`：返回 `priority`、`dropped`、`immediate`、命中规则链与阈值结论。它与 `Bma3App.ingest` 使用**同一个** `PriorityResolver`，不允许第二套算术。

### 3.3 不变量

- **I-B1 规则只改分数**：`dropBelow` / `immediateThreshold` / 推送调度语义不变。
- **I-B2 叠加顺序稳定**：按配置顺序逐条累加，理由链顺序可复现。
- **I-B3 配置错误 fail-fast**：非法正则、超长 pattern、非法 `source`、非法 `commandPrefixes`（空串/含空白）启动即拒。
- **I-B4 单一算术**：explain 与 ingest 必须走同一 `resolve` 调用；测试断言两者 `reasons` 一致。
- **I-B5 热重载**：`weighting` 改动后下一次 ingest/explain 立即生效（现有 `ConfigWatcher` 路径）。
- **I-B6 向后兼容**：不含 `regex`/`source` 的旧 `rules` 行为逐字节不变。

### 3.4 验收判据

| 编号 | 判据 |
|---|---|
| B1 | 旧配置（prefix/contains）分数与理由链不变 |
| B2 | `regex` 命中/不命中各一例；`ignoreCase` 生效 |
| B3 | `source` 作用域只影响指定来源，其他来源分数不变 |
| B4 | `commandPrefixes` 含 `.` 时 `.rd` 得到 `qq_command`；不含时不得到 |
| B5 | `bma3_priority_explain` 与 ingest 对同一输入给出相同 `reasons` |
| B6 | 非法正则 / `pattern` 超长 → 启动报错 |

## 4. 切片 C：定时任务

### 4.1 模型

- 新模块 `bma3-scheduler`（依赖 `bma3-core` + `bma3-agentlib`；不依赖 inbox，通过 `ScheduleSink` 回调在 app 装配）。
- `schedule.db`：
  - `schedule(id TEXT PK, kind TEXT, spec TEXT, text TEXT, priority INT, enabled INT, next_run_at INT, last_run_at INT, missed_policy TEXT, created_at INT, updated_at INT)`
  - `run(id TEXT PK, schedule_id TEXT, due_at INT, fired_at INT, status TEXT)`，唯一索引 `(schedule_id, due_at)`
- `kind`：`once`（`spec` = ISO-8601 本地时刻）、`interval`（`spec` = 秒数，≥ 1）、`cron`（5 字段子集：`*`、`n`、`a-b`、`*/n`、`a-b/n`、逗号列表；不支持秒/年/@reboot）。

### 4.2 行为

- 单线程 tick（`schedule.tickSeconds` 默认 1，范围 1..60）；到点先写 `run` 并 commit，再调用 `ScheduleSink.fire()` 注入 inbox（`source = bma3_task/<scheduleId>`，priority 默认 350）。
- 重启：`missedPolicy=skip`（默认）→ 直接推进到下一个未来时刻；`once` → 立即补跑一次。
- 工具：`bma3_schedule_create`、`bma3_schedule_list`、`bma3_schedule_get`、`bma3_schedule_cancel`、`bma3_schedule_set_enabled`。
- 上限 `schedule.maxSchedules`（默认 200）。

### 4.3 不变量

- **I-C1 先落库后通知**：`run` 行 commit 成功之后才注入；注入失败只把该 run 标 `FAILED`，不伪造 `fired`，也不自动重试刷屏。
- **I-C2 至多一次**：同一 `(schedule_id, due_at)` 唯一索引保证重启/并发下不重复触发。
- **I-C3 表达式严校验**：非法 cron/interval/时刻创建即拒，不存「大概能跑」的表达式。
- **I-C4 tick 线程不执行用户任务**：只发通知，长活由 Agent 在会话里做。
- **I-C5 事实源**：任务与运行历史在 `schedule`/`run` 表；inbox 只做通知；不写消息板（任务事件不是会话消息）。

### 4.4 验收判据

| 编号 | 判据 |
|---|---|
| C1 | `once` 到点触发一次，`run` 行 `fired` |
| C2 | `interval` 连续触发，间隔误差 ≤ 2s（测试时钟下） |
| C3 | cron `*/5 * * * *` 在模拟时钟里每 5 分钟 1 次，且不重复 |
| C4 | 重启后 `skip` 不补跑；`once` 补跑一次 |
| C5 | 注入失败 → run `FAILED`，不重试 |
| C6 | 取消/停用后不再触发 |

## 5. 非目标

- 不做「通过 debug API 执行 shell / 读写文件」——那是另一个权限面，不在本批。
- 不开放非回环监听，不加 TLS/反向代理。
- 不做 cron 秒级字段、年字段、`@reboot`。
- 不改 Inbox 的冻结 / ACK / 推送水位语义。
- 不做长期记忆、任务看板、B 站、QQ 空间、战况导出、自动战棋流水线。
- 不做 `inbox.js` 拆分重构。
- 不发布、不部署远端（等用户单独指令）。

## 6. 门禁

- `./mvnw -q -o test` 全绿；本批新增测试只覆盖上述验收判据，不堆重复用例（用户已要求减少冗余测试）。
- 本机真机：18765/18766/18767 + 3090 + FakeOneBot 上验证 A2/A3/A4、B4（`.rd`）、C1。
- 本批不改 DSH 插件，`dsh-plugin npm test` 仅在插件文件被动到时重跑。
