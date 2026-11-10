# @mosire/bma3-dsh

BMA3 的 DSH 薄插件：Inbox SSE 唤醒、工作区人设、默认工具面。

## 工作区文件

把 [`examples/systemprompt.md`](examples/systemprompt.md) 和
[`examples/agentconfig.json`](examples/agentconfig.json) 复制到 DSH 会话的工作区根目录：

```text
<workspace>/systemprompt.md
<workspace>/agentconfig.json
```

- `systemprompt.md`：人设正文；每次模型 step 组装前按 mtime/size 重读。
- `agentconfig.json`：默认工具组；不含任何 `lock` 字段。Agent 可以改它，也可以用
  `bma3_self_reload` 立即重读。

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

## 启用

DSH profile 里启用 `bma3-preset` loader row，选择 **BMA3** preset。旧的全局
`bma3-persona` row 已标记 disabled；人设现在只由 preset 内的 `self` 模块注册。
