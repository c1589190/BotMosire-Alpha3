# qq_remote_history —— 游标分页（替代「猜页大小降级」）

> **状态**：当前有效（2026-11-13）
> **触发**：D-056「回到 bma3 的历史消息调用 tool，有没有什么更加方便的方式？Onebot 有没有那种分段获取历史消息的接口，以至于让单次历史消息获取不至于返回特别特别多的东西」
> **取代**：`QqRemoteHistoryTool` 原有「count 1..1000 + 失败降级 1000→300→100」策略。
> **证据**：[`../reports/2026-11-13-llbot-host-freeze-investigation.md`](../reports/2026-11-13-llbot-host-freeze-investigation.md)（`count=1000` 打死 pmhq CMD-PIPE；`count ≤ 300` 秒回；游标翻页零重叠）。
> **关联**：红线 6（UNKNOWN 禁自动重发）、红线 3（正文不落日志）。

## 1. 问题

原实现把 `count` 当成"一次要多少条"，并假设大页失败可以靠降级救回来。真实 provider（LLBot 8.3.2 + pmhq 9.0.6）的 `GetGroupMsgHistory._handle` 是：

```js
for (let cur = endSeq; cur >= overallStartSeq; cur -= 30) windows.push({start, end: cur});
const rawBatches = await mapWithConcurrency(windows, 5, w => fetchRangeRaw(...));
```

**内部固定 30 条一个窗口、5 路并发。** `count` 直接被翻译成协议 RPC 数量：

| count | `SsoGetGroupMsg` 数 |
|---|---|
| ≤30 | 1 |
| 100 | 4 |
| 300 | 10 |
| 1000 | **34** |

`count=1000` 会在数秒内发出 34 个协议请求，打死 QQ 客户端的 CMD-PIPE，之后**连无关的 OIDB 命令**也 `no reply within timeout`。此时再"降级"已经没有意义——管道已经坏了。

**真正的分段能力是 `message_seq` 游标**，provider 一直支持，bma3 没用。

## 2. 契约

### 2.1 参数

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `chatType` | `GROUP`\|`PRIVATE` | 必填 | |
| `targetId` | string | 必填 | 群号 / QQ 号 |
| `accountId` | string | 当前账号 | |
| `cursor` | string | `""` | `message_seq`；省略 = 最新一页 |
| `count` | int | **30** | **1..100** |
| `reverseOrder` | bool | `false` | `false`=旧→新（时间顺序），`true`=新→旧 |
| `includeSegments` | bool | false | 返回结构化 segments |
| `timeoutMs` | int | **10000** | 单次请求超时，2000..60000 |

### 2.2 返回

```json
{
  "chatType": "GROUP", "targetId": "955432692",
  "requestedCount": 30, "fetchedCount": 30,
  "messages": [ { "messageId": "...", "messageSeq": "764607", "at": "...",
                  "sender": {...}, "text": "..." } ],
  "nextCursor": "764581",   // 本页最旧 seq - 1，传回 cursor 得上一页（更旧）
  "prevCursor": "764642",   // 本页最新 seq + count，传回 cursor 得下一（更新）页
  "hasMore": true,          // fetchedCount >= requestedCount
  "count": 30
}
```

**游标语义（provider 真实语义，勿改）**：`message_seq = N` 表示「取到 seq N 为止、向过去数 `count` 条」。
于是两个方向不对称：

| 方向 | 传什么 | 为什么 |
|---|---|---|
| 往更旧翻（`nextCursor`） | `本页最旧 seq - 1` | 窗口落在本页下方，紧邻且不重叠 |
| 往更新翻（`prevCursor`） | `本页最新 seq + count` | 窗口末端要在本页**上方**整整一页；传 `+1` 只会得到本页平移一条 |

`prevCursor` 依赖 `count`，所以**换页大小时不要用它**——要么保持同一 `count`，要么自己算。

### 2.3 错误码

| code | 含义 |
|---|---|
| `INVALID_ARGUMENT` | 参数非法（含 `count > 100`） |
| `HISTORY_TIMEOUT` | 单次请求超时（**不降级、不重试**） |
| `HISTORY_FAILED` | provider 返回非 0 retcode 或形状不对 |
| `HISTORY_CIRCUIT_OPEN` | 熔断中，未发出请求 |
| `HISTORY_BUSY` | 同账号已有请求在飞 |

`retcode == 1200` = **空页，不是错误** → `ok` + `hasMore=false` + `nextCursor=""`。

## 3. 不变量

- **I1 单次调用最多 1 次 provider 请求**；由 `count ≤ 100` 保证 provider 内部 RPC ≤ 4。
- **I2 不存在「猜页大小」式降级**：一次调用只试一个 count，失败即失败。
- **I3 游标可逆**：`nextCursor = min(seq) - 1`、`prevCursor = max(seq) + count`；两者都按 min/max 计算而非按位置，所以与 `reverseOrder` 无关。`prevCursor` 依赖 `count`。
- **I4 参数名与 provider schema 一致**：发 `reverseOrder`；**不发** `reverse_order` / `parse_mult_msg` / `disable_get_url`（provider 不认识，静默丢弃）。
- **I5 同账号串行**：进程内每账号最多 1 个在飞请求，超出返回 `HISTORY_BUSY`（不排队）。
- **I6 熔断**：同一账号连续 3 次 `HISTORY_TIMEOUT` → 进入熔断 60 s，期间直接 `HISTORY_CIRCUIT_OPEN`；任意一次成功清零。
- **I7 超时不自动重试**（与红线 6 同源：不确认的调用不叠加）。
- **I8 只读**：不写消息板。

## 4. 验收判据

| # | 判据 |
|---|---|
| A1 | `count=101` → `INVALID_ARGUMENT`，且**没有请求发出** |
| A2 | `count` 省略 → 发送 `count=30` |
| A3 | 传 `cursor="12345"` → 请求体含 `message_seq=12345` |
| A4 | 请求体**不含** `reverse_order` / `parse_mult_msg` / `disable_get_url`；含 `reverseOrder` |
| A5 | 返回 30 条 seq 1000..1029 → `nextCursor="999"`、`prevCursor="1059"`（=1029+30）、`hasMore=true` |
| A6 | 返回 3 条（< count）→ `hasMore=false` |
| A7 | `retcode=1200` → `ok`，`count=0`，`nextCursor=""`，`hasMore=false` |
| A8 | 超时 → `HISTORY_TIMEOUT`，返回里 `attempts` 只有 1 项（证明没降级） |
| A9 | 连续 3 次超时后第 4 次 → `HISTORY_CIRCUIT_OPEN`，且第 4 次**没有请求发出** |
| A10 | 熔断冷却期过后可再次请求；成功后连续计数清零 |
| A11 | 同账号并发 2 个 → 第二个 `HISTORY_BUSY` |
| A12 | `reverseOrder=true` 时返回顺序与 `false` 相反，但 `next/prevCursor` 不变 |

## 5. 迁移

- 默认 `count` 从 20 → **30**（1 个 provider RPC，实测 0.64 s / 20 KB）。
- `count` 上限 1000 → **100**。
- 默认 `timeoutMs` 20000 → **10000**。
- 默认 `reverseOrder` 语义收敛为"仅控制显示顺序"，默认 `false`（provider 默认，也是时间顺序）。
- 删除 `attempts` / `attemptDetails` / `degradedFrom`（单次调用后无意义），错误体保留单条 `attempt`。
