import assert from 'node:assert/strict'
import { test } from 'node:test'

import { apply, createInboxClient, renderPanel, SseParser } from '../lib/inbox.js'

test('renderPanel shows bounded windows, source labels and relation markers', () => {
  const text = renderPanel({
    inboxId: 'bma3',
    revision: 42,
    totalItems: 3,
    omittedItems: 1,
    items: [
      {
        source: 'qq_group/bot-1/955432692',
        sourceLabel: 'QQ群 955432692',
        priority: 300,
        totalMessages: 128,
        omittedMessages: 122,
        entries: [
          { line: '[20:08] #101 LvtN：欢迎回来？ [@3924945183] [图片]' },
        ],
      },
    ],
  })
  assert.match(text, /^\[INBOX_PANEL\] inbox=bma3 rev=42 unread=3 items=1\/3/)
  assert.match(text, /\[QQ群 955432692\] #1 priority=300 total=128 shown=1/)
  assert.match(text, /\[@3924945183\]/)
  assert.match(text, /\[图片\]/)
  assert.match(text, /省略 122 条/)
  assert.match(text, /另有 1 个来源未显示/)
  assert.doesNotMatch(text, /history|完整内容在服务端|请调用 inbox_list/)
})

test('SseParser handles frames split across chunks', () => {
  const events = []
  const parser = new SseParser((event) => events.push(event))
  parser.push('id: 7\nevent: panel\ndata: {"rev')
  parser.push('ision":7}\n\nid: 8\nevent: heartbeat\ndata: {"revision":8}\n\n')
  assert.equal(events.length, 2)
  assert.equal(events[0].event, 'panel')
  assert.equal(events[0].id, '7')
  assert.deepEqual(JSON.parse(events[0].data), { revision: 7 })
  assert.equal(events[1].event, 'heartbeat')
})

test('apply exposes one-panel tools and does not leak the token', async () => {
  const definitions = []
  const ctx = {
    tools: { register: (definition) => { definitions.push(definition); return () => {} } },
    effect: (callback) => callback(),
    agents: { get: () => undefined, list: () => [] },
  }
  const calls = []
  const fetchImpl = async (url, init) => {
    calls.push({ url, init })
    return {
      ok: true,
      status: 200,
      async json() { return { ackedRevision: 42 } },
    }
  }
  const client = apply(ctx, {
    autoStart: false,
    baseUrl: 'http://127.0.0.1:18766',
    inboxId: 'bma3',
    token: 'secret-token',
    fetchImpl,
  })
  assert.deepEqual(definitions.map((definition) => definition.name).sort(), ['bma3_inbox'])
  const inbox = definitions[0]

  const initial = JSON.parse((await inbox.execute({}, { agent: { id: 'session-1' } })).text)
  assert.equal(initial.action, 'status')
  assert.equal(initial.callingSession, 'session-1')
  assert.equal(initial.running, false, 'autoStart:false ⇒ 未启动')
  assert.equal(initial.paused, false)
  assert.equal(initial.config.tokenSource, 'config')
  assert.equal(initial.config.inboxId, 'bma3')

  const bound = JSON.parse((await inbox.execute({ action: 'bind' }, { agent: { id: 'session-1' } })).text)
  assert.equal(bound.targetSessionId, 'session-1')
  assert.equal(bound.thisSessionIsTarget, true)

  const other = JSON.parse((await inbox.execute({ action: 'status' }, { agent: { id: 'session-2' } })).text)
  assert.equal(other.thisSessionIsTarget, false, '别的会话要能看出自己不是目标')

  const paused = JSON.parse((await inbox.execute({ action: 'pause' }, { agent: { id: 'session-1' } })).text)
  assert.equal(paused.paused, true)
  assert.equal(JSON.parse((await inbox.execute({ action: 'resume' }, {})).text).paused, false)
  assert.equal(JSON.parse((await inbox.execute({ action: 'unbind' }, {})).text).targetSessionId, null)

  const ack = JSON.parse((await inbox.execute({ action: 'ack' }, { agent: { id: 'session-1' } })).text)
  assert.equal(ack.ackedRevision, 42)
  assert.equal(calls.length, 1)
  assert.match(calls[0].url, /\/v1\/inboxes\/bma3\/ack$/)
  assert.equal(calls[0].init.headers.Authorization, 'Bearer secret-token')
  assert.doesNotMatch(JSON.stringify(client.status()), /secret-token/)
})


test('one SSE panel wakes an idle agent and advances the cursor', async () => {
  const messages = []
  const agent = {
    id: 'session-1',
    status: 'idle',
    followup(message) { messages.push(message) },
  }
  const ctx = { agents: { get: () => agent, list: () => [agent] } }
  let controller
  let ackCalls = 0
  const ackBodies = []
  const body = new ReadableStream({
    start(next) { controller = next },
  })
  const fetchImpl = async (url, init) => {
    if (url.endsWith('/events')) {
      return { ok: true, status: 200, body }
    }
    if (url.endsWith('/ack')) {
      ackCalls += 1
      ackBodies.push(JSON.parse(init.body))
      return { ok: true, status: 200, json: async () => ({ ackedRevision: 7 }) }
    }
    throw new Error(`unexpected URL ${url}`)
  }
  const client = createInboxClient(ctx, {
    baseUrl: 'http://127.0.0.1:18766',
    inboxId: 'bma3',
    token: 'secret',
    fetchImpl,
    pumpMs: 10,
  })
  try {
    client.start()
    controller.enqueue(new TextEncoder().encode(
      'id: 7\nevent: panel\ndata: ' + JSON.stringify({
        inboxId: 'bma3',
        revision: 7,
        totalItems: 1,
        includeOmittedCount: true,
        items: [{
          id: 'itm-test-1',
          source: 'qq_group/bot-1/955432692',
          sourceLabel: 'QQ群 955432692',
          priority: 300,
          totalMessages: 2,
          omittedMessages: 0,
          entries: [{ line: '[20:08] #101 LvtN：欢迎回来？ [@3924945183]' }],
        }],
      }) + '\n\n'))
    const deadline = Date.now() + 2000
    while (messages.length === 0 && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 10))
    }
    assert.equal(messages.length, 1)
    assert.match(messages[0].content[0].text, /^\[INBOX_PANEL\]/)
    assert.match(messages[0].content[0].text, /QQ群 955432692/)
    assert.match(messages[0].content[0].text, /\[@3924945183\]/)
    assert.equal(ackCalls, 1)
    assert.ok(ackBodies.at(-1).itemIds?.length, 'ack 必须带上面板里实际显示的 itemIds')
    assert.equal(ackBodies.at(-1).itemIds.length, 1)
  } finally {
    client.stop()
    controller.close()
  }
})

test('pause stops injection without dropping the SSE, resume picks the next panel up', async () => {
  const messages = []
  const agent = {
    id: 'session-1',
    status: 'idle',
    followup(message) { messages.push(message) },
  }
  const ctx = { agents: { get: () => agent, list: () => [agent] } }
  let controller
  let ackCalls = 0
  const body = new ReadableStream({ start(next) { controller = next } })
  const fetchImpl = async (url) => {
    if (url.endsWith('/events')) return { ok: true, status: 200, body }
    if (url.endsWith('/ack')) {
      ackCalls += 1
      return { ok: true, status: 200, json: async () => ({ ackedRevision: 9 }) }
    }
    throw new Error(`unexpected URL ${url}`)
  }
  const client = createInboxClient(ctx, {
    baseUrl: 'http://127.0.0.1:18766',
    inboxId: 'bma3',
    token: 'secret',
    fetchImpl,
    pumpMs: 10,
  })
  const panel = (revision) => 'id: ' + revision + '\nevent: panel\ndata: ' + JSON.stringify({
    inboxId: 'bma3', revision, totalItems: 1, includeOmittedCount: true,
    items: [{
      id: 'itm-test-' + revision,
      source: 'qq_group/bot-1/1', sourceLabel: 'QQ群 1', priority: 300,
      totalMessages: 1, omittedMessages: 0, entries: [{ line: '[12:00] #1 A：hi' }],
    }],
  }) + '\n\n'
  try {
    client.start()
    client.pause()
    controller.enqueue(new TextEncoder().encode(panel(8)))
    await new Promise((resolve) => setTimeout(resolve, 200))
    assert.equal(messages.length, 0, '暂停期间不能注入')
    assert.equal(ackCalls, 0, '暂停期间不能自己 ack（否则这条就永远看不到）')
    assert.equal(client.status().paused, true)

    client.resume()
    controller.enqueue(new TextEncoder().encode(panel(9)))
    const deadline = Date.now() + 2000
    while (messages.length === 0 && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 10))
    }
    assert.equal(messages.length, 1)
    assert.equal(ackCalls, 1)
  } finally {
    client.stop()
    controller.close()
  }
})
