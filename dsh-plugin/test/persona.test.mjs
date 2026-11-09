import assert from 'node:assert/strict'
import { test } from 'node:test'

import { apply, promptFor } from '../lib/persona.js'

test('persona registers a complete system prompt section', () => {
  const sections = []
  const ctx = {
    systemPrompt: {
      section(section) {
        sections.push(section)
        return () => {}
      },
    },
    effect(fn) {
      return fn()
    },
  }

  apply(ctx, { prompt: '你是 BMA3 的角色。' })

  assert.equal(promptFor({}), '你是 BMA3 的角色。')
  assert.equal(promptFor({ prompt: '自定义' }), '自定义')
  assert.equal(sections.length, 1)
  assert.equal(sections[0].text, '你是 BMA3 的角色。')
  assert.equal(sections[0].complete, true)
})
