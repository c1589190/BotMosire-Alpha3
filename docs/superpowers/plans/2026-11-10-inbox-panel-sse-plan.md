# Inbox 总面板 / SSE / 通用来源与优先级实施计划

> **状态**：执行中（2026-11-10）
> **设计**：[spec](../specs/2026-11-10-inbox-panel-sse-design.md)
> **裁定**：D-009..D-016；维护规则 M-002

## 目标

把 Inbox 从“per-source 合并 + delivery 轮询”改为“一个 inbox 总面板 + 一条 SSE + 有界窗口”，并把来源/优先级/发送硬限做成 adapter-neutral 的通用层。

## 任务

| # | 任务 | 责任区 | 状态 |
|---|---|---|---|
| 1 | 通用 source：`SourceAddress`/`SourceView`/`SourceLabeler`/`SourceLabels`，QQ labeler | `bma3-core`、`bma3-qqconnect` | 已实现 |
| 2 | 有界 Inbox item：entries、total、event revision、SQLite 迁移 | `bma3-inbox` | 已实现 |
| 3 | 面板视图：top M、每个 item 最近 N 条、source label、关系行 | `bma3-inbox` | 已实现 |
| 4 | 配置：`inbox.display.*`、`qqconnect.send.*`、`weighting.platformWeights/signals`、热重载 | `bma3-app` | 已实现 |
| 5 | SSE：`/events` snapshot/panel/heartbeat，`/panel`，`/ack` 游标 | `bma3-inbox` | 已实现 |
| 6 | 通用优先级：platform 默认 + source 覆盖 + 备注 + 信号 + per-source threshold | `bma3-app/priority` | 已实现（reply/name 未做，标 `NOT_RUN`） |
| 7 | QQ 渲染：图片占位、@/引用/合并转发 marker，图片 URL 不入正文 | `bma3-qqconnect`、`bma3-app` | 已实现 |
| 8 | 发送硬限：统一 `QqSendSupport`，3000 默认，code point，reject/split | `bma3-qqconnect` | 已实现 |
| 9 | DSH 插件：单 SSE 连接、面板渲染、自动 ack、重连/状态 | `dsh-plugin` | 已实现 |
| 10 | 测试/构建/STATUS | 全仓 | 已实现（Java 50 tests / 插件 7 tests / package PASS；真实联调 NOT_RUN） |

## 验证

- `./mvnw -q test`
- `cd dsh-plugin && npm test`
- `./mvnw -o -q -pl bma3-app -am package -DskipTests`
- 真实 QQ/DSH/systemd：`NOT_RUN`

## 不做

- persona（用户暂缓）。
- B 站/微信 adapter 本体。
- reply-to-bot/name 信号（接口保留，QQ 首版未实现）。
- 多消费者 per-consumer ack 游标（首版单面板/单游标）。
