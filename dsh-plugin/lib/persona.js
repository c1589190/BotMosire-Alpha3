/**
 * BMA3 persona row — the preset's only job.
 *
 * Registers one **scoped** system-prompt section that shadows the deployment persona prefix, so a
 * BMA3 session's system prompt is the user's own text instead of the default harness preamble.
 * Registration happens once in the preset's standing scope; agents that select the preset are
 * parented to that scope, so no per-agent hook is involved.
 *
 * This row owns no tools, no MCP client and no tool-face policy: those couplings were removed on
 * 2026-11-14 (D-066). The jar's MCP server is configured and connected by the Agent through DSH's
 * own `mcp_connector_*` tools.
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

export const name = 'bma3-persona'

/** Only the prompt registry is needed; `agentPresets` is resolved through `ctx.get` in preset.js. */
export const inject = ['systemPrompt']

/**
 * Section name that shadows the deployment-wide persona prefix.
 *
 * A scoped section shadows the global section of the same name, which is what keeps the prompt
 * assembly at exactly one complete contributor.
 */
export const PERSONA_SECTION = 'deployment:persona-prefix'

/**
 * Default prompt file: `$DSH_HOME/bma3/systemprompt.md` (usually `~/.dsh/bma3/systemprompt.md`).
 * @param {Record<string, string | undefined>} [env] environment to read.
 * @returns {string} absolute path.
 */
export function defaultPromptFile(env = process.env) {
  const home = env?.DSH_HOME?.trim() || path.join(os.homedir(), '.dsh')
  return path.join(home, 'bma3', 'systemprompt.md')
}

/**
 * Resolve the prompt file from row config.
 * @param {{promptFile?: string}} [config] row config; `~/…` and relative paths are expanded.
 * @param {Record<string, string | undefined>} [env] environment for the default location.
 * @returns {string} absolute path.
 */
export function resolvePromptFile(config = {}, env = process.env) {
  const configured = typeof config?.promptFile === 'string' ? config.promptFile.trim() : ''
  if (!configured) return defaultPromptFile(env)
  if (configured === '~') return os.homedir()
  if (configured.startsWith('~/')) return path.join(os.homedir(), configured.slice(2))
  return path.resolve(configured)
}

/**
 * Read the prompt text.
 * @param {string} file absolute path.
 * @returns {string} file content, or an empty string when the file is missing or blank.
 */
export function readPrompt(file) {
  try {
    const text = fs.readFileSync(file, 'utf8')
    return text.trim() ? text : ''
  } catch {
    return ''
  }
}

function readWorkspacePrompt(context) {
  const root = context?.agent?.session?.header?.cwd
  if (!root || !path.isAbsolute(root)) return ''
  try {
    const directory = fs.realpathSync(root)
    const file = fs.realpathSync(path.join(directory, 'systemprompt.md'))
    const relative = path.relative(directory, file)
    if (relative === '..' || relative.startsWith('..' + path.sep) || path.isAbsolute(relative)) return ''
    return fs.statSync(file).isFile() ? readPrompt(file) : ''
  } catch {
    return ''
  }
}

/**
 * Register the persona section for this preset revision.
 * @param {any} ctx the preset row's context.
 * @param {{promptFile?: string, complete?: boolean, workspacePrompt?: boolean}} [config] row config.
 * @returns {(() => void) | undefined} the section disposer, or undefined when nothing was mounted.
 */
export function apply(ctx, config = {}) {
  const systemPrompt = ctx?.systemPrompt
  if (!systemPrompt?.section) return undefined

  const file = resolvePromptFile(config)
  const explicitFile = typeof config?.promptFile === 'string' && config.promptFile.trim()
  const workspace = config?.workspacePrompt === true && !explicitFile
  const complete = config?.complete !== false
  let text = readPrompt(file)
  const currentPrompt = (context) => {
    const workspaceText = workspace ? readWorkspacePrompt(context) : ''
    if (workspaceText) return workspaceText
    text = readPrompt(file) || text
    return text
  }

  if (!text) {
    if (!workspace) return undefined
    // Each assembly can select a different workspace. Keep the host sections when no file applies.
    return ctx.on('system-prompt/assemble', async (_assembly, context, next) => {
      const assembly = await next()
      const text = currentPrompt(context)
      if (!text) return assembly

      const persona = { name: PERSONA_SECTION, text, interpolate: false }
      if (complete) return { ...assembly, sections: [persona] }

      const sections = [...assembly.sections]
      const prefix = sections.findIndex((section) => section.name === PERSONA_SECTION)
      if (prefix >= 0) sections.splice(prefix, 1, persona)
      else sections.splice(sections.findIndex((section) => section.name === 'harness:identity') + 1, 0, persona)
      return { ...assembly, sections }
    }, { prepend: true })
  }

  const section = {
    name: PERSONA_SECTION,
    order: systemPrompt.getSectionOrder ? systemPrompt.getSectionOrder('DEPLOYMENT_PERSONA_PREFIX') : 0,
    text: currentPrompt,
    complete,
    interpolate: false,
  }
  const dispose = systemPrompt.section(section)
  if (typeof ctx.effect === 'function' && typeof dispose === 'function') {
    ctx.effect(() => dispose, 'bma3.persona')
  }
  return dispose
}
