# STATUS —— 更新时间：2026-11-12 19:10 (UTC+8)

## 当前目标 / 进行中范围

**0.0.4-hotfix1（当前发布）**：修复「重装插件后 bash/read_image 消失」。
根因：`host-tools.js` 把解析失败的 `undefined` 也缓存；`bma3_self_status.lockedTools` 只看 global 视图；
`bma3_self_reload` 不会重挂 preset 子行。修复：不缓存 miss + `invalidateHostToolCache()` + 新增
`bma3_self_repair` + `lockedTools/missingLockedTools` 按 agent scope 计算 + 自动 repair。
报告：[`../superpowers/reports/2026-11-12-dsh-plugin-locked-tools-repair.md`](../superpowers/reports/2026-11-12-dsh-plugin-locked-tools-repair.md)。
版本统一为 `0.0.4-hotfix1`；发布流程：本地门禁 → commit → tag `v0.0.4-hotfix1` → push → Pre-release。
（历史：`0.0.4-early` 已于 2026-11-12 发布。）

**0.0.4-early（上一版，已发布）**：watchdog + 投递模型 + 身份目录 + 富消息 + 协议内文件/历史。
设计：[`../superpowers/specs/2026-11-12-0.0.4-watchdog-delivery-design.md`](../superpowers/specs/2026-11-12-0.0.4-watchdog-delivery-design.md)（D-042..D-050）；
计划：[`../superpowers/plans/2026-11-12-0.0.4-plan.md`](../superpowers/plans/2026-11-12-0.0.4-plan.md)；
回归报告：[`../superpowers/reports/2026-11-12-0.0.4-e2e-report.md`](../superpowers/reports/2026-11-12-0.0.4-e2e-report.md)。

已完成（代码 + 单测 + 本地真进程 e2e）：

1. **Inbox 1B 投递模型**：同源可两条、推送后冻结、`continued` → 面板「（消息更新活跃）」；删除 `consolidateSources()` 与续接合并；ack 幂等；`pushRevision` 落库；`frozen_revision` 作为已推送标记。
2. **`inbox_dismiss`**：`scope=visible|filter|ids`、`priorityBelow/priorityAtLeast/sourcePrefix/olderThanSeconds/limit`、`dryRun`、`allowImportant`；HTTP `POST /dismiss`；watchdog `autoDismiss` 维护任务。
3. **DSH 插件**：状态文件持久化（默认 `~/.dsh/storages/bma3-inbox.json`）；`bind` 落盘；加载时 `agents.get() ?? agents.resume()` 并自动 start；`pushRevision` 去重；`/plugin/heartbeat`；服务端 `control`（restore/remind）；`mcpStartId` 变化时提醒重连 MCP。
4. **watchdog**：新增 `bma3-watchdog-spi`（JDK-only）+ `bma3-watchdog`（依赖除 app 外全部模块）；12 探针、4 恢复动作、1 维护任务、3 个 MCP 工具、`state.json`/`events.jsonl`、固定端口 `/healthz` `/readyz` `/watchdog`、部署者 QQ 私聊报警。
5. **调度**：同源 append 重置等待；`maxFirstPushDelaySeconds` 首次推送硬上限；open item 优先级取 max；`DeliveryPolicy` 热重载；默认 `importantDelaySeconds=3`、`normalDelaySeconds=60`；**`inbox.push.minPriority` 已废弃/忽略**——所有未被 `dropBelow` 丢弃的条目都会推送（低优先走 60s 批次），不再有“只注入不推送”的硬静音。
6. **面板预算**：`maxCharsPerItem`（默认 1200）、`maxMessagesPerItem`/`maxItemsPerPanel` 放宽 1..50、`truncatedChars`；不设总字符上限。
7. **身份/目录/搜索**：`sender_card`/`sender_role` 落库；群名进 source label；`qq_group_members`/`qq_group_member_info`（含头衔）；`qq_person_id/<qq>` 取代 `qq_sender/`，删除 `qq_name/`；`qq_search`。
8. **富消息**：json/xml 卡片（有界 raw、title/desc/url/bizsrc、群邀请 groupId+sequence，ticket 不落库）；`qq_card_view`；`qq_forward_view`（`get_forward_msg` 一层展开、分页、嵌套标记）；`qq_group_request_list/decide` + `qq_group_invite_accept`。
9. **文件/历史**：`qq_conversation_send_file`（`base64://` → 绝对路径 → `providerPath`，三态，UNKNOWN 不重试）；`qq_remote_history`（只读穿透，count 1..1000；真实 LLBot 对 1000 不稳定，自动降级 1000→300→100，retcode 1200 空页，每次尝试记日志）。
10. **观测**：`inbox_status` 带 `pushRevision/ackConflicts/pushes/repushes/dismissed/recentErrors`，计数器跨重启持久化。

## 源码基线

- 仓库：`/home/cna/BotMosire-Alpha3`。
- 分支：`feat/bma3-plan-1`；HEAD 见 `git log -1`。
- 版本号：`0.0.4-hotfix1`（pom / `Main.VERSION` / MCP serverVersion / dsh-plugin package.json / packaging 一致）。
- 发布：**v0.0.4-hotfix1**（2026-11-12，用户「发布0.0.4-hotfix1」授权），上一版 `v0.0.4-early`（commit `fc99132`）保留。
  - Release：<https://github.com/c1589190/BotMosire-Alpha3/releases/tag/v0.0.4-hotfix1>（Pre-release）
  - assets：`bma3-app-0.0.4-hotfix1.jar`、`mosire-bma3-dsh-0.0.4-hotfix1.tgz`、`SHA256SUMS`
- 继承参考：`/home/cna/DevMosire-iiBot`（只读）。

## 最近门禁

| 检查 | 命令 | 结果 |
|---|---|---|
| Java 全量测试 | `./mvnw -o test` | **PASS**：core 7 / agentlib 5 / qqconnect 26 / inbox 29 / webconnect 4（1 skipped）/ watchdog 6 / app 26 = **103 tests，0 fail**（102 pass + 1 skip） |
| DSH 插件测试 | `cd dsh-plugin && npm test` | **PASS**：28 tests |
| 0.0.4 主 e2e | `tools/e2e-local/drive6.py` | **PASS 36/36**（watchdog/目录/卡片/1B/dismiss/文件/历史/搜索/热重载/断线重连） |
| 重启不重推 | `tools/e2e-local/drive6b.py` | **PASS 5/5**（pushRevision/计数器持久化、冻结条目不 pending、不自动重推、新消息仍推进） |
| 真插件客户端 roundtrip | `node tools/e2e-local/e2e_inbox_roundtrip.mjs <inboxPort> 46701` | **PASS 9/9**（窗口 6/8 → 两轮 ack 8 条、itemIds、不重复推） |
| 热重载/防抖/面板/控制面 | `tools/e2e-local/drive6c.py` | **PASS 9/9**（4.04s 延迟、3.02s 防抖重置、truncatedChars、HTTP dismiss、heartbeat/control、minPriority=300 下低优先仍推送） |
| 部署者报警 | `tools/e2e-local/drive6d.py` | **PASS 5/5**（断线→重连→私聊 `[BMA3 watchdog] qqAccount DOWN...`） |
| 真实 LLBot 私聊发送 | `tools/e2e-local/llbot_send_test.py` | **PASS 6/6**：文本 `SENT`、私聊文件 `upload_private_file` `SENT via base64://`、OUT 回写 messageId、真实入站回复 |
| 真实 LLBot 只读探测 | `LlbotProbe ws://47.100.101.156:3001/ <token> 955432692` | **PASS 8/8**：22 群、34 成员（15 title/4 card）、`get_group_member_info.title` 存在、历史 100/300 正常而 1000 超时/retcode1200→工具降级到 300、真实 `get_forward_msg` 形状 `data.messages[].content[]` 可由 `forwardId` 展开、`get_group_system_msg` 正常 |
| jlink/jpackage smoke | `bash packaging/smoke.sh` | **PASS**（app-image 启动且 `/healthz` 200） |
| GitHub Release 资产回下载 | `gh release download v0.0.4-hotfix1` + `sha256sum -c SHA256SUMS` | **PASS**（发布后回填）：jar/tgz sha256 OK、jar `--version` = `0.0.4-hotfix1`；插件 tgz 含 `lib/inbox.js` 等 10 个文件 |
| 真实群文件 / 真实卡片 / 真实群邀请 / 真实 DSH resume / 真实部署者报警 | — | `NOT_RUN`（见报告 §5） |

证据路径：`/tmp/bma3-e2e-004/`（app.log、data/*.db、fake.log）；报告
[`../superpowers/reports/2026-11-12-0.0.4-e2e-report.md`](../superpowers/reports/2026-11-12-0.0.4-e2e-report.md)。

## 当前有效设计 / 维护入口

- 0.0.4 设计：[`../superpowers/specs/2026-11-12-0.0.4-watchdog-delivery-design.md`](../superpowers/specs/2026-11-12-0.0.4-watchdog-delivery-design.md)
- 0.0.4 计划：[`../superpowers/plans/2026-11-12-0.0.4-plan.md`](../superpowers/plans/2026-11-12-0.0.4-plan.md)
- 设计裁定：D-042..D-050（[`../superpowers/DESIGN_RULINGS.md`](../superpowers/DESIGN_RULINGS.md)）；其中 D-043 取代 0.0.3 的「推送即冻结 + 同源续接」、D-044 取代「后到不重置」、D-048 取代 `qq_name/` 与 `qq_sender/`。
- 0.0.3 设计（仍有效的部分）：[`../superpowers/specs/2026-11-11-priority-rules-and-push-schedule-design.md`](../superpowers/specs/2026-11-11-priority-rules-and-push-schedule-design.md) 的分数/配置/媒体/诊断章节；§5.2 第 3 条与 §12 的续接语义已被 0.0.4 取代。
- 维护裁定：M-002（[`MAINTENANCE_RULINGS.md`](MAINTENANCE_RULINGS.md)）。

## 已实现的关键行为（0.0.4）

- **推送是持久水位**：`inbox_meta.push_revision` + `inbox_item.frozen_revision`；Java 重启不重推已冻结条目。
- **同源可多条**：未冻结条目才合并；推送后新消息另开条目并标「（消息更新活跃）」。
- **ack 幂等**：重复 ack 已确认条目返回 200/no-op；同源第二条不会让第一条 409。
- **dismiss**：不读正文按过滤条件清；默认保护 `priority >= immediateThreshold`；只标 ACKED 不删除。
- **watchdog**：QQ 断线自动重连（`AccountRegistry.forceReconnect` + reconcile 死连接替换）；Inbox HTTP/MCP 可重启；插件心跳/控制；关键组件 DOWN → `/readyz` 503；部署者 QQ 私聊报警（冷却、统一发送出口、UNKNOWN 不重发）。
- **身份**：群名片优先显示，昵称以 `[昵称:...]` 保留；头衔来自目录缓存；群名进来源标签。
- **卡片**：面板只显示 `[卡片] 标题`；完整字段/链接/群邀请只在 `qq_card_view` 显式返回；ticket 不落库。
- **转发**：`qq_forward_view` 只读展开一层，分页；嵌套不递归。
- **文件**：只走 OneBot `upload_group_file`/`upload_private_file`；不假设共享 FS、不提供 BMA3 文件服务器。
- **历史**：`qq_remote_history` 只读穿透，不写消息板；`count` 上限 100。

## 未验证 / 风险 / blocker

- **真实 LLBot**：只读探测 PASS + **私聊发送 PASS**（文本 `SENT`；私聊文件 `upload_private_file` `SENT via base64://`；真实入站回复已收到）。**群文件 `upload_group_file` 未真实发送**，`providerPath` 未用；`llonebot_debug` 回退无待处理邀请，`NOT_RUN`。
- **真实 DSH 宿主**：`agents.resume` + 插件热重载/重启恢复 `NOT_RUN`。
- **跨机文件**：若真实 LLBot 不接受 base64/绝对路径，按 D-047 由 Agent 用 bash 放到 QQ 机后传 `providerPath`；该路径未真机执行。
- **真实 QQ 卡片样本**：群邀请卡片的 `mqqapi://` 参数名/格式只有继承仓代码与假样本证据，真实样本 `NOT_RUN`。
- **部署者 QQ 报警**：e2e 只到假 OneBot；真实私聊 `NOT_RUN`。
- **`qq_search` 性能**：`LIKE` 在超大消息板上可能慢；FTS5/中文分词留后续。
- **真实 DSH 插件热重载/常驻**：`NOT_RUN`。
- **systemd 常驻**：`NOT_RUN`。

## 下一步

1. 真实 LLBot **文件上传**：需要明确的目标群/人；验证 `base64://`/绝对路径/`providerPath`（当前只做了只读探测）；
2. 真实 DSH 宿主验证插件 `agents.resume` + heartbeat 控制；
3. 真实卡片样本（json/xml）与真实群邀请接受（需要一条真实待处理邀请）；
4. 真实部署者 QQ 报警（需要真实账号）；
5. `qq_search` 视数据量再评估 FTS5；
6. 0.0.4-hotfix1 已发布；后续真机项（群文件/真实卡片/DSH resume/部署者报警）通过后再决定下一个版本。

```bash
git status --short --branch
```
