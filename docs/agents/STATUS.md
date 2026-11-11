# STATUS —— 更新时间：2026-11-11 19:10 (UTC+8)

## 当前目标 / 进行中范围

1. **0.0.3 批次 1（已完成本轮）**：优先级规则 + 三档推送时序 + `set_config` 写入口 + 消息模型（发送人/结构化段/平台 id）。
2. **0.0.3 批次 2..5（未做）**：媒体查看/下载（D-019）、Inbox 工具面与批量 ack（D-020）、错误可诊断性、真实联调。见 [`../superpowers/plans/2026-11-11-0.0.3-plan.md`](../superpowers/plans/2026-11-11-0.0.3-plan.md)。
3. **不发布**：目标版本 0.0.3；本轮**未发版、未打 tag**（D-022）。

## 源码基线

- 仓库：`/home/cna/BotMosire-Alpha3`。
- 分支：`feat/bma3-plan-1`；HEAD 见 `git log -1`；工作树状态见文末命令。
- 远端：`origin` = `https://github.com/c1589190/BotMosire-Alpha3`。
- 版本号仍为 `0.0.2-hotfix1`（未 bump，等发版前统一处理）；`--version` 与 MCP serverVersion 与 pom 一致。
- 继承参考：`/home/cna/DevMosire-iiBot`（只读）。

## 最近门禁

| 检查 | 命令 | 结果 |
|---|---|---|
| Java 全量测试 | `./mvnw -o -q test` | **PASS**：core 7 / agentlib 4 / qqconnect 9 / inbox 18 / webconnect 4（1 skipped）/ app 23 = **65 tests, 0 fail**（2026-11-11） |
| DSH 插件测试 | `cd dsh-plugin && npm test` | **PASS**：14 tests |
| 真实 QQ / LLBot 联调 | — | `NOT_RUN` |
| 真实 DSH 宿主 / SSE 长连接 | — | `NOT_RUN` |
| `packaging/smoke.sh` / systemd | — | `NOT_RUN` |

## 当前有效设计 / 维护入口

- 0.0.3 设计：[`../superpowers/specs/2026-11-11-priority-rules-and-push-schedule-design.md`](../superpowers/specs/2026-11-11-priority-rules-and-push-schedule-design.md)
- 设计裁定：D-017..D-028（[`../superpowers/DESIGN_RULINGS.md`](../superpowers/DESIGN_RULINGS.md)）；其中 D-021 明确不考虑兼容、D-022 明确本轮不发版。
- 计划：[`../superpowers/plans/2026-11-11-0.0.3-plan.md`](../superpowers/plans/2026-11-11-0.0.3-plan.md)
- 旧设计（仍有效的部分）：[`../superpowers/specs/2026-11-10-inbox-panel-sse-design.md`](../superpowers/specs/2026-11-10-inbox-panel-sse-design.md)（N/M 截断、SSE 生命周期、Inbox 不带 URL）
- 维护裁定：M-002（[`MAINTENANCE_RULINGS.md`](MAINTENANCE_RULINGS.md)）

## 已实现的关键行为（0.0.3 批次 1）

- **优先级一处真源**：分数只在 `config/app.yaml` 的 `weighting.*`；`qq_source_weight` 表、`qq_user_note.priority`、`qq_source_weight_*` 三个工具**已删除**。
- **两级键 + 最具体胜**：`qq_group` / `qq_group/955432692` / `qq_private/132843233`；`qq_sender/<qq>`、`qq_name/<子串>` 是**加法**加分；`maxPriority` 封顶。
- **三档出口**：`< dropBelow(-300)` 丢弃**且不落库**；`≥ immediateThreshold(300)` 1s 推；其余 30s 批。
- **推送时序**：`InboxService.awaitPush`；批次以最早 pending 项为锚、**不重置**；一条立即项到点带走全部 pending；已推送项不重复推；`GET /panel` 与工具仍是实时全量。
- **消息模型**：`chat_message` 增 `sender_id`/`sender_name`/`segments_json`/`external_message_id`；`Segment` 带 `data` 白名单；出站发送成功后回写平台 id；`qq_reply_to_bot` 靠该索引判定。
- **读取工具**：`qq_conversation_history`/`qq_conversation_read`/`qq_message_get` 均带 `sender{id,name}`/`externalMessageId`/`self`，`includeSegments=true` 返回结构化段；`read` 不再要求账号在线；旧的误导字段 `from` 已删除。
- **配置写入口**：`set_config`（点分路径 patch/unset，隔离校验 → 原子替换 → 等热重载，返回 `applied/reloaded/effective`，**无白名单**）；`get_config` 只读脱敏。
- **bot 身份**：`qqconnect.accounts[].selfQq/selfName` 可配；缺省由 `get_login_info` 学习（失败只告警，相关属性保持关闭）。

## 未验证 / 风险 / blocker

- **真实联调 `NOT_RUN`**：`get_login_info` 学习、平台 id 回写、SSE 推送节奏、`set_config` 热重载都只有 fake/回环证据。
- **`pushedItems` 是内存表**：重启后未 ack item 会重新进入 pending（按 `updatedAt + delay`），通常立即 flush 一次；宁重推不漏推，需真实环境观察。
- **`self_name` 子串匹配**误命中率高（"小博"/"bot"），默认 +60 压在立即线以下。
- **旧库迁移**：`chat_message` 新列是 additive；`qq-extras.db` 打开时 `DROP COLUMN priority` / `DROP TABLE qq_source_weight`；远端库需先备份。
- **媒体只有引用、没有查看**：`segments_json` 里有 `file`/`url`，但还没有 `fileId` + 查看/下载工具（批次 2）。
- **Inbox 工具面仍缺**：`inbox_items`/`inbox_get`/批量 ack/`inbox_wait`（批次 3）。
- **诊断仍弱**：OneBot 握手状态码、MCP 401 响应体、发送上限读取工具（批次 4）。
- **多消费者**：ack 仍是单面板全局游标。

## 下一步

1. 按 [`0.0.3 计划`](../superpowers/plans/2026-11-11-0.0.3-plan.md) 批次 2（媒体查看/下载）开工：先补 `fileId` + 查看工具 + `<dataDir>/downloads`，再加大小/域名上限与"不落日志"约束。
2. 批次 3：`inbox_items`/`inbox_get`/批量 ack（序号 + 范围 + revision 校验）/`inbox_wait`。
3. 批次 4：OneBot 握手错误映射、MCP 401 可读响应、`qq_policy`。
4. 全部就绪后做一次真实 LLBot/DSH 联调，再决定 0.0.3 发版与版本号 bump。

```bash
git status --short --branch
```
