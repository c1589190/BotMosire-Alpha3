# iibot → BMA3 易用性差距调查

> **类型**：调查（时间点快照，只作追溯，不作当前事实）· 2026-11-09
> **触发**：用户要求核对 AgentLib 0.2.5 迁移，并调查老 iibot 的易用性功能是否漏迁。
> **方法**：只读源码/文档对照；旧仓 `iiBotMosire` HEAD `refactor/bot-first-main-agent`，BMA3 `feat/bma3-plan-1` 0.0.1；未跑真实 QQ/B 站验收。
> **对照**：`/home/cna/DevMosire-iiBot/iiBotMosire`（Alpha20261103）↔ `BotMosire-Alpha3` 0.0.1
> 结论：BMA3 的“传输/MCP/浏览器/发布”已经成形，但老 iibot 的**工具面导航、用户备注、来源权重、请求管理、摘要渲染、发送队列**大多没有迁过来。这是当前“能用但不好用”的主要原因。

## 1. 先给结论

BMA3 当前暴露 8 个 QQ/Inbox 工具 + 7 个浏览器工具；老 iibot 约 28 个 QQ/Bot 工具。缺失不是零散功能，而是四类“Agent 自己找得到、改得动”的能力：

1. **找不到会话**：没有 thread 列表、最近会话、历史、引用链、消息详情；
2. **改不了优先级**：没有用户备注和按来源权重，只有全局配置；
3. **做不了社交操作**：好友/群请求、入群、群目录全缺；
4. **发得不舒服**：没有 reply、任意私聊、分片、发送状态/节流；图片发送刚补上。

另有渲染与唤醒层的易用性：老 iibot 的 `MessageSummaryBuilder` 会渲染时间/本地 seq/备注标签/引用/媒体/@；BMA3 目前只有 `[HH:mm] #seq 发送者：正文`。老 iibot 的 Inbox 适配器有持久 binding、SSE/revision、claim/view-ack、自动 ack、backoff、ops log；BMA3 目前是内存 binding + 轮询 + 手动 ack。

## 2. 工具面对照

### 2.1 老 iibot 工具（`iibot-bot/.../mcp`）

| 组 | 工具 |
|---|---|
| 账号 | `qq_account_list`、`qq_account_login_info`、`qq_account_status` |
| 会话导航 | `qq_thread_list`、`qq_thread_history`、`qq_message_get`、`qq_message_thread`、`qq_conversation_history`、`qq_recent_messages`、`qq_remote_history` |
| 读取/确认 | `qq_conversation_read`、`qq_conversation_ack`、`bot_thread_read`、`bot_thread_ack` |
| 发送 | `qq_reply`、`conversation_send`、`qq_private_send`、`qq_conversation_send` |
| 用户个性化 | `qq_user_note_get`、`qq_user_note_list`、`qq_user_note_upsert` |
| 来源权重 | `qq_source_weight_get`、`qq_source_weight_list`、`qq_source_weight_set` |
| 社交请求 | `qq_friend_request_list/decide`、`qq_group_request_list/decide`、`qq_group_invite_accept`、`qq_group_search` |
| 其它 | `qq_forward_read`、`qq_inbox_history`、`bot_tool_call_logging` |

证据：`iibot-bot/src/main/java/io/mosire/iibot/bot/mcp/BotMcpTools.java:273-315`。

### 2.2 BMA3 当前工具

| 组 | 工具 |
|---|---|
| QQ | `qq_account_list`、`qq_conversation_read/ack/send/send_image` |
| Inbox | `inbox_status/list/ack` |
| 浏览器 | `browser_open/snapshot/click/type/text/screenshot/video_frames` |

证据：`bma3-qqconnect/.../QqToolSupport.java`、`bma3-inbox/.../InboxTools.java`、`bma3-webconnect/.../BrowserTools.java`。

### 2.3 缺口清单

| 缺口 | 老 iibot 能力 | BMA3 现状 | 影响 |
|---|---|---|---|
| 会话索引 | thread list / recent / history | 无 | Agent 必须先知道 `source`，无法自己发现会话 |
| 消息详情 | message get / thread / forward read | 无 | 不能沿着引用链查上下文，合并转发不可读 |
| 用户画像 | user note get/list/upsert | 无 | 不能记“这个人是谁/重要程度/标签” |
| 来源策略 | source weight get/list/set | 只有全局 `weighting` | 不能给某个群/人单独降噪或提高优先级 |
| 账号诊断 | login info / status | 只有 `connected` 布尔 | 不能看昵称、在线状态、最近错误 |
| 主动私聊 | private_send 任意 QQ | 只能对已有 `source` 发 | 不能主动找用户，只能被动回复 |
| 回复语义 | `qq_reply` / reply-to | 只有纯文本 send | 不能引用回复，群聊里容易丢上下文 |
| 社交操作 | 好友/群请求、入群、群搜索 | 无 | 只能看消息，不能处理关系和入群 |
| 发送体验 | 三态、节流、分片、发送历史 | 三态有，节流/分片/状态查询无 | 超长文本可能被网关拒；刷屏风险 |
| 摘要渲染 | 时间/seq/备注/标签/引用/媒体/@ | 时间/seq/发送者/正文 | 群聊/引用/图片场景可读性明显差 |
| Inbox 绑定 | 持久 binding、SSE、claim/view-ack、自动 ack、backoff、ops log | 内存 binding、轮询、手动 ack、minPriority | 重启丢 binding；失败重试与可观测性弱 |
| 媒体 | media store/inspect/note/forward | 只有出站图片 base64 | 入站图片/文件/合并转发无法持久化或检查 |
| B 站 | video_read/frames/comments/登录 | 浏览器打开/截图/逐帧 | 没有视频元数据、评论、登录态工具 |

## 3. 已迁移/已优于老 iibot 的部分

不是全盘落后，以下 BMA3 已经具备：

- 单一 Java 服务 + 单一 MCP 端点，工具按模块装配；
- 单正向 OneBot WS + 账号热插拔；
- QQ 发送三态、消息板、Inbox 状态机；
- QQ 图片发送（base64，适配云端 LLBot 看不到本地路径）；
- Selenium 浏览器工具 + 视频逐帧；
- DSH 插件显式 `bma3_inbox_bind/unbind/status/ack`，不再写死 session；
- 有界 wake preview + `minPriority` + 普通群消息不唤醒；
- 可下载 jar、MIT license、README 部署指南。

## 4. 建议优先级

### P0 — 先补“能自己找、能自己调”的工具

1. `qq_thread_list` / `qq_recent_messages` / `qq_conversation_history`；
2. `qq_message_get` / `qq_message_thread`（引用链、合并转发）；
3. `qq_user_note_get/list/upsert`；
4. `qq_source_weight_get/list/set`，接到现有 `weighting` 与 Inbox priority；
5. `qq_account_login_info` / `qq_account_status`；
6. `qq_reply` + 2000 字自动分片 + 发送节流/状态查询。

### P1 — 社交与媒体

7. 好友/群请求、入群、群搜索；
8. 入站媒体落盘 + `media_inspect`/`media_note`；
9. B 站视频元数据/评论/登录态工具。

### P2 — 唤醒层体验

10. DSH binding 持久化（重启不丢）、自动 ack、wake 失败 backoff、ops log；
11. 来源/channel 过滤（私聊、群、@、命令）；
12. 服务端 digest 的完整渲染（备注/标签/引用/媒体/@）。

## 5. 待用户拍板

- P0 是否先做 1–6（会话导航、备注、来源权重、账号诊断、reply/分片）；
- 群消息是否永久排除 Inbox，还是按来源/绑定过滤；
- 是否把 AgentLib 0.2.5 的 native Bearer/TLS 面替换现有回环代理；
- 是否把 `qq_*` 旧工具名保持兼容，还是直接用 BMA3 新命名。

## 6. 证据索引

- 老工具注册：`iiBotMosire/iibot-bot/src/main/java/io/mosire/iibot/bot/mcp/BotMcpTools.java`
- 老摘要渲染：`iiBotMosire/iibot-bot/src/main/java/io/mosire/iibot/bot/summary/MessageSummaryBuilder.java`
- 老来源权重：`iiBotMosire/iibot-bot/src/main/java/io/mosire/iibot/bot/weight/`
- 老 Inbox 契约：`DevMosire-iiBot/docs/superpowers/specs/2026-11-06-inbox-push-contract.md`
- 老易用性台账：`DevMosire-iiBot/docs/superpowers/reports/2026-11-09-架构审查与易用性调查.md`、`bot/FRICTION.md`
- BMA3 工具：`bma3-qqconnect/src/main/java/io/mosire/bma3/qqconnect/tools/`
- BMA3 摘要/ingest：`bma3-app/src/main/java/io/mosire/bma3/app/Bma3App.java`
- BMA3 DSH 插件：`dsh-plugin/lib/inbox.js`
