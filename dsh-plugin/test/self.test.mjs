import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { test } from 'node:test'

import { apply, resolveToolGroups } from '../lib/self.js'

function workspace() {
  return mkdtempSync(path.join(tmpdir(), 'bma3-self-'))
}

function fakeHarness(cwd, names) {
  const sections = []
  const restrictions = []
  const tools = []
  let onCreated
  const listeners = new Map()
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
    tools: {
      definitions() {
        return names.map((name) => ({ name }))
      },
      restrict(filter) {
        restrictions.push(filter)
        return () => {}
      },
      register(definition) {
        tools.push(definition)
        return () => {}
      },
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
  apply(ctx)
  return {
    agent,
    sections,
    restrictions,
    tools,
    listeners,
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

  assert.equal(h.sections.length, 1)
  assert.equal(h.sections[0].name, 'deployment:persona-prefix')
  assert.equal(h.sections[0].complete, true, '默认整份替换，不叠加 DSH 默认提示词')
  assert.equal(h.sections[0].interpolate, false)
  assert.equal(h.sections[0].text(), '第一版人设')

  writeFileSync(path.join(cwd, 'systemprompt.md'), '第二版人设，长度不同')
  assert.equal(h.sections[0].text(), '第二版人设，长度不同')
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

  assert.equal(h.sections.length, 0, '没有文件就不能塞任何内置人设')

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
  assert.equal(h.sections[0].complete, true)

  writeFileSync(path.join(cwd, 'agentconfig.json'), JSON.stringify({
    schemaVersion: 1,
    prompt: { complete: false },
  }))
  const reload = h.tools.find((tool) => tool.name === 'bma3_self_reload')
  await reload.execute({}, {})
  assert.equal(h.sections.length, 2)
  assert.equal(h.sections[1].complete, false)
})
