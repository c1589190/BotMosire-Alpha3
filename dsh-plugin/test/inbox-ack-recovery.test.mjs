import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, renameSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'

import { createInboxClient, inboxStatePaths } from '../lib/inbox.js'
import { inboxWakeId, registerInboxRiskRecovery } from '../lib/inbox-risk.js'

const IDENTITY = 'inb_test_alpha'
const encoder = new TextEncoder()

function tempState() {
  const dir = mkdtempSync(join(tmpdir(), 'bma3-ack-'))
  const stateFile = join(dir, 'state.json')
  return { dir, stateFile, paths: inboxStatePaths({ stateFile }) }
}

/** Response object shaped like the subset of the fetch Response the client reads. */
function json(value, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async json() { return value },
  }
}

function ackComplete(body, { ackedRevision = 1, identity = body.inboxIdentity } = {}) {
  return json({
    ackedRevision,
    ackedItemIds: [...body.itemIds],
    alreadyAckedItemIds: [],
    missingItemIds: [],
    inboxIdentity: identity,
    complete: true,
  })
}

function makeItem(id, { body = `body-${id}`, frozenRevision } = {}) {
  return {
    id,
    source: 'qq_group/bot-1/1',
    sourceLabel: 'QQ群 1',
    priority: 300,
    totalMessages: 1,
    omittedMessages: 0,
    continued: false,
    entries: [{ line: `[12:00] #1 A：${body}` }],
    ...(frozenRevision === undefined ? {} : { frozenRevision }),
  }
}

function makePanel({
  revision = 1,
  pushRevision = revision,
  identity = IDENTITY,
  inboxId = 'bma3',
  items = [],
} = {}) {
  return {
    inboxId,
    revision,
    pushRevision,
    totalItems: items.length,
    omittedItems: 0,
    includeOmittedCount: true,
    items,
    ...(identity === undefined ? {} : { inboxIdentity: identity }),
  }
}

function frame(panel) {
  return `id: ${panel.revision}\nevent: panel\ndata: ${JSON.stringify(panel)}\n\n`
}

function fakeInbox({ ack, panel = makePanel({ revision: 1, pushRevision: 1, items: [] }) } = {}) {
  const state = {
    ackCalls: 0,
    ackBodies: [],
    getPanelCalls: 0,
    requests: [],
    controllers: [],
  }
  const server = {
    state,
    panel,
    fetchImpl: async (url, init = {}) => {
      state.requests.push({ url, init })
      if (url.endsWith('/events')) {
        let controller
        const body = new ReadableStream({ start(next) { controller = next } })
        state.controllers.push(controller)
        return { ok: true, status: 200, body }
      }
      if (url.endsWith('/ack')) {
        state.ackCalls += 1
        const body = JSON.parse(init.body)
        state.ackBodies.push(body)
        return ack(body, state)
      }
      if (url.endsWith('/panel') && (!init.method || init.method === 'GET')) {
        state.getPanelCalls += 1
        if (server.panelDelayMs) await new Promise((resolve) => setTimeout(resolve, server.panelDelayMs))
        return json(server.panel)
      }
      if (url.endsWith('/plugin/heartbeat')) {
        return json({ mcpStartId: 'mcp-1' })
      }
      throw new Error(`unexpected URL ${url}`)
    },
    push(value) {
      const controller = state.controllers.at(-1)
      assert.ok(controller, 'SSE connection must be open before pushing a panel')
      controller.enqueue(encoder.encode(frame(value)))
    },
  }
  return server
}

function agentRecorder(id, messages, behavior) {
  return {
    id,
    status: 'idle',
    async followup(message) {
      messages.push({ id, message })
      if (behavior) await behavior(message)
    },
  }
}

function ctxFor(...agents) {
  const byId = new Map(agents.map((agent) => [agent.id, agent]))
  return {
    agents: {
      get: (id) => byId.get(id),
      list: () => [...byId.values()],
    },
  }
}

async function waitFor(predicate, message = 'condition', timeout = 2500) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await predicate()) return
    await new Promise((resolve) => setTimeout(resolve, 5))
  }
  throw new Error(`timed out waiting for ${message}`)
}

function config(server, stateFile, extra = {}) {
  return {
    baseUrl: 'http://127.0.0.1:18766',
    inboxId: 'bma3',
    stateFile,
    fetchImpl: server.fetchImpl,
    pumpMs: 5,
    heartbeatMs: 100000,
    ackRetryBaseMs: 10,
    ackMaxAttempts: 8,
    ...extra,
  }
}

test('followup persists intent, then a pending ACK with the original identity/revisions/IDs', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  let intentAtFollowup = null
  const agent = agentRecorder('session-1', messages, async () => {
    const during = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.equal(during.pendingAck, null, 'pending ACK is only written after followup succeeds')
    assert.ok(during.injectionIntent, 'body-free injection intent is on disk before followup')
    intentAtFollowup = during.injectionIntent
  })
  const panel = makePanel({
    revision: 11,
    pushRevision: 7,
    items: [makeItem('itm-a', { body: 'SECRET_BODY_ALPHA', frozenRevision: 7 })],
  })
  let ackRound = 0
  const server = fakeInbox({
    panel: makePanel({ revision: 12, pushRevision: 7, items: [] }),
    ack: async (body) => {
      ackRound += 1
      const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
      assert.equal(saved.version, 2)
      assert.equal(saved.injectedPushRevision, 7, 'watermark is persisted before ACK')
      assert.ok(saved.pendingAck, 'pending ACK is persisted before ACK')
      assert.equal(saved.pendingAck.inboxIdentity, IDENTITY)
      assert.equal(saved.pendingAck.inboxId, 'bma3')
      assert.equal(saved.pendingAck.targetSessionId, 'session-1')
      assert.equal(saved.pendingAck.panelRevision, 11)
      assert.equal(saved.pendingAck.pushRevision, 7)
      assert.deepEqual(saved.pendingAck.itemIds, ['itm-a'])
      assert.equal(saved.injectionIntent, null)
      assert.deepEqual(body, { revision: 11, itemIds: ['itm-a'], inboxIdentity: IDENTITY })
      assert.doesNotMatch(JSON.stringify(saved), /SECRET_BODY_ALPHA/)
      if (ackRound === 1) return json({ error: 'unavailable' }, 503)
      return ackComplete(body, { ackedRevision: 12 })
    },
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(panel)
    await waitFor(() => messages.length === 1, 'one followup')
    await waitFor(() => server.state.ackCalls >= 2 && client.status().pendingAck === null, 'ACK success')
    assert.equal(messages.length, 1, 'ACK retry must not cause a second followup')
    assert.ok(intentAtFollowup, 'intent was visible to the followup call')
    assert.deepEqual(intentAtFollowup.itemIds, ['itm-a'])
    assert.equal(server.state.ackCalls, 2)
    assert.deepEqual(server.state.ackBodies[0], server.state.ackBodies[1], 'retry uses the original request')
    await waitFor(() => server.state.getPanelCalls === 1, 'fresh panel fetched after confirmed ACK')
    const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.equal(saved.pendingAck, null)
    assert.equal(saved.injectionIntent, null)
    assert.equal(saved.injectedPushRevision, 7)
    assert.equal(saved.ackedRevision, 12)
    assert.doesNotMatch(readFileSync(paths.v2, 'utf8'), /SECRET_BODY_ALPHA/)
  } finally {
    client.dispose()
  }
})

test('a pending ACK blocks new panel injection until a provable newer push is resolved', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const first = makePanel({
    revision: 1, pushRevision: 1,
    items: [makeItem('itm-1', { body: 'FIRST_BODY', frozenRevision: 1 })],
  })
  const second = makePanel({
    revision: 2, pushRevision: 2,
    items: [makeItem('itm-2', { body: 'SECOND_BODY', frozenRevision: 2 })],
  })
  let failAck = true
  const server = fakeInbox({
    panel: makePanel({ revision: 2, pushRevision: 2, items: [] }),
    ack: async (body) => (failAck ? json({ error: 'unavailable' }, 503) : ackComplete(body)),
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(first)
    await waitFor(() => messages.length === 1 && client.status().pendingAck, 'first push pending')
    server.push(second)
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(messages.length, 1, 'pending ACK must block a later push')

    server.panel = second
    failAck = false
    await waitFor(() => messages.length === 2 && client.status().pendingAck === null, 'provable push after ACK')
    assert.match(messages[1].message.content[0].text, /SECOND_BODY/)
    assert.doesNotMatch(messages[1].message.content[0].text, /FIRST_BODY/)
    await new Promise((resolve) => setTimeout(resolve, 80))
    assert.equal(messages.length, 2, 'resolved push is injected exactly once')
  } finally {
    client.dispose()
  }
})

test('an old server without inboxIdentity causes zero followups and zero ACKs', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const server = fakeInbox({
    ack: async (body) => ackComplete(body),
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(makePanel({
      revision: 3, pushRevision: 3, identity: null,
      items: [makeItem('itm-old', { frozenRevision: 3 })],
    }))
    await new Promise((resolve) => setTimeout(resolve, 150))
    assert.equal(messages.length, 0)
    assert.equal(server.state.ackCalls, 0)
    assert.match(String(client.status().blockedReason), /identity/i)
  } finally {
    client.dispose()
  }
})

test('incomplete per-item ACK proof retains the pending record and never injects again', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const panel = makePanel({
    revision: 4, pushRevision: 4,
    items: [
      makeItem('itm-1', { frozenRevision: 4 }),
      makeItem('itm-2', { frozenRevision: 4 }),
    ],
  })
  const server = fakeInbox({
    ack: async (body) => json({
      ackedRevision: 5,
      ackedItemIds: [body.itemIds[0]],
      alreadyAckedItemIds: [],
      missingItemIds: [],
      inboxIdentity: IDENTITY,
      complete: false,
    }),
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(panel)
    await waitFor(() => messages.length === 1 && client.status().pendingAck, 'pending')
    await waitFor(() => server.state.ackCalls === 1, 'first ACK attempt')
    await new Promise((resolve) => setTimeout(resolve, 150))
    assert.equal(server.state.ackCalls, 1, 'partial proof is not automatically retried')
    assert.ok(client.status().pendingAck, 'partial proof retains the pending record')
    assert.match(String(client.status().lastError), /proof|complete/i)
    const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.ok(saved.pendingAck)
    assert.equal(messages.length, 1)
  } finally {
    client.dispose()
  }
})

test('a 409 retains the record, stops retries, and can only be discarded with confirmation', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const panel = makePanel({
    revision: 5, pushRevision: 5,
    items: [makeItem('itm-409', { frozenRevision: 5 })],
  })
  const server = fakeInbox({
    ack: async () => json({
      error: 'inbox_identity_mismatch',
      inboxIdentity: 'inb_other',
    }, 409),
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(panel)
    await waitFor(() => server.state.ackCalls === 1 && client.status().pendingAck, '409 recorded')
    await new Promise((resolve) => setTimeout(resolve, 150))
    assert.equal(server.state.ackCalls, 1, '409 is not retried automatically')
    assert.match(String(client.status().lastError), /409/)
    assert.throws(() => client.discard(), /confirm/i)
    assert.throws(() => client.discard(false), /confirm/i)
    assert.ok(client.status().pendingAck)
    const result = client.discard(true)
    assert.match(result, /重复|未确认/u)
    assert.equal(client.status().pendingAck, null)
    assert.equal(JSON.parse(readFileSync(paths.v2, 'utf8')).pendingAck, null)
  } finally {
    client.dispose()
  }
})

test('a server identity change stops injection until an explicit manual accept', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const first = makePanel({
    revision: 1, pushRevision: 1, identity: IDENTITY,
    items: [makeItem('itm-a', { frozenRevision: 1 })],
  })
  const second = makePanel({
    revision: 2, pushRevision: 2, identity: 'inb_rotated',
    items: [makeItem('itm-b', { frozenRevision: 2 })],
  })
  const server = fakeInbox({
    panel: makePanel({ revision: 1, pushRevision: 1, identity: IDENTITY, items: [] }),
    ack: async (body) => ackComplete(body),
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(first)
    await waitFor(() => messages.length === 1 && client.status().pendingAck === null, 'first ACK')
    server.push(second)
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(messages.length, 1)
    assert.equal(client.status().identityMismatch?.seen, 'inb_rotated')
    assert.throws(() => client.acceptIdentity(false), /confirm/i)
    client.acceptIdentity(true)
    assert.equal(client.status().identityMismatch, null)
    server.push(second)
    await waitFor(() => messages.length === 2, 'injection resumes after explicit accept')
  } finally {
    client.dispose()
  }
})

test('a restart does not ACK before proving the current server identity', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const panel = makePanel({
    revision: 17, pushRevision: 17,
    items: [makeItem('itm-downgrade', { frozenRevision: 17 })],
  })
  const firstServer = fakeInbox({ ack: async () => json({ error: 'down' }, 503) })
  const first = createInboxClient(ctxFor(agent), config(firstServer, stateFile, { ackMaxAttempts: 100 }))
  first.start()
  firstServer.push(panel)
  await waitFor(() => messages.length === 1 && first.status().pendingAck, 'pending before restart')
  first.dispose()

  const oldPanel = makePanel({ revision: 17, pushRevision: 17, identity: null, items: [] })
  const oldServer = fakeInbox({ ack: async (body) => ackComplete(body), panel: oldPanel })
  const second = createInboxClient(ctxFor(agent), config(oldServer, stateFile))
  try {
    second.start()
    await waitFor(() => second.status().serverMissingIdentity === true, 'old server detected')
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(oldServer.state.ackCalls, 0, 'no ACK against an unverified/old server')
    assert.match(String(second.status().blockedReason), /identity/i)
    assert.equal(messages.length, 1, 'no reinjection after restart')
  } finally {
    second.dispose()
  }
})

test('a restart resumes the original ACK and never reinjects the same push', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const panel = makePanel({
    revision: 3, pushRevision: 3,
    items: [makeItem('itm-restart', { frozenRevision: 3 })],
  })
  let ackFail = true
  const firstServer = fakeInbox({
    ack: async (body) => (ackFail ? json({ error: 'down' }, 503) : ackComplete(body)),
  })
  const first = createInboxClient(ctxFor(agent), config(firstServer, stateFile, { ackMaxAttempts: 100 }))
  first.start()
  firstServer.push(panel)
  await waitFor(() => messages.length === 1 && first.status().pendingAck, 'pending before restart')
  first.dispose()

  ackFail = false
  const secondServer = fakeInbox({
    panel: makePanel({ revision: 3, pushRevision: 3, items: [] }),
    ack: async (body) => ackComplete(body),
  })
  const second = createInboxClient(ctxFor(agent), config(secondServer, stateFile))
  try {
    second.start()
    secondServer.push(panel)
    await waitFor(() => second.status().pendingAck === null, 'ACK after restart')
    assert.equal(messages.length, 1, 'restart must not inject the same push again')
    assert.equal(secondServer.state.ackCalls, 1)
  } finally {
    second.dispose()
  }
})

test('a followup that throws after a possible side effect leaves an uncertain intent, not a retry', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages, async () => {
    throw new Error('followup response lost')
  })
  const panel = makePanel({
    revision: 6, pushRevision: 6,
    items: [makeItem('itm-uncertain', { frozenRevision: 6 })],
  })
  const server = fakeInbox({ ack: async (body) => ackComplete(body) })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(panel)
    await waitFor(() => messages.length === 1 && client.status().injectionIntent, 'uncertain intent')
    await new Promise((resolve) => setTimeout(resolve, 150))
    assert.equal(messages.length, 1, 'no automatic reinjection')
    assert.equal(server.state.ackCalls, 0, 'no ACK without a confirmed followup')
    const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.ok(saved.injectionIntent)
    assert.equal(saved.pendingAck, null)
    assert.match(String(client.status().blockedReason), /uncertain|intent/i)

    client.dispose()
    const restartedMessages = []
    const restarted = createInboxClient(
      ctxFor(agentRecorder('session-1', restartedMessages)),
      config(fakeInbox({ ack: async (body) => ackComplete(body) }), stateFile),
    )
    try {
      restarted.start()
      await new Promise((resolve) => setTimeout(resolve, 100))
      assert.equal(restartedMessages.length, 0, 'restart must not reinject an uncertain intent')
      assert.equal(restarted.status().injectionIntent?.itemIds[0], 'itm-uncertain')
      restarted.discard(true)
      assert.equal(restarted.status().injectionIntent, null)
    } finally {
      restarted.dispose()
    }
  } finally {
    client.dispose()
  }
})

test('a state write failure after followup never ACKs or repeats in-process', async () => {
  const { dir, stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages, async () => {
    chmodSync(dir, 0o500)
  })
  const panel = makePanel({
    revision: 8, pushRevision: 8,
    items: [makeItem('itm-writefail', { frozenRevision: 8 })],
  })
  const server = fakeInbox({ ack: async (body) => ackComplete(body) })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(panel)
    await waitFor(() => messages.length === 1, 'followup')
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(server.state.ackCalls, 0, 'a failed snapshot write must not ACK')
    assert.equal(messages.length, 1, 'no in-process repeat')
    assert.ok(client.status().injectionIntent, 'intent remains as the honest uncertain record')
    assert.match(String(client.status().lastError), /state/i)
  } finally {
    chmodSync(dir, 0o700)
    const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.ok(saved.injectionIntent, 'on-disk intent survives the failed pending-ACK write')
    assert.equal(saved.pendingAck, null)
    client.dispose()
  }
})

test('an overlapping writer is refused by the state-file lock and cannot inject', async () => {
  const { stateFile } = tempState()
  const messagesA = []
  const messagesB = []
  const agentA = agentRecorder('session-1', messagesA)
  const agentB = agentRecorder('session-1', messagesB)
  const panel = makePanel({
    revision: 9, pushRevision: 9,
    items: [makeItem('itm-lock', { frozenRevision: 9 })],
  })
  let observer = null
  const serverA = fakeInbox({
    ack: async (body) => {
      if (!observer) {
        observer = createInboxClient(ctxFor(agentB), config(fakeInbox({ ack: async (b) => ackComplete(b) }), stateFile))
      }
      await new Promise((resolve) => setTimeout(resolve, 30))
      return ackComplete(body)
    },
  })
  const clientA = createInboxClient(ctxFor(agentA), config(serverA, stateFile))
  try {
    clientA.start()
    serverA.push(panel)
    await waitFor(() => messagesA.length === 1 && observer, 'in-flight ACK overlap probe')
    assert.ok(observer.status().lockError, 'second writer must fail closed on the held lock')
    observer.start()
    await new Promise((resolve) => setTimeout(resolve, 80))
    assert.equal(messagesB.length, 0)
    observer.dispose()
    clientA.dispose()
    const later = createInboxClient(ctxFor(agentB), config(fakeInbox({ ack: async (b) => ackComplete(b) }), stateFile))
    assert.equal(later.status().lockError, null, 'lock is released on safe disposal')
    later.dispose()
  } finally {
    observer?.dispose()
    clientA.dispose()
  }
})

test('stop and unbind suspend automatic ACK retries; start and bind resume them', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const panel = makePanel({
    revision: 10, pushRevision: 10,
    items: [makeItem('itm-stop', { frozenRevision: 10 })],
  })
  let mode = 'fail'
  const server = fakeInbox({
    ack: async (body) => (mode === 'fail' ? json({ error: 'down' }, 503) : ackComplete(body)),
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile, { ackMaxAttempts: 1000 }))
  try {
    client.start()
    server.push(panel)
    await waitFor(() => server.state.ackCalls >= 1 && client.status().pendingAck, 'retrying')
    client.stop()
    const afterStop = server.state.ackCalls
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(server.state.ackCalls, afterStop, 'stop suspends ACK retries')
    mode = 'ok'
    client.start()
    await waitFor(() => client.status().pendingAck === null, 'start resumes ACK')

    const secondState = tempState()
    const messages2 = []
    const agent2 = agentRecorder('session-2', messages2)
    const panel2 = makePanel({
      revision: 11, pushRevision: 11,
      items: [makeItem('itm-unbind', { frozenRevision: 11 })],
    })
    let mode2 = 'fail'
    const server2 = fakeInbox({
      ack: async (body) => (mode2 === 'fail' ? json({ error: 'down' }, 503) : ackComplete(body)),
    })
    const client2 = createInboxClient(ctxFor(agent2), config(server2, secondState.stateFile, { ackMaxAttempts: 1000 }))
    try {
      client2.start()
      server2.push(panel2)
      await waitFor(() => server2.state.ackCalls >= 1 && client2.status().pendingAck, 'pending while bound')
      client2.unbind()
      const afterUnbind = server2.state.ackCalls
      await new Promise((resolve) => setTimeout(resolve, 120))
      assert.equal(server2.state.ackCalls, afterUnbind, 'unbind suspends ACK retries')
      mode2 = 'ok'
      client2.bind('session-2')
      await waitFor(() => client2.status().pendingAck === null, 'bind resumes ACK')
    } finally {
      client2.dispose()
    }
  } finally {
    client.dispose()
  }
})

test('changing the binding never sends the original pending panel to the new session', async () => {
  const { stateFile } = tempState()
  const messagesA = []
  const messagesB = []
  const agentA = agentRecorder('session-1', messagesA)
  const agentB = agentRecorder('session-2', messagesB)
  const first = makePanel({
    revision: 12, pushRevision: 12,
    items: [makeItem('itm-old', { body: 'OLD_TARGET_BODY', frozenRevision: 12 })],
  })
  let failAck = true
  const server = fakeInbox({
    panel: makePanel({ revision: 12, pushRevision: 12, items: [] }),
    ack: async (body) => (failAck ? json({ error: 'down' }, 503) : ackComplete(body)),
  })
  const client = createInboxClient(ctxFor(agentA, agentB), config(server, stateFile, { ackMaxAttempts: 1000 }))
  try {
    client.start()
    server.push(first)
    await waitFor(() => messagesA.length === 1 && client.status().pendingAck, 'pending in session 1')
    client.bind('session-2')
    failAck = false
    await waitFor(() => client.status().pendingAck === null, 'ACK resolves after rebinding')
    await new Promise((resolve) => setTimeout(resolve, 100))
    assert.equal(messagesA.length, 1)
    for (const entry of messagesB) {
      assert.doesNotMatch(entry.message.content[0].text, /OLD_TARGET_BODY/)
    }
  } finally {
    client.dispose()
  }
})

test('network errors and a lost 200 body retry the exact original ACK before clearing', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const panel = makePanel({
    revision: 14, pushRevision: 14,
    items: [makeItem('itm-net', { frozenRevision: 14 })],
  })
  let round = 0
  const server = fakeInbox({
    ack: async (body) => {
      round += 1
      if (round === 1) throw new Error('socket hang up')
      if (round === 2) {
        return {
          ok: true,
          status: 200,
          async json() { throw new Error('body lost') },
        }
      }
      return ackComplete(body, { ackedRevision: 15 })
    },
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(panel)
    await waitFor(() => client.status().pendingAck === null && server.state.ackCalls === 3, 'retry to success')
    assert.equal(messages.length, 1)
    assert.deepEqual(server.state.ackBodies[0], server.state.ackBodies[1])
    assert.deepEqual(server.state.ackBodies[1], server.state.ackBodies[2])
    assert.equal(server.state.ackBodies[0].inboxIdentity, IDENTITY)
  } finally {
    client.dispose()
  }
})

test('a queued panel containing an already-confirmed ACK id is never reinjected', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const first = makePanel({
    revision: 15, pushRevision: 15,
    items: [makeItem('itm-shared', { body: 'ONCE_ONLY_BODY', frozenRevision: 15 })],
  })
  const queued = makePanel({
    revision: 16, pushRevision: 16,
    items: [
      makeItem('itm-shared', { body: 'ONCE_ONLY_BODY', frozenRevision: 16 }),
      makeItem('itm-new', { body: 'NEW_BODY', frozenRevision: 16 }),
    ],
  })
  const server = fakeInbox({
    panel: makePanel({ revision: 16, pushRevision: 16, items: [] }),
    ack: async (body) => ackComplete(body),
  })
  server.panelDelayMs = 250
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(first)
    await waitFor(() => messages.length === 1 && server.state.ackCalls === 1, 'first ACK request')
    await waitFor(() => client.status().pendingAck === null && server.state.getPanelCalls === 1,
      'ACK persisted and fresh verification started')
    server.push(queued)
    await new Promise((resolve) => setTimeout(resolve, 80))
    assert.equal(messages.length, 1, 'a queued snapshot with an ACKed id must not repeat it')
    assert.match(String(client.status().deferredPush?.reason ?? ''), /ACK|confirmed|queued/i)
    await new Promise((resolve) => setTimeout(resolve, 250))
    assert.equal(messages.length, 1)
  } finally {
    client.dispose()
  }
})

test('persisted bytes never contain the panel body or the inbox token', async () => {
  const { dir, stateFile, paths } = tempState()
  const envFile = join(dir, '.bma3env')
  const { writeFileSync } = await import('node:fs')
  writeFileSync(envFile, 'INBOX_TOKEN=secret-token-do-not-persist\n')
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const panel = makePanel({
    revision: 13, pushRevision: 13,
    items: [makeItem('itm-secret', { body: 'SECRET_PANEL_BODY_XYZ', frozenRevision: 13 })],
  })
  const server = fakeInbox({ ack: async (body) => json({ error: 'down' }, 503) })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile, { envFile }))
  try {
    client.start()
    server.push(panel)
    await waitFor(() => client.status().pendingAck && server.state.ackCalls >= 1, 'pending write')
    const bytes = readFileSync(paths.v2, 'utf8')
    assert.doesNotMatch(bytes, /SECRET_PANEL_BODY_XYZ/)
    assert.doesNotMatch(bytes, /secret-token-do-not-persist/)
    assert.doesNotMatch(JSON.stringify(client.status()), /secret-token-do-not-persist|SECRET_PANEL_BODY_XYZ/)
  } finally {
    client.dispose()
  }
})

function riskSession(id) {
  const events = []
  const nodes = []
  return {
    id,
    get seq() { return events.length },
    surface: { nodes },
    eventAt: (seq) => events[seq],
    append(type, data, options = {}) {
      const seq = events.length
      const event = { type, data, seq, ...options }
      const op = options.surfaceOp
      if (op === 'append') nodes.push(seq)
      if (op?.op === 'replace') {
        const index = nodes.indexOf(op.startSeq)
        assert.notEqual(index, -1)
        assert.equal(op.startSeq, op.endSeq)
        nodes.splice(index, 1, seq)
      }
      events.push(event)
      return event
    },
  }
}

function contentRisk(agent, overrides) {
  return {
    agent, turn: 2, step: 1, provider: 'deepseek-official',
    failure: { status: 400, code: 'INVALID_REQUEST', message: 'Content Exists Risk (request_id: mock-id)' },
    signal: new AbortController().signal, ...overrides,
  }
}

test('Inbox downgrades one rejected wake, then pauses on another rejection without retracting ACK', async (t) => {
  const { stateFile, paths } = tempState()
  const messages = []
  const diagnostics = []
  let onError
  let finishAck
  const allowAck = new Promise((resolve) => { finishAck = resolve })
  const session = riskSession('session-1')
  const earlier = session.append('user/message', { id: 'user-1', source: { kind: 'user' } }, { surfaceOp: 'append' })
  const agent = agentRecorder(session.id, messages, (message) => {
    session.append('turn/start', { turn: 2 })
    session.append('step/start', { turn: 2, step: 1 })
    session.append('user/message', message, { surfaceOp: 'append' })
  })
  agent.session = session
  const ctx = {
    ...ctxFor(agent),
    on(event, handler) {
      assert.equal(event, 'agent/request-error')
      onError = handler
      return () => { onError = undefined }
    },
    logger: () => ({ warn: (message) => diagnostics.push(message) }),
  }
  const panel = makePanel({
    revision: 11, pushRevision: 7,
    items: [makeItem('itm-1', { body: 'MOCK_PANEL_BODY', frozenRevision: 7 })],
  })
  const server = fakeInbox({
    panel: makePanel({ revision: 12, pushRevision: 7 }),
    ack: async (body) => { await allowAck; return ackComplete(body, { ackedRevision: 12 }) },
  })
  const client = createInboxClient(ctx, config(server, stateFile))
  t.after(() => { finishAck(); client.dispose() })
  client.start()
  server.push(panel)
  await waitFor(() => messages.length === 1 && server.state.ackCalls === 1, 'initial followup and original ACK')

  const failure = contentRisk(agent)
  const next = () => assert.fail('the Inbox handler owns this recovery')
  const original = session.surface.nodes.at(-1)
  assert.deepEqual(await onError(failure, next), { kind: 'retry' })
  const replacement = session.eventAt(session.surface.nodes.at(-1))
  assert.deepEqual(replacement.sourceEventSeqs, [original])
  assert.deepEqual(replacement.data.source, {
    kind: 'bma3-inbox', revision: 11, pushRevision: 7, contentRiskFallback: true,
  })
  assert.doesNotMatch(replacement.data.content[0].text, /MOCK_PANEL_BODY/)
  assert.match(session.eventAt(original).data.content[0].text, /MOCK_PANEL_BODY/)
  assert.deepEqual(session.surface.nodes, [earlier.seq, replacement.seq])
  assert.equal(client.status().paused, false)

  assert.equal(await onError(failure, next), undefined)
  assert.equal(session.seq, replacement.seq + 1)
  assert.equal(client.status().paused, true)
  assert.deepEqual(diagnostics, ['inbox content_risk_paused rev=11 push=7'])
  assert.equal(JSON.parse(readFileSync(paths.v2, 'utf8')).paused, true)
  finishAck()
  await waitFor(() => client.status().pendingAck === null, 'ACK finishes while paused')
  assert.deepEqual(server.state.ackBodies, [{ revision: 11, itemIds: ['itm-1'], inboxIdentity: IDENTITY }])
  assert.equal(client.status().injectedPushRevision, 7)
  assert.equal(messages.length, 1)

  client.dispose()
  assert.equal(onError, undefined)
  const restored = createInboxClient(ctx, config(server, stateFile))
  t.after(() => restored.dispose())
  assert.equal(restored.status().paused, true)
  assert.equal(restored.resume().paused, false)
})

test('Inbox delegates unowned failures and skips messages that cannot be replaced safely', async () => {
  const delegates = Symbol('delegate')
  const next = () => delegates
  const ctx = { on(_event, handler) { return handler } }
  const onError = registerInboxRiskRecovery(ctx, (agent) => agent.id === 'bound', () => assert.fail('unexpected pause'))
  const wake = {
    id: inboxWakeId(11, 7), role: 'user', content: [{ type: 'text', text: 'fixture' }],
    source: { kind: 'bma3-inbox', revision: 11, pushRevision: 7 },
  }
  const synthetic = (id = 'bound') => {
    const session = riskSession(id)
    session.append('step/start', { turn: 2, step: 1 })
    return { id, session }
  }
  const bound = synthetic()
  bound.session.append('user/message', wake, { surfaceOp: 'append' })
  for (const overrides of [
    { provider: 'other' }, { step: 2 }, { turn: 3 },
    { failure: { status: 413, code: 'INVALID_REQUEST', message: 'Content Exists Risk' } },
    { failure: { status: 400, code: 'INVALID_REQUEST', message: 'Invalid tool schema' } },
    { signal: AbortSignal.abort() },
  ]) assert.equal(await onError(contentRisk(bound, overrides), next), delegates)
  assert.equal(bound.session.seq, 2)

  const foreign = synthetic('foreign')
  foreign.session.append('user/message', wake, { surfaceOp: 'append' })
  assert.equal(await onError(contentRisk(foreign), next), delegates)
  assert.equal(foreign.session.seq, 2)
  const current = synthetic()
  current.session.append('user/message', { ...wake, source: { kind: 'user' } }, { surfaceOp: 'append' })
  assert.equal(await onError(contentRisk(current), next), delegates)
  bound.session.append('user/message', { ...wake, id: 'human', source: { kind: 'user' } }, { surfaceOp: 'append' })
  assert.equal(await onError(contentRisk(bound), next), delegates)

  const detached = synthetic()
  const removed = detached.session.append('user/message', wake, { surfaceOp: 'append' })
  detached.session.surface.nodes.splice(0, 1)
  assert.equal(await onError(contentRisk(detached), next), delegates)
  assert.equal(detached.session.eventAt(removed.seq), removed)
  const readOnly = synthetic()
  readOnly.session.append('user/message', wake, { surfaceOp: 'append' })
  readOnly.session.append = () => { throw new Error('read-only session') }
  assert.equal(await onError(contentRisk(readOnly), next), delegates)
  assert.equal(await onError(contentRisk({ id: 'bound', session: { id: 'bound' } }), next), delegates)
})

test('Inbox remains paused in memory when the content-risk pause cannot be saved', async (t) => {
  const { stateFile, paths } = tempState()
  const warnings = []
  let onError
  const session = riskSession('session-1')
  const agent = { id: session.id, session }
  const ctx = {
    ...ctxFor(agent),
    on(_event, handler) { onError = handler },
    logger: () => ({ warn: (message) => warnings.push(message) }),
  }
  const server = fakeInbox()
  const client = createInboxClient(ctx, config(server, stateFile))
  t.after(() => client.dispose())
  client.bind(agent.id)
  client.start()
  session.append('step/start', { turn: 2, step: 1 })
  session.append('user/message', {
    id: inboxWakeId(11, 7), role: 'user', content: [{ type: 'text', text: 'fixture' }],
    source: { kind: 'bma3-inbox', revision: 11, pushRevision: 7 },
  }, { surfaceOp: 'append' })
  const next = () => assert.fail('the Inbox handler owns this recovery')
  assert.deepEqual(await onError(contentRisk(agent), next), { kind: 'retry' })
  renameSync(paths.v2, `${paths.v2}.fixture`)
  mkdirSync(paths.v2)
  assert.equal(await onError(contentRisk(agent), next), undefined)
  assert.equal(client.status().paused, true)
  assert.deepEqual(warnings, ['inbox content_risk_pause_not_saved rev=11 push=7'])
})

