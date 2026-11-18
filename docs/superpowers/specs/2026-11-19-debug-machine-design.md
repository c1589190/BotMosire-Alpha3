# 无 QQ 连接的 HTTP 调试机

> **状态** 已确认（2026-11-19，UTC+8）。

## 需求

> 是的，你还要准备一个没有onebot连接时的纯调试状态测试机，包括基于http debug的简单调试推送和调试消息生成等，能做到吗？这次公开发布版本应当是0.0.8-early

调试使用本机 `dsh web`。调试机运行 `0.0.8-early` 的 BMA3 jar，使用专属 Inbox、数据目录和 HTTP 端口，可独立运行，也可连接 Web 会话。命令行入口提供启动、状态快照、单条推送、批量生成及查询和回答。

## 运行契约

- 调试推送通过 `POST /debug/ask` 异步提交。批量生成制作带编号的合成提问，逐条走同一请求路径，逐条返回请求 ID、状态和来源。
- 正文随问诊请求保存。Inbox 预览来自现有 `bma3_debug/<requestId>` 来源，使用正常的优先级、SSE 和确认机制。请求查询与回答复用 `GET /debug/ask/{id}` 和 `POST /debug/answer`。
- DSH Web 使用独立的 Inbox 状态文件。BMA3 与 Web 都可以用该实例的凭据文件接线。

## 不变量

- `qqconnect.accounts` 和 `qqofficial.accounts` 均为空。BMA3 的 MCP、Inbox、健康检查与 DSH Web 监听回环。
- 调试配置、数据库、`.bma3env` 和日志位于仓库外的私有目录。命令从该文件读取 `DEBUG_TOKEN`，只发给本机调试端点。生成结果只输出 ID、来源和状态。
- `/debug` 遵循[既有问诊设计](2026-11-15-debug-channel-schedule-triggers-design.md)，包括独立凭据、待答数量限制和正文保留策略。批量生成的每次请求独立结算，失败时保留已返回的 ID。

## 验收判据

| 判据 | 预期 |
|---|---|
| 启动 | jar 版本 `0.0.8-early`，`/healthz` 和 `/readyz` 返回 200，OneBot 与官方 QQ 账号列表为空 |
| 鉴权 | `/debug/snapshot` 接受该实例的 `DEBUG_TOKEN`，拒绝缺失或其他用途的 token |
| 推送和生成 | 单条提问及批量测试项各有独立请求 ID。Inbox 面板能找到对应条目，SSE 消费者收到推送 |
| 回答 | 使用 HTTP 或 BMA3 MCP 回答后，查询返回 `ANSWERED` 与相同回答 |
| Web | 本机 `dsh web` 装入 BMA3 插件，使用独立状态接收测试推送，验证会话查询及上下文传递 |

## 未决问题

候选包和本机 Web 验证见[项目状态](../../agents/STATUS.md)。Web 验收使用本地模型替身，Watchdog 对副本事件序号间隔的 `CONTEXT_GAP` 判定待核对。
