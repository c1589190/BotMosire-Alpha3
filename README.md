# BotMosire-Alpha3

DevMosire-iiBot 的继承与重构。**版本 `0.0.2-hotfix1`**：Java 21 独立服务 + QQ OneBot 11 + Inbox + MCP + Selenium 浏览器工具 + DSH 薄插件 + jlink/jpackage 安装骨架。

- 远端：<https://github.com/c1589190/BotMosire-Alpha3>
- 继承来源（只读参考）：`/home/cna/DevMosire-iiBot`
- 当前分支：`feat/bma3-plan-1`
- 真实 QQ/DSH/systemd 的完整生产联调仍按 `NOT_RUN` 记录，见 [`docs/agents/STATUS.md`](docs/agents/STATUS.md)。

## 给 Agent 的快速加载顺序

看到仓库后按这个顺序做，不要先改代码：

1. 下载或编译 `bma3-app-0.0.2-hotfix1.jar`；
2. 准备运行目录、`config/app.yaml` 和 token 文件；
3. 启动 jar，确认 MCP 和 Inbox 端口；
4. 把 `dsh-plugin` link 进目标 DSH profile；
5. 启动后 DSH 插件自动连接 Inbox 的单个 SSE；需要指定目标会话时调用 `bma3_inbox_bind`；
6. 需要 MCP 工具时，在 DSH 里添加 Bearer MCP 连接。

### 1. 下载或编译

Release 资产（推荐给只看仓库的 Agent）：

<https://github.com/c1589190/BotMosire-Alpha3/releases/download/v0.0.2-hotfix1/bma3-app-0.0.2-hotfix1.jar>

从源码编译：

```bash
./mvnw -q -pl bma3-app -am package -DskipTests
ls -lh bma3-app/target/bma3-app-0.0.2-hotfix1.jar
```

要求：JDK 21、Node.js 22（仅 DSH 插件需要）。源码构建还需要 `io.mosire:agentlib-mosire:0.2.5`；它当前从 `~/ProjectMosire/AgentLibMosire` 本地安装，不在公共 Maven 中央。只下载 release jar 运行则不需要它。浏览器工具另需 Chromium/ChromeDriver；本机 Snap Chromium 的驱动路径见 [`docs/agents/ENVIRONMENT.md`](docs/agents/ENVIRONMENT.md)。

### 2. 运行目录与配置

```bash
mkdir -p /opt/bma3/run/config /opt/bma3/data
cp bma3-app/src/main/resources/config-template/app.yaml /opt/bma3/run/config/app.yaml
openssl rand -hex 24 > /opt/bma3/token
chmod 600 /opt/bma3/token
```

最小配置示例（把 `dataDir`、endpoint 和 token env 换成实际值）：

```yaml
dataDir: /opt/bma3/data

server:
  host: 127.0.0.1
  port: 8765
  mcpPath: /mcp
  tokenEnv: BMA3_MCP_TOKEN

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

inbox:
  inboxId: bma3
  httpPort: 46500
  display:
    maxMessagesPerItem: 6   # 4..8
    maxItemsPerPanel: 6     # 4..8
    includeOmittedCount: true

# 分数 = 最具体的来源规则 + 命中的属性加分（加法）
# < dropBelow 丢弃（不落消息板）；>= immediateThreshold 走 importantDelay，其余走 normalDelay
weighting:
  immediateThreshold: 300
  dropBelow: -300
  maxPriority: 1000
  importantDelaySeconds: 1
  normalDelaySeconds: 30
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
    # qq_sender/132843233: 50
    # qq_name/管理: 40

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
java -jar bma3-app-0.0.2-hotfix1.jar --config /opt/bma3/run
```

启动日志会打印实际 MCP/health/Inbox 端口；默认配置对应：

- MCP：`http://127.0.0.1:8765/mcp`
- Inbox HTTP：`http://127.0.0.1:46500`
- health：启动日志中的随机回环端口

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
    tokenFile: /opt/bma3/token
    sessionId: session-...   # 可选；省略时取第一个 idle 会话
    autoStart: true

- id: bma3-persona
  disabled: true   # legacy; 人设现在由 bma3-preset 内的 self loader 提供

- id: bma3-preset
  name: "@mosire/bma3-dsh"
  config:
    module: preset
```

启用后在 DSH 新建会话时选择 **BMA3** preset。工作区根目录放两个文件：

```text
systemprompt.md     # 人设正文；每步按 mtime/size 重读
agentconfig.json    # 默认工具组；不含 lock 字段
```

可从 [`dsh-plugin/examples/`](dsh-plugin/examples/) 复制模板。`bash`/`pwsh`、`read_image`
由 preset composition 固定，`agentconfig` 改不掉；子 Agent 的 spawn 走 DSH 内置 `minimal`。

`tokenFile` 必须是 0600 的 Inbox Bearer 文件。一个 inbox 只有一条 SSE；source 只是面板里的条目，不存在 per-source 订阅。普通群消息是否进入面板由服务端统一优先级决定（群内 @/命令等信号会提高优先级），不再需要 `minPriority`。

插件连上后自动注入受限面板；需要指定接收会话时调用 `bma3_inbox_bind`，用 `bma3_inbox_status` 查看连接/游标，`bma3_inbox_ack` 手动推进读游标（正常注入成功后插件会自动 ack）。唤醒文本以 `[INBOX_PANEL]` 开头，只带受限窗口和关系摘要。

### 4. 添加 BMA3 MCP（完整工具需要）

没有 MCP 连接时，`bma3-inbox` 插件仍能通过 HTTP/SSE 唤醒并自动 ack，但 `inbox_list`、
`qq_*` 等工具不会出现。完整工具需要至少一条 `@deepseek-ai/dsh-mcp-client` 连接
（`dsh-mcp-connector` 是管理/配置层，不是 transport 本身）。

在 DSH 的 MCP 连接里新增：

- 名称：`bma3`
- URL：`http://127.0.0.1:8765/mcp`
- 传输：streamable HTTP
- 鉴权：Bearer，值同 `/opt/bma3/token`

或直接加 profile row：

```yaml
- id: bma3-mcp
  name: '@deepseek-ai/dsh-mcp-client'
  config:
    serverName: bma3
    transport: streamable-http
    url: http://127.0.0.1:8765/mcp
    headers:
      Authorization: !!js '`Bearer ${process.env.BMA3_MCP_TOKEN}`'
    failOnStartupError: true
```

加载后可用的工具组：

- QQ 账号：`qq_account_list/status/login_info`
- 会话导航：`qq_thread_list`、`qq_recent_messages`、`qq_conversation_history`、`qq_message_get`
- 读写：`qq_conversation_read/ack/send/send_image`、`qq_reply`、`qq_private_send`
- 用户备注：`qq_user_note_get/list/upsert`（只存别名/标签/备注，不存分数）
- 配置：`get_config`、`set_config`（改 `config/app.yaml` 并热重载；来源规则与属性加分都在这里）
- 目录：`qq_group_list`、`qq_friend_list`
- Inbox：`inbox_status/list/ack`
- 浏览器：`browser_open/snapshot/click/type/text/screenshot/video_frames`

`qq_conversation_send_image` 的 `imagePath` 必须在 `dataDir` 下，适合把登录 QR 截图发给用户。

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
./mvnw -q test                 # Java 19 suites / 50 tests（1 个真实浏览器 smoke 默认跳过）
(cd dsh-plugin && npm test)    # 14 tests
bash packaging/smoke.sh        # jlink/jpackage app-image + /healthz smoke
```

## 边界

新功能必须先有设计稿与验收判据；未跑过的验证一律写 `NOT_RUN`。真实 QQ/DSH/systemd 联调不得用 fake/回环测试冒充。

## License

MIT License. See [`LICENSE`](LICENSE).
