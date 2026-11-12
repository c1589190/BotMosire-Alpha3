import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { test } from 'node:test'

import { apply, resolveToolGroups } from '../lib/self.js'

function workspace() {
  return mkdtempSync(path.join(tmpdir(), 'bma3-self-'))
}

function fakeHarness(cwd, names, options = {}) {
  const sections = []
  const restrictions = []
  const tools = []
  let onCreated
  let agentRef
  const listeners = new Map()
  const scoped = new Set(options.scopedNames ?? [])
  const agentCtx = {
    systemPrompt: {
      section(section) {
        sections.push(section)
        return () => {}
      },
      getSectionOrder() {
        return 0
      },
    },
    get() {
      return undefined
    },
    inject(_names, callback) {
      callback(agentCtx)
    },
    shell: { sandboxMode: undefined },
    tools: (() => {
      // 尽量贴近 DSH 的语义：
      //  - definitions() 返回全局 + 该 agent 自己的 scoped 注册
      //  - restrict() 只接受"可限制的"（全局/继承）名字；出现 scope-local 或未知全局名字就抛
      const restrictable = new Set(names)
      return {
        definitions(scope) {
          const visible = scope === agentRef ? [...names, ...scoped] : [...names]
          return visible.map((name) => ({ name }))
        },
        get(name, scope) {
          const visible = scope === agentRef ? [...names, ...scoped] : [...names]
          return visible.includes(name) ? { name } : undefined
        },
        restrict(filter) {
          const unknown = [...filter.allow ?? [], ...filter.deny ?? []]
            .filter((name) => scoped.has(name) || !restrictable.has(name))
          if (unknown.length > 0) {
            throw new Error(`tools.restrict() names unknown global tool ${unknown.join(', ')}`)
          }
          restrictions.push(filter)
          return () => {}
        },
        register(definition) {
          scoped.add(definition.name)
          tools.push(definition)
          return () => { scoped.delete(definition.name) }
        },
      }
    })(),
    plugin(plugin, config) {
      if (options.invokeApply && typeof plugin?.apply === 'function') {
        plugin.apply(agentCtx, config)
      }
      const basename = plugin?.name === 'tool-bash'
        ? 'bash' : plugin?.name === 'tool-fs' ? 'read_image' : undefined
      if (!basename) return { dispose() {} }
      scoped.add(basename)
      return { dispose: () => scoped.delete(basename) }
    },
    effect(fn) {
      return fn()
    },
    on(event, fn) {
      listeners.set(event, fn)
      return () => {}
    },
  }
  const ctx = {
    on(event, fn) {
      if (event === 'agent/created') onCreated = fn
      return () => {}
    },
  }
  const agent = { session: { header: { cwd } }, ctx: agentCtx }
  agentRef = agent
  apply(ctx, { hostToolEntry: options.hostToolEntry })
  return {
    agent,
    sections,
    restrictions,
    tools,
    listeners,
    scoped,
    async created() {
      await onCreated({ agent })
    },
  }
}

const GLOBAL_NAMES = [
  'mcp_connector_catalog',
  'mcp_probe',
  'mcp__bma3__inbox_list',
  'mcp__bma3__qq_thread_list',
  'mcp__bma3__qq_conversation_send',
  'bash',
  'read_image',
  'subagent',
  'subagent_fork',
  'send_message',
  'interrupt_agent',
  'list_agents',
  'unrelated_tool',
]

test('systemprompt.md is injected as the complete system prompt by default', async () => {
  const cwd = workspace()
  writeFileSync(path.join(cwd, 'systemprompt.md'), '第一版人设')
  const h = fakeHarness(cwd, GLOBAL_NAMES)
  await h.created()

  const persona = h.sections.find((section) => section.name === 'deployment:persona-prefix')
  const shadow = h.sections.find((section) => section.name === 'bma3-persona')
  assert.ok(persona, '人设 section 必须叫 DSH 预留的 deployment:persona-prefix')
  assert.equal(persona.complete, true, '默认整份替换，不叠加 DSH 默认提示词')
  assert.equal(persona.interpolate, false)
  assert.equal(persona.text(), '第一版人设')
  assert.ok(shadow, '必须挂一个同名空段遮蔽旧 persona 模块留下的 complete 段')
  assert.equal(shadow.complete, false)
  assert.equal(shadow.text, '', '影子段必须为空文本')
  assert.equal(h.sections.filter((section) => section.complete === true).length, 1,
    'DSH 只允许一个 complete 段，多一个整轮就失败')

  writeFileSync(path.join(cwd, 'systemprompt.md'), '第二版人设，长度不同')
  assert.equal(persona.text(), '第二版人设，长度不同')
})

test('default groups include MCP, bma3, terminal, image and subagent; lock survives config edits', async () => {
  const cwd = workspace()
  const h = fakeHarness(cwd, GLOBAL_NAMES)
  await h.created()

  const first = h.restrictions.at(-1)
  assert.ok(first, 'tool restriction was applied')
  for (const name of [
    'mcp_connector_catalog',
    'mcp_probe',
    'mcp__bma3__inbox_list',
    'mcp__bma3__qq_thread_list',
    'mcp__bma3__qq_conversation_send',
    'bash',
    'read_image',
    'subagent',
    'subagent_fork',
    'send_message',
    'interrupt_agent',
    'list_agents',
  ]) {
    assert.ok(first.allow.includes(name), `default group should include ${name}`)
  }
  assert.ok(!first.allow.includes('unrelated_tool'))

  writeFileSync(path.join(cwd, 'agentconfig.json'), JSON.stringify({
    schemaVersion: 1,
    defaultTools: [],
  }))
  const reload = h.tools.find((tool) => tool.name === 'bma3_self_reload')
  assert.ok(reload, 'self reload tool is registered')
  await reload.execute({}, {})

  const locked = h.restrictions.at(-1)
  assert.deepEqual(locked.allow, ['bash', 'read_image'])
})

test('the bma3_core group keeps plugin-owned bma3_* tools visible', () => {
  const resolved = resolveToolGroups(['bma3_core'], [
    'mcp__bma3__qq_thread_list',
    'bma3_inbox',
    'bma3_self_status',
    'bash',
  ])
  assert.ok(resolved.allow.includes('bma3_inbox'),
    'bma3_inbox 必须留在 allow 里，否则 BMA3 会话看不到它')
  assert.ok(resolved.allow.includes('mcp__bma3__qq_thread_list'))
  assert.ok(resolved.allow.includes('bash'),
    'bash/read_image 由 preset 锁死，不因没选 terminal 组而消失')
})

test('a missing systemprompt.md injects nothing at all', async () => {
  const cwd = workspace()
  const h = fakeHarness(cwd, GLOBAL_NAMES)
  await h.created()

  assert.equal(h.sections.filter((section) => section.complete === true).length, 0,
    '没有文件就不能塞任何内置人设')

  const status = h.tools.find((tool) => tool.name === 'bma3_self_status')
  assert.match((await status.execute({}, {})).text, /缺少 systemprompt\.md/)
})

test('invalid agentconfig falls back to defaults without failing agent creation', async () => {
  const cwd = workspace()
  writeFileSync(path.join(cwd, 'agentconfig.json'), '{not-json')
  const h = fakeHarness(cwd, GLOBAL_NAMES)
  await h.created()

  const allow = h.restrictions.at(-1).allow
  assert.ok(allow.includes('mcp_connector_catalog'))
  assert.ok(allow.includes('mcp__bma3__inbox_list'))
  assert.ok(allow.includes('bash'))
  assert.ok(allow.includes('read_image'))

  const status = h.tools.find((tool) => tool.name === 'bma3_self_status')
  const result = await status.execute({}, {})
  assert.match(result.text, /配置读取失败/)
})

test('reload re-applies a changed default group and reports status', async () => {
  const cwd = workspace()
  writeFileSync(path.join(cwd, 'systemprompt.md'), '一句话人设')
  writeFileSync(path.join(cwd, 'agentconfig.json'), JSON.stringify({
    schemaVersion: 1,
    defaultTools: ['bma3_core'],
  }))
  const h = fakeHarness(cwd, GLOBAL_NAMES)
  await h.created()

  let allow = h.restrictions.at(-1).allow
  assert.ok(allow.includes('mcp__bma3__inbox_list'))
  assert.ok(allow.includes('bash'))
  assert.ok(allow.includes('read_image'))
  assert.ok(!allow.includes('mcp_connector_catalog'))
  assert.ok(!allow.includes('subagent'))

  writeFileSync(path.join(cwd, 'agentconfig.json'), JSON.stringify({
    schemaVersion: 1,
    defaultTools: ['mcp_admin', 'subagent'],
  }))
  const reload = h.tools.find((tool) => tool.name === 'bma3_self_reload')
  const result = await reload.execute({}, {})
  allow = h.restrictions.at(-1).allow
  assert.ok(allow.includes('mcp_connector_catalog'))
  assert.ok(allow.includes('subagent'))
  assert.ok(allow.includes('bash'))
  assert.ok(allow.includes('read_image'))
  assert.ok(!allow.includes('mcp__bma3__inbox_list'))
  assert.equal(JSON.parse(result.text).ok, true)
})


test('an explicit complete:false opts back into additive mode', async () => {
  const cwd = workspace()
  writeFileSync(path.join(cwd, 'systemprompt.md'), '一句话人设')
  writeFileSync(path.join(cwd, 'agentconfig.json'), JSON.stringify({
    schemaVersion: 1,
    prompt: { complete: true },
  }))
  const h = fakeHarness(cwd, GLOBAL_NAMES)
  await h.created()
  assert.equal(h.sections.find((section) => section.name === 'deployment:persona-prefix').complete,
    true)

  writeFileSync(path.join(cwd, 'agentconfig.json'), JSON.stringify({
    schemaVersion: 1,
    prompt: { complete: false },
  }))
  const reload = h.tools.find((tool) => tool.name === 'bma3_self_reload')
  await reload.execute({}, {})
  const latest = h.sections.filter((section) => section.name === 'deployment:persona-prefix').at(-1)
  assert.equal(latest.complete, false, '显式 complete:false 才退化成叠加')
})

test('a stale global bma3-persona complete section is shadowed, never doubled', async () => {
  const cwd = workspace()
  writeFileSync(path.join(cwd, 'systemprompt.md'), '一句话人设')
  const h = fakeHarness(cwd, GLOBAL_NAMES)

  // 旧 persona 模块当年注册在 global 层且没有 disposer：重载后它仍留在会话里。
  const staleGlobal = {
    name: 'bma3-persona',
    order: 0,
    complete: true,
    text: '旧版大肥鱼人设',
  }
  await h.created()

  // DSH 规则（dsh-system-prompt）：scoped section 遮蔽同名 global section，
  // 只有"有效"的 complete 段计入；多于一个则整轮组装失败。
  const effective = [
    ...['staleGlobal'].map(() => staleGlobal)
      .filter((global) => !h.sections.some((section) => section.name === global.name)),
    ...h.sections,
  ]
  const complete = effective.filter((section) => section.complete === true)
  assert.equal(complete.length, 1,
    '必须只剩一个 complete 段，否则 DSH 报 multiple complete prompt sections')
  assert.equal(complete[0].name, 'deployment:persona-prefix')
  assert.ok(effective.some((section) => section.name === 'bma3-persona'),
    '影子段要占住旧名字')
})

test('the allow list never names scope-local tools (tools.restrict would throw)', async () => {
  const cwd = workspace()
  writeFileSync(path.join(cwd, 'systemprompt.md'), '人设')
  // GLOBAL_NAMES 里有 bash/read_image；插件自己会注册 bma3_self_status/reload（scope-local）。
  const h = fakeHarness(cwd, [...GLOBAL_NAMES, 'bma3_inbox'])
  await h.created()

  const applied = h.restrictions.at(-1)
  assert.ok(applied, 'restrict 必须成功应用，不能因为 scope-local 名字整条策略失败')
  assert.ok(applied.allow.includes('bash'), 'bash 必须留在 allow 里')
  assert.ok(applied.allow.includes('bma3_inbox'), 'bma3_inbox 必须留在 allow 里')
  assert.ok(!applied.allow.includes('bma3_self_status'), 'scope-local 工具不能出现在 allow')
  const status = JSON.parse((await h.tools.find((tool) => tool.name === 'bma3_self_status')
    .execute({}, {})).text)
  assert.equal(status.error, null, '工具策略不能报错')
  assert.ok(status.allow.includes('bash'))
})


test('status reports scoped bash/read_image as lockedTools without poisoning restrict()', async () => {
  const cwd = workspace()
  const globalOnly = GLOBAL_NAMES.filter((name) => name !== 'bash' && name !== 'read_image')
  const h = fakeHarness(cwd, globalOnly, { scopedNames: ['bash', 'read_image'] })
  await h.created()

  const status = JSON.parse((await h.tools.find((tool) => tool.name === 'bma3_self_status')
    .execute({}, {})).text)
  assert.deepEqual(status.lockedTools.sort(), ['bash', 'read_image'])
  assert.deepEqual(status.missingLockedTools, [])
  const allow = h.restrictions.at(-1).allow
  assert.ok(!allow.includes('bash'), 'scoped bash 不能进 restrict() 的 allow')
  assert.ok(!allow.includes('read_image'), 'scoped read_image 不能进 restrict() 的 allow')
})

test('bma3_self_repair force-mounts missing bash/read_image', async () => {
  const cwd = workspace()
  const bashModule = path.join(cwd, 'fake-tool-bash.mjs')
  const fsModule = path.join(cwd, 'fake-tool-fs.mjs')
  writeFileSync(bashModule, "export const name='tool-bash'; export const inject=[]; export function apply(ctx){ ctx.tools.register({name:'bash'}) }\n")
  writeFileSync(fsModule, "export const name='tool-fs'; export const inject=[]; export function apply(ctx){ ctx.tools.register({name:'read_image'}) }\n")
  const globalOnly = GLOBAL_NAMES.filter((name) => name !== 'bash' && name !== 'read_image')
  const h = fakeHarness(cwd, globalOnly, {
    hostToolEntry: (spec) => spec.includes('dsh-tool-fs') ? fsModule : bashModule,
    invokeApply: true,
  })
  await h.created()

  const before = JSON.parse((await h.tools.find((tool) => tool.name === 'bma3_self_status')
    .execute({}, {})).text)
  assert.deepEqual(before.missingLockedTools.sort(), ['bash', 'read_image'])

  const repair = JSON.parse((await h.tools.find((tool) => tool.name === 'bma3_self_repair')
    .execute({}, {})).text)
  assert.equal(repair.changed, true)
  assert.deepEqual(repair.after, [])
  assert.ok(h.scoped.has('bash'))
  assert.ok(h.scoped.has('read_image'))

  const after = JSON.parse((await h.tools.find((tool) => tool.name === 'bma3_self_status')
    .execute({}, {})).text)
  assert.deepEqual(after.missingLockedTools, [])
  assert.deepEqual(after.lockedTools.sort(), ['bash', 'read_image'])
})
