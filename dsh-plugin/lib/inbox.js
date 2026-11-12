import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname } from 'node:path'

const WAKE_REVISION_HEADER = '[INBOX_PANEL]'
const ACTIVE_MARKER = '（消息更新活跃）'
const STATE_VERSION = 1

function tokenFor(config) {
  const fileToken = config.tokenFile ? readFileSync(config.tokenFile, 'utf8').trim() : undefined
  return config.token
    ?? fileToken
    ?? (config.tokenEnv ? process.env[config.tokenEnv] : undefined)
    ?? process.env.BMA3_INBOX_TOKEN
    ?? ''
}

/** Where the token comes from — never the value, never the path. */
function tokenSource(config) {
  if (config.token) return 'config'
  if (config.tokenFile) return 'file'
  if (config.tokenEnv) return `env:${config.tokenEnv}`
  if (process.env.BMA3_INBOX_TOKEN) return 'env:BMA3_INBOX_TOKEN'
  return 'none'
}

function endpoint(config, suffix = '') {
  return `${String(config.baseUrl ?? '').replace(/\/$/, '')}`
    + `/v1/inboxes/${encodeURIComponent(config.inboxId ?? 'bma3')}${suffix}`
}

function defaultStateFile(config) {
  return config.stateFile ?? `${homedir()}/.dsh/storages/bma3-inbox.json`
}

function loadPersisted(config) {
  if (config.persistState === false) return {}
  try {
    const raw = readFileSync(defaultStateFile(config), 'utf8')
    const parsed = JSON.parse(raw)
    return parsed && parsed.version === STATE_VERSION ? parsed : {}
  } catch {
    return {}
  }
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
      + ` rev=${panel?.revision ?? 0} push=${panel?.pushRevision ?? 0}`
      + ` unread=${total} items=${items.length}/${total}`,
  ]
  for (let position = 0; position < items.length; position++) {
    const item = items[position]
    const label = item?.sourceLabel ?? item?.source ?? 'unknown'
    const entries = Array.isArray(item?.entries) ? item.entries : []
    // The 1-based position is what inbox_ack(indices/range) expects; it is only valid for this
    // panel revision, which is exactly why the agent gets the revision in the header.
    const index = Number.isInteger(item?.index) ? item.index : position + 1
    const active = item?.continued ? ACTIVE_MARKER : ''
    lines.push(`[${label}] #${index} priority=${item?.priority ?? 0}`
      + ` total=${item?.totalMessages ?? entries.length} shown=${entries.length}${active}`)
    for (const entry of entries) {
      lines.push(`  ${entry?.line ?? fallbackLine(entry)}`)
    }
    if (includeOmitted && Number(item?.omittedMessages) > 0) {
      lines.push(`  …（省略 ${item.omittedMessages} 条）`)
    }
    if (Number(item?.truncatedChars) > 0) {
      lines.push(`  …（已截断 ${item.truncatedChars} 字）`)
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

function createState(config) {
  const persisted = loadPersisted(config)
  return {
    connection: 'idle',
    lastRevision: 0,
    injectedRevision: 0,
    injectedPushRevision: Number(persisted.injectedPushRevision ?? 0),
    ackedRevision: Number(persisted.ackedRevision ?? 0),
    lastPushRevision: Number(persisted.lastPushRevision ?? 0),
    pendingRevision: 0,
    lastEventAt: null,
    lastError: null,
    targetSessionId: persisted.targetSessionId
      ?? (config.sessionId ? String(config.sessionId) : null),
    paused: Boolean(persisted.paused),
    stopped: Boolean(persisted.stopped),
    lastMcpStartId: String(persisted.lastMcpStartId ?? ''),
    appliedControlId: String(persisted.appliedControlId ?? ''),
    restoredAt: persisted.restoredAt ?? null,
  }
}

/**
 * One inbox = one SSE stream. Source items are just panel entries; there is no per-source
 * subscription. The panel is a work signal; after a successful followup we advance the
 * server read cursor so the same panel is not injected repeatedly.
 *
 * <p>0.0.4 adds two things the 0.0.3 client lacked: a persisted binding/push watermark (so a
 * DSH restart resumes instead of waiting for a human) and a heartbeat/control channel (so the
 * BMA3 watchdog can ask the plugin to restore a session or remind the agent to reload MCP).</p>
 */
export function createInboxClient(ctx, config = {}) {
  const state = createState(config)
  const fetchImpl = config.fetchImpl ?? fetch
  const stateFile = defaultStateFile(config)
  let lastPersisted = ''
  // `stopped` is the engine; `state.stopped` is the persisted user intent (stop/start actions).
  let stopped = true
  let abort = null
  let pending = null
  let retryTimer = null
  let pumpTimer = null
  let heartbeatTimer = null
  let injecting = false
  let resuming = null
  let resumeHandle = null
  let retryMs = Math.max(200, Number(config.reconnectMinMs ?? 1000))
  const pumpMs = Math.max(100, Number(config.pumpMs ?? 500))
  const heartbeatMs = Math.max(1000, Number(config.heartbeatMs ?? 15000))

  const persist = () => {
    if (config.persistState === false) return
    const snapshot = {
      version: STATE_VERSION,
      targetSessionId: state.targetSessionId,
      paused: state.paused,
      stopped,
      injectedPushRevision: state.injectedPushRevision,
      lastPushRevision: state.lastPushRevision,
      ackedRevision: state.ackedRevision,
      lastMcpStartId: state.lastMcpStartId,
      appliedControlId: state.appliedControlId,
      restoredAt: state.restoredAt,
      updatedAt: new Date().toISOString(),
    }
    const serialized = JSON.stringify(snapshot)
    if (serialized === lastPersisted) return
    try {
      mkdirSync(dirname(stateFile), { recursive: true })
      const temporary = `${stateFile}.tmp`
      writeFileSync(temporary, serialized, { mode: 0o600 })
      renameSync(temporary, stateFile)
      lastPersisted = serialized
    } catch (error) {
      state.lastError = `state: ${error?.message ?? error}`
    }
  }
  persist()

  const status = () => ({
    ...state,
    pendingRevision: pending?.revision ?? 0,
    connected: state.connection === 'open',
    running: !stopped,
    stopped: state.stopped,
    agentLive: Boolean(findAgent()),
    stateFile,
    // 配置只报来源，不回显 token 值或文件路径。
    config: {
      baseUrl: config.baseUrl ?? null,
      inboxId: config.inboxId ?? 'bma3',
      autoStart: config.autoStart !== false,
      tokenSource: tokenSource(config),
      sessionId: config.sessionId ? String(config.sessionId) : null,
    },
  })

  /**
   * A bound target must never fall back to "some other idle agent": injecting the wrong session
   * is worse than waiting. Unbound mode keeps the old "first idle" behaviour.
   */
  const findAgent = () => {
    const agents = ctx?.agents
    if (state.targetSessionId) return agents?.get?.(state.targetSessionId)
    return agents?.list?.().find((candidate) => candidate?.status === 'idle')
  }

  /** Resume a persisted session when it is not live yet (DSH restart). */
  const ensureAgent = async (sessionId) => {
    const agents = ctx?.agents
    if (!agents || !sessionId) return null
    const live = agents.get?.(sessionId)
    if (live) return live
    if (typeof agents.resume !== 'function') return null
    if (resuming) return resuming
    resuming = (async () => {
      try {
        const handle = await agents.resume({ resumeSessionId: sessionId })
        if (handle) {
          resumeHandle = handle
          ctx?.effect?.(() => () => {
            Promise.resolve(handle?.dispose?.()).catch(() => {})
          })
        }
        return handle?.agent ?? agents.get?.(sessionId) ?? null
      } catch (error) {
        state.lastError = `resume: ${error?.message ?? error}`
        return null
      } finally {
        resuming = null
      }
    })()
    return resuming
  }

  const remind = async (text) => {
    if (!text) return true
    const agent = findAgent()
    if (!agent || agent.status !== 'idle' || typeof agent.followup !== 'function') {
      return false
    }
    try {
      await agent.followup({
        id: `bma3-inbox-remind-${Date.now()}`,
        role: 'user',
        content: [{ type: 'text', text }],
        source: { kind: 'bma3-inbox', reminder: true },
      })
      return true
    } catch (error) {
      state.lastError = `remind: ${error?.message ?? error}`
      return false
    }
  }

  const applyControl = async (control) => {
    if (!control || !control.id || control.id === state.appliedControlId) return true
    let applied = true
    if (control.action === 'restore') {
      const target = String(control.sessionId ?? state.targetSessionId ?? '').trim()
      if (!target) {
        state.lastError = 'restore: empty sessionId'
        return false
      }
      state.targetSessionId = target
      const agent = await ensureAgent(target)
      if (!agent) {
        // Keep the SSE up anyway: the session may become live later (e.g. the user opens it),
        // and the binding must already be attached. The control stays pending for a retry.
        applied = false
      } else {
        state.restoredAt = new Date().toISOString()
      }
      if (stopped) {
        start(false)
        state.stopped = false
      }
      persist()
      if (applied && control.remind) {
        applied = await remind(control.remind)
      }
    } else if (control.action === 'remind') {
      applied = await remind(control.remind)
    }
    if (applied) {
      state.appliedControlId = control.id
      persist()
    }
    return applied
  }

  const heartbeat = async () => {
    if (stopped && !state.targetSessionId) return null
    try {
      const response = await fetchImpl(endpoint(config, '/plugin/heartbeat'), {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${tokenFor(config)}`,
        },
        body: JSON.stringify({
          sessionId: state.targetSessionId ?? '',
          bound: Boolean(state.targetSessionId),
          sse: state.connection,
          lastPushRevision: state.injectedPushRevision,
          lastMcpStartId: state.lastMcpStartId,
          lastError: state.lastError ?? '',
          appliedControlId: state.appliedControlId,
        }),
      })
      if (!response.ok) {
        state.lastError = `heartbeat: HTTP ${response.status}`
        return null
      }
      const body = await response.json()
      if (body?.mcpStartId && body.mcpStartId !== state.lastMcpStartId) {
        const previous = state.lastMcpStartId
        state.lastMcpStartId = String(body.mcpStartId)
        if (previous) {
          // BMA3 restarted: the DSH MCP client may still hold the old tool list.
          await remind('BMA3 已重启。如果 qq_*/inbox_* MCP 工具不可用，请先重连 MCP'
            + '（mcp_connector_reconnect），再继续处理 inbox。')
        }
        persist()
      }
      if (body?.control) {
        await applyControl(body.control)
      }
      return body
    } catch (error) {
      state.lastError = `heartbeat: ${error?.message ?? error}`
      return null
    }
  }

  const wake = async (panel) => {
    if (!panel || !Array.isArray(panel.items) || panel.items.length === 0) return false
    const push = Number(panel.pushRevision ?? panel.revision ?? 0)
    if (push <= state.injectedPushRevision) return false
    const agent = findAgent()
    if (!agent || agent.status !== 'idle' || typeof agent.followup !== 'function') return false
    const text = renderPanel(panel)
    await agent.followup({
      id: `bma3-inbox-${panel.revision}-${push}`,
      role: 'user',
      content: [{ type: 'text', text }],
      source: { kind: 'bma3-inbox', revision: panel.revision, pushRevision: push },
    })
    state.injectedRevision = Math.max(state.injectedRevision, panel.revision)
    state.injectedPushRevision = Math.max(state.injectedPushRevision, push)
    state.lastPushRevision = Math.max(state.lastPushRevision, push)
    state.lastError = null
    persist()
    try {
      const itemIds = panel.items.map((item) => item?.id).filter(Boolean)
      // 面板条目一定带 id；万一没有（老服务端/测试桩），退回只发 revision，
      // 服务端现在也只会 ack 真正推送过的条目。
      await ack(itemIds.length > 0 ? { revision: panel.revision, itemIds } : panel.revision)
    } catch (error) {
      state.lastError = `ack: ${error?.message ?? error}`
    }
    return true
  }

  const pump = async () => {
    if (stopped || state.paused || injecting || !pending) return
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

  const ack = async (target = state.lastRevision) => {
    // Callers may pass a revision, or {revision, itemIds} to acknowledge exactly the items that
    // were rendered. The item list matters: a panel is capped at maxItemsPerPanel, so a
    // revision-only ack used to acknowledge items that were never displayed.
    const payload = target && typeof target === 'object'
      ? { revision: target.revision, itemIds: target.itemIds }
      : { revision: target }
    const response = await fetchImpl(endpoint(config, '/ack'), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${tokenFor(config)}`,
      },
      body: JSON.stringify(payload),
    })
    if (!response.ok) {
      // 409 = 某条在你看到之后又被更新了（服务端按 itemId 判断，无关流量不再触发）。
      // 不重试成"只带 itemIds"：那会把没看过的新内容一起 ack 掉。留未确认状态，
      // 下一轮面板会带上新内容重新注入。
      const detail = response.status === 409 ? '（条目已更新，下一轮重推）' : ''
      throw new Error(`HTTP ${response.status}${detail}`)
    }
    const body = await response.json()
    state.ackedRevision = Number(body?.ackedRevision ?? payload.revision ?? 0)
    persist()
    return body
  }

  const handleEvent = async ({ id, event, data }) => {
    state.lastEventAt = new Date().toISOString()
    if (event === 'heartbeat') {
      state.lastRevision = Number(id) || state.lastRevision
      return
    }
    if (event === 'control') {
      try {
        await applyControl(JSON.parse(data))
      } catch (error) {
        state.lastError = `control: ${error?.message ?? error}`
      }
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
    const push = Number(panel?.pushRevision ?? panel?.revision ?? 0)
    state.lastPushRevision = Math.max(state.lastPushRevision, push)
    persist()
    if (Array.isArray(panel?.items) && panel.items.length === 0) {
      pending = null
      return
    }
    if (push > state.injectedPushRevision) {
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
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }
      retryMs = Math.max(200, Number(config.reconnectMinMs ?? 1000))
      state.connection = 'open'
      state.lastError = null
      void heartbeat()
      await readStream(response)
      if (!stopped) throw new Error('SSE stream ended')
    } catch (error) {
      if (!stopped) {
        state.lastError = error?.name === 'AbortError' ? state.lastError : (error?.message ?? String(error))
        scheduleReconnect()
      }
    }
  }

  const start = (persistStopped = true) => {
    if (!stopped) return
    stopped = false
    if (persistStopped) {
      state.stopped = false
    }
    persist()
    pumpTimer = setInterval(() => { void pump() }, pumpMs)
    pumpTimer.unref?.()
    if (!heartbeatTimer) {
      heartbeatTimer = setInterval(() => { void heartbeat() }, heartbeatMs)
      heartbeatTimer.unref?.()
    }
    void connect()
  }

  const stop = (persistStopped = true) => {
    stopped = true
    abort?.abort()
    abort = null
    if (retryTimer) clearTimeout(retryTimer)
    if (pumpTimer) clearInterval(pumpTimer)
    if (heartbeatTimer) clearInterval(heartbeatTimer)
    retryTimer = null
    pumpTimer = null
    heartbeatTimer = null
    state.connection = 'closed'
    if (persistStopped) {
      state.stopped = true
    }
    persist()
  }

  if (config.sessionId) state.targetSessionId = String(config.sessionId)
  const pause = () => {
    state.paused = true
    pending = null
    persist()
    return status()
  }

  const resume = () => {
    state.paused = false
    persist()
    return status()
  }

  /** Bind + resume (if needed) + start. Safe to call repeatedly. */
  const restore = async (sessionId = state.targetSessionId) => {
    const target = String(sessionId ?? '').trim()
    if (!target) return status()
    state.targetSessionId = target
    persist()
    const agent = await ensureAgent(target)
    if (!agent) {
      state.lastError = state.lastError ?? 'resume: session is not live yet'
    }
    // Start the SSE even when resume failed: the binding is durable and the session may be
    // opened later; starting now means the first push is not missed.
    if (stopped) {
      start(false)
      state.stopped = false
    }
    persist()
    if (!stopped) {
      void heartbeat()
    }
    return status()
  }

  return {
    start,
    stop,
    connect,
    status,
    heartbeat,
    restore,
    ack: (revision) => ack(revision),
    pause,
    resume,
    bind: (sessionId) => {
      const target = String(sessionId ?? '').trim() || null
      state.targetSessionId = target
      persist()
      if (target) {
        void restore(target)
      }
      return status()
    },
    unbind: () => {
      state.targetSessionId = null
      persist()
      return status()
    },
  }
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

const INBOX_SCHEMA = {
  type: 'object',
  properties: {
    action: {
      type: 'string',
      enum: ['status', 'bind', 'unbind', 'restore', 'pause', 'resume', 'start', 'stop', 'ack'],
      description: '查看或修改本会话的 inbox 挂载：status 看状态；bind/unbind 改目标会话；'
        + 'restore 恢复持久化绑定（必要时 resume 会话）并启动 SSE；pause/resume 暂停或恢复注入；'
        + 'start/stop 开关 SSE；ack 推进读游标。默认 status。',
    },
    sessionId: {
      type: 'string',
      description: 'bind/restore 时可指定目标 DSH 会话；省略则用调用本工具的会话或已持久化绑定。',
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
      name: 'bma3_inbox',
      description:
        '查看/修改当前会话的 inbox 挂载状态。action=status 返回绑定会话、SSE 连接、游标、暂停标志、'
        + 'push 水位、心跳/MCP 状态与配置来源；bind 把面板绑到指定会话（省略则当前会话）并持久化；'
        + 'restore 恢复持久化绑定并 resume 会话；unbind 解绑；pause/resume 暂停或恢复注入；'
        + 'start/stop 开关 SSE；ack 推进读游标。不带参数等价 status。',
      parameters: INBOX_SCHEMA,
      output: OUTPUT,
      execute: async (args = {}, exec = {}) => {
        const action = String(args.action ?? 'status').trim() || 'status'
        const callingSession = exec?.agent?.id ? String(exec.agent.id) : null
        let value
        switch (action) {
          case 'status':
            value = client.status()
            break
          case 'bind': {
            const target = String(args.sessionId ?? callingSession ?? '').trim()
            if (!target) throw new Error('sessionId 不能为空：调用方未提供会话 id 时请显式传参')
            value = client.bind(target)
            break
          }
          case 'restore': {
            value = await client.restore(args.sessionId ?? client.status().targetSessionId)
            break
          }
          case 'unbind':
            value = client.unbind()
            break
          case 'pause':
            value = client.pause()
            break
          case 'resume':
            value = client.resume()
            break
          case 'start':
            client.start()
            value = client.status()
            break
          case 'stop':
            client.stop()
            value = client.status()
            break
          case 'ack':
            value = await client.ack()
            break
          default:
            throw new Error(`未知 action: ${action}`)
        }
        return {
          text: JSON.stringify({
            action,
            callingSession,
            thisSessionIsTarget:
              value.targetSessionId != null && value.targetSessionId === callingSession,
            ...value,
          }, null, 2),
        }
      },
    })
  }

  // A persisted binding wins over autoStart: "bound once, bound forever" (D-045).
  const initial = client.status()
  if (initial.targetSessionId && !initial.stopped) {
    void client.restore(initial.targetSessionId)
  } else if (config.autoStart !== false && !initial.stopped) {
    client.start()
  }
  ctx?.effect?.(() => () => client.stop(false))
  return client
}
