# dsh-bili-whale 复用调查：能否作为 bma3 的 B 站能力底座

> **类型**：调查（时间点快照，只作追溯，不作当前事实）· **日期**：2026-11-10
> **方法**：只读克隆 [`Alterssss/dsh-bili-whale`](https://github.com/Alterssss/dsh-bili-whale)
> 的 `v0.4.6`（commit `8b0009f`）到 `/tmp`；读代码、README、Issue、Release；
> 执行 `npm ci --ignore-scripts` + `npm test`。**未改本项目代码，未登录真实 B 站账号，
> 未发任何评论/私信，未启动 DSH。**
> **触发**：用户提出「也许我们不应该重新造车轮子，而是基于这个项目设计一个简单的封装/配合接口」。
> **当前事实入口**：[`../../agents/STATUS.md`](../../agents/STATUS.md)

---

## 0. 结论速览（事实 / 推断 / 待拍板）

1. **值得复用，但复用的是 `tools/` 这套 B 站 CLI，不是它的 DSH 插件半边。**
   该项目是「一个完整 DSH 插件 + 一套可独立运行的 Node CLI」；插件负责设置页、生物钟、
   人设、日记、会话注入，CLI 负责 B 站 API、登录、风控、额度、私信/评论/视频。
2. **推荐做「CLI sidecar 封装」，不做「插件对插件」集成。**
   bma3 继续做唯一 Agent、唯一 Inbox、唯一消息板、唯一优先级；bili-whale 的 CLI 只作为
   B 站能力提供方。不要 `import lib/index.js`，不要启动它的 `startScheduler()`。
3. **视频读取可以优先接，B 站私信/评论只能算「部分可用」。**
   `dm-await` 只读 `owner.mid` 一个人的私信，`comment-digest` 只读「回复我的 / @我的」；
   它不是通用 B 站收件箱，也不是任意用户私信适配器。
4. **硬限制先摆明**：单账号、`dm-await` 单消费者、部分命令只有人类可读文本、需要 Node、
   抽帧需要 ffmpeg、systemd 下必须显式给 `HOME`/`DSH_HOME`/`DSH_NODE`。
5. **不建议现在 fork/移植。** 先封装 CLI；只有在需要任意私信对象、多账号、实时 WS、
   或必须纯 Java 时，才考虑自写 B 站 adapter 或向上游提交扩展。

**证据等级**

| 等级 | 内容 |
|---|---|
| **已确认事实** | 项目同时包含 DSH 插件和独立 CLI；插件 `inject` 了 `sessionController` 且不注册 Agent 工具；`dm-await` 只读 `owner.mid`；CLI 无第三方依赖；`npm ci && npm test` 154 pass；没有 npm 包；34 open issues / 15 open PR |
| **推断（未做真实联调）** | 整插件接入会与 bma3 形成两个 Agent、两套监听；CLI sidecar 是改动最小且不破坏 bma3 单一事实源的路径；B 站私信只适合「主人通道」而非通用客服 |
| **待用户拍板** | B 站范围（只读视频 / 私信评论 Inbox）；是否接受只覆盖 owner；部署是否允许 Node/ffmpeg；写操作是否只草稿；固定 tag 还是 fork |

---

## 1. 它到底是什么

| 层 | 入口 | 性质 |
|---|---|---|
| DSH 服务端插件 | [`lib/index.js`](https://github.com/Alterssss/dsh-bili-whale/blob/v0.4.6/lib/index.js) | Cordis 插件；注册设置页 API、扫码登录、依赖体检、待审草稿；启动生物钟/监听 |
| DSH 客户端设置页 | [`lib/client.js`](https://github.com/Alterssss/dsh-bili-whale/blob/v0.4.6/lib/client.js) | 注入 DSH「设置 → 养鱼」的 React section |
| 自主调度 | [`lib/scheduler.js`](https://github.com/Alterssss/dsh-bili-whale/blob/v0.4.6/lib/scheduler.js) | 每 60 秒 tick；到点用 `sessionController.prompt()` 叫醒 `bili-whale` 会话 |
| 会话投递 | [`lib/session-delivery.js`](https://github.com/Alterssss/dsh-bili-whale/blob/v0.4.6/lib/session-delivery.js) | 维护 `whale-session.json`；拼人设/日记前缀；调 `controller.prompt()` |
| B 站 CLI | [`tools/bili.mjs`](https://github.com/Alterssss/dsh-bili-whale/blob/v0.4.6/tools/bili.mjs) | 30+ 个子命令；`spawnSync` 到 `tools/commands/**`、`tools/verify/**` |
| 状态 | `~/.bili-ai/`、`$DSH_HOME/` | 凭据、额度、判重、静默反馈、日记、计划、截图、日志 |

关键事实：

- 插件声明 `inject: ['sessionController', 'webServer', 'connection']`
  （[`lib/index.js:157`](https://github.com/Alterssss/dsh-bili-whale/blob/v0.4.6/lib/index.js#L157)），
  **不注册 Agent 工具，也不是 MCP server**。
- 它的「叫醒」是直接往自己的会话塞 prompt
  （[`lib/scheduler.js:272`](https://github.com/Alterssss/dsh-bili-whale/blob/v0.4.6/lib/scheduler.js#L272)、
  [`lib/session-delivery.js:65`](https://github.com/Alterssss/dsh-bili-whale/blob/v0.4.6/lib/session-delivery.js#L65)）。
- `tools/` 下所有 `.mjs` **只 import Node 内置模块和相对路径**，没有第三方依赖；
  直接依赖 `qrcode@1.5.4` 只被插件半边的 `lib/auth.js` 使用
  （[`package.json:49`](https://github.com/Alterssss/dsh-bili-whale/blob/v0.4.6/package.json#L49)）。
- 它没有发布到 npm（`npm view dsh-bili-whale` 返回 404），安装方式是 DSH bundle 或本地
  `link:`；可复用的是 Git tag/Release 里的 `tools/`，不是某个稳定库 API。

---

## 2. 哪些能复用，哪些不能

| 能力 | 复用？ | 说明 |
|---|---|---|
| B 站 cookie / 扫码登录 | ✅/⚠️ | CLI 直接读 `~/.bili-ai/credentials.json`；扫码登录只在插件路由里，CLI 没有 login 命令 |
| WBI 签名、视频元信息、字幕、评论、搜索 | ✅ | `search`、`video-content`、`subtitles`、`comments`、`debrief`、`feed` 等 |
| 请求保护 / 412 熔断 / 读写额度 | ✅ | `tools/lib/traffic.mjs`、`quota.mjs`；但状态是文件级、跨进程共享 |
| 私信历史 / 游标 / 撤回过滤 | ✅/⚠️ | `dm-await` + `readHistory` 实现完整；**只对 `owner.mid` 一个对话方** |
| 评论/@ 收件箱 | ✅/⚠️ | `comment-digest --json` 汇总「回复我的 / @我的」；不是某视频全部评论 |
| 发评论 / 发私信 / 草稿 | ✅/⚠️ | CLI 有 `--dry`/`--go`、额度闸、互关闸；`comment --go` 仍可绕过「陌生人先草稿」的上层策略 |
| 生物钟 / 人设 / 两本日记 / 分享评分 / 设置页 | ❌ | 这是「养鱼」产品逻辑，和 bma3 的单 Agent/Inbox 模型冲突 |
| DSH 会话注入 / `sessionController` | ❌ | bma3 已有自己的 Inbox/SSE 注入；再跑一套会变成两个 Agent |
| 多实例 / 多账号 | ❌ | 作者在 Issue #24 明确「这一轮不做」，且列出 13 处写死 |

---

## 3. 四种集成方式

| 方案 | 判断 | 理由 |
|---|---|---|
| **A. 把整个插件装进 bma3 的 DSH** | ❌ 不做 | 它会新建/占用 `bili-whale` 会话，自己叫醒、自己写日记、自己推送；bma3 的 Inbox 看不到它的内部状态。两个 Agent、两套人设、两套 B 站监听会抢同一个账号和 `dm-await` 游标 |
| **B. 只把 `tools/bili.mjs` 当 sidecar，bma3 做封装** | ✅ 推荐 | bma3 保持唯一事实源；复用最难的 B 站 API/风控/额度/私信历史；CLI 无第三方依赖，进程边界清楚 |
| **C. fork 插件，把 `session-delivery` 换成 bma3 Inbox** | ⚠️ 后置 | 需要改它的调度/会话/提示词，等于维护一个分叉；只有在需要改它的「养鱼」行为时才值得 |
| **D. 只用它的 `notify` webhook/command 给 bma3 发摘要** | ⚠️ 只适合单向汇报 | 这是它支持的扩展点，但只能收到它主动推送的摘要，不能把 B 站私信/评论变成 bma3 的可回复 Inbox 条目 |

> 现有调查早已判断 `dsh-bili-whale`「耦合 DSH 生命周期，不适合直接搬」
> （[`2026-11-09-bot-workflow-design-survey.md:83`](2026-11-09-bot-workflow-design-survey.md)）。
> 本次调查的修正是：**它的插件不适合搬，但它的 CLI 工具链可以单独复用。**

---

## 4. 推荐封装：BiliWhale CLI sidecar

这一节是**候选接口草案，不是已确认设计**；确认后再写 spec，不直接写产品代码。

### 4.1 进程边界

```text
bma3-app
  └─ bma3-bili (新模块)
       ├─ BiliWhaleCli        进程运行器：绝对路径、参数数组、超时、输出上限、UTF-8
       ├─ BiliEventPoller     dm-await / comment-digest → Inbox
       ├─ BiliSourceLabeler   bilibili/... → 「B站私信 …」「B站评论 …」
       ├─ BiliSignalExtractor mention/reply/name/command → PrioritySignals
       └─ BiliTools           只读工具 + 受控写工具
            │
            └─ node <vendor>/tools/bili.mjs <command>
                 cwd = <data>/bili/work
                 HOME = <data>/bili/home
                 DSH_HOME = <data>/bili/dsh
                 BILI_WHALE_CONFIG = <data>/bili/dsh/bili-whale.config.json
                 DSH_NODE = <absolute node>
```

要求：

- 不用 shell 拼字符串；`ProcessBuilder` 参数数组，避免命令注入。
- 每个子进程显式设置 `HOME`/`USERPROFILE`/`DSH_HOME`/`BILI_WHALE_CONFIG`/`DSH_NODE`；
  不继承 bma3 进程的全局 `HOME`。这不是洁癖：上游 Issue #71 就是 systemd 没有 `HOME` 时
  `dm-await` 去 `/.bili-ai` 找凭据，导致私信永远读不到。
- 凭据目录 `<data>/bili/home/.bili-ai/` 必须 0600；日志只记命令名、退出码、耗时、字节数，
  不记 stdout 正文。
- 固定上游 tag/commit（当前 `v0.4.6` / `8b0009f`），升级必须跑契约测试。

### 4.2 能力白名单

| bma3 能力 | CLI | 形态 |
|---|---|---|
| 体检 | `deps --json`、`session`、`traffic status`、`quota status` | 只读 |
| 搜索 | `search <kw>` | 文本 |
| 视频事实 | `video-content <BV>`、`subtitles <BV>`、`comments <BV> --json`、`debrief <BV> --no-frames` | 文本/JSON |
| 抽帧 | `debrief <BV>` / `watch-video` | 文本 + 图片文件路径 |
| 选题 | `feed` | 文本 |
| 私信事件 | `dm-await --once --json --peek` → `--ack <receipt>` | JSON |
| 评论/@ 事件 | `comment-digest --hours 24 --json` | JSON |
| 回评论 | `comment --oid … --root … --parent … --message … --dry/--go` | 文本 |
| 发私信 | `dm --msg … --dry/--go` | 文本 |
| 草稿队列 | `reply-draft list --json` | JSON |

`dm-await --json` 的契约是：退出码 `0` = 有消息/ack 成功，`1` = 正常空收件箱，
`2` = 参数错，`3` = 失败；stdout 可能先有日志、最后一行才是 JSON。
`comment-digest --json` 同样可能先输出人类可读行，**只取最后一行非空 JSON**，
不要把整段 stdout 当 JSON 解析。

不要把 30+ 命令全暴露给模型；先只读，写操作单独加策略。

### 4.3 事件与 source

建议：

```text
bilibili/dm/{selfMid}/{peerMid}                  # 当前 peerMid 只能是 owner.mid
bilibili/comment/{selfMid}/{bvid}/{rootRpid}     # 评论/@
```

如果 `comment-digest` 只给 `oid` 而拿不到 `bvid`，source 就用 `oid`，不要伪造 `bvid`；
路由的具体形状在写 spec 时按上游实际字段定。

- `messageId` 用**稳定键**，不要用一次性 receipt：
  - 私信：`dm:<key>`（`dm-await --json` 返回 `key`；`receipt` 只用于 `--ack`）。
  - 评论：`comment:<oid>:<rpid>`。
- `dm-await --peek` 会保留 pending；bma3 先写消息板/Inbox，再 `--ack`。
  若 bma3 在两者之间崩溃，下一次会重发 pending；消息板的 `message_key` 去重，
  不会产生重复 Inbox 条目。这正好利用 bma3 已有的幂等写入。
- `comment-digest` 没有 ack；bma3 用消息板去重 + 自己的 `lastSeen/rpid` 游标。
  它只回看窗口（默认 24h），所以轮询间隔必须小于窗口；不能把它当无限历史。
- Inbox entry：`sender=who`、`text=正文`、`at=ts/time`、markers=`[私信]`/`[评论]`/`[回复]`/`[视频 BV…]`；
  `line` 仍由 bma3 服务端渲染，不让插件拼最终展示。

### 4.4 优先级

建议在 `weighting.platformWeights` 增加：

```yaml
weighting:
  platformWeights:
    bilibili: 120
```

- 普通 B 站内容/摘要：120，低于默认阈值 200，不唤醒。
- 私信来源权重 300；评论/@ 来源权重 200；reply/mention 信号 300。
- 主人私信 = 300；评论被回复 = max(200, 120+300) = 420；普通评论 = 200，刚好到阈值；
  可按需把普通评论来源权重降到 120，只让 reply/mention 唤醒。
- `BiliSignalExtractor` 只做适配器层信号，不把平台判断塞进 Inbox。

### 4.5 写操作

- 默认 `mode: draft`：只允许 `--dry`，或只把「建议回复」写进 bma3 的待审队列。
- 主人私信可直接回（如果用户确认）；陌生人评论一律先草稿。
- 发送前必须过 bma3 的 `SendPolicy`，并把结果记为 `SENT` / `FAILED` / `UNKNOWN`。
- 上游 `traffic.mjs` 在写请求超时后返回「结果不确定，已禁止自动重发」
  （[`traffic.mjs:189`](https://github.com/Alterssss/dsh-bili-whale/blob/v0.4.6/tools/lib/traffic.mjs#L189)）。
  这正好对应 bma3 红线「`UNKNOWN` 表示可能已发送，禁止自动重发」；wrapper 必须把这类
  非零退出映射成 `UNKNOWN`，不能当普通失败重试。
- 私信 CLI 自己还有硬限制：只发 `owner.mid`、必须互关、单条 ≤300 字
  （`tools/commands/dm.mjs`）。bma3 的 3000 字通用发送上限不能直接套到 B 站私信。

---

## 5. 不能忽略的硬限制

### 5.1 私信只覆盖「主人」一个对话方

`dm-await` 把 `owner.mid` 写死为唯一对话方
（[`dm-await.mjs:9`](https://github.com/Alterssss/dsh-bili-whale/blob/v0.4.6/tools/commands/dm-await.mjs#L9)），
`readHistory(owner, …)` 只拉这个会话
（[`dm-history.mjs:19`](https://github.com/Alterssss/dsh-bili-whale/blob/v0.4.6/tools/lib/dm-history.mjs#L19)、
[`dm-history.mjs:44`](https://github.com/Alterssss/dsh-bili-whale/blob/v0.4.6/tools/lib/dm-history.mjs#L44)）。
所以：

- ✅ 适合「主人 ↔ Bot」的私密通道。
- ❌ 不适合「任意 B 站用户私信 Bot」的客服/社交通道。
- ❌ 也不适合多账号；作者在 Issue #24 明确说多实例不在 roadmap。

如果 bma3 的 B 站目标是「像 QQ 一样接所有人的私信」，这个项目**不能直接当底座**；
只能借它的登录/风控/历史读取实现，自己扩展 peer 维度。

### 5.2 `dm-await` 是单消费者

它用 `dm-await-state.json` + 进程锁保存 `pending/queue/lastSeen`。如果 bma3 和
dsh-bili-whale 插件同时开启 `listen.dm`，两边会抢同一个游标：一边 ack 了，另一边就看不到。
必须二选一：

- **推荐**：bma3 独占 `dm-await`；dsh-bili-whale 插件不装，或至少 `listen.dm=false`。
- 或者：dsh-bili-whale 独占；bma3 只通过它的 `notify` 收摘要（单向，不能回复）。

### 5.3 不是所有命令都有 JSON

`search`、`feed`、`debrief`、`subtitles`、`video-content` 主要是人类/模型可读文本；
`comments`、`comment-digest`、`dm-await`、`deps`、`reply-draft` 等有 `--json`。
Wrapper 不要把文本当稳定协议解析；文本工具直接作为 tool 输出给模型，Inbox 只用 JSON 事件。

### 5.4 扫码登录不在 CLI 里

扫码登录的 `QrAuth` 在 `lib/auth.js`，由 DSH 设置页路由调用；CLI 没有 login 命令。
如果 bma3 不装插件，MVP 需要：

- 手动导入 cookie（`SESSDATA`/`bili_jct`/`buvid3`），或
- 写一个很小的 Node 登录 helper 复用 `lib/auth.js` + `qrcode`，或
- 后续在 Java 里自己实现扫码轮询。

这是「简单封装」里最容易被低估的一块。

### 5.5 运行环境

- CLI 需要 Node；`node tools/bili.mjs help` 在干净 `HOME` 下可运行（本机 Node v22.23.3 验证）。
- 抽帧需要 ffmpeg；不装只影响画面那一路。
- systemd 必须显式设置 `HOME`、`DSH_HOME`、`DSH_NODE`；否则会踩 Issue #71。
- 上游 README 写 Node 18+，但插件半边的 `AbortSignal.any` 需要 Node 20+；
  bma3 DSH 插件本身要求 Node ≥22.13，若复用同一运行时则没问题。CLI 单独跑仍建议 Node 20+。

### 5.6 上游状态与维护风险

- 当前 `v0.4.6`，主分支最后提交 `2026-10-25`；34 个 open issue、15 个 open PR。
- 与我们相关的 open issue：
  - [#71](https://github.com/Alterssss/dsh-bili-whale/issues/71) systemd 无 `HOME` 时私信读不到；
  - [#67](https://github.com/Alterssss/dsh-bili-whale/issues/67) `dm`/`notify` 的 `send_msg` 经 traffic wrapper 可能 30 秒后报「结果不确定」；
  - [#64](https://github.com/Alterssss/dsh-bili-whale/issues/64) 某些 DSH 版本扫码登录报「二维码生成失败」；
  - [#24](https://github.com/Alterssss/dsh-bili-whale/issues/24) 多实例不在 roadmap；
  - [#43](https://github.com/Alterssss/dsh-bili-whale/issues/43) 可扩展平台接口、[#42](https://github.com/Alterssss/dsh-bili-whale/issues/42) 窄口模式，都还是 issue/提案。
- 没有 npm 包；要固定 Git tag/Release，不能 `latest`。
- 测试：`npm ci --ignore-scripts && npm test` → **154 pass / 0 fail**（本机 Node v22.23.3）。
  首次不装 `qrcode` 时 5 个插件集成测试会失败；这是依赖未安装，不是 CLI 逻辑失败。
  测试只覆盖逻辑/模拟网络，**未验证真实 B 站接口和账号**。

### 5.7 许可

MIT（[`LICENSE`](https://github.com/Alterssss/dsh-bili-whale/blob/v0.4.6/LICENSE)）。
如果只调用外部进程，边界较清楚；如果复制 `tools/` 代码进本仓，必须保留版权/NOTICE，
并注意 `tools/docs/` 来自 bilibili-API-collect、ffmpeg 是 LGPL 且不随包分发。

---

## 6. 建议的落地顺序

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | 固定 `v0.4.6`；隔离 `HOME`/`DSH_HOME`；包装 `deps --json`、`session`、`traffic status` | 干净环境能体检；缺 Node/凭据时 fail-closed 且错误可读 |
| P1 | 只读工具：`search`、`comments`、`subtitles`、`debrief --no-frames`、`feed` | fake 进程契约测试；真实 B 站 `NOT_RUN` |
| P2 | 事件：`dm-await`（owner）+ `comment-digest` → message board + Inbox；source/label/priority；ack/去重 | 重启/重复投递不产生重复条目；单消费者；不发消息 |
| P3 | 写：`comment`/`dm` 的 `--dry` → 审批 → `--go`；`SENT/FAILED/UNKNOWN` | 不自动重发 `UNKNOWN`；额度/互关/长度限制生效 |

工作量粗估：

- P0+P1：约 1–2 天（进程封装、契约测试、只读工具）。
- P2：约 2–3 天（游标、ack、去重、source/priority、并发测试）。
- P3：约 2–3 天（审批、发送三态、额度、失败注入）。
- 合计约 1 周 MVP；不是「装一下就好」。

---

## 7. 需要用户裁定的问题

1. **B 站范围**：只要「视频研究工具」，还是要「B 站私信/评论作为 Inbox 来源」？
   如果只要视频，甚至可以不碰它的插件，只接只读 CLI。
2. **私信对象**：只接受「主人私信」够不够？如果要做任意用户私信，这个项目只能当参考，
   需要自写 peer 维度的 adapter 或扩展上游。
3. **运行依赖**：bma3 部署能否接受 Node 18+/20+、ffmpeg（可选）、一个 vendored CLI？
   如果必须纯 Java/单 jar，就不能走 sidecar，只能移植 B 站 API。
4. **写策略**：主人私信可直接回，陌生人评论只草稿？还是全部先草稿？
5. **上游策略**：固定 tag + 契约测试，还是 fork 后自己维护？建议前者。

---

## 8. 证据

- 克隆：`git clone --depth 1 https://github.com/Alterssss/dsh-bili-whale /tmp/dsh-bili-whale-investigate`
- 版本：`v0.4.6`，`HEAD 8b0009f8ce2ebdc421afcb2e727b19ac18912ec6`
- 测试：`npm ci --ignore-scripts` → `npm test` → `154 pass / 0 fail`
- 独立 CLI：`HOME=/tmp/... node tools/bili.mjs help` → rc 0（本机 Node v22.23.3）
- 插件耦合：`lib/index.js:157` `inject: ['sessionController','webServer','connection']`；
  `lib/scheduler.js:272` `delivery.send(...)`；`lib/session-delivery.js:65` `controller.prompt(...)`
- 私信 owner-only：`tools/commands/dm-await.mjs:9`；`tools/lib/dm-history.mjs:19,44`
- 上游 Issue：#24、#42、#43、#64、#67、#71
- **NOT_RUN**：真实 B 站 cookie/账号、真实私信/评论收发、真实 DSH 宿主、systemd 部署。

---

## 9. 一句话结论

**「轮子」值得借，但要借的是 `tools/bili.mjs` 这套 B 站能力，不是「养鱼」插件本身。**
做一个薄 CLI sidecar，让 bma3 继续做唯一的 Agent/Inbox/消息板；先只读，再 owner 私信/评论，
最后才考虑受控写入。这样既复用最难的 B 站 API/风控/历史读取，又不把两个 Agent 塞进同一个宿主。
