# @mosire/bma3-dsh

BMA3 的 DSH 薄插件：Inbox SSE 唤醒、工作区人设、默认工具面。

## 工作区文件

把 [`examples/systemprompt.md`](examples/systemprompt.md) 和
[`examples/agentconfig.json`](examples/agentconfig.json) 复制到 DSH 会话的工作区根目录：

```text
<workspace>/systemprompt.md
<workspace>/agentconfig.json
```

- `systemprompt.md`：**就是完整的 system prompt**。默认 `complete:true`——它整份替换 DSH 默认提示词，
  插件不拼接任何内置台词、不加安全约束；每次 step 组装前按 mtime/size 重读。
  文件缺失/为空时**不挂任何 section**（宁可什么都不注入，也不塞内置人设）。
  只有你在 `agentconfig.json` 里显式写 `"prompt": {"complete": false}` 才会退化成"插一段、保留 DSH 默认提示词"。
- `agentconfig.json`：默认工具组 + `prompt.complete`（默认 `true`）；不含任何 `lock` 字段。
  Agent 可以改它，也可以用 `bma3_self_reload` 立即重读（`complete` 翻转会在下一次 reload 重挂 section）。

## 默认工具面

`defaultTools` 支持：

| 组 | 匹配 |
|---|---|
| `mcp_admin` | `mcp_connector_*`、`mcp_probe` |
| `bma3_core` | `inbox_list/ack/status`、`qq_*` 基本工具 |
| `terminal` | `bash` / `pwsh`（按平台） |
| `image` | `read_image` |
| `subagent` | `subagent`、`send_message`、`interrupt_agent`、`list_agents` 等（fork 未挂） |

也可以直接写具体工具名。

## 锁定

`bash`/`pwsh` 和 `read_image` 不是靠 `agentconfig` 的 `lock` 字段锁的，而是**在 preset
composition 里挂载**：它们是 scoped tools，`agentconfig` 的全局 allowlist 无法删除它们。
`subagent` 工具同样是 preset composition 提供的 scoped tools；`agentconfig` 只选择
global MCP/bma3 工具组，不会删除这些 scoped 工具。

子 Agent 的 spawn 走 DSH 内置 `minimal` preset；`minimal` 本身固定包含 `read_image` +
terminal，且不挂本插件的人设 loader。`subagent_fork` 继承父 preset、不能切 `minimal`，
所以本 preset 不挂 fork 工具，避免出现非 minimal 子 Agent。

## 工具

- `bma3_self_status`：查看 prompt/config hash、默认组解析结果、缺失组和锁定工具。
- `bma3_self_reload`：重读两个工作区文件并重新应用默认工具组；下一步生效。
- `bma3_inbox`：**一个工具管本会话的 inbox 挂载**：
  - `action=status`（默认）：绑定会话 / 当前调用会话 / `thisSessionIsTarget`、SSE 连接与运行状态、
    `lastRevision`/`injectedRevision`/`ackedRevision`/`pendingRevision`、`paused`、`lastError`，
    以及配置来源（`baseUrl`/`inboxId`/`autoStart`/`tokenSource`；token 只报来源，不回显值与路径）。
  - `action=bind`（可带 `sessionId`，省略=当前会话）/ `unbind`：改目标会话。
  - `action=pause` / `resume`：暂停/恢复注入。暂停期间**照收 SSE、照更新 revision，但不注入也不 ack**
    （所以恢复后不会丢那一条）。
  - `action=start` / `stop`：开关 SSE。
  - `action=ack`：推进面板读游标。

## 启用

DSH profile 里启用 `bma3-preset` loader row，选择 **BMA3** preset。旧的全局
`bma3-persona` loader 行与 `persona` 模块**已删除**（它会注册第二个 complete system-prompt 段，
且从不释放自己注册的 section，导致插件每次重载后老会话报
`multiple complete prompt sections are active`）。人设现在只由 preset 内的 `self` 模块注册，
段名是 DSH 预留的 `deployment:persona-prefix`（scoped 遮蔽部署级人设）。

`self` 还会在 agent scope 注册一个**同名空段** `bma3-persona`：旧进程里那个没被释放的
global complete 段会被它遮蔽，于是热重载后老会话不需要重启就能恢复。
