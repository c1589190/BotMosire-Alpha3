import { apply as applyInbox } from './lib/inbox.js'
import { apply as applyPreset } from './lib/preset.js'
import { apply as applySelf } from './lib/self.js'

/** Cordis must provide these services before apply() touches ctx.tools/ctx.agents/ctx.agentPresets. */
export const inject = ['tools', 'agents', 'systemPrompt', 'agentPresets']

/** Cordis plugin name used by loader diagnostics. */
export const name = 'bma3-dsh'

/** Components the package can mount. */
export const MODULES = Object.freeze(['inbox', 'preset', 'self'])

/**
 * One package, three modules. `self` is mounted inside the BMA3 preset and owns the persona.
 * The old `persona` module was removed: it registered a second *complete* system-prompt
 * section (`bma3-persona`) and never released it, which made every reload end in
 * "multiple complete prompt sections are active". `inbox` failure must not prevent the role
 * prompt or tool preset from mounting; each module resolves only the services it needs.
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
  if (module === 'self') {
    applySelf(ctx, config)
    return
  }
  const logger = ctx?.logger?.('bma3-dsh')
  logger?.warn?.(`unknown module ${JSON.stringify(module)}; expected ${MODULES.join(', ')}`)
}
