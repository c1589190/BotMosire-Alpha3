import assert from 'node:assert/strict'
import { resolve } from 'node:path'
import { test } from 'node:test'
import { managedEndpoints } from '../lib/runtime.js'

const ready = {
  protocol: 1, version: '0.0.9-early', mcpPort: 19001, mcpPath: '/bot/mcp/',
  inboxPort: 19002, inboxId: 'bma3', healthPort: 19003, envFile: resolve('test.env'),
}

test('managed MCP connects to daily and reports the complementary ops endpoint', () => {
  const result = managedEndpoints({ ...ready, mcpDailyPath: '/bot/mcp/daily', mcpOpsPath: '/bot/mcp/ops' })
  assert.equal(result.mcpUrl, 'http://127.0.0.1:19001/bot/mcp/daily')
  assert.equal(result.mcpOpsUrl, 'http://127.0.0.1:19001/bot/mcp/ops')
  assert.equal(result.mcpSurface, 'daily')
  assert.equal(result.inboxUrl, 'http://127.0.0.1:19002')
})

test('the v1 handshake from an older JAR still connects to its exact full path', () => {
  const result = managedEndpoints(ready)
  assert.equal(result.mcpUrl, 'http://127.0.0.1:19001/bot/mcp/')
  assert.equal(result.mcpSurface, 'full')
  assert.equal(result.mcpOpsUrl, undefined)
})

test('managed handshake rejects unexpected additional paths', () => {
  for (const paths of [
    { mcpDailyPath: 'http://127.0.0.1:19050/bot/mcp/daily' },
    { mcpDailyPath: '/other/daily' },
    { mcpDailyPath: null },
    { mcpDailyPath: '/bot/mcp/daily', mcpOpsPath: '/bot/mcp/daily' },
    { mcpOpsPath: '/bot/mcp/ops' },
  ]) assert.throws(() => managedEndpoints({ ...ready, ...paths }), /INVALID_MANAGED_HANDSHAKE/)
  assert.equal(managedEndpoints({ ...ready, mcpPath: '/', mcpDailyPath: '/daily' }).mcpUrl,
    'http://127.0.0.1:19001/daily')
})
