import assert from 'node:assert/strict'
import { test } from 'node:test'

import { apply, pollOnce, wakeText } from '../lib/inbox.js'

test('wakeText carries delivery id and rendered text only', () => {
  const text = wakeText({ id: 'dlv-1', text: 'hello\nworld', itemIds: ['itm-1'] })
  assert.match(text, /dlv-1/)
  assert.match(text, /hello\nworld/)
})

test('no session is bound until the bind tool is called', async () => {
  const definitions = []
  const ctx = {
    tools: { register: (definition) => { definitions.push(definition); return () => {} } },
    effect: (callback) => callback(),
    agents: { get: () => undefined, list: () => [] },
  }
  const registry = apply(ctx, {
    autoStart: false,
    baseUrl: 'http://127.0.0.1:18766',
    inboxId: 'bma3',
  })
  assert.equal(registry.list().length, 0)
  assert.deepEqual(definitions.map((definition) => definition.name).sort(), [
    'bma3_inbox_bind', 'bma3_inbox_status', 'bma3_inbox_unbind',
  ])

  const bind = definitions.find((definition) => definition.name === 'bma3_inbox_bind')
  const unbind = definitions.find((definition) => definition.name === 'bma3_inbox_unbind')
  const status = definitions.find((definition) => definition.name === 'bma3_inbox_status')
  await bind.execute({}, { agent: { id: 'session-1' } })
  assert.equal(registry.list().length, 1)
  assert.equal(registry.list()[0].sessionId, 'session-1')
  const state = JSON.parse((await status.execute({}, { agent: { id: 'session-1' } })).text)
  assert.equal(state.bound, true)
  await unbind.execute({}, { agent: { id: 'session-1' } })
  assert.equal(registry.list().length, 0)
})

test('a busy session is not woken and does not consume a delivery', async () => {
  const agent = { id: 'session-1', status: 'active', followup: () => { throw new Error('must not wake') } }
  const ctx = { agents: { get: () => agent, list: () => [agent] } }
  let fetched = false
  const count = await pollOnce({
    ctx,
    config: { baseUrl: 'http://127.0.0.1:18766', inboxId: 'bma3', sessionId: 'session-1' },
    fetchImpl: async () => { fetched = true; return { ok: true, json: async () => [] } },
  })
  assert.equal(count, 0)
  assert.equal(fetched, false)
})

test('pollOnce injects each due delivery into an idle agent', async () => {
  const calls = []
  const agent = {
    id: 'session-1',
    status: 'idle',
    followup(message) {
      calls.push(message)
    },
  }
  const ctx = {
    agents: {
      list: () => [agent],
      get: (id) => (id === agent.id ? agent : undefined),
    },
  }
  const fetchImpl = async (url, init) => {
    assert.match(url, /\/deliveries\/due$/)
    assert.equal(init.headers.Authorization, 'Bearer test-token')
    return {
      ok: true,
      async json() {
        return [
          { id: 'dlv-1', text: 'first' },
          { id: 'dlv-2', text: 'second' },
        ]
      },
    }
  }

  process.env.BMA3_TEST_INBOX_TOKEN = 'test-token'
  let count
  try {
    count = await pollOnce({
      ctx,
      config: {
        baseUrl: 'http://127.0.0.1:46500',
        inboxId: 'bma3',
        tokenEnv: 'BMA3_TEST_INBOX_TOKEN',
      },
      fetchImpl,
    })
  } finally {
    delete process.env.BMA3_TEST_INBOX_TOKEN
  }

  assert.equal(count, 2)
  assert.equal(calls.length, 2)
  assert.match(calls[0].content[0].text, /first/)
  assert.equal(calls[0].source.deliveryId, 'dlv-1')
})
