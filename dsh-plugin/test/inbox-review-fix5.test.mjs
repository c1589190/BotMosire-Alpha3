import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'

import {
  createInboxClient, inboxStatePaths, registerInboxCommand, renderInboxStatus,
} from '../lib/inbox.js'

const IDENTITY = 'inb_fix5_alpha'
const encoder = new TextEncoder()

function tempState() {
  const dir = mkdtempSync(join(tmpdir(), 'bma3-fix5-'))
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

function ackComplete(body, { ackedRevision = Number(body.revision) + 1 } = {}) {
  return json({
    ackedRevision,
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
  revision = 1, pushRevision = revision, identity = IDENTITY, inboxId = 'bma3', items = [],
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

function frame(value) {
  return `id: ${value.revision}\nevent: panel\ndata: ${JSON.stringify(value)}\n\n`
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function fakeInbox({
  ack = async (body) => ackComplete(body),
  livePanel = panel({ revision: 0, pushRevision: 0, items: [] }),
  heartbeat = async () => json({ mcpStartId: 'mcp-1' }),
  runtime = async () => { throw new Error('runtime not configured') },
  panelGate = null,
} = {}) {
  const state = {
    ackCalls: 0,
    ackBodies: [],
    getPanelUrls: [],
    runtimeCalls: 0,
    requests: [],
    controllers: [],
  }
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
        return ack(body, state, init)
      }
      if (url.endsWith('/panel') && (!init.method || init.method === 'GET')) {
        state.getPanelUrls.push(url)
        if (panelGate) await panelGate.promise
        return json(server.livePanel)
      }
      if (url.endsWith('/runtime')) {
        state.runtimeCalls += 1
        return runtime(state)
      }
      if (url.endsWith('/plugin/heartbeat')) return heartbeat(init, state)
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

function agentRecorder(id, messages, behavior) {
  return {
    id,
    status: 'idle',
    async followup(message) {
      messages.push({ id, message })
      if (behavior) return behavior(message)
    },
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
    ackRetryMaxMs: 80,
    verifyRetryBaseMs: 10,
    verifyRetryMaxMs: 40,
    ...extra,
  }
}

async function waitFor(predicate, message = 'condition', timeout = 3000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await predicate()) return
    await new Promise((resolve) => setTimeout(resolve, 5))
  }
  throw new Error(`timed out waiting for ${message}`)
}

function seedPendingPush(path, pendingPush) {
  const snapshot = JSON.parse(readFileSync(path, 'utf8'))
  snapshot.pendingPush = pendingPush
  snapshot.pendingAck = null
  snapshot.injectionIntent = null
  snapshot.freshVerification = null
  snapshot.targetSessionId = pendingPush.targetSessionId
  writeFileSync(path, JSON.stringify(snapshot), { mode: 0o600 })
}

function pendingPushFor({ id = 'itm-recover', revision = 1, pushRevision = 1 } = {}) {
  return {
    inboxId: 'bma3',
    inboxIdentity: IDENTITY,
    sourceTargetFingerprint: createHash('sha256')
      .update('http://127.0.0.1:18766').digest('hex'),
    sourceTargetFingerprintVersion: 1,
    targetSessionId: 'session-1',
    panelRevision: revision,
    pushRevision,
    itemIds: [id],
    createdAt: new Date().toISOString(),
  }
}

function bootstrapWithPendingPush(stateFile, paths) {
  const bootstrap = createInboxClient(
    ctxFor(agentRecorder('session-1', [])),
    config(fakeInbox(), stateFile, { sessionId: 'session-1', autoStart: false }),
  )
  bootstrap.dispose()
  seedPendingPush(paths.v2, pendingPushFor())
}

/** The real `/inbox` command handler over the real client. */
function commandHandler(client) {
  const definitions = []
  const ctx = {
    effect: (callback) => callback(),
    inject: (deps, callback) => {
      const child = {
        commands: {
          register: (definition) => {
            definitions.push(definition)
            return () => {}
          },
        },
        effect: (callback) => callback(),
      }
      callback(child)
      return { dispose() {} }
    },
  }
  registerInboxCommand(ctx, client)
  assert.equal(definitions.length, 1, 'one /inbox command definition')
  return definitions[0].handler
}

const recoverablePanel = () => panel({
  revision: 1,
  pushRevision: 1,
  items: [item('itm-recover', { frozenRevision: 1 })],
})

/**
 * Regression under repair: a legitimate server restart changes `/runtime.processId` from P1 to
 * P2 while the inbox database identity and the frozen panel stay identical. `pendingPush` was
 * verified against P1 and is retained. Before the fix, the explicit `/inbox ack` retry cleared
 * the mismatch code but never cleared the in-memory `pendingPushProcessId`, so every later probe
 * compared P1 with P2 and the push was permanently locked (while README/status promised retry).
 * The fix may only re-establish the instance *baseline* on explicit manual retry; every other
 * field (identity/URL/inboxId/session/revisions/IDs/frozen) must still match exactly.
 */
test('R5 a legitimate server restart (P1→P2) is recoverable by an explicit /inbox ack', async () => {
  const { stateFile, paths } = tempState()
  bootstrapWithPendingPush(stateFile, paths)
  const messages = []
  let processId = 'proc-A'
  const panelGate = deferred()
  const server = fakeInbox({
    livePanel: recoverablePanel(),
    runtime: async () => json({ processId, inboxIdentity: IDENTITY }),
    panelGate,
  })
  const client = createInboxClient(
    ctxFor(agentRecorder('session-1', messages)),
    config(server, stateFile, { sessionId: 'session-1' }),
  )
  try {
    client.start()
    await waitFor(() => server.state.runtimeCalls >= 1, 'SSE instance probe')
    await waitFor(() => server.state.getPanelUrls.length >= 1, 'recovery GET started')
    // Legitimate restart: same database identity, same frozen panel, new process id forever.
    processId = 'proc-B'
    panelGate.resolve()
    await waitFor(() => client.status().lastError === 'stale_connection_instance',
      'instance mismatch diagnostic')
    assert.equal(messages.length, 0)
    assert.ok(client.status().pendingPush, 'mismatch retains the durable push')

    const before = renderInboxStatus(client.status())
    assert.match(before, /以当前运行实例作为新基线/u,
      'status must explain what the explicit retry does')
    assert.match(before, /无法识别同身份克隆/u,
      'status must not claim clone/rollback detection')

    const handler = commandHandler(client)
    const result = await handler({ rawInput: 'ack', agent: { id: 'session-1' } })
    assert.equal(result.kind, 'success', result.text)
    assert.match(result.text, /以当前运行实例作为新基线/u)
    assert.doesNotMatch(result.text, /已恢复|已注入|恢复成功/u,
      'the manual retry must not claim recovery before the fresh GET/probe')

    await waitFor(() => messages.length === 1, 'manual recovery injection')
    await waitFor(() => client.status().pendingPush === null, 'recovered push cleared')
    await waitFor(() => server.state.ackBodies.length >= 1, 'recovered ACK')
    assert.deepEqual(server.state.ackBodies.at(-1).itemIds, ['itm-recover'])
    assert.equal(server.state.ackBodies.at(-1).inboxIdentity, IDENTITY)
    await new Promise((resolve) => setTimeout(resolve, 80))
    assert.equal(messages.length, 1, 'no duplicate injection')
    const disk = readFileSync(paths.v2, 'utf8')
    assert.doesNotMatch(disk, /proc-A|proc-B/, 'instance evidence stays runtime-only')
  } finally {
    client.dispose()
  }
})

test('R5 the instance re-baseline never loosens the exact frozen-push match', async () => {
  const { stateFile, paths } = tempState()
  bootstrapWithPendingPush(stateFile, paths)
  const messages = []
  let processId = 'proc-A'
  const panelGate = deferred()
  const server = fakeInbox({
    livePanel: recoverablePanel(),
    runtime: async () => json({ processId, inboxIdentity: IDENTITY }),
    panelGate,
  })
  const client = createInboxClient(
    ctxFor(agentRecorder('session-1', messages)),
    config(server, stateFile, { sessionId: 'session-1' }),
  )
  try {
    client.start()
    await waitFor(() => server.state.runtimeCalls >= 1, 'SSE instance probe')
    await waitFor(() => server.state.getPanelUrls.length >= 1, 'recovery GET started')
    processId = 'proc-B'
    panelGate.resolve()
    await waitFor(() => client.status().lastError === 'stale_connection_instance',
      'instance mismatch diagnostic')

    // The new instance serves the same identity/URL but a different frozen push.
    server.livePanel = panel({
      revision: 2,
      pushRevision: 2,
      items: [item('itm-other', { frozenRevision: 2 })],
    })
    await commandHandler(client)({ rawInput: 'ack', agent: { id: 'session-1' } })
    await waitFor(() => client.status().lastError === 'pending_push_unverified',
      'exact-match rejection')
    await new Promise((resolve) => setTimeout(resolve, 80))
    assert.equal(messages.length, 0, 'a different push is never injected')
    assert.equal(server.state.ackBodies.length, 0, 'nothing is ACKed')
    assert.ok(client.status().pendingPush, 'the retained push is preserved')
    assert.match(renderInboxStatus(client.status()), /精确匹配|不会自动重放/u)
  } finally {
    client.dispose()
  }
})

test('R5 a /runtime 200 without processId cannot be bypassed by /inbox ack', async () => {
  const { stateFile, paths } = tempState()
  bootstrapWithPendingPush(stateFile, paths)
  const messages = []
  let processId = 'proc-A'
  let runtimeMode = 'with-process-id'
  const panelGate = deferred()
  const server = fakeInbox({
    livePanel: recoverablePanel(),
    runtime: async () => (runtimeMode === 'missing-process-id'
      ? json({ inboxIdentity: IDENTITY })
      : json({ processId, inboxIdentity: IDENTITY })),
    panelGate,
  })
  const client = createInboxClient(
    ctxFor(agentRecorder('session-1', messages)),
    config(server, stateFile, { sessionId: 'session-1' }),
  )
  try {
    client.start()
    await waitFor(() => server.state.runtimeCalls >= 1, 'SSE instance probe')
    await waitFor(() => server.state.getPanelUrls.length >= 1, 'recovery GET started')
    processId = 'proc-B'
    panelGate.resolve()
    await waitFor(() => client.status().lastError === 'stale_connection_instance',
      'instance mismatch diagnostic')

    runtimeMode = 'missing-process-id'
    const result = await commandHandler(client)(
      { rawInput: 'ack', agent: { id: 'session-1' } },
    )
    assert.equal(result.kind, 'success', result.text)
    assert.match(result.text, /服务端不提供 processId/u,
      'the command must warn that a missing processId keeps recovery blocked')
    assert.doesNotMatch(result.text, /已恢复|已注入|恢复成功/u)

    await waitFor(() => client.status().lastError === 'connection_instance_unverified',
      'unverified instance stays fail-closed')
    await new Promise((resolve) => setTimeout(resolve, 80))
    assert.equal(messages.length, 0, 'no injection without instance proof')
    assert.equal(server.state.ackBodies.length, 0)
    assert.ok(client.status().pendingPush, 'the push is retained')
    const statusText = renderInboxStatus(client.status())
    assert.match(statusText, /不提供.*processId|未提供.*processId/u)
    assert.match(statusText, /不会注入/u)
  } finally {
    client.dispose()
  }
})

test('R5 /inbox ack while stopped does not claim a recovery request', async () => {
  const { stateFile, paths } = tempState()
  bootstrapWithPendingPush(stateFile, paths)
  const messages = []
  let processId = 'proc-A'
  const panelGate = deferred()
  const server = fakeInbox({
    livePanel: recoverablePanel(),
    runtime: async () => json({ processId, inboxIdentity: IDENTITY }),
    panelGate,
  })
  const client = createInboxClient(
    ctxFor(agentRecorder('session-1', messages)),
    config(server, stateFile, { sessionId: 'session-1' }),
  )
  try {
    client.start()
    await waitFor(() => server.state.runtimeCalls >= 1, 'SSE instance probe')
    await waitFor(() => server.state.getPanelUrls.length >= 1, 'recovery GET started')
    processId = 'proc-B'
    panelGate.resolve()
    await waitFor(() => client.status().lastError === 'stale_connection_instance',
      'instance mismatch diagnostic')
    client.stop()

    const result = await commandHandler(client)(
      { rawInput: 'ack', agent: { id: 'session-1' } },
    )
    assert.equal(result.kind, 'error', result.text)
    assert.match(result.text, /已停止|inbox start/u)
    assert.doesNotMatch(result.text, /已显式请求|已发起/u,
      'a stopped client must not report that a request was issued')
    assert.ok(client.status().pendingPush, 'the record is retained')
  } finally {
    client.dispose()
  }
})

test('R5 /inbox ack during an in-flight fresh verify does not claim a new request', async () => {
  const { stateFile, paths } = tempState()
  bootstrapWithPendingPush(stateFile, paths)
  const messages = []
  let processId = 'proc-A'
  const panelGate = deferred()
  const server = fakeInbox({
    livePanel: recoverablePanel(),
    runtime: async () => json({ processId, inboxIdentity: IDENTITY }),
    panelGate,
  })
  const client = createInboxClient(
    ctxFor(agentRecorder('session-1', messages)),
    config(server, stateFile, { sessionId: 'session-1' }),
  )
  try {
    client.start()
    await waitFor(() => server.state.runtimeCalls >= 1, 'SSE instance probe')
    await waitFor(() => server.state.getPanelUrls.length >= 1, 'recovery GET started')

    const result = await commandHandler(client)(
      { rawInput: 'ack', agent: { id: 'session-1' } },
    )
    assert.equal(result.kind, 'error', result.text)
    assert.match(result.text, /校验在途|在途/u)
    assert.doesNotMatch(result.text, /已显式请求|已发起/u,
      'an in-flight verify must not be reported as a new recovery request')

    processId = 'proc-B'
    panelGate.resolve()
    await waitFor(() => client.status().lastError === 'stale_connection_instance',
      'in-flight verify finishes fail-closed')
    await new Promise((resolve) => setTimeout(resolve, 80))
    assert.equal(messages.length, 0)
    assert.ok(client.status().pendingPush)
  } finally {
    client.dispose()
  }
})
