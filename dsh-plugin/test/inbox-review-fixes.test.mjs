import assert from 'node:assert/strict'
import {
  chmodSync, mkdtempSync, readFileSync, writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'

import { createInboxClient, inboxStatePaths } from '../lib/inbox.js'

const IDENTITY = 'inb_review_alpha'
const encoder = new TextEncoder()

function tempState() {
  const dir = mkdtempSync(join(tmpdir(), 'bma3-review-'))
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

function fakeInbox({
  ack = async (body) => ackComplete(body),
  livePanel = panel({ revision: 0, pushRevision: 0, items: [] }),
  heartbeat = async () => json({ mcpStartId: 'mcp-1' }),
} = {}) {
  const state = { ackCalls: 0, ackBodies: [], getPanelCalls: 0, heartbeatCalls: 0, controllers: [], requests: [] }
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
        return json(server.livePanel)
      }
      if (url.endsWith('/plugin/heartbeat')) {
        state.heartbeatCalls += 1
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

test('dispose during followup leaves only the uncertain intent: no pending ACK, ACK, or new timer', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const followupGate = deferred()
  const agent = agentRecorder('session-1', messages, () => followupGate.promise)
  const server = fakeInbox()
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-dispose-followup', { frozenRevision: 1 })],
    }))
    await waitFor(() => messages.length === 1, 'followup started')
    const during = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.ok(during.injectionIntent, 'intent is durable before the awaited followup')
    client.dispose()
    followupGate.resolve()
    await new Promise((resolve) => setTimeout(resolve, 120))
    const after = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.ok(after.injectionIntent, 'the disposed instance must not replace intent with pendingAck')
    assert.equal(after.pendingAck, null)
    assert.equal(server.state.ackCalls, 0, 'the disposed instance must not ACK')
    assert.equal(messages.length, 1)
  } finally {
    client.dispose()
  }
})

test('dispose during heartbeat prevents a delayed control/remind continuation from starting timers or writing', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const other = agentRecorder('session-9', messages)
  const heartbeatGate = deferred()
  const server = fakeInbox({
    heartbeat: () => heartbeatGate.promise,
  })
  const client = createInboxClient(ctxFor(agent, other), config(server, stateFile, {
    sessionId: 'session-1',
  }))
  try {
    client.start()
    await waitFor(() => server.state.heartbeatCalls >= 1, 'heartbeat in flight')
    const before = readFileSync(paths.v2, 'utf8')
    client.dispose()
    heartbeatGate.resolve(json({
      mcpStartId: 'mcp-new',
      control: {
        id: 'ctl-after-dispose',
        action: 'restore',
        sessionId: 'session-9',
        remind: 'SECRET_REMIND_BODY',
      },
    }))
    await new Promise((resolve) => setTimeout(resolve, 150))
    assert.equal(messages.length, 0, 'no reminder followup after dispose')
    assert.equal(readFileSync(paths.v2, 'utf8'), before, 'no state write after dispose')
    assert.equal(server.state.requests.filter((entry) => entry.url.endsWith('/events')).length, 1,
      'no reconnect after dispose')
  } finally {
    client.dispose()
  }
})

test('dispose during SSE reading prevents an already-queued panel from writing or injecting', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const server = fakeInbox()
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    await waitFor(() => server.state.controllers.length === 1, 'SSE connected')
    const before = readFileSync(paths.v2, 'utf8')
    client.dispose()
    server.push(panel({
      revision: 99, pushRevision: 99,
      items: [item('itm-after-dispose', { frozenRevision: 99 })],
    }))
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(messages.length, 0, 'no followup after dispose')
    assert.equal(server.state.ackCalls, 0)
    assert.equal(readFileSync(paths.v2, 'utf8'), before, 'queued SSE must not write after dispose')
  } finally {
    client.dispose()
  }
})

test('a replaced lock owner blocks persistence and is never deleted by the old instance', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const server = fakeInbox()
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    const before = readFileSync(paths.v2, 'utf8')
    const foreign = JSON.stringify({ pid: 1, host: 'foreign', token: 'foreign-token', startedAt: 'x' })
    writeFileSync(paths.lock, foreign, { mode: 0o600 })
    assert.throws(() => client.pause(), /lock|state/i, 'control write must fail when the lock is not ours')
    assert.equal(readFileSync(paths.v2, 'utf8'), before, 'no state write without a proven lock')
    client.dispose()
    assert.equal(readFileSync(paths.lock, 'utf8'), foreign, 'dispose must not delete a foreign lock')
  } finally {
    client.dispose()
  }
})

function abortableHang(init, label) {
  return new Promise((resolve, reject) => {
    const signal = init?.signal
    const fail = () => {
      const error = new Error(`${label} aborted`)
      error.name = 'AbortError'
      reject(error)
    }
    if (signal?.aborted) return fail()
    signal?.addEventListener('abort', fail, { once: true })
  })
}

test('an ACK request times out, aborts its signal, and retries the exact original body', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  let timedOutSignal = null
  let calls = 0
  const server = fakeInbox({
    ack: async (body, state) => {
      calls += 1
      if (calls === 1) {
        timedOutSignal = state.requests.at(-1).init.signal
        return abortableHang(state.requests.at(-1).init, 'ack')
      }
      return ackComplete(body)
    },
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile, {
    ackTimeoutMs: 25,
    ackRetryBaseMs: 10,
  }))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-timeout', { frozenRevision: 1 })],
    }))
    await waitFor(() => server.state.ackCalls >= 2 && client.status().pendingAck === null, 'ACK timeout retry')
    assert.equal(timedOutSignal?.aborted, true, 'the timed-out request signal is aborted')
    assert.deepEqual(server.state.ackBodies[0], server.state.ackBodies[1], 'retry uses the original body')
    assert.equal(messages.length, 1, 'a timeout must not repeat followup')
  } finally {
    client.dispose()
  }
})

test('a fresh-panel GET times out, is diagnostic, and is retried with backoff rather than every pump', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  let panelCalls = 0
  let firstSignal = null
  const server = fakeInbox({
    ack: async (body) => ackComplete(body),
    livePanel: panel({ revision: 1, pushRevision: 1, items: [] }),
  })
  const originalFetch = server.fetchImpl
  server.fetchImpl = async (url, init = {}) => {
    if (url.endsWith('/panel')) {
      panelCalls += 1
      if (panelCalls === 1) {
        firstSignal = init.signal
        return abortableHang(init, 'panel')
      }
      return json(server.livePanel)
    }
    return originalFetch(url, init)
  }
  const client = createInboxClient(ctxFor(agent), config(server, stateFile, {
    panelTimeoutMs: 20,
    verifyRetryBaseMs: 20,
    verifyRetryMaxMs: 60,
  }))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-panel-timeout', { frozenRevision: 1 })],
    }))
    await waitFor(() => client.status().pendingAck === null, 'ACK resolved')
    await waitFor(() => panelCalls >= 2, 'fresh panel retry')
    assert.equal(firstSignal?.aborted, true, 'the timed-out GET signal is aborted')
    assert.equal(panelCalls, 2, 'one retry after the timeout, not a pump-driven storm')
  } finally {
    client.dispose()
  }
})

test('a persistently failing fresh-panel GET backs off and keeps a bounded diagnostic', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  let panelCalls = 0
  const server = fakeInbox({
    ack: async (body) => ackComplete(body),
    livePanel: panel({ revision: 1, pushRevision: 1, items: [] }),
  })
  const originalFetch = server.fetchImpl
  server.fetchImpl = async (url, init = {}) => {
    if (url.endsWith('/panel')) {
      panelCalls += 1
      return abortableHang(init, 'panel')
    }
    return originalFetch(url, init)
  }
  const client = createInboxClient(ctxFor(agent), config(server, stateFile, {
    panelTimeoutMs: 20,
    verifyRetryBaseMs: 25,
    verifyRetryMaxMs: 50,
  }))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-panel-fail', { frozenRevision: 1 })],
    }))
    await waitFor(() => client.status().pendingAck === null, 'ACK resolved')
    await new Promise((resolve) => setTimeout(resolve, 200))
    assert.ok(panelCalls >= 2 && panelCalls <= 6, `bounded GET retries, saw ${panelCalls}`)
    const diagnostic = client.status().freshVerification
    assert.equal(diagnostic?.required, true)
    assert.match(String(diagnostic?.lastError), /timeout|fresh_verify/i)
    assert.match(String(client.status().lastError), /timeout|fresh_verify/i)
  } finally {
    client.dispose()
  }
})

test('ACK failures use bounded exponential backoff instead of a pump-driven zero delay', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const times = []
  const server = fakeInbox({
    ack: async () => {
      times.push(Date.now())
      return json({ error: 'unavailable' }, 503)
    },
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile, {
    pumpMs: 5,
    ackRetryBaseMs: 30,
    ackRetryMaxMs: 120,
    ackMaxAttempts: 10,
  }))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-backoff', { frozenRevision: 1 })],
    }))
    await waitFor(() => times.length >= 4, 'four ACK attempts')
    const gaps = times.slice(1).map((value, index) => value - times[index])
    assert.ok(gaps[0] >= 20, `first retry gap ${gaps[0]}ms should respect backoff`)
    assert.ok(gaps[1] >= 35, `second retry gap ${gaps[1]}ms should grow`)
    assert.ok(gaps[2] >= 60, `third retry gap ${gaps[2]}ms should grow toward the cap`)
    assert.equal(server.state.ackCalls, times.length)
  } finally {
    client.dispose()
  }
})

test('stop and unbind issue no new ACK, while pause keeps ACK recovery running', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  let mode = 'fail'
  const server = fakeInbox({
    ack: async (body) => (mode === 'fail' ? json({ error: 'down' }, 503) : ackComplete(body)),
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile, {
    ackMaxAttempts: 1000,
    ackRetryBaseMs: 15,
  }))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-pause-ack', { frozenRevision: 1 })],
    }))
    await waitFor(() => server.state.ackCalls >= 2, 'ACK retries running')
    client.pause()
    const afterPause = server.state.ackCalls
    await new Promise((resolve) => setTimeout(resolve, 100))
    assert.ok(server.state.ackCalls > afterPause, 'pause must not stop ACK recovery')
    client.resume()
    client.stop()
    const afterStop = server.state.ackCalls
    await new Promise((resolve) => setTimeout(resolve, 100))
    assert.equal(server.state.ackCalls, afterStop, 'stop must not issue new ACK')
    client.start()
    await waitFor(() => server.state.ackCalls > afterStop, 'start resumes ACK')
    client.unbind()
    const afterUnbind = server.state.ackCalls
    await new Promise((resolve) => setTimeout(resolve, 100))
    assert.equal(server.state.ackCalls, afterUnbind, 'unbind must not issue new ACK')
    mode = 'ok'
    client.bind('session-1')
    await waitFor(() => client.status().pendingAck === null, 'bind resumes ACK')
  } finally {
    client.dispose()
  }
})

test('dispose aborts an in-flight ACK request and leaves the durable record untouched', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  let signal = null
  fakeInbox()
  const server = fakeInbox({
    ack: async (body, state) => {
      signal = state.requests.at(-1).init.signal
      return abortableHang(state.requests.at(-1).init, 'ack')
    },
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile, {
    ackTimeoutMs: 5000,
  }))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-dispose-ack', { frozenRevision: 1 })],
    }))
    await waitFor(() => server.state.ackCalls === 1, 'ACK in flight')
    const before = JSON.parse(readFileSync(paths.v2, 'utf8')).pendingAck
    assert.ok(before)
    client.dispose()
    assert.equal(signal?.aborted, true, 'dispose aborts the ACK request')
    await new Promise((resolve) => setTimeout(resolve, 50))
    assert.deepEqual(JSON.parse(readFileSync(paths.v2, 'utf8')).pendingAck?.itemIds, before.itemIds)
  } finally {
    client.dispose()
  }
})

test('untrusted ACK HTTP/exception text never reaches durable state, status, or heartbeat', async () => {
  for (const mode of ['http', 'exception']) {
    const { stateFile, paths } = tempState()
    const messages = []
    const agent = agentRecorder('session-1', messages)
    let calls = 0
    const heartbeatBodies = []
    const server = fakeInbox({
      ack: async () => {
        calls += 1
        if (mode === 'http') {
          return json({ error: 'SECRET_SERVER_CODE', detail: 'SECRET_BODY_TOKEN' }, 500)
        }
        throw new Error('SECRET_EXCEPTION_TOKEN')
      },
      heartbeat: async (init) => {
        heartbeatBodies.push(JSON.parse(init.body))
        return json({ mcpStartId: 'mcp-1' })
      },
    })
    const client = createInboxClient(ctxFor(agent), config(server, stateFile, {
      ackMaxAttempts: 1,
      ackRetryBaseMs: 10,
    }))
    try {
      client.start()
      server.push(panel({
        revision: 1, pushRevision: 1,
        items: [item('itm-secret-' + mode, { frozenRevision: 1 })],
      }))
      await waitFor(() => calls >= 1 && client.status().pendingAck, `${mode} failure recorded`)
      await client.heartbeat()
      const persisted = readFileSync(paths.v2, 'utf8')
      const status = JSON.stringify(client.status())
      const heartbeat = JSON.stringify(heartbeatBodies)
      for (const text of [persisted, status, heartbeat]) {
        assert.doesNotMatch(text, /SECRET_SERVER_CODE|SECRET_BODY_TOKEN|SECRET_EXCEPTION_TOKEN/)
      }
      assert.match(String(client.status().pendingAck.lastError), /ack_http_500|ack_network/)
    } finally {
      client.dispose()
    }
  }
})

test('a followup exception body never reaches status or heartbeat text', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages, async () => {
    throw new Error('SECRET_FOLLOWUP_TOKEN')
  })
  const heartbeatBodies = []
  const server = fakeInbox({
    heartbeat: async (init) => {
      heartbeatBodies.push(JSON.parse(init.body))
      return json({ mcpStartId: 'mcp-1' })
    },
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-followup-secret', { frozenRevision: 1 })],
    }))
    await waitFor(() => messages.length === 1 && client.status().injectionIntent, 'uncertain intent')
    await client.heartbeat()
    for (const text of [
      readFileSync(paths.v2, 'utf8'),
      JSON.stringify(client.status()),
      JSON.stringify(heartbeatBodies),
    ]) {
      assert.doesNotMatch(text, /SECRET_FOLLOWUP_TOKEN/)
    }
    assert.match(String(client.status().lastError), /followup_failed/)
  } finally {
    client.dispose()
  }
})

function makeAgent() {
  return agentRecorder('session-1', [])
}

test('failed control-state writes are explicit and do not pretend the intent was persisted', async () => {
  const cases = [
    {
      name: 'pause',
      setup: async (client) => client,
      act: (client) => client.pause(),
      check: (client) => assert.equal(client.status().paused, false),
    },
    {
      name: 'resume',
      setup: async (client) => { client.pause(); return client },
      act: (client) => client.resume(),
      check: (client) => assert.equal(client.status().paused, true),
    },
    {
      name: 'bind',
      setup: async (client) => client,
      act: (client) => client.bind('session-2'),
      check: (client) => assert.equal(client.status().targetSessionId, null),
    },
    {
      name: 'unbind',
      setup: async (client) => { client.bind('session-2'); await new Promise((r) => setTimeout(r, 20)); return client },
      act: (client) => client.unbind(),
      check: (client) => assert.equal(client.status().targetSessionId, 'session-2'),
    },
    {
      name: 'start',
      setup: async (client) => client,
      act: (client) => client.start(),
      check: (client) => assert.equal(client.status().running, false),
    },
    {
      name: 'stop',
      setup: async (client) => { client.start(); await new Promise((r) => setTimeout(r, 20)); return client },
      act: (client) => client.stop(),
      check: (client) => assert.equal(client.status().running, true),
    },
  ]

  for (const entry of cases) {
    const { dir, stateFile } = tempState()
    const server = fakeInbox()
    const client = createInboxClient(ctxFor(makeAgent(), makeAgent()), config(server, stateFile, {
      autoStart: false,
    }))
    try {
      await entry.setup(client)
      chmodSync(dir, 0o500)
      assert.throws(() => entry.act(client), /state_write_failed|无法持久化|state/i, entry.name)
      entry.check(client)
      assert.match(String(client.status().persistence.error ?? ''), /state_write_failed|state/i, entry.name)
    } finally {
      chmodSync(dir, 0o700)
      client.dispose()
    }
  }
})

test('a failed stop does not become a silent auto-start after restart', async () => {
  const { dir, stateFile } = tempState()
  const server = fakeInbox()
  const first = createInboxClient(ctxFor(makeAgent()), config(server, stateFile))
  first.start()
  await new Promise((resolve) => setTimeout(resolve, 20))
  try {
    chmodSync(dir, 0o500)
    assert.throws(() => first.stop(), /state_write_failed|无法持久化|state/i)
    assert.equal(first.status().running, true, 'a failed stop leaves the runtime running')
  } finally {
    chmodSync(dir, 0o700)
    first.dispose()
  }
  const second = createInboxClient(ctxFor(makeAgent()), config(fakeInbox(), stateFile))
  try {
    assert.equal(second.status().stopped, false, 'the old persisted intent was not silently reversed')
  } finally {
    second.dispose()
  }
})

test('deferred push diagnostics survive a restart', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  let failAck = true
  const server = fakeInbox({
    ack: async (body) => (failAck ? json({ error: 'down' }, 503) : ackComplete(body)),
  })
  const first = createInboxClient(ctxFor(agent), config(server, stateFile, {
    ackMaxAttempts: 100,
    ackRetryBaseMs: 1000,
  }))
  first.start()
  server.push(panel({
    revision: 1, pushRevision: 1,
    items: [item('itm-deferred-1', { frozenRevision: 1 })],
  }))
  await waitFor(() => first.status().pendingAck, 'pending')
  server.push(panel({
    revision: 2, pushRevision: 2,
    items: [item('itm-deferred-2', { frozenRevision: 2 })],
  }))
  await waitFor(() => first.status().deferredPush?.pushRevision === 2, 'deferred push diagnostic')
  first.dispose()
  const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
  assert.equal(saved.deferredPush?.pushRevision, 2, 'deferred diagnostic is persisted')
  failAck = false
  const second = createInboxClient(ctxFor(agent), config(fakeInbox(), stateFile))
  try {
    assert.equal(second.status().deferredPush?.pushRevision, 2, 'restart keeps the diagnostic')
    assert.equal(second.status().deferredPush?.reason, saved.deferredPush.reason)
  } finally {
    second.dispose()
  }
})

test('fresh-panel verification requirement survives a restart and gates injection', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  let panelGate = deferred()
  const server = fakeInbox({ ack: async (body) => ackComplete(body) })
  const originalFetch = server.fetchImpl
  server.fetchImpl = async (url, init = {}) => {
    if (url.endsWith('/panel')) return panelGate.promise
    return originalFetch(url, init)
  }
  const first = createInboxClient(ctxFor(agent), config(server, stateFile, {
    panelTimeoutMs: 5000,
  }))
  first.start()
  server.push(panel({
    revision: 1, pushRevision: 1,
    items: [item('itm-verify-restart', { frozenRevision: 1 })],
  }))
  await waitFor(() => first.status().freshVerification?.required === true, 'verification required')
  first.dispose()
  panelGate.resolve(json(panel({ revision: 2, pushRevision: 2, items: [] })))
  const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
  assert.equal(saved.freshVerification?.required, true, 'verification requirement is persisted before GET')
  const second = createInboxClient(ctxFor(agent), config(fakeInbox(), stateFile))
  try {
    assert.equal(second.status().freshVerification?.required, true, 'restart keeps the requirement')
    second.start()
    await waitFor(() => second.status().freshVerification?.required === false, 'restart verifies first')
    assert.equal(messages.length, 1)
  } finally {
    second.dispose()
  }
})

test('discarding an uncertain intent warns and advances the old push watermark so it is not replayed', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages, async () => {
    throw new Error('side effect may have happened')
  })
  const server = fakeInbox()
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    const samePush = panel({
      revision: 1, pushRevision: 1,
      items: [item('itm-discard-intent', { frozenRevision: 1 })],
    })
    server.push(samePush)
    await waitFor(() => client.status().injectionIntent, 'uncertain intent')
    assert.equal(client.status().injectedPushRevision, 0)
    const warning = client.discard(true)
    assert.match(warning, /不会自动重放|同一 ?push|重复注入/u)
    assert.ok(client.status().injectedPushRevision >= 1, 'discard advances the old push watermark')
    const saved = JSON.parse(readFileSync(paths.v2, 'utf8'))
    assert.ok(saved.injectedPushRevision >= 1)
    assert.equal(saved.injectionIntent, null)
    server.push(samePush)
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(messages.length, 1, 'the same push is not replayed after an uncertain discard')
  } finally {
    client.dispose()
  }
})

test('accepting a new database identity resets unusable watermarks and warns explicitly', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const server = fakeInbox({
    ack: async (body) => ackComplete(body),
    livePanel: panel({ revision: 1, pushRevision: 1, identity: IDENTITY, items: [] }),
  })
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(panel({
      revision: 5, pushRevision: 5,
      items: [item('itm-old-db', { frozenRevision: 5 })],
    }))
    await waitFor(() => messages.length === 1 && client.status().injectedPushRevision === 5, 'old DB acked')
    await waitFor(() => client.status().freshVerification?.required === false, 'fresh verify done')
    const newDbPanel = panel({
      revision: 1, pushRevision: 1, identity: 'inb_new_database',
      items: [item('itm-new-db', { frozenRevision: 1 })],
    })
    server.push(newDbPanel)
    await waitFor(() => client.status().identityMismatch?.seen === 'inb_new_database', 'identity change')
    assert.equal(messages.length, 1, 'new DB must not inject before explicit accept')
    const message = client.acceptIdentity(true)
    assert.match(message, /旧.*水位|重置|watermark/u)
    assert.equal(client.status().injectedPushRevision, 0)
    assert.equal(JSON.parse(readFileSync(paths.v2, 'utf8')).injectedPushRevision, 0)
    server.push(newDbPanel)
    await waitFor(() => messages.length === 2, 'new DB injects after explicit accept')
    assert.match(messages[1].message.content[0].text, /itm-new-db|INBOX_PANEL/u)
  } finally {
    client.dispose()
  }
})

test('an SSE push that mixes frozen revisions is not injected or ACKed as one batch', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const server = fakeInbox()
  const client = createInboxClient(ctxFor(agent), config(server, stateFile))
  try {
    client.start()
    server.push(panel({
      revision: 2, pushRevision: 2,
      items: [
        item('itm-old-frozen', { frozenRevision: 1 }),
        item('itm-new-frozen', { frozenRevision: 2 }),
      ],
    }))
    await new Promise((resolve) => setTimeout(resolve, 150))
    assert.equal(messages.length, 0, 'mixed frozen revisions must not be merged into one push')
    assert.equal(server.state.ackCalls, 0)
    assert.match(String(client.status().lastError), /frozen|batch|mixed/i)
  } finally {
    client.dispose()
  }
})

test('a reconnected SSE snapshot also rejects mixed frozen revisions', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const server = fakeInbox()
  const client = createInboxClient(ctxFor(agent), config(server, stateFile, {
    reconnectMinMs: 20,
    reconnectMaxMs: 40,
  }))
  try {
    client.start()
    await waitFor(() => server.state.controllers.length === 1, 'first connection')
    server.state.controllers[0].close()
    await waitFor(() => server.state.controllers.length >= 2, 'reconnect')
    server.push(panel({
      revision: 3, pushRevision: 3,
      items: [
        item('itm-reconnect-old', { frozenRevision: 2 }),
        item('itm-reconnect-new', { frozenRevision: 3 }),
      ],
    }))
    await new Promise((resolve) => setTimeout(resolve, 150))
    assert.equal(messages.length, 0)
    assert.equal(server.state.ackCalls, 0)
    assert.match(String(client.status().lastError), /frozen|batch|mixed/i)
  } finally {
    client.dispose()
  }
})

test('the pre-ACK identity GET can time out and cancel without issuing an ACK', async () => {
  const { stateFile } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const firstPanel = panel({
    revision: 1, pushRevision: 1,
    items: [item('itm-identity-timeout', { frozenRevision: 1 })],
  })
  const firstServer = fakeInbox({ ack: async (body) => ackComplete(body) })
  const first = createInboxClient(ctxFor(agent), config(firstServer, stateFile))
  first.start()
  firstServer.push(firstPanel)
  await waitFor(() => first.status().pendingAck, 'pending before restart')
  first.dispose()

  let panelCalls = 0
  let signal = null
  const secondServer = fakeInbox({ ack: async (body) => ackComplete(body) })
  const originalFetch = secondServer.fetchImpl
  secondServer.fetchImpl = async (url, init = {}) => {
    if (url.endsWith('/panel')) {
      panelCalls += 1
      if (panelCalls === 1) signal = init.signal
      return abortableHang(init, 'identity-panel')
    }
    return originalFetch(url, init)
  }
  const second = createInboxClient(ctxFor(agent), config(secondServer, stateFile, {
    panelTimeoutMs: 20,
    ackRetryBaseMs: 15,
  }))
  try {
    second.start()
    await waitFor(() => panelCalls >= 2, 'identity GET retried after timeout')
    assert.equal(signal?.aborted, true, 'identity GET signal is aborted')
    assert.equal(secondServer.state.ackCalls, 0, 'no ACK without a verified current identity')
    assert.match(String(second.status().pendingAck?.lastError), /identity|timeout/i)
  } finally {
    second.dispose()
  }
})

test('dispose during a suspended resume continuation cannot start timers or write after lock release', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-9', messages)
  const resumeGate = deferred()
  let effectCalls = 0
  const ctx = {
    agents: {
      get: () => undefined,
      list: () => [],
      resume: async () => resumeGate.promise,
    },
    effect: (callback) => { effectCalls += 1; return callback() },
  }
  const server = fakeInbox()
  const client = createInboxClient(ctx, config(server, stateFile, {
    sessionId: 'session-9',
  }))
  try {
    const restored = client.restore('session-9')
    await new Promise((resolve) => setTimeout(resolve, 20))
    const before = readFileSync(paths.v2, 'utf8')
    client.dispose()
    resumeGate.resolve({ agent, dispose() {} })
    await restored
    await new Promise((resolve) => setTimeout(resolve, 120))
    assert.equal(messages.length, 0)
    assert.equal(readFileSync(paths.v2, 'utf8'), before, 'no write after dispose')
    assert.equal(server.state.requests.filter((entry) => entry.url.endsWith('/events')).length, 0,
      'no SSE start after dispose')
    assert.equal(effectCalls, 0, 'no effect registered after dispose')
  } finally {
    client.dispose()
  }
})

test('a delayed heartbeat control cannot undo a newer stop', async () => {
  const { stateFile, paths } = tempState()
  const messages = []
  const agent = agentRecorder('session-1', messages)
  const other = agentRecorder('session-9', messages)
  const gate = deferred()
  const server = fakeInbox({ heartbeat: () => gate.promise })
  const client = createInboxClient(ctxFor(agent, other), config(server, stateFile, {
    sessionId: 'session-1',
  }))
  try {
    client.start()
    await waitFor(() => server.state.heartbeatCalls >= 1, 'heartbeat in flight')
    client.stop()
    assert.equal(client.status().running, false)
    const eventsAfterStop = server.state.requests.filter((entry) => entry.url.endsWith('/events')).length
    gate.resolve(json({
      control: { id: 'ctl-after-stop', action: 'restore', sessionId: 'session-9', remind: 'late' },
    }))
    await new Promise((resolve) => setTimeout(resolve, 150))
    assert.equal(client.status().running, false, 'a newer stop wins')
    assert.equal(client.status().stopped, true)
    assert.equal(client.status().targetSessionId, 'session-1', 'the delayed control did not rebind')
    assert.equal(messages.length, 0)
    assert.equal(server.state.requests.filter((entry) => entry.url.endsWith('/events')).length, eventsAfterStop)
    assert.equal(JSON.parse(readFileSync(paths.v2, 'utf8')).stopped, true)
  } finally {
    client.dispose()
  }
})
