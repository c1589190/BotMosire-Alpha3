# BotMosire-Alpha3

DevMosire-iiBot 的继承与重构。**版本 `0.0.5-early`（开发中，未发布）**：Java 21 独立服务 + QQ OneBot 11 + Inbox + MCP + Selenium 浏览器工具 + **Xvfb 虚拟桌面（Computer Use）** + DSH 薄插件 + jlink/jpackage 安装骨架。

> 最近一次已发布版本仍是 `0.0.4-hotfix1`，下面的 Release 链接指向它。`0.0.5-early` 只在本仓库与 CI 门禁中存在，**未打 tag、未发布**。

- 远端：<https://github.com/c1589190/BotMosire-Alpha3>
- 继承来源（只读参考）：`/home/cna/DevMosire-iiBot`
- 当前分支：`feat/bma3-plan-1`
- 真实 QQ/DSH/systemd 的完整生产联调仍按 `NOT_RUN` 记录，见 [`docs/agents/STATUS.md`](docs/agents/STATUS.md)。

## 给 Agent 的快速加载顺序

看到仓库后按这个顺序做，不要先改代码：

1. 下载或编译 `bma3-app-0.0.5-early.jar`（或下载已发布的 `0.0.4-hotfix1`）；
2. 准备运行目录、`config/app.yaml` 和 token 文件；
3. 启动 jar，确认 MCP 和 Inbox 端口；
4. 把 `dsh-plugin` link 进目标 DSH profile；
5. 启动后 DSH 插件自动连接 Inbox 的单个 SSE；需要指定目标会话时调用 `bma3_inbox_bind`；
6. 需要 MCP 工具时，在 DSH 里添加 Bearer MCP 连接。

### 1. 下载或编译

Release 资产（推荐给只看仓库的 Agent）：

<https://github.com/c1589190/BotMosire-Alpha3/releases/download/v0.0.4-hotfix1/bma3-app-0.0.4-hotfix1.jar>

从源码编译：

```bash
./mvnw -q -pl bma3-app -am package -DskipTests
ls -lh bma3-app/target/bma3-app-0.0.4-hotfix1.jar
```

要求：JDK 21、Node.js 22（仅 DSH 插件需要）。源码构建还需要 `io.mosire:agentlib-mosire:0.2.5`；它当前从 `~/ProjectMosire/AgentLibMosire` 本地安装，不在公共 Maven 中央。只下载 release jar 运行则不需要它。浏览器工具另需 Chromium/ChromeDriver；本机 Snap Chromium 的驱动路径见 [`docs/agents/ENVIRONMENT.md`](docs/agents/ENVIRONMENT.md)。

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
export BMA3_MCP_TOKEN="$(cat /opt/bma3/token)"
export LLBOT_ACCESS_TOKEN="..."          # 云端 LLBot/OneBot token
export BMA3_CHROMEDRIVER="/path/to/chromedriver"  # 可选；不填走 Selenium Manager
java -jar bma3-app-0.0.5-early.jar --config /opt/bma3/run
```

启动日志会打印实际 MCP/health/Inbox 端口；默认配置对应：

- MCP：`http://127.0.0.1:8765/mcp`
- Inbox HTTP：`http://127.0.0.1:46500`
- health：`http://127.0.0.1:8766/healthz`（liveness）、`/readyz`（readiness）、`/watchdog`（Bearer）

验证 Inbox（Bearer 用 `/opt/bma3/token`）：

```bash
curl -H "Authorization: Bearer $(cat /opt/bma3/token)" \
  http://127.0.0.1:46500/v1/inboxes/bma3/status
```

### 3. 安装 DSH 插件

插件是仓库里的 `dsh-plugin/`，包名 `@mosire/bma3-dsh`。一个包四个模块：`bma3-inbox`、`bma3-preset`（内含 `self` 人设 loader）、`bma3-self`、以及兼容用的 `bma3-persona`（已停用）。

```bash
git clone https://github.com/c1589190/BotMosire-Alpha3.git
cd BotMosire-Alpha3
dsh plugin --profile web add link:$PWD/dsh-plugin
```

在目标 profile 的 `cordis.patch.yml` 加/覆盖：

```yaml
- id: bma3-inbox
  name: "@mosire/bma3-dsh"
  config:
    module: inbox
    baseUrl: http://127.0.0.1:46500
    inboxId: bma3
    envFile: /opt/bma3/run/config/.bma3env
    sessionId: session-...   # 可选；省略时取第一个 idle 会话
    autoStart: true
    # stateFile: /home/you/.dsh/storages/bma3-inbox.json  # 默认即此路径
    heartbeatMs: 15000

- id: bma3-persona
  disabled: true   # legacy; 人设现在由 bma3-preset 内的 self loader 提供

- id: bma3-preset
  name: "@mosire/bma3-dsh"
  config:
    module: preset
```

启用后在 DSH 新建会话时选择 **BMA3** preset。预设只做一件事：注入你自己的系统提示词。

```text
~/.dsh/bma3/systemprompt.md    # 人设正文（$DSH_HOME 优先）；每次组装重读，缺失即不注入
```

可从 [`dsh-plugin/examples/systemprompt.md`](dsh-plugin/examples/systemprompt.md) 复制模板。
工具面沿用宿主默认：插件不再挂 bash/read_image/子 Agent 行，也没有 `agentconfig.json`
（工具面分层已按 D-066 删除）。

`envFile` 指向 jar 生成的 `.bma3env`（0600），插件只读 `INBOX_TOKEN`，不再接受 `tokenFile`/`tokenEnv`/`token`（D-063）。一个 inbox 只有一条 SSE；source 只是面板里的条目，不存在 per-source 订阅。普通群消息是否进入面板由服务端统一优先级决定（群内 @/命令等信号会提高优先级），不再需要 `minPriority`。

插件连上后自动注入受限面板；需要指定接收会话时调用 `bma3_inbox bind`，绑定会**落盘**，DSH 重启后插件会 `agents.resume` 恢复该会话并自动启动 SSE。`bma3_inbox status` 查看连接/游标/push 水位/心跳；`bma3_inbox ack` 手动推进读游标（正常注入成功后插件会自动按 itemIds ack）。唤醒文本以 `[INBOX_PANEL]` 开头，只带受限窗口和关系摘要；同源推送后又有新消息时另开一条并标「（消息更新活跃）」。

### 4. 接上 BMA3 MCP（业务工具）

没有 MCP 连接时，`bma3-inbox` 插件仍能通过 HTTP/SSE 唤醒并自动 ack，但 `inbox_list`、
`qq_*` 等工具不会出现。

**唯一方式：Agent 用 DSH 自带的连接器自己连**（D-066 —— 插件不再做桥接）。

jar 起来后会在数据目录写 `.bma3env`（0600），里面有 `MCP_TOKEN` / `INBOX_TOKEN`；
MCP 端点是 `http://127.0.0.1:18765/mcp`。会话里对 Agent 说「接上 bma3」，它应当：

```text
mcp_connector_configure(
  name: "bma3",
  url: "http://127.0.0.1:18765/mcp",
  transport: "streamable-http",
  authMode: "bearer",
  bearerToken: <读 .bma3env 的 MCP_TOKEN>
)
```

通过校验后工具以 `mcp__bma3__*` 出现（约 40 个：`inbox_*`、`qq_*`、`get_config`/`set_config`、
`bma3_watchdog_*`）。按会话临时启用子集用 `mcp_connector_session_tools`。
不想用 token 就让 jar 用 `--no-auth` 启动，连接器改 `authMode: "none"`。

> 红线 2（D-060）：Agent 自行配置连接时 token 会落进当次会话日志；仍禁止进入提交、配置样例、
> 仓库文件与导出/快照类输出；凭据文件必须 0600。

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
