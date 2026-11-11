# bma3-app —— 组合根与运行时策略

## 职责

- 装配 config/logging/MCP/health/Inbox HTTP/SSE；把 adapter 事件送进 Inbox。
- 通用优先级：`app/priority/PriorityResolver` = 最具体的 `weighting.sourcePriority` 规则 + `weighting.attributes` 加法加分；产出 `PriorityDecision(priority, dropped, immediate, reasons)`。
- 热重载：`ConfigWatcher`（mtime+size 签名）更新 `RuntimePolicy`，只影响后续 append/panel/发送；`set_config` 是唯一写入口。

## 边界

- 不把 QQ/平台分支写进 Inbox；平台差异由 adapter 的 `SourceLabeler` / `SourceKeyer` / 属性键提供。
- 分数只有一处真源：`config/app.yaml`；唯一写入口是 `set_config`（`config/ConfigEditor`：先隔离校验、再原子替换），`get_config` 只读且脱敏。
- Inbox 是总面板；MCP/HTTP 端口仍不热重载。
- 完整消息在消息板；SSE 只推受限面板。

## 入口

- 组合根：`src/main/java/io/mosire/bma3/app/Bma3App.java`
- 配置：`config/AppConfig.java`、`config/ConfigLoader.java`、`config/ConfigWatcher.java`
- 优先级：`src/main/java/io/mosire/bma3/app/priority/*`
- 配置读写：`config/ConfigEditor.java`、`tools/{SetConfigTool,GetConfigTool,ConfigView}.java`
- 设计：`../docs/superpowers/specs/2026-11-10-inbox-panel-sse-design.md`
