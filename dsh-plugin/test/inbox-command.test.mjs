import assert from 'node:assert/strict'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'

import { apply, registerInboxCommand, renderInboxStatus } from '../lib/inbox.js'

/**
 * Contract: docs/superpowers/specs/2026-11-13-dsh-inbox-slash-command-design.md
 *
 * The slash command exists because the Agent is often the very session being pushed to:
 * asking it to "please call bma3_inbox status" is a bootstrap deadlock. `/inbox` renders
 * straight to the UI and never enters the model context.
 */

/**
 * Minimal host stub with Cordis semantics that matter here:
 *   - `ctx.commands` is THROWN on when not injected (that is the real proxy's behaviour)
 *   - the optional-dependency pattern is `ctx.inject(deps, callback)`, which runs the callback
 *     in a child fiber only once the services exist
 */
function makeCtx({ withCommands = true } = {}) {
  const definitions = []
  const childDisposers = []
  const injected = []
  const registry = {
    register: (definition) => {
      definitions.push(definition)
      return () => {
        const index = definitions.indexOf(definition)
        if (index >= 0) definitions.splice(index, 1)
      }
    },
  }
  const ctx = {
    tools: { register: () => () => {} },
    agents: { get: () => undefined, list: () => [] },
    effect: (callback) => callback(),
    logger: () => ({ warn: () => {}, info: () => {} }),
    inject: (deps, callback) => {
      injected.push(deps)
      if (withCommands) {
        const child = {
          commands: registry,
          effect: (cb) => { const disposer = cb(); childDisposers.push(disposer); return disposer },
        }
        callback(child)
      }
      return {
        dispose: () => { for (const disposer of childDisposers.splice(0)) disposer?.() },
      }
    },
  }
  return { ctx, definitions, injected }
}

/** The command needs a client; a stub keeps these tests about grammar and wording. */
function makeClient(overrides = {}) {
  const calls = []
  const state = {
    inboxId: 'bma3',
    running: true,
    stopped: false,
    connection: 'open',
    targetSessionId: 'sess-7f3a2b1c-9999',
    paused: false,
    injectedPushRevision: 42,
    lastPushRevision: 42,
    ackedRevision: 42,
    pendingRevision: 0,
    lastMcpStartId: 'mcp-1',
    stateFile: '/home/x/.dsh/storages/bma3-inbox.json',
    config: { baseUrl: 'http://127.0.0.1:46500', tokenSource: 'env:BMA3_INBOX_TOKEN' },
    ...overrides.status,
  }
  const client = {
    status: () => ({ ...state }),
    bind: (id) => { calls.push(['bind', id]); state.targetSessionId = id; return client.status() },
    unbind: () => { calls.push(['unbind']); state.targetSessionId = null; return client.status() },
    restore: async (id) => { calls.push(['restore', id]); return client.status() },
    pause: () => { calls.push(['pause']); state.paused = true; return client.status() },
    resume: () => { calls.push(['resume']); state.paused = false; return client.status() },
    start: () => { calls.push(['start']); state.running = true; state.stopped = false },
    stop: () => { calls.push(['stop']); state.running = false; state.stopped = true },
    ack: async () => { calls.push(['ack']) },
    ...overrides.methods,
  }
  return { client, calls, state }
}

function invocation(rawInput, sessionId = 'sess-7f3a2b1c-9999') {
  return { rawInput, agent: sessionId ? { id: sessionId } : undefined }
}

async function run(verb, opts = {}) {
  const { ctx, definitions, injected } = makeCtx(opts)
  const { client, calls } = makeClient(opts)
  registerInboxCommand(ctx, client)
  assert.deepEqual(injected[0], ['commands'], 'commands is an optional dependency')
  assert.equal(definitions.length, 1, 'one command definition registered')
  const definition = definitions[0]
  const result = await definition.handler(invocation(verb, opts.session))
  return { result, calls, definition, definitions }
}

// ---- A1 / A2 ----

test('/inbox with no input renders status', async () => {
  const { result } = await run('')
  assert.equal(result.kind, 'success')
  assert.match(result.text, /绑定:/u)
  assert.match(result.text, /SSE:/u)
})

test('/inbox status renders binding, cursor and token source', async () => {
  const { result } = await run('status')
  assert.equal(result.kind, 'success')
  assert.match(result.text, /Inbox bma3 · 运行中/u)
  assert.match(result.text, /push=42 ack=42 待注入=0/u)
  assert.match(result.text, /env:BMA3_INBOX_TOKEN/u)
  assert.match(result.text, /本会话/u)
})

// ---- A3 / A4 ----

test('/inbox bind <id> binds that session', async () => {
  const { result, calls } = await run('bind sess-target-123456')
  assert.equal(result.kind, 'success')
  assert.deepEqual(calls[0], ['bind', 'sess-target-123456'])
  assert.match(result.text, /sess-tar…/u)
})

test('/inbox bind with no id uses the calling session', async () => {
  const { calls } = await run('bind', { session: 'sess-caller-abcdef' })
  assert.deepEqual(calls[0], ['bind', 'sess-caller-abcdef'])
})

test('/inbox bind without id or session is an error with usage', async () => {
  const { result, calls } = await run('bind', { session: null })
  assert.equal(result.kind, 'error')
  assert.match(result.text, /usage: \/inbox bind <sessionId>/u)
  assert.equal(calls.length, 0, 'must not bind anything without a target')
})

// ---- A5 / A6 ----

test('/inbox unbind clears the binding and warns about the idle fallback', async () => {
  const { result, calls } = await run('unbind')
  assert.equal(result.kind, 'success')
  assert.deepEqual(calls[0], ['unbind'])
  assert.match(result.text, /已解绑/u)
  assert.match(result.text, /第一个 idle 会话/u)
  assert.doesNotMatch(result.text, /\/inbox detach.*\/inbox detach/u)
})

test('/inbox detach unbinds and stops the SSE', async () => {
  const { result, calls } = await run('detach')
  assert.equal(result.kind, 'success')
  assert.deepEqual(calls.map((call) => call[0]), ['unbind', 'stop'])
  assert.match(result.text, /已解绑并停止/u)
})

// ---- A7 ----

test('/inbox with an unknown verb lists the available verbs', async () => {
  const { result } = await run('explode')
  assert.equal(result.kind, 'error')
  assert.match(result.text, /未知子命令: explode/u)
  assert.match(result.text, /status/u)
  assert.match(result.text, /unbind/u)
  assert.match(result.text, /detach/u)
})

// ---- DSH runtime contract ----

/**
 * Mirrors `normalizeDefinition` in @deepseek-ai/dsh-commands/lib/index.js. We cannot import
 * that package here (the plugin deliberately has no @deepseek-ai dependency), so the exact
 * rules are restated: a definition that violates any of them throws inside `register()` at
 * host startup, which would take the whole inbox module down.
 */
const DSH_COMMAND_NAME = /^[a-z][a-z0-9_-]*$/u

test('the definition satisfies DSH command metadata validation', async () => {
  const { definition } = await run('status')
  assert.match(definition.name, DSH_COMMAND_NAME)
  assert.equal(typeof definition.description, 'string')
  assert.ok(definition.description.trim().length > 0)
  assert.equal(typeof definition.handler, 'function')
  assert.equal(typeof definition.input.hint, 'string')
  assert.ok(definition.input.hint.trim().length > 0)
  if ('attachments' in definition.input) {
    assert.equal(typeof definition.input.attachments, 'boolean')
  }
})

test('the command name does not collide with a DSH builtin', async () => {
  const { definition } = await run('status')
  // builtins: goal / plan / feedback / compact / permission / export (+ zh aliases)
  const builtin = ['goal', 'plan', 'feedback', 'compact', 'permission', 'export']
  assert.ok(!builtin.includes(definition.name))
})

// ---- A8 ----

test('a host without a commands service still mounts the inbox tool', () => {
  const { ctx, definitions } = makeCtx({ withCommands: false })
  assert.doesNotThrow(() => apply(ctx, { persistState: false, autoStart: false }))
  assert.equal(definitions.length, 0)
})

/**
 * Regression for the 0.0.5-hotfix1 failure reported from a real host:
 *   Error: cannot get property "commands" without inject
 * The old code read `ctx.commands` first; Cordis throws instead of returning undefined, so the
 * throw escaped `apply()` and took the WHOLE inbox module down (no bma3_inbox tool at all).
 */
test('a Cordis-style ctx that throws on undeclared services still mounts the module', () => {
  const { ctx, definitions } = makeCtx()
  Object.defineProperty(ctx, 'commands', {
    get() { throw new Error('cannot get property "commands" without inject') },
  })
  assert.doesNotThrow(() => apply(ctx, { persistState: false, autoStart: false }))
  assert.equal(definitions.length, 1, '/inbox still registers through ctx.inject')
})

test('a throwing ctx.inject cannot take the module down', () => {
  const { ctx } = makeCtx()
  ctx.inject = () => { throw new Error('boom') }
  assert.doesNotThrow(() => apply(ctx, { persistState: false, autoStart: false }))
})

// ---- A9 ----

test('the command definition is disposable so reloads do not stack', () => {
  const { ctx, definitions } = makeCtx()
  const { client } = makeClient()
  const fork = registerInboxCommand(ctx, client)
  assert.equal(definitions.length, 1)
  fork.dispose()
  assert.equal(definitions.length, 0)
})

// ---- A10 ----

test('status never echoes the token value', () => {
  const text = renderInboxStatus(
    makeClient().client.status(),
    'sess-7f3a2b1c-9999',
  )
  assert.match(text, /env:BMA3_INBOX_TOKEN/u)
  assert.doesNotMatch(text, /Bearer/u)
  assert.doesNotMatch(text, /secret-token-value/u)
})

// ---- A11 ----

test('unbound status reminds about the first-idle fallback', () => {
  const text = renderInboxStatus({
    inboxId: 'bma3',
    running: true,
    stopped: false,
    connection: 'open',
    targetSessionId: null,
    config: { baseUrl: 'http://127.0.0.1:46500', tokenSource: 'config' },
  })
  assert.match(text, /绑定:     未绑定/u)
  assert.match(text, /第一个 idle 会话/u)
})
