#!/usr/bin/env node
/**
 * 用**宿主自己的** `@deepseek-ai/dsh-tools` + `@deepseek-ai/dsh-scope` 复现一件事：
 * preset 作用域注册的工具，在 agent 作用域被 `tools.restrict()` 裁剪时会不会一起消失。
 *
 * 为什么需要这个探针：`dsh-plugin/lib/self.js` 的 allow 列表是拿"全局视图"
 * (`tools.definitions()`) 算的，而 DSH 的 `restrict()` 作用域语义写在宿主源码里
 * (`dsh-tools/src/index.ts:192-226 view()` + `dsh-scope/src/store.ts:192 chainLayers()`)：
 * 可见集 = 全局 + **祖先作用域**，再由每层的 restriction 过滤；只有"观察者自己那层"的注册豁免。
 * 单测的假 harness 是单层 registry，测不到这个差别，所以单测全绿也不代表真实宿主里成立。
 *
 * 本脚本不启动 DSH、不联网、不写任何文件；只构造一个最小 Cordis 世界并断言行为。
 *
 * 用法：
 *   node tools/e2e-local/dsh-tool-face-probe.mjs
 *   BMA3_DSH_BIN=/path/to/@deepseek-ai/dsh/lib/bin.js node tools/e2e-local/dsh-tool-face-probe.mjs
 *
 * 退出码：0 = 与断言语义一致；1 = 语义变了或环境不满足（输出会说明）。
 */
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const WANTED = ['@deepseek-ai/dsh-tools', '@deepseek-ai/dsh-scope', '@deepseek-ai/cordis']

/** 宿主入口候选：pnpm/npm 全局树里的 dsh bin.js。 */
function entryCandidates() {
  const found = []
  if (process.env.BMA3_DSH_BIN) found.push(process.env.BMA3_DSH_BIN)
  for (const cmd of ['pnpm', 'npm']) {
    let root
    try {
      root = execFileSync(cmd, ['root', '-g'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
    } catch {
      continue
    }
    if (!root) continue
    found.push(path.join(root, '@deepseek-ai/dsh/lib/bin.js')) // 常规全局布局
    try {
      // pnpm 按内容寻址的布局：<root>/<hash>/node_modules/@deepseek-ai/dsh/lib/bin.js
      for (const entry of readdirSync(root)) found.push(path.join(root, entry, 'node_modules/@deepseek-ai/dsh/lib/bin.js'))
    } catch {
      // 读不了就只留常规布局
    }
  }
  return found.filter((item) => existsSync(item))
}

/** 升到包含 package.json 的目录。 */
function packageDirOf(file) {
  let dir = path.dirname(file)
  for (let hop = 0; hop < 12; hop += 1) {
    if (existsSync(path.join(dir, 'package.json'))) return dir
    const up = path.dirname(dir)
    if (up === dir) break
    dir = up
  }
  return undefined
}

/** pnpm store 布局的入口：`<...>/node_modules/.pnpm/@deepseek-ai+dsh-tools@<ver>/node_modules/@deepseek-ai/dsh-tools`。 */
function fromPnpmStore(entryPoint, spec) {
  const scope = spec.slice(0, spec.indexOf('/')) // @deepseek-ai
  const bare = spec.slice(spec.indexOf('/') + 1) // dsh-tools
  const storeName = `${scope}+${bare}`
  let dir = path.dirname(entryPoint)
  for (let hop = 0; hop < 8; hop += 1) {
    const store = path.join(dir, 'node_modules', '.pnpm')
    if (existsSync(store)) {
      try {
        const hit = readdirSync(store)
          .filter((name) => name.startsWith(`${storeName}@`))
          .sort()
          .at(-1)
        if (hit !== undefined) {
          const candidate = path.join(store, hit, 'node_modules', spec)
          if (existsSync(path.join(candidate, 'package.json'))) return candidate
        }
      } catch {
        // 不可读就继续往上走
      }
    }
    const up = path.dirname(dir)
    if (up === dir) break
    dir = up
  }
  return undefined
}

/** 依次尝试：require 直解析 → 依赖锚点包（dsh-web-app / dsh-base）→ pnpm store → 插件自带的旧运行时。 */
function locate(spec) {
  const attempts = []
  for (const entry of entryCandidates()) {
    attempts.push({ label: `require(${spec})`, dir: () => {
      try {
        return packageDirOf(createRequire(entry).resolve(spec))
      } catch {
        return undefined
      }
    } })
    for (const anchor of ['@deepseek-ai/dsh-web-app', '@deepseek-ai/dsh-base']) {
      attempts.push({ label: `require(${anchor}) → ${spec}`, dir: () => {
        try {
          const anchorDir = packageDirOf(createRequire(entry).resolve(anchor))
          if (anchorDir === undefined) return undefined
          return packageDirOf(createRequire(path.join(anchorDir, 'package.json')).resolve(spec))
        } catch {
          return undefined
        }
      } })
    }
    attempts.push({ label: `pnpm store ← ${entry}`, dir: () => fromPnpmStore(entry, spec) })
  }
  attempts.push({ label: 'plugin-local runtime', dir: () => {
    const dir = path.join(REPO, 'dsh-plugin/node_modules', spec)
    return existsSync(path.join(dir, 'package.json')) ? dir : undefined
  } })
  for (const attempt of attempts) {
    const dir = attempt.dir()
    if (dir === undefined) continue
    const entry = path.join(dir, 'lib/index.js')
    if (!existsSync(entry)) continue
    return { dir, entry, via: attempt.label }
  }
  return undefined
}

const located = Object.fromEntries(WANTED.map((spec) => [spec, locate(spec)]))
const missing = WANTED.filter((spec) => located[spec] === undefined)
if (missing.length > 0) {
  console.error(`找不到运行时包：${missing.join(', ')}`)
  console.error('可用 BMA3_DSH_BIN=/path/to/@deepseek-ai/dsh/lib/bin.js 指定宿主入口')
  process.exit(1)
}

const versionOf = (spec) => JSON.parse(readFileSync(path.join(located[spec].dir, 'package.json'), 'utf8')).version
console.log(`运行时: dsh-tools@${versionOf('@deepseek-ai/dsh-tools')} · dsh-scope@${versionOf('@deepseek-ai/dsh-scope')}`)
console.log(`来源:   ${located['@deepseek-ai/dsh-tools'].via}\n`)

const { Context } = await import(located['@deepseek-ai/cordis'].entry)
const { ToolsService } = await import(located['@deepseek-ai/dsh-tools'].entry)
const { createScope } = await import(located['@deepseek-ai/dsh-scope'].entry)

const tick = (ms = 60) => new Promise((resolve) => setTimeout(resolve, ms))

/** 最小 ToolDefinition：dsh-tools 要求 output {schema, render}。 */
const def = (name) => ({
  name,
  description: `probe:${name}`,
  parameters: { type: 'object', properties: {}, additionalProperties: false },
  output: {
    schema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'], additionalProperties: false },
    render: () => [{ type: 'text', text: 'ok' }],
  },
  execute: async () => ({ text: 'ok' }),
})

const root = new Context()
root.provide('systemPrompt', { tools() {} })
root.plugin(ToolsService)
await tick()
const tools = root.get('tools')
if (!tools) throw new Error('tools 服务没挂上')

/** 通过某个 ctx 注册工具（注册落到该 ctx 所属的 scope 层）。 */
const reg = (ctx, name) => ctx.inject(['tools'], (child) => {
  child.effect(() => child.tools.register(def(name)), `probe:${name}`)
})

// ① 全局层：bma3 插件自己注册的 bma3_inbox + MCP 业务工具 + 一个无关工具
for (const name of ['bma3_inbox', 'mcp__bma3__qq_thread_list', 'unrelated_tool']) reg(root, name)

// ② preset 层：对应 preset.js 挂的 tool-bash / tool-fs / tool-subagent-control / tool-subagent
const presetKey = {}
const preset = createScope(root, presetKey)
for (const name of ['bash', 'read_image', 'subagent', 'wait_agent']) reg(preset.ctx, name)

// ③ agent 层（父 = preset）：对应 self.js 的 bma3_self_*
const agentKey = {}
const agent = createScope(root, agentKey, { parent: presetKey })
reg(agent.ctx, 'bma3_self_status')
await tick()

const names = (scope) => tools.definitions(scope).map((item) => item.name).sort()
const show = (label, list) => console.log(`${label}: ${list.join(', ')}`)

const before = names(agentKey)
show('agent 可见（restrict 前）', before)
show('restrictableNames（restrict 能点名的）', [...tools.view(agentKey).restrictableNames].sort())

// ④ 照 self.js 的做法：只用"全局名"算 allow，然后 restrict
const globalNames = names(undefined)
const allow = globalNames.filter((name) => name === 'bma3_inbox' || name === 'mcp__bma3__qq_thread_list')
agent.ctx.inject(['tools'], (child) => { child.tools.restrict({ allow }) })
await tick()
const after = names(agentKey)
show('agent 可见（restrict 后）', after)

// ⑤ 照 self.js 的 repairHostTools：把 bash 重挂到 agent 自己的层
reg(agent.ctx, 'bash')
await tick()
const repaired = names(agentKey)
show('agent 可见（agent 层重挂 bash 后）', repaired)

const failures = []
const expect = (ok, message) => { if (!ok) failures.push(message) }
expect(before.includes('bash') && before.includes('subagent'), 'restrict 前 preset 工具应当可见')
expect(tools.view(agentKey).restrictableNames.has('bash'), 'preset 作用域的 bash 应当出现在 restrictableNames 里')
expect(after.includes('bma3_inbox') && after.includes('mcp__bma3__qq_thread_list'), 'allow 内的全局工具应当保留')
expect(after.includes('bma3_self_status'), 'agent 自己那层的工具应当豁免 restriction')
expect(!after.includes('bash') && !after.includes('read_image'), 'preset 作用域的 bash/read_image 应当被 allow 裁掉')
expect(!after.includes('subagent') && !after.includes('wait_agent'), 'preset 作用域的 subagent/wait_agent 应当被裁掉')
expect(repaired.includes('bash') && !repaired.includes('subagent'), 'agent 层重挂的 bash 复活、subagent 仍然不在')

if (failures.length > 0) {
  console.error('\nFAIL：DSH 作用域/restrict 语义与断言语义不一致：')
  for (const item of failures) console.error(`- ${item}`)
  process.exit(1)
}
console.log('\nPASS：restrict() 作用于「全局 + 祖先作用域」，只有观察者自己那层的注册豁免。')
process.exit(0)
