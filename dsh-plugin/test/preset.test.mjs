import assert from 'node:assert/strict'
import { test } from 'node:test'

import { apply, presetDefinition } from '../lib/preset.js'

test('preset mounts the self loader and the default tool face', () => {
  const definition = presetDefinition()
  assert.equal(definition.id, 'bma3')

  const byId = (id) => definition.plugins.find((row) => row.id === id)
  assert.equal(byId('self').config.module, 'self')

  const fs = byId('tool-fs')
  assert.equal(fs.config.readImage, true)
  assert.equal(fs.config.read, false)
  assert.equal(fs.config.write, false)
  assert.equal(fs.config.edit, false)

  const spawn = byId('tool-subagent')
  assert.equal(spawn.config.agentPreset, 'minimal')
  assert.equal(spawn.config.toolName, 'subagent')

  assert.equal(byId('tool-subagent-fork'), undefined)

  assert.ok(definition.plugins.some((row) => row.id === 'tool-bash'))
  assert.ok(definition.plugins.some((row) => row.id === 'tool-pwsh'))
  assert.ok(definition.plugins.some((row) => row.id === 'tool-subagent-control'))
  assert.ok(!definition.plugins.some((row) => row.name === '@deepseek-ai/dsh-persona'))
})

test('preset can be registered', () => {
  const registered = []
  const ctx = {
    agentPresets: {
      register(value) {
        registered.push(value)
        return () => {}
      },
    },
    effect(fn) {
      return fn()
    },
  }

  apply(ctx)
  assert.equal(registered.length, 1)
  assert.equal(registered[0].id, 'bma3')
})

test('preset reads agentPresets through ctx.get, not the uninjected property', () => {
  const registered = []
  const ctx = {
    get(name) {
      return name === 'agentPresets'
        ? { register(value) { registered.push(value); return () => {} } }
        : undefined
    },
    effect(fn) { return fn() },
  }
  Object.defineProperty(ctx, 'agentPresets', {
    get() { throw new Error('cannot get property "agentPresets" without inject') },
  })

  apply(ctx)
  assert.equal(registered.length, 1)
  assert.equal(registered[0].id, 'bma3')
})
