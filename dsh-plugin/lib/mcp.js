import { hostToolRowName } from './host-tools.js'

const CREDENTIAL = 'BMA3_MANAGED_MCP_TOKEN'

/** Mount the host's own client in the caller's scope. Credentials stay in a private realm. */
export function mountMcp(ctx, url, token, { serverName = 'bma3', monitor, failOnStartupError = true } = {}) {
  let resolveReady
  let rejectReady
  const ready = new Promise((resolve, reject) => {
    resolveReady = resolve
    rejectReady = reject
  })
  const fiber = ctx.isolate('credentials').plugin({
    name: 'bma3-mcp-credentials',
    apply(scope) {
      scope.provide('credentials', {
        async resolve(ref) {
          return ref === CREDENTIAL && token ? { value: token } : undefined
        },
      })
      scope.plugin({
        name: 'bma3-native-mcp-client',
        inject: ['credentials', 'tools'],
        async apply(clientContext) {
          try {
            const client = await import(hostToolRowName('@deepseek-ai/dsh-mcp-client'))
            const applied = client.apply(clientContext, client.Config({
              transport: 'streamable-http',
              serverName,
              url,
              ...(token ? { headers: { Authorization: 'Bearer ${credential:BMA3_MANAGED_MCP_TOKEN}' } } : {}),
              expandTemplates: true,
              startupTimeoutMs: 5000,
              failOnStartupError,
            }))
            const status = client.connectionStatusOf?.({ ctx: clientContext })
            if (status && monitor) {
              monitor(status.current().state)
              clientContext.effect(() => status.onChange((value) => monitor(value.state)))
            }
            await applied
            resolveReady()
          } catch {
            rejectReady(new Error('MCP_UNAVAILABLE'))
          }
        },
      })
    },
  })
  return { ready, dispose: () => fiber.dispose() }
}
