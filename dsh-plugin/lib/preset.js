import { promptFor } from './persona.js'

export const PRESET_ID = 'bma3'

export function presetDefinition(config = {}) {
  return {
    id: PRESET_ID,
    name: 'BMA3',
    description: 'BMA3 角色扮演预设：角色提示词 + 最小工具组。',
    plugins: [
      {
        id: 'persona',
        name: '@deepseek-ai/dsh-persona',
        config: {
          prefix: promptFor(config),
          complete: true,
          includeRuntimeContext: false,
        },
      },
    ],
  }
}

export function apply(ctx, config = {}) {
  const definition = presetDefinition(config)
  const presets = ctx?.agentPresets ?? ctx?.get?.('agentPresets')
  const dispose = presets?.register?.(definition)
  if (typeof dispose === 'function') {
    ctx?.effect?.(() => dispose)
  } else if (dispose?.then) {
    ctx?.effect?.(() => () => dispose.then((fn) => (typeof fn === 'function' ? fn() : undefined)))
  }
  return definition
}
