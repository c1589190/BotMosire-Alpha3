import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { pathToFileURL } from 'node:url'

import { describeHostTools, hostToolEntry, invalidateHostToolCache } from './host-tools.js'

export const DEFAULT_PROMPT = '你是 BMA3 的角色。'
export const DEFAULT_TOOL_GROUPS = Object.freeze([
  'mcp_admin',
  'bma3_core',
  'terminal',
  'image',
  'subagent',
])
export const LOCKED_TOOL_BASENAMES = Object.freeze(['bash', 'pwsh', 'read_image'])
export const PRESET_PROVIDED_GROUPS = Object.freeze(['terminal', 'image', 'subagent'])

const LEGACY_PERSONA_SECTION = 'bma3-persona'
const PERSONA_SECTION = 'deployment:persona-prefix'
const CONFIG_FILE = 'agentconfig.json'
const PROMPT_FILE = 'systemprompt.md'
const DEFAULT_MAX_BYTES = 32768
const CONFIG_MAX_BYTES = 65536
const BASIC_BMA3_BASENAMES = new Set([
  'inbox_list',
  'inbox_items',
  'inbox_get',
  'inbox_ack',
  'inbox_status',
  'inbox_wait',
  'qq_thread_list',
  'qq_conversation_history',
  'qq_message_get',
  'qq_media_view',
  'get_config',
  'set_config',
  'qq_conversation_read',
  'qq_conversation_send',
  'qq_reply',
  'qq_private_send',
  'qq_conversation_ack',
  'qq_account_list',
  'qq_account_status',
  'qq_group_list',
  'qq_friend_list',
])
/** Registered on the agent's own layer: exempt from restrictions, and NOT restrictable. */
const SCOPED_BMA3_TOOLS = new Set([
  'bma3_self_status',
  'bma3_self_reload',
  'bma3_self_repair',
])

/**
 * Host tool packages that the preset is supposed to mount. The repair path uses the same absolute
 * `file:` entries as the preset rows, so a plugin reinstall that leaves the rows unmounted can be
 * fixed without restarting DSH.
 */
export const HOST_TOOL_REPAIRS = Object.freeze({
  bash: { spec: '@deepseek-ai/dsh-tool-bash', config: {} },
  pwsh: { spec: '@deepseek-ai/dsh-tool-pwsh', config: {} },
  read_image: {
    spec: '@deepseek-ai/dsh-tool-fs',
    config: { read: false, readImage: true, write: false, edit: false },
  },
})
const SUBAGENT_BASENAMES = new Set([
  'subagent',
  'subagent_fork',
  'send_message',
  'interrupt_agent',
  'list_agents',
  'list_subagent_models',
])

/** The DSH-facing name suffix, so both bma3 and mcp__bma3__* match. */
function baseName(name) {
  const text = String(name ?? '')
  const marker = text.lastIndexOf('__')
  return marker < 0 ? text : text.slice(marker + 2)
}

function within(root, target) {
  const relative = path.relative(root, target)
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))
}

function hash(text) {
  return crypto.createHash('sha256').update(String(text)).digest('hex')
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value))
}

export function normalizeConfig(raw) {
  if (raw !== undefined && (raw === null || typeof raw !== 'object' || Array.isArray(raw))) {
    throw new Error('agentconfig 必须是 JSON 对象')
  }
  if (raw?.schemaVersion !== undefined && raw.schemaVersion !== 1) {
    throw new Error('agentconfig.schemaVersion 只支持 1')
  }
  const prompt = raw?.prompt
  if (prompt !== undefined && (prompt === null || typeof prompt !== 'object' || Array.isArray(prompt))) {
    throw new Error('agentconfig.prompt 必须是对象')
  }
  if (raw?.defaultTools !== undefined && !Array.isArray(raw.defaultTools)) {
    throw new Error('agentconfig.defaultTools 必须是字符串数组')
  }
  if (Array.isArray(raw?.defaultTools) && raw.defaultTools.some((item) => typeof item !== 'string')) {
    throw new Error('agentconfig.defaultTools 必须是字符串数组')
  }
  const maxBytes = prompt?.maxBytes === undefined
    ? DEFAULT_MAX_BYTES
    : Number(prompt.maxBytes)
  if (!Number.isFinite(maxBytes)) throw new Error('agentconfig.prompt.maxBytes 必须是数字')
  return {
    schemaVersion: 1,
    prompt: {
      // 默认 true：systemprompt.md 就是完整的 system prompt，不拼任何默认内容。
      // 只有显式写 complete:false 才退化成「插一段、保留 DSH 默认提示词」。
      complete: prompt?.complete !== false,
      maxBytes: clamp(Math.round(maxBytes), 4096, CONFIG_MAX_BYTES),
    },
    defaultTools: raw?.defaultTools === undefined
      ? [...DEFAULT_TOOL_GROUPS]
      : raw.defaultTools.map((item) => item.trim()).filter(Boolean),
  }
}

export function resolveToolGroups(groups, globalNames) {
  const names = [...new Set((globalNames ?? []).map(String).filter(Boolean))]
  const allow = new Set()
  const missing = new Set()
  const matchByBase = (predicate) => {
    let matched = false
    for (const name of names) {
      if (predicate(baseName(name))) {
        allow.add(name)
        matched = true
      }
    }
    return matched
  }
  for (const group of groups ?? []) {
    if (group === 'mcp_admin') {
      if (!matchByBase((name) => name.startsWith('mcp_connector_') || name === 'mcp_probe')) missing.add(group)
    } else if (group === 'bma3_core') {
      // `bma3_` covers tools this plugin registers globally (bma3_inbox …). Without it such a
      // tool is silently filtered out of every BMA3 session.
      if (!matchByBase((name) => BASIC_BMA3_BASENAMES.has(name)
          || name.startsWith('qq_') || name.startsWith('bma3_'))) missing.add(group)
    } else if (group === 'terminal') {
      if (!matchByBase((name) => name === 'bash' || name === 'pwsh')) missing.add(group)
    } else if (group === 'image') {
      if (!matchByBase((name) => name === 'read_image')) missing.add(group)
    } else if (group === 'subagent') {
      if (!matchByBase((name) => SUBAGENT_BASENAMES.has(name) || name.startsWith('subagent'))) missing.add(group)
    } else if (names.includes(group)) {
      allow.add(group)
    } else {
      missing.add(group)
    }
  }
  // Locked at the plugin/preset layer, never by agentconfig.
  for (const name of names) {
    if (LOCKED_TOOL_BASENAMES.includes(baseName(name))) allow.add(name)
  }
  // `tools.restrict()` only accepts restrictable (global/inherited) names; naming a scope-local
  // tool makes the whole restrict() call throw. Our own scoped tools are exempt anyway, so drop
  // them instead of poisoning the allow list.
  for (const name of SCOPED_BMA3_TOOLS) allow.delete(name)
  return { allow: [...allow].sort(), missing: [...missing].sort() }
}

function globalToolNames(agentCtx) {
  try {
    const definitions = agentCtx?.tools?.definitions?.()
    return Array.isArray(definitions) ? definitions.map((item) => item.name).filter(Boolean) : []
  } catch {
    return []
  }
}

/** Names actually visible to this agent: global + this agent's scoped registrations. */
function visibleToolNames(agentCtx, agent) {
  const names = new Set()
  try {
    const definitions = agent && agentCtx?.tools?.definitions
      ? agentCtx.tools.definitions(agent)
      : agentCtx?.tools?.definitions?.()
    if (Array.isArray(definitions)) {
      for (const definition of definitions) {
        if (definition?.name) names.add(definition.name)
      }
    }
  } catch {
    // Fall back to the global view below.
  }
  if (names.size === 0) {
    for (const name of globalToolNames(agentCtx)) names.add(name)
  }
  // `definitions(scope)` may not include a tool whose registration is still settling; ask for the
  // exact locked names as a second opinion.
  for (const basename of LOCKED_TOOL_BASENAMES) {
    try {
      if (agentCtx?.tools?.get?.(basename, agent)) names.add(basename)
    } catch {
      // ignore
    }
  }
  return [...names]
}

function requiredLockedTools() {
  return process.platform === 'win32' ? ['pwsh', 'read_image'] : ['bash', 'read_image']
}

function lockedToolStatus(agentCtx, agent) {
  const visible = visibleToolNames(agentCtx, agent)
  const present = visible.filter((name) => LOCKED_TOOL_BASENAMES.includes(baseName(name)))
  const missing = requiredLockedTools().filter((basename) =>
    !visible.some((name) => baseName(name) === basename))
  return { visible, present, missing }
}

export class SelfState {
  constructor(cwd, pluginConfig = {}) {
    this.cwd = path.resolve(String(cwd))
    this.promptPath = path.join(this.cwd, PROMPT_FILE)
    this.configPath = path.join(this.cwd, CONFIG_FILE)
    this.pluginConfig = pluginConfig ?? {}
    this.config = normalizeConfig({})
    this.prompt = null
    this.promptStat = null
    this.errors = { config: null, prompt: null, tools: null }
    this.restriction = null
    this.lastAllowKey = null
    this.lastAllow = []
    this.missing = []
    this.applying = false
    this.pending = false
    this.onReload = null
    this.agent = null
    this.hostToolEntry = pluginConfig.hostToolEntry ?? hostToolEntry
    this.hostToolMounts = new Map()
    this.repairTimer = null
  }

  loadInitial() {
    this.errors = { config: null, prompt: null, tools: null }
    try {
      this.config = this.readConfig()
    } catch (error) {
      this.errors.config = `配置读取失败，使用默认工具组：${error.message}`
    }
    this.promptText(true)
    return this
  }

  readConfig() {
    let realFile
    try {
      const realRoot = fs.realpathSync(this.cwd)
      realFile = fs.realpathSync(this.configPath)
      if (!within(realRoot, realFile)) throw new Error('配置文件不在当前工作区内')
      const stat = fs.statSync(realFile)
      if (!stat.isFile()) throw new Error('配置文件不是普通文件')
      if (stat.size > CONFIG_MAX_BYTES) throw new Error(`配置超过 ${CONFIG_MAX_BYTES} 字节`)
    } catch (error) {
      if (error.code === 'ENOENT') return normalizeConfig({})
      throw error
    }
    const text = fs.readFileSync(realFile, 'utf8')
    return normalizeConfig(JSON.parse(text))
  }

  promptText(force = false) {
    try {
      const realRoot = fs.realpathSync(this.cwd)
      const realFile = fs.realpathSync(this.promptPath)
      if (!within(realRoot, realFile)) throw new Error('文件不在当前工作区内')
      const stat = fs.statSync(realFile)
      if (!stat.isFile()) throw new Error('不是普通文件')
      if (stat.size > this.config.prompt.maxBytes) {
        throw new Error(`文件超过 ${this.config.prompt.maxBytes} 字节`)
      }
      if (!force && this.promptStat
          && this.promptStat.mtimeMs === stat.mtimeMs
          && this.promptStat.size === stat.size) {
        return this.prompt
      }
      const text = fs.readFileSync(realFile, 'utf8')
      if (text.trim() === '') throw new Error('文件为空')
      this.prompt = text
      this.promptStat = { mtimeMs: stat.mtimeMs, size: stat.size }
      this.errors.prompt = null
    } catch (error) {
      if (error.code !== 'ENOENT') this.errors.prompt = `系统提示词不可用：${error.message}`
      else this.errors.prompt = '缺少 systemprompt.md'
    }
    // 没有可用文件就返回 null：宁可什么都不注入，也不塞一段内置人设。
    return this.prompt || null
  }

  status(agentCtx) {
    const names = globalToolNames(agentCtx)
    const resolved = resolveToolGroups(this.config.defaultTools, names)
    const visible = lockedToolStatus(agentCtx, this.agent)
    const presetOnly = new Set(PRESET_PROVIDED_GROUPS)
    for (const name of LOCKED_TOOL_BASENAMES) presetOnly.add(name)
    return {
      ok: Object.values(this.errors).every((item) => item == null),
      cwd: this.cwd,
      promptPath: this.promptPath,
      promptHash: hash(this.prompt || ''),
      promptComplete: this.config.prompt.complete,
      promptSection: PERSONA_SECTION,
      presetTools: describeHostTools(),
      legacyPersonaShadowed: true,
      configPath: this.configPath,
      configHash: hash(JSON.stringify(this.config)),
      defaultTools: this.config.defaultTools,
      allow: resolved.allow,
      missingGroups: resolved.missing.filter((name) => !presetOnly.has(name)),
      presetProvidedGroups: [...PRESET_PROVIDED_GROUPS],
      presetLockedTools: [...LOCKED_TOOL_BASENAMES],
      globalToolCount: names.length,
      visibleToolCount: visible.visible.length,
      requiredLockedTools: requiredLockedTools(),
      lockedTools: visible.present,
      missingLockedTools: visible.missing,
      error: Object.values(this.errors).filter(Boolean).join(' | ') || null,
    }
  }

  /**
   * DSH plugin reinstall can leave the preset child rows (tool-bash/tool-fs) unmounted while the
   * `self` row survives. Mount the missing host tool plugins into this agent scope directly.
   * The entries are the same absolute host paths the preset uses; Cordis owns their disposal.
   */
  async repairHostTools(agentCtx, { force = false } = {}) {
    const before = lockedToolStatus(agentCtx, this.agent)
    const mounted = []
    const errors = []
    for (const basename of before.missing) {
      if (basename === 'bash' && process.platform === 'win32') continue
      if (basename === 'pwsh' && process.platform !== 'win32') continue
      const repair = HOST_TOOL_REPAIRS[basename]
      if (!repair) continue
      const previous = this.hostToolMounts.get(repair.spec)
      if (previous && !force) continue
      if (previous && force) {
        try {
          await previous?.dispose?.()
        } catch {
          // The old fiber may already be gone with the agent scope.
        }
        this.hostToolMounts.delete(repair.spec)
      }
      let entry
      try {
        invalidateHostToolCache()
        entry = this.hostToolEntry(repair.spec)
      } catch (error) {
        errors.push({ tool: basename, error: `resolve: ${error.message}` })
        continue
      }
      if (!entry) {
        errors.push({ tool: basename, error: 'HOST_TOOL_NOT_RESOLVED' })
        continue
      }
      try {
        const module = await import(pathToFileURL(entry).href)
        const config = typeof module.Config === 'function'
          ? module.Config(repair.config)
          : repair.config
        let handle
        if (typeof agentCtx.plugin === 'function') {
          handle = agentCtx.plugin({
            name: module.name ?? repair.spec,
            inject: module.inject ?? [],
            apply: module.apply,
          }, config)
          const outcome = await Promise.race([
            Promise.resolve(handle).then(() => 'ready'),
            new Promise((resolve) => {
              const timer = setTimeout(() => resolve('pending'), 3000)
              timer.unref?.()
            }),
          ])
          if (outcome === 'pending') {
            errors.push({ tool: basename, error: 'MOUNT_PENDING_INJECT' })
            this.hostToolMounts.set(repair.spec, handle)
            continue
          }
        } else if (typeof module.apply === 'function') {
          handle = module.apply(agentCtx, config)
        } else {
          throw new Error('host tool module has no apply()')
        }
        this.hostToolMounts.set(repair.spec, handle)
        mounted.push(basename)
      } catch (error) {
        errors.push({ tool: basename, error: error?.message ?? String(error) })
      }
    }
    // Registrations settle on the next microtask/tick; inspect after that.
    await new Promise((resolve) => setTimeout(resolve, 0))
    const after = lockedToolStatus(agentCtx, this.agent)
    if (mounted.length > 0) {
      this.errors.tools = null
    }
    return { changed: mounted.length > 0, before: before.missing, after: after.missing,
      mounted, errors }
  }

  scheduleRepair(agentCtx, delay = 400) {
    if (this.repairTimer) clearTimeout(this.repairTimer)
    this.repairTimer = setTimeout(() => {
      this.repairTimer = null
      this.repairHostTools(agentCtx).catch(() => {})
    }, delay)
    this.repairTimer.unref?.()
  }

  applyToolPolicy(agentCtx, { force = false } = {}) {
    if (!agentCtx?.tools?.restrict || !agentCtx?.tools?.definitions) return this.status(agentCtx)
    if (this.applying) {
      this.pending = true
      return this.status(agentCtx)
    }
    this.applying = true
    try {
      const names = globalToolNames(agentCtx)
      const resolved = resolveToolGroups(this.config.defaultTools, names)
      const key = resolved.allow.join('\n')
      if (!force && this.restriction && key === this.lastAllowKey) {
        this.missing = resolved.missing
        return this.status(agentCtx)
      }
      const next = agentCtx.tools.restrict({ allow: resolved.allow })
      const previous = this.restriction
      this.restriction = next
      this.lastAllowKey = key
      this.lastAllow = resolved.allow
      this.missing = resolved.missing
      this.errors.tools = null
      if (previous) {
        try {
          previous()
        } catch {
          // The exact disposer may race agent disposal; the mask is gone with the scope.
        }
      }
    } catch (error) {
      this.errors.tools = `工具组应用失败：${error.message}`
    } finally {
      this.applying = false
      if (this.pending) {
        this.pending = false
        queueMicrotask(() => this.applyToolPolicy(agentCtx))
      }
    }
    return this.status(agentCtx)
  }

  async reload(agentCtx) {
    this.errors = { config: null, prompt: null, tools: null }
    try {
      this.config = this.readConfig()
    } catch (error) {
      this.errors.config = `重载失败：${error.message}`
      return this.status(agentCtx)
    }
    this.promptText(true)
    this.applyToolPolicy(agentCtx, { force: true })
    const repair = await this.repairHostTools(agentCtx)
    this.onReload?.()
    return { ...this.status(agentCtx), repair }
  }
}

const OUTPUT = {
  schema: {
    type: 'object',
    properties: {
      text: { type: 'string', description: 'Human-readable result.' },
    },
    required: ['text'],
    additionalProperties: false,
  },
  render(_args, value) {
    return [{ type: 'text', text: value.text }]
  },
}

function registerSelfTools(agentCtx, state) {
  const tools = agentCtx?.tools
  if (!tools?.register) return
  const definitions = [
    {
      name: 'bma3_self_status',
      description: 'Read the bma3 persona/config state and the currently resolved default tool groups. No secrets.',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: OUTPUT,
      execute: async () => ({ text: JSON.stringify(state.status(agentCtx), null, 2) }),
    },
    {
      name: 'bma3_self_reload',
      description: 'Reload systemprompt.md and agentconfig.json, then reapply the default tool groups. The bash/pwsh and read_image tools stay locked available.',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: OUTPUT,
      execute: async () => ({ text: JSON.stringify(await state.reload(agentCtx), null, 2) }),
    },
    {
      name: 'bma3_self_repair',
      description: 'Force-mount the preset host tools (bash/pwsh/read_image) into this session when a DSH plugin reinstall left lockedTools empty. Uses the same absolute host entries as the preset; no restart needed.',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: OUTPUT,
      execute: async () => ({
        text: JSON.stringify(await state.repairHostTools(agentCtx, { force: true }), null, 2),
      }),
    },
  ]
  for (const definition of definitions) {
    try {
      agentCtx.effect(() => tools.register(definition), `bma3-self.${definition.name}`)
    } catch {
      // A duplicate registration means another loader row already mounted this agent.
    }
  }
}

export function apply(ctx, config = {}) {
  if (!ctx?.on) return
  const repairLiveAgents = async () => {
    const agents = typeof ctx.get === 'function' ? ctx.get('agents') : ctx.agents
    const list = Array.isArray(agents?.list?.()) ? agents.list() : []
    for (const agent of list) {
      if (agent?.session?.header?.agentPreset !== 'bma3') continue
      const cwd = agent?.session?.header?.cwd
      if (!cwd || !agent?.ctx) continue
      const state = new SelfState(cwd, config)
      state.agent = agent
      state.loadInitial()
      // Existing sessions do not re-run agent/created on plugin reload; make the repair tool
      // available and mount the missing host tools in place.
      registerSelfTools(agent.ctx, state)
      try {
        await state.repairHostTools(agent.ctx)
      } catch {
        // Repair is best-effort; status/repair tool reports the detail.
      }
    }
  }
  const repairTimer = setTimeout(() => {
    repairLiveAgents().catch(() => {})
  }, 800)
  repairTimer.unref?.()

  ctx.on('agent/created', async ({ agent }) => {
    const cwd = agent?.session?.header?.cwd
    if (!cwd) return
    const state = new SelfState(cwd, config)
    state.agent = agent
    state.loadInitial()

    /**
     * 旧 `persona` 模块注册的 global complete 段（名字就叫 bma3-persona）当年没有保存
     * `systemPrompt.section()` 返回的 disposer，所以插件重载它也卸载不掉。结果是：
     * 老会话里留着旧的 complete 段，重载后 self 又挂一个 complete 段
     * ⇒ DSH 判定 "multiple complete prompt sections are active"，每一轮都失败。
     *
     * 这里在 **agent scope** 注册一个同名空段（非 complete）。DSH 规定 scoped section
     * 遮蔽同名 global section，于是旧段失效，只剩 deployment:persona-prefix 一个 complete。
     * 新进程里它只是一个空段，无副作用。
     */
    const mountLegacyShadow = () => {
      const systemPrompt = agent.ctx?.systemPrompt
      if (!systemPrompt?.section) return
      try {
        agent.ctx.effect?.(() => systemPrompt.section({
          name: LEGACY_PERSONA_SECTION,
          order: 0,
          text: '',
          complete: false,
          interpolate: false,
        }), 'bma3-self.legacy-shadow')
      } catch (error) {
        state.errors.prompt = `旧 persona 兼容段挂载失败：${error.message}`
      }
    }

    let sectionDisposer = null
    const mountPrompt = () => {
      if (!agent.ctx?.systemPrompt?.section) return
      if (sectionDisposer) {
        try {
          sectionDisposer()
        } catch {
          // Agent disposal may have already unwound the section.
        }
        sectionDisposer = null
      }
      // systemprompt.md 缺失/为空 ⇒ 不挂任何 section，绝不注入内置替身。
      if (!state.promptText()) return
      const order = agent.ctx.systemPrompt.getSectionOrder
        ? agent.ctx.systemPrompt.getSectionOrder('DEPLOYMENT_PERSONA_PREFIX')
        : 0
      sectionDisposer = agent.ctx.effect?.(() => agent.ctx.systemPrompt.section({
        name: PERSONA_SECTION,
        order,
        text: () => state.promptText() ?? '',
        complete: state.config.prompt.complete,
        interpolate: false,
      }), 'bma3-self.prompt') ?? null
    }
    mountPrompt()
    mountLegacyShadow()
    state.onReload = () => mountPrompt()

    registerSelfTools(agent.ctx, state)
    state.applyToolPolicy(agent.ctx)
    state.scheduleRepair(agent.ctx, 500)
    agent.ctx?.on?.('tools/change', () => {
      state.applyToolPolicy(agent.ctx)
      state.scheduleRepair(agent.ctx, 250)
    })
  })
}
