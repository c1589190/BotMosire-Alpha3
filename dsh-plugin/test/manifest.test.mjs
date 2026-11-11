import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'

import { inject } from '../index.js'

test('manifest exposes the bundle modules and required services', async () => {
  const pkg = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'))
  assert.equal(pkg.name, '@mosire/bma3-dsh')
  assert.equal(pkg.type, 'module')
  assert.ok(pkg.dsh.bundle.patch)
  assert.ok(pkg.exports['./inbox'])
  assert.ok(pkg.exports['./preset'])
  assert.ok(pkg.exports['./self'])
  assert.ok(inject.includes('agentPresets'))

  const patch = await readFile(new URL('../cordis.patch.yml', import.meta.url), 'utf8')
  assert.match(patch, /bma3-inbox/)
  assert.doesNotMatch(patch, /bma3-persona/,
    'legacy persona 行必须不再插入：它会注册第二个 complete 段')
  assert.doesNotMatch(patch, /module: persona/)
  assert.match(patch, /bma3-preset/)
})
