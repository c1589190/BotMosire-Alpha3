# @mosire/bma3-dsh

BMA3 的 DSH 薄插件。**只做两件事**：

| 模块 | 作用 |
|---|---|
| `inbox` | 把 BMA3 Inbox 注入 DSH 会话：SSE 唤醒、revision 推送、`/inbox` 斜杠命令 |
| `preset` | 注册 `bma3` 预设：注入你的系统提示词 + 挂一层薄工具面 |

**没有白名单，没有 `restrict`，没有策略层** —— 预设挂什么，会话就看到什么（D-067）。
0.0.5 的 `defaultTools` 组别白名单已删除：它依赖别的插件先把工具名注册进全局层，而且
allow 从全局视图算，会把预设自己挂的 `bash`/`read_image`/`subagent` 一起裁掉。

## 预设：人设 + 薄面

提示词文件默认 `~/.dsh/bma3/systemprompt.md`（`$DSH_HOME` 优先），示例见
[`examples/systemprompt.md`](examples/systemprompt.md)。

```yaml
- id: bma3-preset
  name: '@mosire/bma3-dsh'
  config:
    module: preset
    promptFile: ~/.dsh/bma3/systemprompt.md   # 可省，省略即默认
    complete: true                            # 可省，默认 true
```

预设挂的 6 行（就是会话默认可见面）：

| 行 | 给什么 |
|---|---|
| `persona` | 你的 system prompt（遮蔽 `deployment:persona-prefix`） |
| `tool-bash` / `tool-pwsh` | `bash`（Windows 上是 `pwsh`） |
| `tool-fs` | **只有 `read_image`**（`read`/`write`/`edit` 关；文本读写走 bash） |
| `tool-subagent-control` + `tool-subagent` | 子 Agent（`agentPreset: minimal`） |
| `tool-plugin-manager` | 宿主有 `profileContext` 才挂；这是 Agent 自己挂插件的门 |

要更宽的面就把行加进 `presetDefinition()` 的 `toolRows()`；要收窄就删行。**不需要改任何策略**。

人设语义：

- `complete: true`（默认）：这份文本**就是**完整 system prompt，整份替换 DSH 默认提示词。
- `complete: false`：退化成"插在 persona 前缀位置"，保留 DSH 默认提示词。
- 文件缺失或为空 ⇒ **不挂任何 section**，绝不塞内置替身。
- 每次组装前重读，改了下一轮生效；读取失败保留上一份非空文本。

会话里选 `BMA3` 即生效。

## Agent 怎么自己扩工具面

| 想要 | 用什么 |
|---|---|
| 连 MCP server（含 bma3 jar） | `mcp_connector_configure` → 工具落**全局层**，名字 `mcp__<server>__*` |
| 加/减本地插件行 | `tool-plugin-manager`（预设已挂） |
| 按会话临时收窄 | `mcp_connector_session_tools`（内部就是 `agent.ctx.tools.restrict({deny})`） |

## Inbox

```yaml
- id: bma3-inbox
  name: '@mosire/bma3-dsh'
  config:
    module: inbox
    baseUrl: http://127.0.0.1:46500
    inboxId: bma3
    tokenEnv: BMA3_INBOX_TOKEN
    autoStart: true
    heartbeatMs: 15000
    # stateFile: ~/.dsh/storages/bma3-inbox.json
```

- 一个 inbox 一条 SSE；`source` 只是条目，不存在 per-source 订阅（红线 11）。
- 写盘内容只有 sessionId、水位、暂停标志。
- token 从 `tokenEnv` 指定的环境变量读；读不到就不带 Authorization 头发请求。
- 工具：`bma3_inbox`（status/bind/pause/resume/start/stop/ack）。

## Agent 怎么连 jar 的 MCP

jar 起来后会在数据目录写 `.bma3env`（0600），里面有 `MCP_TOKEN` 和 `INBOX_TOKEN`。
端点是 `http://127.0.0.1:<port>/mcp`（默认 18765）。Agent 用 DSH 自带的连接器工具连接：

```text
mcp_connector_configure(
  name: "bma3",
  url: "http://127.0.0.1:18765/mcp",
  transport: "streamable-http",
  authMode: "bearer",
  bearerToken: <读 .bma3env 的 MCP_TOKEN>
)
```

配置通过校验后 Host 会挂载它，工具以 `mcp__bma3__*` 出现（约 40 个：`inbox_*`、`qq_*`、
`get_config`/`set_config`、`bma3_watchdog_*`）。想按会话临时启用某几个用
`mcp_connector_session_tools`。

不想用 token 就让 jar 用 `--no-auth` 启动，此时 `.bma3env` 里的 token 留空，
连接器改用 `authMode: "none"`。

> 红线 2（D-060）：Agent 自行配置连接时可以把 token 作为工具参数，它会落进当次会话日志；
> 但 token 不得进入提交、配置样例、仓库文件与导出/快照类输出。凭据文件必须 0600。

## 装到 profile

插件包自带 `cordis.patch.yml`，DSH 加载 profile 时会自动并入；也可以照上面的 YAML 手写进
`~/.dsh/profiles/<name>/cordis.patch.yml`。
