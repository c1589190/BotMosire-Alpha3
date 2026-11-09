import { readFileSync } from 'node:fs'

/** Rendered wake text; delivery id is the only internal handle exposed to the Agent. */
export function wakeText(delivery) {
  return `[BMA3 Inbox]\n${delivery.text}\n(delivery: ${delivery.id})`
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
export async function pollOnce({ ctx, config, fetchImpl = fetch, now = new Date() }) {
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

  const response = await fetchImpl(`${baseUrl}/v1/inboxes/${encodeURIComponent(inboxId)}/deliveries/due`, {
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
  }
}

/** Per-session bindings. Nothing is bound until the Agent calls the bind tool. */
export function createBindingRegistry(ctx, config = {}) {
  const bindings = new Map()

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
      injected += await pollOnce({ ctx, config: binding, fetchImpl, now })
    }
    return injected
  }

  return { bind, unbind, get, list, pollAll, clear: () => bindings.clear() }
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
      execute: async (args = {}, exec = {}) => {
        const target = String(args.sessionId ?? exec.agent?.id ?? '').trim()
        const removed = registry.unbind(target)
        return { text: removed ? `已解绑 ${target}` : `${target} 当前没有绑定` }
      },
    })
    register({
      name: 'bma3_inbox_status',
      description:
        'Read BMA3 Inbox bindings without exposing tokens: whether the caller is bound and the list of bound sessions.',
      parameters: STATUS_SCHEMA,
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
