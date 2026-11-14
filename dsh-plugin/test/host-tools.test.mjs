import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { test } from 'node:test'

import {
  HOST_TOOL_PACKAGES,
  describeHostTools,
  hostToolEntry,
  hostToolRowName,
  hostToolCacheSize,
  invalidateHostToolCache,
} from '../lib/host-tools.js'

function fakeHost() {
  const root = mkdtempSync(path.join(tmpdir(), 'bma3-host-'))
  const entryPoint = path.join(root, 'host.js')
  writeFileSync(entryPoint, '')
  const web = path.join(root, 'node_modules', '@deepseek-ai', 'dsh-web-app')
  mkdirSync(web, { recursive: true })
  writeFileSync(path.join(web, 'package.json'),
    JSON.stringify({ name: '@deepseek-ai/dsh-web-app', version: '1.0.0', main: 'index.js' }))
  writeFileSync(path.join(web, 'index.js'), 'export default {}\n')
  return { root, entryPoint }
}

function addTool(root, name = 'dsh-tool-bash') {
  const dir = path.join(root, 'node_modules', '@deepseek-ai', name)
  mkdirSync(path.join(dir, 'lib'), { recursive: true })
  writeFileSync(path.join(dir, 'package.json'),
    JSON.stringify({ name: `@deepseek-ai/${name}`, version: '1.0.0', main: 'lib/index.js' }))
  writeFileSync(path.join(dir, 'lib', 'index.js'), 'export const name = "fake"\n')
}

test('host tool lookup degrades to the bare specifier instead of throwing', () => {
  const bogus = '/nonexistent/host/bin.js'
  assert.equal(hostToolEntry('@deepseek-ai/dsh-tool-bash', bogus), undefined)
  assert.equal(hostToolRowName('@deepseek-ai/dsh-tool-bash', bogus), '@deepseek-ai/dsh-tool-bash')
  assert.deepEqual(describeHostTools(bogus), Object.fromEntries(HOST_TOOL_PACKAGES.map((name) => [name, null])))
})

test('the tool package list stays in preset order and covers bash/fs/subagent', () => {
  assert.deepEqual([...HOST_TOOL_PACKAGES], [
    '@deepseek-ai/dsh-tool-bash',
    '@deepseek-ai/dsh-tool-pwsh',
    '@deepseek-ai/dsh-tool-fs',
    '@deepseek-ai/dsh-tool-subagent',
    '@deepseek-ai/dsh-tool-subagent-control',
  ])
})


test('a host-tool miss is not cached forever (reinstall regression)', () => {
  const host = fakeHost()
  assert.equal(hostToolEntry('@deepseek-ai/dsh-tool-bash', host.entryPoint), undefined)
  assert.equal(hostToolCacheSize(host.entryPoint), 0, 'miss 不能进缓存')
  addTool(host.root)
  const found = hostToolEntry('@deepseek-ai/dsh-tool-bash', host.entryPoint)
  assert.ok(found, '补齐宿主树后必须重新解析到，而不是返回缓存的 undefined')
  assert.ok(found.endsWith(path.join('lib', 'index.js')))
  invalidateHostToolCache(host.entryPoint)
  rmSync(host.root, { recursive: true, force: true })
})

test('invalidateHostToolCache drops a positive lookup', () => {
  const host = fakeHost()
  addTool(host.root)
  const first = hostToolEntry('@deepseek-ai/dsh-tool-bash', host.entryPoint)
  assert.ok(first)
  assert.equal(hostToolCacheSize(host.entryPoint), 1)
  invalidateHostToolCache(host.entryPoint)
  assert.equal(hostToolCacheSize(host.entryPoint), 0)
  rmSync(host.root, { recursive: true, force: true })
})

test('subpath specifiers resolve through the package that owns them', () => {
  const ep = process.argv[1]
  const root = hostToolEntry('@deepseek-ai/dsh-plugin-manager', ep)
  if (root === undefined) {
    // This machine's host does not ship the plugin manager; nothing to prove.
    return
  }
  const tools = hostToolEntry('@deepseek-ai/dsh-plugin-manager/tools', ep)
  assert.notEqual(tools, undefined, '子路径要从包根目录里解析，不能只找同名包')
  assert.notEqual(tools, root)
})
