import assert from 'node:assert/strict'
import {
  mkdtempSync, readFileSync, statSync, unlinkSync, writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'

import { createInboxClient, inboxStatePaths } from '../lib/inbox.js'

const IDENTITY = 'inb_state_beta'
const encoder = new TextEncoder()

function tempState() {
  const dir = mkdtempSync(join(tmpdir(), 'bma3-state-'))
  const stateFile = join(dir, 'state.json')
  return { dir, stateFile, paths: inboxStatePaths({ stateFile }) }
}

function json(value, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async json() { return value },
  }
}

function ackComplete(body) {
  return json({
    ackedRevision: Number(body.revision) + 1,
    ackedItemIds: [...body.itemIds],
    alreadyAckedItemIds: [],
    missingItemIds: [],
    inboxIdentity: body.inboxIdentity,
    complete: true,
  })
}

function item(id, { frozenRevision = 1, body = `body-${id}` } = {}) {
  return {
    id,
    source: 'qq_group/bot-1/1',
    sourceLabel: 'QQ群 1',
    priority: 300,
    totalMessages: 1,
    omittedMessages: 0,
    entries: [{ line: `[12:00] #1 A：${body}` }],
    frozenRevision,
  }
}

function panel({
  revision = 1, pushRevision = revision, identity = IDENTITY, items = [],
} = {}) {
  return {
    inboxId: 'bma3',
    revision,
    pushRevision,
    totalItems: items.length,
    omittedItems: 0,
    includeOmittedCount: true,
    items,
    ...(identity === undefined ? {} : { inboxIdentity: identity }),
  }
}

function frame(value) {
  return `id: ${value.revision}\nevent: panel\ndata: ${JSON.stringify(value)}\n\n`
}

function fakeInbox({ ack = async (body) => ackComplete(body), livePanel = null } = {}) {
  const state = { ackCalls: 0, ackBodies: [], getPanelCalls: 0, controllers: [], requests: [] }
  const server = {
    state,
    livePanel,
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
        return json(server.livePanel ?? panel({ revision: 0, pushRevision: 0, items: [] }))
      }
      if (url.endsWith('/plugin/heartbeat')) return json({ mcpStartId: 'mcp-1' })
      throw new Error(`unexpected URL ${url}`)
    },
    push(value) {
      const controller = state.controllers.at(-1)
      assert.ok(controller, 'SSE must be connected first')
      controller.enqueue(encoder.encode(frame(value)))
    },
  }
  return server
}

function agentRecorder(id, messages) {
  return {
    id,
    status: 'idle',
    async followup(message) { messages.push({ id, message }) },
  }
}

function ctxFor(...agents) {
  const byId = new Map(agents.map((agent) => [agent.id, agent]))
  return { agents: { get: (id) => byId.get(id), list: () => [...byId.values()] } }
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
    ackMaxAttempts: 4,
    ...extra,
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

function v1State(overrides = {}) {
  return {
    version: 1,
    targetSessionId: 'session-v1',
    paused: false,
    stopped: false,
    injectedPushRevision: 4,
    lastPushRevision: 4,
    ackedRevision: 3,
    lastMcpStartId: 'mcp-old',
    appliedControlId: 'control-1',
    restoredAt: null,
    updatedAt: '2026-11-15T00:00:00.000Z',
    ...overrides,
  }
}

test('a valid v1 snapshot is migrated to the deterministic v2 sibling and v1 is never rewritten', async () => {
  const { stateFile, paths } = tempState()
  const original = JSON.stringify(v1State())
  writeFileSync(stateFile, original, { mode: 0o600 })
  const messages = []
  const agent = agentRecorder('session-v1', messages)
  const server = fakeInbox()
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    assert.equal(client.status().lockError, null)
    assert.equal(client.status().stateFile, paths.v2)
    const migrated = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.equal(migrated.version, 2)
    assert.equal(migrated.targetSessionId, 'session-v1')
    assert.equal(migrated.injectedPushRevision, 4)
    assert.equal(migrated.ackedRevision, 3)
    assert.equal(migrated.pendingAck, null)
    assert.equal(migrated.injectionIntent, null)
    assert.equal(readFileSync(stateFile, 'utf8'), original, 'v1 evidence stays byte-identical')
  } finally {
    client.dispose()
  }
})

test('an old-version writer to v1 cannot erase the v2 pending record', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const panel1 = panel({
    revision: 20, pushRevision: 20,
    items: [item('itm-v2', { frozenRevision: 20 })],
  })
  let failAck = true
  const server = fakeInbox({
    ack: async (body) => (failAck ? json({ error: 'down' }, 503) : ackComplete(body)),
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile, { ackMaxAttempts: 1000 }))
  try {
    client.start()
    server.push(panel1)
    await waitFor(() => client.status().pendingAck && server.state.ackCalls >= 1, 'v2 pending')
    client.dispose()
  } finally {
    client.dispose()
  }
  const pendingBefore = JSON.parse(readFileSync(paths.v2, 'utf8')).pendingAck
  assert.ok(pendingBefore)
  // Simulate an old binary: rewrite the configured v1 path with stale fields.
  writeFileSync(stateFile, JSON.stringify(v1State({ targetSessionId: 'stale-old-writer' })), { mode: 0o600 })
  failAck = false
  const secondMessages = []
  const second = createInboxClient(
    ctxFor(agentRecorder('session-1', secondMessages)),
    config(fakeInbox({ ack: async (body) => ackComplete(body) }), stateFile),
  )
  try {
    assert.equal(second.status().targetSessionId, null, 'v2 wins over the stale v1 target')
    assert.notEqual(second.status().targetSessionId, 'stale-old-writer')
    assert.deepEqual(second.status().pendingAck?.itemIds, pendingBefore.itemIds)
  } finally {
    second.dispose()
  }
})

test('a corrupt v1 file without v2 fails closed, preserves bytes, and never injects', async () => {
  const { stateFile, paths } = tempState()
  const corrupt = '{"version":1, "targetSessionId":'
  writeFileSync(stateFile, corrupt, { mode: 0o600 })
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const server = fakeInbox()
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    assert.match(String(client.status().stateError), /corrupt|unsupported/i)
    client.start()
    assert.equal(client.status().running, false, 'corrupt state must not start the SSE engine')
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(messages.length, 0)
    assert.equal(server.state.ackCalls, 0)
    assert.equal(readFileSync(stateFile, 'utf8'), corrupt, 'corrupt evidence must not be cleared')
    assert.throws(() => statSync(paths.v2), /ENOENT/)
  } finally {
    client.dispose()
  }
})

test('an unsupported v2 version fails closed without rewriting the file', async () => {
  const { stateFile, paths } = tempState()
  const unsupported = JSON.stringify({ version: 99, targetSessionId: 'future' })
  writeFileSync(paths.v2, unsupported, { mode: 0o600 })
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const server = fakeInbox()
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    assert.match(String(client.status().stateError), /unsupported|corrupt/i)
    client.start()
    assert.equal(client.status().running, false, 'unsupported state must not start the SSE engine')
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(messages.length, 0)
    assert.equal(server.state.ackCalls, 0)
    assert.equal(readFileSync(paths.v2, 'utf8'), unsupported)
  } finally {
    client.dispose()
  }
})

test('a corrupt v2 JSON fails closed without rewriting the file', async () => {
  const { stateFile, paths } = tempState()
  const corrupt = '{"version":2,'
  writeFileSync(paths.v2, corrupt, { mode: 0o600 })
  const messages = []
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(fakeInbox(), stateFile))
  try {
    assert.match(String(client.status().stateError), /corrupt|unsupported/i)
    assert.equal(readFileSync(paths.v2, 'utf8'), corrupt)
  } finally {
    client.dispose()
  }
})

test('missing state is first use: the client injects and writes a v2 snapshot', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const server = fakeInbox()
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-first', { frozenRevision: 1 })],
    }))
    await waitFor(() => messages.length === 1 && client.status().pendingAck === null, 'first use works')
    const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.equal(saved.version, 2)
    assert.equal(saved.lastSeenIdentity, IDENTITY)
  } finally {
    client.dispose()
  }
})

test('persistence disabled disables injection entirely', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const server = fakeInbox()
  const client = createInboxClient(ctxFor(agent), config(server, stateFile, { persistState: false }))
  try {
    assert.match(String(client.status().blockedReason), /persistence/i)
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-disabled', { frozenRevision: 1 })],
    }))
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(messages.length, 0)
    assert.equal(server.state.ackCalls, 0)
    assert.throws(() => statSync(inboxStatePaths({ stateFile }).v2), /ENOENT/)
  } finally {
    client.dispose()
  }
})

test('the v2 snapshot and lock are created with 0600 permissions', async () => {
  const { stateFile, paths } = tempState()
  const client = createInboxClient(
    ctxFor(agentRecorder('session-1', [])),
    config(fakeInbox(), stateFile),
  )
  try {
    assert.equal(statSync(paths.v2).mode & 0o777, 0o600)
    assert.equal(statSync(paths.lock).mode & 0o777, 0o600)
  } finally {
    client.dispose()
  }
  assert.throws(() => statSync(paths.lock), /ENOENT/, 'lock is removed on safe disposal')
})

test('a stale lock is never stolen automatically; manual cleanup is required', async () => {
  const { stateFile, paths } = tempState()
  writeFileSync(paths.lock, JSON.stringify({ pid: 999999, host: 'old', startedAt: 'x' }), { mode: 0o600 })
  const messages = []
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(fakeInbox(), stateFile))
  try {
    assert.match(String(client.status().lockError), /lock/i)
    client.start()
    await new Promise((resolve) => setTimeout(resolve, 80))
    assert.equal(messages.length, 0)
    assert.ok(statSync(paths.lock), 'stale lock is left for manual verification')
    assert.throws(() => statSync(paths.v2), /ENOENT/, 'no state is written while the lock is ambiguous')
    unlinkSync(paths.lock)
    const next = createInboxClient(ctxFor(agentRecorder('session-1', [])), config(fakeInbox(), stateFile))
    try {
      assert.equal(next.status().lockError, null)
    } finally {
      next.dispose()
    }
  } finally {
    client.dispose()
  }
})

test('a live GET panel with unpushed items is not injected as though it were a push', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const server = fakeInbox({
    ack: async (body) => ackComplete(body),
    livePanel: panel({
      revision: 2,
      pushRevision: 2,
      items: [item('itm-live', { frozenRevision: 0, body: 'UNPUSHED_BODY' })],
    }),
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-pushed', { frozenRevision: 1, body: 'PUSHED_BODY' })],
    }))
    await waitFor(() => messages.length === 1 && client.status().pendingAck === null, 'first push ACKed')
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(messages.length, 1, 'unpushed live items must never be injected')
    assert.match(String(client.status().deferredPush?.reason ?? ''), /frozen|unpushed|mixed/i)
    assert.equal(server.state.getPanelCalls, 1)
  } finally {
    client.dispose()
  }
})

test('a live GET panel with mixed frozen revisions is not split or injected', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const server = fakeInbox({
    ack: async (body) => ackComplete(body),
    livePanel: panel({
      revision: 2,
      pushRevision: 2,
      items: [
        item('itm-old-frozen', { frozenRevision: 1 }),
        item('itm-new-frozen', { frozenRevision: 2 }),
      ],
    }),
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-first', { frozenRevision: 1 })],
    }))
    await waitFor(() => messages.length === 1 && client.status().pendingAck === null, 'first push ACKed')
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(messages.length, 1, 'mixed batches must not be split')
    assert.match(String(client.status().deferredPush?.reason ?? ''), /frozen|mixed|batch/i)
  } finally {
    client.dispose()
  }
})

test('a panel with duplicate or empty item ids causes zero followups and zero ACKs', async () => {
  for (const [name, items] of [
    ['duplicate', [item('itm-dup', { frozenRevision: 1 }), item('itm-dup', { frozenRevision: 1 })]],
    ['empty', [{ ...item('itm-empty', { frozenRevision: 1 }), id: '' }]],
  ]) {
    const { stateFile } = tempState()
    const messages = []
    const server = fakeInbox()
    const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(server, stateFile))
    try {
      client.start()
      server.push(panel({ revision: 1, pushRevision: 1, items }))
      await new Promise((resolve) => setTimeout(resolve, 100))
      assert.equal(messages.length, 0, `${name} ids must not inject`)
      assert.equal(server.state.ackCalls, 0, `${name} ids must not ACK`)
      assert.match(String(client.status().lastError), /id|duplicate|missing|empty/i)
    } finally {
      client.dispose()
    }
  }
})

test('disposal during an in-flight ACK releases the lock without writing after disposal', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const payload = panel({
    revision: 30, pushRevision: 30,
    items: [item('itm-inflight', { frozenRevision: 30 })],
  })
  let resolveAck = null
  const server = fakeInbox({
    ack: async (body) => new Promise((resolve) => {
      resolveAck = () => resolve(ackComplete(body))
    }),
  })
  const first = createInboxClient(ctxFor(agent), config(server, stateFile))
  first.start()
  server.push(payload)
  await waitFor(() => messages.length === 1 && resolveAck, 'in-flight ACK')
  const pendingBefore = JSON.parse(readFileSync(paths.v2, 'utf8')).pendingAck
  assert.ok(pendingBefore)
  first.dispose()
  const overlapping = createInboxClient(
    ctxFor(agentRecorder('session-1', [])),
    config(fakeInbox(), stateFile),
  )
  try {
    assert.equal(overlapping.status().lockError, null, 'dispose released the lock')
    assert.deepEqual(overlapping.status().pendingAck?.itemIds, pendingBefore.itemIds)
    resolveAck()
    await new Promise((resolve) => setTimeout(resolve, 60))
    const after = JSON.parse(readFileSync(paths.v2, 'utf8')).pendingAck
    assert.deepEqual(after?.itemIds, pendingBefore.itemIds, 'the disposed writer must not clear the record')
  } finally {
    overlapping.dispose()
  }
})

test('an empty panel still records the durable identity for a later change check', async () => {
  const { stateFile } = tempState()
  const messages = []
  const server = fakeInbox()
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(server, stateFile))
  try {
    client.start()
    server.push(panel({ revision: 1, pushRevision: 1, items: [] }))
    await waitFor(() => client.status().lastSeenIdentity === IDENTITY, 'identity remembered')
    server.push(panel({
      revision: 2, pushRevision: 2,
      items: [item('itm-after-empty', { frozenRevision: 2 })],
    }))
    await waitFor(() => messages.length === 1, 'later push injects')
  } finally {
    client.dispose()
  }
})

test('the v2 snapshot and lock paths are deterministic siblings of the configured path', () => {
  assert.deepEqual(inboxStatePaths({ stateFile: '/tmp/example/bma3-inbox.json' }), {
    configured: '/tmp/example/bma3-inbox.json',
    v2: '/tmp/example/bma3-inbox.v2.json',
    lock: '/tmp/example/bma3-inbox.v2.json.lock',
  })
  assert.equal(inboxStatePaths({ stateFile: '/tmp/example/state' }).v2, '/tmp/example/state.v2')
})

