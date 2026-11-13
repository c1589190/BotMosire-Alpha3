/**
 * Verifies the `/inbox` slash command against the REAL DSH command registry
 * (`@deepseek-ai/dsh-commands` + a real Cordis Context), rather than a hand-written stub.
 *
 * Why: `dsh-plugin` deliberately has no `@deepseek-ai/*` dependency, so the unit tests can only
 * restate the host's validation rules. This script loads the host's own package instead, which
 * catches the class of bug that would otherwise surface only when a host starts up and
 * `ctx.commands.register()` throws.
 *
 * Checks:
 *   1. the plugin's definition is accepted by the real `normalizeDefinition`
 *   2. `commands.find(agent, 'inbox')` resolves it, and `commands.list()` shows it
 *   3. the real `parseCommand('/inbox status')` produces name=`inbox`, rawInput=' status'
 *   4. the real registry's handler renders a status block
 *
 * Usage:
 *   DSH_PNPM_STORE=/path/to/pnpm/global/v11/<hash>/node_modules/.pnpm \
 *       node tools/e2e-local/dsh-inbox-command-check.mjs
 *
 * The store path is deployment-specific; find it with:
 *   ps -eo args | grep '[d]sh/lib/bin.js'   # prints .../global/v11/<hash>/node_modules/@deepseek-ai/dsh/lib/bin.js
 */
import { existsSync, readdirSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const PLUGIN = new URL('../../dsh-plugin/lib/inbox.js', import.meta.url)

/** Every pnpm virtual store on this machine; a host install keeps one per global hash. */
function candidateStores() {
  if (process.env.DSH_PNPM_STORE) return [process.env.DSH_PNPM_STORE]
  const root = join(homedir(), '.local', 'share', 'pnpm', 'global')
  if (!existsSync(root)) return []
  const stores = []
  for (const version of readdirSync(root)) {
    const globalDir = join(root, version)
    if (!existsSync(globalDir)) continue
    for (const hash of readdirSync(globalDir)) {
      const candidate = join(globalDir, hash, 'node_modules', '.pnpm')
      if (existsSync(candidate)) stores.push(candidate)
    }
  }
  return stores
}

/** The store that actually holds @deepseek-ai/dsh-commands (not every global hash does). */
function findStore(stores) {
  return stores.find((store) => readdirSync(store).some(
    (name) => name.startsWith('@deepseek-ai+dsh-commands@')))
}

/** pnpm suffixes package dirs with a peer-resolution hash, so match by prefix. */
function resolveInStore(store, prefix, tail) {
  const match = readdirSync(store).filter((name) => name.startsWith(prefix)).sort().pop()
  if (!match) throw new Error(`not found in store: ${prefix}*`)
  return join(store, match, 'node_modules', ...tail)
}

const store = findStore(candidateStores())
if (!store) {
  console.error('cannot locate a pnpm store holding @deepseek-ai/dsh-commands; '
    + 'set DSH_PNPM_STORE (see header)')
  process.exit(2)
}

const cordisPath = resolveInStore(store, '@deepseek-ai+cordis@', ['@deepseek-ai', 'cordis', 'lib', 'index.js'])
const commandsPath = resolveInStore(store, '@deepseek-ai+dsh-commands@', ['@deepseek-ai', 'dsh-commands', 'lib', 'index.js'])

const { Context } = await import(pathToFileURL(cordisPath).href)
const commandsModule = await import(pathToFileURL(commandsPath).href)
const CommandRuntime = commandsModule.default
const { parseCommand } = commandsModule
const { registerInboxCommand } = await import(PLUGIN.href)

const results = []
function check(name, ok, detail = '') {
  results.push(Boolean(ok))
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  | ${detail}` : ''}`)
}

const ctx = new Context()
ctx.plugin(CommandRuntime)
await new Promise((resolve) => setTimeout(resolve, 500))
check('real commands service mounts', typeof ctx.commands?.register === 'function')

const client = {
  status: () => ({
    inboxId: 'bma3', running: true, stopped: false, connection: 'open',
    targetSessionId: 'sess-abc123456', paused: false, injectedPushRevision: 42,
    ackedRevision: 42, pendingRevision: 0, lastMcpStartId: 'mcp-1',
    stateFile: '/home/x/.dsh/storages/bma3-inbox.json',
    config: { baseUrl: 'http://127.0.0.1:46500', tokenSource: 'env:BMA3_INBOX_TOKEN' },
  }),
  bind: () => client.status(), unbind: () => client.status(),
  restore: async () => client.status(), pause: () => client.status(),
  resume: () => client.status(), start: () => {}, stop: () => {}, ack: async () => {},
}

// Run the registration from inside a plugin fiber that does NOT inject `commands`, which is
// exactly the shape of the real host and the reason 0.0.5-hotfix1 threw
// "cannot get property "commands" without inject" and took the whole module down.
let registerError = null
const probeFiber = ctx.plugin({
  name: 'bma3-inbox-probe',
  inject: [],
  apply(fiberCtx) {
    try {
      registerInboxCommand(fiberCtx, client)
    } catch (error) {
      registerError = error
    }
  },
})
await new Promise((resolve) => setTimeout(resolve, 600))
check('registering from a fiber without `commands` in inject does not throw',
  registerError === null, registerError ? String(registerError.message) : '')

const agent = { id: 'sess-abc123456' }
const definition = ctx.commands?.find(agent, 'inbox')
check('commands.find(agent, "inbox") resolves it', Boolean(definition))
check('commands.list() exposes it', ctx.commands.list(agent).map((row) => row.name).includes('inbox'),
  ctx.commands.list(agent).map((row) => row.name).join(','))

const parsed = parseCommand('/inbox status')
check('parseCommand splits "/inbox status"', parsed.name === 'inbox' && parsed.rawInput.trim() === 'status',
  JSON.stringify(parsed))

const rendered = await definition.handler({
  rawInput: parsed.rawInput, agent, attachments: [], signal: new AbortController().signal,
})
check('handler renders a status block', rendered.kind === 'success'
  && /绑定:/.test(rendered.text) && /SSE:/.test(rendered.text))
check('status text does not leak the token value', !/Bearer/.test(rendered.text)
  && /env:BMA3_INBOX_TOKEN/.test(rendered.text))

// Disposing the owning plugin fiber must take the command with it (no duplicate on reload).
if (typeof probeFiber?.dispose === 'function') {
  await probeFiber.dispose()
  await new Promise((resolve) => setTimeout(resolve, 300))
  check('disposing the plugin fiber unregisters the command',
    ctx.commands.find(agent, 'inbox') === undefined)
} else {
  check('disposing the plugin fiber unregisters the command', false, `plugin() returned ${typeof probeFiber}`)
}

const passed = results.filter(Boolean).length
console.log(`\n${passed}/${results.length} checks passed`)
process.exit(passed === results.length ? 0 : 1)
