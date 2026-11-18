import assert from 'node:assert/strict'
import { test } from 'node:test'

import { apply, presetDefinition } from '../lib/preset.js'

test('preset mounts the thin face: persona + terminal + image + subagents + plugin door', () => {
  const definition = presetDefinition({ get: () => undefined })
  assert.equal(definition.id, 'bma3')
  assert.equal(definition.name, 'BMA3')
  const ids = definition.plugins.map((row) => row.id)
  assert.deepEqual(ids, [
    'persona',
    'tool-bash',
    'tool-pwsh',
    'tool-fs',
    'tool-subagent-control',
    'tool-subagent',
    'media',
    'tool-plugin-manager',
    'tool-session-query',
  ])
  assert.match(definition.plugins[0].name, /persona\.js$/, '行名必须是独立入口文件而不是包名')

  const fsRow = definition.plugins.find((row) => row.id === 'tool-fs')
  assert.deepEqual(fsRow.config, { read: false, readImage: true, write: false, edit: false },
    '只开 readImage：文本读写走 bash')

  const spawn = definition.plugins.find((row) => row.id === 'tool-subagent')
  assert.equal(spawn.config.agentPreset, 'minimal')

  // 0.0.5 的组别白名单已删除：预设不再生成任何 allow/deny 规则
  assert.equal(definition.toolFace, undefined)
  assert.equal(definition.defaultTools, undefined)
})

test('preset mounts the read-only session-query tools and no compaction backend', () => {
  const definition = presetDefinition({ get: () => undefined })
  const rows = new Map(definition.plugins.map((row) => [row.id, row]))

  // dsh-web-app 把 session-query 服务留在 host 平面、把模型面工具留给预设；不挂这一行，Agent 就只能读
  // jar 自己那份滚动副本（memory_context），读不到真相层。
  const sessionQuery = rows.get('tool-session-query')
  assert.ok(sessionQuery, '缺少 tool-session-query：Agent 读不到宿主会话日志')
  // 名字要能落到宿主安装树里的绝对入口（测试进程下可能解析失败退回裸 specifier，两者都接受）。
  assert.match(sessionQuery.name, /dsh-tool-session-query/)
  assert.equal(sessionQuery.disabled, undefined, '这一行不能是 disabled，否则等于没挂')

  // 压缩归 billion-context（D-082）：预设里再挂一套就会重复压缩，且它按 row.name 精确匹配
  // `file:` URL 形式的行名，拦不住。
  assert.equal(rows.has('compaction-basic'), false, '压缩后端应交给 billion-context，不在预设里挂')
  assert.equal(rows.has('command-compact'), false, '/compact 由宿主决定，不在预设里开')
})

test('plugin-manager is only mounted when the host publishes profileContext', () => {
  const without = presetDefinition({ get: () => undefined })
  assert.equal(without.plugins.find((row) => row.id === 'tool-plugin-manager').disabled, true)

  const withSeam = presetDefinition({ get: (name) => (name === 'profileContext' ? {} : undefined) })
  assert.equal(withSeam.plugins.find((row) => row.id === 'tool-plugin-manager').disabled, false)
})

test('persona config is forwarded, empty values are omitted', () => {
  const definition = presetDefinition(undefined, { promptFile: '/tmp/p.md', complete: false })
  assert.deepEqual(definition.plugins[0].config, { promptFile: '/tmp/p.md', complete: false })
  assert.deepEqual(presetDefinition(undefined, { promptFile: '' }).plugins[0].config, {})
})

test('preset can be registered', () => {
  const registered = []
  const ctx = {
    agentPresets: {
      register(value) {
        registered.push(value)
        return () => {}
      },
    },
    effect(fn) {
      return fn()
    },
  }

  apply(ctx)
  assert.equal(registered.length, 1)
  assert.equal(registered[0].id, 'bma3')
})

test('preset waits for agentPresets through ctx.inject instead of reading a property', () => {
  const registered = []
  const presets = { register(value) { registered.push(value); return () => {} } }
  let waited = null
  const ctx = {
    get(name) {
      if (name === 'agentPresets') {
        throw new Error('ctx.get must not be used for agentPresets before inject settles')
      }
      return undefined
    },
    inject(deps, cb) {
      waited = deps
      cb({ get: (name) => (name === 'agentPresets' ? presets : undefined), effect(fn) { return fn() } })
    },
    effect(fn) { return fn() },
  }

  apply(ctx)
  assert.deepEqual(waited, ['agentPresets'])
  assert.equal(registered.length, 1)
  assert.equal(registered[0].id, 'bma3')
})

test('a missing registry is a no-op, not a crash', () => {
  const ctx = { effect(fn) { return fn() } }
  assert.doesNotThrow(() => apply(ctx))
})
