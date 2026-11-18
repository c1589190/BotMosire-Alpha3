/**
 * BMA3 agent preset.
 *
 * Two things, no policy layer:
 *  - `persona`  injects the user's own system prompt (shadows `deployment:persona-prefix`).
 *  - tool rows below decide the face by *mounting*: what the preset mounts is what the session
 *    sees. There is no allow/deny list and no `tools.restrict()` — the 0.0.5 group whitelist
 *    (`defaultTools` + `resolveToolGroups`) was deleted because it depended on other plugins
 *    having registered names, and because its allow list was computed from the global view and
 *    therefore masked the preset's own rows.
 *  - `sessionRows` mounts the compaction backend and `/compact` itself, because the web host
 *    leaves both to presets (see the comment on `sessionRows`).
 *
 * The Agent extends its own face at runtime:
 *  - MCP servers (the bma3 jar among them) → `mcp_connector_configure`, tools arrive as
 *    `mcp__<server>__*` in the global layer;
 *  - local plugin rows → `tool-plugin-manager`;
 *  - per-session narrowing → `mcp_connector_session_tools`.
 */
import { hostToolRowName, invalidateHostToolCache } from './host-tools.js'

export const PRESET_ID = 'bma3'

/** Rows copied from 0.0.5's face: terminal, image-only fs, subagents, plus the plugin door. */
function toolRows(ctx) {
  // Re-resolve on every (re)registration: a cached miss from a mid-reinstall lookup would leave
  // rows as bare specifiers, which cannot resolve from the profile tree.
  invalidateHostToolCache()
  const profileContext = ctx?.get?.('profileContext') !== undefined
  return [
    {
      id: 'tool-bash',
      name: hostToolRowName('@deepseek-ai/dsh-tool-bash'),
      disabled: process.platform === 'win32',
    },
    {
      id: 'tool-pwsh',
      name: hostToolRowName('@deepseek-ai/dsh-tool-pwsh'),
      disabled: process.platform !== 'win32',
    },
    {
      id: 'tool-fs',
      name: hostToolRowName('@deepseek-ai/dsh-tool-fs'),
      // Only the image reader: text read/write/edit go through bash, which keeps the face small
      // (0.0.5 behaviour). Widen here if a session should get first-class file tools.
      config: {
        read: false,
        readImage: true,
        write: false,
        edit: false,
      },
    },
    {
      id: 'tool-subagent-control',
      name: hostToolRowName('@deepseek-ai/dsh-tool-subagent-control'),
    },
    {
      id: 'tool-subagent',
      name: hostToolRowName('@deepseek-ai/dsh-tool-subagent'),
      config: {
        provider: 'spawn',
        toolName: 'subagent',
        agentPreset: 'minimal',
      },
    },
    {
      // DeepSeek 官方 OCR / ASR（D-068）。挂在这里而不是 profile 层：付费接口只在 BMA3 会话里出现。
      id: 'media',
      name: new URL('./media.js', import.meta.url).href,
    },
    {
      // The door for "Agent 想挂啥挂啥": install/enable/disable plugin rows at runtime.
      id: 'tool-plugin-manager',
      name: hostToolRowName('@deepseek-ai/dsh-plugin-manager/tools'),
      disabled: !profileContext,
    },
  ]
}

/**
 * Session-plane rows a preset must own because the host deliberately does not.
 *
 * `@deepseek-ai/dsh-web-app/cordis.patch.yml:566-581` disables `compaction-basic` and
 * `command-compact` at the *host* plane with the note "Presets own the human command and
 * model-facing tool". A preset that mounts neither therefore loses two things at once: the
 * `/compact` (and `/压缩`) command, and — worse — the compaction backend itself, so pressure
 * never produces a summary and the session can only overflow.
 *
 * The ids match the host rows they replace (same shape as `tool-subagent` below): preset children
 * live in their own loader scope, so reusing the id does not collide with the host-plane row.
 */
function sessionRows() {
  return [
    {
      // The backend: reads the host-plane token meter, writes `compaction/summary` + surface replace.
      id: 'compaction-basic',
      name: hostToolRowName('@deepseek-ai/dsh-compaction-basic'),
    },
    {
      id: 'command-compact',
      name: hostToolRowName('@deepseek-ai/dsh-command-compact'),
    },
  ]
}

/**
 * Build the preset definition.
 * @param {any} ctx declaring context; used for the `profileContext` seam only.
 * @param {{promptFile?: string, complete?: boolean}} [config] loader row config.
 * @returns {{id: string, name: string, description: string, plugins: any[]}} the definition.
 */
export function presetDefinition(ctx, config = {}) {
  return {
    id: PRESET_ID,
    name: 'BMA3',
    description: 'BMA3 角色扮演预设：注入自定义系统提示词 + 终端/图片/子 Agent 薄面；其余工具由 Agent 自己挂。',
    plugins: [
      {
        id: 'persona',
        // A separate entry file keeps this row's identity distinct from the bundle the profile
        // already mounts; a row named after the package is downgraded to disabled by the profile
        // compatibility preflight, silently.
        name: new URL('./persona.js', import.meta.url).href,
        config: {
          ...(config.promptFile ? { promptFile: config.promptFile } : {}),
          ...(config.complete === undefined ? {} : { complete: config.complete }),
        },
      },
      ...toolRows(ctx),
      ...sessionRows(),
    ],
  }
}

/**
 * Register the preset once `agentPresets` exists.
 *
 * `agentPresets` is deliberately **not** in the package-level `inject`: that gate would also apply
 * to the rows *inside* this preset, and a preset child can never satisfy "inject the registry that
 * is currently mounting it" — the whole subtree then stays pending forever (observed 2026-11-14).
 * The registry is treated as an optional dependency instead: wait for it, then register.
 *
 * @param {any} ctx loading context.
 * @param {{promptFile?: string, complete?: boolean}} [config] loader row config.
 * @returns {any} the definition that was registered, for tests and diagnostics.
 */
export function apply(ctx, config = {}) {
  const definition = presetDefinition(ctx, config)
  const logger = typeof ctx?.logger === 'function' ? ctx.logger('bma3-dsh') : undefined

  const register = (host) => {
    let presets
    try {
      presets = typeof host?.get === 'function' ? host.get('agentPresets') : host?.agentPresets
    } catch {
      presets = undefined
    }
    if (!presets?.register) {
      logger?.warn?.('bma3 preset: agentPresets registry unavailable, preset not registered')
      return
    }
    const dispose = presets.register(definition)
    logger?.info?.(`bma3 preset registered: id=${definition.id} rows=${definition.plugins.length}`)
    if (typeof dispose === 'function') {
      host.effect?.(() => dispose)
    } else if (dispose?.then) {
      host.effect?.(() => () => dispose.then((fn) => (typeof fn === 'function' ? fn() : undefined)))
    }
  }

  if (typeof ctx.inject === 'function') ctx.inject(['agentPresets'], (child) => register(child ?? ctx))
  else register(ctx)
  return definition
}
