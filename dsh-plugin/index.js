import { apply as applyInbox } from './lib/inbox.js'
import { apply as applyPersona } from './lib/persona.js'
import { apply as applyPreset } from './lib/preset.js'
import { apply as applySelf } from './lib/self.js'

/** Cordis must provide these services before apply() touches ctx.tools/ctx.agents. */
export const inject = ['tools', 'agents', 'systemPrompt']

/** Cordis plugin name used by loader diagnostics. */
export const name = 'bma3-dsh'

/** Components the package can mount. */
export const MODULES = Object.freeze(['inbox', 'persona', 'preset', 'self'])

/**
 * One package, four modules. `self` is mounted inside the BMA3 preset; `persona` is legacy. `inbox` failure must not prevent the role prompt
 * or tool preset from mounting; each module resolves only the services it needs.
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
  if (module === 'persona') {
    applyPersona(ctx, config)
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
