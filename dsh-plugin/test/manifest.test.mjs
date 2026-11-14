import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'

import { MODULES, inject, name } from '../index.js'

test('manifest exposes the bundle modules and required services', async () => {
  const pkg = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'))
  assert.equal(pkg.name, '@mosire/bma3-dsh')
  assert.equal(pkg.type, 'module')
  assert.ok(pkg.dsh.bundle.patch)
  assert.ok(pkg.exports['./inbox'])
  assert.ok(pkg.exports['./preset'])
  assert.ok(pkg.exports['./persona'])
  // 回归：耦合层删除后不得再导出 self（工具面/桥接/修复都在那一层，D-066）
  assert.equal(pkg.exports['./self'], undefined)
  assert.deepEqual([...MODULES], ['inbox', 'preset'])
  assert.equal(name, 'bma3-dsh')

  // 回归：`agentPresets` 不能进 inject —— 预设子树是在 registry.register() 内部挂载的，
  // 子行 inject 注册表会永远 pending，整棵子树静默消失（2026-11-14 实测）。
  assert.ok(inject.includes('tools'))
  assert.ok(inject.includes('agents'))
  assert.ok(inject.includes('systemPrompt'))
  assert.ok(!inject.includes('agentPresets'),
    'agentPresets 必须用 ctx.get() 取，不能进 inject')

  const patch = await readFile(new URL('../cordis.patch.yml', import.meta.url), 'utf8')
  assert.match(patch, /bma3-inbox/)
  assert.match(patch, /module: inbox/)
  assert.match(patch, /bma3-preset/)
  assert.match(patch, /module: preset/)
  assert.doesNotMatch(patch, /module: self/)
  assert.doesNotMatch(patch, /bma3-persona'?\s*$/m, 'legacy persona 行必须不再插入')
})
