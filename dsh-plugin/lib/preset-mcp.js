import { mountMcp } from './mcp.js'

export const name = 'bma3-preset-mcp'
export const inject = ['bma3Backend', 'tools']

export function apply(ctx, config = {}) {
  const backend = ctx.bma3Backend
  let current = []
  let queue = Promise.resolve()
  let disposed = false
  const observer = {}
  const detach = async () => {
    const clients = current
    current = []
    await Promise.all(clients.map((client) => client.dispose()))
  }
  const replace = (endpoint) => {
    const update = async () => {
      await detach()
      if (disposed || !endpoint?.mcpUrl || backend.current() !== endpoint) return
      let recreating = false
      const connect = (url, serverName) => {
        const primary = serverName === 'bma3'
        let wasConnected = false
        const client = mountMcp(ctx, url, endpoint.mcpToken, {
          serverName,
          failOnStartupError: false,
          monitor(state) {
            if (primary) backend.reportMcp(observer, endpoint, state)
            if (wasConnected && ['reconnecting', 'failed', 'waiting-credentials'].includes(state)
                && !recreating && !disposed && backend.current() === endpoint) {
              // The host may keep old schemas during its retry budget. Drop this registration
              // first so the disconnected backend contributes no tools while retrying.
              recreating = true
              void replace(endpoint).catch(() => ctx.logger.warn('BMA3 preset: MCP_RECONNECT_FAILED'))
            }
            wasConnected = state === 'connected'
          },
        })
        current.push(client)
        void client.ready.catch(() => {
          if (primary) backend.reportMcp(observer, endpoint, 'failed')
          else ctx.logger.warn('BMA3 preset: MCP_OPS_UNAVAILABLE')
        })
      }
      connect(endpoint.mcpUrl, 'bma3')
      if (config.enableOps === true && endpoint.mcpOpsUrl) {
        connect(endpoint.mcpOpsUrl, 'bma3_ops')
      }
    }
    queue = queue.then(update, update)
    return queue
  }
  const unsubscribe = backend.subscribe(replace)
  ctx.effect(() => async () => {
    disposed = true
    unsubscribe()
    backend.removeMcp(observer)
    await replace(null)
  })
}
