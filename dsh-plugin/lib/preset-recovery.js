import { hostToolEntry } from './host-tools.js'
import { pathToFileURL } from 'node:url'

const CORE_TOOLS = new Map([
  ['tool-bash', 'bash'],
  ['tool-pwsh', 'pwsh'],
  ['tool-fs', 'read_image'],
])

/** Restore missing native tools in retired BMA3 revisions without rebinding their sessions. */
export function recoverPresetTools(ctx, presets, definition, registered, logger) {
  const agents = ctx.get?.('agents')
  const tools = ctx.get?.('tools')
  if (!agents?.list || !tools?.view || !presets.acquireScope || !ctx.on || !ctx.plugin) return
  const repairs = new Map()
  let disposed = false
  let queue = Promise.resolve()

  async function recover() {
    if (disposed) return
    const scopeEntry = hostToolEntry('@deepseek-ai/dsh-scope')
    if (!scopeEntry) return
    const { createScope, scopeOf, scopeParentOf } = await import(pathToFileURL(scopeEntry).href)
    const active = new Set(agents.list()
      .filter((agent) => presets.composedPreset(agent.ctx) === definition.id)
      .map((agent) => scopeParentOf(scopeOf(agent.ctx)))
      .filter(Boolean))
    for (const [key, repair] of repairs) {
      if (active.has(key)) continue
      repairs.delete(key)
      await repair.scope.dispose()
    }
    if (disposed || active.size === 0) return

    // A broken new definition must not prevent repair of a separately retained revision.
    const latest = await presets.acquireScope(definition.id).catch(() => undefined)
    try {
      for (const key of active) {
        if (disposed || key === latest?.key) continue
        for (const row of definition.plugins) {
          const tool = CORE_TOOLS.get(row.id)
          if (!tool || row.disabled || tools.view(key).knownNames.has(tool)) continue
          if (repairs.get(key)?.tools.has(tool)) continue
          try {
            const entry = hostToolEntry('@deepseek-ai/dsh-' + row.id)
            if (!entry) continue
            const native = await import(pathToFileURL(entry).href)
            if (disposed || tools.view(key).knownNames.has(tool)) continue
            if (!repairs.has(key)) repairs.set(key, { scope: createScope(ctx, key), tools: new Set() })
            const repair = repairs.get(key)
            const fiber = repair.scope.ctx.plugin({
              name: 'bma3-preset-recovery-' + row.id,
              inject: native.inject,
              apply: native.apply,
            }, native.Config ? native.Config(row.config ?? {}) : row.config)
            repair.tools.add(tool)
            void Promise.resolve(fiber).catch(() => {
              repair.tools.delete(tool)
              logger?.warn?.('BMA3 retained preset tool unavailable: ' + tool)
            })
          } catch {
            logger?.warn?.('BMA3 retained preset tool unavailable: ' + tool)
          }
        }
      }
    } finally {
      await latest?.[Symbol.asyncDispose]()
    }
  }

  const schedule = () => {
    queue = queue.then(recover).catch(() => {
      if (!disposed) logger?.warn?.('BMA3 retained preset recovery unavailable')
    })
  }
  ctx.on('plugin-manager/changed', schedule)
  ctx.on('agent/disposed', schedule)
  ctx.effect(() => async () => {
    disposed = true
    await queue
    await Promise.allSettled([...repairs.values()].map((repair) => repair.scope.dispose()))
    repairs.clear()
  })
  void Promise.resolve(registered).then(schedule, () => {})
}
