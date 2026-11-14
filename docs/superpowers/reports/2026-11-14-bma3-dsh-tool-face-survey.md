# BMA3（`bma3` preset）Agent 工具面现状调查

> **类型**：调查 / 代码审计（**只作追溯，不作当前事实**；当前事实见 [`../../agents/STATUS.md`](../../agents/STATUS.md)）
> **日期**：2026-11-14
> **触发**：用户要设计"三层工具面"（原生 / 业务 / 可选），要求先把原始调查记录入库。
> **范围**：`dsh-plugin/**`（`0.0.5-hotfix2`）+ 宿主 `@deepseek-ai/dsh-*@0.2.2-rc.3` 的契约；附本机部署时点快照。
> **未做**：不改产品代码、不改 profile、不重启 DSH。
> **证据规则**：事实给 `file:line` 或「命令 + 输出」；推断一律标「推断」。

---

## 0. 摘要

1. **工具面由三段代码共同决定**：`lib/preset.js` 的 `plugins[]`（挂哪些工具插件）→ `lib/self.js` 的 `DEFAULT_TOOL_GROUPS` + `tools.restrict()`（全局工具留哪些）→ `<cwd>/agentconfig.json`（运行时可改的组声明）。三者中只有第三段是 Agent 能改的。
2. **`preset.js` 挂的工具全部落在 preset 作用域**，而 DSH 的 `restrict()` 语义是「裁掉全局 **和祖先作用域** 的工具，只有观察者自己那层豁免」（§3.2 已用宿主真实包实证）。因此 `self.js` 那句"scoped tools 不受 allowlist 影响"**只对 agent 作用域的注册成立**。
3. **allow 列表是按"全局视图"算的**（`self.js:177-184`），所以 preset 层的 `bash / read_image / subagent / list_agents / send_message / interrupt_agent / list_subagent_models / wait_agent` 天生进不了 allow → 会被同一次 `restrict()` 裁掉。其中 `bash` / `read_image` 靠 `repairHostTools()` 在 agent 层重挂复活；**subagent 一族没有任何复活路径**。
4. **白名单是 allow-only + 前缀/枚举匹配**：不在组里的工具默认不可见。当前 `xvfb_*`（12）、`browser_*`（7）、`inbox_dismiss` 都不在任何组，`wait_agent` 漏在 `SUBAGENT_BASENAMES` 之外。
5. **单测测不到 2/3**：`test/self.test.mjs` 的 fake harness 是单层 registry，且 `GLOBAL_NAMES` 把 `bash/read_image/subagent` 当成全局工具，45 条全绿。

---

## 1. 装配链路（五层）

```
① profile 层   ~/.dsh/profiles/web/package.json          dsh.profile.bundles 含 @mosire/bma3-dsh
              ~/.dsh/profiles/web/cordis.patch.yml     按 id 覆盖 / 禁用 bundle 行
② bundle 层   dsh-plugin/cordis.patch.yml                insert: bma3-inbox、bma3-preset
③ 模块层      dsh-plugin/index.js:24-40                 config.module → inbox | preset | self
④ 组合层      dsh-plugin/lib/preset.js:13-62            preset.plugins[] = 6 行（决定 scoped 工具）
⑤ 策略层      dsh-plugin/lib/self.js:563-631            agent/created：人设 section + bma3_self_* + restrict + repair
```

- 宿主工具行名不是裸包名：`lib/host-tools.js:149-152` 把 `@deepseek-ai/dsh-tool-*` 解析成 `file://` 绝对路径（profile 里没有这些包，且它们把整个宿主运行时声明成 peerDependencies）。
- 解析失败**不会**降级成"少一个工具"：`mount.ts` 的 `auditRows()` 任一行失败即 `throw`，整棵 preset 子树 reject → 该 preset 变 `broken`（§3.1）。

---

## 2. 设计承诺（代码里写死的意图）

| 承诺 | 出处 | 实现手段 |
|---|---|---|
| 默认五组：MCP 管理 / 基本 bma3 / 终端 / 图片 / 子 Agent | `self.js:9-17`、`examples/agentconfig.json` | `DEFAULT_TOOL_GROUPS` + `resolveToolGroups()` |
| `mcp_admin` 只管连接器管理面，不含业务 MCP 工具 | `self.js:147-148` | 只匹配 `mcp_connector_*`、`mcp_probe` |
| `bma3_core` 覆盖插件工具与 Java 侧 MCP 工具 | `self.js:25-47, 149-153` | `BASIC_BMA3_BASENAMES` + `qq_*` + `bma3_*` |
| `bash`/`pwsh`/`read_image` 删不掉 | `self.js:16, 166-169`、`preset.js:27-46`、`self.js:343-417` | ① preset 行挂载 ② `LOCKED_TOOL_BASENAMES` 补 allow ③ 缺失时 repair 重挂 |
| 文件工具只开读图 | `preset.js:37-46` | `tool-fs` 配置 `read/write/edit:false`、`readImage:true`（`applyPatch` 默认 false） |
| 子 Agent 一定 `minimal` | `preset.js:51-59` | 只挂 spawn 版 `tool-subagent`（`agentPreset:'minimal'`），**故意不挂 `subagent_fork`** |
| 不新增审批 / 权限模型 | `self.js:49-53` 注释、2026-11-10 调查 §1.3 | 插件内无 approve/policy 代码；连自己注册的工具都从 allow 里剔除（`restrict()` 不接受 scope-local 名字） |
| 人设文件 = 完整 system prompt | `self.js:120-125, 596-621`、README | `prompt.complete` 默认 true；文件缺失/为空则不挂任何 section |

---

## 3. 宿主契约（决定"三层"能做到什么）

### 3.1 preset 组合 = 挂在 preset scope 下的 loader 子树，eager load，任一行失败即整棵 broken

`dsh-agent-preset-registry/src/mount.ts`：`mountPreset()` 建 in-memory `EntryTree`、`await tree.root.update(prepareProfileEntries(...))`，然后 `auditRows()`；`failed.length > 0` 直接 `throw`。`src/index.ts` 的 `activate()` 捕获后写 `record.broken`，`retain()` 遇到 broken 抛 `agent-preset/invalid`。

→ **推断**：`host-tools.js` 解析 miss 时回退裸包名，不是"某行挂不上"，而是"整个 bma3 预设不可用"。

### 3.2 可见性 = 全局 + 祖先作用域；`restrict()` 作用于祖先层

- `dsh-scope/src/index.ts`：`createScope(ctx, key)` 给 ctx 打 scope 标签；`scopeChainOf()` 返回 `[自己, 父, 祖父…]`。
- `dsh-scope/src/store.ts:192-199`：`chainLayers()` 反过来排（远祖先在前）。
- `dsh-tools/src/index.ts:192-226` `view(scope)`：`inherited = 全局 + chainLayers(scope) 中除"自己那层"以外的所有层`；`layers.every(layer => layer.admits(name))` 决定可见。
- `dsh-agent-preset-registry/src/index.ts` `join()`：`bindScopeParent(agentKey, presetKey)` → **preset 的作用域是 agent 的父作用域**。

**实证**（`tools/e2e-local/dsh-tool-face-probe.mjs`，用宿主 `dsh-tools@0.2.2-rc.3` + `dsh-scope@0.2.2-rc.3`）：

```
agent 可见（restrict 前）: bash, bma3_inbox, bma3_self_status, mcp__bma3__qq_thread_list, read_image, subagent, unrelated_tool, wait_agent
restrictableNames（restrict 能点名的）: bash, bma3_inbox, mcp__bma3__qq_thread_list, read_image, subagent, unrelated_tool, wait_agent
agent 可见（restrict 后）: bma3_inbox, bma3_self_status, mcp__bma3__qq_thread_list
agent 可见（agent 层重挂 bash 后）: bash, bma3_inbox, bma3_self_status, mcp__bma3__qq_thread_list
PASS：restrict() 作用于「全局 + 祖先作用域」，只有观察者自己那层的注册豁免。
```

→ preset 层的 `bash/read_image/subagent/wait_agent` **全部被裁**；agent 层的 `bma3_self_status` 存活；在 agent 层重挂的 `bash` 复活。
→ **推断**：`subagent / list_agents / send_message / interrupt_agent / list_subagent_models / wait_agent` 在 bma3 会话里不可见，且无任何修复路径。

### 3.3 `when` 是逐 step 门控（现有代码未使用）

`dsh-tools/src/contracts.ts:107-124`：

- `ToolRegistrationOptions.when?: (context: {agent, modelSelection, signal}) => boolean | Promise<boolean>`
- `false` → **既不进声明也不进调度**；`reject` → **step 准备失败**（所以判定函数必须 total）。
- `dsh-tools/src/index.ts:246-290` `resolve()` 每 step 求值一次，产出 `ResolvedToolSet`。

→ 这是"按需可见"的**原生原语**：不需要重复 register/unregister，也不受 `restrict()` 的 allow-only 一刀切影响（但祖先层的 allow 仍会先把它裁掉，两者会叠加）。

### 3.4 工具集变化会进会话日志，并按 `toolUpdate` 模式投影到 wire

- `dsh-agent-loop/src/agent.ts:699-710`：本 step 的工具集与上一个 `request/header` 比对，差异写成 `developer/message`，内容为 `tool-addition` / `tool-removal`。
- `dsh-llm-deepseek/src/types.ts:36-42`：`toolUpdate: 'addition-only'` 表示端点只认后来的 `tool_addition`（配 `defer_loading`）；`'in-history'` 还会按顺序读 removal；**未声明** = 每个请求发完整列表。
- `dsh-llm/src/content.ts:445-472`：`toolUpdate === undefined` 时剥掉 `deferLoading` 并丢弃 developer 更新消息；`addition-only` 路由遇到 removal 会 `rebaseAdditionOnlyHistory()`（重建 baseline）。

→ **推断**：开/关工具是"模型可见 ⇔ 已入日志"的合规路径，但**每次变更都可能重建请求前缀**（缓存失效）；"ablate-only" 的 provider 路由要靠 rebase 兜底。

### 3.5 preset 首轮后锁定

`dsh-agent-preset-registry/src/index.ts` `select()`：会话已有 turn 时抛 `agent-preset/locked`。

→ **推断**：工具面的分层结构必须在 preset 注册时定死；会话中途只能改"层内可见性"，不能换层结构。

---

## 4. 现状与设计承诺的差距

| # | 差距 | 证据 |
|---|---|---|
| 1 | allow 用**全局视图**算，preset 层工具进不了 allow，被同一次 `restrict()` 裁掉 | `self.js:177-184`（`definitions()` 无 scope）、`:428-467`；§3.2 实证 |
| 2 | `wait_agent` 不在 `SUBAGENT_BASENAMES`，`subagent` 前缀规则也匹配不到 | `self.js:68-75` vs `dsh-tool-subagent-control/src/wait-agent.ts:91` |
| 3 | `xvfb_*`(12) / `browser_*`(7) / `inbox_dismiss` 无组覆盖 → 白名单下默认不可见 | `self.js:25-47, 146-165`；Java 侧 `*Tool.java` 的 `name()` 字符串 |
| 4 | `PRESET_PROVIDED_GROUPS` 把 terminal/image/subagent 从 `missingGroups` 里过滤 → 自检报绿但工具不可见 | `self.js:17, 311-312, 326` |
| 5 | 单测把 preset 工具当全局工具（单层 registry） | `test/self.test.mjs:26-89, 108-120` |
| 6 | "修复"用时间窗（500ms / 250ms）而不是可判定的就绪信号 | `self.js:626-630`、`:419-426` |
| 7 | README「锁定」段描述的是设计意图，与 `restrict()` 的实际作用域不符 | `README.md`「锁定」节 vs §3.2 |

---

## 5. 本机部署时点快照（2026-11-14 13:5x，仅作追溯）

| 事实 | 证据 |
|---|---|
| 活宿主 = `dsh web`（profile `web`，pid 4232，13:38 启动） | `ps -eo pid,lstart,cmd` |
| `bma3-preset` 行 `disabled: true`；`bma3-inbox` 行启用并覆盖了 `baseUrl: http://127.0.0.1:18766` / `tokenFile` | `dsh --profile web --dump-config`（输出存 `/tmp/dsh-dump-web.yml:1542-1555`） |
| 每次构建都有一条 `patch: entry "bma3-persona" not found`（旧行残留） | 同上，第 5 行 |
| 当时活着的会话 preset 均为 `standard` | `session_list` |
| MCP 连接 0 条 | `mcp_connector_status` |
| 宿主 5 个工具包可解析（经 pnpm store 兜底；`host-tools.js:30` 的 ANCHORS 在本机**不成立**） | 附录 A.3 |

→ 当时"bma3 预设下的工具面"在生产实例里**不存在**；`bma3-dsh` 只贡献 inbox 模块（`bma3_inbox` 工具 + `/inbox` 命令 + SSE）。

---

## 6. 对下一步设计的约束（不是设计本身）

设计稿（`docs/superpowers/specs/**`）至少要回答：

1. **注册 × 可见分离**：三层是"注册层"划分还是"可见性"划分？两者混用就是差距 #1 的成因。
2. **不用 allow-only 白名单**：分层要求"默认可见 + 显式收窄"，与 `restrict({allow})` 语义相反；若保留 `restrict()`，allow 必须按 **agent 视图**（`definitions(agent)`）算，且要把祖先层工具显式并入。
3. **就绪信号**：L2「状态初始化完之后才暴露」需要一个可判定的 per-module ready（而非定时器），并定义超时降级行为。
4. **门控原语**：`when`（逐 step）vs `restrict`（常驻 allow）vs 不注册（composition）——三者语义不同，需明确每层用哪一个。
5. **开关代价**：工具增减会写日志并可能重建请求前缀（§3.4），L3 的开关频率要有预算。
6. **可重建性**：L3 的激活状态必须能在会话恢复时从日志/持久状态重建（否则模型可见集与日志不一致）。
7. **组边界**：`xvfb_*` / `browser_*` / `inbox_dismiss` / `wait_agent` 归属哪层、默认是否可见。
8. **诊断**：自检要能报"已组合但不可见"，而不是像差距 #4 那样吞掉。

---

## 7. 未验证 / NOT_RUN

- **真实 DSH 宿主里 bma3 preset 挂载成功后的工具面清单**：`NOT_RUN`（preset 当时是 disabled，从未跑过）。
- **差距 #1/#2/#3 在真实宿主里的端到端表现**：只在宿主同版本 `dsh-tools`/`dsh-scope` 上做了等价复现（§3.2），未在真实 bma3 会话里观察。
- **`minimal` 子 Agent 的真实工具面**：`NOT_RUN`。
- **MCP 连上后的 `mcp__bma3__*` 命名与组匹配**：连接数 0，`NOT_RUN`。
- **`when` 门控与 `restrict()` 叠加时的实际行为**：只读源码（§3.3），未实跑。

---

## 附录 A · 证据清单

### A.1 插件单测

```bash
cd dsh-plugin && npm test
# 1..45 · pass 45 · fail 0 · skipped 0 · rc=0
```

### A.2 工具面作用域探针（宿主 rc.3）

```bash
node tools/e2e-local/dsh-tool-face-probe.mjs
# 运行时: dsh-tools@0.2.2-rc.3 · dsh-scope@0.2.2-rc.3
# …（见 §3.2 输出）
# PASS · rc=0
```

脚本会依次尝试 `BMA3_DSH_BIN` → `pnpm/npm root -g` 的常规与按 hash 布局 → 依赖锚点包 → pnpm store → 插件自带旧运行时；打印实际用到的版本与来源。

### A.3 宿主工具行解析（5/5）

```bash
cd dsh-plugin && node --input-type=module -e "
import { describeHostTools } from './lib/host-tools.js'
const entry='<…>/global/v11/<hash>/node_modules/@deepseek-ai/dsh/lib/bin.js'
for (const [s,p] of Object.entries(describeHostTools(entry))) console.log((p?'OK  ':'MISS')+' '+s)
"
# OK dsh-tool-bash / dsh-tool-pwsh / dsh-tool-fs / dsh-tool-subagent / dsh-tool-subagent-control
```

注：`createRequire(bin.js).resolve('@deepseek-ai/dsh-web-app')` → `MODULE_NOT_FOUND`，即 `host-tools.js:30` 的 ANCHORS 在本机不成立，实际生效的是 `scanStore()`（`host-tools.js:57-88`）。

### A.4 部署快照

```bash
dsh --profile web --dump-config | grep -n -A12 "bma3"   # 见 §5
mcp_connector_status                                     # 0 条连接
```
