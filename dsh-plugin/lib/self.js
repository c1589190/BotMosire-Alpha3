import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'

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
      if (!matchByBase((name) => BASIC_BMA3_BASENAMES.has(name) || name.startsWith('qq_'))) missing.add(group)
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
    const presetOnly = new Set(PRESET_PROVIDED_GROUPS)
    for (const name of LOCKED_TOOL_BASENAMES) presetOnly.add(name)
    return {
      ok: Object.values(this.errors).every((item) => item == null),
      cwd: this.cwd,
      promptPath: this.promptPath,
      promptHash: hash(this.prompt || ''),
      promptComplete: this.config.prompt.complete,
      configPath: this.configPath,
      configHash: hash(JSON.stringify(this.config)),
      defaultTools: this.config.defaultTools,
      allow: resolved.allow,
      missingGroups: resolved.missing.filter((name) => !presetOnly.has(name)),
      presetProvidedGroups: [...PRESET_PROVIDED_GROUPS],
      presetLockedTools: [...LOCKED_TOOL_BASENAMES],
      globalToolCount: names.length,
      lockedTools: resolved.allow.filter((name) => LOCKED_TOOL_BASENAMES.includes(baseName(name))),
      error: Object.values(this.errors).filter(Boolean).join(' | ') || null,
    }
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

  reload(agentCtx) {
    this.errors = { config: null, prompt: null, tools: null }
    try {
      this.config = this.readConfig()
    } catch (error) {
      this.errors.config = `重载失败：${error.message}`
      return this.status(agentCtx)
    }
    this.promptText(true)
    this.applyToolPolicy(agentCtx, { force: true })
    this.onReload?.()
    return this.status(agentCtx)
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
      execute: async () => ({ text: JSON.stringify(state.reload(agentCtx), null, 2) }),
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

export function apply(ctx, _config = {}) {
  if (!ctx?.on) return
  ctx.on('agent/created', async ({ agent }) => {
    const cwd = agent?.session?.header?.cwd
    if (!cwd) return
    const state = new SelfState(cwd)
    state.loadInitial()

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
        name: 'deployment:persona-prefix',
        order,
        text: () => state.promptText() ?? '',
        complete: state.config.prompt.complete,
        interpolate: false,
      }), 'bma3-self.prompt') ?? null
    }
    mountPrompt()
    state.onReload = () => mountPrompt()

    registerSelfTools(agent.ctx, state)
    state.applyToolPolicy(agent.ctx)
    agent.ctx?.on?.('tools/change', () => state.applyToolPolicy(agent.ctx))
  })
}
