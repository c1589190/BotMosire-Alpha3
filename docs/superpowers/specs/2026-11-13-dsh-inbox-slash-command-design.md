# DSH 插件：`/inbox` 斜杠命令（手动查看状态 / 手动解绑）

> **状态**：当前有效（2026-11-13）
> **需求原话**：D-057「我发现现有 dsh 插件还是没有手动输入 `/inbox status` 查看状态、`unbind` 手动解绑 inbox 的命令，能不能补上」
> **关联**：D-043（同源可多条 / 推送即冻结）、D-045（绑定持久化，「bound once, bound forever」）、红线 1（只绑回环）、红线 2（密钥零泄露）。

## 1. 问题

`bma3_inbox` 工具已经能做全部挂载操作，但**只有 Agent 能调用**。人在 DSH 输入框里没有任何入口：

- 看不到 inbox 当前挂在哪个会话、SSE 通不通、游标推到哪。
- 想解绑必须先"请 Agent 帮我调个工具"，而 Agent 本身可能就是被推送唤醒的那个会话 —— 自举死锁。

DSH 提供斜杠命令：`ctx.commands.register({ name, description, input, handler })`，handler 返回
`{ kind: 'success' | 'error', text }`，**文本由 UI 直接渲染，不进模型上下文**。

## 2. 契约

### 2.1 命令

```
/inbox [status|bind [sessionId]|unbind|detach|restore|pause|resume|start|stop|ack]
```

| 子命令 | 行为 | 说明 |
|---|---|---|
| `status`（默认） | `client.status()` | 无参数等同 |
| `bind [id]` | `client.bind(id)` | 省略 id 则绑到**当前会话**；bind 会 restore + 必要时 resume |
| `unbind` | `client.unbind()` | **只清绑定与落盘，不停止 SSE**（与 MCP 工具语义一致） |
| `detach` | `client.unbind()` + `client.stop()` | 彻底脱手：解绑 + 停止 SSE |
| `restore` | `client.restore()` | 恢复持久化绑定并 resume |
| `pause` / `resume` | `client.pause()` / `client.resume()` | 暂停/恢复注入（SSE 保持） |
| `start` / `stop` | `client.start()` / `client.stop()` | 开关 SSE（保留绑定） |
| `ack` | `client.ack()` | 推进读游标到当前 push 水位 |

### 2.2 `status` 输出（人类可读，多行）

```
Inbox bma3 · 运行中
绑定:     7f3a2b1c…（本会话）
SSE:      open
游标:     push=42 ack=42 待注入=0
注入:     未暂停 · MCP 已连接
状态文件: ~/.dsh/storages/bma3-inbox.json
配置:     http://127.0.0.1:46500 · token 来源 env:BMA3_INBOX_TOKEN
```

未绑定时 `绑定: 未绑定`，并追加一行提醒：
`⚠ 未绑定状态下，新面板会注入到第一个 idle 会话；要彻底停下用 /inbox detach`。

### 2.3 错误

| 场景 | 返回 |
|---|---|
| `bind` 且既无参数、调用方也不是会话 | `kind: 'error'`，提示 `usage: /inbox bind <sessionId>` |
| 未知子命令 | `kind: 'error'`，列出可用子命令 |
| `commands` 服务缺失 | 不注册，`warn` 一行，**不影响其它模块** |
| 注册过程抛异常 | 吞掉并 `warn`，**不得**让 `apply()` 失败（I5 的强制要求） |

## 3. 不变量

- **I1 命令只动本插件的挂载状态**：不写消息板、不调 QQ provider、不改 provider 侧任何东西。
- **I2 结果不进模型上下文**：只用 `CommandResult.text` 由 UI 渲染，不以用户消息形式提交给 Agent。
- **I3 不泄漏密钥**：`status` 只报 token **来源**（`env:BMA3_INBOX_TOKEN` / `config`），绝不回显值。
- **I4 注册可回收**：经 `ctx.effect(() => commands.register(...))` 注册，插件重载不留重复定义。
- **I5 缺失 `commands` 不阻断**：包级 `inject` 是三个模块共享的，把 `commands` 加进去会连带 gate 掉 `preset`/`self`。
  因此**不改包级 `inject`**，改用 Cordis 的**可选依赖**写法：
  ```js
  ctx.inject(['commands'], (child) => {
    child.effect(() => child.commands.register(definition))
  })
  ```
  子 fiber 等服务就绪后注册，父模块不被 gate；服务缺失则永不注册（只 warn）。
- **I6 与 MCP 工具语义一致**：同名 action 走同一个 `client` 方法，不另写一套状态机。
- **I7 `unbind` 不隐式停 SSE**（保持既有语义）；要彻底停下必须显式 `detach` 或 `stop`。

## 4. 验收判据

| # | 判据 |
|---|---|
| A1 | `/inbox`（空输入）→ `success`，文本含 `绑定:` 与 `SSE:` |
| A2 | `/inbox status` → 同 A1 |
| A3 | `/inbox bind <id>` → 调 `client.bind('<id>')`，文本含该 id |
| A4 | `/inbox bind`（无 id、无会话上下文）→ `error`，文本含 `usage: /inbox bind <sessionId>` |
| A5 | `/inbox unbind` → 调 `client.unbind()`，文本含「已解绑」且**含未绑定回退提醒** |
| A6 | `/inbox detach` → 同时调 `unbind()` 与 `stop()`，文本含「已停止」 |
| A7 | `/inbox 乱写` → `error`，文本列出 `status/bind/unbind/detach/...` |
| A8 | `ctx.commands` 缺失时 `apply()` 不抛异常，`bma3_inbox` 工具仍注册（回归保护） |
| A9 | `ctx.effect` 的 disposer 被调用后，命令定义从注册表移除（重载不留重复） |
| A10 | `status` 文本**不含** token 值，只含 `tokenSource` |
| A11 | 未绑定时 `status` 文本追加「第一个 idle 会话」提醒 |

## 5. 回归记录（0.0.5-hotfix1 → 0.0.5-hotfix2）

**0.0.5-hotfix1 上线即炸**，远程宿主报：

```
bma3-inbox (@mosire/bma3-dsh): Error: cannot get property "commands" without inject
    at registerInboxCommand (…/lib/inbox.js:705:23)
    at apply (…/lib/inbox.js:852:3)
```

三处错误，逐条记下来：

1. **`ctx.commands` 未声明 inject 时是 `throw`，不是 `undefined`。**
   Cordis 的 context proxy 在属性既不是自有属性、也没被 `internal/get` waterfall 解析出来时
   **直接抛 `cannot get property "X" without inject`**。所以 `ctx.commands ?? ctx.get('commands')`
   这种"空值合并兜底"**永远走不到右边**——异常在取值那一步就抛了。
   本机用真 Cordis 实测三种取法：

   | 写法 | 结果 |
   |---|---|
   | `ctx.commands` | **THROW** |
   | `ctx.get('commands')` | 正常返回服务 |
   | `ctx.reflect.get('commands')` | 正常返回服务 |

2. **异常逃出 `apply()`，整个 inbox 模块都没挂上** —— 不是"少一条命令"，而是 `bma3_inbox` 工具
   和 SSE 推送一起消失。命令面是**附加能力**，它的任何失败都不该影响主模块。

3. **单测没抓到，因为 stub 的语义是错的**：原 stub 里 `ctx.commands` 不存在时返回 `undefined`，
   而真实 Cordis 抛异常。**桩的语义错了，测试就是在验证幻觉。**

修复（0.0.5-hotfix2）：

- 改用 `ctx.inject(['commands'], child => …)` 可选依赖，父模块不被 gate。
- 注册调用整体 try/catch，**任何异常都只 warn，绝不让 `apply()` 失败**。
- 单测 stub 改成 Cordis 语义（未注入的属性访问抛异常），并新增两条回归：
  - `a Cordis-style ctx that throws on undeclared services still mounts the module`
  - `a throwing ctx.inject cannot take the module down`
- 真 registry 检查脚本改成**在没 inject `commands` 的 plugin fiber 里**注册 ——
  与真实宿主结构一致，直接复现原错误。

## 6. 第二个回归：插件重载会把 inbox 永久停掉

修 hotfix1 时又挖出一个更早就存在、破坏力更大的 bug——它让"重启就好了"这个前提**不成立**。

`persist()` 里写的是：

```js
const snapshot = { …, stopped, … }   // ← closure 里的引擎变量
```

而拆卸插件时 `apply()` 的清理是 `ctx.effect(() => () => client.stop(false))`，
`stop(false)` 只把引擎变量置 `true`、**不动 `state.stopped`**，然后 `persist()` 把 `true` 落盘。

下一次加载：

```js
stopped: Boolean(persisted.stopped)          // createState → 当成"用户点了 stop"
if (initial.targetSessionId && !initial.stopped) restore(...)
else if (config.autoStart !== false && !initial.stopped) start()
```

两个分支都被 `!initial.stopped` 挡住 ⇒ **不恢复绑定、不启 SSE**，而且看起来像用户自己的选择。

**结论**：任何一次插件卸载/热重载（换版本、改文件、行重排）都会让 inbox 静默停摆，
重启 DSH 也救不回来——必须手动把状态文件改回 `stopped:false`，或（新版本）敲 `/inbox start`。

**修复**：`persist()` 只落盘**用户意图**，不落引擎状态。

```js
stopped: state.stopped,
```

不变式补一条：

- **I8 意图与引擎分离**：`state.stopped` 是持久化的用户意图；闭包 `stopped` 是运行时引擎。
  只有意图可以落盘。拆卸 → `stop(false)` → 引擎停、意图不变。

回归测试 `unloading the plugin must not persist the user-stop intent` 覆盖三件事：
拆掉插件后文件仍是 `stopped:false`；重新加载会自己起来；**用户显式 `stop()` 过则重载后不自启**。

> 运维后果：被旧版本写坏的状态文件（`stopped:true`）在升级后仍会被尊重为"用户意图"，
> 升级后**必须**改一次文件或敲 `/inbox start`。
