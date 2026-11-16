# Inbox fresh-verification 卡死热修复设计（0.0.7-hotfix1）

> **状态**：已确认（2026-11-16）。用户直接要求「先修复然后发布0.0.7-hotfix1」。
> **范围**：修复 DSH Inbox 插件在 ACK 后 fresh verification 永久停止重试、且 live `/panel` 证明过严导致的推送冻结。
> **关系**：这是 [发送与 Inbox 确认止损设计](2026-11-15-delivery-safety-design.md) 中 live GET 恢复路径的热修复修正；guarded ACK、pendingAck、pendingPush、SSE push 同批冻结证明与身份保护不变。

## 1. 需求原话

用户转述的远端现场（2026-11-16）关键原文：

> 「19:04:04 插件做 fresh verification，拿到的一批 panel item 里 `frozenRevision` 不一致（`fresh_verify_batch_mixed`），把推送标成 `deferredPush`，reason＝`panel item is not demonstrably frozen for a push`；然后它把校验标记为 **done**、`nextAt=null`——不再重试；我实测 panel item 里**根本没有 `frozenRevision` 字段**（`inbox_get` 确认），所以这个校验永远过不去，推送就冻住了。」

> 「1. fresh verify 失败不能 `done + nextAt=null`，必须排延迟重试；2. `/panel` 的 live GET 不应强制要求 `frozenRevision`，或服务端把 `frozenRevision` 带进 panel。」

用户随后明确要求：

> 「远程报告了一个需要修复的问题，并没有应用这个hotfix，请先修复然后发布0.0.7-hotfix1」

## 2. 根因与修复边界

- 根因一：`verifyFreshPanel()` 在 live snapshot 的 batch proof 失败时调用 `markFreshVerificationDone(...)`，把 `required` 设为 `false` 且 `nextAt` 设为 `null`；之后既不重试，也不能自动恢复积压。
- 根因二：fresh GET 是当前面板快照，不是一次 SSE push。它可能包含多个早期 push 留下的已冻结条目（`frozenRevision` 可不同），也可能来自尚未返回该字段的旧服务端。把 SSE push 的「整批同一 `frozenRevision`」证明原样套到 live GET 上，会使恢复永远过不去。
- 服务端 `/panel` 的 `PanelItem` 已包含 `frozenRevision`；`inbox_get` 的响应投影此前漏了该字段，增加它是诊断修复，不替代插件侧恢复逻辑。

## 3. 不变量

- **I-H1**：fresh verification 的可重试失败必须保留 `required=true`，写入有限 `nextAt`，按有界退避重试；禁止 `required=false + nextAt=null` 的终态。
- **I-H2**：live `/panel` 可作为一个 coherent recovery snapshot 注入，条件是：条目 ID 非空且唯一、`pushRevision` 为正且高于已注入水位、没有刚确认的 ACK item IDs；所有条目要么显式 `frozenRevision > 0`（允许混合 revision），要么整批省略该字段（旧服务端兼容）。显式 `frozenRevision=0`、部分缺失/部分存在或非法 revision 一律拒绝并重试。
- **I-H3**：SSE push 与 `pendingPush` 精确恢复仍要求全部条目同一 `frozenRevision=pushRevision`；live recovery 的放宽不得污染这两条路径。
- **I-H4**：guarded ACK 继续是服务端最终权威。客户端不得把未冻结条目标记为已确认；ACK 409 时保留原 pending 记录，不自动重放。
- **I-H5**：`inbox_get` 必须返回 `frozenRevision`，便于区分已推送/未推送与现场诊断。
- **I-H6**：不新增正文、token、baseUrl 或远端异常正文进入状态/日志；现有状态 v2 schema 与人工入口保持兼容。

## 4. 验收判据

| # | 场景 | 判据 |
|---|---|---|
| H1 | live `/panel` 所有条目省略 `frozenRevision` | 插件把它当 legacy recovery snapshot 注入一次，并由 guarded ACK 确认 |
| H2 | live `/panel` 条目有多个正 `frozenRevision` | 插件作为一个 coherent snapshot 注入一次，不拆批、不重复注入 |
| H3 | live `/panel` 显式 `frozenRevision=0` | 不注入、不 ACK；`freshVerification.required=true`、有限 `nextAt`，GET 有界重试 |
| H4 | SSE push 混 revision | 仍拒绝注入/ACK，回归测试保持 |
| H5 | `pendingPush` 精确恢复 | 仍要求原 revision/IDs/同一 frozen proof，不能走 live 放宽 |
| H6 | `inbox_get` | JSON 明确含 `frozenRevision`（未推送为 0） |
| H7 | 发布门禁 | Java 全量、插件全量、`packaging/smoke.sh` 通过；真实 DSH/QQ/远端部署仍单独标 `NOT_RUN` |

## 5. 非目标

- 不改变服务端 push 调度、冻结窗口、ACK 事务或 Inbox 数据模型。
- 不把 live GET 当成一次新的服务端 push，也不修改 pushRevision。
- 不自动重试 `UNKNOWN` 出站发送，不承诺跨系统恰好一次。
- 不在本热修复中做真实 DSH 会话、真实 QQ、远端部署或掉电演练。
