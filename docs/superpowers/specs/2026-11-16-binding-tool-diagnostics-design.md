# DSH 绑定自愈、工具注册与诊断设计（0.0.7 后续）

> **状态**：已确认（2026-11-16，用户对「绑定自愈 + `bma3_inbox` 可靠注册 + 一屏诊断」范围回复「开始吧」）。
> **日期**：2026-11-16（UTC+8）
> **范围**：DSH Inbox 插件的会话绑定生命周期、`bma3_inbox` 工具在真实宿主的可见性、以及 Agent 侧的只读诊断面。

## 1. 背景与需求

远程 Agent 实测（`rq-05901ac20e2c`、`rq-0e912f803c44`）：

- DSH 重启后曾观察到 `targetSessionId=null`；当前已人工回绑，但根因未定。
- `bma3_inbox` 插件工具没有出现在远程 Agent 的原生工具列表中；插件本身在运行，state/SSE/心跳正常。
- Agent 希望不依赖人工就能 `status/bind/restore`，并能一次看到 jar/插件握手、deferred/fresh/ACK 冲突、最近错误。
- 远程当前版本 `0.0.7-hotfix1`，`inbox_get` 已返回 `frozenRevision`；fresh verification 的永久重试修复已发布但尚未在真实 DSH 混合 batch 上压测。

## 2. 目标与非目标

**目标**：

1. 未显式绑定但实际选择了可注入会话时，持久化该目标；DSH/插件重启后自动恢复，不能让运行时自动绑定只留在内存。
2. 让 `bma3_inbox` 在真实 DSH 中可发现：注册失败可见、可重试，并在 profile scope 之外尝试 agent scope 注册。
3. 新增一屏诊断，显示 `deferredPush`、`freshVerification`、ACK 冲突/错误、插件心跳、MCP 启动 id、目标绑定与工具注册状态；不返回正文/凭据。
4. 给 MCP 侧提供一个只依赖已连接 MCP 的插件控制兜底：可查看插件心跳/控制命令，并请求 `restore`，解决原生工具暂时不可见时无法自愈的问题。

**非目标**：不改变 Inbox ACK 协议、消息板或推送调度；不把调试口暴露公网；不实现嵌套转发、面板分页或 scheduler 扩展；不把 `bma3_inbox` 做成新的 MCP 业务工具替代 MCP 工具面。

## 3. 不变量

- **I-B1 绑定的持久性**：只要 `wake()` 从一个未绑定状态选中了目标会话，必须在 followup 前把 `targetSessionId` 和目标 push 记录一起持久化；persist 失败则不注入，状态保留为可诊断错误。
- **I-B2 不串会话**：已有 `targetSessionId` 时绝不因为“当前有别的 idle agent”而改用其它会话；自动绑定只在原本为 null 时发生。
- **I-B3 恢复兼容**：旧 v1/v2 状态里 `pendingPush.targetSessionId=null` 可作为“尚未选定目标”的 legacy 情况；在恢复时若选定目标，必须原子补写目标；非 null 且不匹配时仍 fail-closed。
- **I-T1 注册可重试**：`tools.register` 不可用/抛错时不能静默消失；至少尝试等待/重试，并把 `registered/attempts/lastError` 暴露到 client status、heartbeat 和诊断工具。
- **I-T2 双 scope**：除 profile/global 注册外，在获得具体 agent 时尝试 `agent.ctx.tools.register`，同名工具在 agent scope 内可见；重复注册要幂等，dispose 要清理。
- **I-D1 诊断无正文**：诊断只含 id、计数、状态、错误码、时间、版本和布尔值；不包含 token、Cookie、消息正文、原始 baseUrl/userinfo 或异常正文。
- **I-D2 控制兜底**：MCP 插件控制只能排队 `restore/remind` 控制并返回 pending/心跳；不能直接伪造 ACK、改变目标 ID、绕过身份检查或自动重放未确认消息。

## 4. 验收判据

| # | 场景 | 判据 |
|---|---|---|
| B1 | 未配置 sessionId，首次 push 选中 idle agent | 注入一次，v2 state 持久化 `targetSessionId`，status 显示已绑定；无第二次注入 |
| B2 | B1 后重启客户端 | 加载同一 state 后恢复目标并 resume/get，不回到未绑定 |
| B3 | 已有目标但有另一个 idle agent | 绝不改投其它会话 |
| B4 | legacy `pendingPush.targetSessionId=null` | 选定当前可用目标后原子补写；目标冲突仍 fail-closed |
| T1 | tools service 延迟可用/首次 register 抛错 | 有界重试后成功，或明确记录 failure；apply 不崩溃，SSE/ACK 仍可运行 |
| T2 | agent ctx 有 tools 而 profile tools 不可见 | agent scope 注册成功，`bma3_inbox` 出现在该会话；重复 wake 不重复注册 |
| D1 | 诊断调用 | 返回 jar/插件版本、mcpStartId、heartbeat、pendingControl、deferred/fresh/ACK 状态；无正文/凭据 |
| D2 | 原生工具不可见 | MCP `inbox_plugin_control` 可报告心跳并排队 restore/remind，返回控制 id；不能 ACK/改目标 |
| D3 | 回归 | 现有插件状态机、ACK、fresh verification、Java 全量测试通过 |

## 5. 实现落点

- `dsh-plugin/lib/inbox.js`：自动绑定/legacy pendingPush 目标补写、工具注册重试与 agent scope 注册、心跳诊断摘要。
- `dsh-plugin/index.js`：如需要，保持 package-level inject 只包含必要服务；工具注册失败不阻断 inbox。
- `bma3-inbox`：扩展 `PluginHeartbeat` 保存插件诊断摘要；新增只读 `inbox_diagnose` 和受控 `inbox_plugin_control` MCP 工具。
- 测试：插件状态/注册/心跳，Java tools/heartbeat，真实 DSH 仍需单列 `NOT_RUN`。


## 6. 2026-11-16 test2 增量：stale pendingPush 自愈与显式 recover

远端 0.0.7-test1 实测发现：`pendingPush` 指向的 item 已被服务端 ACK（`ackedRevision` 已越过该 push），但插件仍反复要求 exact frozen proof，导致 `pending_push_unverified` 循环和 `deferredPush=pending_push_waiting_for_recovery`。

新增不变量：

- **I-R1**：fresh GET 中若 pendingPush 的所有 item IDs 已不在面板，且服务端 `ackedRevision >= pendingPush.panelRevision`，该 pendingPush 已被外部/服务端事实推进；插件必须清除 `pendingPush`、`deferredPush` 和相关 mismatch，持久化后继续处理当前面板，不能继续等一个不存在的 panelRevision。
- **I-R2**：清除 stale pendingPush 不得误清仍有 item visible、或 `ackedRevision` 尚未越过的记录；这些仍走 exact proof/fail-closed。
- **I-R3**：显式 `recover` 只在无 pendingAck/injectionIntent、无在途 verify/inject、持久化可用且目标指纹匹配时执行；它清除 pendingPush/deferredPush、保留注入水位并从当前面板重试。必须 `confirm=true`。
- **I-R4**：`recover` 可通过 native `bma3_inbox`、`/inbox recover confirm`、MCP `inbox_plugin_control action=recover confirm=true` 排队；控制通道仍只排既有 control，不直接 ACK/改身份/写消息。

验收：

| # | 场景 | 判据 |
|---|---|---|
| R1 | pendingPush item 已 ACK、面板为空且 ackedRevision 越过 panelRevision | pendingPush/deferredPush 清除，freshVerification 完成，不注入 |
| R2 | pendingPush item 仍可见 / ackedRevision 未越过 | 不清除，不误注入，保持 fail-closed |
| R3 | 显式 recover | 清除 stale 状态并重新 GET 当前面板；无 pendingAck/intent 才允许 |
| R4 | MCP control recover | confirm=false 拒绝；confirm=true 排队 action=recover，plugin appliedControlId 更新 |
