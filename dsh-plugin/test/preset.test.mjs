import assert from 'node:assert/strict'
import { test } from 'node:test'

import { apply, presetDefinition } from '../lib/preset.js'

test('preset defines the BMA3 agent preset and can register it', () => {
  const definition = presetDefinition({ prompt: '角色提示' })
  assert.equal(definition.id, 'bma3')
  assert.equal(definition.plugins[0].config.prefix, '角色提示')

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

  apply(ctx, { prompt: '角色提示' })
  assert.equal(registered.length, 1)
  assert.equal(registered[0].id, 'bma3')
})
