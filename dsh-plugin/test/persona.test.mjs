import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { test } from 'node:test'

import {
  PERSONA_SECTION,
  defaultPromptFile,
  apply,
  readPrompt,
  resolvePromptFile,
} from '../lib/persona.js'

/** Minimal ctx recording what the persona row registers. */
function fakeCtx() {
  const sections = []
  const effects = []
  return {
    sections,
    effects,
    systemPrompt: {
      getSectionOrder: (name) => (name === 'DEPLOYMENT_PERSONA_PREFIX' ? -50 : 0),
      section(value) {
        sections.push(value)
        return () => {}
      },
    },
    effect(fn) {
      effects.push(fn)
      return fn()
    },
  }
}

function tempPrompt(text) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bma3-persona-'))
  const file = path.join(dir, 'systemprompt.md')
  fs.writeFileSync(file, text)
  return file
}

test('default prompt file honours DSH_HOME', () => {
  assert.equal(defaultPromptFile({ DSH_HOME: '/opt/dsh' }), path.join('/opt/dsh', 'bma3', 'systemprompt.md'))
  assert.equal(defaultPromptFile({}), path.join(os.homedir(), '.dsh', 'bma3', 'systemprompt.md'))
})

test('resolvePromptFile expands ~ and relative paths, ignores blank config', () => {
  assert.equal(resolvePromptFile({ promptFile: '~/x.md' }), path.join(os.homedir(), 'x.md'))
  assert.equal(resolvePromptFile({ promptFile: '   ' }, { DSH_HOME: '/opt/dsh' }),
    path.join('/opt/dsh', 'bma3', 'systemprompt.md'))
  assert.equal(resolvePromptFile({ promptFile: 'rel.md' }), path.resolve('rel.md'))
})

test('a missing or blank prompt registers no section at all', () => {
  const ctx = fakeCtx()
  assert.equal(apply(ctx, { promptFile: '/nonexistent/nope.md' }), undefined)
  assert.equal(ctx.sections.length, 0, '绝不注入内置替身')

  const blank = tempPrompt('   \n\n')
  assert.equal(apply(fakeCtx(), { promptFile: blank }), undefined)
})

test('the persona shadows the deployment prefix and is complete by default', () => {
  const file = tempPrompt('# 我是谁\n你是 BMA3。')
  const ctx = fakeCtx()
  apply(ctx, { promptFile: file })

  assert.equal(ctx.sections.length, 1)
  const section = ctx.sections[0]
  assert.equal(section.name, PERSONA_SECTION)
  assert.equal(section.name, 'deployment:persona-prefix')
  assert.equal(section.order, -50)
  assert.equal(section.complete, true)
  assert.equal(section.interpolate, false)
  assert.equal(section.text(), '# 我是谁\n你是 BMA3。')
  assert.equal(ctx.effects.length, 1, 'section disposer is wired to the row effect')
})

test('complete:false opts back into additive prompting', () => {
  const file = tempPrompt('附加人设')
  const ctx = fakeCtx()
  apply(ctx, { promptFile: file, complete: false })
  assert.equal(ctx.sections[0].complete, false)
})

test('the text provider re-reads the file so edits apply without a reload', () => {
  const file = tempPrompt('第一版')
  const ctx = fakeCtx()
  apply(ctx, { promptFile: file })
  const section = ctx.sections[0]
  assert.equal(section.text(), '第一版')

  fs.writeFileSync(file, '第二版')
  assert.equal(section.text(), '第二版')

  // A transient read failure must not blank a complete section.
  fs.rmSync(file)
  assert.equal(section.text(), '第二版')
})

test('readPrompt never throws', () => {
  assert.equal(readPrompt('/definitely/not/here.md'), '')
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bma3-persona-dir-'))
  assert.equal(readPrompt(dir), '', 'a directory is not a prompt')
})

test('a ctx without systemPrompt is a no-op', () => {
  assert.equal(apply({}, { promptFile: tempPrompt('x') }), undefined)
  assert.equal(apply(undefined, {}), undefined)
})
