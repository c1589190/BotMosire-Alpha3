export const PRESET_ID = 'bma3'

/**
 * BMA3 preset: persona loader + the default tool face.
 *
 * The tool rows below are intentionally part of the preset composition, not
 * agentconfig.json. agentconfig lives in the workspace and the Agent can edit
 * it, so it must never be able to remove bash/pwsh or read_image. Scoped tools
 * from this preset stay visible even when the loader restricts global MCP tools.
 */
export function presetDefinition() {
  return {
    id: PRESET_ID,
    name: 'BMA3',
    description: 'BMA3 角色扮演预设：工作区人设 + MCP/QQ/终端/图片/子 Agent 默认工具面。',
    plugins: [
      {
        id: 'self',
        name: '@mosire/bma3-dsh',
        config: { module: 'self' },
      },
      {
        id: 'tool-bash',
        name: '@deepseek-ai/dsh-tool-bash',
        disabled: process.platform === 'win32',
      },
      {
        id: 'tool-pwsh',
        name: '@deepseek-ai/dsh-tool-pwsh',
        disabled: process.platform !== 'win32',
      },
      {
        id: 'tool-fs',
        name: '@deepseek-ai/dsh-tool-fs',
        config: {
          read: false,
          readImage: true,
          write: false,
          edit: false,
        },
      },
      {
        id: 'tool-subagent-control',
        name: '@deepseek-ai/dsh-tool-subagent-control',
      },
      {
        id: 'tool-subagent',
        name: '@deepseek-ai/dsh-tool-subagent',
        config: {
          provider: 'spawn',
          toolName: 'subagent',
          agentPreset: 'minimal',
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
