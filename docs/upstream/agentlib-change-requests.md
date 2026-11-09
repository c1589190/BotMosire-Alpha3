# AgentLib 变更需求（BotMosire-Alpha3 → AgentLib）

> **日期**：2026-11-09
> **目标**：`io.mosire:agentlib-mosire` 0.2.5 及后续版本
> **状态**：0.2.5 已发布；A1/A2/A3 已进入库，BMA3 已迁移版本并使用 A2 批量注册；A1 仍用回环 Bearer 代理，A4/A5/A6 待评估。
> **背景**：BMA3 是独立常驻 Java 服务；`qqconnect` / `webconnect` / `inbox` 各自实现 `AgentTool`，注册到 app 的单一 `ToolRegistry`，由 `AgentToMcpServer` 暴露 MCP；DSH 只挂一个 connector。

## 0. 已确认的现状（不需要重做）

- `AgentTool`：name / description / jsonSchema / spec / roles / resources / execute。
- `ToolRegistry`：线程安全、`onChange` 增量通知、`unregisterAllBySource`。
- `AgentToMcpServer`：订阅 `ToolRegistry.onChange`，自动 `addTool`/`removeTool` 并通知 `notifications/tools/list_changed`；`startHttp` 可起 streamable HTTP MCP。
- `ToolSpec`：requiredLevel / sensitive / destructive / noExport。
- `ToolResult`：text + assetDocIds；`ToolAssetResolver` 可把图片资产出成 MCP ImageContent。

**结论**：BMA3 不需要每个 connect 模块自己起 MCP server；各模块实现 `AgentTool`，app 汇总到一个 registry 即可。

## P0 —— 组合根必须

| # | 要求 | 原因 | 验收 |
|---|---|---|---|
| A1 | `AgentToMcpServer.startHttp` 支持认证钩子（Bearer/token verifier）或官方认可的代理包装 | 当前 HTTP MCP **无鉴权、无 TLS**；BMA3 必须回环 + Bearer，不能把无鉴权面暴露出去 | 未认证 401；默认回环；非回环且未配置认证时 fail-closed；有单元测试；不破坏现有调用方 |
| A2 | `ToolRegistry.registerAll(String sourceId, List<AgentTool>)` 原子批量注册 | 热插拔 connect 包需要「一个 source 整组上线/下线」；现有 `registerAll(List)` 不带 source，`register(sourceId, tool)` 只能单条 | 全量校验；批内/跨批重名失败回滚；成功后一次 `onChange`；与 `unregisterAllBySource` 对称 |
| A3 | `ToolContext` 增加 deadline / cancellation（或等价的超时取消入口） | 浏览器 click/wait、网络工具可能长时间阻塞；MCP 客户端取消目前传不到 `AgentTool.execute` | 工具能读到 deadline/取消信号；超时/取消返回稳定 `TIMEOUT`/`CANCELLED`；旧 4/5 参构造保持兼容 |

## P1 —— 热配置与工具预设

| # | 要求 | 原因 | 验收 |
|---|---|---|---|
| A4 | `AgentToMcpServer.resync()` / `refresh()` 公开幂等入口 | 工具白名单/调用者权限变化但 registry 没增删时，当前 `include` 谓词不会重新求值 | 调用后按当前谓词重算暴露面；发 `tools/list_changed`；重复调用无副作用 |
| A5 | `AgentTool` / `ToolSpec` 增加可选 `group()`、`tags()`、`title()` 元数据 | DSH 工具组预设、Agent 自助配置、Web 管理面需要分组；现在只能靠工具名前缀猜 | 缺省空值，旧工具零改动；MCP 工具描述可选带 annotations/title；有测试 |
| A6 | 供给源元数据（displayName/version） | 管理面要知道「这个工具来自 qqconnect 0.3.1」；现在只有 `sourceIds()` 字符串 | 可选 `ToolSource` 注册/查询；不改变按 source 卸载语义 |

## P2 —— 可选增强

| # | 要求 | 原因 |
|---|---|---|
| A7 | MCP `outputSchema` / structured content 支持 | 浏览器 snapshot、会话列表等结构化结果对模型更稳；当前主要靠 text |
| A8 | 异步工具执行 / 更完整的取消链 | 长任务（浏览器、媒体）不阻塞 MCP 线程；A3 先解决最小闭环即可 |

## 非需求（明确不要改）

- 不要改变 `AgentTool` 的必填方法；新增能力一律 default/可选，保证旧工具零改动。
- 不要移除 `ToolSpec.noExport` / roles / resources / sensitive / destructive。
- 不要把 MCP auth 做成「默认无鉴权但只绑回环」的隐式约定；BMA3 需要显式可配置。
- 不要让 `AgentToMcpServer` 直接依赖某个具体 HTTP 框架；认证钩子保持传输无关。

## BMA3 侧的对应设计

- `app` 只建一个 `ToolRegistry`；`qqconnect` / `webconnect` / `inbox-tools` 各返回 `List<AgentTool>`，app 按 source 批量注册。
- `inbox-core` **不依赖** AgentLib；只有 `inbox-tools` 依赖。
- MCP 只暴露一个端点；Inbox 的 HTTP API 独立，不要求 DSH 挂第二个 connector。
- 工具热插拔 = `registerAll(sourceId, tools)` / `unregisterAllBySource(sourceId)`，由配置 reconcile 驱动。
