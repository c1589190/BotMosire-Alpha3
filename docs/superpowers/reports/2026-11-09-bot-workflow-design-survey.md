# Bot 工作流设计前置调查：LLBot / Milky / DSH 注入面 / iibot-Inbox

> **类型**：调查（时间点快照，**只作追溯，不作当前事实**）· **日期**：2026-11-09
> **方法**：只读检查本机 LLBot 配置、DSH profile 与插件、iibot/Inbox 设计文档；核对 LLBot 与 Milky 官方文档。**未改代码、未启动/重启任何服务、未连接真实 QQ/B 站。**
> **触发**：用户提出以 DSH 为唯一宿主，设计「QQ/B 站/其他社交软件 → 统一 Inbox 面板 → Agent」的工作流，并询问是否继续用 Java。
> **当前事实入口**：[`../../agents/STATUS.md`](../../agents/STATUS.md)

---

## 0. 结论速览

1. **LLBot 同时支持 OneBot 11、Milky、Satori。** OneBot 11 不必同时开 WS + HTTP：正向/反向 WS 都能在同一连接上收事件、调 API；当前本机 `ws:3001 + http:3000` 双开只是历史配置。Milky 则是一个 HTTP 端口上 `POST /api` + `GET /event`（SSE/WS），概念更干净，但 LLBot 官方明确说 Milky 功能还不如 OneBot 11 多。
2. **DSH 原生支持直接注入。** `agent.followup()`、`agent/status`、`agent/created`、`agent/disposed`、`tools.register()` 都在；一个薄插件就能完成 binding、idle 判断、去重和注入。现有 `InboxMosire-DSH` 的 `adapter.js` 约 1,569 行、`manager.js` 约 958 行，大部分复杂度来自「通用多宿主适配器」，不是 DSH 缺能力。
3. **B 站能力要拆成两半。** DSH 生态已有多个「看 B 站视频」插件/工具（`dsh-bili-watch`、`bilibili-mcp`、`dsh-bilibili` 等），可复用；但**B 站私信**没有现成 DSH 插件，iibot 的 `BilibiliAdapter` 才有轮询/发送，且登录续期链有已知缺口。视频工具与账号会话不要混成一个东西。
4. **iibot 的 Inbox 服务端语义成熟，但形态偏重。** durable item、revision、delivery/ack、bounded summary、content 不归 Inbox 所有，这些值得继承；但 claim/release/complete 旧路径、独立 daemon、三套宿主适配器，对「DSH 单宿主」是多余复杂度。
5. **Java 的性能不是决定因素。** 这个系统是 I/O 密集型（WS/HTTP/SQLite/媒体），消息速率远低于需要 JVM 才能撑住的量级。真正要选的是「和 DSH 同栈、单二进制、还是复用旧 Java 代码」；三者对应 TypeScript / Go / Kotlin-JVM。

---

## 1. LLBot 与 Milky

### 1.1 本机事实

- `LLBot-Docker` 使用 `linyuchen/llbot:8.3.2` + `pmhq`，WebUI `13080`。
- 当前 LLBot 配置同时启用了：
  - OneBot 11 WebSocket `3001`（正向 WS，事件 + API）；
  - OneBot 11 HTTP `3000`（HTTP API）；
  - Milky 关闭；Satori 关闭。
- 这说明「WS + HTTP 双开」是配置选择，不是 OneBot 11 的硬要求。

### 1.2 协议对比

| 维度 | OneBot 11（LLBot） | Milky |
|---|---|---|
| 传输 | 正向 WS、反向 WS、HTTP、HTTP POST | 一个 HTTP 端口：`POST /api` + `GET /event`（SSE/WS/WebHook） |
| API 调用 | WS 或 HTTP | 只能 HTTP POST |
| 事件 | WS / HTTP POST / SSE | WS / SSE / WebHook |
| 单连接 | 正向/反向 WS 可在同一 socket 收发事件与 API | 事件走 WS/SSE，API 另走 HTTP |
| 功能覆盖 | 最全，LLBot 兼容绝大多数 GoCQ 接口 | 官方称仍在发展，功能没有 OneBot 11 多 |
| 规范强度 | 历史包袱多，字段模棱两可 | 强类型、明确兼容策略、季度累积更新 |
| 适合 | 第一版生产接入 | 第二适配器 / 新项目试点 |

官方资料：[LLBot 协议开发对接](https://www.luckylillia.com/guide/develop)、[Milky 通信](https://milky.ntqqrev.org/guide/communication)、[LLBot 仓库](https://github.com/LLOneBot/LuckyLilliaBot)。

### 1.3 判断

- **第一版用 OneBot 11，单传输。** 优先「正向 WS 或反向 WS 二选一」，不要同时挂 WS 和 HTTP。正向 WS 由 Bot 主动连 LLBot，适合同机/回环；反向 WS 由 LLBot 连 Bot，适合容器网络或 Bot 侧统一入口。
- **Milky 作为第二适配器，不抢第一版。** 它的通信模型更干净，但功能覆盖和生态还在发展；等第一版把「适配器 SPI + 统一配置 + 会话模型」跑通，再加 Milky 成本很低。
- **统一配置不是把 LLBot 的四种模式都暴露给用户。** 对用户只暴露「平台 + 协议 + 一个 endpoint + tokenEnv」；适配器内部决定用哪种 transport。配置校验要能 `get_login_info`、拉群/好友列表、发一条自检消息。

---

## 2. DSH 的注入面

DSH `0.2.2-rc.1` 的能力（只在邻居仓本地核对：`/home/cna/DevMosire-iiBot/InboxMosire-DSH/docs/HOST-CAPABILITIES.md`）：

- `agent.followup(message)`：排队一个普通 follow-up 回合并唤醒 driver；
- `agent/status`：running/idle；
- `agent/created` / `agent/disposed` / `workspace/session-stop`：启动、恢复、归档、删除；
- `tools.register()`：注册模型可见工具；
- `agent.id`：权威 session id；
- **没有**内建 follow-up 幂等键，需要适配器自己做 revision/watermark。

结论：如果 DSH 是唯一宿主，**不需要一个通用 Inbox daemon + 三套宿主适配器**。一个 DSH 插件可以：

```text
LLBot/B站事件 → 核心 store → 待注入队列
                                  │
                                  ▼
                    DSH 插件：取队列 → 检查 session idle
                                  → agent.followup(摘要)
                                  → 等 Agent 调工具 read/ack
```

现有 `InboxMosire-DSH` 的复杂度主要在「通用 SSE/cursor/revision/occupancy/多 binding」；这些能力对多宿主有意义，对 DSH 单宿主可以大幅收缩，但不能丢掉持久化、去重、ack 和 idle 判断。

---

## 3. B 站能力

### 3.1 视频（可复用）

邻居 2026-10-29 的调查（`/home/cna/DevMosire-iiBot/iiBotMosire/docs/superpowers/reports/2026-10-29-dsh-bilibili-video-projects-research.md`）已对比：

- `dsh-bili-watch`：DSH 插件 + CLI，元数据/评论/弹幕/字幕/ASR/抽帧，CLI 边界清楚；
- `bilibili-mcp`：通用 stdio MCP，只读子集可复用；
- `dsh-bili-scroll` / `dsh-bili-whale`：自主刷视频 + 互动，耦合 DSH 生命周期，不适合直接搬。

判断：**视频读取作为独立工具/MCP 复用，不塞进 Inbox 核心**。Agent 被唤醒后按需调用；Inbox 只给摘要，不塞视频全文。

### 3.2 私信（需自建）

- iibot 的 `BilibiliAdapter` 负责私信/评论通知轮询与发送；`iibot-video-connect` 负责视频读取/抽帧/评论。
- 2026-11-09 的 DSH 迁移调查（`/home/cna/DevMosire-iiBot/docs/superpowers/reports/2026-11-09-DSH迁移调查-Hermes记忆导出-B站登录-上下文层.md`）指出：B 站扫码登录已改为 crossDomain 票据 + `refresh_token` 续期，现有代码丢弃 `refresh_token`，续期链未实现。
- DSH 生态里没有找到现成的 B 站私信插件；视频插件不等于私信适配器。

判断：**B 站私信是独立适配器**，需要 cookie 存储、登录续期、轮询、限流、发送三态；不要和视频工具绑在一起。若第一版只做 QQ，B 站私信可以后置；视频工具可先以 MCP 复用。

---

## 4. iibot / Inbox 评估

### 4.1 值得继承的

- 统一消息板：`chat_message` / `chat_thread` / `chat_message_segment`，消息板是业务事实源；
- canonical `source`：`qq_group/{account}/{group}`、`qq_private/...`、`bili_dm/...` 等；
- 发送三态：`SENT` / `FAILED_BEFORE_SEND` / `UNKNOWN`，`UNKNOWN` 不自动重发；
- Inbox 语义：durable item、revision、delivery/ack、bounded summary、content 不归 Inbox；
- Agent 不维护内部标记；服务端维护 read/delivered 游标；
- 安全红线：回环、密钥零泄露、正文不落日志、账号不串号。

### 4.2 应该丢掉的

- 三套宿主适配器（DSH / OpenCode / Hermes）各写一遍 wake；
- claim/release/complete 旧生命周期与 view-ack 并存；
- 独立 Inbox daemon + 内嵌 server + 多 token 的部署复杂度；
- Playwright 五平台驱动进主产物；
- 4 模块构建强绑定「Java + Maven」这一技术选择。

### 4.3 关键判断

**Inbox 不是整个面板，只是面板的「唤醒队列」投影。** 面板还需要：

- 统一的 Account / Conversation / Message store；
- 平台适配器（ingress/egress）；
- Agent 工具面（read/send/ack）；
- 人看的 Web UI（可选）；
- 管理面（账号、健康、配置、日志）。

如果只做 Inbox 而不做统一 store，各平台的消息历史、已读、发送状态仍然是碎的。

---

## 5. 架构选项

### 方案 A：DSH 同进程插件

```text
DSH 进程内：适配器 + SQLite + 工具 + 注入 + Web 面板
```

- 优点：零 IPC、零 MCP connector、直接 `agent.followup`、一个语言（TS）、最少代码。
- 缺点：Bot 生命周期绑死 DSH；DSH 重启时可能漏消息；不能给非 DSH 客户端用；适配器故障可能影响宿主；Web UI 受宿主形态限制。

### 方案 B：独立常驻服务 + 薄 DSH 插件（推荐）

```text
独立 Bot 服务：适配器 + store + Inbox 队列 + MCP + Web UI
        ▲  HTTP/SSE（或本地 socket）
        │
DSH 薄插件：取 wake → agent.followup → 等 Agent 调 MCP 工具
```

- 优点：DSH 重启不影响收消息；可给其他 Agent/MCP 客户端用；独立 watchdog/健康检查；语言自由；边界清晰。
- 缺点：多一个进程；需要一条很薄的传输层和鉴权；DSH 插件仍要写（但可以很小）。

### 方案 C：保留 Java 核心 + TS 薄插件

- 优点：复用 iibot 33k 行业务代码、B 站/QQ 适配器、SQLite schema。
- 缺点：JVM + fat jar；DSH 插件仍是 TS；Web UI 另做；迭代慢；旧架构的耦合会一起继承。

**我的倾向：方案 B。** 它保留了 iibot 最好的部分（独立服务、消息板、MCP），同时把三套宿主适配器砍成一个薄 DSH 插件。方案 A 更简单，但「DSH 重启时继续收消息」这个需求一旦成立，A 就不合适。方案 C 只在「复用旧 Java 代码」优先级最高时选。

---

## 6. 语言选项

| 选项 | 优势 | 代价 | 适合 |
|---|---|---|---|
| **TypeScript / Node 22** | 与 DSH 插件同栈；Web UI 同语言；MCP/JSON/SSE 生态好；迭代快 | 需要 Node 运行时；原生 SQLite 模块；CPU 密集任务靠外部工具 | 方案 B 的首选；DSH 单宿主 |
| **Go** | 单静态二进制；并发/部署/ watchdog 简单；可嵌 Web 静态资源 | DSH 插件仍要 TS；QQ/Milky 生态不如 Node；B 站 Java 代码要移植 | 想要「一个二进制扔服务器」 |
| **Kotlin / Java 21** | 复用 iibot 代码；虚拟线程；JVM 成熟 | DSH 插件仍要 TS；fat jar；Web UI 分离；旧耦合 | 复用旧代码优先 |
| **Rust** | 性能/单二进制/安全 | 开发成本高；QQ/MCP 生态少 | 不推荐当前阶段 |

关键事实：这是 I/O 密集型系统，Java 的「高性能」不构成选型理由。真正决定成本的是：

1. DSH 插件必须是 JS/TS（Cordis），所以无论核心用什么语言，都有一条 TS 边界；
2. Web UI 用 TS 最省事；
3. 如果核心也用 TS，只有一种语言、一套类型、一个构建；
4. 如果核心用 Go，部署最干净，但两种语言；
5. 如果核心用 Java，复用最多，但三种构建面（JVM + TS 插件 + Web）。

**我的倾向：TypeScript 做独立服务 + DSH 薄插件 + Web UI。** 如果「单二进制部署」是硬要求，改 Go 核心，TS 插件不变。Java 只有在「必须复用现有 iibot 代码」时才值得。

---

## 7. 需要用户拍板的岔路

| # | 问题 | 影响 |
|---|---|---|
| Q-1 | 面板是独立常驻服务，还是 DSH 同进程插件？ | 决定语言、Inbox 传输、watchdog、部署形态 |
| Q-2 | 第一版平台范围：只 QQ，还是 QQ + B 站私信？ | 决定适配器数量与工期 |
| Q-3 | B 站视频工具是复用 DSH 插件/MCP，还是并进 Bot 服务？ | 决定是否多一个 connector |
| Q-4 | 多账号/多 session：一个 Bot 服务绑定一个 DSH session，还是多 session 路由？ | 决定 binding/consumer 模型 |
| Q-5 | 注入策略：全量唤醒、私聊必唤醒、重要/提及才唤醒，还是可配置？ | 决定 Inbox 队列与限流 |
| Q-6 | 是否需要人看的 Web 面板？ | 决定前端工作量与鉴权 |
| Q-7 | 旧数据（bot.db / inbox.db / 媒体）是否迁移？ | 决定 schema 与迁移工具 |

---

## 8. 证据索引

| 事实 | 证据 |
|---|---|
| LLBot 支持 OneBot11/Milky/Satori、四种 OneBot 传输 | [LLBot 协议开发对接](https://www.luckylillia.com/guide/develop) |
| Milky 单 HTTP 端口、API/事件分离 | [Milky 通信](https://milky.ntqqrev.org/guide/communication) |
| Milky 兼容策略与功能发展阶段 | [Milky 兼容性](https://milky.ntqqrev.org/guide/compatibility) |
| 本机 LLBot 8.3.2、WS 3001 + HTTP 3000 双开 | `/home/cna/LLBot-Docker/docker-compose.yml`、`llbot_config/config_*.json`（token 已脱敏） |
| DSH `agent.followup` / 生命周期 / tools.register | `/home/cna/DevMosire-iiBot/InboxMosire-DSH/docs/HOST-CAPABILITIES.md` |
| 现有 Inbox-DSH 适配器规模 | `InboxMosire-DSH/lib/adapter.js`、`lib/manager.js`；邻居架构审查报告 |
| iibot 4 模块、消息板、source、三态 | `iiBotMosire/AGENTS.md`、`docs/superpowers/specs/2026-10-28-unified-qq-agent-client-architecture.md` |
| Inbox delivery/ack 语义 | `InboxMosire/docs/inbox-push-contract.md` |
| B 站视频 DSH 生态 | `iiBotMosire/docs/superpowers/reports/2026-10-29-dsh-bilibili-video-projects-research.md` |
| B 站登录续期缺口 | `docs/superpowers/reports/2026-11-09-DSH迁移调查-Hermes记忆导出-B站登录-上下文层.md` |
