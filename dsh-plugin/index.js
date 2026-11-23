import { apply as applyInbox } from './lib/inbox.js'
import { apply as applyPreset } from './lib/preset.js'
import { applyRuntime } from './lib/runtime.js'

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
 * `lib/media.js` (`bma3_ocr` / `bma3_asr`) is mounted as a *preset row*, not as a profile row, so the
 * paid DeepSeek tools only exist inside BMA3 sessions.
 *
 * The Inbox row can source its backend from a managed app-image or local JAR (D-085).
 * With an external service it uses the existing Inbox configuration and native connector.
 *
 * @param {any} ctx Cordis context.
 * @param {{module?: string}} [config] Loader row config.
 */
export function apply(ctx, config = {}) {
  const module = config?.module ?? 'inbox'
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
  logger?.warn?.(`unknown module ${JSON.stringify(module)}; expected ${MODULES.join(', ')}`)
}
