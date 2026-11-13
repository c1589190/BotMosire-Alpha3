# 0.0.4-early 远程使用报告 —— 逐条定位（triaging）

> **类型**：调查（时间点快照，只作追溯，不作当前事实）
> **日期**：2026-11-13
> **触发**：远端在真实使用 `0.0.4-early` 后提交的 P1/P2/P3 共 9 条报告，要求「定位相关问题」。
> **方法**：本仓库 `feat/bma3-plan-1 @ fb2f05a`（0.0.5-early）源码逐条核对；每条给 `file:line`；**未在真机 LLBot 上复现**（远端现象按转述处理）。
> **限制**：远端 devmosire 的库、日志、群样本不在本机；凡"复现"均标 `NOT_RUN`。本文件只做定位与建议，不做裁定。

---

## 0. 总表

| # | 报告问题 | 定位结论 | 复核严重度 | 修复状态（2026-11-13） |
|---|---|---|---|---|
| P1-1 | 群历史 count=1000 不稳 | 降级早已实现；差在"超时不分类、超时不可配、尝试明细只在日志里" | P2 | **已修**：`attemptDetails[]`（count/outcome/retcode/elapsedMs/messages）+ `TIMEOUT` 独立分类 + `timeoutMs` 参数（2s..120s） |
| P1-2 | 某群历史读不到 0 条 | 真缺口：无 `get_group_info`；空页只回通用 hint | P1 | **已修**：新增 `qq_group_info`；空页 hint 改为指向它 |
| P1-3 | 私聊非好友返回 UNKNOWN | 半真：UNKNOWN 只在超时/断线；结果丢 retcode；无替代通道 | P1 | **部分修**：结果带 `retcode/providerStatus/providerMessage`；`qq_private_send` 支持 `groupId`（群临时会话）。主动加好友未做（依赖 provider 能力） |
| P2-4 | 没有撤回工具 | 真缺口 | P2 | **已修**：新增 `qq_delete_msg`（DELETED/FAILED/UNKNOWN 三态，UNKNOWN 禁重试） |
| P2-5 | 合并转发只展开一层 | 已知限制 | P2 | **已修**：`qq_forward_view` 新增 `depth=1\|2`，depth=2 把嵌套那层展开进 `nested`（单次最多 10 条嵌套、每条 50 行） |
| P2-6 | 长图不可读 | bma3 侧不给尺寸；4096 属宿主 | P3 | **已修**：`LongImageSplitter` + `qq_media_view` 自动切段（详见该节） |
| P2-7 | 群列表缓存滞后 | 真缺口：失败无回退 | P2 | **已修**：`qq_group_list` 实时失败时退回本地目录缓存并标 `source=cache`，提示用 `qq_group_info` 确认 |
| P3-8 | 1991 条旧消息 sender 空 | 加列未回填 | P3 | **部分修**：写入侧 `userId<=0` 不再写 `"0"`（改空）；**老数据不可恢复**，未做回填 |
| P3-9 | 面板截断无提示 | 已实现，表达不显眼 | P3 | **已修（文案）**：`…（另有 N 条未显示；需要时用 inbox_list 拉全部）` |

---

## P1-1 群历史接口不稳 —— **降级已经有了，缺的是"超时说清楚"**

**代码事实**

- `QqRemoteHistoryTool.java:109-117`：`attempts` 用 `LinkedHashSet` 拼成 `requested → 300 → 100`，去重，最多三次。
- `QqRemoteHistoryTool.java:133-136`：`.orTimeout(20, TimeUnit.SECONDS)`。
- `:141-149`：`retcode == 1200` 且 `attemptCount > 100` ⇒ 判为"页太大"，**降级重试**（注释里就写着真机 1000 空/300 通）。
- `:158-164`：大页返回空数组同理降级。
- `:166-168`：`catch (RuntimeException)` —— **超时也走这条路**，记 `lastError` 后继续下一个 count。
- `:176-186`：返回体带 `requestedCount / fetchedCount / degradedFrom / attempts`。

**所以报告里的建议「统一 1000→300→100 降级」已实现**（2026-11-12 的 D-046 就做了）。剩下三个真问题：

1. **超时没有被单独识别**：`.join()` 抛的是 `CompletionException`，`lastError` 只能写成 `CompletionException`，日志 `outcome=failed:CompletionException`。运维看到"失败"分不清是"页太大"还是"网络抖"。
2. **20s 是硬编码**（`:134`），不同 LLBot 部署/网络不能调。
3. **"把 timeout 当部分成功"做不到**：每次尝试都是全量请求，没有可回传的半截数据；能力上只能做到"降级后成功 ⇒ `degradedFrom` 标出你拿到的是 300 而不是 1000"。这一条**已经在语义上满足**，需要的是把 `degradedFrom` 在面板/工具里显式说出来。

**建议**：`attempts` 结果里逐次记 `{count, outcome, retcode, elapsedMs, messages}`（现在只在日志里有）；超时单独成 `TIMEOUT` 码；`timeoutMs` 进配置。

---

## P1-2 群历史读不到（719028297 返回 0）—— **真缺口：没有诊断能力**

**代码事实**

- `QqRemoteHistoryTool.java:244-262`：空页走 `empty()`，只返回
  `hint = "provider 返回 retcode 1200：没有更多历史或该页为空"` ——
  **不区分**「群不存在 / 机器人不在群 / 群被全员禁言 / 权限不足 / 确实没消息」。
- 全仓**没有 `get_group_info` 工具**：`grep -rn "get_group_info" --include=*.java` 只命中 `tools/e2e-local/FakeOneBot.java:61`（测试假服务）。
  ⇒ 报告里"`get_group_info` 却正常"是操作者**手写 WS** 调出来的，不是 bma3 的能力。

**为什么这值得算 P1**：bma3 的设计是把平台差异吸收在工具面里（D-014 同一思路），让 Agent 自己写 WS 调 `get_group_info` 等于把协议细节漏给上层。而且"0 条"这个结果在 Agent 看来和"这个群今天没人说话"完全一样——**无法行动**。

**建议**：新增 `qq_group_info`（`get_group_info`）+ 在 `qq_remote_history` 空页时附带诊断字段：
`{ joined: bool, groupName, memberCount, canReadHistory, providerRetcodes[] }`，并给出下一步建议。

---

## P1-3 私聊非好友 → UNKNOWN —— **半真，且没有任何替代路径**

**代码事实**

- `QqSendSupport.java:57-61`：
  ```java
  boolean ok = "ok".equals(response.path("status").asText()) && retcode == 0;
  state = ok ? "SENT" : "FAILED_BEFORE_SEND";
  } catch (RuntimeException failure) { state = "UNKNOWN"; }
  ```
  ⇒ **只有抛异常（超时/断连）才是 UNKNOWN**；provider 明确回 `retcode != 0` 是 `FAILED_BEFORE_SEND`。
  所以"对非好友返回 UNKNOWN"更可能是 **LLBot 对这个目标直接不响应/超时**，而不是"判定为非好友"。
- 问题在于：**结果里没有 retcode**（`states` 只有 `localSeq/state/messageId`，`:70-78`），事后无法区分这两种情况。
- `QqPrivateSendTool.java:64-67`：只调 `send_private_msg {user_id, message}`，**没有** `group_id`（群临时会话）参数，也没有加好友路径。

**这是三个独立的小问题**：
1. 三态的信息量不够（缺 retcode / 缺"为什么"）；
2. 没有群临时会话通道（OneBot 的 `send_private_msg` 支持带 `group_id`）；
3. 没有好友申请通道（`set_friend_add_request` 是**审批**用的，主动加好友 OneBot 标准没有，要看 LLBot 扩展）。

**建议**：先把 1 做完（结果带 `retcode`/`message`），再评估 2/3；2 很便宜，3 需要确认 provider 能力。

---

## P2-4 没有撤回消息工具 —— **真缺口**

`grep -rn "delete_msg"` 全仓 0 命中。底层 `OneBot11Client.sendAction(action, params)` 是通用的（`QqAccountActionTool` 已经在用它跑 `get_group_list` 之类的动作），所以加一个 `qq_delete_msg {source, messageId}` 的成本很低。

**注意点（建议写进 spec）**：2 分钟窗口、管理员权限、`retcode` 分类（无权限/超时/已过期）、以及**撤回后消息板要不要标状态**（当前 `chat_message` 没有撤回字段）。

---

## P2-5 合并转发 —— **已知限制，代码里写明了**

- `QqForwardViewTool.java:16-18`：「只展开一层；嵌套转发标 `[嵌套合并转发]`，不递归」。
- `QqMediaViewTool.java:34-36` 的 description 只列 图片/文件/语音/视频，**没有 forward**，所以 `qq_media_view` 打不开合并转发。

**建议**：给 `qq_forward_view` 加 `depth`（默认 1，上限 2）+ `parentOffset`，让"再拉一层"变成一次调用而不是两次手拉。

---

## P2-6 长图不可读 —— **bma3 能做的那一半还没做**

- `QqMediaViewTool.java:63-83`：返回 `{fileId, kind, contentType, size, inline, path, hint}` —— **没有 width/height**。
- 4096 高度压缩是 DSH 宿主 `read_image` 的行为，bma3 无法改变。

**已实现（2026-11-13，未提交）**：`LongImageSplitter` + `qq_media_view` 接线。

- 超过 `maxPartHeight`（默认 2048）就切段：相邻段**重叠 64px**（不把字切两半）、一律写 PNG（不二次有损）、落到 `policy.dir()`（`<dataDir>/downloads`，QQ 发送工具可直接取）。
- 返回 `width/height/split/parts[{index,total,path,bytes,width,height,y0,y1}]` + 一段说明为什么切、怎么用。
- 拒绝而不是装成功：像素数 > 4000 万（OOM 风险）、段数 > 16、ImageIO 解不了的格式（如 WebP），各自返回明确 `reason`。
- 实测：1080×18000 → 9 段、每段 1080×2048（末段 2128），肉眼确认文字清晰。
- 仍属宿主侧：`read_image` 自身把超图压到 4096 高，这段 bma3 改不了。

---

## P2-7 群列表/成员缓存滞后 —— **真缺口**

- `QqContactListTool.java:47-53`：只有 `get_group_list` **成功**时才 `rememberGroups()` 写本地目录缓存。
- 失败/超时：没有回退，也没有 `get_group_info` 可用（见 P1-2）。
- 所以"新加的群不在 list 里、但 get_group_info 正常"是**完全符合现状**的：list 是缓存+一次性拉取，info 是实时单查。

**建议**：和 P1-2 一起做 —— 有了 `qq_group_info` 之后，`qq_group_list` 失败时对已知群号逐个 info 补齐；并在返回里标 `source: cache|live`。

---

## P3-8 1991 条旧消息 sender 为空 —— **根因确认：加列没回填**

- `SqliteMessageBoardStore.java:78`：`ensureColumn(statement, "chat_message", "sender_id", "TEXT")`
  —— **表早就在了，sender_id 是后来加的列**；`ALTER TABLE ADD COLUMN` 对历史行给 NULL，之后从来没回填过（`:487`）。
- 新写入路径是好的：`Bma3App.java:367` `String senderId = Long.toString(event.userId())`；`:386-394` 写进 `InboundMessage`。
- **顺带一个隐患**：如果事件没有 `user_id`，这条会写成字符串 `"0"` 而不是空——"0" 会被当成一个真实 QQ 号参与画像/检索。

**建议**：① 写入侧：`event.userId() <= 0` ⇒ 存空而不是 `"0"`；② 历史侧：老数据**没有可恢复来源**（当时的表里就没这列），只能回填成 `unknown` 或按 `source` 粗推断；③ 检索/画像侧显式跳过空 sender，别当成一个人。

---

## P3-9 面板截断没提示 —— **其实做了，但表达不显眼**

- `InboxService.java:169-182`：面板只取前 `maxItemsPerPanel` 条，同时算出 `omittedItems`。
- `InboxPanel` 记录带 `totalItems / omittedItems / includeOmittedCount`，`InboxTools.json()` 用 Jackson 直接序列化 ⇒ **字段确实在工具结果与 SSE 里**。
- `ConfigLoader.java:153`：`includeOmittedCount` **默认 true**。
- 插件侧 `dsh-plugin/lib/inbox.js:51-52, 81-83`：头部输出 `items=6/9`，尾部输出 `…（另有 N 个来源未显示）`。

**所以"没看到提示"更可能是**：`items=6/9` 这种数字不够显眼，或者那次 `eligible.size() <= maxItemsPerPanel`（根本没省略）。

**建议**（纯文案）：把头部改成 `另外还有 N 条未显示（inbox_list 查看）`，一句话说清"是什么 + 怎么办"。

---

## 建议的下一版范围（建议，非裁定）

| 优先级 | 内容 | 理由 |
|---|---|---|
| **P1 批** | `qq_group_info` + history 空页诊断（P1-2/P2-7 合并做）；发送结果带 retcode（P1-3-1） | 都是"让 Agent 能自己判断"，符合工具面哲学 |
| **P2 批** | `qq_delete_msg`；forward `depth`；media 返回宽高+切段提示；history attempts 明细 + 可配超时 | 单点小改，收益直接 |
| **P3 批** | sender 写入修正 + 老数据标记；面板文案 | 低风险 |
| **待定** | 群临时会话（P1-3-2）、主动加好友（P1-3-3，需确认 LLBot 能力） | 依赖 provider |

## 修复批次（2026-11-13，未提交）

改动清单：

| 文件 | 改动 |
|---|---|
| `QqRemoteHistoryTool` | `attemptDetails[]`、`TIMEOUT` 分类、`timeoutMs`、失败体带 hints |
| `QqGroupInfoTool`（新） | `qq_group_info`，成功顺手刷新群目录缓存 |
| `QqDeleteMsgTool`（新） | `qq_delete_msg`，三态 + 撤不回的原因提示 |
| `QqSendSupport` | 每次发送的 state 里带 `retcode/providerStatus/providerMessage` |
| `QqPrivateSendTool` | 新增 `groupId` → 群临时会话（`send_private_msg` + `group_id`） |
| `QqForwardViewTool` | 新增 `depth=1\|2`，嵌套展开进 `nested` |
| `QqContactListTool` | `get_group_list` 失败退回目录缓存（`source=cache`） |
| `Bma3App` | `userId<=0` 的发送者写空而不是 `"0"` |
| `dsh-plugin/lib/inbox.js` | 面板省略提示改成人话（+ 对应测试期望） |

新增测试：`QqGroupInfoAndDeleteToolTest` 6 条、`QqRemoteHistoryToolTest` +2 条（明细/TIMEOUT）、`LongImageSplitterTest` 7 条。
门禁：`./mvnw -o test` **136 tests / 0 fail / 1 skip**；`dsh-plugin npm test` **28 pass / 0 fail**。

## 实际调用测试（2026-11-13，真进程 + 真 MCP + 假 OneBot）

栈：`FakeOneBot`（已扩展 `delete_msg` / 大页 `retcode 1200` / 大页不响应 / `get_group_info` 失败 /
嵌套转发 / `/control` 运行时开关）+ `bma3-app-0.0.5-early.jar` + 真 MCP（Bearer）+ 假 CDN。

| # | 调用 | 实测结果 |
|---|---|---|
| 1 | `qq_group_info {groupId:955432692}` | `ok=true, groupName=E2E群, memberCount=42, maxMemberCount=200` |
| 2 | `qq_group_info {groupId:999}` | `ok=false, retcode=102`，hint = `provider retcode=102（常见：不在群/权限不足）` |
| 3 | `qq_delete_msg {messageId:"12345"}` | `state=DELETED, retcode=0` |
| 4 | `qq_delete_msg {messageId:"999"}` | `state=FAILED, retcode=100` + 2 分钟/权限提示 |
| 5 | `qq_delete_msg {messageId:"out-abc"}` | `INVALID_ARGUMENT`，**未调用 provider** |
| 6 | `qq_remote_history {count:1000}` | `fetchedCount=300, degradedFrom=1000`；`attemptDetails=[{count:1000,outcome:"retcode1200",retcode:1200,elapsedMs:4},{count:300,outcome:"ok",messages:1}]` |
| 7 | 同上 + provider 对大页**不响应** + `timeoutMs:2000` | `attemptDetails[0] = {outcome:"TIMEOUT", elapsedMs:2001}`，随后 300 成功；`degradedFrom=1000` |
| 8 | `qq_private_send {userId:132843233, groupId:955432692}` | `state=SENT, retcode=0`；假服务日志 `EVENT send_private_msg tempSession=true` ⇒ **group_id 真的进了请求** |
| 9 | `qq_forward_view {depth:1}` vs `{depth:2}` | depth=1 无 `nested`；depth=2 展开出 `nested.messages`（2 条，含更深一层的 `[更深一层的合并转发]`） |
| 10 | `qq_group_list`（正常） | 返回 provider 原始 `data`，并写入本地群目录 |
| 11 | `qq_group_list`（provider retcode=120） | **回退缓存**：`{source:"cache", liveRetcode:120, groups:[{group_id:955432692,…}], hint:"实时失败…用 qq_group_info 确认"}` |
| 12 | 注入一条带 1080×18000 长图的群消息 → `qq_media_view` | `width=1080, height=18000, split=true, parts=9`，每段 `1080×2048`（末段 2128），y 区间连续且重叠；落盘在 `<dataDir>/downloads/`；第 1 段肉眼确认「第 001–033 行」清晰 |

**测试中发现并修掉的真 bug（P2-7）**：第一版缓存回退**不生效**——`QqAccountActionTool` 拿到响应就返回
`ToolResult.ok`，provider 失败也照样 ok，而我判的是 `result.success()`。改成解析
`response.status/retcode` 之后才正确回退（上面第 11 行的结果就是修复后重测的）。

## 真实 LLBot 实测（2026-11-13，`ws://47.100.101.156:3001/`）

用真实 token 起来真进程 `bma3-app-0.0.5-early.jar`，走真 MCP 调用。**只做了只读动作**
（`qq_group_list` / `qq_group_info` / `qq_remote_history`），未做任何写入。

### 实测到的事实

| 观察 | 结论 |
|---|---|
| `qq_account_list` → `connected=true, self=3924945183(肥鱼弗洛洛)` | 连上了，账号身份正确 |
| `qq_group_list` → **22 个群**，含 `150112205 1980新冷战(71)`、`938269591 历史歧途的选择(75)`、`719028297 伟大拉莱耶扪特之芸芸众生！！(23)` | **P2-7「新群不在群列表」在这台部署上不复现**；报告里说缺的三个群都在 |
| `qq_group_info` × 4（955432692 / 150112205 / 719028297 / 938269591）全部 `ok=true, retcode=0`，成员数 34/71/23/75 | **P1-2 不是「机器人不在群」**：`719028297` 机器人确实在，且群可读 |
| `qq_remote_history` 对 3 个群、count ∈ {1000, 300, 100}、`timeoutMs=8000` → **9 次尝试全部 TIMEOUT** | `attemptDetails` 每次都显示 `elapsedMs≈8000, retcode=-1` |
| 单跑 `count=100`（默认 20s 超时）→ 仍 TIMEOUT（20000ms） | **这台 LLBot 的 `get_group_msg_history` 直接不响应**，与"页太大"无关 |

### 与远端报告的差异（重要）

远端说「降到 100/300 就正常」。**实测不是这样**：降到 100 依然 20s 无响应。
所以真实根因更可能是 **provider 侧这个动作不可用/被禁用**（或需要额外参数），而不是"count 太大"。
P1-1 的降级逻辑本身没错（它把 1000→300→100 都试了一遍并如实记录），但它救不了一个**完全不响应**的动作。

> **⚠ 上述推断已于 2026-11-13 二次实测推翻。** 读容器内 `llbot.js` 源码后确认：`get_group_msg_history` 内部**固定 30 条一窗、5 路并发**，
> `count=1000` 会瞬间发出 **34 个** `SsoGetGroupMsg`，打死 QQ 客户端的 CMD-PIPE，**连无关 OIDB 命令一起超时** ——
> 后续 `count=300/100` 的"超时"是因为它们排在被 1000 打死的管道后面，**不是 provider 不支持**。
> 重启后实测 `count ≤ 300` 全部秒回（10/30/60/100/300 → 0.47/0.64/0.73/1.37/3.58 s）。
> 正确修法不是"标记为能力不确定"，而是**降 count 上限 + 改用 `message_seq` 游标分页**。
> 详见 [`2026-11-13-llbot-host-freeze-investigation.md`](2026-11-13-llbot-host-freeze-investigation.md)。

**建议**：把 `get_group_msg_history` 通路标记为"provider 能力不确定"，并在 `qq_remote_history` 失败提示里加上
「如果 100 也超时，说明这个 provider 不支持该动作，改用 `qq_conversation_history`（本地消息板）」。

### 未能完成的部分

- 实测过程中 LLBot 之后**不再接受新的 WS 连接**（TCP 端口仍可连，但 WebSocket 握手超时），
  等待 90 s 后重试仍失败；期间已停止重试，避免继续压它。
  这本身也是一个值得注意的现象：**这台 LLBot 似乎只容忍单连接/单会话**，断开后不会立刻释放。

> **⚠ 该现象已于 2026-11-13 查明，与"单连接"无关。** 那两次重连（+90 s、+210 s）正好落在整机冻结窗口内：
> 17:21:06 后 ECS 内存耗尽 livelock 约 16 分钟，17:37 被硬重启。TCP 能连是内核 SYN 队列还活着，
> WS 握手超时是用户态已不调度。见 [`2026-11-13-llbot-host-freeze-investigation.md`](2026-11-13-llbot-host-freeze-investigation.md)。
- 因此 **`qq_delete_msg` / `qq_private_send`（含群临时会话）的真实 provider 语义仍未验**，
  只验过工具行为（见上一节「实际调用测试」）。

## NOT_RUN

- `qq_delete_msg` / `qq_private_send` 的真实平台语义（权限、2 分钟限制、临时会话是否支持）：`NOT_RUN`。
- 群历史：成功路径**已于 2026-11-13 二次实测取得**（`count` 10/30/60/100/300 全部 `retcode=0`，`message_seq` 游标翻页零重叠、严格连续）；`count=1000` 的致死路径**未复现**（避免再次打死远端机器人），只由源码 + 超时计数佐证。
