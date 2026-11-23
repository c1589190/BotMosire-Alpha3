# @mosire/bma3-dsh

BMA3 的 DSH 插件有两个加载模块。

| 模块 | 作用 |
|---|---|
| `inbox` | 把 BMA3 Inbox 注入 DSH 会话，也可以托管 Java 进程并连接原生 MCP |
| `preset` | 注册 `bma3` 预设：注入你的系统提示词 + 挂一层薄工具面（含 OCR/ASR 两个媒体工具） |

**没有白名单，没有 `restrict`，没有策略层** —— 预设挂什么，会话就看到什么（D-067）。
0.0.5 的 `defaultTools` 组别白名单已删除：它依赖别的插件先把工具名注册进全局层，而且
allow 从全局视图算，会把预设自己挂的 `bash`/`read_image`/`subagent` 一起裁掉。

## 预设：人设 + 薄面

提示词文件默认 `~/.dsh/bma3/systemprompt.md`（`$DSH_HOME` 优先），示例见
[`examples/systemprompt.md`](examples/systemprompt.md)。

```yaml
- id: bma3-preset
  name: '@mosire/bma3-dsh'
  config:
    module: preset
    promptFile: ~/.dsh/bma3/systemprompt.md   # 可省，省略即默认
    complete: true                            # 可省，默认 true
```

预设为会话挂载以下组件。

| 行 | 给什么 |
|---|---|
| `persona` | 你的 system prompt（遮蔽 `deployment:persona-prefix`） |
| `tool-bash` / `tool-pwsh` | `bash`（Windows 上是 `pwsh`） |
| `tool-fs` | **只有 `read_image`**（`read`/`write`/`edit` 关；文本读写走 bash） |
| `tool-subagent-control` + `tool-subagent` | 子 Agent（`agentPreset: minimal`） |
| `tool-plugin-manager` | 宿主有 `profileContext` 才挂；这是 Agent 自己挂插件的门 |
| `media` | DeepSeek OCR / ASR |
| `tool-session-query` | 五个只读会话查询工具 |

要更宽的面就把行加进 `presetDefinition()` 的 `toolRows()`；要收窄就删行。**不需要改任何策略**。

人设语义：

- `complete: true`（默认）：这份文本**就是**完整 system prompt，整份替换 DSH 默认提示词。
- `complete: false`：退化成"插在 persona 前缀位置"，保留 DSH 默认提示词。
- 文件缺失或为空 ⇒ **不挂任何 section**，绝不塞内置替身。
- 每次组装前重读，改了下一轮生效；读取失败保留上一份非空文本。

会话里选 `BMA3` 即生效。

## 托管 BMA3

托管模式在 Linux 的 DSH Web profile 中使用宿主原生 MCP 客户端，已验证 DSH `0.2.2-rc.3`。profile 的
`bma3_runtime` 工具支持 `status`、`start` 和 `stop`。显式 `stop` 会跨宿主重启保留。

新版 JAR 会让托管 DSH 以 `bma3` 自动连接日常入口。旧 JAR 继续连接它原来的全量入口。
`bma3_runtime status` 中的 `mcpSurface` 显示当前入口，`mcpOpsUrl` 显示可以按需挂载的额外工具入口。

### Linux x86_64 整合包

构建后从本仓库运行：

```bash
dsh plugin --profile web add "$PWD/dist/mosire-bma3-dsh-managed-0.0.9-fix1.tgz"
dsh --profile web
```

首次启动 profile 会校验并安装内置镜像，启动 BMA3 并自动接通 MCP 与 Inbox。
每个 profile 默认使用 `$DSH_HOME/bma3/instances/<profile>/`，可用下列同一个
`bma3-inbox` 行的 `rootDir` 指定绝对目录。整合包的 `source` 为 `bundled`。
升级会保留配置、数据库和 Inbox 状态。

### Windows x64 整合包

在 DSH Desktop 的会话中，用 `plugin_manager` 的 `install_bundle` 动作安装本地
`dist/mosire-bma3-dsh-managed-0.0.9-fix1-windows-x64.tgz`，传入安装包的绝对路径。
DSH Desktop `0.2.2-rc.5` 已验证返回 `application: applied` 并直接生效，无需重启。

Windows DSH Web 用户可以在 PowerShell 使用 CLI 安装到对应的 Web profile：

```powershell
dsh.cmd plugin --profile web add "$PWD\dist\mosire-bma3-dsh-managed-0.0.9-fix1-windows-x64.tgz"
```

整合包内置 Java 21，激活后自动接通 MCP 和 Inbox，可用 `bma3_runtime` 的 `status` 动作查看就绪状态。
Windows 候选包以 `pwsh -File packaging/build-dsh-bundle-windows.ps1` 构建。

### 用普通插件托管本机 JAR

安装普通插件包。在目标 profile 的 `cordis.patch.yml` 中覆盖 `bma3-inbox` 行：

```yaml
- id: bma3-inbox
  config:
    module: inbox
    source: local-jar
    javaPath: /usr/bin/java
    jarPath: /opt/bma3/bma3-app-0.0.9-fix1.jar
    configRoot: /opt/bma3/run
    autoStart: false
```

`configRoot` 下应已有 `config/app.yaml`，配置使用 `127.0.0.1` 和 token 鉴权。
启动 profile 后可使用 `bma3_runtime(action="start")`，也可启用 `autoStart`。
已由外部服务启动的实例继续使用下面的连接方式，其启停由外部服务负责。

## 配置 OneBot 凭据

托管模式和独立 JAR 共用 BMA3 的 `bma3_credentials` 工具。Agent 可用 `set`（`name`、`value`）保存或替换
OneBot access token。使用过旧版 Windows 启动器的用户可用 `import_windows_onebot`（`name`）导入本机已有凭据。
例如先保存名为 `snowluma` 的凭据，再用 `set_config` 在 `qqconnect.accounts` 中设置账号的
`tokenRef: snowluma`，接着调用 `qq_account_list` 确认连接。凭据写入后会重连使用该引用的账号。
`list` 查看凭据名称和来源，`remove` 撤销指定凭据及对应账号连接。已有账号可继续使用 `tokenEnv`。

## 连接独立运行的 BMA3

jar 提供标准 MCP Streamable HTTP，连接由 DSH 自带的
`@deepseek-ai/dsh-mcp-client` 完成（D-066）。

### 端点与凭据从哪来

| 事实 | 位置 |
|---|---|
| MCP 端点 | `<config 目录>/config/app.yaml` 的 `server.port` + `server.mcpPath`（默认 `8765` + `/mcp`） |
| **MCP token** | **`<config 目录>/.bma3env` 的 `MCP_TOKEN`** —— 不是 `dataDir` 下，也不是环境变量 |
| Inbox token | 同一文件里的 `INBOX_TOKEN`（两个 token 相互独立） |
| 鉴权模式 | `security.auth: token`（默认，要 Bearer）/ `none`（`--no-auth`，仅回环，不需要 header） |

默认 `/mcp` 提供全量工具，`/mcp/daily` 提供日常收发、查询、Inbox 和记忆工具，`/mcp/ops` 提供其余工具。
自定义 `server.mcpPath` 时在该路径下使用 `daily` 或 `ops`。三个入口共用同一个监听端口和鉴权方式。
日常与额外入口可以一起挂载且工具不重复。全量入口适合单独挂载；不同名称接同一全量入口会产生重复工具。

jar 启动时自动写这个文件（0600、原子写、存在即复用）。**不要**再读 `server.tokenEnv`：
那个键 0.0.6 仍被解析但已不参与鉴权。

### 一次性配置（推荐）

在 `~/.dsh/profiles/<name>/cordis.patch.yml` 末尾加：

```yaml
- insert:
    - id: bma3-mcp
      name: "@deepseek-ai/dsh-mcp-client"
      # jar 从没起过（没有 .bma3env）就不挂这一行，免得留一个必然失败的悬空连接
      disabled: !!js "!process.getBuiltinModule('node:fs').existsSync('/opt/bma3/run/.bma3env')"
      config:
        serverName: bma3
        transport: streamable-http
        url: http://127.0.0.1:18765/mcp        # 换成你的 port + mcpPath
        headers:
          # 组装期从 jar 自己的 .bma3env 读 token：不进对话、不进仓库、不用手抄
          Authorization: !!js '`Bearer ${/^MCP_TOKEN=(.*)$/m.exec(process.getBuiltinModule("node:fs").readFileSync("/opt/bma3/run/.bma3env","utf8"))[1].trim()}`'
        failOnStartupError: false
        reconnect:
          initialDelayMs: 1000
          maxDelayMs: 30000
          # 默认 10 次 ≈ 2 分钟；给足 8 小时，jar 晚起也能接上
          maxAttempts: 1000
```

要点：

- `!!js` 里**不能用 `require`**，要用 `process.getBuiltinModule('node:fs')`（宿主的 `!!js` 沙箱里没有 `require`）。
- 新行必须写在 `- insert: [...]` 里；顶层平铺的 `id` 是"改已有行"，会报 `entry "xxx" not found`。
- `auth: none` 时删掉 `headers` 整段即可（不要把空 token 塞进去，会变成 `Authorization: Bearer `）。
- **jar 没起会怎样**：DSH 照常启动，这一行按 `initialDelayMs` 翻倍退避重试，**预算耗尽后永久放弃**
  （默认 10 次 ≈ 2 分钟），之后 jar 起来了也**不会自愈**，要重启 DSH。所以把 `maxAttempts` 调大，
  或者让 jar 先起（systemd `After=`/`Requires=`）。

### Agent 侧自查（没有一次性配置时才需要）

```bash
pgrep -af 'bma3-app.*\.jar'          # → 拿 --config /path/to/root
sed -n 's/.*port: \([0-9]*\).*/\1/p' /path/to/root/config/app.yaml
sed -n 's#.*mcpPath: *\([^,}]*\).*#\1#p' /path/to/root/config/app.yaml
sed -n 's/^MCP_TOKEN=//p' /path/to/root/.bma3env
```

然后让宿主加同一行（或让运维加）。**不要把 token 抄进对话当参数**：它会被重发给模型供应商
每一轮（D-060 只放行"落进运行期日志"，不等于"每轮出境"）。

### 验证

连上后工具以 `mcp__bma3__*` 出现（完整配置约 68 个：`inbox_*`、`qq_*`、`xvfb_*`、`get_config`/`set_config`、
`bma3_watchdog_*`）。最快的自检是调一次 `mcp__bma3__inbox_status`，返回 JSON 即通。

### 常见故障

| 症状 | 原因 | 处理 |
|---|---|---|
| `unknown tool "mcp__bma3__*"` | 行没挂 / 连接失败 / jar 没起 | 看 MCP 面板状态；确认 `.bma3env` 存在；重启 DSH 重新求值 |
| MCP 请求 401 | token 取错（用了旧 env token 或 MCP/Inbox 混用） | 只认 `.bma3env` 的 `MCP_TOKEN` |
| `serverName "bma3" is already in use` | 另有一条 `mcp-client` 行用了同一 `serverName` | 同一实例保留一条主连接；只给独立实例或额外工具入口使用另一个名字 |
| 第二实例起不来 `已有 BMA3 实例运行中` | 单实例锁（同一 `dataDir`） | 先停旧实例，或换 `dataDir` |

## 媒体：OCR / ASR（DeepSeek 官方接口）

预设里挂了一行 `media`（`lib/media.js`），给 BMA3 会话三个工具：

| 工具 | 端点 | 说明 |
|---|---|---|
| `bma3_media` | 本地路由 | 把 `qq_media_view` 的 path 按 auto/image/document/audio 路由到 OCR/ASR；无 key 返回结构化失败 |
| `bma3_ocr` | `POST https://api.deepseek.com/v1/tools/ocr` | PDF/DOC/DOCX/PPT/PPTX/PNG/JPEG/WebP/GIF/BMP/TIFF，按页返回文本；`purpose` 默认 `text_recognition` |
| `bma3_asr` | `POST https://api.deepseek.com/v1/tools/asr` | 音频转写；先用 ffmpeg 转 16 kHz 单声道 16 位 PCM WAV，最长 120 秒 / 8 MiB |

- key 从 DSH `credentials` 的 `DEEPSEEK_API_KEY` 取（缺省退回同名环境变量），缺了就返回结构化错误、**不发起请求、不回显密钥**。
- 语音文件本身由 jar 下载：`qq_media_view` 对 `record` 段调用即落到 downloads（`.amr`/`.mp3`/`.ogg`/`.wav`/`.silk`），再把返回的 `path` 交给 `bma3_media`（音频会自动转 ASR）。
- 纯文本模型没有视觉能力时，先 `qq_media_view` 落盘，再 `bma3_media(filePath)`；没有 OCR key 就明确返回失败，不要根据文件名猜内容。
- 计费随 DeepSeek 官方：OCR 1.5 元/1000 页、ASR 0.78 元/小时（限时 5 折）。
- 出境边界：**只有 Agent 显式调这些工具时，被指定的那一个文件才发往 `api.deepseek.com`**；插件不批量、不缓存。
- `bma3_media` 是 BMA3 preset 工具，不在 MCP 的 `mcp__bma3__*` 列表里；老会话可能保留旧 preset 行快照，需要新 BMA3 会话或宿主重载。
- TTS 不做（D-068）。

## Inbox

```yaml
- id: bma3-inbox
  name: '@mosire/bma3-dsh'
  config:
    module: inbox
    baseUrl: http://127.0.0.1:18766
    inboxId: bma3
    envFile: /opt/bma3/run/.bma3env    # jar 生成的同一份文件，读 INBOX_TOKEN
    autoStart: true
    heartbeatMs: 15000
    # stateFile: ~/.dsh/storages/bma3-inbox.json
```

- 一个 inbox 一条 SSE；`source` 只是条目，不存在 per-source 订阅（红线 11）。
- token 从 `envFile` 指向的 `.bma3env` 读 `INBOX_TOKEN`；读不到就不带 Authorization 头发请求。
  （0.0.5 的 `tokenFile` / `tokenEnv` 两个键已删除，写了也不会生效。）

绑定会话刚收到一批 Inbox 预览时，如果 `deepseek-official` 在第一步返回 `Content Exists Risk`，
插件通过宿主钩子把该条预览替换为仅含批次标识的通知，并在同一步重试一次。若再次被拒，
插件暂停自动唤醒。用 `/inbox status` 查看暂停状态，用 `/inbox resume` 恢复。原始会话事件仍可追溯，
原批次 ACK 按正常投递流程确认。功能需要宿主支持 `agent/request-error` 和单条上下文替换。

### 状态文件、锁与升级顺序

| 文件 | 作用 |
|---|---|
| `stateFile`（配置里的路径，默认 `~/.dsh/storages/bma3-inbox.json`） | **v1 路径**，只用于首次迁移读取，本版不再写 |
| `state.v2.json`（上面路径的确定性兄弟文件） | 真正写入的 v2 快照（0600、临时文件 + fsync + rename） |
| `state.v2.json.lock`（0600） | 每个状态文件的**独占锁**，在首次读状态前用 `O_EXCL` 取得；含实例 token 与 inode 校验 |

- 快照只含 sessionId、绑定、暂停/stop 意图、水位、数据库身份、待 ACK / 注入意图的
  **ID 与 revision**。**不含面板正文、token 或 Authorization**。
- 存在但损坏/版本不受支持的 v1 或 v2 **不会被当成首次安装**，也不会被清空或重写；客户端
  fail-closed，`/inbox status` 会显示原因。
- 从有效 v1 迁移时保留 sessionId、水位和暂停/stop 意图；**不会伪造历史 item IDs**，所以升级前
  已经丢失 ACK 的旧条目仍需人工核对。
- `persistState: false`（或状态无法持久化）时**禁用注入与 ACK**：没有可靠落盘就不产生跨系统副作用。
- 锁文件被替换/删除后，旧实例**不再写盘、不再 ACK**，且**不会删除别人的锁**；控制操作会明确报错。
- `bind`/`unbind`/`pause`/`resume`/`start`/`stop`/`restore` 的持久化失败会回滚内存意图并抛出
  `state_write_failed` 类错误（命令/工具返回失败），不会伪装成已保存；重启后仍以旧持久化意图为准。
  `restore` 把**新绑定与启动意图放在同一个原子快照**里：若这一写失败，内存和磁盘都不变；
  成功后不再有第二次“只写启动状态”的落盘。因此不会出现“操作报告失败，但磁盘已换绑定”的
  静默重启绑定变化。
- 目标配置的 `inboxId` 与 baseUrl 的规范化 SHA-256 指纹会随 pending/注入意图保存。**原始 baseUrl
  不落盘**；带 userinfo/query/fragment 或非 http(s) 的 baseUrl 直接 `config_target_unsupported`
  fail-closed，不丢弃这些成分后继续请求。目标指纹算法只对已验证无 userinfo/query/fragment 的
  `protocol + host + port + path` 做 SHA-256。旧 pending 没有目标指纹时按
  `pending_target_unverified` fail-closed，需人工 `discard` 或按原目标重建。
- SSE panel 绑定到**连接实际来源**（连接时的 inboxId/目标指纹）；config 目标改变后旧连接
  的 panel 只留 `stale_connection_target` 诊断，不注入/ACK。连接时会尽力读取 `/runtime` 的
  `processId` 作为实例证据。**SSE push、GET 恢复的 pendingPush 和 ACK 后的 fresh GET 都携带
  已证明实例，并在注入前再次读取 `/runtime` 比对**：
  - `processId` 与已证明实例不同 → `stale_connection_instance` fail-closed：保留 `pendingPush`，
    不自动注入/ACK。**合法服务端重启必然改变 `processId`，这不是永久锁死**：确认是正常重启后，
    用 `/inbox ack` 显式重试一次；该次人工动作会**以当前运行实例作为新基线**，随后仍要求
    identity/目标/会话/revision/item IDs/frozen 精确匹配，匹配才注入，不匹配不会注入。也可
    `/inbox discard confirm` 放弃（可能丢失该 push）。
  - `/runtime` 超时、网络错误、5xx 或响应体丢失是**暂时不可达**，不是实例不符：保留 push，
    按 `verifyRetryBaseMs`/`verifyRetryMaxMs` 有界退避；`stop`/`start`/`restore` 后仍会恢复。
  - `/runtime` 返回 200 但不再提供 `processId`（或无法安全判定）→
    `connection_instance_unverified` fail-closed：**不会注入，`/inbox ack` 也不能绕过**（没有可
    采用的实例证明）。需先让服务端提供 `processId` 再 `/inbox ack`，或核对后
    `/inbox discard confirm`。
  - `processId` **每次服务端启动都会变**，也不持久化；插件只在“本进程此前已证明过原实例”时
    据此识别同 URL/同数据库身份的实例替换。插件重启后没有持久化实例证据，服务端不提供
    `processId` 时也无法区分克隆。**显式重试只改实例基线，不能识别同身份克隆/回滚**：克隆库由
    新进程提供时与正常重启在 `processId` 上不可区分；不要据此声称能检测全部克隆/回滚，
    可疑时按服务端流程停插件并离线轮换身份（spec 明确说明身份字段不能做到）。

> **升级顺序（必须）**：先完全停掉旧插件实例 → 再升级/启动新服务端 → 最后加载本插件。
> 旧插件不识别新锁、v2 路径和 guarded ACK；新旧实例重叠写入不受支持。旧实例只会写 v1，
> 因此也不会覆盖 v2 证据，但它的 ACK 会被新服务端 fail-closed 拒绝。
>
> 数据库备份/恢复若克隆了 `inbox.db` 的身份，必须在**停插件**后按服务端流程离线轮换身份。
> 仅凭身份字段不能自动识别全部回滚或克隆。

#### 离线备份、恢复与回滚

1. **先停止旧插件和所有 BMA3 服务端写者**，确认它们不会自动重启。数据库与插件状态应在同一停写窗口内备份；插件状态可能含内部 ID，应保护访问权限。
2. 对 `dataDir` 中的 `bot.db` 和 `inbox.db` 分别使用 SQLite 一致性备份（例如 SQLite `.backup`），不要在 WAL 模式下只复制主数据库文件而忽略未合并的 `-wal` / `-shm`。保留与备份匹配的旧版程序和插件；备份及恢复后应检查数据库完整性。
3. 恢复或克隆 `inbox.db` 会保留原身份，**不能靠身份自动发现克隆或回滚**。保持插件与服务端停止，用新服务端程序执行 `--config <配置目录> --rotate-inbox-identity`：命令会取得 `dataDir` 的单实例锁、轮换身份后退出，不启动服务。旧身份下的待 ACK 记录不能直接套到新身份，需人工核对并处理。
4. 恢复服务时遵循上述成对升级顺序。**回滚旧版**须停掉新旧写者，并同时恢复兼容的旧版程序、旧插件和升级前的匹配备份；旧版无法表示新投递状态，也不理解 v2 记录。不要让旧版直接写入新库或与新插件同时运行。

本节是操作约束，不代表备份、恢复、掉电或升级/回滚已在真实部署中验证。

### 待 ACK 恢复

1. `followup` **之前**先原子落盘一个无正文的注入意图（目标会话、数据库身份、panel/push
   revision、实际显示的 item IDs、目标 inboxId 与 baseUrl 指纹）。
2. `followup` 成功后才把意图原子替换为 `pendingAck` + 注入水位，然后才发 ACK。
3. ACK 必须带原 `{revision, itemIds, inboxIdentity}`，且只有 `complete=true`、逐条证明数组
   恰好覆盖请求集合、`missingItemIds` 为空、身份回显一致、`ackedRevision` 为**非负安全整数**时
   才清除记录。非法/缺失 `ackedRevision` 保留原 pending（ID/revision/identity 不变），诊断
   `ack_revision_invalid`，不自动重试。
4. 网络错误/5xx/响应丢失按**有界指数退避**重试**原请求**；ACK、身份校验 GET 和新鲜面板 GET
   都有**独立硬超时**（不依赖对端遵守 AbortSignal，覆盖响应体 `json()`）并可被 stop/dispose
   取消（`ackTimeoutMs`、`panelTimeoutMs`、`ackRetryBaseMs`/`ackRetryMaxMs`、
   `verifyRetryBaseMs`/`verifyRetryMaxMs`）。409（身份、条目缺失/未推送、revision 冲突）和
   部分证明保留记录并停止自动重试。
5. 待 ACK 期间不注入新面板；确认 ACK 并持久化删除后，先 GET 一次新鲜 `/panel` 校验身份与
   已确认条目。fresh GET 固定使用 ACK 记录对应的 inboxId/目标指纹；当前配置的 inboxId/baseUrl
   变了就 fail-closed（`fresh_inbox_id_mismatch`/`fresh_base_url_mismatch`），不重定向。
   - **SSE push 与 `pendingPush` 精确恢复**：仍要求整批条目同一 `frozenRevision=pushRevision`；
     混合/缺失证明只留诊断，不会拆批或错误 ACK。
   - **live `/panel` 恢复快照**：GET 的是当前 coherent 面板，不是新 push。若所有条目都显式
     `frozenRevision>0`（允许混合 revision），或旧服务端整批省略该字段，可作为一次恢复快照注入
     并由 guarded ACK 确认；显式 `frozenRevision=0`、部分缺失或非法 revision 拒绝注入。
   - **重试**：fresh verification 的 proof 失败属于可重试观察，必须保留 `required=true`、写入
     有限 `nextAt` 并按 `verifyRetryBaseMs`/`verifyRetryMaxMs` 退避重试；不得再出现
     `done + nextAt=null` 的永久卡死。ACK 409 或服务端拒绝未推送条目仍保留原记录，不自动重放。
6. `stop` / `unbind` / `detach` 会停止新的自动 ACK 重试并取消在途 ACK/GET（崩溃/对端不接受
   取消时不能保证撤回已发出的字节）；`start` / `bind` 后恢复。`pause` 只暂停注入，ACK 恢复继续。
7. `followup` 抛错或成功后快照写失败会留下「注入意图」这一不确定记录：不自动重投、不 ACK，
   必须人工核对后处置。`followup` 与本地持久化是两个系统，**不承诺跨系统恰好一次**。

人工入口（只读摘要看 `/inbox status` 或 `bma3_inbox` 的 `status`）：

- `/inbox discard confirm` 或 `bma3_inbox` `{action:"discard", confirm:true}`：放弃待 ACK 记录/
  注入意图。⚠ 可能导致重复注入，或留下未确认的服务端条目。放弃不确定 intent 时会**推进旧 push
  水位**，明确牺牲该条以避免同一 push 自动重放；若实际未注入，这一条会丢失。
- 已验证但未注入的 push 会以 `pendingPush`（仅 inboxId、目标指纹、目标会话、revision、item IDs）
  持久化。重启后安全恢复：重新 GET 当前面板并**精确匹配** pushRevision/panelRevision/item IDs/
  frozen 证明/目标/会话，且用已证明实例校验 `/runtime`；匹配才注入。不匹配只留
  `pending_push_unverified` 诊断，不自动重放、不错误确认。**服务端正常重启会改变 `processId`**，
  自动恢复会以 `stale_connection_instance` 停住；确认是正常重启后需人工 `/inbox ack` 一次，
  以当前实例为新基线重新校验（仍要求上述精确匹配）。**没有目标会话的记录无法自动恢复**，
  不会改投到别的会话；目标/会话不一致时先恢复绑定，再用 `/inbox ack` 显式重试；`discard`
  可显式放弃并警告丢失。
- 目标配置变化导致 ACK fail-closed 后，**恢复原配置不会自动 ACK**；需显式 `/inbox ack`
  （或 tool `ack`）重试原记录，或 `/inbox discard confirm` 放弃。不会静默重发。
  旧 pending 缺少目标指纹（`pending_target_unverified`）时恢复配置也无法安全重试，只能
  `discard` 后重建；`/inbox status` 会给出对应提示，不会误导为“恢复配置即可 ack”。
- `/inbox ack` 在没有 pendingAck 但存在 `pendingPush` 时，会显式重新做一次恢复校验（不改变原
  ID/revision/会话）；对实例不符的记录，这一次人工动作会把**当前运行实例**作为新基线，但
  仍要求 identity/目标/会话/revision/IDs/frozen 精确匹配，无法识别同身份克隆/回滚。
  已停止（先 `/inbox start`）、校验在途、持久化不可用、服务端 200 缺 `processId` 时不会发出
  或完成恢复：命令返回 error 和原因，不会说成“已请求重试”，也不会自动重放。
- `/inbox start` 在 unsupported target、持久化不可用/锁或状态阻断时返回 error，不会误报
  “SSE 已启动”；若 SSE 已运行但仍有待 ACK/身份等阻断，会明确说“已启动，但仍有阻断”。
- `/inbox accept-identity confirm` 或 `{action:"accept-identity", confirm:true}`：在确认换库/轮换
  后接受新的 `inboxIdentity`。旧数据库的水位不可沿用，接受时会**重置 push/ACK 水位为 0**，
  并立即重新 GET 当前新库面板，补上被身份变化挡下的当前 push；旧库未确认条目仍需人工核对。
  有未处置记录时会拒绝。目标 baseUrl 含 userinfo/query/fragment 时该操作也会安全失败。
- `deferredPush` 与 `freshVerification`（尝试次数/安全错误码）会随 v2 快照保存，重启后
  `/inbox status` 仍可看到；不会因重启而静默消失。
- `inbox_get` 现在显式返回 `frozenRevision`（未推送为 0），便于区分「已推送待 ACK」与「尚未冻结」；
  该字段是诊断/证明元数据，不是 Agent 可编辑的业务状态。
- 错误文本只保存有界分类码（如 `ack_timeout`、`ack_http_409_...`、`state_write_failed`、
  `pending_inbox_id_mismatch`、`pending_base_url_mismatch`、`config_target_unsupported`），
  不保存远端响应正文、异常 message、原始 baseUrl/userinfo/query、面板正文或 token。
- 空面板（含初始 `pushRevision=0`）是合法状态更新，不注入、不 ACK；只有存在实际显示条目时才
  要求 `pushRevision > 0` 且所有条目同一 `frozenRevision`。所有 revision 必须是安全非负整数。
- 崩溃遗留的 `.lock` **不会自动抢占**。确认没有旧 DSH 实例仍持有该状态文件后，人工删除
  `state.v2.json.lock` 再启动；否则客户端会 fail-closed 并报告锁被占用。

- 工具：`bma3_inbox`（status/bind/unbind/restore/pause/resume/start/stop/ack/discard/accept-identity）。
  - 未显式配置 `sessionId` 时，首次真正选中 idle 会话会**自动持久化** `targetSessionId`；heartbeat/status
    随即显示 bound=true。重启后按 v2 state 自动 `restore()`，不会只把绑定留在内存。
  - legacy `pendingPush.targetSessionId=null` 会在首次选定目标时与绑定一起原子补写；已有目标不匹配仍
    fail-closed，绝不会改投另一个 idle 会话。
  - `bma3_inbox` 注册失败不会拖垮 inbox/SSE/ACK；插件会重试 profile 注册，并在拿到具体 agent 时尝试
    `agent.ctx.tools.register`。`status.toolRegistration` 显示 `registered/attempts/lastError/scopes`。
- 诊断兜底：
  - MCP 工具 `inbox_diagnose`：一屏返回版本、runtime/MCP 启动 id、inbox 状态、插件 heartbeat、
    deferredPush/freshVerification/toolRegistration 摘要和 pending control；只读，不含正文/凭据。
  - MCP 工具 `inbox_plugin_control`：`status` 查看心跳；`restore` 排队让插件恢复已保存的会话；
    `remind` 排队发送短提醒；`recover confirm=true` 清除 stale pendingPush/deferredPush 并重试当前面板。
    只操作既有 control 通道，不直接 ACK、改身份或写消息。
  - 插件 heartbeat 的 `diagnostics` 字段只带版本、注册状态、deferred/fresh 元数据与计数，服务端可在
    `/plugin` 与诊断工具中读取。
- **stale pendingPush 自愈**：fresh GET 若发现 pendingPush 的 item 已全部从面板消失，且服务端
  `ackedRevision` 已越过该 pendingPush 的 panelRevision，会清除 pendingPush/deferredPush 并继续
  处理当前面板。若仍有 item 可见或 ackedRevision 未越过，保持 exact proof/fail-closed。
  人工可在确认服务端条目不存在后使用 `/inbox recover confirm` 或 MCP
  `inbox_plugin_control {action:"recover",confirm:true}`。

## 装到 profile

插件包自带 `cordis.patch.yml`，DSH 加载 profile 时会自动并入；也可以照上面的 YAML 手写进
`~/.dsh/profiles/<name>/cordis.patch.yml`。
