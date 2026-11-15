import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { chmodSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'

import {
  createInboxClient, inboxStatePaths, registerInboxCommand,
} from '../lib/inbox.js'

const IDENTITY = 'inb_fix3_alpha'
const encoder = new TextEncoder()

function tempState() {
  const dir = mkdtempSync(join(tmpdir(), 'bma3-fix3-'))
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

function fakeInbox({
  ack = async (body) => ackComplete(body),
  livePanel = panel({ revision: 0, pushRevision: 0, items: [] }),
  heartbeat = async () => json({ mcpStartId: 'mcp-1' }),
  runtime = async () => { throw new Error('runtime not configured') },
} = {}) {
  const state = { ackCalls: 0, ackUrls: [], ackBodies: [], getPanelUrls: [], runtimeCalls: 0, requests: [], controllers: [] }
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
        state.ackUrls.push(url)
        const body = JSON.parse(init.body)
        state.ackBodies.push(body)
        return ack(body, state, init)
      }
      if (url.endsWith('/panel') && (!init.method || init.method === 'GET')) {
        state.getPanelUrls.push(url)
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

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function rejectOnAbort(signal) {
  return new Promise((_resolve, reject) => {
    const fail = () => {
      const error = new Error('aborted')
      error.name = 'AbortError'
      reject(error)
    }
    if (signal?.aborted) return fail()
    signal?.addEventListener('abort', fail, { once: true })
  })
}

test('N1 cancelled ACK after stop/unbind then start does not consume the retry budget', async () => {
  for (const stopMode of ['stop', 'unbind']) {
    const { stateFile } = tempState()
    const messages = []
    let calls = 0
    const server = fakeInbox({
      ack: async (body, _state, init) => {
        calls += 1
        if (calls === 1) {
          return { ok: true, status: 200, json: () => rejectOnAbort(init.signal) }
        }
        return ackComplete(body)
      },
    })
    const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(server, stateFile, {
      ackTimeoutMs: 5000,
      ackMaxAttempts: 1,
      ackRetryBaseMs: 10,
    }))
    try {
      client.start()
      server.push(panel({
        revision: 1, pushRevision: 1,
        items: [item('itm-cancel-' + stopMode, { frozenRevision: 1 })],
      }))
      await waitFor(() => server.state.ackCalls >= 1, 'ACK in flight')
      if (stopMode === 'stop') client.stop()
      else client.unbind()
      client.start()
      await waitFor(() => client.status().pendingAck === null, 'ACK after cancellation')
      assert.equal(server.state.ackCalls, 2)
      assert.equal(server.state.ackBodies[1].itemIds[0], 'itm-cancel-' + stopMode)
    } finally {
      client.dispose()
    }
  }
})

test('N2 restore persistence failure does not invalidate a successful in-flight followup', async () => {
  const { dir, stateFile } = tempState()
  const messages = []
  const followGate = deferred()
  const server = fakeInbox({ ack: async (body) => ackComplete(body) })
  const agent = agentRecorder('session-1', messages, () => followGate.promise)
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-restore-race', { frozenRevision: 1 })],
    }))
    await waitFor(() => messages.length === 1 && client.status().injectionIntent, 'followup in flight')
    chmodSync(dir, 0o500)
    await assert.rejects(() => client.restore('session-9'), /state_write_failed|state/i)
    chmodSync(dir, 0o700)
    followGate.resolve()
    await waitFor(() => client.status().pendingAck || client.status().injectionIntent, 'followup resolved')
    assert.ok(client.status().pendingAck, 'successful followup remains pending, not uncertain')
    assert.equal(client.status().injectionIntent, null)
  } finally {
    chmodSync(dir, 0o700)
    client.dispose()
  }
})

test('N3 a panel from an old SSE connection is rejected after the configured target changes', async () => {
  const { stateFile } = tempState()
  const messages = []
  const server = fakeInbox()
  const configObject = config(server, stateFile)
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), configObject)
  try {
    client.start()
    await waitFor(() => server.state.controllers.length === 1, 'SSE connected')
    configObject.baseUrl = 'http://127.0.0.1:19999'
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-stale-sse', { frozenRevision: 1 })],
    }))
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(messages.length, 0, 'stale connection panel must not inject')
    assert.equal(server.state.ackCalls, 0)
    assert.match(String(client.status().lastError), /stale_connection_target|stale/i)
  } finally {
    client.dispose()
  }
})

test('N4 a target-mismatch pending ACK requires explicit manual retry after config restoration', async () => {
  const { stateFile } = tempState()
  const messages = []
  let mode = 'fail'
  const server = fakeInbox({ ack: async (body) => (mode === 'fail' ? json({ error: 'down' }, 503) : ackComplete(body)) })
  const configObject = config(server, stateFile, { ackMaxAttempts: 100, ackRetryBaseMs: 1000 })
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), configObject)
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-manual-target', { frozenRevision: 1 })],
    }))
    await waitFor(() => client.status().pendingAck, 'pending')
    configObject.baseUrl = 'http://127.0.0.1:19999'
    await waitFor(() => client.status().pendingAck?.lastError === 'pending_base_url_mismatch', 'target mismatch')
    assert.equal(client.status().pendingAck.retryable, false)
    const callsAtMismatch = server.state.ackCalls
    configObject.baseUrl = 'http://127.0.0.1:18766'
    await new Promise((resolve) => setTimeout(resolve, 150))
    assert.equal(server.state.ackCalls, callsAtMismatch, 'target restoration must not auto-ACK')
    mode = 'ok'
    await client.ack()
    await waitFor(() => client.status().pendingAck === null, 'manual ACK')
    assert.deepEqual(server.state.ackBodies.at(-1).itemIds, ['itm-manual-target'])
    assert.equal(server.state.ackBodies.at(-1).panelRevision, undefined)
  } finally {
    client.dispose()
  }
})

test('N6 /inbox start reports an unsupported target instead of claiming success', async () => {
  const { stateFile } = tempState()
  const server = fakeInbox()
  const client = createInboxClient(ctxFor(agentRecorder('session-1', [])), config(server, stateFile, {
    baseUrl: 'http://user:pass@127.0.0.1:18766/path?token=SECRET',
  }))
  const definitions = []
  const ctx = {
    inject: (_deps, callback) => {
      callback({
        commands: { register: (definition) => { definitions.push(definition); return () => {} } },
        effect: (cb) => cb(),
      })
      return { dispose() {} }
    },
  }
  try {
    registerInboxCommand(ctx, client)
    const result = await definitions[0].handler({ rawInput: 'start', agent: { id: 'session-1' } })
    assert.equal(result.kind, 'error')
    assert.match(result.text, /config_target_unsupported|未启动/u)
    assert.doesNotMatch(result.text, /SSE 已启动/u)
    assert.equal(server.state.requests.length, 0)
  } finally {
    client.dispose()
  }
})

function seedPendingPush(stateFile, pendingPush, extra = {}) {
  const snapshot = JSON.parse(readFileSync(stateFile, 'utf8'))
  snapshot.pendingPush = pendingPush
  snapshot.pendingAck = null
  snapshot.injectionIntent = null
  snapshot.freshVerification = null
  snapshot.targetSessionId = pendingPush.targetSessionId
  Object.assign(snapshot, extra)
  writeFileSync(stateFile, JSON.stringify(snapshot), { mode: 0o600 })
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

test('a persisted verified-but-not-injected push is recovered via a matching fresh panel, without body', async () => {
  const { stateFile, paths } = tempState()
  const bootstrap = createInboxClient(
    ctxFor(agentRecorder('session-1', [])),
    config(fakeInbox(), stateFile, { sessionId: 'session-1', autoStart: false }),
  )
  bootstrap.dispose()
  seedPendingPush(paths.v2, pendingPushFor())
  const messages = []
  const server = fakeInbox({
    livePanel: panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-recover', { frozenRevision: 1, body: 'SECRET_PENDING_PUSH_BODY' })],
    }),
  })
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(server, stateFile, {
    sessionId: 'session-1',
  }))
  try {
    client.start()
    await waitFor(() => messages.length === 1, 'pendingPush recovered')
    await waitFor(() => client.status().pendingAck === null, 'recovered ACK')
    assert.equal(client.status().pendingPush, null)
    assert.doesNotMatch(readFileSync(paths.v2, 'utf8'), /SECRET_PENDING_PUSH_BODY/)
    assert.deepEqual(server.state.ackBodies.at(-1).itemIds, ['itm-recover'])
  } finally {
    client.dispose()
  }
})

test('a persisted pendingPush that no longer matches the fresh panel stays observable without injection', async () => {
  const { stateFile, paths } = tempState()
  const bootstrap = createInboxClient(
    ctxFor(agentRecorder('session-1', [])),
    config(fakeInbox(), stateFile, { sessionId: 'session-1', autoStart: false }),
  )
  bootstrap.dispose()
  seedPendingPush(paths.v2, pendingPushFor())
  const messages = []
  const server = fakeInbox({
    livePanel: panel({
      revision: 2, pushRevision: 2,
      items: [item('itm-different', { frozenRevision: 2 })],
    }),
  })
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(server, stateFile, {
    sessionId: 'session-1',
  }))
  try {
    client.start()
    await waitFor(() => client.status().lastError === 'pending_push_unverified', 'unverified diagnostic')
    assert.equal(messages.length, 0)
    assert.equal(server.state.ackCalls, 0)
    assert.deepEqual(client.status().pendingPush.itemIds, ['itm-recover'])
    const panelCalls = server.state.getPanelUrls.length
    await new Promise((resolve) => setTimeout(resolve, 100))
    assert.equal(server.state.getPanelUrls.length, panelCalls, 'mismatch does not loop GETs')
  } finally {
    client.dispose()
  }
})

test('discard can explicitly clear a verified-but-not-injected pendingPush and warns about loss', async () => {
  const { stateFile, paths } = tempState()
  const bootstrap = createInboxClient(
    ctxFor(agentRecorder('session-1', [])),
    config(fakeInbox(), stateFile, { sessionId: 'session-1', autoStart: false }),
  )
  bootstrap.dispose()
  seedPendingPush(paths.v2, pendingPushFor())
  const client = createInboxClient(ctxFor(agentRecorder('session-1', [])), config(fakeInbox(), stateFile, {
    sessionId: 'session-1', autoStart: false,
  }))
  try {
    const warning = client.discard(true)
    assert.match(warning, /未注入|丢失/u)
    assert.equal(client.status().pendingPush, null)
    const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.equal(saved.pendingPush, null)
    assert.ok(saved.injectedPushRevision >= 1, 'discarded push is not replayed')
  } finally {
    client.dispose()
  }
})

test('a persisted pendingPush with a changed target is diagnostic and never injected', async () => {
  const { stateFile, paths } = tempState()
  const bootstrap = createInboxClient(
    ctxFor(agentRecorder('session-1', [])),
    config(fakeInbox(), stateFile, { sessionId: 'session-1', autoStart: false }),
  )
  bootstrap.dispose()
  seedPendingPush(paths.v2, pendingPushFor())
  const messages = []
  const server = fakeInbox()
  const configObject = config(server, stateFile, { sessionId: 'session-1' })
  configObject.baseUrl = 'http://127.0.0.1:19999'
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), configObject)
  try {
    client.start()
    await waitFor(() => client.status().lastError === 'pending_push_target_mismatch', 'target mismatch diagnostic')
    assert.equal(messages.length, 0)
    assert.equal(server.state.getPanelUrls.length, 0)
    assert.deepEqual(client.status().pendingPush.itemIds, ['itm-recover'])
  } finally {
    client.dispose()
  }
})

test('N3 a same-URL clone is rejected when the runtime instance changes', async () => {
  const { stateFile } = tempState()
  const messages = []
  let processId = 'proc-A'
  const server = fakeInbox({
    runtime: async () => json({ processId, inboxIdentity: IDENTITY, mcpStartId: 'mcp-A' }),
  })
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(server, stateFile))
  try {
    client.start()
    await waitFor(() => server.state.runtimeCalls >= 1, 'runtime instance captured')
    processId = 'proc-B'
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-clone', { frozenRevision: 1 })],
    }))
    await waitFor(() => client.status().lastError === 'stale_connection_instance', 'clone diagnostic')
    assert.equal(messages.length, 0)
    assert.equal(server.state.ackCalls, 0)
    const runtimeCalls = server.state.runtimeCalls
    await new Promise((resolve) => setTimeout(resolve, 100))
    assert.equal(server.state.runtimeCalls, runtimeCalls, 'clone mismatch does not loop recovery')
  } finally {
    client.dispose()
  }
})
