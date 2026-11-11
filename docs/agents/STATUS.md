# STATUS —— 更新时间：2026-11-11 23:12 (UTC+8)

## 当前目标 / 进行中范围

0. **0.0.3-hotfix5**：修 `bma3_` 前缀把 scope-local 工具（`bma3_self_status/reload`）拉进 `tools.restrict()` 的 allow，导致 DSH 抛 "names unknown global tool"、整条工具策略失效（bash 等被状态未定义）。修法：allow 里剔除 scope-local 名字；测试桩改成严格模拟 DSH 的 restrict 校验。
0a. **0.0.3-hotfix4**：修「multiple complete prompt sections」回归——删除 legacy `persona` 模块与其 loader 行，`self` 加同名空段遮蔽旧残留段（热重载即恢复，无需重启）。
0a. **0.0.3-hotfix3**：修「窗口外条目被吞 + ack 范围过大」；插件 `bma3_core` 组纳入 `bma3_*`（`bma3_inbox` 终于可见）；插件 ack 改 `itemIds`。
0a. **0.0.3-hotfix2**：发布 jar + DSH 插件 tgz；插件侧新增 `bma3_inbox`（status/bind/unbind/pause/resume/start/stop/ack）。
0b. **0.0.3-hotfix1**：人设语义修正——`systemprompt.md` 是完整 system prompt（`complete` 默认 `true`），插件不拼任何内置内容；随 `v0.0.3-hotfix1` 发布。
1. **0.0.3 批次 1（已完成）**：优先级规则 + 三档推送时序 + `set_config` 写入口 + 消息模型（发送人/结构化段/平台 id）。
2. **0.0.3 批次 2（已完成）**：`qq_media_view` + `fileId` + `downloads` 落盘 + 上限/超时配置（D-019）。
3. **0.0.3 批次 3（已完成）**：`inbox_items`/`inbox_get`/`inbox_wait`、逐条 ack（序号/范围/itemId + revision 校验）、注入筛选 + 时钟重置、保留期（D-020/D-029..D-032）。
4. **0.0.3 批次 4（已完成）**：OneBot 连接诊断 + 主动探测、MCP 401 可读响应（D-033/D-034）。
5. **0.0.3 批次 5（未做）**：真实 LLBot/DSH 联调。见 [`../superpowers/plans/2026-11-11-0.0.3-plan.md`](../superpowers/plans/2026-11-11-0.0.3-plan.md)。
3. **不发布**：目标版本 0.0.3；本轮**未发版、未打 tag**（D-022）。

## 源码基线

- 仓库：`/home/cna/BotMosire-Alpha3`。
- 分支：`feat/bma3-plan-1`；发布：tag `v0.0.3-hotfix5`；HEAD 见 `git log -1`；工作树状态见文末命令。
- 远端：`origin` = `https://github.com/c1589190/BotMosire-Alpha3`。
- 版本号：`0.0.3-hotfix5`（pom / `Main.VERSION` / MCP serverVersion / dsh-plugin package.json / packaging 一致）。
- 继承参考：`/home/cna/DevMosire-iiBot`（只读）。

## 最近门禁

| 检查 | 命令 | 结果 |
|---|---|---|
| Java 全量测试 | `./mvnw -o -q test` | **PASS**：core 7 / agentlib 5 / qqconnect 18 / inbox 21 / webconnect 4（1 skipped）/ app 23 = **78 tests, 0 fail**（2026-11-11） |
| DSH 插件测试 | `cd dsh-plugin && npm test` | **PASS**：17 tests（含 complete 段唯一性回归） |
| 本地真进程端到端（假 OneBot） | `tools/e2e-local/drive*.py` | **PASS 27/27**：优先级三档时序、合并批次、逐条 ack、set_config 热重载、丢弃线、媒体下载、连接诊断 hint、read/ack 游标、引用回写平台 id |
| 统一真环境回归（真 jar + 真插件客户端） | `node tools/e2e-local/e2e_inbox_roundtrip.mjs <inboxPort> <injectPort>` | **PASS 9/9**：8 个低优先来源 → 推 6 条 → 插件按 itemIds ack 6 条 → 剩 2 条下一轮补推 → 两次 ack 合计 8 条、无刷屏 |
| 真实 QQ / LLBot 联调 | — | `NOT_RUN`（本地只用了假 OneBot） |
| 真实 DSH 宿主 / SSE 长连接 | — | `NOT_RUN` |
| `packaging/smoke.sh`（jlink/jpackage + 安装 dry-run + 启动 /healthz） | `bash packaging/smoke.sh` | **PASS**（2026-11-11，app-image 启动且 /healthz=200） |
| systemd 实际安装/开机常驻 | — | `NOT_RUN` |

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
- **媒体查看**：`qq_media_view` 只接受 `fileId`（`source+messageId+index` 编码），先 `get_msg`/`get_image` 刷新 rkey 链接再按上限下载；文本 ≤64KB 内联，其余落 `<dataDir>/downloads` 返回路径；坏 id/超限不落盘。
- **插件侧 inbox 管理**：`bma3_inbox`（status/bind/unbind/pause/resume/start/stop/ack）一个工具搞定本会话挂载状态与修改；`status` 带 `thisSessionIsTarget` 与 token 来源（不回显 token）。
- **Inbox 工具面**：`inbox_items`（含 M 之外的条目）、`inbox_get`、`inbox_wait`（只读、默认 20s/硬顶 60s）；`inbox_ack` 逐条 ack（`itemIds`/`indices`/`range` + `revision` 校验，冲突返回 409/`REVISION_CONFLICT`）。
- **注入筛选与时钟**：`inbox.push.minPriority` 让条目"可见但不唤醒"；每轮 flush 后按 `max(updatedAt, lastFlushAt)` 重排重算。
- **保留期**：`inbox.retention.maxAgeDays=30` / `maxItems=5000`，只清 ACKED。
- **诊断**：`OneBotDiagnostics` 把 426/401/404/拒绝/DNS 映射成 hint，并在握手失败后主动探测同 host:port 的 HTTP；`qq_account_list` 输出真实 `connected` + `lastError`；MCP 401 响应体自带 `errorCode/reason/hint`。
- **精简（D-036）**：删掉 legacy delivery 路径（`Delivery`/`due()`/`ack(deliveryId)`/`/deliveries/*`/`inbox_delivery` 表/重试参数）与三个重复工具（`qq_recent_messages`、`qq_account_login_info`、`qq_user_note_get`）；工具面 = QQ 16 + Inbox 6 + 配置 2。
- **配置写入口**：`set_config`（点分路径 patch/unset，隔离校验 → 原子替换 → 等热重载，返回 `applied/reloaded/effective`，**无白名单**）；`get_config` 只读脱敏。
- **bot 身份**：`qqconnect.accounts[].selfQq/selfName` 可配；缺省由 `get_login_info` 学习（失败只告警，相关属性保持关闭）。

## 未验证 / 风险 / blocker

- **真实 LLBot/DSH 仍 `NOT_RUN`**：本地已用"真进程 + 真 MCP/HTTP + 假 OneBot"跑通 27 项（见 `tools/e2e-local/`），但真实 LLBot 的 `get_msg`/`get_image` 行为、以及 DSH 宿主侧的注入链路仍未验证。
- **hotfix3 引入、hotfix4 修掉的回归**：`self` 默认改成 `complete: true` 后，与 legacy `persona` 模块注册的
  `bma3-persona` complete 段同时生效 → DSH 报 `multiple complete prompt sections are active`，整轮失败；
  而 legacy 模块**从不释放**自己的 section（`persona.js` 丢弃了 `systemPrompt.section()` 的 disposer），
  所以每次重载后老会话都会复发。修法：删除 `persona` 模块与其 loader 行；`self` 额外注册同名空段
  `bma3-persona` 遮蔽旧段（scoped 遮蔽 global），热重载即可恢复、不必重启。
- **0.0.3-hotfix2 实测发现、hotfix3 修掉的两个真 bug**：
  ⑤ **窗口外条目被"标记已推送"吞掉**：`flushPending()` 把整批 pending 都标成 delivered，而面板只渲染 top-M → 窗口外的条目再也不会推；
  ⑥ **ack 范围过大**：`POST /ack {revision}` 会 ack 所有 `eventRevision ≤ revision` 的条目，包括没进窗口、从未显示的 → 低优先级消息被静默吃掉。
  修法：flush 只标记窗口内条目；`nextDue()` 增加"窗口容量"语义（窗口满时窗口外的不算 due，等 ack 腾位）；`ackPanel(revision)` 只 ack 当前处于 delivered 状态的条目；插件 ack 改为按 `itemIds` 精确确认。
- **e2e 发现并已修的四个真 bug**：
  ① 同一来源连发会把批次时钟往后推（合并刷新 `updatedAt`）→ 锚定"首次欠推送时刻" `pendingSince`；
  ② 连不上的账号从 `qq_account_list` 里消失（只列 active）→ 改为列"配置中的账号"；
  ③ **推送被旧连接抢走**：flush 由"谁先等到"执行，慢/断开的 SSE 读者可能把这一轮推掉，在线客户端收不到 → 改为按 `pushRevision` 投递游标，任何等待者都能补收；
  ④ **热重载卡住入站**：`reconcile()` 持注册表锁做"连接 5s + 诊断探测 2s"，入站要同一把锁读 bot 身份 → 改成并发 map，慢路径不持锁。
- **`pushedItems` 是内存表**：重启后未 ack item 会重新进入 pending（按 `updatedAt + delay`），通常立即 flush 一次；宁重推不漏推，需真实环境观察。
- **`self_name` 子串匹配**误命中率高（"小博"/"bot"），默认 +60 压在立即线以下。
- **旧库迁移**：`chat_message` 新列是 additive；`qq-extras.db` 打开时 `DROP COLUMN priority` / `DROP TABLE qq_source_weight`；远端库需先备份。
- **媒体未接 MCP ImageContent**：`qq_media_view` 目前返回本地路径（Agent 用 `read_image`）；`assetDocIds` + `ToolAssetResolver` 通道等真实宿主验证后再决定。
- **媒体下载只有 fake CDN 证据**：真实 QQ CDN 的 302/rkey 行为 `NOT_RUN`。
- **多消费者**：ack 仍是单面板全局游标。

## 下一步

1. 批次 5：真实 LLBot/DSH 联调（本地假 OneBot 已 PASS，真机仍 `NOT_RUN`）（`get_login_info`、平台 id 回写、推送节奏、逐条 ack、`set_config` 热重载、诊断 hint）。
2. 真实宿主验证 MCP 图片通道，再决定媒体是否补 `assetDocIds`（D-035 暂缓）。
3. 联调通过后再决定 0.0.3 发版与版本号 bump。
4. 全部就绪后做一次真实 LLBot/DSH 联调，再决定 0.0.3 发版与版本号 bump。

```bash
git status --short --branch
```
