# 0.0.6 远端使用反馈调查

> 类型：调查（时间点快照，只作追溯，不作当前事实）  
> 日期：2026-11-15（UTC+8）  
> 方法：经用户授权 SSH 只读查询进程、配置摘要、SQLite 数据与 watchdog 元数据；本地对照源码。  
> 触发：远端报告 seq 疑似撞号、Inbox 更新后停止、面板过大等问题，并希望增加定时任务、诊断导出和 QQ 空间。

## 已确认事实

- 远端运行 `0.0.6` app image；Java 进程打开的是部署目录下 `data/bot.db`、`data/inbox.db` 和各自 WAL。远端还存在多个旧版本和旧数据库，不能按文件名猜当前数据源。
- 远端 Inbox 插件包版本 `0.0.6`；`lib/inbox.js` SHA-256 与本地一致。检查时持久化状态存在目标会话，`paused=false`、`stopped=false`。这不证明此前升级过程自动恢复成功。[当前启动与状态代码](../../../dsh-plugin/lib/inbox.js#L189)。
- 检查时消息板 `local_seq=14457` 是一条出站消息。现存 Inbox 条目及历史 delivery 未找到原始 `#14457` 面板记录，不能确认此前展示的是什么。对现存 **10,840 条** Inbox entry，与当前消息板按 `localSeq` 比对：来源、入站方向及时间戳全部一致。两库分别读取且线上仍在运行，这只是当时保留记录的检查，并非跨库原子快照或历史无误证明。
- 进一步只在远端流式检索 13 份 DSH 会话日志（44,862 条事件，未导出正文）：相关会话于 17:15:32（UTC+8）有 Agent 转述 `[17:14] #14457`，随后于 17:15:36 写入同号出站消息。此前调用查询该号未找到；转述文本在当时相邻时间窗口内未能匹配到消息板正文。该转述出现于助手生成的内容中，未在已检索会话日志中找到独立的原始面板注入记录；不能因此断定消息板曾分配或回滚该号，也不能排除日志缺失。事件编号与消息板编号是不同命名空间。
- watchdog 历史事件记录 2026-11-14 18:05（UTC+8）`dshPlugin/UNBOUND`，18:09 `inboxBacklog/PUSH_STALLED`，18:10 backlog 恢复、18:12 插件恢复；检查时所有 watchdog 组件状态为 `UP`。事件不能单独证明故障根因。
- 检查时远端配置面板上限 `maxItemsPerPanel=6`、`maxMessagesPerItem=6`、`maxCharsPerItem=1200`；`dropBelow=-300`，内容规则数为 0。500+ 条报告的时点、来源和当时配置未确认。[面板实现](../../../bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/InboxService.java#L169)。
- DSH 配置指定 `deepseek-search-api` 和 `deepseek-fetch-api`；远端安装的 `dsh-web-deepseek-api@0.2.2-rc.1` 源码将默认 `/v1/tools` 基址分别拼 `/search` 和 `/fetch`。升级时段 journal 里的 4 条 404 与 MCP 有关，并非已证实的 fetch 失败。
- 相关只读脚本 `/tmp/bma3_remote_check.sh`、`/tmp/bma3_remote_times.sh`、`/tmp/bma3_remote_more.sh`、`/tmp/bma3_remote_dsh.sh`、`/tmp/bma3_remote_fetch.sh`、`/tmp/bma3_remote_session_scan.sh`、`/tmp/bma3_remote_seq_compare.sh` 经 SSH 执行 rc=0；脚本与远端实时数据均可能随后变化。凭据、正文及原始会话日志未写入报告。

## 推断与建议

- 独立的 `bot.db` 和 `inbox.db` 在更新、回滚或迁移时若来自不同时间点，可能导致引用不一致；目前无证据证明本次发生过。编号来自不同数据库或误将远端 `message_seq` 当作本地 `localSeq` 也需要排除，不能当成事实。
- `UNKNOWN` 的发送可能已达平台；现有文本发送每次建立新 UUID，调用方重新发起会有重复风险。平台未提供可靠幂等能力时，不能用仅在成功响应后得到的 external id 保证恰好一次。
- 建议设计持久化定时任务、Inbox 与 MCP 各自的更新恢复验收、以白名单和脱敏为基础的诊断导出；都属于待设计事项，不是用户已确认的实现裁定。

## 待确认 / NOT_RUN

- 原始 `#14457` 所在会话、来源、时间和当时数据库快照；远端热更新前后完整恢复过程。
- 原始 `web_fetch` 错误、调用 provider 和状态码来源；未发真实 fetch 请求。
- 真实 LLBot 的深层转发样本、特定群历史、出站群文件、重复发送和 QQ 空间能力。本轮没有进行实际发送或更改远端运行状态。
- 测试与构建 `NOT_RUN`：本轮为只读调查及文档记录，没有修改产品代码。

## 待用户拍板

- QQ 空间的具体范围，以及定时器漏跑补偿和重叠执行语义，应在需求及 spec 中明确。
