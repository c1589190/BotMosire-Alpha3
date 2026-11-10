# bma3-app —— 组合根与运行时策略

## 职责

- 装配 config/logging/MCP/health/Inbox HTTP/SSE；把 adapter 事件送进 Inbox。
- 通用优先级：`app/priority/PriorityResolver` 读取 platform 默认权重、source 覆盖、备注、信号与 per-source threshold。
- 热重载：`ConfigWatcher` 更新 `RuntimePolicy`，只影响后续 append/panel/发送。

## 边界

- 不把 QQ/平台分支写进 Inbox；平台差异由 adapter 的 `SourceLabeler`/`PrioritySignals` 提供。
- Inbox 是总面板；MCP/HTTP 端口仍不热重载。
- 完整消息在消息板；SSE 只推受限面板。

## 入口

- 组合根：`src/main/java/io/mosire/bma3/app/Bma3App.java`
- 配置：`config/AppConfig.java`、`config/ConfigLoader.java`、`config/ConfigWatcher.java`
- 优先级：`src/main/java/io/mosire/bma3/app/priority/*`
- 设计：`../docs/superpowers/specs/2026-11-10-inbox-panel-sse-design.md`
