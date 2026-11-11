# bma3-inbox —— 单 Inbox 总面板

## 职责

- 聚合多个 source 的条目；一个 inbox 一条面板/SSE；不拥有完整消息正文。
- 有界窗口：item entries ≤ `maxMessagesPerItem`，panel items ≤ `maxItemsPerPanel`。
- 面板 revision / acked revision；SSE snapshot/panel/heartbeat；ack 只推进游标。
- 逐条 ack：`ItemStatus.ACKED` 是可见性唯一开关；`ackedRevision` 只是观测值；`inbox_ack` 支持 itemIds/1-based indices/range + revision 校验。
- 保留期：只清 ACKED（`inbox.retention.*`）。
- 推送节奏：`awaitPush()` 按 priority 分两档（≥ immediateThreshold 走 importantDelay，其余走 normalDelay）；批次以最早 pending 项为锚、不重置；任一条立即项到点会带走全部 pending。
- `GET /panel` 与工具永远返回实时全量未 ack 视图；延迟只作用于推送/唤醒。

## 边界

- 不知道 QQ/平台；不出现 `qq_group`/`bilibili` 分支。来源显示名由 `SourceView.label` 提供。
- 完整消息、引用原文、图片 URL 在消息板；Inbox 只存预览与关系标记。
- 不建立 per-source subscription/lease；不按 source 起后台任务。
- 旧 `Delivery`/`due` 是 legacy 兼容路径，新面板/SSE 不得依赖它阻塞。

## 入口

- 模型：`src/main/java/io/mosire/bma3/inbox/core/{InboxEntry,InboxItem,PanelItem,InboxPanel,InboxService}.java`
- HTTP/SSE：`src/main/java/io/mosire/bma3/inbox/http/InboxHttpServer.java`
- 工具：`src/main/java/io/mosire/bma3/inbox/tools/*.java`
- 设计：`../docs/superpowers/specs/2026-11-10-inbox-panel-sse-design.md`

## 验证

- `./mvnw -q -pl bma3-inbox -am test`
- 验收覆盖：窗口截断、top M、SSE snapshot/panel/heartbeat、并发 append、三档推送时序与时钟重置（`InboxPushScheduleTest`）、工具面与逐条 ack（`InboxToolsTest`）。
