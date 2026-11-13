# 0.0.4-early 远程使用报告 —— 逐条定位（triaging）

> **类型**：调查（时间点快照，只作追溯，不作当前事实）
> **日期**：2026-11-13
> **触发**：远端在真实使用 `0.0.4-early` 后提交的 P1/P2/P3 共 9 条报告，要求「定位相关问题」。
> **方法**：本仓库 `feat/bma3-plan-1 @ fb2f05a`（0.0.5-early）源码逐条核对；每条给 `file:line`；**未在真机 LLBot 上复现**（远端现象按转述处理）。
> **限制**：远端 devmosire 的库、日志、群样本不在本机；凡"复现"均标 `NOT_RUN`。本文件只做定位与建议，不做裁定。

---

## 0. 总表

| # | 报告问题 | 定位结论 | 复核严重度 |
|---|---|---|---|
| P1-1 | 群历史 count=1000 不稳 | **已实现降级**（1000→300→100，含超时/1200/空页）；差在"超时不分类、超时不可配" | **P2**（体验/可观测） |
| P1-2 | 某群历史读不到 0 条 | **真缺口**：`get_group_info` 工具不存在；空页只回一句通用 hint | **P1** |
| P1-3 | 私聊非好友返回 UNKNOWN | **半真**：UNKNOWN 只在异常/超时出现，provider 明确失败是 `FAILED_BEFORE_SEND`；但结果**丢掉了 retcode**，无法区分；且无任何替代发送路径 | **P1** |
| P2-4 | 没有撤回工具 | **真缺口**，全仓无 `delete_msg` | **P2** |
| P2-5 | 合并转发 | **已知限制**（代码里写明只展开一层）；`qq_media_view` 不支持 forward | **P2** |
| P2-6 | 长图不可读 | **半在 bma3**：`qq_media_view` 不返回宽高；4096 压缩是 DSH 宿主工具行为，bma3 改不了 | **P2** |
| P2-7 | 群列表缓存滞后 | **真缺口**：只在成功时写缓存，失败无回退，且没有 `get_group_info` 可回退 | **P2** |
| P3-8 | 1991 条旧消息 sender 空 | **根因确认**：`sender_id` 是后加的列，老行是 NULL，从未回填 | **P3** |
| P3-9 | 面板截断无提示 | **已实现**，但表达是 `items=6/9` 这种数字，不够显眼 | **P3**（文案） |

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

**建议**：落盘时用 `ImageIO.read` 读尺寸塞进返回（`width/height/aspect/estimatedSegments`），并在 `hint` 里直接说「这是 1080×18000 的长图，read_image 会被压到 4096 高、字会糊，建议按 y=0..6000/6000..12000/… 分 3 段」。更进一步可以直接在 bma3 侧切段落盘，返回 `parts[]`。

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

## NOT_RUN

- 远端 9 条现象**均未在本机复现**（无远端库、无对应群、无 LLBot 会话）。
- 本文件结论全部来自**源码阅读**，不含真机验证。
