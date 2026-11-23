import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'

import { createInboxClient, inboxStatePaths } from '../lib/inbox.js'

const IDENTITY_A = 'inb_round2_alpha'
const IDENTITY_B = 'inb_round2_beta'
const encoder = new TextEncoder()

function tempState() {
  const dir = mkdtempSync(join(tmpdir(), 'bma3-fix2-'))
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
  revision = 1, pushRevision = revision, identity = IDENTITY_A, items = [],
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

function fakeInbox({
  ack = async (body) => ackComplete(body),
  livePanel = panel({ revision: 0, pushRevision: 0, items: [] }),
  heartbeat = async () => json({ mcpStartId: 'mcp-1' }),
} = {}) {
  const state = {
    ackCalls: 0,
    ackUrls: [],
    ackBodies: [],
    getPanelUrls: [],
    heartbeatBodies: [],
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
        return ack(body, state)
      }
      if (url.endsWith('/panel') && (!init.method || init.method === 'GET')) {
        state.getPanelUrls.push(url)
        return json(server.livePanel)
      }
      if (url.endsWith('/plugin/heartbeat')) {
        state.heartbeatBodies.push(JSON.parse(init.body))
        return heartbeat(init, state)
      }
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

/** A promise that never settles, ignoring AbortSignal entirely. */
function neverSettles() {
  return new Promise(() => {})
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex')
}

// ---------- I6: target fingerprint / fail-closed target config ----------

const SECRET_BASE = 'http://user:pass@127.0.0.1:18766/path?token=SECRET_QUERY#SECRET_FRAGMENT'

test('a baseUrl with userinfo/query/fragment is rejected before any request or state pollution', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const server = fakeInbox()
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(server, stateFile, {
    baseUrl: SECRET_BASE,
  }))
  try {
    client.start()
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(server.state.requests.length, 0, 'no network request for an unsupported target')
    assert.equal(messages.length, 0)
    assert.match(String(client.status().targetError), /config_target_unsupported/)
    assert.match(String(client.status().blockedReason), /config_target_unsupported/)
    for (const text of [JSON.stringify(client.status()), readFileSync(paths.v2, 'utf8')]) {
      assert.doesNotMatch(text, /user:pass|SECRET_QUERY|SECRET_FRAGMENT|127\.0\.0\.1:18766/)
    }
  } finally {
    client.dispose()
  }
})

test('pending ACK fails closed when baseUrl or inboxId changes', async () => {
  for (const mutation of ['baseUrl', 'inboxId']) {
    const { stateFile, paths } = tempState()
    const messages = []
    const server = fakeInbox({ ack: async () => json({ error: 'down' }, 503) })
    const configObject = config(server, stateFile, { ackMaxAttempts: 100, ackRetryBaseMs: 1000 })
    const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), configObject)
    try {
      client.start()
      server.push(panel({
        revision: 1, pushRevision: 1,
        items: [item('itm-target-' + mutation, { frozenRevision: 1 })],
      }))
      await waitFor(() => client.status().pendingAck, 'pending')
      const before = JSON.parse(readFileSync(paths.v2, 'utf8')).pendingAck
      const ackCalls = server.state.ackCalls
      if (mutation === 'baseUrl') configObject.baseUrl = 'http://127.0.0.1:19999'
      else configObject.inboxId = 'other-inbox'
      await new Promise((resolve) => setTimeout(resolve, 120))
      assert.equal(server.state.ackCalls, ackCalls, 'no ACK after target config change')
      const after = JSON.parse(readFileSync(paths.v2, 'utf8')).pendingAck
      assert.deepEqual(after.itemIds, before.itemIds)
      assert.equal(after.panelRevision, before.panelRevision)
      assert.equal(after.pushRevision, before.pushRevision)
      assert.equal(after.inboxIdentity, before.inboxIdentity)
      assert.match(String(after.lastError), mutation === 'baseUrl'
        ? /pending_base_url_mismatch/ : /pending_inbox_id_mismatch/)
    } finally {
      client.dispose()
    }
  }
})

test('a managed target keeps its pending ACK across a loopback port change', async () => {
  const { stateFile, paths } = tempState()
  const fingerprint = sha256('managed-test-instance')
  const incoming = panel({ items: [item('managed-frozen')] })
  const first = fakeInbox({ livePanel: incoming, ack: async () => json({}, 503) })
  const messages = []
  const ctx = ctxFor(agentRecorder('session-1', messages))
  const oldClient = createInboxClient(ctx, config(first, stateFile), undefined, fingerprint)
  try {
    oldClient.start()
    first.push(incoming)
    await waitFor(() => oldClient.status().pendingAck, 'pending managed ACK')
    assert.equal(JSON.parse(readFileSync(paths.v2, 'utf8')).pendingAck.sourceTargetFingerprint, fingerprint)
  } finally {
    oldClient.dispose()
  }

  const next = fakeInbox({
    livePanel: incoming,
    heartbeat: async () => json({ mcpStartId: 'mcp-2' }),
  })
  const newClient = createInboxClient(ctx, config(next, stateFile, { baseUrl: 'http://127.0.0.1:19999' }),
    undefined, fingerprint)
  try {
    newClient.start()
    await waitFor(() => next.state.ackCalls === 1 && newClient.status().pendingAck === null, 'recovered managed ACK')
    assert.match(next.state.ackUrls[0], /:19999\//)
    assert.equal(messages.length, 1)
  } finally {
    newClient.dispose()
  }
})

test('an equivalent baseUrl (trailing slash) still ACKs and persists a fingerprint, never the raw URL', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  let allowAck = false
  const server = fakeInbox({ ack: async (body) => allowAck ? ackComplete(body) : json({}, 503) })
  const configObject = config(server, stateFile, { baseUrl: 'http://127.0.0.1:18766/' })
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), configObject)
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-fingerprint', { frozenRevision: 1 })],
    }))
    await waitFor(() => client.status().pendingAck, 'pending')
    configObject.baseUrl = 'http://127.0.0.1:18766'
    allowAck = true
    await waitFor(() => client.status().pendingAck === null, 'equivalent target ACKs')
    const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.match(String(saved.freshVerification?.sourceTargetFingerprint ?? ''), /^[0-9a-f]{64}$/)
    assert.doesNotMatch(readFileSync(paths.v2, 'utf8'), /http:\/\/127\.0\.0\.1:18766/)
    assert.equal(saved.freshVerification.sourceTargetFingerprint,
      sha256('http://127.0.0.1:18766'))
  } finally {
    client.dispose()
  }
})

// ---------- I5: strict revisions, empty panel/push=0, invalid ACK revision ----------

test('a legitimate empty panel with pushRevision=0 is accepted without injection', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const server = fakeInbox()
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(server, stateFile))
  try {
    client.start()
    server.push(panel({ revision: 0, pushRevision: 0, items: [] }))
    await waitFor(() => client.status().lastRevision === 0, 'empty panel processed')
    assert.equal(messages.length, 0)
    assert.equal(server.state.ackCalls, 0)
    const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.equal(saved.lastPushRevision, 0)
    assert.equal(saved.version, 2)
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-after-empty', { frozenRevision: 1 })],
    }))
    await waitFor(() => messages.length === 1, 'next real push injects')
  } finally {
    client.dispose()
  }
})

test('fractional/unsafe panel revisions are rejected without poisoning the persisted v2 snapshot', async () => {
  const cases = [
    { revision: 2, pushRevision: 1.5 },
    { revision: 1.5, pushRevision: 2 },
    { revision: 2, pushRevision: Number.MAX_SAFE_INTEGER + 1 },
    { revision: -1, pushRevision: 2 },
  ]
  for (const panelCase of cases) {
    const { stateFile, paths } = tempState()
    const messages = []
    const server = fakeInbox()
    const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(server, stateFile))
    try {
      client.start()
      server.push(panel({
        revision: panelCase.revision,
        pushRevision: panelCase.pushRevision,
        items: [item('itm-bad-revision-' + String(panelCase.pushRevision), { frozenRevision: 1 })],
      }))
      await new Promise((resolve) => setTimeout(resolve, 80))
      assert.equal(messages.length, 0)
      assert.equal(server.state.ackCalls, 0)
      const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
      assert.equal(Number.isSafeInteger(saved.lastPushRevision), true)
      assert.equal(Number.isSafeInteger(saved.injectedPushRevision), true)
      assert.match(String(client.status().lastError), /revision|batch|safe/i)
    } finally {
      client.dispose()
    }
    const restarted = createInboxClient(ctxFor(agentRecorder('session-1', [])), config(fakeInbox(), stateFile))
    try {
      assert.equal(restarted.status().stateError, null, 'persisted snapshot stays loadable')
    } finally {
      restarted.dispose()
    }
  }
})

test('an ACK with an invalid ackedRevision retains the exact original pending IDs and identity', async () => {
  for (const invalid of [1.5, -1, undefined, '5']) {
    const { stateFile, paths } = tempState()
    const messages = []
    const server = fakeInbox({
      ack: async (body) => json({
        ackedRevision: invalid,
        ackedItemIds: [...body.itemIds],
        alreadyAckedItemIds: [],
        missingItemIds: [],
        inboxIdentity: body.inboxIdentity,
        complete: true,
      }),
    })
    const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(server, stateFile))
    try {
      client.start()
      server.push(panel({
        revision: 3, pushRevision: 3,
        items: [item('itm-invalid-revision-' + String(invalid), { frozenRevision: 3 })],
      }))
      await waitFor(() => server.state.ackCalls >= 1 && client.status().pendingAck, 'ACK response')
      await new Promise((resolve) => setTimeout(resolve, 80))
      const pending = client.status().pendingAck
      assert.ok(pending, 'invalid ackedRevision must not clear pending')
      assert.equal(pending.panelRevision, 3)
      assert.equal(pending.pushRevision, 3)
      assert.equal(pending.inboxIdentity, IDENTITY_A)
      assert.deepEqual(pending.itemIds, ['itm-invalid-revision-' + String(invalid)])
      assert.equal(client.status().injectedPushRevision, 3, 'pending watermark is preserved')
      assert.match(String(pending.lastError), /ack_revision_invalid/)
      const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
      assert.equal(saved.pendingAck.panelRevision, 3)
      assert.deepEqual(saved.pendingAck.itemIds, pending.itemIds)
      assert.equal(saved.version, 2)
    } finally {
      client.dispose()
    }
  }
})

// ---------- I4: hard timeout even if abort is ignored ----------

test('ACK body json() that ignores abort still times out and retries', async () => {
  const { stateFile } = tempState()
  const messages = []
  let calls = 0
  const server = fakeInbox({
    ack: async (body) => {
      calls += 1
      if (calls === 1) {
        return {
          ok: true,
          status: 200,
          json: () => neverSettles(),
        }
      }
      return ackComplete(body)
    },
  })
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(server, stateFile, {
    ackTimeoutMs: 25,
    ackRetryBaseMs: 10,
  }))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-hard-timeout-ack', { frozenRevision: 1 })],
    }))
    await waitFor(() => server.state.ackCalls >= 2 && client.status().pendingAck === null, 'hard timeout retry')
    assert.equal(messages.length, 1)
  } finally {
    client.dispose()
  }
})

test('fresh-panel body json() that ignores abort still times out and backs off', async () => {
  const { stateFile } = tempState()
  const messages = []
  let panelCalls = 0
  const server = fakeInbox({ ack: async (body) => ackComplete(body) })
  const originalFetch = server.fetchImpl
  server.fetchImpl = async (url, init = {}) => {
    if (url.endsWith('/panel')) {
      panelCalls += 1
      if (panelCalls === 1) {
        return { ok: true, status: 200, json: () => neverSettles() }
      }
      return json(panel({ revision: 1, pushRevision: 1, items: [] }))
    }
    return originalFetch(url, init)
  }
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(server, stateFile, {
    panelTimeoutMs: 20,
    verifyRetryBaseMs: 20,
    verifyRetryMaxMs: 60,
  }))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-hard-timeout-panel', { frozenRevision: 1 })],
    }))
    await waitFor(() => client.status().pendingAck === null, 'ACK resolved')
    await waitFor(() => panelCalls >= 2, 'fresh GET retried after hard timeout')
    await waitFor(() => client.status().freshVerification?.required === false, 'verification completes')
  } finally {
    client.dispose()
  }
})

// ---------- I1: acceptIdentity re-fetches the current new-DB panel ----------

test('acceptIdentity re-fetches the current new-database panel instead of dropping it', async () => {
  const { stateFile } = tempState()
  const messages = []
  const server = fakeInbox({
    ack: async (body) => ackComplete(body),
    livePanel: panel({ revision: 1, pushRevision: 1, identity: IDENTITY_A, items: [] }),
  })
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(server, stateFile))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1, identity: IDENTITY_A,
      items: [item('itm-a', { frozenRevision: 1 })],
    }))
    await waitFor(() => messages.length === 1 && client.status().pendingAck === null, 'old DB ACKed')
    await waitFor(() => client.status().freshVerification?.required === false, 'old verification done')
    const newPanel = panel({
      revision: 1, pushRevision: 1, identity: IDENTITY_B,
      items: [item('itm-b', { frozenRevision: 1 })],
    })
    server.push(newPanel)
    await waitFor(() => client.status().identityMismatch?.seen === IDENTITY_B, 'identity change')
    assert.equal(messages.length, 1)
    server.livePanel = newPanel
    client.acceptIdentity(true)
    await waitFor(() => messages.length === 2, 'accept re-fetches current new panel')
    assert.match(messages[1].message.content[0].text, /INBOX_PANEL/)
    assert.equal(client.status().identityMismatch, null)
  } finally {
    client.dispose()
  }
})

// ---------- I2: unbind -> start resumes ACK ----------

test('start after unbind resumes ACK without opening another SSE connection', async () => {
  const { stateFile } = tempState()
  const messages = []
  let mode = 'fail'
  const server = fakeInbox({
    ack: async (body) => (mode === 'fail' ? json({ error: 'down' }, 503) : ackComplete(body)),
  })
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(server, stateFile, {
    ackMaxAttempts: 1000,
    ackRetryBaseMs: 15,
    ackRetryMaxMs: 40,
  }))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-unbind-start', { frozenRevision: 1 })],
    }))
    await waitFor(() => server.state.ackCalls >= 2 && client.status().pendingAck, 'ACK running')
    client.unbind()
    const afterUnbind = server.state.ackCalls
    const events = server.state.requests.filter((entry) => entry.url.endsWith('/events')).length
    await new Promise((resolve) => setTimeout(resolve, 80))
    assert.equal(server.state.ackCalls, afterUnbind, 'unbind suspends ACK')
    mode = 'ok'
    client.start()
    await waitFor(() => client.status().pendingAck === null, 'start resumes ACK')
    assert.equal(server.state.requests.filter((entry) => entry.url.endsWith('/events')).length, events,
      'start must not open a second SSE stream')
  } finally {
    client.dispose()
  }
})

// ---------- I3: invalid/unrelated control must not poison in-flight followup ----------

test('an invalid control during followup does not turn a successful injection into uncertain', async () => {
  for (const control of [
    { id: 'bad-unknown', action: 'bogus' },
    { id: 'bad-restore', action: 'restore', sessionId: '' },
  ]) {
    const { stateFile } = tempState()
    const messages = []
    const heartbeatGate = deferred()
    const followGate = deferred()
    const server = fakeInbox({
      ack: async (body) => ackComplete(body),
      heartbeat: async () => heartbeatGate.promise,
    })
    const agent = agentRecorder('session-1', messages, () => followGate.promise)
    const client = createInboxClient(ctxFor(agent), config(server, stateFile))
    try {
      client.start()
      await waitFor(() => server.state.heartbeatBodies.length >= 1, 'heartbeat in flight')
      server.push(panel({
        revision: 1, pushRevision: 1,
        items: [item('itm-control-' + control.id, { frozenRevision: 1 })],
      }))
      await waitFor(() => messages.length === 1 && client.status().injectionIntent, 'followup in flight')
      heartbeatGate.resolve(json({ control }))
      await new Promise((resolve) => setTimeout(resolve, 30))
      followGate.resolve()
      await waitFor(() => client.status().pendingAck || client.status().injectionIntent, 'followup resolved')
      assert.ok(client.status().pendingAck, `${control.action} must not abandon a successful followup`)
      assert.equal(client.status().injectionIntent, null)
      assert.notEqual(client.status().appliedControlId, control.id)
    } finally {
      client.dispose()
    }
  }
})

// ---------- I7: discard during followup cannot be undone by stale continuation ----------

test('discard during followup is not undone by the late followup continuation', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const followGate = deferred()
  const server = fakeInbox({ ack: async (body) => ackComplete(body) })
  const agent = agentRecorder('session-1', messages, () => followGate.promise)
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-discard-race', { frozenRevision: 1 })],
    }))
    await waitFor(() => messages.length === 1 && client.status().injectionIntent, 'followup in flight')
    const warning = client.discard(true)
    assert.match(warning, /不会自动重放|丢失/u)
    followGate.resolve()
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(client.status().pendingAck, null, 'late followup must not recreate pendingAck')
    assert.equal(client.status().injectionIntent, null)
    assert.equal(server.state.ackCalls, 0)
    assert.ok(client.status().injectedPushRevision >= 1)
    const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.equal(saved.pendingAck, null)
    assert.equal(saved.injectionIntent, null)
  } finally {
    client.dispose()
  }
})

test('a legacy pending record without a target fingerprint fails closed for ACK', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const firstServer = fakeInbox({ ack: async () => json({ error: 'down' }, 503) })
  const first = createInboxClient(ctxFor(agentRecorder('session-1', messages)), config(firstServer, stateFile, {
    ackMaxAttempts: 100,
    ackRetryBaseMs: 1000,
  }))
  first.start()
  firstServer.push(panel({
    revision: 1, pushRevision: 1,
    items: [item('itm-legacy-target', { frozenRevision: 1 })],
  }))
  await waitFor(() => first.status().pendingAck, 'legacy pending')
  first.dispose()
  const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
  const ids = saved.pendingAck.itemIds
  delete saved.pendingAck.sourceTargetFingerprint
  delete saved.pendingAck.sourceTargetFingerprintVersion
  writeFileSync(paths.v2, JSON.stringify(saved), { mode: 0o600 })
  const secondServer = fakeInbox({ ack: async (body) => ackComplete(body) })
  const second = createInboxClient(ctxFor(agentRecorder('session-1', [])), config(secondServer, stateFile))
  try {
    second.start()
    await waitFor(() => second.status().pendingAck?.lastError === 'pending_target_unverified', 'unverified diagnostic')
    assert.equal(secondServer.state.ackCalls, 0)
    assert.deepEqual(second.status().pendingAck.itemIds, ids)
    assert.equal(second.status().pendingAck.retryable, false)
  } finally {
    second.dispose()
  }
})

test('a fresh GET after target config changes is fail-closed, not redirected', async () => {
  const { stateFile } = tempState()
  const messages = []
  const configObject = config(fakeInbox({ ack: async () => json({ error: 'down' }, 503) }), stateFile)
  const server = fakeInbox({
    ack: async (body) => {
      configObject.baseUrl = 'http://127.0.0.1:19999'
      return ackComplete(body)
    },
  })
  // Replace the preliminary server with the ACK handler that mutates the target config.
  configObject.fetchImpl = server.fetchImpl
  const client = createInboxClient(ctxFor(agentRecorder('session-1', messages)), configObject)
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-fresh-target-change', { frozenRevision: 1 })],
    }))
    await waitFor(() => client.status().pendingAck === null, 'ACK resolved')
    await waitFor(() => client.status().freshVerification?.lastError === 'fresh_base_url_mismatch',
      'fresh target mismatch')
    assert.equal(server.state.getPanelUrls.length, 0, 'no fresh GET to the changed target')
    assert.equal(messages.length, 1)
  } finally {
    client.dispose()
  }
})
