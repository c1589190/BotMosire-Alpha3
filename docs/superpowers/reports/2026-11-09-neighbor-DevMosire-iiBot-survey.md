# 邻居项目基本情况调查：DevMosire-iiBot

> **类型**：调查（时间点快照，**只作追溯，不作当前事实**）· **日期**：2026-11-09
> **方法**：只读检查 `/home/cna/DevMosire-iiBot` 与其 `iiBotMosire/` 的 `AGENTS.md`、`README.md`、`docs/**`、最新架构审查报告；用 `git rev-parse` 核对分支与 HEAD，用 `find`/`wc` 核对规模。**未构建、未运行、未连接 QQ/B 站/宿主、未修改任何文件。**
> **触发**：用户要求「从隔壁的DevMosire-iiBot了解隔壁项目基本情况」，当前目录将作为其继承与重构。
> **当前事实入口**：[`../../agents/STATUS.md`](../../agents/STATUS.md)

---

## 0. 一页纸结论

1. **邻居不是一个仓库，是一个协调工作区。** 根目录 `/home/cna/DevMosire-iiBot` 本身无 Git；内部有 5 个独立 Git 仓，另有 `bot/`、`bot2/` 运行目录和不带 Git 的 `InboxMosire-OpenCode/`。
2. **产品主体是 `iiBotMosire`。** Java 21 + Maven 4 模块，做 QQ（OneBot 11 / NapCat / LLBot）与 B 站会话、统一消息板、Bot MCP 工具面、Inbox 唤醒；不运行 LLM，也不做文游语义。
3. **架构是「一个 Bot 核心 + 一个通知板 + 三套宿主适配器」。** `InboxMosire` 做通知/唤醒账本，`InboxMosire-DSH` / `InboxMosire-OpenCode` / `InboxMosire-Hermes` 分别把唤醒翻译成三个宿主的回合；同一套 wake 语义被实现了三遍。
4. **邻居自己的最新审查认为瓶颈在集成面，不在业务代码。** 业务主体约 33,897 行，宿主适配器合计约 22,559 行；`iibot-app` jar 约 227 MB，其中 99% 是 Playwright 五平台驱动，而运行配置 `video.browser.enabled=false`。报告建议 P0 移除 Playwright、P1 砍到一个宿主、P2 用 `AgentLib` 的 `AgentLoop` 做进程内原型——**这些都是待用户拍板的建议，不是既定裁定**。
5. **文档系统成熟，但有滞后。** 两层轴（设计 / 维护）、薄根路由、唯一 `STATUS.md`、D-xxx / M-xxx 裁定、Skill 状态机都值得继承；但根 `AGENTS.md` 的 HEAD、仓库数量与实测不一致，引用前必须复核。
6. **对 Alpha3 的含义：** 可继承的是领域契约、消息板语义、MCP 工具面、安全红线与文档规范；不应直接继承的是「4 模块 + 三宿主 + Inbox IPC + Playwright fat jar」这套运行形态。所有取舍见 [`DESIGN_RULINGS.md`](../DESIGN_RULINGS.md) 的 Q-A..Q-H，均未决定。

---

## 1. 工作区拓扑

2026-11-09 实测：

| 目录 | 角色 | 技术栈 | 分支 / HEAD | Git |
|---|---|---|---|---|
| `iiBotMosire/` | 产品组合根：QQ/B 站接入、消息板、Bot MCP | Java 21、Maven 4 模块 | `refactor/bot-first-main-agent` @ `f3b1602` | 是 |
| `InboxMosire/` | Agent 无关的本地通知板 daemon / 库 | Java 21、Maven 6 模块 | `main` @ `e3f0a89` | 是 |
| `InboxMosire-DSH/` | DSH 宿主的 Inbox 唤醒适配器 | Node.js ESM、`node:test` | `main` @ `3bf7630` | 是 |
| `InboxMosire-Hermes/` | Hermes 宿主的 Inbox 插件 | Python | `main` @ `efa51e6` | 是 |
| `InboxMosire-OpenCode/` | OpenCode 宿主的 Inbox 唤醒适配器 | Node.js ESM | — | **否** |
| `iiBotMosire-DSH/` | iiBot 的 DSH 人设/guard/wake-profile 扩展 | Node.js ESM | `main` @ `42611c5` | 是 |
| `bot/`、`bot2/` | 运行实例（jar、配置、数据库、媒体、日志） | — | — | 否 |
| `docs/` | 协调工作区的跨仓文档 | — | — | 否 |

补充事实：

- `bot.tar.gz` 约 280 MB（运行目录归档）；`bot/` + `bot2/` 合计约 1 GB；`iiBotMosire/` 全仓约 1.6 GB。
- 根 `AGENTS.md` 说「四个源码目录」、`docs/AGENTS.md` 说「六个源码仓」，实测是 **5 个 Git 仓 + 1 个无 Git 的 OpenCode 包**；两处都不完全准确，属于文档滞后。
- 根 `AGENTS.md` 记录的 `InboxMosire-DSH 681f976`、`iiBotMosire 9db9d29` 与实测 HEAD 不一致；本报告以 `git rev-parse` 为准。

---

## 2. 产品主体：iiBotMosire

### 2.1 定位

`iiBotMosire` 是 **Agent 无关的统一 QQ / B 站会话客户端**，不是文游服务。它负责：

- 一个或多个 OneBot 11 / NapCat / LLBot 账号接入；
- 一个或多个 B 站账号的凭据校验、私信/评论通知轮询与显式发送；
- QQ / B 站统一消息板、thread、历史、用户备注与媒体；
- 通用 B 站视频阅读/抽帧/评论（任意来源链接）；
- 统一 MCP 工具面，供外部 Agent 读取、回复和管理会话；
- 通过 Inbox 唤醒外部 Agent。

**非目标**：玩家、秘书、回合、行动、裁定、世界、Ticket 等文游语义；在 iibot 内运行 LLM/主 Agent/推演循环；自动刷视频、自动评论、自动私信、自动点赞/关注；任意 OneBot action、原始 JSON、任意 SQL/shell。

### 2.2 四个 Maven 模块

| 模块 | 职责 | 依赖 |
|---|---|---|
| `iibot-connect` | OneBot 11 / NapCat / LLBot 接入、重连、去重、限速、发送三态；只保留外部消息连接与 core SPI | Jackson、SLF4J |
| `iibot-video-connect` | B 站视频元数据/播放/评论/登录/抽帧/显式写入，以及 B 站会话 Adapter；不依赖 AgentLib/MCP | connect + Jackson |
| `iibot-bot` | 统一消息板、thread、用户备注、摘要、Inbox digest、conversation/QQ MCP 工具 | connect + AgentLib + SQLite |
| `iibot-app` | 可运行组合根：配置、Adapter、embedded Inbox、唯一 MCP 工具目录、B 站视频工具、日志 | bot + video-connect + Inbox artifacts |

```text
iibot-connect
      │
      ├──────────────┐
      ▼              ▼
iibot-bot     iibot-video-connect
      │              │
      └──────┬───────┘
             ▼
         iibot-app
```

### 2.3 运行链路

```text
QQ / NapCat / LLBot ──OneBot 11──┐
                                 ▼
                           iiBotMosire
                             ├── 写入消息板（SQLite，业务事实源）
                             ├── 生成 bounded digest → embedded InboxMosire
                             └── 暴露 Bot MCP（qq_* / conversation_* / video_*）

embedded InboxMosire ──HTTP / SSE / MCP──> 宿主适配器 ──> 外部宿主 Agent
                                                    │
                                                    └── 调 Bot MCP 读/回
```

- 设计态一次完整运行需要 **5 个活动部件**：NapCat → iibot jar → 宿主（Hermes/DSH/OpenCode）→ 宿主插件 → LLM 供应商。
- 默认端口：Bot MCP `127.0.0.1:8765/mcp/bot`；Inbox HTTP `46500`；Inbox MCP `46501`；NapCat 常见 `3000/3001`。
- 默认只监听回环；token 从环境变量或 `data/inbox/*.token` 读取。

### 2.4 能力与关键契约

- QQ 私聊/群聊入库、thread、历史、用户备注、媒体读取、好友申请、群邀请/群目录。
- B 站账号凭据校验、WBI、私信轮询、评论回复通知轮询、私信/评论发送；写入默认关闭。
- 通用 `video_read` / `video_frames` / `video_comments`；`bili_*` 兼容别名。
- 显式单次 `video_comment_reply` / `video_like` / `video_coin` / `video_favorite` / `video_follow`；默认关闭、单次、有配额/幂等，不做自动循环。
- `qq_media_inspect`（首帧/网格降采样）、`qq_media_note`（按 assetId 持久化图片备注）、媒体 source 去重。
- DSH wake 默认 `wakeContent=bounded`：摘要后读取展示 item 的 detail `content`，放入独立不可信块；每项 4000 / 整轮 12000 默认预算；摘要 512 边界、5 秒合并窗口。
- 消息来源统一为 canonical `source`：`qq_group/{account}/{group}`、`qq_private/{account}/{peer}`、`qq_system/{account}/{event}`、`app_system/{event}`、`bili_dm/{account}/{peerMid}`、`bili_comment/{account}/{bvid}/{rootRpid}`、`bili_video/{account}/{bvid}`。
- 出站三态：`SENT` / `FAILED_BEFORE_SEND` / `UNKNOWN`；`UNKNOWN` 不自动重发。
- Agent 不维护 revision/snapshot/itemId/内部 seq；消息板服务端维护 read/delivered 游标。

### 2.5 构建与验证（邻居记录，本次未复跑）

- JDK 21；仓库内 `./mvnw`；门禁 `tools/gate.sh docs|selftest|pre|focused|full`。
- 邻居 `AGENTS.md`（2026-11-01）记录：`tools/gate.sh full` **343 tests PASS**（connect 73 / video 26 / bot 128 / app 116），docs PASS（4 modules / 52 files / 173 links）。
- 同一文档把真实 DSH reload/大图/5.7MB GIF 验收、真实 QQ/B 站/DSH/OpenCode 外部闭环标为 `NOT_RUN`。
- **本次没有运行上述任何命令**，因此这些数字只能写成「邻居记录」，不是本报告验证结果。

---

## 3. 通知板与宿主适配层

### 3.1 InboxMosire

- Java 21、Maven 6 模块，角色是 **Agent 无关的本地通知板 daemon / 库**。
- 负责通知、来源/trigger、未读预览与计数、游标、revision、occupancy；完整正文仍从 `iiBotMosire` 消息板恢复。
- 邻居自己的审查指出它的双重身份是混乱根源：
  - **账本**：消息去重、ack 幂等、delivery 台账、水位——必要且做得不错；
  - **IPC**：把「有新消息」通知给另一个进程——只在 Agent 不在同一进程时才需要。

### 3.2 三套宿主适配器

| 适配器 | 语言 | 规模（邻居审查） | 备注 |
|---|---|---:|---|
| `InboxMosire-DSH` | Node ESM | 6,668 行 | 与 OpenCode 版近乎逐文件克隆 |
| `InboxMosire-OpenCode` | Node ESM | 5,944 行 | 无 Git；`session.synthetic({resume:true})` 是一等 API |
| `InboxMosire-Hermes` | Python | 9,193 行 | 三者中唯一要改宿主源码的；邻居实测 `~/.hermes/hermes-agent` 有 10 个已修改文件 |
| `iiBotMosire-DSH` | Node ESM | 754 行 | 人设/guard/wake-profile 扩展 |

三套适配器合计约 **22,559 行**，而业务主体约 **33,897 行**；它们都在解同一组问题：cursor/revision 水位、SSE 重连 backoff、wake watermark、去重、ops-log、binding TTL。

### 3.3 邻居的最新架构审查（2026-11-09）

报告：`/home/cna/DevMosire-iiBot/docs/superpowers/reports/2026-11-09-架构审查与易用性调查.md`。其结论（**邻居的建议，非本项目裁定**）：

1. **字节臃肿**：`iibot-app-Alpha20261106.jar` 227 MB，99% 是 Playwright 五平台驱动；自研 `io/mosire` 仅 3.6 MB；运行时 `video.browser.enabled=false`。
2. **集成面臃肿**：22,559 行适配器 ≈ 业务主体的 2/3；DSH 与 OpenCode 两仓近乎逐文件克隆；Hermes 是唯一要维护宿主分叉的。
3. **会话模型错配**：编码型宿主是「用户发起回合」，QQ Bot 是「事件发起回合、永远在线」；三份 wake 水位/幂等/backoff 都在补这个错配。
4. **另一条路**：`agentlib-mosire 0.2.4` 自带 `AgentLoop`（337 行）、上下文压缩、LLM client、SQLite store、permission/approval；iiBot 已用其工具 SPI，但 `AgentLoop` 引用为 0。邻居建议做进程内原型，让宿主退回「控制台」。
5. **建议路线**：P0 把 Playwright 踢出 fat jar（半天）；P1 砍到一个宿主并冻结另两个（一周）；P2 用 `AgentLoop` 做进程内原型。**均待用户拍板。**

另一份 2026-11-09 报告还指出：B 站扫码登录成功后不再 `Set-Cookie` 而走 crossDomain 票据，代码丢弃了 `data.refresh_token`，续期链（cookie/info → correspond/1 → cookie/refresh → confirm/refresh）从未实现。该结论本次未复核。

---

## 4. 文档系统（值得继承的部分）

邻居的文档体系是其最成熟资产，结构如下：

| 层 | 位置 | 作用 |
|---|---|---|
| 薄根路由 | `AGENTS.md` | 项目边界、冲突顺序、常驻红线、模块/文档索引 |
| 文档系统 | `docs/AGENTS.md` | 设计/维护两条轴、分层、取代、检查 |
| 维护手册 | `docs/agents/{STATUS,WORKFLOW,VERIFICATION,ENVIRONMENT,MAINTENANCE_RULINGS,SKILLS}.md` | 当前状态、流程、证据、环境、裁定、Skill |
| 设计 | `docs/superpowers/{specs,plans,reports,reviews,status}/` + `DESIGN_RULINGS.md` | 专题设计、计划、调查、评审、历史快照 |
| 工程 Skill | `.agents/skills/<name>/SKILL.md` | 可复用判断流程，状态机 proposed→…→retired |
| 上游契约 | `docs/upstream/` | OneBot/MCP/宿主等外部契约摘要 |

关键原则：

- **设计 ≠ 维护**：一条规则只有一个主正本，另一侧只放链接。
- **报告不作当前事实**：`reports/**` 只是某时点证据，引用前复核代码。
- **用户当次裁定 > 旧不变量**；被取代的文档加状态头，不删除。
- **没跑写 `NOT_RUN`**；Mock/fake 不能冒充真实联调。
- **Skill 不批量造**：先候选，再 pilot，再 active。

同时有两个可继承的教训：

1. 文档量会膨胀。邻居 `iiBotMosire` 的文档约 104 篇 / 18,700 行，接近业务主体 33,897 行的一半；报告被当事实读、STATUS 多口径是常见问题。
2. 文档会滞后。本次实测 HEAD 与根 `AGENTS.md` 记录不一致，说明「薄根路由 + 唯一 STATUS」必须配合机械检查或定期复核，否则仍会漂移。

---

## 5. 当前基线与验证状态

| 项 | 邻居记录 | 本次实测 / 复核 |
|---|---|---|
| `iiBotMosire` 分支 | `refactor/bot-first-main-agent` | 同；HEAD `f3b1602`（文档写 `9db9d29`） |
| `iiBotMosire` 测试 | 343 tests PASS（2026-11-01） | **NOT_RUN** |
| `InboxMosire` | `main` @ `a040f25` | `main` @ `e3f0a89` |
| `InboxMosire-DSH` | `main` @ `681f976` | `main` @ `3bf7630` |
| `InboxMosire-Hermes` | `main` @ `9495ffd` / tag `v0.3.0` | `main` @ `efa51e6` |
| `iiBotMosire-DSH` | `main` @ `dda6843` | `main` @ `42611c5` |
| 真实 QQ/B 站/DSH/OpenCode 闭环 | `NOT_RUN` | 未复核；仍按 `NOT_RUN` 处理 |

结论：邻居文档记录的是**某个历史时点**；分支/HEAD 已前进，测试数字未复跑。任何继承设计引用这些数字时，必须注明「邻居记录，未复跑」。

---

## 6. 对 Alpha3 的继承含义（只列问题，不做决定）

### 6.1 可直接作为输入继承的

- 领域契约：QQ/B 站消息来源地址、thread/消息板语义、发送三态、`UNKNOWN` 不重发。
- 安全红线：回环监听、密钥零泄露、正文不落日志、账号不串号。
- MCP 工具面的语义设计（`qq_*` / `conversation_*` / `video_*` / `inbox_*`）与「Agent 不维护内部标记」原则。
- 文档系统：设计/维护分账、唯一 STATUS、D/M 裁定、报告只作追溯、Skill 状态机。
- 踩坑记录：三宿主重复、Hermes 分叉、Playwright fat jar、Inbox 账本/IPC 双重身份、文档漂移。

### 6.2 不应默认继承的

- 「Java 21 + Maven 4 模块」作为既定技术栈；
- 「外部宿主 + Inbox IPC + 三套适配器」作为既定运行形态；
- Playwright 五平台驱动打进主产物；
- 旧配置键、旧工具名、旧 `source` 地址的兼容义务；
- 旧运行目录 `bot/`、`bot2/` 的数据与凭据。

### 6.3 需要用户拍板的岔路

见 [`DESIGN_RULINGS.md`](../DESIGN_RULINGS.md) 的 Q-A..Q-H：重构范围、仓库形态、运行时形态、Inbox 去留、B 站/Playwright、兼容性、数据迁移、发布形态。**当前一个都未定。**

---

## 7. 本次做了什么 / 没做什么

**做了（只读）**：

- 读取邻居根 `AGENTS.md`、`docs/AGENTS.md`、`docs/agents/**`、`docs/superpowers/{AGENTS,DESIGN_RULINGS}.md`、`iiBotMosire/{AGENTS,README}.md`、最新架构审查报告。
- 用 `git -C <repo> branch --show-current` / `rev-parse --short HEAD` 核对 5 个 Git 仓。
- 用 `find`/`wc` 核对源码与文档规模，用 `java -version` / `node --version` / `python3 --version` / `ffmpeg -version` 核对外部工具链。
- 用 `git ls-remote` 与 GitHub API 确认 `BotMosire-Alpha3` 初始化时为空仓。

**没做（`NOT_RUN`）**：

- 没有构建/测试邻居任何仓库；
- 没有启动/重启任何 Bot、Inbox、宿主或 NapCat；
- 没有连接真实 QQ / B 站 / DSH / OpenCode / Hermes；
- 没有修改、提交、清理继承来源的任何文件；
- 没有逐文件审查 330 个 Java 源文件；模块职责以邻居 `AGENTS.md` / `README.md` 与目录结构为准；
- 没有复核邻居架构审查报告中的 Playwright 体积、适配器行数、Hermes 修改文件数等度量；这些均标为「邻居报告」。

---

## 8. 证据索引

| 事实 | 证据 |
|---|---|
| 邻居根不是 Git 仓；内部 5 个 Git 仓 | `git -C /home/cna/DevMosire-iiBot/<repo> rev-parse`；根目录 `ls -la` |
| 5 个 HEAD / 分支 | 2026-11-09 实测，见 §5 表 |
| 产品定位、非目标、红线 | `/home/cna/DevMosire-iiBot/iiBotMosire/AGENTS.md` |
| 4 模块职责与依赖图 | 同上 §3；`iiBotMosire/README.md` |
| 运行端口、配置、MCP 面 | `iiBotMosire/README.md` §3/§6/§7 |
| 能力清单 | `iiBotMosire/README.md`「当前能力」 |
| 文档系统 | `DevMosire-iiBot/docs/AGENTS.md`、`docs/agents/*.md`、`docs/superpowers/*/AGENTS.md` |
| 架构审查数字 | `DevMosire-iiBot/docs/superpowers/reports/2026-11-09-架构审查与易用性调查.md` |
| 邻居测试记录 | `iiBotMosire/AGENTS.md`（2026-11-01）与 `docs/agents/STATUS.md`（时点混杂） |
| 远端空仓 | `git ls-remote https://github.com/c1589190/BotMosire-Alpha3.git` rc=0 无 refs；GitHub API `branches` 返回 `[]` |
