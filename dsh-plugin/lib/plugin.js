import { join } from 'node:path'
import { apply as applyInbox } from './inbox.js'
import { apply as applyPreset } from './preset.js'
import { applyRuntime, runtimeRoot } from './runtime.js'

const EXTERNAL_MCP_CHECK_MS = 5000

async function reachableMcp(endpoint, signal) {
  try {
    const response = await fetch(endpoint.mcpUrl, {
      method: 'HEAD',
      headers: endpoint.mcpToken ? { Authorization: 'Bearer ' + endpoint.mcpToken } : {},
      redirect: 'error',
      signal: AbortSignal.any([signal, AbortSignal.timeout(3000)]),
    })
    await response.body?.cancel()
    // A Streamable HTTP server can reject HEAD with 405 even when the MCP route is healthy.
    return response.ok || [400, 405, 406].includes(response.status)
  } catch {
    return false
  }
}

function backendLink(inbox, logger) {
  let current = null
  let active = null
  const observers = new Set()
  const mcp = new Map()
  function updateInbox(endpoint) {
    try {
      inbox.setBackend(endpoint)
    } catch {
      logger.warn('BMA3 Inbox: BACKEND_UPDATE_FAILED')
    }
  }
  async function publish(endpoint) {
    if (current === endpoint) return
    current = endpoint
    mcp.clear()
    const updates = await Promise.allSettled(
      [...observers].map(async (observer) => observer(endpoint)),
    )
    if (updates.some((result) => result.status === 'rejected')) {
      logger.warn('BMA3 preset: BACKEND_UPDATE_FAILED')
    }
  }
  return {
    current: () => current,
    subscribe(observer) {
      observers.add(observer)
      const initial = current
      void Promise.resolve().then(() => observer(initial)).catch(() => {
        logger.warn('BMA3 preset: BACKEND_UPDATE_FAILED')
      })
      return () => observers.delete(observer)
    },
    reportMcp(observer, endpoint, state) {
      if (current === endpoint) mcp.set(observer, state)
    },
    removeMcp(observer) {
      mcp.delete(observer)
    },
    status() {
      const inboxStatus = inbox.status()
      const states = [...mcp.values()]
      return {
        inbox: !active?.inboxUrl ? 'offline' : inboxStatus.connected ? 'connected' : inboxStatus.connection,
        mcp: !current?.mcpUrl ? 'offline' : states.includes('connected') ? 'connected'
          : states[0] ?? 'waiting-preset',
      }
    },
    async activate(endpoint, watchExternal = false) {
      active = endpoint
      updateInbox(endpoint)
      await publish(endpoint)
      const watcher = new AbortController()
      let timer
      function schedule() {
        timer = setTimeout(async () => {
          const reachable = await reachableMcp(endpoint, watcher.signal)
          if (watcher.signal.aborted || active !== endpoint) return
          await publish(reachable ? endpoint : null)
          if (!watcher.signal.aborted) schedule()
        }, EXTERNAL_MCP_CHECK_MS)
        timer.unref?.()
      }
      if (watchExternal && endpoint.mcpUrl) schedule()
      return async () => {
        watcher.abort()
        clearTimeout(timer)
        if (active === endpoint) {
          active = null
          updateInbox(null)
          await publish(null)
        }
      }
    },
  }
}

export function applyPlugin(ctx, { runtime = {}, inbox = {}, preset = {} } = {}) {
  const source = runtime.source ?? 'bundled'
  let stateFile = inbox.stateFile
  let persistState = inbox.persistState
  if (stateFile === undefined && source !== 'external') {
    try {
      stateFile = join(runtimeRoot(ctx.get('profileContext'), runtime), 'inbox.json')
    } catch {
      persistState = false
    }
  }
  const inboxClient = applyInbox(ctx, {
    ...inbox,
    stateFile,
    persistState,
    reclaimManagedLock: inbox.stateFile === undefined,
    hasMcp(agent) {
      try {
        return ctx.tools.definitions(agent).some((tool) => tool.name === 'mcp__bma3__inbox_list')
      } catch {
        return false
      }
    },
  }, undefined, true)
  const backend = backendLink(inboxClient, ctx.logger('bma3-dsh'))
  ctx.provide('bma3Backend', backend)
  applyPreset(ctx, preset, true)
  applyRuntime(ctx, {
    ...runtime,
    source,
    ...(source === 'external' ? { inboxUrl: inbox.baseUrl, inboxId: inbox.inboxId, envFile: inbox.envFile } : {}),
  }, backend)
}
