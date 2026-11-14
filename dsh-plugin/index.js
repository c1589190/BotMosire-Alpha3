import { apply as applyInbox } from './lib/inbox.js'
import { apply as applyPreset } from './lib/preset.js'

/** Cordis must provide these services before apply() touches ctx.tools/ctx.agents/ctx.systemPrompt. */
/**
 * Cordis gates a row on every name here before `apply()` runs.
 *
 * `agentPresets` must NOT be listed: the preset subtree is mounted *from inside*
 * `agentPresets.register()`, so a child row that injects the registry can never satisfy that
 * injection and stays pending forever — the whole subtree silently disappears (observed
 * 2026-11-14). Cross-service lookups use `ctx.get('agentPresets')` instead, which needs no inject.
 */
export const inject = ['tools', 'agents', 'systemPrompt']

/** Cordis plugin name used by loader diagnostics. */
export const name = 'bma3-dsh'

/** Components the package can mount. */
export const MODULES = Object.freeze(['inbox', 'preset'])

/**
 * One package, two modules, two responsibilities.
 *
 * - `inbox` injects the BMA3 inbox into DSH sessions (wake, revision push, `/inbox` command).
 * - `preset` registers the `bma3` agent preset, which injects the user's own system prompt.
 *
 * Everything else was removed on 2026-11-14 (D-066): the tool-face layering, the `bma3_*` self
 * tools, the MCP bridge and the host-tool repair. The jar's MCP server is reachable over plain
 * HTTP with a bearer token, and the Agent configures and connects it through DSH's own
 * `mcp_connector_*` tools — no plugin code in that path.
 *
 * @param {any} ctx Cordis context.
 * @param {{module?: string}} [config] Loader row config.
 */
export function apply(ctx, config = {}) {
  const module = config?.module ?? 'inbox'
  if (module === 'inbox') {
    applyInbox(ctx, config)
    return
  }
  if (module === 'preset') {
    applyPreset(ctx, config)
    return
  }
  const logger = ctx?.logger?.('bma3-dsh')
  logger?.warn?.(`unknown module ${JSON.stringify(module)}; expected ${MODULES.join(', ')}`)
}
