# Inbox 来源等待与面板摘要设计（0.0.7-hotfix2 切片 A）

> **状态**：已确认方向（2026-11-16，用户 D-069/D-070 与「也许可以开始了？」授权）
> **范围**：Inbox 服务端来源静默等待、Agent 自主 `inbox_wait_quiet`、可配置面板摘要。媒体/诊断/QQ/xvfb 见独立切片。
> **设计依据**：[hotfix2 调查](../reports/2026-11-16-hotfix2-feature-survey.md)、D-069/D-070。
> **不改变**：Inbox 的单一推送/唤醒路径；消息板仍是业务事实源；SSE 仍只推受限面板；ACK 仍是逐条事实。

## 1. 需求与边界

用户要求：

- 可配置、可热重载的硬静默等待，适用于任意 Inbox source；
- Agent 自己决定是否等待、等多久、对哪个来源等；
- 现有 Inbox 只有一套统一注入调度，不能偷偷起第二个注入器；
- 面板摘要在不破坏 Agent 展开完整内容的前提下，降低注入 token；
- 默认值：**硬等待未配置来源为 0 秒**；**Agent 自主 `inbox_wait_quiet` 默认 quietMs=2000**。

### 非目标

- 不做语义/标点猜测式“是否说完”。
- 不引入 per-source SSE 或 per-source 后台任务。
- 不改变消息板、发送、ACK 或 guarded identity 语义。
- 不在本切片做媒体 OCR、watchdog、xvfb、QQ action 面。

## 2. 术语

- **注入调度**：`InboxService.awaitPush/nextDue/flushPending` 决定何时把 panel 交给 SSE/Agent。
- **硬等待（source wait）**：注入调度中的一个 source-scoped quiet 门槛；它不新建调度器。
- **Agent 自主等待（inbox_wait_quiet）**：Agent 被唤醒后主动调用的一次只读阻塞；不改变注入调度，不 ACK、不冻结、不创建 item。
- **摘要模式**：panel 对每个 item 只展示一行/一个 entry；完整内容仍由 `inbox_get` 获取。

## 3. 硬等待

### 3.1 配置

`inbox.wait` 为热重载策略：

```yaml
inbox:
  wait:
    enabled: true
    defaultQuietSeconds: 0
    defaultMaxWaitSeconds: 20
    sources:
      - source: qq_private
        quietSeconds: 8
        maxWaitSeconds: 20
      - source: qq_group/955432692
        quietSeconds: 5
        maxWaitSeconds: 15
      - source: bma3_debug
        quietSeconds: 0
        maxWaitSeconds: 5
```

语义：

- `enabled=false` 时所有来源不额外等待；单个来源 `quietSeconds=0` 也关闭该来源。
- `sources[].source` 是 source 前缀；匹配使用 `/` 边界，**最长前缀优先**（与 `weighting.sourcePriority` 同语义）。
- `quietSeconds` 范围 `0..300`；`maxWaitSeconds` 范围 `1..600`；`quietSeconds > 0` 时必须 `maxWaitSeconds >= quietSeconds`。
- `defaultQuietSeconds` 默认 0；`defaultMaxWaitSeconds` 默认 20。
- 配置变化后，下一次 `nextDue()`/等待循环立即采用新值；不需要重启。
- 非法配置由现有 `set_config` 的隔离校验拒绝，不落盘。

### 3.2 调度语义

对每个 pending item：

```text
baseDue   = pendingSince + (重要 ? importantDelay : normalDelay)
quietDue  = pendingSince + quietSeconds          // quietSeconds>0 时
hardDue   = pendingFirstSeen + maxWaitSeconds
due       = min(
              max(baseDue, quietDue),
              hardDue,
              maxFirstPushDelaySeconds 的现有硬上限
            )
```

- 同 source 新消息仍由 `afterAppend()` 重置 `pendingSince`；`quietDue` 跟着后移。
- `hardDue` 到点后必须推送，即使对方还在连续发送。
- 若某个 item 仍在 quiet 中（`quietDue > now` 且 `hardDue > now`），其他来源的到期 item 触发 flush 时**不得把它一起冻结**。
- 非 quiet 的 pending 仍保持现有“任一条到期带走同窗口全部 pending”的批次语义，避免改变既有行为。
- 重启后 `pendingSince` 为空时沿用现有行为：以 `item.updatedAt` 为锚；hard cap 从该锚重新计算（本切片不新增持久字段）。

### 3.3 诊断

`InboxStatus` 增加只读观测字段（不包含正文/路径）：

- `waitingForQuiet`：当前被 quiet 持有、尚未到 hardDue 的 item 数；
- `nextQuietAt`：最早的 quiet 到期时间，空字符串表示没有；
- `effectiveWaitRules` 可由 `inbox_diagnose` 从运行期策略读取（不塞进 `InboxStatus` 大对象）。

`inbox_diagnose` 必须能回答：硬等待是否开启、默认值、当前生效规则、哪些 item 正在等、何时最晚推送。

## 4. Agent 自主等待工具

### 4.1 工具契约

工具名：`inbox_wait_quiet`。

输入：

```json
{
  "source": "qq_private/account/12345",
  "sourcePrefix": "qq_group/955432692",
  "quietMs": 2000,
  "maxWaitMs": 20000,
  "includeEntries": false
}
```

- `source` 与 `sourcePrefix` 至少给一个；两者都给时按精确 `source` 优先（或拒绝歧义，见实现选择）。
- `source` 精确匹配；`sourcePrefix` 用 `/` 边界前缀匹配；支持任意 source。
- `quietMs` 默认 2000，范围 `0..60000`。
- `maxWaitMs` 默认 20000，范围 `1..120000`；必须 `>= quietMs`。
- `includeEntries=true` 时返回最新 item 的 bounded entries；默认 false，返回 item 摘要 + `inbox_get` 提示，减少 token。

输出：

```json
{
  "settled": true,
  "reason": "quiet",
  "source": "qq_private/account/12345",
  "quietMs": 2000,
  "maxWaitMs": 20000,
  "waitedMs": 2041,
  "lastActivityAt": "2026-11-16T13:00:00Z",
  "latestItemId": "itm-...",
  "items": []
}
```

持续有新消息到硬上限：

```json
{
  "settled": false,
  "reason": "max_wait",
  "stillActive": true,
  "hint": "来源仍在活动；你可以再等一轮，或现在给出阶段性回复"
}
```

### 4.2 语义

- 只读：不 ACK、不冻结、不修改 `pendingSince`、不创建 item、不推进 revision。
- 每次调用从当前时刻计算；已有 item 的最后活动时间作为初始锚，因此“已经安静够久”会立即返回。
- 新消息到达通过 `InboxService` 的 `notifyAll()` 唤醒等待；每次醒来重新计算 `lastActivity`。
- `sourcePrefix` 匹配多个来源时，以匹配项中的一个最新活动时间为准；同时在结果返回 `matchedSources` 摘要（最多 20 个）以便 Agent 判断。
- 工具不能替代 Inbox 的唤醒；它只在 Agent 已被唤醒/正在运行时使用。

## 5. 面板摘要

### 5.1 配置

```yaml
inbox:
  display:
    mode: auto              # full | summary | auto；默认 auto
    maxMessagesPerItem: 6   # full 模式
    maxItemsPerPanel: 6
    includeOmittedCount: true
    maxCharsPerItem: 1200   # full 模式单条上限
    summaryMaxCharsPerItem: 200
    summaryAboveItems: 8    # auto：可见 item 超过此值切摘要
```

- `mode=full`：保持现有行为；
- `mode=summary`：每个 item 只展示最新 1 条 entry，单条截断到 `summaryMaxCharsPerItem`；
- `mode=auto`：可见 item 数 `> summaryAboveItems` 时使用 summary，否则 full；
- 不新增总字符硬上限；D-044 的“不设总字符上限”仍有效；
- `inbox_get(itemId)` 仍返回该 item 的 bounded full entries，供展开；
- `inbox_items` 保持一行预览；可选增加按 panel index 展开的便利（不强制）。

### 5.2 插件协同

`PanelItem.entries` 在 summary 下只保留 1 条，因此：

- Java `panel()` 的 `omittedMessages`/`truncatedChars` 必须正确；
- DSH 插件 `renderPanel()` 继续按 `entries` 渲染，不返回空白；
- `inbox_get` 的完整 entries 不受影响；
- SSE 的 `panel` 事件仍携带 summary/full 模式对应的 entries，不新增独立事件类型。

### 5.3 诊断

`inbox_diagnose` 需要显示：

- `mode`
- 当前实际使用的模式（auto 解析后）
- `summaryAboveItems`
- `summaryMaxCharsPerItem`
- 当前 visibleItems 数、是否触发摘要。

## 6. 不变量

1. **单一调度器**：硬等待只修改 `InboxService` 的 due/flush，不新增第二个注入器。
2. **不误冻结**：quiet 中的 item 不能被其他 item 的 flush 冻结。
3. **可关闭**：`quietSeconds=0` 或 `enabled=false` 时，调度与现有版本等价。
4. **热重载**：等待/摘要配置改动无需重启；下一次调度立即生效。
5. **Agent wait 只读**：不改 ACK、revision、frozenRevision、消息板。
6. **来源通用**：不在 Inbox core 写 `qq_` 分支；只用 source 前缀匹配。
7. **摘要不丢事实**：summary 只是 panel 显示收缩；`inbox_get` 与消息板仍可取得完整窗口。
8. **非法配置 fail-closed**：越界/非法 source 规则不落盘，旧配置继续生效。

## 7. 验收判据

### 硬等待

- A1：`qq_private` 配置 `quietSeconds=8`，在 0/4/8 秒各追加一条，最后一条后满 8 秒才推送；三条在同一 item。
- A2：连续消息超过 `maxWaitSeconds` 时，在 hardDue 到点推送，不无限等待。
- A3：`qq_group` 或任意自定义 source 配置后同样生效；未配置来源保持 0 秒行为。
- A4：A 来源 quiet 中、B 来源紧急消息到期，B 触发 flush 时 A 不能被冻结/推送。
- A5：`quietSeconds=0` 与 `enabled=false` 的下一次调度行为与改动前一致。
- A6：热重载 `quietSeconds`/`maxWaitSeconds` 后现有 item 下一次计算采用新值。
- A7：`inbox_status`/`inbox_diagnose` 显示等待规则与当前 waiting 状态。

### Agent wait

- B1：`inbox_wait_quiet(source, quietMs=2000)` 在持续新消息时不会提前 settled；静默 2 秒后返回 `settled=true`。
- B2：持续消息超过 `maxWaitMs` 返回 `settled=false, reason=max_wait, stillActive=true`。
- B3：等待期间不 ACK、不冻结、不改变 revision/frozenRevision，不创建 item。
- B4：任意来源（包括 `bma3_debug`/`bma3_task`/自定义 source）可调用；`sourcePrefix` 可匹配多个来源。
- B5：`quietMs=0` 立即返回 settled；非法 source/超界参数返回结构化错误。

### 摘要

- C1：`mode=full` panel 与改动前逐项一致。
- C2：`mode=summary` 每个 item 只有最新 1 条 entry，`omittedMessages`/`truncatedChars` 正确。
- C3：`mode=auto` 在 visibleItems > `summaryAboveItems` 时切摘要，否则 full。
- C4：`inbox_get` 仍返回完整 bounded entries；`inbox_items` 仍是一行预览。
- C5：DSH 插件 `renderPanel()` 对 summary panel 注入非空、可读；真实宿主仍 `NOT_RUN` 直到验证。
- C6：配置热重载后下一次 panel 采用新模式。

## 8. 未决/实现选择

- `source` 与 `sourcePrefix` 同时提供时，本实现选择：**`source` 优先**，并在结果中标记 `matchedBy=source`；不静默混用。
- 群聊是否要按发送者粒度等待：本切片先按 source；`senderId` 级增强另开，不在默认承诺。
- `inbox_wait_quiet` 是否暴露为 DSH 原生 `bma3_inbox` action：本切片先提供 MCP 工具；原生 action 作为后续易用性增强。
