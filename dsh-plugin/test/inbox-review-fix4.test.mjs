import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { chmodSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'

import {
  createInboxClient, inboxStatePaths, registerInboxCommand, renderInboxStatus,
} from '../lib/inbox.js'

const IDENTITY = 'inb_fix4_alpha'
const encoder = new TextEncoder()

function tempState() {
  const dir = mkdtempSync(join(tmpdir(), 'bma3-fix4-'))
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
  revision = 1, pushRevision = revision, ackedRevision = 0, identity = IDENTITY,
  inboxId = 'bma3', items = [],
} = {}) {
  return {
    inboxId,
    revision,
    pushRevision,
    ackedRevision,
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
    ackUrls: [],
    ackBodies: [],
    getPanelUrls: [],
    runtimeCalls: 0,
    runtimeStatuses: [],
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
        state.ackUrls.push(url)
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
        const result = await runtime(state)
        state.runtimeStatuses.push(result?.status ?? 200)
        return result
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

function bootstrapWithPendingPush(stateFile, paths) {
  const bootstrap = createInboxClient(
    ctxFor(agentRecorder('session-1', [])),
    config(fakeInbox(), stateFile, { sessionId: 'session-1', autoStart: false }),
  )
  bootstrap.dispose()
  seedPendingPush(paths.v2, pendingPushFor())
}

test('a server-acked stale pendingPush is cleared and the current panel is retried', async () => {
  const { stateFile, paths } = tempState()
  bootstrapWithPendingPush(stateFile, paths)
  const messages = []
  const server = fakeInbox({
    livePanel: panel({
      revision: 3,
      pushRevision: 3,
      ackedRevision: 3,
      items: [],
    }),
  })
  const client = createInboxClient(
    ctxFor(agentRecorder('session-1', messages)),
    config(server, stateFile),
  )
  try {
    client.start()
    await waitFor(() => client.status().pendingPush === null,
      'stale pendingPush cleared after server ACK')
    await waitFor(() => client.status().freshVerification?.required === false,
      'fresh panel verification completed')
    assert.equal(client.status().deferredPush, null)
    assert.equal(client.status().lastError, null)
    assert.equal(messages.length, 0)
    const disk = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.equal(disk.pendingPush, null)
    assert.equal(disk.deferredPush, null)
  } finally {
    client.dispose()
  }
})

test('explicit recover clears a stale pendingPush and retries the current panel', async () => {
  const { stateFile, paths } = tempState()
  bootstrapWithPendingPush(stateFile, paths)
  const messages = []
  const server = fakeInbox({
    livePanel: panel({ revision: 3, pushRevision: 3, ackedRevision: 0, items: [] }),
  })
  const client = createInboxClient(
    ctxFor(agentRecorder('session-1', messages)),
    config(server, stateFile),
  )
  try {
    client.start()
    await waitFor(() => client.status().lastError === 'pending_push_unverified',
      'stale pendingPush observed')
    assert.ok(client.status().pendingPush)
    const recovered = client.recover(true)
    assert.equal(recovered.pendingPush, null)
    assert.equal(recovered.deferredPush, null)
    await waitFor(() => client.status().freshVerification?.required === false,
      'explicit recovery fresh verification')
    assert.equal(client.status().pendingPush, null)
    assert.equal(messages.length, 0)
    const disk = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.equal(disk.pendingPush, null)
    assert.equal(disk.deferredPush, null)
  } finally {
    client.dispose()
  }
})

test('a heartbeat recover control clears the stale pendingPush', async () => {
  const { stateFile, paths } = tempState()
  bootstrapWithPendingPush(stateFile, paths)
  const messages = []
  const control = {
    id: 'ctl-recover-test',
    action: 'recover',
    sessionId: '',
    remind: '',
    createdAt: new Date().toISOString(),
  }
  const server = fakeInbox({
    livePanel: panel({ revision: 3, pushRevision: 3, ackedRevision: 0, items: [] }),
    heartbeat: async () => json({ mcpStartId: 'mcp-1', control }),
  })
  const client = createInboxClient(
    ctxFor(agentRecorder('session-1', messages)),
    config(server, stateFile),
  )
  try {
    client.start()
    await waitFor(() => client.status().pendingPush,
      'stale pendingPush observed')
    await client.heartbeat()
    await waitFor(() => client.status().pendingPush === null,
      'heartbeat recover control cleared pendingPush')
    assert.equal(client.status().appliedControlId, 'ctl-recover-test')
    await waitFor(() => client.status().freshVerification?.required === false,
      'recover control fresh verification')
    assert.equal(messages.length, 0)
  } finally {
    client.dispose()
  }
})

// ---------------------------------------------------------------------------
// I1: GET-derived recovery/injection must carry the proven connection instance.
// ---------------------------------------------------------------------------

test('I1 a post-ACK fresh GET panel fails closed when the proven instance changes', async () => {
  const { stateFile } = tempState()
  const messages = []
  let processId = 'proc-A'
  const ackGate = deferred()
  const server = fakeInbox({
    ack: async (body) => {
      await ackGate.promise
      return ackComplete(body)
    },
    runtime: async () => json({ processId, inboxIdentity: IDENTITY }),
  })
  server.livePanel = panel({
    revision: 2, pushRevision: 2,
    items: [item('itm-fresh', { frozenRevision: 2 })],
  })
  const client = createInboxClient(
    ctxFor(agentRecorder('session-1', messages)),
    config(server, stateFile),
  )
  try {
    client.start()
    await waitFor(() => server.state.runtimeCalls >= 1, 'SSE instance probe')
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-first', { frozenRevision: 1 })],
    }))
    await waitFor(() => client.status().pendingAck, 'first pending ACK')
    await waitFor(() => server.state.ackCalls === 1, 'ACK in flight')
    processId = 'proc-B'
    ackGate.resolve()
    await waitFor(() => server.state.getPanelUrls.length >= 1, 'post-ACK fresh GET')
    await waitFor(() => /stale_connection_instance|pending_push_/.test(String(client.status().lastError)),
      'instance-change diagnostic')
    assert.equal(messages.length, 1, 'the GET-derived panel must not be injected')
    assert.equal(server.state.ackCalls, 1)
    assert.ok(client.status().pendingPush, 'the verified push stays durable')
    assert.deepEqual(client.status().pendingPush.itemIds, ['itm-fresh'])
    const runtimeCalls = server.state.runtimeCalls
    await new Promise((resolve) => setTimeout(resolve, 100))
    assert.equal(server.state.runtimeCalls, runtimeCalls, 'instance mismatch must not loop probes')
  } finally {
    client.dispose()
  }
})

test('I1 a GET-recovered pendingPush fails closed when the proven instance changes', async () => {
  const { stateFile, paths } = tempState()
  bootstrapWithPendingPush(stateFile, paths)
  const panelGate = deferred()
  const messages = []
  let processId = 'proc-A'
  const server = fakeInbox({
    livePanel: panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-recover', { frozenRevision: 1 })],
    }),
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
      'GET-recovery instance diagnostic')
    assert.equal(messages.length, 0, 'the GET-recovered panel must not be injected')
    assert.equal(server.state.ackCalls, 0)
    assert.deepEqual(client.status().pendingPush.itemIds, ['itm-recover'])
  } finally {
    client.dispose()
  }
})

// ---------------------------------------------------------------------------
// I2: transient /runtime failures retain the push and recover; only a real
// instance mismatch is permanently fail-closed until explicit handling.
// ---------------------------------------------------------------------------

test('I2 a transient runtime probe failure retains pendingPush and retries with backoff', async () => {
  const { stateFile, paths } = tempState()
  bootstrapWithPendingPush(stateFile, paths)
  const messages = []
  let runtimeHealthy = false
  let runtimeCalls = 0
  const panelGate = deferred()
  const server = fakeInbox({
    livePanel: panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-recover', { frozenRevision: 1 })],
    }),
    runtime: async () => {
      runtimeCalls += 1
      if (runtimeCalls === 1) return json({ processId: 'proc-A', inboxIdentity: IDENTITY })
      return runtimeHealthy
        ? json({ processId: 'proc-A', inboxIdentity: IDENTITY })
        : json({ error: 'unavailable' }, 503)
    },
    panelGate,
  })
  const client = createInboxClient(
    ctxFor(agentRecorder('session-1', messages)),
    config(server, stateFile, { sessionId: 'session-1' }),
  )
  try {
    client.start()
    // The first /runtime probe belongs to the SSE connect; make it appear proven before
    // letting the recovery GET proceed, then fail every later probe transiently.
    await waitFor(() => server.state.runtimeCalls >= 1, 'SSE runtime probe')
    await waitFor(() => server.state.getPanelUrls.length >= 1, 'recovery GET started')
    panelGate.resolve()
    await waitFor(() => client.status().lastError === 'pending_push_instance_unverified',
      'transient instance diagnostic')
    assert.equal(messages.length, 0)
    assert.deepEqual(client.status().pendingPush.itemIds, ['itm-recover'])
    runtimeHealthy = true
    await waitFor(() => messages.length === 1, 'retry after runtime recovers')
    await waitFor(() => client.status().pendingPush === null, 'recovered push cleared')
    await waitFor(() => server.state.ackCalls === 1, 'recovered ACK')
  } finally {
    client.dispose()
  }
})

test('I2 stop/start after a transient instance probe failure resumes recovery', async () => {
  const { stateFile, paths } = tempState()
  bootstrapWithPendingPush(stateFile, paths)
  const messages = []
  let runtimeHealthy = false
  let runtimeCalls = 0
  const panelGate = deferred()
  const server = fakeInbox({
    livePanel: panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-recover', { frozenRevision: 1 })],
    }),
    runtime: async () => {
      runtimeCalls += 1
      if (runtimeCalls === 1) return json({ processId: 'proc-A', inboxIdentity: IDENTITY })
      return runtimeHealthy
        ? json({ processId: 'proc-A', inboxIdentity: IDENTITY })
        : json({ error: 'unavailable' }, 503)
    },
    panelGate,
  })
  const client = createInboxClient(
    ctxFor(agentRecorder('session-1', messages)),
    config(server, stateFile, { sessionId: 'session-1' }),
  )
  try {
    client.start()
    await waitFor(() => server.state.runtimeCalls >= 1, 'SSE runtime probe')
    await waitFor(() => server.state.getPanelUrls.length >= 1, 'recovery GET started')
    panelGate.resolve()
    await waitFor(() => client.status().lastError === 'pending_push_instance_unverified',
      'transient instance diagnostic')
    assert.equal(messages.length, 0)
    client.stop()
    runtimeHealthy = true
    client.start()
    await waitFor(() => messages.length === 1, 'recovery after stop/start')
    await waitFor(() => client.status().pendingPush === null, 'recovered push cleared')
  } finally {
    client.dispose()
  }
})

test('I2 a proven instance mismatch stays fail-closed until an explicit ack retry', async () => {
  const { stateFile, paths } = tempState()
  bootstrapWithPendingPush(stateFile, paths)
  const messages = []
  let processId = 'proc-A'
  const panelGate = deferred()
  const server = fakeInbox({
    livePanel: panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-recover', { frozenRevision: 1 })],
    }),
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
      'real instance mismatch')
    assert.equal(messages.length, 0)
    assert.ok(client.status().pendingPush, 'mismatch retains the durable push')
    const panelCalls = server.state.getPanelUrls.length
    await new Promise((resolve) => setTimeout(resolve, 100))
    assert.equal(server.state.getPanelUrls.length, panelCalls, 'no automatic retry on mismatch')
    processId = 'proc-A'
    await client.ack()
    await waitFor(() => messages.length === 1, 'explicit ack retry recovers')
    await waitFor(() => client.status().pendingPush === null, 'recovered push cleared')
    await waitFor(() => server.state.ackBodies.length >= 1, 'recovered ACK body')
    assert.deepEqual(server.state.ackBodies.at(-1).itemIds, ['itm-recover'])
  } finally {
    client.dispose()
  }
})

// ---------------------------------------------------------------------------
// M4: restore must not report failure while silently changing the restart binding.
// ---------------------------------------------------------------------------

test('M4 a reported restore failure never silently changes the durable restart binding', async () => {
  const { dir, stateFile, paths } = tempState()
  const messages = []
  const resumeGate = deferred()
  const agent = agentRecorder('session-9', messages)
  const ctx = {
    agents: {
      get: () => undefined,
      list: () => [],
      resume: async () => resumeGate.promise,
    },
    effect: (callback) => callback(),
  }
  const server = fakeInbox()
  const client = createInboxClient(ctx, config(server, stateFile))
  try {
    client.stop()
    const restored = client.restore('session-9')
    await waitFor(() => {
      try {
        return JSON.parse(readFileSync(paths.v2, 'utf8')).targetSessionId === 'session-9'
      } catch {
        return false
      }
    }, 'first restore snapshot')
    chmodSync(dir, 0o500)
    resumeGate.resolve({ agent, dispose() {} })
    let failure = null
    try {
      await restored
    } catch (error) {
      failure = error
    }
    chmodSync(dir, 0o700)
    const disk = JSON.parse(readFileSync(paths.v2, 'utf8'))
    const state = client.status()
    assert.equal(disk.targetSessionId, state.targetSessionId,
      'a reported failure must not leave a new durable binding behind')
    assert.equal(disk.stopped, state.stopped,
      'a reported failure must not leave a different durable start intent behind')
    if (!failure) {
      assert.equal(state.targetSessionId, 'session-9')
      assert.equal(disk.stopped, false)
    }
  } finally {
    chmodSync(dir, 0o700)
    client.dispose()
  }
})

// ---------------------------------------------------------------------------
// M1/M2: status hints for old pending records, unbound pending pushes, stopped mode.
// ---------------------------------------------------------------------------

const PENDING_BASE = {
  inboxId: 'bma3',
  inboxIdentity: 'inb_7f3a',
  targetSessionId: 'sess-7f3a2b1c-9999',
  panelRevision: 42,
  pushRevision: 40,
  itemIds: ['itm-1', 'itm-2'],
  createdAt: '2026-11-15T00:00:00.000Z',
  status: 'pending',
  attempts: 2,
  retryable: true,
  lastError: 'ack_http_503',
  lastAttemptAt: '2026-11-15T00:00:01.000Z',
}

function statusBase(extra = {}) {
  return {
    inboxId: 'bma3',
    running: true,
    stopped: false,
    connection: 'open',
    targetSessionId: 'sess-7f3a2b1c-9999',
    config: { baseUrl: 'http://127.0.0.1:46500', tokenSource: 'env-file' },
    ...extra,
  }
}

test('M1 status does not tell an old pending record to restore config and /inbox ack', () => {
  const text = renderInboxStatus(statusBase({
    pendingAck: { ...PENDING_BASE, lastError: 'pending_target_unverified', retryable: false },
  }))
  assert.doesNotMatch(text, /恢复原目标配置后可用 \/inbox ack/u)
  assert.match(text, /discard confirm|无法安全重试|重建/u)
})

test('M2 status says an unbound pendingPush has no recoverable target session', () => {
  const text = renderInboxStatus(statusBase({
    targetSessionId: null,
    pendingPush: {
      inboxId: 'bma3',
      inboxIdentity: IDENTITY,
      panelRevision: 1,
      pushRevision: 1,
      itemIds: ['itm-recover'],
      targetSessionId: null,
    },
  }))
  assert.match(text, /没有目标会话|无目标会话|人工/u)
  assert.doesNotMatch(text, /新面板会注入到第一个 idle 会话/u)
})

test('M2 status does not claim unbound panels will be injected while stopped', () => {
  const text = renderInboxStatus(statusBase({
    targetSessionId: null,
    running: false,
    stopped: true,
    connection: 'closed',
  }))
  assert.doesNotMatch(text, /新面板会注入到第一个 idle 会话/u)
  assert.match(text, /已停止|不会注入/u)
})

// ---------------------------------------------------------------------------
// M3: start/ack short-circuit messages must describe what really happened.
// ---------------------------------------------------------------------------

function commandHarness(client) {
  const definitions = []
  const ctx = {
    inject: (_deps, callback) => {
      callback({
        commands: { register: (definition) => { definitions.push(definition); return () => {} } },
        effect: (callback2) => callback2(),
      })
      return { dispose() {} }
    },
  }
  registerInboxCommand(ctx, client)
  assert.equal(definitions.length, 1)
  return (rawInput) => definitions[0].handler({ rawInput, agent: { id: 'session-1' } })
}

test('M3 /inbox ack does not claim a retry when a target mismatch stopped it', async () => {
  const pending = {
    ...PENDING_BASE,
    retryable: false,
    lastError: 'pending_base_url_mismatch',
  }
  const client = {
    status: () => statusBase({ pendingAck: pending }),
    ack: async () => {},
  }
  const run = commandHarness(client)
  const result = await run('ack')
  assert.doesNotMatch(result.text, /已请求重试/u)
  assert.match(result.text, /未发出 ACK|不会自动重发|原目标/u)
})

test('M3 /inbox ack mentions a pendingPush instead of claiming there is no record', async () => {
  const client = {
    status: () => statusBase({
      pendingAck: null,
      pendingPush: {
        inboxId: 'bma3',
        inboxIdentity: IDENTITY,
        panelRevision: 1,
        pushRevision: 1,
        itemIds: ['itm-recover'],
        targetSessionId: 'sess-7f3a2b1c-9999',
      },
    }),
    ack: async () => {},
  }
  const run = commandHarness(client)
  const result = await run('ack')
  assert.doesNotMatch(result.text, /当前没有待 ACK 记录/u)
  assert.match(result.text, /待注入|实例|恢复/u)
})

test('M3 /inbox start does not claim success when the running target is unsupported', async () => {
  const client = {
    status: () => statusBase({
      targetError: 'config_target_unsupported',
      config: {
        baseUrl: null,
        targetError: 'config_target_unsupported',
        tokenSource: 'env-file',
      },
    }),
    start: () => {},
  }
  const run = commandHarness(client)
  const result = await run('start')
  assert.equal(result.kind, 'error')
  assert.doesNotMatch(result.text, /SSE 已启动/u)
  assert.match(result.text, /target|unsupported|未启动/u)
})
