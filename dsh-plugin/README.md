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
  - `action=bind`（可带 `sessionId`，省略=当前会话）/ `unbind`：改目标会话并**落盘**。
  - `action=restore`：恢复持久化绑定（必要时 `agents.resume`）并启动 SSE。
  - `action=pause` / `resume`：暂停/恢复注入。暂停期间**照收 SSE、照更新 revision，但不注入也不 ack**
    （所以恢复后不会丢那一条）。
  - `action=start` / `stop`：开关 SSE。
  - `action=ack`：推进面板读游标。
  - `action=status` 额外返回 `pushRevision`、`lastPushRevision`、`lastMcpStartId`、
    `appliedControlId`、`agentLive`、`stateFile`。

## 斜杠命令 `/inbox`

工具有个自举问题：被推送唤醒的往往就是**目标会话本身**，让它"帮我调一下 `bma3_inbox status`"很别扭，
解绑时更是死锁。所以另开一条**人直接能用**的命令面（D-057）：

```
/inbox [status|bind [sessionId]|unbind|detach|restore|pause|resume|start|stop|ack]
```

| 子命令 | 作用 |
|---|---|
| `status`（默认） | 绑定会话 / SSE / 游标 / 暂停 / MCP / 状态文件 / 配置来源 |
| `bind [id]` | 绑到指定会话；省略 id 则绑到**当前会话**（bind 会 restore + 必要时 resume） |
| `unbind` | **只清绑定与落盘，不停 SSE**（与工具语义一致） |
| `detach` | 解绑 **+** 停止 SSE —— 彻底脱手用这个 |
| `restore` | 恢复持久化绑定并 resume |
| `pause` / `resume` | 暂停/恢复注入（SSE 保持） |
| `start` / `stop` | 开关 SSE（保留绑定） |
| `ack` | 推进读游标 |

```
> /inbox status
Inbox bma3 · 运行中
绑定:     7f3a2b1c…（本会话）
SSE:      open
游标:     push=42 ack=42 待注入=0
注入:     未暂停 · MCP 已连接
状态文件: ~/.dsh/storages/bma3-inbox.json
配置:     http://127.0.0.1:46500 · token 来源 env:BMA3_INBOX_TOKEN
```

**几点约定**

- 命令结果由 UI 直接渲染，**不进模型上下文**（不会变成一条用户消息丢给 Agent）。
- `status` 只报 token **来源**，绝不回显值。
- 未绑定时会多一行提醒：新面板会注入到第一个 idle 会话，要彻底停下用 `/inbox detach`。
- **`commands` 服务缺失时不注册**（只 warn），不影响 `preset`/`self` 模块 ——
  包级 `inject` 是三个模块共享的，把 `commands` 加进去会连带 gate 掉那两个模块。
- 注册走 `ctx.effect(...)`，插件重载不留重复定义。

## 启用

DSH profile 里启用 `bma3-preset` loader row，选择 **BMA3** preset。旧的全局
`bma3-persona` loader 行与 `persona` 模块**已删除**（它会注册第二个 complete system-prompt 段，
且从不释放自己注册的 section，导致插件每次重载后老会话报
`multiple complete prompt sections are active`）。人设现在只由 preset 内的 `self` 模块注册，
段名是 DSH 预留的 `deployment:persona-prefix`（scoped 遮蔽部署级人设）。

`self` 还会在 agent scope 注册一个**同名空段** `bma3-persona`：旧进程里那个没被释放的
global complete 段会被它遮蔽，于是热重载后老会话不需要重启就能恢复。

## 预设工具行从宿主安装树解析

DSH 的 preset 子行按**声明它的 loader 基点**解析——也就是 profile。profile 里没有 `@deepseek-ai`
scope，所以早先版本写的裸包名 `@deepseek-ai/dsh-tool-bash` 一律 `ERR_MODULE_NOT_FOUND`，
bma3 会话因此从建立起就没有 bash / read_image / 子 Agent。

把这些包装进 profile 也解决不了：它们把 `@deepseek-ai/dsh-tools`、`dsh-llm`、`dsh-sandbox`、
`cordis` 等整个宿主运行时声明成 **peerDependencies**，从 profile 导入会直接失败：

```
Cannot find package '@deepseek-ai/dsh-tools' imported from .../dsh-tool-bash/lib/index.js
```

唯一能一起解析的地方是**宿主安装树**。`lib/host-tools.js` 在那里解析这 5 个工具包：

```
createRequire(process.argv[1]).resolve('@deepseek-ai/dsh-web-app')   # 宿主里声明它们的包
  → createRequire(<dsh-web-app>/package.json).resolve('@deepseek-ai/dsh-tool-bash')
  → file:///…/dsh-tool-bash/lib/index.js                              # 作为 preset 行名
```

解析不到时回退成裸包名（该行挂不上，但不会拖垮整个插件），并在 `bma3_self_status.presetTools`
里逐项报告解析结果，便于一眼看出是"没解析到"还是"没挂上"。


## 持久化与自动恢复（0.0.4）

- 状态文件默认 `~/.dsh/storages/bma3-inbox.json`，可用 `config.stateFile` 覆盖；
  只写 `targetSessionId`、`paused`、`stopped`、`injectedPushRevision`、`lastPushRevision`、
  `lastMcpStartId`、`appliedControlId`，**不写 token**。
- `bind` 成功后写入 `targetSessionId`；DSH 重启后模块加载时若存在绑定，会
  `ctx.agents.get(id) ?? ctx.agents.resume({resumeSessionId:id})`，再自动 `start()` SSE。
  `stop` 会持久化“用户手动停”，重启后不会自己起来。
- 注入去重键是 **`panel.pushRevision`**（不是 `panel.revision`）：同一推送水位重启后不会重复注入；
  服务端 Java 重启也不会把已冻结条目再推一遍。
- 每 15s `POST /v1/inboxes/{id}/plugin/heartbeat` 上报 `sessionId/bound/sse/lastPushRevision/
  lastMcpStartId/lastError/appliedControlId`；响应可带 `control`（`restore`/`remind`）。
  插件应用后回报 `appliedControlId`，服务端清除 pending control。
- `mcpStartId` 变化（BMA3 进程重启）时注入一次提醒，让 Agent 必要时重连 MCP。


## 0.0.4-hotfix1：lockedTools 修复（bash/read_image 重装后消失）

### 根因

- `host-tools.js` 过去把**解析失败也写进缓存**：`dsh plugin` 重装期间宿主树短暂不可解析，缓存里留下 `undefined`，之后同一进程里 `presetDefinition()` 一直拿到裸包名，`tool-bash` / `tool-fs` 行挂不上，`bash`/`read_image` 就从会话里消失。
- `bma3_self_status.lockedTools` 之前只看 **global** 工具视图；bash/read_image 是 agent-scope 工具，所以即使它们其实在，状态也会显示空数组，误导排查。
- `bma3_self_reload` 只重应用 `agentconfig` 的工具组，不会重新挂 preset 子行。

### 修复

- `host-tools.js`：**不再缓存 miss**；新增 `invalidateHostToolCache()`，`presetDefinition()` 每次注册前强制重解析。
- `bma3_self_status`：`lockedTools` / `missingLockedTools` 改为按当前 agent 的可见工具（`definitions(agent)` + `get(name, agent)`）计算；`requiredLockedTools` 按平台给出 bash/read_image 或 pwsh/read_image。
- 新增 `bma3_self_repair`：从宿主安装树直接 `import()` `dsh-tool-bash` / `dsh-tool-fs`，用 `ctx.plugin(...)` 挂到当前 agent scope；3s 内未完成注入会返回 `MOUNT_PENDING_INJECT`，不会卡死。
- `agent/created` 后 500ms、`tools/change` 后 250ms 自动尝试 repair；`bma3_self_reload` 也会先 repair 再返回状态。
- 如果 preset 行后来才挂上，repair 再检查一次；已有同名工具不会重复挂。

### 验证

- `cd dsh-plugin && npm test` → **28 tests**：
  - scoped `bash`/`read_image` 不会污染 `tools.restrict()` 的 allow；
  - `bma3_self_status.lockedTools` 能看到 scoped 工具；
  - `bma3_self_repair` 能挂载缺失的 bash/read_image 并让 `missingLockedTools` 变空；
  - host tool miss 不再被永久缓存；`invalidateHostToolCache` 能清掉正缓存。

安装后先跑一次 `bma3_self_status`：若 `missingLockedTools` 非空，再跑 `bma3_self_repair`，然后重新调用 `bash`/`read_image` 验证。
