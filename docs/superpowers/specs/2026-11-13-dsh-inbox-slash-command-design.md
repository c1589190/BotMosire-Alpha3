# DSH 插件：`/inbox` 斜杠命令（手动查看状态 / 手动解绑）

> **状态**：当前有效（2026-11-13）
> **需求原话**：D-057「我发现现有 dsh 插件还是没有手动输入 `/inbox status` 查看状态、`unbind` 手动解绑 inbox 的命令，能不能补上」
> **关联**：D-043（同源可多条 / 推送即冻结）、D-045（绑定持久化，「bound once, bound forever」）、红线 1（只绑回环）、红线 2（密钥零泄露）。

## 1. 问题

`bma3_inbox` 工具已经能做全部挂载操作，但**只有 Agent 能调用**。人在 DSH 输入框里没有任何入口：

- 看不到 inbox 当前挂在哪个会话、SSE 通不通、游标推到哪。
- 想解绑必须先"请 Agent 帮我调个工具"，而 Agent 本身可能就是被推送唤醒的那个会话 —— 自举死锁。

DSH 提供斜杠命令：`ctx.commands.register({ name, description, input, handler })`，handler 返回
`{ kind: 'success' | 'error', text }`，**文本由 UI 直接渲染，不进模型上下文**。

## 2. 契约

### 2.1 命令

```
/inbox [status|bind [sessionId]|unbind|detach|restore|pause|resume|start|stop|ack]
```

| 子命令 | 行为 | 说明 |
|---|---|---|
| `status`（默认） | `client.status()` | 无参数等同 |
| `bind [id]` | `client.bind(id)` | 省略 id 则绑到**当前会话**；bind 会 restore + 必要时 resume |
| `unbind` | `client.unbind()` | **只清绑定与落盘，不停止 SSE**（与 MCP 工具语义一致） |
| `detach` | `client.unbind()` + `client.stop()` | 彻底脱手：解绑 + 停止 SSE |
| `restore` | `client.restore()` | 恢复持久化绑定并 resume |
| `pause` / `resume` | `client.pause()` / `client.resume()` | 暂停/恢复注入（SSE 保持） |
| `start` / `stop` | `client.start()` / `client.stop()` | 开关 SSE（保留绑定） |
| `ack` | `client.ack()` | 推进读游标到当前 push 水位 |

### 2.2 `status` 输出（人类可读，多行）

```
Inbox bma3 · 运行中
绑定:     7f3a2b1c…（本会话）
SSE:      open
游标:     push=42 ack=42 待注入=0
注入:     未暂停 · MCP 已连接
状态文件: ~/.dsh/storages/bma3-inbox.json
配置:     http://127.0.0.1:46500 · token 来源 env:BMA3_INBOX_TOKEN
```

未绑定时 `绑定: 未绑定`，并追加一行提醒：
`⚠ 未绑定状态下，新面板会注入到第一个 idle 会话；要彻底停下用 /inbox detach`。

### 2.3 错误

| 场景 | 返回 |
|---|---|
| `bind` 且既无参数、调用方也不是会话 | `kind: 'error'`，提示 `usage: /inbox bind <sessionId>` |
| 未知子命令 | `kind: 'error'`，列出可用子命令 |
| `commands` 服务缺失 | 不注册，`warn` 一行，**不影响其它模块** |

## 3. 不变量

- **I1 命令只动本插件的挂载状态**：不写消息板、不调 QQ provider、不改 provider 侧任何东西。
- **I2 结果不进模型上下文**：只用 `CommandResult.text` 由 UI 渲染，不以用户消息形式提交给 Agent。
- **I3 不泄漏密钥**：`status` 只报 token **来源**（`env:BMA3_INBOX_TOKEN` / `config`），绝不回显值。
- **I4 注册可回收**：经 `ctx.effect(() => commands.register(...))` 注册，插件重载不留重复定义。
- **I5 缺失 `commands` 不阻断**：包级 `inject` 是三个模块共享的，把 `commands` 加进去会连带 gate 掉 `preset`/`self`。
  因此**不改 `inject`**，改为运行时探测 `ctx.commands ?? ctx.get('commands')`，没有就跳过并 warn。
- **I6 与 MCP 工具语义一致**：同名 action 走同一个 `client` 方法，不另写一套状态机。
- **I7 `unbind` 不隐式停 SSE**（保持既有语义）；要彻底停下必须显式 `detach` 或 `stop`。

## 4. 验收判据

| # | 判据 |
|---|---|
| A1 | `/inbox`（空输入）→ `success`，文本含 `绑定:` 与 `SSE:` |
| A2 | `/inbox status` → 同 A1 |
| A3 | `/inbox bind <id>` → 调 `client.bind('<id>')`，文本含该 id |
| A4 | `/inbox bind`（无 id、无会话上下文）→ `error`，文本含 `usage: /inbox bind <sessionId>` |
| A5 | `/inbox unbind` → 调 `client.unbind()`，文本含「已解绑」且**含未绑定回退提醒** |
| A6 | `/inbox detach` → 同时调 `unbind()` 与 `stop()`，文本含「已停止」 |
| A7 | `/inbox 乱写` → `error`，文本列出 `status/bind/unbind/detach/...` |
| A8 | `ctx.commands` 缺失时 `apply()` 不抛异常，`bma3_inbox` 工具仍注册（回归保护） |
| A9 | `ctx.effect` 的 disposer 被调用后，命令定义从注册表移除（重载不留重复） |
| A10 | `status` 文本**不含** token 值，只含 `tokenSource` |
| A11 | 未绑定时 `status` 文本追加「第一个 idle 会话」提醒 |
