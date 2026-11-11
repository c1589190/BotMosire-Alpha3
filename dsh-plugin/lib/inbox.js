import { readFileSync } from 'node:fs'

const WAKE_REVISION_HEADER = '[INBOX_PANEL]'

function tokenFor(config) {
  const fileToken = config.tokenFile ? readFileSync(config.tokenFile, 'utf8').trim() : undefined
  return config.token
    ?? fileToken
    ?? (config.tokenEnv ? process.env[config.tokenEnv] : undefined)
    ?? process.env.BMA3_INBOX_TOKEN
    ?? ''
}

function endpoint(config, suffix = '') {
  return `${String(config.baseUrl ?? '').replace(/\/$/, '')}`
    + `/v1/inboxes/${encodeURIComponent(config.inboxId ?? 'bma3')}${suffix}`
}

/**
 * Render one bounded panel as the wake text. Full history stays in the message board;
 * this text carries only source labels, relation markers and a short omitted count.
 */
export function renderPanel(panel) {
  const items = Array.isArray(panel?.items) ? panel.items : []
  const includeOmitted = panel?.includeOmittedCount !== false
  const total = includeOmitted && Number.isInteger(panel?.totalItems)
    ? panel.totalItems : items.length
  const lines = [
    `${WAKE_REVISION_HEADER} inbox=${panel?.inboxId ?? 'bma3'}`
      + ` rev=${panel?.revision ?? 0} unread=${total} items=${items.length}/${total}`,
  ]
  for (let position = 0; position < items.length; position++) {
    const item = items[position]
    const label = item?.sourceLabel ?? item?.source ?? 'unknown'
    const entries = Array.isArray(item?.entries) ? item.entries : []
    // The 1-based position is what inbox_ack(indices/range) expects; it is only valid for this
    // panel revision, which is exactly why the agent gets the revision in the header.
    const index = Number.isInteger(item?.index) ? item.index : position + 1
    lines.push(`[${label}] #${index} priority=${item?.priority ?? 0}`
      + ` total=${item?.totalMessages ?? entries.length} shown=${entries.length}`)
    for (const entry of entries) {
      lines.push(`  ${entry?.line ?? fallbackLine(entry)}`)
    }
    if (includeOmitted && Number(item?.omittedMessages) > 0) {
      lines.push(`  …（省略 ${item.omittedMessages} 条）`)
    }
  }
  if (includeOmitted && Number(panel?.omittedItems) > 0) {
    lines.push(`…（另有 ${panel.omittedItems} 个来源未显示）`)
  }
  return lines.join('\n')
}

function fallbackLine(entry) {
  const seq = entry?.localSeq ?? 0
  const sender = entry?.sender ?? ''
  const text = entry?.text ?? ''
  const markers = Array.isArray(entry?.markers) ? entry.markers.join(' ') : ''
  return `#${seq} ${sender}：${text}${markers ? ` ${markers}` : ''}`
}

/** Minimal SSE frame parser; exposed for tests and deterministic reconnect handling. */
export class SseParser {
  constructor(onEvent) {
    this.onEvent = onEvent
    this.buffer = ''
  }

  push(chunk) {
    this.buffer += String(chunk ?? '')
    let index
    while ((index = this.buffer.search(/\r?\n\r?\n/)) >= 0) {
      const frame = this.buffer.slice(0, index)
      this.buffer = this.buffer.slice(index).replace(/^\r?\n\r?\n/, '')
      this.dispatch(frame)
    }
  }

  dispatch(frame) {
    let id = ''
    let event = 'message'
    const data = []
    for (const rawLine of frame.split(/\r?\n/)) {
      if (rawLine.startsWith(':')) continue
      const colon = rawLine.indexOf(':')
      const field = colon < 0 ? rawLine : rawLine.slice(0, colon)
      let value = colon < 0 ? '' : rawLine.slice(colon + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'id') id = value
      else if (field === 'event') event = value
      else if (field === 'data') data.push(value)
    }
    if (data.length > 0) this.onEvent?.({ id, event, data: data.join('\n') })
  }
}

function createState() {
  return {
    connection: 'idle',
    lastRevision: 0,
    injectedRevision: 0,
    ackedRevision: 0,
    pendingRevision: 0,
    lastEventAt: null,
    lastError: null,
    targetSessionId: null,
  }
}

/**
 * One inbox = one SSE stream. Source items are just panel entries; there is no per-source
 * subscription. The panel is a work signal; after a successful followup we advance the
 * server read cursor so the same panel is not injected repeatedly.
 */
export function createInboxClient(ctx, config = {}) {
  const state = createState()
  const fetchImpl = config.fetchImpl ?? fetch
  let stopped = true
  let abort = null
  let pending = null
  let retryTimer = null
  let pumpTimer = null
  let injecting = false
  let retryMs = Math.max(200, Number(config.reconnectMinMs ?? 1000))
  const pumpMs = Math.max(100, Number(config.pumpMs ?? 500))

  const status = () => ({
    ...state,
    pendingRevision: pending?.revision ?? 0,
    connected: state.connection === 'open',
  })

  const findAgent = () => {
    const agents = ctx?.agents
    if (state.targetSessionId) return agents?.get?.(state.targetSessionId)
    return agents?.list?.().find((candidate) => candidate?.status === 'idle')
  }

  const wake = async (panel) => {
    if (!panel || !Array.isArray(panel.items) || panel.items.length === 0) return false
    if (panel.revision <= state.injectedRevision) return false
    const agent = findAgent()
    if (!agent || agent.status !== 'idle' || typeof agent.followup !== 'function') return false
    const text = renderPanel(panel)
    await agent.followup({
      id: `bma3-inbox-${panel.revision}`,
      role: 'user',
      content: [{ type: 'text', text }],
      source: { kind: 'bma3-inbox', revision: panel.revision },
    })
    state.injectedRevision = Math.max(state.injectedRevision, panel.revision)
    state.lastError = null
    try {
      await ack(panel.revision)
    } catch (error) {
      state.lastError = `ack: ${error?.message ?? error}`
    }
    return true
  }

  const pump = async () => {
    if (stopped || injecting || !pending) return
    injecting = true
    try {
      const panel = pending
      const injected = await wake(panel)
      if (injected && pending === panel) pending = null
    } catch (error) {
      state.lastError = `wake: ${error?.message ?? error}`
    } finally {
      injecting = false
    }
  }

  const ack = async (revision = state.lastRevision) => {
    const response = await fetchImpl(endpoint(config, '/ack'), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${tokenFor(config)}`,
      },
      body: JSON.stringify({ revision }),
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const body = await response.json()
    state.ackedRevision = Number(body?.ackedRevision ?? revision)
    return body
  }

  const handleEvent = async ({ id, event, data }) => {
    state.lastEventAt = new Date().toISOString()
    if (event === 'heartbeat') {
      state.lastRevision = Number(id) || state.lastRevision
      return
    }
    if (event !== 'panel') return
    let panel
    try {
      panel = JSON.parse(data)
    } catch {
      state.lastError = 'invalid panel JSON'
      return
    }
    state.lastRevision = Number(panel?.revision ?? id ?? 0)
    if (Array.isArray(panel?.items) && panel.items.length === 0) {
      pending = null
      return
    }
    if (panel.revision > state.injectedRevision) {
      pending = panel
      await pump()
    }
  }

  const scheduleReconnect = () => {
    if (stopped) return
    state.connection = 'retrying'
    const delay = retryMs
    retryMs = Math.min(Math.max(retryMs * 2, 1000), Number(config.reconnectMaxMs ?? 30000))
    retryTimer = setTimeout(() => {
      retryTimer = null
      void connect()
    }, delay)
    retryTimer.unref?.()
  }

  const readStream = async (response) => {
    const reader = response.body?.getReader?.()
    if (!reader) throw new Error('SSE response body is not readable')
    const decoder = new TextDecoder()
    const parser = new SseParser((event) => { void handleEvent(event) })
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      parser.push(decoder.decode(value, { stream: true }))
    }
    parser.push(decoder.decode())
  }

  const connect = async () => {
    if (stopped) return
    state.connection = 'connecting'
    abort = new AbortController()
    try {
      const response = await fetchImpl(endpoint(config, '/events'), {
        method: 'GET',
        headers: {
          Accept: 'text/event-stream',
          Authorization: `Bearer ${tokenFor(config)}`,
          ...(state.lastRevision > 0 ? { 'Last-Event-ID': String(state.lastRevision) } : {}),
        },
        signal: abort.signal,
      })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      retryMs = Math.max(200, Number(config.reconnectMinMs ?? 1000))
      state.connection = 'open'
      state.lastError = null
      await readStream(response)
      if (!stopped) throw new Error('SSE stream ended')
    } catch (error) {
      if (!stopped) {
        state.lastError = error?.name === 'AbortError' ? state.lastError : (error?.message ?? String(error))
        scheduleReconnect()
      }
    }
  }

  const start = () => {
    if (!stopped) return
    stopped = false
    pumpTimer = setInterval(() => { void pump() }, pumpMs)
    pumpTimer.unref?.()
    void connect()
  }

  const stop = () => {
    stopped = true
    abort?.abort()
    abort = null
    if (retryTimer) clearTimeout(retryTimer)
    if (pumpTimer) clearInterval(pumpTimer)
    retryTimer = null
    pumpTimer = null
    state.connection = 'closed'
  }

  if (config.sessionId) state.targetSessionId = String(config.sessionId)
  return { start, stop, status, ack: (revision) => ack(revision), bind: (sessionId) => {
    state.targetSessionId = String(sessionId ?? '').trim() || null
    return status()
  }, unbind: () => {
    state.targetSessionId = null
    return status()
  }, connect }
}

const OUTPUT = {
  schema: {
    type: 'object',
    properties: {
      text: { type: 'string', description: 'Human-readable Inbox status.' },
    },
    required: ['text'],
    additionalProperties: false,
  },
  render(_args, value) {
    return [{ type: 'text', text: value.text }]
  },
}

const BIND_SCHEMA = {
  type: 'object',
  properties: {
    sessionId: {
      type: 'string',
      description: 'Target DSH session id for this one inbox panel; omit to use the calling session.',
    },
  },
  additionalProperties: false,
}

export function apply(ctx, config = {}) {
  const client = createInboxClient(ctx, config)
  const tools = ctx?.tools ?? ctx?.get?.('tools')
  if (tools?.register) {
    const register = (definition) => ctx.effect(() => tools.register(definition))
    register({
      name: 'bma3_inbox_bind',
      description:
        'Set the DSH session that receives this inbox panel. There is one inbox SSE, not a subscription per source.',
      parameters: BIND_SCHEMA,
      output: OUTPUT,
      execute: async (args = {}, exec = {}) => {
        const target = String(args.sessionId ?? exec.agent?.id ?? '').trim()
        if (!target) throw new Error('sessionId 不能为空')
        return { text: JSON.stringify(client.bind(target), null, 2) }
      },
    })
    register({
      name: 'bma3_inbox_unbind',
      description: 'Clear the target DSH session; the inbox SSE connection stays available.',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: OUTPUT,
      execute: async () => ({ text: JSON.stringify(client.unbind(), null, 2) }),
    })
    register({
      name: 'bma3_inbox_ack',
      description: 'Advance the Inbox panel read cursor to the current revision.',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: OUTPUT,
      execute: async () => ({ text: JSON.stringify(await client.ack(), null, 2) }),
    })
    register({
      name: 'bma3_inbox_status',
      description:
        'Read the one-inbox SSE connection and panel cursor without exposing tokens.',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: OUTPUT,
      execute: async () => ({ text: JSON.stringify(client.status(), null, 2) }),
    })
  }

  if (config.autoStart !== false) {
    client.start()
    ctx?.effect?.(() => () => client.stop())
  }
  return client
}
