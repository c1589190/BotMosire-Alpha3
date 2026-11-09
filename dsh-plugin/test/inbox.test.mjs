import assert from 'node:assert/strict'
import { test } from 'node:test'

import { pollOnce, wakeText } from '../lib/inbox.js'

test('wakeText carries delivery id and rendered text only', () => {
  const text = wakeText({ id: 'dlv-1', text: 'hello\nworld', itemIds: ['itm-1'] })
  assert.match(text, /dlv-1/)
  assert.match(text, /hello\nworld/)
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
