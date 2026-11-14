# @mosire/bma3-dsh

BMA3 的 DSH 薄插件。**只做两件事**：

| 模块 | 作用 |
|---|---|
| `inbox` | 把 BMA3 Inbox 注入 DSH 会话：SSE 唤醒、revision 推送、`/inbox` 斜杠命令 |
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

预设挂的 6 行（就是会话默认可见面）：

| 行 | 给什么 |
|---|---|
| `persona` | 你的 system prompt（遮蔽 `deployment:persona-prefix`） |
| `tool-bash` / `tool-pwsh` | `bash`（Windows 上是 `pwsh`） |
| `tool-fs` | **只有 `read_image`**（`read`/`write`/`edit` 关；文本读写走 bash） |
| `tool-subagent-control` + `tool-subagent` | 子 Agent（`agentPreset: minimal`） |
| `tool-plugin-manager` | 宿主有 `profileContext` 才挂；这是 Agent 自己挂插件的门 |

要更宽的面就把行加进 `presetDefinition()` 的 `toolRows()`；要收窄就删行。**不需要改任何策略**。

人设语义：

- `complete: true`（默认）：这份文本**就是**完整 system prompt，整份替换 DSH 默认提示词。
- `complete: false`：退化成"插在 persona 前缀位置"，保留 DSH 默认提示词。
- 文件缺失或为空 ⇒ **不挂任何 section**，绝不塞内置替身。
- 每次组装前重读，改了下一轮生效；读取失败保留上一份非空文本。

会话里选 `BMA3` 即生效。

## 连上 bma3 jar（0.0.6 的正确方式）

插件**不做桥接**（D-066）。jar 是一个标准 MCP Streamable HTTP 服务，连接由 DSH 自带的
`@deepseek-ai/dsh-mcp-client` 完成——**写一行 profile 配置即可，不需要 Agent 每次去发现**。

### 端点与凭据从哪来

| 事实 | 位置 |
|---|---|
| MCP 端点 | `<config 目录>/config/app.yaml` 的 `server.port` + `server.mcpPath`（默认 `8765` + `/mcp`） |
| **MCP token** | **`<config 目录>/.bma3env` 的 `MCP_TOKEN`** —— 不是 `dataDir` 下，也不是环境变量 |
| Inbox token | 同一文件里的 `INBOX_TOKEN`（两个 token 相互独立） |
| 鉴权模式 | `security.auth: token`（默认，要 Bearer）/ `none`（`--no-auth`，仅回环，不需要 header） |

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

连上后工具以 `mcp__bma3__*` 出现（约 40 个：`inbox_*`、`qq_*`、`get_config`/`set_config`、
`bma3_watchdog_*`）。最快的自检是调一次 `mcp__bma3__inbox_status`，返回 JSON 即通。

### 常见故障

| 症状 | 原因 | 处理 |
|---|---|---|
| `unknown tool "mcp__bma3__*"` | 行没挂 / 连接失败 / jar 没起 | 看 MCP 面板状态；确认 `.bma3env` 存在；重启 DSH 重新求值 |
| MCP 请求 401 | token 取错（用了旧 env token 或 MCP/Inbox 混用） | 只认 `.bma3env` 的 `MCP_TOKEN` |
| `serverName "bma3" is already in use` | 另有一条 `mcp-client` 行用了同一 `serverName` | 改 `serverName` 或删掉重复行 |
| 第二实例起不来 `已有 BMA3 实例运行中` | 单实例锁（同一 `dataDir`） | 先停旧实例，或换 `dataDir` |

## 媒体：OCR / ASR（DeepSeek 官方接口）

预设里挂了一行 `media`（`lib/media.js`），只给 BMA3 会话两个工具：

| 工具 | 端点 | 说明 |
|---|---|---|
| `bma3_ocr` | `POST https://api.deepseek.com/v1/tools/ocr` | PDF/DOC/DOCX/PPT/PPTX/PNG/JPEG/WebP/GIF/BMP/TIFF，按页返回文本；`purpose` 默认 `text_recognition` |
| `bma3_asr` | `POST https://api.deepseek.com/v1/tools/asr` | 音频转写；先用 ffmpeg 转 16 kHz 单声道 16 位 PCM WAV，最长 120 秒 / 8 MiB |

- key 从 DSH `credentials` 的 `DEEPSEEK_API_KEY` 取（缺省退回同名环境变量），缺了就返回结构化错误、**不发起请求、不回显密钥**。
- 语音文件本身由 jar 下载：`qq_media_view` 对 `record` 段调用即落到 downloads（`.amr`/`.mp3`/`.ogg`/`.wav`/`.silk`），再把返回的 `path` 交给 `bma3_asr`。
- 计费随 DeepSeek 官方：OCR 1.5 元/1000 页、ASR 0.78 元/小时（限时 5 折）。
- 出境边界：**只有 Agent 显式调这两个工具时，被指定的那一个文件才发往 `api.deepseek.com`**；插件不批量、不缓存。
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
- 写盘内容只有 sessionId、水位、暂停标志。
- token 从 `envFile` 指向的 `.bma3env` 读 `INBOX_TOKEN`；读不到就不带 Authorization 头发请求。
  （0.0.5 的 `tokenFile` / `tokenEnv` 两个键已删除，写了也不会生效。）
- 工具：`bma3_inbox`（status/bind/pause/resume/start/stop/ack）。

## 装到 profile

插件包自带 `cordis.patch.yml`，DSH 加载 profile 时会自动并入；也可以照上面的 YAML 手写进
`~/.dsh/profiles/<name>/cordis.patch.yml`。
