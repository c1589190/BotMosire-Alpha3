# BMA3 基础架构设计（Plan 1 范围）

> **状态**：设计基线（2026-11-09，待计划评审）
> **关联裁定**：[`../DESIGN_RULINGS.md`](../DESIGN_RULINGS.md) D-001..D-008
> **本设计只覆盖 Plan 1**：Java 服务骨架、core/store、AgentLib/MCP、QQ OneBot 11、Inbox、app 组合、DSH 插件与 jpackage 安装。webconnect/B 站/桌面控制后续单独设计。

## 1. 目标与非目标

**目标**

- 一个独立于 DSH 的 Java 21 常驻服务 `botmosire-a3`；
- QQ 通过 LLBot 单正向 WebSocket（OneBot 11）接入，多账号可热插拔；
- 统一消息板 + 单 Inbox 状态机 + 一个带 Bearer 的回环 MCP；
- 配置分模块、可校验、可热重载；结构化日志可动态调级；
- DSH 插件一站式检测/安装/启动服务，并提供 Inbox 注入、角色提示词、工具组预设；
- 用 `jlink`/`jpackage` 产出自带运行时的 app-image。

**非目标（Plan 1）**

- B 站私信/视频、webconnect/Selenium、Linux 桌面控制；
- 在服务内运行 LLM/AgentLoop；
- 完整 Web 管理前端（只留健康/HTTP API）；
- 旧 iibot 配置、工具名、数据库的完全兼容。

## 2. 模块与依赖

```text
bma3-core（领域 + SQLite store + 配置/日志 SPI）
  ├── bma3-agentlib（AgentLib 包装：ToolRegistry / MCP / Bearer）
  ├── bma3-qqconnect（OneBot 11 正向 WS / 账号 registry / QQ tools）
  └── bma3-inbox（单 Inbox 状态机 / HTTP API / inbox tools）
bma3-app（组合根：config、logging、lifecycle、MCP、health）
```

依赖方向只能向下：`app → {agentlib, qqconnect, inbox} → core`。`agentlib` 不依赖任何业务模块。

## 3. 核心模型

- `AccountId`：稳定业务 ID；一个账号一条 OneBot 连接。
- `SourceAddress`：`qq_group/{account}/{group}`、`qq_private/{account}/{peer}`、`qq_system/{account}/{event}`、`app_system/{event}`。
- `Conversation`：`accountId + type + targetId`；`threadId` 为不透明句柄。
- `Message`：入站/出站、`localSeq`、`messageKey`、segments、时间、发送状态。
- `Outbox`：`PENDING → SENT / FAILED_BEFORE_SEND / UNKNOWN`；`UNKNOWN` 不自动重发。
- `InboxItem` / `Delivery`：单 Inbox 的 available/delivered/acked/undelivered、revision、attempts、TTL、occupancy。
- `MediaAsset`：assetId、sha256、本地路径、来源；正文不进日志。

## 4. AgentLib / MCP

- 只用 `AgentTool` + `ToolRegistry` + `AgentToMcpServer`；不运行 `AgentLoop`。
- 一个 `ToolRegistry`；各模块按 source 注册/卸载；app 只起一个 MCP 端点。
- AgentLib 0.2.4 的 `startHttp` 无鉴权：复用 `BotMcpServer` 的「内部 loopback + 外部 Bearer 代理」模式。
- 老版本可用的 workaround：单条 `register(sourceId, tool)`、工具内超时、app 侧重启 MCP；等隔壁 P0 合并后替换包装层。
- 工具元数据/分组由 app 侧映射，等 AgentLib 提供 `group/tags` 再收敛。

## 5. qqconnect

- 第一版只做 OneBot 11 **正向 WS**：`ws://127.0.0.1:3001`，同一连接收事件/调 API；token 走环境变量。
- 多账号 = 多个 endpoint/连接；`AccountRegistry` 按 `accountId` 管理。
- 热插拔：配置 reconcile；新增/删除/改 endpoint/token 只影响对应连接；重复 `self_id` fail-closed；旧连接 drain。
- 工具：账号列表/状态、conversation_read/send/ack、媒体读取；工具实现为 `AgentTool`，依赖 `bma3-agentlib`。
- 入站去重、重连 backoff、心跳、发送三态；真实 QQ 验收 `NOT_RUN`。

## 6. inbox

- 单 Inbox 状态机：append/update、status/list、due、delivery、ack、occupancy；状态 `available → delivered → acked`，失败上限 `undelivered`。
- 不拥有消息正文、不知道平台；通过 `source` 指向消息板。
- HTTP API：`/healthz`、`/v1/inboxes/{inbox}/items`、`/status`、`/deliveries/{id}/ack`。
- 工具：`inbox_status`、`inbox_list`、`inbox_ack`；由 app 注册。
- 先不做 claim/release/complete；需要多消费者时再加。

## 7. 配置与日志

- 文件：`config/app.yaml`、`qqconnect.yaml`、`inbox.yaml`、`logging.yaml`；JSON Schema 校验；`${ENV:...}`/文件引用密钥。
- 热重载：watch → validate → diff → apply → health；失败保留 last-known-good；`config validate/diff/reload`。
- 日志：JSON lines + console；模块 category、`traceId/accountId/threadId/toolCallId`；动态调级；脱敏；滚动；audit 单独。
- 默认不打正文/token/cookie；debug 可按模块开启。

## 8. DSH 插件与安装

- 一个 npm 包 `@mosire/bma3-dsh`，三个独立 module/row：
  - `inbox`：消费 wake 队列 → `agent.followup`；
  - `persona`：角色系统提示词/`PERSONA.md` 注入/压缩重锚；
  - `preset`：工具组预设。
- 复用 `iiBotMosire-DSH` 的 module/preset 模式和 `InboxMosire-DSH` 的 host 事件/wake 逻辑；新包只做薄桥。
- 安装：DSH 插件 first-run 检测 `botmosire-a3`；缺失则从 GitHub Release 下载 app-image tar.gz + SHA256，解压到 `~/.local/share/botmosire-a3/<version>`，写 systemd user unit，`enable --now`；失败给明确命令。
- `jpackage` app-image 不含 Chromium；Chromium/Selenium 属后续 webconnect。

## 9. 不变量与验收

1. 服务默认只绑回环；MCP 必须 Bearer；密钥不进日志/仓库。
2. 消息板是业务事实源；Inbox 只做唤醒/预览；正文由工具读取。
3. 发送必须经消息板；`UNKNOWN` 不自动重发；账号不串号。
4. 配置非法时 fail-closed，保留旧配置；热重载不丢在途消息。
5. 没有真实 QQ/DSH 验收时写 `NOT_RUN`；fake WS/回环测试不能冒充真实联调。
6. Plan 1 的验收：fake OneBot WS 入站 → 消息板 → Inbox → MCP read/ack/send；app-image 能启动、`/healthz` 200；DSH 插件能加载并注入 fake wake。

## 10. 后续计划

- Plan 2：webconnect（Selenium + system Chromium，浏览器 computer-use）。
- Plan 3：B 站视频/私信。
- Plan 4：Linux 桌面控制、Web 管理面、更多平台适配器。
