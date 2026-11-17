# QQ 双渠道路由与崩溃恢复实施前复核

> **类型：调查（时间点快照，只作追溯，不作当前事实）**；日期：2026-11-17（UTC+8）。
> **方法**：只读审查当前工作树中的组合根、消息板、Inbox、渠道控制/观察存储和匹配内核；分析三份 SQLite 之间的中断与并发边界。此报告不替代[已确认专题设计](../specs/2026-11-17-qq-official-connect-and-inbox-channel-correlation-design.md)及[实施计划](../plans/2026-11-17-qq-official-connect-and-channel-correlation-plan.md)。
> **触发**：实施 Phase 8–10 前独立复核；当前代码仍可能持续变化，最新事实见 [STATUS](../../agents/STATUS.md)。

## 已确认事实

1. 工作树中的 `Bma3App.ingest` 原路径对 OneBot 先评分、drop 后不落板，然后 `MessageBoardStore.append`，最后 `InboxService.appendOrMerge`；已绑定路由尚未在此快照完成接线。参见 `bma3-app/src/main/java/io/mosire/bma3/app/Bma3App.java` 的 `ingest`。
2. 官方 ingest 的 `OfficialQqProjectionSink`、渠道 `QqChannelRegistry` / `QqChannelObservationStore` / `CrossChannelMatcher` 和通用 `InboxService.appendProjectedOnce` 已存在，但各自通过不同 SQLite 文件/连接持久化；无跨库原子提交。参见 `bma3-app/src/main/java/io/mosire/bma3/app/{official,channel}/` 和 `bma3-inbox/src/main/java/io/mosire/bma3/inbox/core/`。
3. HEAD `43b57b4` 不含 Inbox channel/receipt 表；实现过程中的中间版不是已发布迁移基线。旧 HEAD item/entries 的迁移与新版本 receipt 幂等分别需要测试。
4. 平台不提供两渠道共享消息 ID；映射缺失、匹配关闭、超时或迟到时不能从局部事实推导确定匹配。详见专题设计 §9 和 §16。
5. 此次未运行真实 QQ 收发、真实手机扫码或同群双渠道试验，均为 `NOT_RUN`。

## 推断与工程建议（不是新增用户裁定）

- 对通过既有 `dropBelow` 过滤、且当时已绑定的消息，在和控制面**共享的一把 gate** 下先持久化不含正文的 ingest intent，再写消息板，之后推进 observation、路由和 Inbox；未绑定的 OneBot 原成功路径保持不变。意图不是消息事实；board 未写入时不得伪造 observation。
- intent 必须冻结该事件的绑定/注入开关、展示选择、匹配模式及可验证的发送者身份决策。恢复不能拿当前已改变的开关或映射重判旧消息。空 sender 与合法负优先级仍须允许。
- 绑定/解绑边界必须和入站 gate 协调并可崩溃恢复；不能在两个连接上分别提交 binding 与 start-board-sequence 后假定它们原子。source 各自的 generation 数值不能因为偶然相等就当成同一会话 epoch；跨渠道匹配需验证实际有效绑定对。
- 若首选渠道在线且启用但未观察到某条消息，非首选的观察只保留事实和证据，不建立 item、唤醒或追溯补注。首选不可用/暂停后仅后续消息可选择 fallback；item 一旦确定 display channel，生命周期内不改正文渠道。
- fallback 后的旧副本不能仅因「未匹配」就当新消息。对窗口内无法确定或有多个可能候选的观察保守抑制额外唤醒并保持 `UNVERIFIED`，不冒充 `MATCHED`；超出可比较窗口的副本无法保证精确识别，属于已在设计 §16 记录的限制。
- 匹配关联应按真实绑定作用域做 1:1 原子约束并保留审计 lineage；不可靠时间/内容、歧义、无身份映射不能变成 `MATCHED`。多候选与单侧证据不应强制捏造唯一 peer。
- 投影使用 Inbox 自己的持久 receipt，item/receipt/revision 同库事务；receipt 即使 item 被 ACK 或保留期清理仍应阻止二次注入。晚到证据使用原目标 item ID，目标不存在时仅保留 evidence，不回落到新 item。证据汇总要可按绝对快照与版本幂等更新。
- 启动时在渠道控制工具与外部入站启用前恢复已提交 board 对应的未完成 intent/observation；没有 board 行的 intent 只能等待重投。每个断点和并发切换都需要故障注入测试，不得把组件单测当成端到端通过。

## 待用户拍板

按照现有 D-027/D-028、D-074..D-077 和已确认专题设计，以上属于保守实现与真实性限制，**未发现必须新增的用户裁定**。若未来要求无共享消息 ID、无身份映射且跨任意延迟仍严格识别每一份副本，同时不漏掉相同内容的新消息，现有事实不足，需要另行讨论与裁定；本报告不作这种承诺。
