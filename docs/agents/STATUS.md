# STATUS —— 更新时间：2026-11-10 21:35 (UTC+8)

## 当前目标 / 进行中范围

1. **Inbox 总面板 / SSE / 通用来源 / 通用优先级 / 发送硬限**：Java 侧已实现，DSH 插件已切到单 SSE 注入；Mock/回环测试 PASS。
2. **未完成验收**：真实 QQ/LLBot、真实 DSH 宿主加载、systemd 常驻、SSE 长连接断开重连实测均 `NOT_RUN`。
3. **人设**：2026-11-10 已实现 DSH 插件侧 `self` loader：工作区 `systemprompt.md` 动态 section、`agentconfig.json` 默认工具组、`bma3_self_status/reload`；preset 组合固定 terminal/read_image，spawn 子 Agent 走 `minimal`。Mock/fake 测试 PASS；真实 DSH preset 激活/远端 `NOT_RUN`。

## 源码基线

- 仓库：`/home/cna/BotMosire-Alpha3`。
- 分支：`feat/bma3-plan-1`；发布：`v0.0.2` tag；本轮发布提交已推送，工作树提交后 clean。
- 远端：`origin` = `https://github.com/c1589190/BotMosire-Alpha3`；已 push `feat/bma3-plan-1` + `v0.0.2`。
- 发布资产：`bma3-app-0.0.2.jar` + `.sha256`（GitHub Release `v0.0.2`）。
- 继承参考：`/home/cna/DevMosire-iiBot`（只读）。

## 最近门禁

| 检查 | 命令 | 结果 |
|---|---|---|
| Java 全量测试 | `./mvnw -q test` | **PASS**：19 suites / 50 tests / 0 failures / 0 errors / 1 skipped（真实浏览器 smoke 跳过，2026-11-10 21:31） |
| DSH 插件测试 | `cd dsh-plugin && npm test` | **PASS**：13 tests / 0 fail（2026-11-10 21:31） |
| 离线打包 | `./mvnw -o -q -pl bma3-app -am package -DskipTests` | **PASS**：`bma3-app/target/bma3-app-0.0.2.jar` 53MB，含 AgentLib 0.2.5 |
| packaging/smoke.sh | — | `NOT_RUN`（本轮未重跑） |
| 真实 QQ / LLBot 联调 | — | `NOT_RUN` |
| 真实 DSH 宿主加载 / SSE 长连接 / BMA3 preset 激活 | — | `NOT_RUN` |
| systemd 实际安装/开机常驻 | — | `NOT_RUN` |

## 当前有效设计 / 维护入口

- 专题设计：[`../superpowers/specs/2026-11-10-inbox-panel-sse-design.md`](../superpowers/specs/2026-11-10-inbox-panel-sse-design.md)。
- 设计裁定：D-009..D-016（[`../superpowers/DESIGN_RULINGS.md`](../superpowers/DESIGN_RULINGS.md)）。
- 维护裁定：M-002（[`MAINTENANCE_RULINGS.md`](MAINTENANCE_RULINGS.md)）。
- 实施计划：[`../superpowers/plans/2026-11-10-inbox-panel-sse-plan.md`](../superpowers/plans/2026-11-10-inbox-panel-sse-plan.md)。
- 事故复核：[`../superpowers/reports/2026-11-10-remote-bma3-incident-investigation.md`](../superpowers/reports/2026-11-10-remote-bma3-incident-investigation.md)。
- B 站复用调查：[`../superpowers/reports/2026-11-10-dsh-bili-whale-investigation.md`](../superpowers/reports/2026-11-10-dsh-bili-whale-investigation.md)（结论：只复用其 `tools/` CLI，不装其 DSH 插件；B 站范围/Node 依赖/写策略待用户裁定）。
- 人设自加载调查：[`../superpowers/reports/2026-11-10-persona-self-reload-investigation.md`](../superpowers/reports/2026-11-10-persona-self-reload-investigation.md)（结论：静态 preset + `agent/created` 动态 `systemPrompt.section`；默认工具组由 preset 组合提供，子 Agent 用 `minimal`；权限面归 DSH；preset 首轮后不可切换）。

## 已实现的关键行为

- **Inbox 总面板**：一个 inbox 一条 SSE；source 只是面板条目，没有 per-source 订阅/lease；`InboxService` 的 append/due/ack 已串行化。
- **有界窗口**：每个 item 只保留最近 N 条 entry（N=4..8，默认 6），panel 只取 priority 最高的前 M 个 item（M=4..8，默认 6）；`includeOmittedCount` 控制省略计数；旧 `summary` 不再无限增长。
- **SSE**：`GET /v1/inboxes/{inbox}/events`，snapshot/panel/heartbeat，`Last-Event-ID`；`POST /ack` 推进面板读游标，不阻塞新事件。
- **通用来源**：`SourceAddress` + `SourceView` + `SourceLabeler`/`SourceLabels`；QQ labeler 输出 `QQ群 955...`/`QQ私聊 366...`；未知平台回退 `platform · 最后一段`；Inbox 不 switch 平台。
- **通用优先级**：`PriorityResolver` = platform 默认权重 + per-source weight/threshold + 用户备注 + mention/command 信号（reply/name 接口保留但未实现，标 `NOT_RUN`）；群内 @ 可突破群默认静音。
- **关系摘要**：`InboxEntry` 带 `[@qq]`/`[引用 #id]`/`[图片]`/`[合并转发]` 等 marker；图片 URL/rkey 不再进新消息正文；wake 不写“去看 history”。
- **发送硬限**：`QqSendSupport` 统一出口；`maxTextChars` 默认 3000、可配置；按 code point 计数；reject/split；`qq_conversation_send` 也走统一出口。
- **配置热重载**：`inbox.display.*`、`qqconnect.send.*`、`weighting.platformWeights/signals` 由 `ConfigWatcher` 更新 `RuntimePolicy`；非法配置保留 last-known-good。
- **DSH 插件**：`lib/inbox.js` 改成单 SSE 客户端；启动自动连接、断线 backoff、目标会话可选、注入成功后自动 ack、状态含 connection/lastRevision/lastError。
- **人设/工具面**：`lib/self.js` 在 `agent/created` 注册 `deployment:persona-prefix` 动态 section（每步按 mtime/size 读 `systemprompt.md`）；`agentconfig.json` 解析 `mcp_admin`/`bma3_core`/`terminal`/`image`/`subagent` 默认组并 apply tool restriction；`bma3_self_status/reload` 为 scoped tools；`bash`/`pwsh`/`read_image` 由 preset composition 固定，agentconfig 无 lock 字段。

## 未验证 / 风险 / blocker

- **真实联调**：SSE + DSH 宿主 + 真实 QQ 面板注入、自动 ack、断线重连均 `NOT_RUN`；不能把 fake/回环测试写成真实验收。
- **旧数据迁移**：`inbox_item` 新列/`inbox_meta` 已做 additive migration；旧 `summary` 行不会自动进入新面板（`event_revision=0`），需要手动清理/ack 或等新消息覆盖；远端 7 个巨型 item 的处置未执行。
- **信号缺口**：reply-to-bot 需要出站外显 message_id 索引；name 需要 bot 昵称；QQ 首版只做 mention/command。
- **WS 重连/readiness 未改**：`OneBot11Client`/`AccountRegistry` 还没有自动重连与真实连接状态；P1-6 仍开放。
- **旧图片 URL 未清洗**：新消息不再把 image URL 写进正文，但旧 `bot.db` 的 105 条 URL 仍在；需要单独迁移/清洗。
- **多消费者**：ack 游标是单面板全局的；多 DSH 会话同时连同一 inbox 需要 per-consumer 游标，未做。
- **平台 adapter**：B 站/微信只有 source/label/priority 通用接口，实际 adapter 未实现；B 站复用调查已完成，但未写 spec/代码。
- **人设**：插件侧实现完成，`npm test` 13/13；真实 DSH 会话中 BMA3 preset 注册、Agent 创建读取 `systemprompt.md`、tool restriction 实际生效、minimal 子 Agent 工具面均 `NOT_RUN`；本机 web profile 的 `bma3-preset` 仍 disabled。
- **远端部署**：本机 jar 可构建，但远端替换路径/权限未提供；未 push/commit。bma3 MCP/Inbox 都只绑回环，跨机 DSH 不能直连；同机部署或 SSH 隧道。`bma3-dsh` 插件自带 `bma3_inbox_bind/ack/status`（HTTP/SSE），但 `inbox_list`/`qq_*` 等完整工具仍需 MCP client（`dsh-mcp-connector` 是管理/配置层，实际 transport 由 `@deepseek-ai/dsh-mcp-client` 提供）。

## 下一步

1. 用临时 `config/app.yaml` 启动真实 bma3 + 真实 LLBot/DSH，验证 SSE snapshot/panel、注入文本、自动 ack、断线重连；结果写证据。
2. 备份远端 `inbox.db`/`bot.db`，再部署新 jar；旧 7 个巨型 item 先手动清理/ack，不直接恢复唤醒。
3. 真实联调通过后，再做 persona spec（用户暂缓）。
4. 若用户确认 B 站方向：先写 Bili CLI sidecar spec（只读/事件/写分层、隔离环境、owner-only 边界），再实现；未确认前不写 B 站产品代码。
5. 人设实测：在临时 DSH profile 启用 `bma3-preset`，选择 BMA3，创建会话验证 `systemprompt.md` 首轮读取、改文件下一步生效、`agentconfig.json` 默认组、`bash/read_image` 不可被 config 删除、`subagent` 走 `minimal`；远端同样验证。

```bash
git status --short --branch
```
