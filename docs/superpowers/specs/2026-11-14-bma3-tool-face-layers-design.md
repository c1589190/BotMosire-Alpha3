# BMA3 工具面分层 + token 自举（0.0.6 设计稿）

> ⚠️ **已被取代（2026-11-14，D-066）**：工具面分层、`bma3_*` 自检工具、预设内 MCP 桥接与
> per-agent 组合**全部删除**。插件收窄为两件事：Inbox 注入 + `bma3` 预设注入自定义系统提示词。
> jar 的 MCP 由 Agent 用 DSH 自带 `mcp_connector_configure` 自行配置连接。
> 本稿保留为决策历史，**其中 §1–§7 的工具面部分不再具有约束力**；
> token 自举（`Bma3Env` / `--no-auth` / `--rotate-token` / 单实例锁）与 D-058/D-060 仍然有效。

> **状态**：草案（2026-11-14），待用户确认后进实现。
> **触发裁定**：D-058（不设出网闸门）· D-059（默认窄面）· D-060（红线 2 收窄）· D-061（单实例）· D-062（允许无 token 启动）· D-063（token 归 bma3）。
> **取代**：`dsh-plugin/lib/self.js` 现有「`DEFAULT_TOOL_GROUPS` 白名单 + `resolveToolGroups` + 业务工具名单」策略层（2026-11-10 调查 §1.3 的裁定本来就不该用 restrict 做默认组）。
> **证据基线**：`docs/superpowers/reports/2026-11-14-bma3-dsh-tool-face-survey.md`。

---

## 1. 需求原话

| 出处 | 原话 |
|---|---|
| D-059 | 「默认面肯定是窄的，只加bash、bma3的基础检查工具、mcp的配置和连接工具」 |
| D-058 | 「对啊，就是要给Agent任意外连的能力，Agent是用户！用户！」 |
| D-062 | 「那就是无token启动，Agent想这么干就这么干，直接无脑连」 |
| D-063 | 「插件的那个删了，token统一交给bma3维护」 |
| D-061 | 「缺口1：是的，加单实例保护」 |

补充（同次会话，未编号的过程裁定）：业务工具清单与可见性**由 bma3 的 jar 与 MCP 暴露面维护**，插件不得持有影子清单。

---

## 2. 目标与边界

**目标（一句话）**：装好 bma3、选 bma3 预设，Agent 在会话内即可自查 → 起 jar → 连 MCP → 按需打开工具面，全过程不需要人介入，也不需要 Agent 抄写 token。

**非目标**：
- 不做工具使用策略审批（D-054/D-058：边界在沙箱/容器，不在插件）。
- 不接管 DSH 的 MCP 客户端实现（复用 `@deepseek-ai/dsh-mcp-client`）。
- 不改变 jar 侧业务工具语义（`qq_*`/`inbox_*`/`xvfb_*`/`browser_*` 保持原样）。

---

## 3. 目标运行序列

**S1 正常（jar 已常驻）**

```
jar 启动 ──> 生成 <configRoot>/.bma3env（幂等）──> MCP:18765 / InboxHTTP:18766 就绪
DSH 加载 bma3 预设 ──> bma3_status 三态：env=ok · endpoint=ok · mcp=未连
Agent: bma3_connect ──> 挂 dsh-mcp-client（preset 作用域，读 .bma3env.MCP_TOKEN）
                     ──> mcp__bma3__* 进入注册表
Agent: bma3_tools enable group:mcp__bma3__ ──> 下一步可见业务工具
```

**S2 jar 未起**

```
bma3_status ──> endpoint=down，返回明确提示
Agent: bash 起 jar（同一配置根目录）──> 回到 S1
（jar 已被 systemd 起过 → 单实例锁让它立刻退出并报已有 pid）
```

**S3 无鉴权模式（显式开启，D-064）**

```
Agent 决定不要鉴权：bash bma3-app --config <configRoot> --no-auth
   （或 app.yaml: security.auth: none）
jar ──> 启动成功，WARN，/healthz 报 auth:none（仅回环，非回环被硬拒）
bma3_status ──> auth=none
bma3_connect ──> 不带 Authorization，直接连
```
对照：默认 `security.auth: token` 时**缺 token 仍 fail-fast**（与现状一致），不再隐式降级。

---

## 4. 契约

### 4.1 `.bma3env`

| 项 | 规定 |
|---|---|
| 位置 | `<configRoot>/.bma3env`（`--config` 指向的目录；可用 `security.envFile` 覆盖绝对路径）。**不用 cwd** |
| 内容 | `MCP_TOKEN=<hex>`、`INBOX_TOKEN=<hex>`（两个独立值，各自 48 hex 字符） |
| 权限 | `0600` |
| 写入 | `1600` 临时文件 + `ATOMIC_MOVE`；写失败只 WARN，`/healthz` 报 `envFile: degraded` |
| 幂等 | 存在且两个 key 均非空 → **原样复用，不重写**。轮换只能靠删文件或显式 `--rotate-token` |
| 缺失 | 允许缺 key：该端点进入无鉴权模式（见 4.5） |

### 4.2 单实例锁（D-061）

- 启动时对 `<dataDir>/.bma3.lock` 取 `FileLock`（`tryLock`）。
- 拿不到 → 打印已有实例 pid（读锁文件内容）→ 退出码非 0。
- 关闭时释放并删除锁文件。

### 4.3 工具面三层

| 层 | 定义 | 归属 | 默认可见 |
|---|---|---|---|
| **L1 原生** | `bash`、`bma3_status`、`bma3_tools`、`bma3_connect`、`bma3_inbox`（桥接自身状态） | bma3-dsh 自己拥有（可枚举） | ✅ |
| **L2 业务** | jar 通过 MCP 暴露的 `mcp__bma3__*` | **jar 的 `tools/list`**（插件不枚举） | ❌ 连上并 enable 后可见 |
| **L3 可选** | DSH 侧已挂载但默认隐藏的：`web_search`/`web_fetch`、`read_image`/`read`/`write`/`edit`、`subagent` 一族、`mcp_connector_*` | 宿主包（`@deepseek-ai/dsh-tool-*`） | ❌ 按需 enable |

**关键约束**：L3「能加回来」的前提是**已挂载**——base 层 `tool-bash`/`tool-fs`/`tool-subagent`/`tool-web` 的全局行都是 `disabled: true`，只在 preset 内挂。故 preset composition 必须**挂得宽、显示得窄**。

### 4.4 三个 L1 工具的动作面

| 工具 | 动作 | 说明 |
|---|---|---|
| `bma3_status` | — | 三态：`env`（`.bma3env` 存在/两个 key 是否存在）、`endpoint`（`127.0.0.1:18765` 可达性 + `/healthz` 摘要）、`mcp`（`mcp__bma3__*` 是否已注册）；另报 `presetTools`/`lockedTools`（沿用现有字段）。**永不回显 token 值**，只报来源与存在性 |
| `bma3_connect` | `connect` / `disconnect` | `connect` 无参：读 `.bma3env` → 用 `hostToolEntry('@deepseek-ai/dsh-mcp-client')` 挂到 **preset 作用域**（`failOnStartupError:false`），返回 `{ok, toolCount, serverName}`；`disconnect` 卸载并清空命名空间 |
| `bma3_tools` | `list` / `enable` / `disable` / `reset` | `list` 按**来源**分组列出运行时注册表（`native`/`bma3-mcp`/`dsh`/`other`）与可见性；`enable|disable` 接受**来源型规则**（`group:mcp__bma3__`、`group:dsh-search`…），**不接受具体业务工具名** |

### 4.5 鉴权与就绪

- `security.auth: token`（默认）：`Authorization: Bearer <MCP_TOKEN>` / `Bearer <INBOX_TOKEN>`，比较用 `MessageDigest.isEqual`（现状已如此）；缺 token → 启动失败（现状）。
- `security.auth: none`（显式，`--no-auth` 等价）：跳过校验、记 WARN、`/healthz` 报 `auth:none`；客户端不带 `Authorization`（D-064）。
- 非回环 host：**始终拒绝启动**（`Bma3McpServer.java:152-154`、`InboxHttpServer.java:54` 现状即有，保持不变）。

### 4.6 心跳与 watchdog

- 插件 heartbeat 增两个字段：`tokenSource`（`env-file`/`none`）、`tokenOk`（最近一次是否 401）。
- `PluginHeartbeat`（Java record，`bma3-inbox/src/main/java/.../core/PluginHeartbeat.java:9-29`）需同步扩字段。
- watchdog 新增探针：`plugin_heartbeat_fresh`（>3× heartbeatMs 未更新即告警）、`token_mismatch`（heartbeat 报 `tokenOk=false`）。

---

## 5. 不变量

| ID | 不变量 |
|---|---|
| I1 | **token 单一来源**：只有 jar 生成 `.bma3env`；插件不生成、不持久化、不落盘 token |
| I2 | **插件不持有业务工具清单**：L2 的可见性只能由「来源规则 + 运行时注册表」决定，代码里不得出现 jar 的工具名枚举 |
| I3 | **已挂载面 ⊇ 可见面**：任何允许被 enable 的工具必须已在某个作用域注册 |
| I4 | **restrict 只吃已注册名**：allow/deny 必须在每次应用时从 `definitions(agent)` 现算；未知名字不得进入（否则整次抛错，`dsh-tools/src/index.ts:180-183`） |
| I5 | **作用域分层**：管理器与 L1 工具注册在 agent 作用域（豁免 restriction）；业务工具注册在 preset 作用域（可被 restriction 管到） |
| I6 | **无鉴权 = 显式 + 只在回环**：`auth:none` 必须由 `--no-auth`/`security.auth: none` 显式开启，且与"非回环拒绝启动"绑定，不可配置放宽（D-064） |
| I7 | **单实例**：同一 `dataDir` 同时只有一个进程 |
| I8 | **幂等**：`.bma3env` 存在即复用；重启不隐式轮换 token |
| I9 | **失败软化**：MCP 连不上、`.bma3env` 缺失、enable 规则指向空集，都不得让 preset 或 agent 创建失败 |

---

## 6. 验收判据

| ID | 判据 | 验法 |
|---|---|---|
| A1 | 首启生成 `.bma3env`，含两 key、权限 0600；二次启动内容**逐字节不变** | 单测 + 真机 |
| A2 | 删除后重启 → 重新生成且值与上次不同 | 真机 |
| A3 | `--no-auth` 启动 → `/healthz` 含 `auth:none` 且客户端不带 Authorization 可连；默认 `token` 模式缺 token → 启动失败（非 0 退出） | 真机 |
| A4 | 同 `dataDir` 起第二个实例 → 退出码非 0 且提示已有 pid | 真机 |
| A5 | bma3 会话首步可见面 = L1 ∪ {MCP 配置/连接工具}，无任何 `mcp__bma3__*` | 真会话 |
| A6 | `bma3_status` 三态与实际一致（含 jar 停掉后转 `endpoint=down`） | 真机 |
| A7 | `bma3_connect` 成功后 `mcp__bma3__*` 出现在注册表；`bma3_tools enable group:mcp__bma3__` 后**下一步**可见 | 真会话 |
| A8 | `disable` 后下一步不可见，且不产生 `UNKNOWN_TOOL` 之外的新错误 | 真会话 |
| A9 | jar 未起时 `bma3_connect` 返回结构化错误（不抛、不崩 agent） | 单测 |
| A10 | 无 token 时 `bma3_connect` 不带 Authorization 也能连上 | 真机 |
| A11 | heartbeat 含 `tokenSource`/`tokenOk`；watchdog 能对"心跳陈旧""token 不匹配"告警 | 单测 + 真机 |
| A12 | 工具集变化写进会话日志（`tool-addition`/`tool-removal`），恢复会话后可见面与日志一致 | 真会话 |

> 沿用仓库规矩：以上凡未真机跑过的，STATUS 一律写 `NOT_RUN`，不得写成 PASS。

---

## 7. 与既有代码的边界

**删除**（回归到"桥接器"定位）：
- `self.js`：`DEFAULT_TOOL_GROUPS`、`BASIC_BMA3_BASENAMES`、`SUBAGENT_BASENAMES`、`LOCKED_TOOL_BASENAMES`、`resolveToolGroups()`、`applyToolPolicy()` 中的业务工具过滤逻辑。
- `inbox.js`：`tokenFile`/`tokenEnv` 配置与读文件逻辑（D-063）。
- `agentconfig.json`：`defaultTools` 字段与相关校验（D-065）。
- `repairHostTools()` 的重挂行为（其存在理由是修 restrict 副作用；策略层重构后降级为纯诊断）。

**保留**：
- inbox SSE/绑定/注入/ack/heartbeat/`/inbox` 命令（桥接本职）。
- `bma3_self_status/reload/repair`（自检）；人设 loader。
- `host-tools.js` 的宿主树解析（新增 `@deepseek-ai/dsh-mcp-client` 与候选面工具包）。

---

## 8. 未决问题（2026-11-14 已全部关闭，见 D-065）

| ID | 问题 | 结论 |
|---|---|---|
| Q1 | `toolFace` 存哪 | **混合（C）**：`agentconfig.json.toolFace` 只作**初始默认**；会话内 `enable/disable` **只改内存**；`bma3_tools save` 才写回工作区。恢复会话回落到文件默认值，`bma3_status` 明示来源。动态工具排障交给 Agent 自己 |
| Q2 | 分组依据 | 先按**命名前缀**（`qq_*`/`xvfb_*`/`browser_*`/`inbox_*`/`bma3_*`）；agentlib 透传 MCP `annotations` 推后 |
| Q3 | 是否 `--rotate-token` | **做**：显式轮换，重写 `.bma3env` 两值；要求插件重连 |
| Q4 | 无鉴权下 watchdog 敏感动作 | **降级为只读告警** |
| Q5 | 默认窄面是否含 connector 的 configure/reconnect | **包含**：bma3 自己走 `bma3_connect`，任意 server 走 connector |

补充决议：`agentconfig.json.defaultTools` **删除**（v1 读到即忽略并 WARN），旧影子清单语义一并废弃。

## 9. 证据

- 工具面现状与差距：[`../reports/2026-11-14-bma3-dsh-tool-face-survey.md`](../reports/2026-11-14-bma3-dsh-tool-face-survey.md)
- 行为验证脚本：`tools/e2e-local/dsh-tool-face-probe.mjs`（宿主 `dsh-tools`/`dsh-scope` 真实作用域语义）
- token 现状：`Bma3App.java:128`（env 读取）、`:206-208`（MCP 与 Inbox HTTP 共用同一 token）、`InboxHttpServer.java:303-306`（常量时间比较）、`README.md:43`（`openssl` 手工生成）
- 没有单实例保护：`bma3-app`/`bma3-core` 内无 pidfile/FileLock（grep 无结果）
- 回环硬约束：`Bma3McpServer.java:152-154`、`InboxHttpServer.java:54`
- mcp-client 配置与语义：`@deepseek-ai/dsh-mcp-client/src/index.ts:161-200`（Config）、`:206-273`（apply/failOnStartupError）
- **真实 `tools/list`（2026-11-14 smoke，jar 0.0.5-hotfix2，关掉 xvfb/webconnect）**：40 个工具；名字全部合法（最长公开名 `mcp__bma3__qq_conversation_send_image` = 37 字符，上限 64）；`capabilities.tools.listChanged = true`
- **input schema 的 `minimum`/`maximum` 不构成阻塞**：DSH 只对 `output.schema` 做 `assertSupportedJsonSchema`（`dsh-tools/src/index.ts:136`），而 `dsh-mcp-client` 把 `inputSchema` 原样放进 `parameters`（`src/tools.ts:260`）。15/40 个工具用了 `minimum`/`maximum` ⇒ 仅意味着边界值必须由 jar 自己校验（现状即如此）
- **token 分离实测**：`MCP_TOKEN` 只过 MCP（Inbox 用它 → 401），`INBOX_TOKEN` 只过 Inbox HTTP（MCP 用它 → 401）
