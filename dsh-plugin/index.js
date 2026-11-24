import { apply as applyInbox } from './lib/inbox.js'
import { apply as applyPreset } from './lib/preset.js'
import { applyRuntime } from './lib/runtime.js'
import { applyPlugin } from './lib/plugin.js'

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

/** Linux uses the single entry. Explicit module rows still serve the legacy bundle layout. */
export function apply(ctx, config = {}) {
  const module = config?.module
  if (module === undefined) return applyPlugin(ctx, config ?? {})
  if (module === 'inbox') {
    if (config.source === 'bundled' || config.source === 'local-jar') applyRuntime(ctx, config)
    else if (config.source === undefined || config.source === 'external') applyInbox(ctx, config)
    else ctx.logger('bma3-runtime').warn('BMA3 runtime: INVALID_SOURCE')
    return
  }
  if (module === 'preset') {
    applyPreset(ctx, config)
    return
  }
  const logger = ctx?.logger?.('bma3-dsh')
  logger?.warn?.('BMA3 DSH: INVALID_MODULE')
}
