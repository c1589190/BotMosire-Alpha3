# BotMosire-Alpha3

DevMosire-iiBot 的继承与重构。当前版本为 **`0.0.8-hotfix1` 预发布版**，包含 Windows 原生、纯 MCP 的 QQ 接入和本地 HTTP 调试机。[预发布说明](https://github.com/c1589190/BotMosire-Alpha3/releases/tag/v0.0.8-hotfix1)

> 稳定版 `0.0.7-tolatest` 包含来源等待、面板摘要、watchdog/媒体诊断、xvfb 易用性和 QQ friend/provider 动作。[稳定版说明](https://github.com/c1589190/BotMosire-Alpha3/releases/tag/v0.0.7-tolatest)

- 远端：<https://github.com/c1589190/BotMosire-Alpha3>
- 继承来源（只读参考）：`/home/cna/DevMosire-iiBot`
- Windows / Codex 快速开始：[Windows 纯 MCP 运行指南](docs/windows-mcp.md)。
- 真实 QQ/DSH/systemd 的完整生产联调仍按 `NOT_RUN` 记录，见 [`docs/agents/STATUS.md`](docs/agents/STATUS.md)。

以下是已发布的 `0.0.7-tolatest` 功能；Windows 开发分支不要求安装 DSH 插件。

## `0.0.7-tolatest` 新功能

- **Inbox 来源等待**：`inbox.wait.sources` 支持任意 source 前缀、最长匹配和热重载；未配置来源默认 `0` 秒，Agent 可自行开启。quiet 中的条目不会被其他来源的 flush 提前推走。
- **Agent 自主等待**：MCP `inbox_wait_quiet(source/sourcePrefix, quietMs=2000, maxWaitMs=20000)` 只读等待来源安静；不 ACK、不冻结、不创建 item。`settled` 只表示来源安静，`allAckable/processable` 才表示当前返回项可处理。
- **面板摘要**：`inbox.display.mode=full|summary|auto`；`summaryMaxCharsPerItem`、`summaryAboveItems` 可热重载；摘要模式只显示最新 entry，`inbox_get` 可用 `itemId` 或 panel `index` 展开。
- **watchdog / 媒体诊断**：`GET /watchdog/diagnostics` 与 MCP `bma3_watchdog_diagnose` 提供无正文诊断；媒体计数包含 image/file/record/video 和下载目录统计。
- **媒体工具**：插件 `bma3_media(filePath)` 按 image/document/audio 路由 OCR/ASR；没有 `DEEPSEEK_API_KEY` 时明确失败，不伪造识别结果。它是 BMA3 preset 工具，不在 bma3 MCP 的 68 个工具里。
- **xvfb**：`xvfb_open_browser`、`xvfb_wait` 把“开浏览器 + 等窗口”缩短为两步；默认带 `--no-first-run`/`--no-default-browser-check`。
- **QQ**：`qq_friend` 提供常用好友操作；`qq_provider_action` 覆盖 provider 扩展。写 action 需 `confirm=true`，发送/上传 action 永久禁止绕过消息板。
- **升级注意**：jar 与 DSH 插件必须成对升级；先停旧写者并备份 SQLite。BMA3 preset 工具需要新 BMA3 会话或宿主重载后才能看到最新行。

## 给 Agent 的快速加载顺序

Linux DSH 使用下文的一键包。安装到目标 profile 后启动 DSH，用 `bma3_runtime` 确认 JAR 状态。
在目标会话运行 `/inbox bind` 接收预览。需要业务 MCP 工具时，选择 BMA3 预设。

### 1. 下载或编译

`v0.0.8-hotfix1` 提供以下发布资产。

本次修复普通优先级 Inbox 推送间隔。升级后可用 `set_config` 将
`weighting.normalDelaySeconds` 设为 `1800`（半小时），最大 `86400`（24 小时）。

- Linux x86-64 app-image。[下载](https://github.com/c1589190/BotMosire-Alpha3/releases/download/v0.0.8-hotfix1/botmosire-a3-linux-x86_64.tar.gz)
- 独立 JAR，需要 JDK 21。[下载](https://github.com/c1589190/BotMosire-Alpha3/releases/download/v0.0.8-hotfix1/bma3-app-0.0.8-hotfix1.jar)
- DSH 插件。[下载](https://github.com/c1589190/BotMosire-Alpha3/releases/download/v0.0.8-hotfix1/mosire-bma3-dsh-0.0.8-hotfix1.tgz)

源码构建需要 JDK 21，以及当前 Maven 本地仓库中已安装的 AgentMosire 父 POM 和 `io.github.c1589190.agentmosire:agentlib-mosire:0.0.1-early2`。首次准备依赖时，在对应版本的 AgentMosire 源码根目录运行 `./mvnw -B -ntp -pl AgentLib -am -DskipTests install`。

回到本仓库构建 app-image：

```bash
bash packaging/build-app-image.sh
ls -lh dist/botmosire-a3-linux-x86_64.tar.gz
```

DSH 插件需要 Node.js 22。浏览器工具需要 Chromium 和 ChromeDriver，本机 Snap Chromium 的驱动路径见[环境说明](docs/agents/ENVIRONMENT.md)。

### 0.0.9-fix3 Linux DSH 一键包

在 Linux x86_64 上运行 `bash packaging/build-dsh-bundle.sh`，生成
`dist/mosire-bma3-dsh-0.0.9-fix3-linux-x64-full.tgz`。在目标 DSH profile 安装后，
启动 profile 即可运行内置 Java、MCP 与 Inbox。Linux 插件还可托管由管理员指定的本机 JAR 或连接已运行的服务。
配置与启停示例见 [DSH 托管指南](dsh-plugin/README.md#托管-bma3)。

### 2. 运行目录与配置

```bash
mkdir -p /opt/bma3/run/config /opt/bma3/data
cp bma3-app/src/main/resources/config-template/app.yaml /opt/bma3/run/config/app.yaml
# token 不用手工造：jar 首启会在配置目录生成 .bma3env（MCP_TOKEN / INBOX_TOKEN，0600，幂等）。
# 轮换用 --rotate-token；想彻底不要鉴权就加 --no-auth（仅回环可用）。
```

首次启动后配置目录里会多出：

```text
/opt/bma3/run/config/.bma3env     # 0600，jar 生成，DSH 插件只读
```

同一 `dataDir` 只允许一个进程：第二次启动会退出码 1 并打印已有实例的 pid。

最小配置示例（把 `dataDir`、endpoint 和 token env 换成实际值）：

```yaml
dataDir: /opt/bma3/data

server:
  host: 127.0.0.1
  port: 8765
  mcpPath: /mcp
  tokenEnv: BMA3_MCP_TOKEN
  healthPort: 8766          # /healthz /readyz /watchdog；0=随机

qqconnect:
  accounts:
    - accountId: qq-main
      endpoint: ws://47.100.101.156:3001/
      tokenEnv: LLBOT_ACCESS_TOKEN
      enabled: true
      # selfQq / selfName 可省：启动时用 get_login_info 自动学习
      # selfName: BotMosire
  send:
    maxTextChars: 3000   # 1..3000
    overflow: reject     # reject | split
  media:
    dir: ""                  # 空 = <dataDir>/downloads
    maxInlineBytes: 65536
    maxDownloadBytes: 33554432
    timeoutSeconds: 20

inbox:
  inboxId: bma3
  httpPort: 46500
  display:
    maxMessagesPerItem: 6   # 1..50
    maxItemsPerPanel: 6     # 1..50
    includeOmittedCount: true
    maxCharsPerItem: 1200   # 单条目字符上限；0=不限（不设总字符上限）
    mode: auto              # full | summary | auto
    summaryMaxCharsPerItem: 200
    summaryAboveItems: 8
  wait:                     # 硬静默只修改现有注入调度；默认来源 0 秒
    enabled: true
    defaultQuietSeconds: 0
    defaultMaxWaitSeconds: 20
    sources: []
      # - source: qq_private
      #   quietSeconds: 8
      #   maxWaitSeconds: 20
  push:
    minPriority: -1000      # 已废弃/忽略：所有未被 dropBelow 丢弃的条目都会推送
    maxFirstPushDelaySeconds: 60
  dismiss:
    autoEnabled: false      # watchdog 自动清理低优先 backlog
    autoBelowPriority: 0
    autoAfterSeconds: 1800
    autoMaxPerRound: 200
  retention:
    maxAgeDays: 30
    maxItems: 5000

# 分数 = 最具体的来源规则 + 命中的属性加分（加法）
# < dropBelow 丢弃（不落消息板）；>= immediateThreshold 走 importantDelay，其余走 normalDelay
weighting:
  immediateThreshold: 300
  dropBelow: -300
  maxPriority: 1000
  importantDelaySeconds: 3
  normalDelaySeconds: 60
  sourcePriority:
    "*": 0
    qq_private: 300
    qq_group: 200
    # qq_group/955432692: 350     # 更具体者胜
  attributes:
    qq_at_bot: 100
    qq_at_all: 20
    qq_at_other: 0
    qq_reply_to_bot: 100
    qq_self_name: 60
    qq_command: 100
    # qq_person_id/132843233: 50   # 稳定 QQ 号；昵称搜索用 qq_search

deployer:
  qq: ""                 # 组件 DOWN 时用可用账号私聊这个 QQ；空=关闭
  enabled: false

watchdog:
  enabled: true
  intervalSeconds: 10
  probeTimeoutMs: 2000
  failureThreshold: 2
  recoveryCooldownSeconds: 30
  alertCooldownSeconds: 600
  pluginHeartbeatTimeoutSeconds: 45
  diskMinFreeMb: 200
  readyzCritical: [mcp, inboxHttp, qqAccount, db]

webconnect:
  enabled: true
  binary: /snap/bin/chromium
  headless: true
  allowedHosts:
    - bilibili.com
    - www.bilibili.com
    - passport.bilibili.com
    - search.bilibili.com
    - space.bilibili.com
```

启动：

```bash
export LLBOT_ACCESS_TOKEN="..."          # 云端 LLBot/OneBot token
export BMA3_CHROMEDRIVER="/path/to/chromedriver"  # 可选；不填走 Selenium Manager
./dist/botmosire-a3/bin/botmosire-a3 --config /opt/bma3/run
```

启动日志会打印实际 MCP/health/Inbox 端口；默认配置对应：

- MCP：`http://127.0.0.1:8765/mcp`
- Inbox HTTP：`http://127.0.0.1:46500`
- health：`http://127.0.0.1:8766/healthz`（liveness）、`/readyz`（readiness）、`/watchdog` 与 `/watchdog/diagnostics`（Bearer）

验证 Inbox（Bearer 用 `.bma3env` 里的 `INBOX_TOKEN`）：

```bash
INBOX_TOKEN="$(sed -n 's/^INBOX_TOKEN=//p' /opt/bma3/run/config/.bma3env)"
curl -H "Authorization: Bearer $INBOX_TOKEN" \
  http://127.0.0.1:46500/v1/inboxes/bma3/status
```

### 3. 安装与使用 Linux DSH 插件

构建并安装上述一键包，在目标 profile 中只有一条 `bma3-plugin` 入口。首次启动 profile
会使用内置 Java 21 启动 BMA3。任意会话可以调用 `bma3_runtime` 查看、启动、停止和重启 JAR。
在要接收通知的会话里运行 `/inbox bind`。JAR 停止时，Inbox 和运行时管理入口仍可访问。

### 4. 使用 BMA3 预设和业务工具

在目标会话选择 **BMA3** 预设。提示词来自 `$DSH_HOME/bma3/systemprompt.md`，可参考
[提示词模板](dsh-plugin/examples/systemprompt.md)。插件会在该预设内接入业务 MCP。
JAR 就绪后，可调用 `mcp__bma3__inbox_status` 自检。旧插件升级、外部 JAR 和低频工具入口配置见
[DSH 插件指南](dsh-plugin/README.md)。

### 4.1 远程问诊（debug API）与定时任务（0.0.8 批次）

两个新面都挂在 **health 端口**上，所以远程调试只需要一条 SSH 隧道：

```bash
ssh -L 8766:127.0.0.1:8766 ubuntu@<host>
# 本地拿到的是远端 jar 的 health/debug 面
```

token 还是同一个 `.bma3env`，但 debug 面用**第三条独立 token**（`DEBUG_TOKEN`）：

```bash
curl -H "Authorization: Bearer $(grep DEBUG_TOKEN .bma3env | cut -d= -f2)"      http://127.0.0.1:8766/debug/snapshot
```

| 端点 | 作用 |
|---|---|
| `GET /debug/snapshot` | 版本/pid/uptime + watchdog 快照 + Inbox 状态与插件心跳 + 各 DB 大小 |
| `GET /debug/logs?since=<ISO>&limit=` | watchdog 事件尾（只有 ID/状态/耗时，没有正文） |
| `POST /debug/ask` | `{"question":"...","timeoutSeconds":120,"wait":true}` → 提问注入被绑定会话；`wait=false` 立返回 202 + `id` |
| `GET /debug/ask/{id}` | 拉单条结果（`PENDING`/`ANSWERED`/`EXPIRED`） |
| `POST /debug/answer` | `{"requestId":"...","text":"..."}`，给没有 MCP 工具的调用方 |
| `GET /debug/ask?status=pending` | 列表 |

- 问题以 `source=bma3_debug/<id>`、优先级 350 **经 Inbox** 推给 Agent；Agent 用 MCP 工具
  `bma3_debug_reply(requestId, text)` 回答，你那条 HTTP 请求就会返回答案。
- 不做单飞：`debug.maxPending` 默认 32，`0` = 不限；超限返回 429。
- `security.auth=none` 时整个 `/debug` 面**不挂载**（404）。这里**不能**执行命令，只能提问和看快照。

需要在空 QQ 账号列表下复现推送时，可用[本地 HTTP 调试机](tools/e2e-local/README.md#无-qq-连接的-http-调试机)启动独立 jar，再用本机 `dsh web` 连接专用 Inbox。调试工具支持单条推送、批量生成、查询和回答。

定时任务（`bma3_schedule_*`）：`once`（ISO 时刻）/`interval`（秒）/`cron`（5 字段）。
到点只往 Inbox 注入一条 `source=bma3_task/<id>` 的通知，Agent 在会话里看到后自己决定做什么；
`skip`/`once` 决定停机错过时是跳过还是补一次，运行历史在 `data/schedule.db` 的 `run` 表。

权重排查用 `bma3_priority_explain(text, source, attributes)`：它和 ingest 走同一个 resolver，
直接告诉你「为什么这条没唤醒我」——命中了哪条来源规则、哪个属性、哪条正文规则、最后是否 ≥ 阈值。

`.rd` 这类非 `/`、`!` 前缀的命令，现在只要配置：

```yaml
weighting:
  commandPrefixes: ["/", "!", "."]      # qq_command 由这里派生
  rules:
    - match: prefix
      pattern: ".rd"
      delta: 150                        # 私聊 300 + qq_command 100 + 150 = 550
      # source: qq_group/955432692      # 可选：只对这个来源生效
```

### 5. 常见问题

- **QQ 连不上**：先检查云端 LLBot `/get_login_info` 和 `/get_status`，再检查 `endpoint`/token env；不要把本地 LLBot 和远程配置混用。
- **Inbox 没唤醒**：确认插件行是“运行中”、`bma3_inbox_status` 的 `connection=open`、目标会话是 idle；SSE 断线会自动重连，错误在 `lastError`。
- **浏览器起不来**：Snap Chromium 不能读 `/tmp`，profile 必须在 home 下；ChromeDriver 用 `BMA3_CHROMEDRIVER` 显式指定。
- **密钥**：token 文件 0600，不提交、不打印、不进 MCP 结果；所有服务只绑定回环。

## 从这里开始

| 要什么 | 去哪 |
|---|---|
| 工作规则、红线、继承关系 | [`AGENTS.md`](AGENTS.md) |
| 当前状态与下一步 | [`docs/agents/STATUS.md`](docs/agents/STATUS.md) |
| 文档系统规范 | [`docs/AGENTS.md`](docs/AGENTS.md) |
| 邻居项目调查 | [`docs/superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md`](docs/superpowers/reports/2026-11-09-neighbor-DevMosire-iiBot-survey.md) |
| 设计与维护裁定 | [`docs/superpowers/DESIGN_RULINGS.md`](docs/superpowers/DESIGN_RULINGS.md)、[`docs/agents/MAINTENANCE_RULINGS.md`](docs/agents/MAINTENANCE_RULINGS.md) |

## 构建与测试

```bash
./mvnw -q test                 # Java 103 tests（102 pass + 1 个真实浏览器 smoke 默认跳过）
(cd dsh-plugin && npm test)    # 28 tests
bash packaging/smoke.sh        # jlink/jpackage app-image + /healthz smoke
```

## 边界

新功能必须先有设计稿与验收判据；未跑过的验证一律写 `NOT_RUN`。真实 QQ/DSH/systemd 联调不得用 fake/回环测试冒充。

## License

MIT License. See [`LICENSE`](LICENSE).
