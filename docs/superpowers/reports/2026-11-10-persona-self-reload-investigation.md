# 人设自加载 / 自修改可行性调查

> **类型**：调查（时间点快照，只作追溯，不作当前事实）· **日期**：2026-11-10
> **方法**：初稿只读检查本机 DSH `0.2.2-rc.1` 的已安装包源码与 README，核对当前
> `@mosire/bma3-dsh` 的 persona/preset 实现和 `web` profile 配置；**初稿未改产品代码、
> 未新建 DSH 会话、未跑真实模型**。后续实现见下方 21:25 记录。
> **触发**：用户提出「加载人设扮演预设后，第一条消息发出时自动从当前工作区的
> `systemprompt.md` 加载系统提示词、`agentconfig.xxx` 加载动态工具预设，并允许 bot 主动重载/修改自己」。
> **2026-11-10 21:25 后续**：结论已进入实现，见 `dsh-plugin/lib/self.js`、
> `dsh-plugin/lib/preset.js`、`dsh-plugin/README.md`、`dsh-plugin/examples/`；
> `cd dsh-plugin && npm test` = **13 pass / 0 fail**。实现选择 **spawn-only**（不挂 `subagent_fork`，
> 避免 fork 继承父 preset 而不是 minimal）。真实 DSH preset 激活/远端仍 `NOT_RUN`。

> **2026-11-10 21:10 用户修正（优先于下文旧稿）**：
> 「谁让你动权限面了，配置文件里默认工具面应当包括mcp管理、基本的bma3工具、终端工具、图片读取工具和子Agent调用工具
> （子Agent的预设走极简模式即可，不需要额外读系统提示词），其中极简模式提供的终端和图片读取工具不应该能被删除，
> 除此之外Agent根据权限面想干啥干啥」。
>
> 按此修正：**权限面归 DSH，本插件不新增审批、owner-only 或 allow/deny 权限模型**；
> `agentconfig` 只声明默认工具组；子 Agent 用 DSH 内置 `minimal`，其 terminal/read_image 固定不可删；
> 其余能力按 DSH 权限面。下文旧的“自修改审批/allowlist”段落已按此改写，若仍有残留以本条为准。

---

## 0. 结论

**有办法，而且不需要发明新的 DSH 协议。** 但正确形态不是“每次重新注册一个 preset”，而是：

> **静态 preset + 每个 Agent 创建时注册一个动态 system-prompt section；默认工具组由 preset 组合提供。**

- **系统提示词**：`ctx.systemPrompt.section({ text: () => ... })` 支持函数式文本；DSH 在
  **每个模型步骤组装前**重新求值，所以 `systemprompt.md` 改完，下一次组装就会生效。
- **工具面**：默认工具组由 `bma3` preset 的组合/DSH 工具面提供；`agentconfig` 只声明
  默认组，不新增权限/审批规则。默认组至少包括 MCP 管理、基本 bma3、终端、`read_image`、
  子 Agent 调用。MCP/工具实际可用性仍由 DSH 权限面决定。
- **bot 自修改**：系统提示词可以做到“改文件 → 下一步自动生效”；是否允许改写文件由
  DSH 文件工具与权限面决定，本插件不额外加审批。工具面默认由 preset 组合决定，不在
  每轮做权限判定。
- **不能做的**：会话开始后不能切换整个 agent preset（`agent-preset/locked`）；只能改
  preset 内插件读取的内容。要换 preset 组合，必须新会话。

**证据等级**

| 等级 | 内容 |
|---|---|
| **已确认事实** | `systemPrompt.section.text` 可以是函数，`assemble()` 每步调用；`agent/created` 串行等待；preset 定义静态加载，`select` 在首轮后锁定；DSH 内置 `minimal` 组合固定包含 `read_image` 与 terminal；`subagent` 的 `agentPreset` 仅 in-process spawn 支持；DeepSeek `deepseek-flash` 声明 `systemPromptUpdate: in-history`、`toolUpdate: addition-only` |
| **推断（未跑真实 DSH 会话）** | 用静态 preset 内一个 loader 插件即可覆盖所有使用该 preset 的会话；每步读小文件开销可忽略；`agentPreset: minimal` 能让 spawn 子 Agent 走内置极简组合 |
| **待用户拍板** | 默认工具组的具体边界（MCP 管理是否含 `mcp__...` 业务工具、基本 bma3 具体哪些）；`systemprompt.md` 是 persona 前缀还是完整系统提示词；配置用 JSON 还是 YAML；子 Agent 是否只用 spawn |

---

## 1. DSH 已经提供的原语

### 1.1 动态 system prompt section

`@deepseek-ai/dsh-system-prompt@0.2.2-rc.1` 的 `PromptSection.text` 类型是：

```ts
text: string | ((context: AssembleContext) => string)
```

`assemble()` 里每次都会执行：

```js
text: typeof section.text === "function" ? section.text(context) : section.text
```

见 `dsh-system-prompt/lib/index.js:358-401`（`assemble()`）与 `:389`（函数求值）。
`dsh-agent-loop` 在 `agent/pre-step` **之前**调用 `assemble()`（`dsh-agent-loop/lib/index.js:679`），
所以第一次模型请求前就会读到文件；不需要额外的“第一条消息”钩子。

两个细节：

- **`complete: true`**：该 section 成为唯一系统提示词；多个 complete section 会直接
  `multiple complete prompt sections are active` 报错（`dsh-system-prompt/lib/index.js:383-384`）。
- **`interpolate: false`**：保留 `{{...}}` 字面量；人设文件里可能有模板/代码，建议关闭插值。

### 1.2 每个 Agent 的 scoped context

`agent/created` 事件在创建完成前串行等待（`dsh-agent/lib/index.js:710`），payload 里有
`agent`。`Agent.ctx` 是该 Agent 的 scoped context；通过它注册的 prompt section/tool
只影响这个 Agent，并在 disposal 时自动撤销（`dsh-agent/README.md:74`）。

`Agent.session.header.cwd` 是当前会话的工作目录（`dsh-session` 的 `SessionHeader.cwd`），
可以作为“当前工作区”的权威路径。`dsh-subagent` 就是这么给子 Agent 覆盖 persona 的：
`childCtx.systemPrompt.section({ name: 'deployment:persona-prefix', ... })`
（`dsh-subagent/lib/index.js:385-397`）。

### 1.3 工具面（本次不设计权限）

DSH 有 `ctx.tools.restrict({ allow?, deny? })` 能力，但**本次按用户修正不使用它做权限/
默认组**：默认工具面由 `bma3` preset 的 composition 提供，实际调用由 DSH 权限面决定。
这里只记录能力存在，不作为方案的一部分；不再设计审批、owner-only、allow/deny。

### 1.4 preset 是静态的，首轮后锁定

`@deepseek-ai/dsh-agent-preset` 的定义在注册时 eager load；文档明确“edits affect
subsequently created Agents”（`dsh-agent-preset/README.md`）。`agentPresets.select()` 在
会话已有 turn 时抛 `agent-preset/locked`（`dsh-agent-preset-registry/lib/index.js:769`）。
所以：

- ✅ 可以：在 preset 里挂一个 loader 插件，它每次 Agent 创建时读当前文件。
- ❌ 不可以：在会话中“重新注册/切换一个 preset”，让已有会话换一套插件组合。
- ✅ 可以：改文件内容；工具面默认由 preset composition/DSH 权限面决定，不在会话中
  重新注册 preset。

### 1.5 DeepSeek 的会话中更新能力

`dsh-llm-deepseek@0.2.2-rc.1` 的默认 `deepseek-flash` 条目声明：

```js
systemPromptUpdate: "in-history",
toolUpdate: "addition-only"
```

（`dsh-llm-deepseek/lib/index.js:20-27`）

- 系统提示词变更：agent loop 会在 cached history 之后追加新的 system snapshot，而不是
  重写开头；模型能看到最新 system prompt。
- 工具变更：loop 会生成 `tool-addition` / `tool-removal` developer message；DeepSeek adapter
  把它们序列化成 `tool_addition` / `tool_removal`，并发送
  `mid-conversation-tool-changes-2026-07-01` beta header
  （`dsh-llm-deepseek/lib/index.js:2230`）。

所以“会话中改人设 + 改工具面”在 DSH/DeepSeek 这条链路上是**有协议支撑的**，不是我们
自己拼的旁路。代价是：`in-history` 的旧 system snapshot 会留在历史里，直到 compaction
或新的 request series 清理；频繁重载会推高上下文成本。**本机未发真实 DeepSeek 请求，
provider 端最终接受度 `NOT_RUN`。**

---

## 2. 为什么“第一条消息时加载”不需要消息钩子

DSH 的执行顺序是：

```text
agent/created（串行等待）
  → 注册 agent-scoped system-prompt section
  → 第一次 user message
  → systemPrompt.assemble()（section.text() 在这里读文件）
  → agent/pre-step
  → 模型请求
```

默认工具面不在这里动态改：它由 `bma3` preset 的 composition 提供。所以最稳的做法是：

1. **`agent/created`**：拿到 `agent.session.header.cwd`，做一次初始读取/校验，只注册
   动态 system-prompt section。`agent/created` 在 fresh/resume/clear/compaction 时都会触发；
   每个新 Agent 都会重新注册自己的 scoped section，旧 Agent 的注册随 disposal 撤销。
2. **section text provider**：每次 `assemble()` 重新读 `systemprompt.md`（按 mtime/size 缓存），
   这样第一条消息、以及之后的每条消息都拿到最新文件。
3. **不需要 `bma3_self_reload`**：prompt 文件改动会在下一次 `assemble()` 自动生效；工具面
   由 preset composition 决定，不在每轮重算。

效果上等价于“第一条消息时加载”，但不会出现“消息已经到了、prompt 还是旧的”的竞态。

结构示意（不是实现代码）：

```js
export function apply(ctx, config = {}) {
  ctx.on('agent/created', async ({ agent }) => {
    const cwd = agent.session.header.cwd
    if (!cwd) return
    const state = new PersonaState(cwd, config)
    await state.loadInitial()

    agent.ctx.effect(() => agent.ctx.systemPrompt.section({
      name: 'deployment:persona-prefix',
      order: agent.ctx.systemPrompt.getSectionOrder('DEPLOYMENT_PERSONA_PREFIX'),
      text: () => state.promptText(),   // 每步同步读，失败回 last-good
      complete: state.complete,
      interpolate: false,
    }))
  })
}
```

注意：`PromptSection.text` 是**同步** provider；不要在里面 `await`。用
`fs.readFileSync` + `mtime/size` 缓存即可；如果坚持走 `ctx.fs` 的异步沙箱读取，需在
`agent/created` 预读并缓存，再在 provider 里返回缓存文本。

---

## 3. 三种做法

| 方案 | 做法 | 判断 |
|---|---|---|
| **A. 静态 preset + 每 Agent loader** | preset 里挂 `bma3-self` 只读 prompt；默认工具组由同一 preset 的 composition 提供；文件改动由 section provider 读取 | ✅ **推荐**。不换 preset，不碰 DSH 权限面，支持人设文件热读 |
| **B. 动态重注册/切换 preset** | 第一条消息时 `agentPresets.register` 一个新定义并 `select` | ❌ 不成立。preset 定义 eager load，已有会话的 composition 稳定；首轮后 `select` 直接 `agent-preset/locked` |
| **C. 外部 watcher 改 profile YAML + 重启 DSH** | 文件变化后改 `cordis.patch.yml`，重启宿主 | ❌ 代价是断会话/重启，且不是“第一条消息时加载”；只适合开发期改插件组合 |
| **D. 只用 DSH 自带 `AGENTS.md` 加载** | 把 persona 写进 `AGENTS.md`，依赖 `dsh-agent-instructions` | ❌ 不够。它是 **user-role workspace instruction**，不是 system prompt；且不做持续外部文件监听（只在首次请求/文件操作/resume 时刷新） |

> `dsh-agent-instructions` 已确认：它加载 `AGENTS.md`/`CLAUDE.md` 作为 sourced `user/message`，
> 不是 system section；不要把它和 persona system prompt 混为一谈。

---

## 4. 候选文件契约

### 4.1 `systemprompt.md`

- 位置：`<agent.session.header.cwd>/systemprompt.md`。
- 内容：Markdown 人设正文；不解析 front matter，不做变量插值（`interpolate: false`）。
- 读取：每次 `assemble()` 前按 `mtime + size` 检查，变化才重读；上限建议 16–32 KiB。
- 失败：文件缺失/空/超限/不是普通文件 → 返回**上一次成功文本**；没有上一次就用内置默认，
  **绝不返回空 complete section**（空会清掉系统提示词）。

### 4.2 `agentconfig.json`（默认工具组声明，不是权限策略）

`agentconfig.xxx` 不需要用 DSH 的 Cordis YAML。Cordis YAML 是**构建期插件组合**，允许
`!!js` 表达式，不适合 bot 在运行时改写。运行时配置用**纯数据 JSON**，只声明默认工具组：

```json
{
  "schemaVersion": 1,
  "prompt": {
    "file": "systemprompt.md",
    "complete": false,
    "maxBytes": 32768
  },
  "defaultTools": [
    "mcp_admin",
    "bma3_core",
    "terminal",
    "image",
    "subagent"
  ],
  "subagent": {
    "preset": "minimal"
  }
}
```

约定：

- **`defaultTools` 是默认工具组声明，不是审批/owner-only/deny 规则。** 实际工具由
  `bma3` preset 的 composition 提供（MCP 管理/bma3 工具走全局 MCP；terminal/read_image/
  subagent 用对应插件行），最终能不能调用仍由 DSH 权限面决定；本插件不新增权限判定。
- 组由插件解析到当前已注册工具：
  - `mcp_admin`：MCP 管理工具（如 `mcp_connector_*`；不含具体 server 的业务工具）。
  - `bma3_core`：基本 bma3 工具（inbox/ack/status 等；具体清单待确认）。
  - `terminal`：平台终端工具（Linux `bash` / Windows `pwsh`）。
  - `image`：`read_image`。
  - `subagent`：子 Agent 调用/控制工具（`subagent`、`send_message`、
    `interrupt_agent`、`list_agents` 等；实现里未挂 fork）。
- 某组当前不存在：status 报 missing，不因此禁用其他组；不要静默伪造工具。
- `prompt.file` 固定为 `systemprompt.md`（或只允许 workspace 内相对路径）；不允许绝对路径/
  越界路径。
- `subagent.preset: minimal`：使用 DSH 内置 `minimal` 组合；它已经固定包含 `read_image`
  与 terminal，且 **不挂 bma3 的 `systemprompt.md` loader**，因此不会额外读系统提示词。
  `agentPreset` 只支持 in-process spawn；`subagent_fork` 继承父 preset，不能用它切 minimal。
- **不配置 `allow`/`deny`/`requireApproval`**：这些属于权限面，不由人设插件重新设计。
- `complete: false` 推荐：文件作为 `deployment:persona-prefix`，保留 harness identity、
  tool guidance、runtime context；`complete: true` 时文件是唯一系统提示词，工具使用指导
  也会被压掉，需自行在文件里补。

> 为什么不用 YAML：JSON 无 tag/锚点/代码执行，解析和原子校验简单；bot 生成 JSON 也比
> 生成严格 YAML 更稳。若人类编辑体验优先，可以后续加 `agentconfig.yaml` 作为可选输入，
> 但正本仍建议 JSON。

### 4.3 极简子 Agent（用户修正）

DSH 0.2.2-rc.1 的内置 `minimal` preset 已经正好是用户要的形态：

- `@deepseek-ai/dsh-tool-fs`：`read: false`、`readImage: true`、`write: false`、`edit: false`
  —— 只提供 `read_image`。
- `persistent-shell` 组：`dsh-terminal` + `dsh-terminal-bash` + `dsh-tool-bash-persistent`
  —— Linux 提供 `bash`；Windows 用 `pwsh` 对应行。
- 自带一个 complete persona（`You are a helpful software engineer assistant.`），
  **不挂 `dsh-agent-instructions`，也不挂 bma3 的 `systemprompt.md` loader**。

因此子 Agent 直接 `agentPreset: minimal` 即可；terminal/read_image 由 minimal 的
composition 固定，不经过 bma3 的 `agentconfig`，所以不会被本插件删除。若未来给 minimal
再加一层配置，必须把 `terminal`/`image` 做成不可移除的保留组。

### 4.4 当前实现的差距

- `dsh-plugin/lib/persona.js:15-20`：`text` 是静态字符串，`complete: true`；不能读文件。
- `dsh-plugin/lib/preset.js:10-19`：preset 里直接挂 `@deepseek-ai/dsh-persona`，`prefix`
  是静态配置；没有 loader 插件，也没有 terminal/image/subagent 工具行。
- `~/.dsh/profiles/web/cordis.patch.yml:199-202`：`bma3-preset` / `bma3-persona` 当前
  **都是 disabled**；实际只启用了 `bma3-inbox`。要试人设，需要启用 preset 并替换其中的
  persona/tool rows。

---

## 5. 重载语义

### 5.1 系统提示词

- 文件变化 → 下一次 `assemble()` 读到新文本；不需要注册新 section。
- 如果改动发生在同一个 turn 的工具调用之后：下一次 step 的 `assemble()` 会读新文件；
  DeepSeek `in-history` 会追加新 system snapshot。
- 如果 agent 空闲：下一次用户消息触发；不会主动发模型请求。
- 相同内容不会重复追加（DSH 比较渲染文本）；但每次内容变化都会增加一个 system snapshot。

### 5.2 工具面

- **默认工具组由 `bma3` preset 的 composition 提供**：MCP 管理、基本 bma3、终端、
  `read_image`、子 Agent 调用。`agentconfig.json` 只声明默认组，不新增审批/owner-only/
  allow/deny 权限规则。
- 组解析到当前已注册工具；某组不存在时 status 报 missing，不因此禁用其他组。
- **权限面仍归 DSH**：工具是否可调用由 DSH 权限面/工具注册决定，本插件不另做判定。
- 子 Agent：`agentPreset: minimal` 走内置极简组合；terminal/read_image 由 minimal 固定，
  不经 bma3 的 `agentconfig`，所以不会被本插件删除。`minimal` 不挂 bma3 的
  `systemprompt.md` loader，不会额外读系统提示词。
- `agentPreset` 只支持 in-process spawn；`subagent_fork` 继承父 preset，不能切 minimal。
  如果所有子 Agent 都必须 minimal，就只用 spawn 的 `subagent`，或另配 fork preset。

### 5.3 不能做的事

- 不能在一个已有会话里切到另一个 preset；`agent-preset/locked`。
- 不能靠 `agentconfig` 新增一个当前不存在的工具；默认组只能解析已注册工具。
- 不能在 `complete: true` 下既保留 harness identity 又只放文件内容；二者只能选一个。
- 不能把 bma3 的 `systemprompt.md` loader 挂进 minimal；minimal 的 terminal/read_image
  是固定组合，不该被额外配置层删除。

---

## 6. 实现边界（不是权限策略）

**权限面归 DSH。** 本插件不新增审批、owner-only 或 allow/deny 权限模型；下面只是文件读取/
默认工具组落地的实现约束。

1. **文件读取安全。** `systemprompt.md` 只从 `<cwd>` 读；拒绝绝对路径、`..`、symlink 逃逸；
   用 `realpath` 确认父目录在 workspace 内。写入是否允许由 DSH 文件工具/权限面决定；
   插件不另设审批。
2. **大小/空内容。** prompt 上限 16–32 KiB；空文件不替换 last-good；complete 模式下空文本
   会清系统提示词，必须 fail-closed（这是读取安全，不是权限判定）。
3. **默认工具组。** `mcp_admin`/`bma3_core`/`terminal`/`image`/`subagent` 是默认可见组；
   某组不存在时 status 报 missing，不伪造、不静默删其他组。实际调用仍由 DSH 权限面判定。
4. **极简锁定。** minimal 的 `read_image` + terminal 由 DSH minimal composition 固定；
   bma3 的 loader 不挂进 minimal，因此不会删除它们。若未来给 minimal 加配置层，必须把
   这两组做成保留组。
5. **子 Agent。** `agentPreset: minimal` 只支持 in-process spawn；fork 继承父 preset。
   只主 Agent 有人设时，按 `payload.source`/`parentSession` 过滤，避免 loader 给子 Agent
   额外注入 bma3 系统提示词。
6. **多会话。** 同一 workspace 的多个会话共享 `systemprompt.md`；改一次所有会话下次组装
   都变。要不同人设，用不同 workspace，不要靠 per-session 文件。
7. **上下文成本。** `in-history` 下每次 prompt 变化都追加 system snapshot；频繁自改会膨胀
   上下文。只在 hash 变化时 reload；必要时新会话/compaction。
8. **提示注入是操作者/权限面的事。** 如果 DSH 文件工具允许任意消息驱动写 `systemprompt.md`，
   那是权限面/操作者的选择；插件不替它加审批，但也不把“能改配置”当成“能执行任意代码”。

---

## 7. 推荐落地顺序（确认后）

1. **选型确认**：先拍板 §8 的问题；在此之前不写产品代码。
2. **P0**：新 `bma3-self` loader 插件 + `systemprompt.md` 动态 section；只读，不改工具面。
3. **P1**：`bma3` preset 加入默认工具组：MCP 管理、基本 bma3、终端、`read_image`、
   子 Agent 调用；`agentconfig.json` 只做默认组声明/校验。
4. **P2**：子 Agent 走内置 `minimal`（`agentPreset: minimal`，spawn）；确认
   terminal/read_image 由 minimal 固定，bma3 persona loader 不挂进去。
5. **P3**：真实 DSH 会话验收：首条消息读文件、改文件下一步生效、minimal 子 Agent 只有
   terminal/read_image、主 preset 默认组存在。真实模型/真实宿主联调前写 `NOT_RUN`。

---

## 8. 需要用户拍板

1. **默认组边界**：`mcp_admin` 是否就是 `mcp_connector_*`（不含 `mcp__...` 业务工具）？
   `bma3_core` 具体包含哪些（inbox/ack/status + 哪些 QQ/browser 工具）？
2. **系统提示词形态**：`systemprompt.md` 是“persona 前缀”（保留 DSH harness/tool guidance，
   推荐）还是“完整系统提示词”（`complete: true`，压掉其他 section）？
3. **配置协议**：`agentconfig.json`（推荐，纯数据）还是 `agentconfig.yaml`（人类友好，但要
   引 YAML 解析器）？
4. **子 Agent**：只用 spawn 的 `subagent`（可 `agentPreset: minimal`），还是还要保留
   `subagent_fork`（继承父 preset，不能切 minimal）？

---

## 9. 证据

- DSH 版本：`0.2.2-rc.1`（本机 `dsh` 全局包）。
- `@deepseek-ai/dsh-system-prompt@0.2.2-rc.1`：
  - `lib/index.js:358-401` `assemble()`；
  - `:389` `text` 函数求值；
  - `:383-384` 多个 complete section 报错。
- `@deepseek-ai/dsh-agent-loop@0.2.2-rc.1`：
  - `lib/index.js:679` `assemble()` 在 `agent/pre-step` 之前；
  - `:850-852` `systemPrompt.project` 的 `inHistory` / `toolUpdate` 分支。
- `@deepseek-ai/dsh-agent@0.2.2-rc.1`：
  - `lib/index.js:710` `agent/created` 串行等待；
  - `lib/types/runtime-types.d.ts:176` `Agent.ctx`。
- `@deepseek-ai/dsh-agent-preset-registry@0.2.2-rc.1`：
  - `lib/index.js:769` `agent-preset/locked`；
  - README 的 preset eager load / new Agents 说明。
- `@deepseek-ai/dsh-web-app@0.2.2-rc.1` 内置 `presets/minimal.patch.yml`：
  - `tool-fs`：`read:false` / `readImage:true` / `write:false` / `edit:false`；
  - `persistent-shell`：terminal + `dsh-tool-bash-persistent`（Windows 对应 pwsh）；
  - 自带 complete persona，未挂 `dsh-agent-instructions`。
- `@deepseek-ai/dsh-tool-subagent@0.2.2-rc.1` README:52-53：`agentPreset` /
  `allowedAgentPresets`；且只支持 in-process spawn，fork/external 拒绝。
- `@deepseek-ai/dsh-llm-deepseek@0.2.2-rc.1`：
  - `lib/index.js:20-27` `systemPromptUpdate: in-history` / `toolUpdate: addition-only`；
  - `:2230` `mid-conversation-tool-changes-2026-07-01` beta header。
- `@deepseek-ai/dsh-agent-instructions@0.2.2-rc.1` README:12：`AGENTS.md` 是 user-role
  workspace instruction，且不做持续外部监听。
- 当前实现：`dsh-plugin/lib/persona.js:15-20`（静态 complete section）、
  `dsh-plugin/lib/preset.js:10-19`（静态 `dsh-persona` row，缺 terminal/image/subagent 工具行）、
  `~/.dsh/profiles/web/cordis.patch.yml:199-202`（`bma3-preset`/`bma3-persona` disabled）。
- **NOT_RUN**：真实 DSH 会话首条消息读取、改文件后下一步生效、minimal 子 Agent 实际工具面、
  真实模型下的上下文增长。

---

## 10. 一句话

**能做，而且 DSH 的 `systemPrompt.section(text: () => ...)` + `agent/created` 正好适合
“静态 preset、动态人设”；默认工具面由 preset 组合提供，子 Agent 直接用内置 `minimal`。**
权限面归 DSH：不新增审批、owner-only 或 allow/deny；只做默认工具组、minimal 固定
terminal/read_image，以及人设文件的安全读取。
