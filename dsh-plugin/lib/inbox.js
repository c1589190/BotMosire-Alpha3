import { readFileSync } from 'node:fs'

/** Rendered wake text; delivery id is the only internal handle exposed to the Agent. */
export function wakeText(delivery) {
  return `[BMA3 Inbox]\n${delivery.text}\n(delivery: ${delivery.id})`
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
  const fileToken = config.tokenFile ? readFileSync(config.tokenFile, 'utf8').trim() : undefined
  const token = config.token
    ?? fileToken
    ?? (config.tokenEnv ? process.env[config.tokenEnv] : undefined)
    ?? process.env.BMA3_INBOX_TOKEN
    ?? ''
  const response = await fetchImpl(`${baseUrl}/v1/inboxes/${encodeURIComponent(inboxId)}/deliveries/due`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
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

  const agents = ctx?.agents
  const agent = config.sessionId
    ? agents?.get?.(config.sessionId)
    : agents?.list?.().find((candidate) => candidate?.status === 'idle')
  if (!agent?.followup) {
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

export function apply(ctx, config = {}) {
  if (config.autoStart === false) {
    return
  }
  const intervalMs = Math.max(200, Number(config.intervalMs ?? 1000))
  const timer = setInterval(() => {
    pollOnce({ ctx, config }).catch(() => {
      // Service may be starting; the next tick retries.
    })
  }, intervalMs)
  timer.unref?.()
  ctx?.effect?.(() => () => clearInterval(timer))
}
