import { readFileSync } from 'node:fs'

/** Rendered wake text; delivery id is the only internal handle exposed to the Agent. */
const WAKE_HEADER = '[INBOX_HOST_EVENT]'
const WAKE_PREVIEW_CHARS = 800

/**
 * The wake message is a bounded notification, not the full server-assembled text.
 * The full text stays in Inbox; the Agent reads it through tools after this signal.
 */
export function wakeText(delivery) {
  const raw = String(delivery?.text ?? '').replace(/\s+/g, ' ').trim() || '[空消息]'
  const preview = raw.length > WAKE_PREVIEW_CHARS
    ? `${raw.slice(0, WAKE_PREVIEW_CHARS)}…(已截断)`
    : raw
  const itemCount = Array.isArray(delivery?.itemIds) ? delivery.itemIds.length : 1
  return [
    WAKE_HEADER,
    `inbox=bma3 items=${itemCount} important=${delivery?.important === true}`,
    '',
    preview,
    '',
    '这是通知信号，不是完整正文；完整 content 在服务端。',
    '请调用程序 MCP 的 inbox_list 查看条目，处理完后调用 bma3_inbox_ack。',
  ].join('\n')
}

function tokenFor(config) {
  const fileToken = config.tokenFile ? readFileSync(config.tokenFile, 'utf8').trim() : undefined
  return config.token
    ?? fileToken
    ?? (config.tokenEnv ? process.env[config.tokenEnv] : undefined)
    ?? process.env.BMA3_INBOX_TOKEN
    ?? ''
}

/**
 * Poll one batch of due deliveries and inject each into an idle bound agent.
 * Returns the number of injected deliveries. No token is ever rendered into the message.
 */
export async function pollOnce({ ctx, config, fetchImpl = fetch, now = new Date(), onInjected }) {
  const baseUrl = String(config.baseUrl ?? '').replace(/\/$/, '')
  if (!baseUrl) {
    throw new Error('bma3 inbox: baseUrl 不能为空')
  }
  const inboxId = config.inboxId ?? 'bma3'
  const agents = ctx?.agents
  const agent = config.sessionId
    ? agents?.get?.(config.sessionId)
    : agents?.list?.().find((candidate) => candidate?.status === 'idle')
  // Never consume a delivery while the target is busy: it would become an open delivery
  // and block every later wake until someone acks it.
  if (!agent?.followup || agent.status !== 'idle') {
    return 0
  }

  const minPriority = Number.isInteger(config.minPriority) ? config.minPriority : undefined
  const dueUrl = `${baseUrl}/v1/inboxes/${encodeURIComponent(inboxId)}/deliveries/due`
    + (minPriority === undefined ? '' : `?minPriority=${minPriority}`)
  const response = await fetchImpl(dueUrl, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${tokenFor(config)}`,
    },
    body: JSON.stringify({ now: now.toISOString() }),
  })
  if (!response.ok) {
    throw new Error(`bma3 inbox: HTTP ${response.status}`)
  }
  const deliveries = await response.json()
  if (!Array.isArray(deliveries) || deliveries.length === 0) {
    return 0
  }

  let injected = 0
  for (const delivery of deliveries) {
    agent.followup({
      id: `bma3-${delivery.id}`,
      role: 'user',
      content: [{ type: 'text', text: wakeText(delivery) }],
      source: { kind: 'bma3-inbox', deliveryId: delivery.id },
    })
    onInjected?.(delivery)
    injected += 1
  }
  return injected
}

function publicBinding(binding) {
  return {
    sessionId: binding.sessionId,
    inboxId: binding.inboxId,
    baseUrl: binding.baseUrl,
    tokenFile: binding.tokenFile ?? null,
    minPriority: binding.minPriority ?? null,
  }
}

/** Per-session bindings. Nothing is bound until the Agent calls the bind tool. */
export function createBindingRegistry(ctx, config = {}) {
  const bindings = new Map()
  const lastDeliveries = new Map()

  const bind = (input = {}) => {
    const sessionId = String(input.sessionId ?? '').trim()
    if (!sessionId) {
      throw new Error('sessionId 不能为空；默认使用调用工具的 agent.id')
    }
    const binding = {
      sessionId,
      inboxId: String(input.inboxId ?? config.inboxId ?? 'bma3'),
      baseUrl: String(input.baseUrl ?? config.baseUrl ?? '').replace(/\/$/, ''),
      tokenFile: input.tokenFile ?? config.tokenFile,
      token: input.token,
      tokenEnv: input.tokenEnv ?? config.tokenEnv,
      minPriority: Number.isInteger(input.minPriority) ? input.minPriority : config.minPriority,
    }
    if (!binding.baseUrl) {
      throw new Error('baseUrl 不能为空')
    }
    bindings.set(sessionId, binding)
    return publicBinding(binding)
  }

  const unbind = (sessionId) => bindings.delete(String(sessionId ?? ''))
  const get = (sessionId) => bindings.get(String(sessionId ?? ''))
  const list = () => [...bindings.values()].map(publicBinding)

  const pollAll = async ({ fetchImpl = fetch, now = new Date() } = {}) => {
    let injected = 0
    for (const binding of bindings.values()) {
      injected += await pollOnce({
        ctx, config: binding, fetchImpl, now,
        onInjected: (delivery) => lastDeliveries.set(binding.sessionId, {
          ...binding, deliveryId: delivery.id,
        }),
      })
    }
    return injected
  }

  const lastDelivery = (sessionId) => lastDeliveries.get(String(sessionId ?? ''))
  const clearLast = (sessionId) => lastDeliveries.delete(String(sessionId ?? ''))
  return { bind, unbind, get, list, pollAll, lastDelivery, clearLast,
    clear: () => { bindings.clear(); lastDeliveries.clear() } }
}


/** DSH tool registry requires an explicit canonical output contract. */
const OUTPUT = {
  schema: {
    type: 'object',
    properties: {
      text: { type: 'string', description: 'Human-readable Inbox binding result.' },
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
      description: 'Target DSH session id; defaults to the calling session.',
    },
    inboxId: { type: 'string', description: 'Inbox id, default bma3.' },
    baseUrl: { type: 'string', description: 'Optional BMA3 Inbox API base URL.' },
    tokenFile: { type: 'string', description: 'Optional absolute path to the 0600 Inbox token.' },
    minPriority: {
      type: 'integer',
      description: 'Only wake for items at or above this priority; use 200 to ignore ordinary group traffic.',
    },
  },
  additionalProperties: false,
}

const STATUS_SCHEMA = {
  type: 'object',
  properties: {
    sessionId: {
      type: 'string',
      description: 'Optional session to inspect; omit to show the caller and all bindings.',
    },
  },
  additionalProperties: false,
}

export function apply(ctx, config = {}) {
  const registry = createBindingRegistry(ctx, config)
  const tools = ctx?.tools ?? ctx?.get?.('tools')
  if (tools?.register) {
    const register = (definition) => ctx.effect(() => tools.register(definition))
    register({
      name: 'bma3_inbox_bind',
      description:
        'Bind this DSH session (or an explicit sessionId) to a BMA3 Inbox. Nothing is bound by default; the Agent chooses whether to receive wake injections.',
      parameters: BIND_SCHEMA,
      output: OUTPUT,
      execute: async (args = {}, exec = {}) => {
        const target = String(args.sessionId ?? exec.agent?.id ?? '').trim()
        const binding = registry.bind({ ...args, sessionId: target })
        return { text: `已绑定 ${binding.sessionId} 到 ${binding.inboxId} (${binding.baseUrl})` }
      },
    })
    register({
      name: 'bma3_inbox_unbind',
      description: 'Remove the BMA3 Inbox wake binding for this session (or an explicit sessionId).',
      parameters: STATUS_SCHEMA,
      output: OUTPUT,
      execute: async (args = {}, exec = {}) => {
        const target = String(args.sessionId ?? exec.agent?.id ?? '').trim()
        const removed = registry.unbind(target)
        return { text: removed ? `已解绑 ${target}` : `${target} 当前没有绑定` }
      },
    })
    register({
      name: 'bma3_inbox_ack',
      description:
        'Ack the last BMA3 Inbox delivery injected into this session. The delivery id stays internal; the Agent never has to maintain it.',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: OUTPUT,
      execute: async (_args = {}, exec = {}) => {
        const target = String(exec.agent?.id ?? '').trim()
        const last = registry.lastDelivery(target)
        if (!last) {
          return { text: `${target} 当前没有待 ack 的 delivery` }
        }
        const url = `${last.baseUrl}/v1/inboxes/${encodeURIComponent(last.inboxId)}`
          + `/deliveries/${encodeURIComponent(last.deliveryId)}/ack`
        const response = await fetch(url, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${tokenFor(last)}`,
          },
          body: '{}',
        })
        if (!response.ok) {
          throw new Error(`bma3 inbox ack: HTTP ${response.status}`)
        }
        const body = await response.json()
        registry.clearLast(target)
        return { text: JSON.stringify(body) }
      },
    })
    register({
      name: 'bma3_inbox_status',
      description:
        'Read BMA3 Inbox bindings without exposing tokens: whether the caller is bound and the list of bound sessions.',
      parameters: STATUS_SCHEMA,
      output: OUTPUT,
      execute: async (args = {}, exec = {}) => {
        const caller = String(exec.agent?.id ?? '')
        const target = String(args.sessionId ?? caller).trim()
        const current = registry.get(target)
        const all = registry.list()
        return {
          text: JSON.stringify({
            sessionId: target,
            bound: current !== undefined,
            current: current ?? null,
            bindings: all,
          }, null, 2),
        }
      },
    })
  }

  if (config.autoStart !== false) {
    const intervalMs = Math.max(200, Number(config.intervalMs ?? 1000))
    const timer = setInterval(() => {
      registry.pollAll().catch(() => {
        // Service may be starting; the next tick retries.
      })
    }, intervalMs)
    timer.unref?.()
    ctx?.effect?.(() => () => {
      clearInterval(timer)
      registry.clear()
    })
  }
  return registry
}
